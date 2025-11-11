package org.jetbrains.hris.review

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.common.models.Review
import org.jetbrains.hris.db.schemas.Employees
import org.jetbrains.hris.db.schemas.ReviewStatus
import org.jetbrains.hris.db.schemas.Reviews


/**
 * Repository for managing performance reviews.
 * Reviews can be created as DRAFT and edited until submitted.
 * Once SUBMITTED, reviews become immutable.
 */
class ReviewRepository {

    /**
     * Creates a new performance review in DRAFT status.
     * Validates that scores are in the range 1-10.
     * Returns the generated review ID.
     */
    fun createReview(request: CreateReviewRequest): Long = transaction {
        val validation = ReviewValidator.validateCreateReview(
            reviewerId = request.reviewerId,
            reviewDate = request.reviewDate.toString(),
            performanceScore = request.performanceScore,
            softSkillsScore = request.softSkillsScore,
            independenceScore = request.independenceScore,
            aspirationForGrowthScore = request.aspirationForGrowthScore
        )

        if (!validation.valid) {
            throw IllegalArgumentException(validation.message)
        }

        val now = Clock.System.now()
        Reviews.insertAndGetId { row ->
            row[employeeId] = EntityID(request.employeeId, Employees)
            row[reviewerId] = EntityID(request.reviewerId, Employees)
            row[reviewDate] = request.reviewDate
            row[performanceScore] = request.performanceScore
            row[softSkillsScore] = request.softSkillsScore
            row[independenceScore] = request.independenceScore
            row[aspirationForGrowthScore] = request.aspirationForGrowthScore
            row[notes] = request.notes
            row[status] = ReviewStatus.DRAFT
            row[createdAt] = now
            row[updatedAt] = now
        }.value
    }

    /**
     * Updates a review. Only DRAFT reviews can be updated.
     * Returns true if updated successfully, false if review is not in DRAFT status or doesn't exist.
     */
    fun updateReview(reviewId: Long, request: UpdateReviewRequest): Boolean = transaction {
        // Check if review exists and is in DRAFT status
        val review = Reviews
            .selectAll()
            .where { Reviews.id eq EntityID(reviewId, Reviews) }
            .singleOrNull()
            ?: return@transaction false

        if (review[Reviews.status] != ReviewStatus.DRAFT) {
            return@transaction false
        }

        // Validate scores using the final values (request values override existing values)
        val perfScore = request.performanceScore ?: review[Reviews.performanceScore]
        val softScore = request.softSkillsScore ?: review[Reviews.softSkillsScore]
        val indepScore = request.independenceScore ?: review[Reviews.independenceScore]
        val aspirScore = request.aspirationForGrowthScore ?: review[Reviews.aspirationForGrowthScore]

        val validation = ReviewValidator.validateUpdateReview(
            reviewDate = request.reviewDate?.toString(),
            performanceScore = perfScore,
            softSkillsScore = softScore,
            independenceScore = indepScore,
            aspirationForGrowthScore = aspirScore
        )

        if (!validation.valid) {
            throw IllegalArgumentException(validation.message)
        }

        val updated = Reviews.update({ Reviews.id eq EntityID(reviewId, Reviews) }) { row ->
            request.reviewDate?.let { row[reviewDate] = it }
            request.performanceScore?.let { row[performanceScore] = it }
            request.softSkillsScore?.let { row[softSkillsScore] = it }
            request.independenceScore?.let { row[independenceScore] = it }
            request.aspirationForGrowthScore?.let { row[aspirationForGrowthScore] = it }
            request.notes?.let { row[notes] = it }
            row[updatedAt] = Clock.System.now()
        }

        updated > 0
    }

    /**
     * Submits a review, changing its status from DRAFT to SUBMITTED.
     * After submission, the review becomes immutable.
     * Returns true if submitted successfully, false if review doesn't exist or is already submitted.
     */
    fun submitReview(reviewId: Long): Boolean = transaction {
        val updated = Reviews.update({
            (Reviews.id eq EntityID(reviewId, Reviews)) and
                    (Reviews.status eq ReviewStatus.DRAFT)
        }) { row ->
            row[status] = ReviewStatus.SUBMITTED
            row[updatedAt] = Clock.System.now()
        }

        updated > 0
    }

