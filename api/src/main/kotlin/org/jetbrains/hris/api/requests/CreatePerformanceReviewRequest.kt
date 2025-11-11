package org.jetbrains.hris.api.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreatePerformanceReviewRequest(
    val reviewerId: Long,
    val reviewDate: String, // ISO-8601 format
    val performanceScore: Int,
    val softSkillsScore: Int,
    val independenceScore: Int,
    val aspirationForGrowthScore: Int,
    val notes: String? = null
)
