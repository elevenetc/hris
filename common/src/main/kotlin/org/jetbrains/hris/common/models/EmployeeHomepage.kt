package org.jetbrains.hris.common.models

import kotlinx.serialization.Serializable

/**
 * Homepage data for an employee showing their organizational context.
 *
 * Contains:
 * - The employee themselves
 * - Their manager (if any)
 * - Their peers (colleagues with the same manager)
 * - Their direct reports (subordinates)
 */
@Serializable
data class EmployeeHomepage(
    val employee: Employee,
    val manager: Employee? = null,
    val peers: List<Employee> = emptyList(),
    val subordinates: List<Employee> = emptyList()
)
