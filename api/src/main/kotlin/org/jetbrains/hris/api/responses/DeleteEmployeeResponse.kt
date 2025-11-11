package org.jetbrains.hris.api.responses

import kotlinx.serialization.Serializable

@Serializable
data class DeleteEmployeeResponse(
    val success: Boolean,
    val removedCount: Int? = null,
    val error: String? = null
)
