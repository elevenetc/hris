package org.jetbrains.hris.api.responses

import kotlinx.serialization.Serializable

@Serializable
data class AddEmployeeResponse(
    val id: Long
)
