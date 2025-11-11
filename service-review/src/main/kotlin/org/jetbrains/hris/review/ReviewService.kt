package org.jetbrains.hris.review

import org.jetbrains.hris.common.events.ReviewSubmittedEvent
import org.jetbrains.hris.common.models.Review
import org.jetbrains.hris.db.schemas.ReviewStatus
import org.jetbrains.hris.infrastructure.events.EventBus
import kotlinx.datetime.Instant

/**
 * Service layer for performance review operations.
 *
 * Responsibilities:
 * - Orchestrate business logic
 * - Coordinate between repositories
 * - Publish domain events
 * - Transaction boundaries
 *
 * This layer sits between API and Repository:
 * API → Service → Repository
 *          ↓
 *       EventBus
 */
class ReviewService(
    private val reviewRepo: ReviewRepository,
    private val eventBus: EventBus
) {

    /**
     * Creates a new performance review in DRAFT status.
     *
     * @throws IllegalArgumentException if scores are invalid
     * @return The created review ID
     */
    fun createReview(request: CreateReviewRequest): Long {
        // Business logic: Validate scores (done in repository, but could be here)
        // For now, delegate to repository which validates
        val reviewId = reviewRepo.createReview(request)

        // Note: No event published for DRAFT creation per requirements
        // Only the employee being reviewed cares about submitted reviews

        return reviewId
    }

    /**
     * Updates a DRAFT review.
     *
     * @return true if updated successfully, false if review not found or not DRAFT
     * @throws IllegalArgumentException if scores are invalid
     */
    fun updateReview(reviewId: Long, request: UpdateReviewRequest): Boolean {
        // Business logic: Ensure only DRAFT reviews can be updated
        // This is enforced by repository, but service could add additional checks
        return reviewRepo.updateReview(reviewId, request)
    }

    /**
     * Submits a review, changing status from DRAFT to SUBMITTED.
     * After submission, the review becomes immutable and a notification is sent.
     *
     * This is the orchestration layer - it coordinates:
     * 1. Fetching review for validation
     * 2. Submitting review via repository
     * 3. Publishing domain event for notifications
     *
     * @return true if submitted successfully, false if review not found or already submitted
     */
    fun submitReview(reviewId: Long): Boolean {
        // 1. Fetch review for validation and event data
        val review = reviewRepo.getReviewById(reviewId)
            ?: return false

        // 2. Attempt to submit via repository
        val success = reviewRepo.submitReview(reviewId)

        // 3. Publish domain event if successful
        if (success) {
            eventBus.publish(
                ReviewSubmittedEvent(
                    reviewId = reviewId,
                    employeeId = review.employeeId,
                    reviewerId = review.reviewerId
                )
            )
        }

        return success
    }

    /**
     * Deletes a DRAFT review.
     *
     * @return true if deleted successfully, false if review not found or not DRAFT
     */
    fun deleteReview(reviewId: Long): Boolean {
        // Business logic: Ensure only DRAFT reviews can be deleted
        // Enforced by repository
        return reviewRepo.deleteReview(reviewId)
    }

    /**
     * Fetches a single review by ID.
     *
     * @return The review or null if not found
     */
    fun getReviewById(reviewId: Long): Review? {
        return reviewRepo.getReviewById(reviewId)
    }

    /**
     * Fetches reviews for a specific employee with optional filtering and pagination.
     *
     * @param employeeId The employee whose reviews to fetch
     * @param fromDate Optional start date filter (inclusive)
     * @param toDate Optional end date filter (inclusive)
     * @param status Optional status filter
     * @param limit Maximum number of results (default 50)
     * @param offset Offset for pagination (default 0)
     * @return List of reviews sorted by review_date descending
     */
    fun getEmployeeReviews(
        employeeId: Long,
        fromDate: Instant? = null,
        toDate: Instant? = null,
        status: ReviewStatus? = null,
        limit: Int = 50,
        offset: Long = 0
    ): List<Review> {
        return reviewRepo.getEmployeeReviews(employeeId, fromDate, toDate, status, limit, offset)
    }

    /**
     * Fetches reviews conducted by a specific reviewer.
     *
     * @param reviewerId The reviewer whose reviews to fetch
     * @param fromDate Optional start date filter (inclusive)
     * @param toDate Optional end date filter (inclusive)
     * @param status Optional status filter
     * @param limit Maximum number of results (default 50)
     * @param offset Offset for pagination (default 0)
     * @return List of reviews sorted by review_date descending
     */
    fun getReviewsByReviewer(
        reviewerId: Long,
        fromDate: Instant? = null,
        toDate: Instant? = null,
        status: ReviewStatus? = null,
        limit: Int = 50,
        offset: Long = 0
    ): List<Review> {
        return reviewRepo.getReviewsByReviewer(reviewerId, fromDate, toDate, status, limit, offset)
    }
}
