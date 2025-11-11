package org.jetbrains.hris.api.routes

import io.ktor.http.HttpStatusCode.Companion.Created
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Instant
import org.jetbrains.hris.api.requests.CreatePerformanceReviewRequest
import org.jetbrains.hris.api.requests.UpdatePerformanceReviewRequest
import org.jetbrains.hris.api.responses.CreatePerformanceReviewResponse
import org.jetbrains.hris.api.utils.getLongOrFail
import org.jetbrains.hris.common.exceptions.notFoundException
import org.jetbrains.hris.common.exceptions.validationException
import org.jetbrains.hris.db.schemas.ReviewStatus
import org.jetbrains.hris.review.CreateReviewRequest
import org.jetbrains.hris.review.ReviewService
import org.jetbrains.hris.review.UpdateReviewRequest

fun Route.reviewRoutes(reviewService: ReviewService) {
    route("/employees/{id}/reviews") {
        post {
            val employeeId = call.parameters.getLongOrFail("id")

            val req = call.receive<CreatePerformanceReviewRequest>()

            val reviewId = reviewService.createReview(
                CreateReviewRequest(
                    employeeId = employeeId,
                    reviewerId = req.reviewerId,
                    reviewDate = Instant.parse(req.reviewDate),
                    performanceScore = req.performanceScore,
                    softSkillsScore = req.softSkillsScore,
                    independenceScore = req.independenceScore,
                    aspirationForGrowthScore = req.aspirationForGrowthScore,
                    notes = req.notes
                )
            )

            call.respond(Created, CreatePerformanceReviewResponse(reviewId))
        }

        get {
            val employeeId = call.parameters.getLongOrFail("id")
            val fromDate = call.request.queryParameters["fromDate"]?.let { Instant.parse(it) }
            val toDate = call.request.queryParameters["toDate"]?.let { Instant.parse(it) }
            val statusParam = call.request.queryParameters["status"]
            val status = statusParam?.let {
                try {
                    ReviewStatus.valueOf(it.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw MissingRequestParameterException("status")
                }
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
            val offset = call.request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0

            val reviews = reviewService.getEmployeeReviews(employeeId, fromDate, toDate, status, limit, offset)
            call.respond(reviews)
        }
    }

    route("/reviews/{id}") {
        get {
            val reviewId = call.parameters.getLongOrFail("id")

            val review = reviewService.getReviewById(reviewId)
                ?: notFoundException("Review not found")

            call.respond(review)
        }

        put {
            val reviewId = call.parameters.getLongOrFail("id")

            val req = call.receive<UpdatePerformanceReviewRequest>()

            val success = reviewService.updateReview(
                reviewId,
                UpdateReviewRequest(
                    reviewDate = req.reviewDate?.let { Instant.parse(it) },
                    performanceScore = req.performanceScore,
                    softSkillsScore = req.softSkillsScore,
                    independenceScore = req.independenceScore,
                    aspirationForGrowthScore = req.aspirationForGrowthScore,
                    notes = req.notes
                )
            )

            if (!success) {
                validationException("Review not found or not in DRAFT status")
            }

            call.respond(mapOf("success" to true))
        }

        delete {
            val reviewId = call.parameters.getLongOrFail("id")

            val success = reviewService.deleteReview(reviewId)

            if (!success) {
                validationException("Review not found or not in DRAFT status")
            }

            call.respond(mapOf("success" to true))
        }

        post("/submit") {
            val reviewId = call.parameters.getLongOrFail("id")

            val success = reviewService.submitReview(reviewId)

            if (!success) {
                validationException("Review not found or already submitted")
            }

            call.respond(mapOf("success" to true))
        }
    }
}
