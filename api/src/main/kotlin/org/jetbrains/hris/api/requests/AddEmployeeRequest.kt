package org.jetbrains.hris.api.requests

import kotlinx.serialization.Serializable

@Serializable
data class AddEmployeeRequest(
    val firstName: String,
    val lastName: String,
    val email: String,
    val position: String? = null,
    val managerId: Long? = null
)
