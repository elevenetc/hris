package org.jetbrains.hris.common.models

import kotlinx.serialization.Serializable

/**
 * Domain model representing a performance review.
 * Used across all layers: Repository, Service, and API.
 */
@Serializable
data class Review(
    val id: Long,
    val employeeId: Long,
    val reviewerId: Long,
    val reviewDate: String,
    val performanceScore: Int,
    val softSkillsScore: Int,
    val independenceScore: Int,
    val aspirationForGrowthScore: Int,
    val notes: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String
)