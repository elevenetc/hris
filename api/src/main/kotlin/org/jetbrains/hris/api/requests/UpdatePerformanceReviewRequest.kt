package org.jetbrains.hris.api.requests

import kotlinx.serialization.Serializable

@Serializable
data class UpdatePerformanceReviewRequest(
    val reviewDate: String? = null, // ISO-8601 format
    val performanceScore: Int? = null,
    val softSkillsScore: Int? = null,
    val independenceScore: Int? = null,
    val aspirationForGrowthScore: Int? = null,
    val notes: String? = null
)
