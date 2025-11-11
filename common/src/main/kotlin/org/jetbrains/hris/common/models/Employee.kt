package org.jetbrains.hris.common.models

import kotlinx.serialization.Serializable

/**
 * Domain model representing an employee.
 * Used across all layers: Repository, Service, and API.
 */
@Serializable
data class Employee(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val email: String,
    val position: String? = null,
    val managerId: Long? = null,
    val subordinates: List<Employee> = emptyList()
)