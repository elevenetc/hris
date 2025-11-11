package org.jetbrains.hris.review

import kotlinx.datetime.Instant
import org.jetbrains.hris.common.validation.ValidationResult

object ReviewValidator {
    private const val MIN_SCORE = 1
    private const val MAX_SCORE = 10

    fun validateCreateReview(
        reviewerId: Long,
        reviewDate: String,
        performanceScore: Int,
        softSkillsScore: Int,
        independenceScore: Int,
        aspirationForGrowthScore: Int
    ): ValidationResult {
        return when {
            reviewerId <= 0 -> ValidationResult.invalid("reviewerId must be positive")
            performanceScore !in MIN_SCORE..MAX_SCORE ->
                ValidationResult.invalid("performanceScore must be between $MIN_SCORE and $MAX_SCORE")
            softSkillsScore !in MIN_SCORE..MAX_SCORE ->
                ValidationResult.invalid("softSkillsScore must be between $MIN_SCORE and $MAX_SCORE")
            independenceScore !in MIN_SCORE..MAX_SCORE ->
                ValidationResult.invalid("independenceScore must be between $MIN_SCORE and $MAX_SCORE")
            aspirationForGrowthScore !in MIN_SCORE..MAX_SCORE ->
                ValidationResult.invalid("aspirationForGrowthScore must be between $MIN_SCORE and $MAX_SCORE")
            else -> validateReviewDate(reviewDate)
        }
    }

    fun validateUpdateReview(
        reviewDate: String?,
        performanceScore: Int?,
        softSkillsScore: Int?,
        independenceScore: Int?,
        aspirationForGrowthScore: Int?
    ): ValidationResult {
        return when {
            performanceScore != null && performanceScore !in MIN_SCORE..MAX_SCORE ->
                ValidationResult.invalid("performanceScore must be between $MIN_SCORE and $MAX_SCORE")
            softSkillsScore != null && softSkillsScore !in MIN_SCORE..MAX_SCORE ->
                ValidationResult.invalid("softSkillsScore must be between $MIN_SCORE and $MAX_SCORE")
            independenceScore != null && independenceScore !in MIN_SCORE..MAX_SCORE ->
                ValidationResult.invalid("independenceScore must be between $MIN_SCORE and $MAX_SCORE")
            aspirationForGrowthScore != null && aspirationForGrowthScore !in MIN_SCORE..MAX_SCORE ->
                ValidationResult.invalid("aspirationForGrowthScore must be between $MIN_SCORE and $MAX_SCORE")
            reviewDate != null -> validateReviewDate(reviewDate)
            else -> ValidationResult.valid()
        }
    }

    private fun validateReviewDate(reviewDate: String): ValidationResult {
        return try {
            Instant.parse(reviewDate)
            ValidationResult.valid()
        } catch (_: IllegalArgumentException) {
            ValidationResult.invalid("reviewDate must be in valid ISO-8601 format")
        }
    }
}
