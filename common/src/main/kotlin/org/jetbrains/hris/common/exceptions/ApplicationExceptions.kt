package org.jetbrains.hris.common.exceptions

/**
 * Marker interface for exceptions that are safe to expose to external clients.
 * These exceptions contain user-friendly messages without sensitive internal details.
 */
interface ExternalException

/**
 * Marker interface for exceptions that should NOT be exposed to external clients.
 * These exceptions may contain sensitive information like database details, stack traces, etc.
 */
interface InternalException

/**
 * Base class for all application-level exceptions.
 * These exceptions represent business logic errors and expected failure scenarios.
 */
sealed class ApplicationException(message: String) : RuntimeException(message)

/**
 * Thrown when a requested resource is not found.
 * Maps to HTTP 404 Not Found.
 * Safe to expose: Contains controlled user-facing messages.
 */
class NotFoundException(message: String) : ApplicationException(message), ExternalException

/**
 * Thrown when request parameters or data fail validation.
 * Maps to HTTP 400 Bad Request.
 * Safe to expose: Contains controlled validation error messages.
 */
class ValidationException(message: String) : ApplicationException(message), ExternalException

/**
 * Thrown when an operation conflicts with existing data (e.g., duplicate keys, unique constraints).
 * Maps to HTTP 409 Conflict.
 * Safe to expose: Contains controlled business rule violation messages.
 */
class ConflictException(message: String) : ApplicationException(message), ExternalException

/**
 * Thrown when a repository/database operation fails.
 * Maps to HTTP 500 Internal Server Error.
 * NOT safe to expose: May contain database details, SQL errors, connection strings, etc.
 */
class RepositoryException(message: String, cause: Throwable? = null) : ApplicationException(message), InternalException {
    init {
        cause?.let { initCause(it) }
    }
}

fun validationException(message: String): Nothing = throw ValidationException(message)
fun notFoundException(message: String): Nothing = throw NotFoundException(message)
fun conflictException(message: String): Nothing = throw ConflictException(message)
fun repositoryException(message: String, cause: Throwable? = null): Nothing =
    throw RepositoryException(message, cause)
