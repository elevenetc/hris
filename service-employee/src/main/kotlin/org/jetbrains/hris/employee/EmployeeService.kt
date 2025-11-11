package org.jetbrains.hris.employee

import org.jetbrains.hris.common.events.ManagerChangedEvent
import org.jetbrains.hris.common.events.NewDirectReportEvent
import org.jetbrains.hris.common.exceptions.*
import org.jetbrains.hris.common.models.Employee
import org.jetbrains.hris.common.models.EmployeeHomepage
import org.jetbrains.hris.infrastructure.cache.Cache
import org.jetbrains.hris.infrastructure.cache.getTyped
import org.jetbrains.hris.infrastructure.events.EventBus
import org.postgresql.util.PSQLException

/**
 * Service layer for employee operations.
 *
 * Responsibilities:
 * - Orchestrate business logic
 * - Coordinate between repositories
 * - Publish domain events
 * - Transaction boundaries
 *
 * This layer sits between API and Repository:
 * API → Service → Repository
 *          ↓
 *       EventBus
 */
class EmployeeService(
    private val employeeRepo: EmployeeRepository,
    private val eventBus: EventBus,
    private val cache: Cache
) {

    /**
     * Adds a new employee to the system.
     *
     * Publishes NewDirectReportEvent if the employee has a manager.
     *
     * @return The created employee ID
     * @throws ConflictException if an employee with the same email already exists
     * @throws ValidationException if the manager ID is invalid
     * @throws RepositoryException if the operation fails for other reasons
     */
    fun addEmployee(
        firstName: String,
        lastName: String,
        email: String,
        position: String?,
        managerId: Long?
    ): Long {
        // 1. Create employee via repository
        val employeeId = try {
            employeeRepo.addEmployee(firstName, lastName, email, position, managerId)
        } catch (e: RepositoryException) {
            // Inspect the cause and throw appropriate domain exception
            when (val cause = e.cause) {
                is PSQLException if cause.message?.contains("employee_email_unique") == true ->
                    conflictException("An employee with email '$email' already exists")

                is PSQLException if (cause.message?.contains("foreign key constraint") == true ||
                        cause.message?.contains("violates foreign key") == true) ->
                    validationException("Invalid manager ID: $managerId")

                is NoSuchElementException -> validationException("Invalid manager ID: $managerId")
                else -> throw e  // Re-throw as RepositoryException
            }
        }

        // 2. Publish domain event if employee has a manager
        if (managerId != null) {
            eventBus.publish(
                NewDirectReportEvent(
                    managerId = managerId,
                    employeeId = employeeId,
                    employeeName = "$firstName $lastName"
                )
            )
        }

        // 3. Invalidate tree cache since org structure changed
        invalidateTreeCache()

        return employeeId
    }

    /**
     * Updates an employee's manager.
     *
     * All validation and updates are performed atomically in a single transaction.
     * Publishes ManagerChangedEvent to notify the employee.
     * Publishes NewDirectReportEvent to notify the new manager.
     *
     * @throws NotFoundException if the employee or new manager doesn't exist
     * @throws RepositoryException if the operation fails
     */
    fun changeManager(employeeId: Long, newManagerId: Long?) {
        // 1. Get current employee info for event publishing
        val employee = employeeRepo.getEmployeeById(employeeId)
            ?: notFoundException("Employee not found with ID: $employeeId")

        // 2. Update in repository (validates and returns old manager ID in single transaction)
        val oldManagerId = employeeRepo.changeManager(employeeId, newManagerId)

        // 3. Publish domain event
        eventBus.publish(
            ManagerChangedEvent(
                employeeId = employeeId,
                oldManagerId = oldManagerId,
                newManagerId = newManagerId,
                employeeName = "${employee.firstName} ${employee.lastName}"
            )
        )

        // 4. Notify new manager if exists
        if (newManagerId != null) {
            eventBus.publish(
                NewDirectReportEvent(
                    managerId = newManagerId,
                    employeeId = employeeId,
                    employeeName = "${employee.firstName} ${employee.lastName}"
                )
            )
        }

        // 5. Invalidate tree cache since org structure changed
        invalidateTreeCache()
    }

    /**
     * Gets an employee by ID.
     *
     * @return The employee or null if not found
     */
    fun getEmployeeById(employeeId: Long): Employee? {
        return employeeRepo.getEmployeeById(employeeId)
    }

    /**
     * Gets the direct manager of an employee.
     *
     * @return Manager employee or null if no manager
     */
    fun getManagerOf(employeeId: Long): Employee? {
        return employeeRepo.getManagerOf(employeeId)
    }

    /**
     * Gets direct subordinates of a manager.
     *
     * @return List of subordinate employees
     */
    fun getSubordinates(managerId: Long): List<Employee> {
        return employeeRepo.getSubordinates(managerId)
    }

    /**
     * Gets homepage data for an employee: the employee, manager, peers, and subordinates.
     *
     * This provides all organizational context needed for the employee's homepage view.
     *
     * @param employeeId The employee ID
     * @return Homepage data or null if employee not found
     */
    fun getEmployeeHomepage(employeeId: Long): EmployeeHomepage? {
        return employeeRepo.getEmployeeHomepage(employeeId)
    }

    /**
     * Gets the organizational tree starting from the given manager.
     * Returns the manager as the root with all subordinates nested recursively.
     *
     * This method uses caching to improve performance since org structures
     * don't change frequently.
     *
     * @param managerId The employee ID to use as the root of the tree
     * @return The employee tree or null if the employee does not exist
     */
    fun getSubordinatesFullTree(managerId: Long): Employee? {
        val cacheKey = "employee:subordinates-full-tree:$managerId"
        return cache.getTyped<Employee>(cacheKey) {
            employeeRepo.getEmployeeTree(managerId)
        }
    }

    /**
     * Removes an employee and their entire subtree of subordinates.
     *
     * @param employeeId The employee ID to remove
     * @return Number of employees removed (including the employee and all subordinates)
     * @throws NotFoundException if the employee doesn't exist
     */
    fun removeEmployeeWithSubordinates(employeeId: Long): Int {
        employeeRepo.getEmployeeById(employeeId)
            ?: notFoundException("Employee not found with ID: $employeeId")

        val removedCount = employeeRepo.removeEmployeeWithSubordinates(employeeId)
        if (removedCount > 0) invalidateTreeCache()
        return removedCount
    }

    /**
     * Removes an employee only if they have no direct reports.
     *
     * @param employeeId The employee ID to remove
     * @return true if the employee was removed, false if they have subordinates
     * @throws NotFoundException if the employee doesn't exist
     */
    fun removeEmployeeWithoutSubordinates(employeeId: Long): Boolean {
        employeeRepo.getEmployeeById(employeeId)
            ?: notFoundException("Employee not found with ID: $employeeId")

        val removed = employeeRepo.removeEmployeeWithoutSubordinates(employeeId)
        if (removed) invalidateTreeCache()
        return removed
    }

    /**
     * Invalidate employee tree cache when the organizational structure changes.
     * This should be called after operations that modify the org structure.
     */
    private fun invalidateTreeCache() {
        cache.deletePattern("employee:subordinates-full-tree:*")
    }
}
