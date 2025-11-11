package org.jetbrains.hris.api.responses

import kotlinx.serialization.Serializable

@Serializable
data class CreatePerformanceReviewResponse(
    val id: Long
)
