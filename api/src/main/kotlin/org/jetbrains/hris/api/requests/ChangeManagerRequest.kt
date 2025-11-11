package org.jetbrains.hris.api.requests

import kotlinx.serialization.Serializable

@Serializable
data class ChangeManagerRequest(
    val newManagerId: Long?
)
