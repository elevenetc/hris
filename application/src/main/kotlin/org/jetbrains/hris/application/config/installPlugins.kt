package org.jetbrains.hris.application.config

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.jetbrains.hris.api.requests.AddEmployeeRequest
import org.jetbrains.hris.api.requests.CreatePerformanceReviewRequest
import org.jetbrains.hris.api.requests.UpdatePerformanceReviewRequest
import org.jetbrains.hris.common.exceptions.*
import org.jetbrains.hris.common.exceptions.NotFoundException
import org.jetbrains.hris.employee.EmployeeValidator
import org.jetbrains.hris.review.ReviewValidator
import org.slf4j.LoggerFactory
import io.ktor.server.plugins.requestvalidation.ValidationResult as KtorValidationResult

fun Application.installPlugins() {
    val logger = LoggerFactory.getLogger("HrisApplication")

    install(ContentNegotiation) {
        json()
    }

    install(RequestValidation) {
        validate<AddEmployeeRequest> { request ->
            val result = EmployeeValidator.validateAddEmployee(
                firstName = request.firstName,
                lastName = request.lastName,
                email = request.email
            )
            if (result.valid) KtorValidationResult.Valid else KtorValidationResult.Invalid(result.message!!)
        }

        validate<CreatePerformanceReviewRequest> { request ->
            val result = ReviewValidator.validateCreateReview(
                reviewerId = request.reviewerId,
                reviewDate = request.reviewDate,
                performanceScore = request.performanceScore,
                softSkillsScore = request.softSkillsScore,
                independenceScore = request.independenceScore,
                aspirationForGrowthScore = request.aspirationForGrowthScore
            )
            if (result.valid) KtorValidationResult.Valid else KtorValidationResult.Invalid(result.message!!)
        }

        validate<UpdatePerformanceReviewRequest> { request ->
            val result = ReviewValidator.validateUpdateReview(
                reviewDate = request.reviewDate,
                performanceScore = request.performanceScore,
                softSkillsScore = request.softSkillsScore,
                independenceScore = request.independenceScore,
                aspirationForGrowthScore = request.aspirationForGrowthScore
            )
            if (result.valid) KtorValidationResult.Valid else KtorValidationResult.Invalid(result.message!!)
        }
    }

    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            logger.error("RequestValidationException: ${cause.reasons}", cause)
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to cause.reasons.joinToString(", "))
            )
        }

        exception<MissingRequestParameterException> { call, cause ->
            logger.error("MissingRequestParameterException: ${cause.parameterName}", cause)
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing or invalid required parameter: ${cause.parameterName}")
            )
        }

        exception<NumberFormatException> { call, cause ->
            logger.error("NumberFormatException: ${cause.message}", cause)
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid parameter format: ${cause.message}")
            )
        }

        exception<ApplicationException> { call, cause ->
            logger.error("${cause::class.simpleName}: ${cause.message}", cause)

            val (statusCode, errorMessage) = when (cause) {
                // External exceptions - safe to expose with original message
                is ExternalException -> when (cause) {
                    is NotFoundException ->
                        HttpStatusCode.NotFound to cause.message

                    is ValidationException ->
                        HttpStatusCode.BadRequest to cause.message

                    is ConflictException ->
                        HttpStatusCode.Conflict to cause.message

                    else -> HttpStatusCode.InternalServerError to "An error occurred"
                }
                // Internal exceptions - hide details, use generic message
                is InternalException -> when (cause) {
                    is RepositoryException ->
                        HttpStatusCode.InternalServerError to "A database error occurred"

                    else -> HttpStatusCode.InternalServerError to "An internal error occurred"
                }
                // Unknown application exception type
                else -> HttpStatusCode.InternalServerError to "An error occurred"
            }

            call.respond(statusCode, mapOf("error" to errorMessage))
        }

        // Catch-all for any other unexpected exceptions
        exception<Exception> { call, cause ->
            logger.error("Unhandled exception: ${cause.message}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "An internal error occurred")
            )
        }
    }
}