    /**
     * Fetches a single review by ID.
     * Returns null if not found.
     */
    fun getReviewById(reviewId: Long): Review? = transaction {
        Reviews
            .selectAll()
            .where { Reviews.id eq EntityID(reviewId, Reviews) }
            .singleOrNull()
            ?.toReview()
    }

    /**
     * Fetches reviews for a specific employee with optional filtering and pagination.
     * @param employeeId The employee whose reviews to fetch
     * @param fromDate Optional start date filter (inclusive)
     * @param toDate Optional end date filter (inclusive)
     * @param status Optional status filter
     * @param limit Maximum number of results (default 50)
     * @param offset Offset for pagination (default 0)
     * @return List of reviews sorted by review_date descending (most recent first)
     */
    fun getEmployeeReviews(
        employeeId: Long,
        fromDate: Instant? = null,
        toDate: Instant? = null,
        status: ReviewStatus? = null,
        limit: Int = 50,
        offset: Long = 0
    ): List<Review> = transaction {
        val query = Reviews
            .selectAll()
            .where { Reviews.employeeId eq EntityID(employeeId, Employees) }
        queryReview(fromDate, query, toDate, status, limit, offset)
    }

    /**
     * Fetches reviews conducted by a specific reviewer with optional filtering and pagination.
     * @param reviewerId The reviewer whose reviews to fetch
     * @param fromDate Optional start date filter (inclusive)
     * @param toDate Optional end date filter (inclusive)
     * @param status Optional status filter
     * @param limit Maximum number of results (default 50)
     * @param offset Offset for pagination (default 0)
     * @return List of reviews sorted by review_date descending (most recent first)
     */
    fun getReviewsByReviewer(
        reviewerId: Long,
        fromDate: Instant? = null,
        toDate: Instant? = null,
        status: ReviewStatus? = null,
        limit: Int = 50,
        offset: Long = 0
    ): List<Review> = transaction {
        val query = Reviews
            .selectAll()
            .where { Reviews.reviewerId eq EntityID(reviewerId, Employees) }
        queryReview(fromDate, query, toDate, status, limit, offset)
    }


    /**
     * Deletes a review. Only DRAFT reviews can be deleted.
     * Returns true if deleted successfully, false if review doesn't exist or is not in DRAFT status.
     */
    fun deleteReview(reviewId: Long): Boolean = transaction {
        val deleted = Reviews.deleteWhere {
            (Reviews.id eq EntityID(reviewId, Reviews)) and
                    (Reviews.status eq ReviewStatus.DRAFT)
        }
        deleted > 0
    }

}

data class CreateReviewRequest(
    val employeeId: Long,
    val reviewerId: Long,
    val reviewDate: Instant,
    val performanceScore: Int,
    val softSkillsScore: Int,
    val independenceScore: Int,
    val aspirationForGrowthScore: Int,
    val notes: String? = null
)

data class UpdateReviewRequest(
    val reviewDate: Instant? = null,
    val performanceScore: Int? = null,
    val softSkillsScore: Int? = null,
    val independenceScore: Int? = null,
    val aspirationForGrowthScore: Int? = null,
    val notes: String? = null
)

private fun queryReview(
    fromDate: Instant?,
    query: Query,
    toDate: Instant?,
    status: ReviewStatus?,
    limit: Int,
    offset: Long
): List<Review> {
    var query1 = query
    fromDate?.let {
        query1 = query1.andWhere { Reviews.reviewDate greaterEq it }
    }

    toDate?.let {
        query1 = query1.andWhere { Reviews.reviewDate lessEq it }
    }

    status?.let {
        query1 = query1.andWhere { Reviews.status eq it }
    }

    return query1
        .orderBy(Reviews.reviewDate, SortOrder.DESC).limit(limit).offset(offset)
        .map { it.toReview() }
}

private fun ResultRow.toReview() = Review(
    id = this[Reviews.id].value,
    employeeId = this[Reviews.employeeId].value,
    reviewerId = this[Reviews.reviewerId].value,
    reviewDate = this[Reviews.reviewDate].toString(),
    performanceScore = this[Reviews.performanceScore],
    softSkillsScore = this[Reviews.softSkillsScore],
    independenceScore = this[Reviews.independenceScore],
    aspirationForGrowthScore = this[Reviews.aspirationForGrowthScore],
    notes = this[Reviews.notes],
    status = this[Reviews.status].name,
    createdAt = this[Reviews.createdAt].toString(),
    updatedAt = this[Reviews.updatedAt].toString()
)
