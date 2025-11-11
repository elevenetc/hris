package org.jetbrains.hris.employee

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.common.exceptions.RepositoryException
import org.jetbrains.hris.common.exceptions.notFoundException
import org.jetbrains.hris.common.exceptions.repositoryException
import org.jetbrains.hris.common.models.Employee
import org.jetbrains.hris.common.models.EmployeeHomepage
import org.jetbrains.hris.db.schemas.EmployeePath
import org.jetbrains.hris.db.schemas.Employees
import org.jetbrains.hris.db.utils.LTree
import org.jetbrains.hris.db.utils.LTreeColumnType
import org.jetbrains.hris.db.utils.LtreeContainedIn
import org.jetbrains.hris.db.utils.buildLTree

/**
 * Basic repository for adding and removing employees while maintaining the ltree path table.
 *
 * Removal strategy provided here is subtree deletion: removing an employee deletes the employee
 * and all of their descendants. This keeps the materialized paths consistent without needing to
 * recompute paths for orphaned subtrees.
 */
class EmployeeRepository {

    /**
     * Adds a new employee. If [managerId] is null, the employee becomes a root under `company`.
     * Returns the generated employee id.
     *
     * @throws RepositoryException if the operation fails (wraps PSQLException and other errors)
     */
    fun addEmployee(
        firstName: String,
        lastName: String,
        email: String,
        position: String?,
        managerId: Long?
    ): Long = try {
        transaction {
            val newId = Employees.insertAndGetId { row ->
                row[Employees.firstName] = firstName
                row[Employees.lastName] = lastName
                row[Employees.email] = email
                row[Employees.position] = position
                row[Employees.managerId] = managerId?.let { EntityID(it, Employees) }
            }.value

            val path = buildLTree(newId, managerId)

            EmployeePath.insert { ep ->
                ep[employeeId] = EntityID(newId, Employees)
                ep[EmployeePath.path] = path
                // nlevel approximation: number of segments = dots + 1
                ep[EmployeePath.depth] = path.value.count { it == '.' } + 1
            }

            newId
        }
    } catch (e: Exception) {
        repositoryException("Failed to create employee", e)
    }

    /**
     * Returns the direct manager employee for the given [employeeId].
     * - If the employee is a root (no manager) or does not exist, returns null.
     */
    fun getManagerOf(employeeId: Long): Employee? = transaction {
        val managerId = Employees
            .selectAll()
            .where { Employees.id eq EntityID(employeeId, Employees) }
            .limit(1)
            .singleOrNull()
            ?.get(Employees.managerId)
            ?.value
            ?: return@transaction null

        getEmployeeById(managerId)
    }

    /**
     * Returns a list of direct subordinate employees for the given [managerId].
     * If the manager has no direct reports or does not exist, returns an empty list.
     */
    fun getSubordinates(managerId: Long): List<Employee> = transaction {
        querySubordinatesTx(managerId)
    }

    /**
     * Deletes an employee and the entire subtree beneath them.
     * Returns number of employees removed.
     */
    fun removeEmployeeWithSubordinates(employeeId: Long): Int = transaction {
        // Fetch the path of the employee being removed
        val targetPath = EmployeePath
            .selectAll()
            .where { EmployeePath.employeeId eq EntityID(employeeId, Employees) }
            .limit(1)
            .singleOrNull()?.get(EmployeePath.path)
            ?: return@transaction 0

        // Collect all descendant employee IDs (including self)
        val ids = EmployeePath
            .selectAll()
            .where { LtreeContainedIn(EmployeePath.path, QueryParameter(targetPath, LTreeColumnType)) }
            .map { it[EmployeePath.employeeId].value }

        if (ids.isEmpty()) return@transaction 0

        // Delete all employees in the subtree; employee_path will cascade
        val idEntities = ids.map { EntityID(it, Employees) }
        Employees.deleteWhere { Employees.id inList idEntities }
    }

    /**
     * Deletes an employee only if they have no direct reports. Returns true if deleted, false otherwise.
     */
    fun removeEmployeeWithoutSubordinates(employeeId: Long): Boolean = transaction {
        val hasChildren = Employees
            .selectAll()
            .where { Employees.managerId eq EntityID(employeeId, Employees) }
            .empty().not()

        if (hasChildren) return@transaction false

        val deleted = Employees.deleteWhere { Employees.id eq EntityID(employeeId, Employees) }
        // employee_path row is removed via ON DELETE CASCADE
        deleted > 0
    }

    /**
     * Changes the manager of an employee. Updates the employee's manager_id and
     * recalculates the ltree path for the employee and all their descendants.
     *
     * All validations and updates are performed in a single transaction to ensure atomicity.
     *
     * @param employeeId The employee whose manager should be changed
     * @param newManagerId The new manager ID, or null to make the employee a root
     * @return The old manager ID (or null if employee was a root)
     * @throws RepositoryException if the operation fails, employee not found, or new manager not found
     * @see org.jetbrains.hris.db.utils.LTree for implementation notes
     */
    fun changeManager(employeeId: Long, newManagerId: Long?): Long? = try {
        transaction {
            // 1. Verify employee exists and get current manager ID
            val employee = Employees
                .selectAll()
                .where { Employees.id eq EntityID(employeeId, Employees) }
                .limit(1)
                .singleOrNull()
                ?: notFoundException("Employee not found with ID: $employeeId")

            val oldManagerId = employee[Employees.managerId]?.value

            // 2. Verify new manager exists if not null
            if (newManagerId != null) {
                val managerExists = Employees
                    .selectAll()
                    .where { Employees.id eq EntityID(newManagerId, Employees) }
                    .empty().not()

                if (!managerExists) {
                    notFoundException("Manager not found with ID: $newManagerId")
                }
            }

            // 3. Update manager_id
            Employees.update({ Employees.id eq EntityID(employeeId, Employees) }) { row ->
                row[managerId] = newManagerId?.let { EntityID(it, Employees) }
            }

            // 4. Get the old path and calculate the new path
            val oldPath = EmployeePath
                .selectAll()
                .where { EmployeePath.employeeId eq EntityID(employeeId, Employees) }
                .limit(1)
                .single()[EmployeePath.path]

            val newPath = buildLTree(employeeId, newManagerId)

            // 5. Update the employee's path
            EmployeePath.update({ EmployeePath.employeeId eq EntityID(employeeId, Employees) }) { row ->
                row[path] = newPath
                row[depth] = newPath.value.count { it == '.' } + 1
            }

            // 6. Update all descendant paths by replacing the old path prefix with the new one
            val descendants = EmployeePath
                .selectAll()
                .where { LtreeContainedIn(EmployeePath.path, QueryParameter(oldPath, LTreeColumnType)) }
                .andWhere { EmployeePath.employeeId neq EntityID(employeeId, Employees) }
                .toList()

            descendants.forEach { descendantRow ->
                val descendantPath = descendantRow[EmployeePath.path]
                val descendantId = descendantRow[EmployeePath.employeeId]

                // Replace the old path prefix with the new path
                val relativePath = descendantPath.value.removePrefix(oldPath.value)
                val updatedPath = LTree(newPath.value + relativePath)

                EmployeePath.update({ EmployeePath.employeeId eq descendantId }) { row ->
                    row[path] = updatedPath
                    row[depth] = updatedPath.value.count { it == '.' } + 1
                }
            }

            oldManagerId
        }
    } catch (e: Exception) {
        when (e) {
            is RepositoryException -> throw e
            else -> repositoryException("Failed to change manager for employee $employeeId", e)
        }
    }

    /**
     * Fetch a single employee by id or return null if not found.
     */
    fun getEmployeeById(id: Long): Employee? = transaction {
        queryEmployeeTx(id)
    }

    /**
     * Returns the organizational tree starting from the given [managerId].
     * The manager becomes the root of the tree with all subordinates nested recursively.
     * Returns null if the employee does not exist.
     */
    fun getEmployeeTree(managerId: Long): Employee? = transaction {
        // First verify the manager exists
        val managerExists = Employees
            .selectAll()
            .where { Employees.id eq EntityID(managerId, Employees) }
            .empty().not()

        if (!managerExists) return@transaction null

        // Get the path of the manager
        val managerPath = EmployeePath
            .selectAll()
            .where { EmployeePath.employeeId eq EntityID(managerId, Employees) }
            .limit(1)
            .single()[EmployeePath.path]

        // Query all employees in the subtree (including the manager)
        val subtreeEmployees = EmployeePath
            .join(Employees, JoinType.INNER, EmployeePath.employeeId, Employees.id)
            .selectAll()
            .where { LtreeContainedIn(EmployeePath.path, QueryParameter(managerPath, LTreeColumnType)) }
            .map { it.toEmployee() }

        // Build maps for efficient tree construction
        val employeeMap = subtreeEmployees.associateBy { it.id }
        val subordinatesMap = subtreeEmployees
            .filter { it.managerId != null }
            .groupBy { it.managerId!! }

        // Recursively build the tree
        fun buildNode(empId: Long): Employee? {
            val emp = employeeMap[empId] ?: return null
            val subs = subordinatesMap[empId]?.mapNotNull { buildNode(it.id) } ?: emptyList()
            return Employee(
                id = emp.id,
                firstName = emp.firstName,
                lastName = emp.lastName,
                email = emp.email,
                position = emp.position,
                managerId = emp.managerId,
                subordinates = subs
            )
        }

        buildNode(managerId)
    }

    /**
     * Returns homepage data for an employee: the employee, their manager, peers, and subordinates.
     * Returns null if the employee does not exist.
     *
     * Optimized to fetch all data in a single transaction with minimal queries.
     */
    fun getEmployeeHomepage(employeeId: Long): EmployeeHomepage? = transaction {
        // 1. Get the employee
        val employee = queryEmployeeTx(employeeId) ?: return@transaction null

        // 2. Get the manager (if employee has one)
        val managerId = employee.managerId
        val manager = if (managerId != null) {
            queryEmployeeTx(managerId)
        } else null

        // 3. Get peers (employees with the same manager, excluding the employee themselves)
        val peers = if (managerId != null) {
            Employees
                .selectAll()
                .where {
                    (Employees.managerId eq EntityID(managerId, Employees)) and
                            (Employees.id neq EntityID(employeeId, Employees))
                }
                .map { it.toEmployee() }
        } else {
            emptyList()
        }

        // 4. Get subordinates (direct reports)
        val subordinates = querySubordinatesTx(employeeId)

        EmployeeHomepage(
            employee = employee,
            manager = manager,
            peers = peers,
            subordinates = subordinates
        )
    }
}

private fun queryEmployeeTx(id: Long): Employee? = Employees
    .selectAll()
    .where { Employees.id eq EntityID(id, Employees) }
    .singleOrNull()
    ?.toEmployee()

private fun querySubordinatesTx(managerId: Long): List<Employee> = Employees
    .selectAll()
    .where { Employees.managerId eq EntityID(managerId, Employees) }
    .map { it.toEmployee() }

private fun ResultRow.toEmployee() = Employee(
    id = this[Employees.id].value,
    firstName = this[Employees.firstName],
    lastName = this[Employees.lastName],
    email = this[Employees.email],
    position = this[Employees.position],
    managerId = this[Employees.managerId]?.value
)