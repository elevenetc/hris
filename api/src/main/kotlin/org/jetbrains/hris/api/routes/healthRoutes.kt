package org.jetbrains.hris.api.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.api.responses.CacheMetricsResponse
import org.jetbrains.hris.api.responses.HealthResponse
import org.jetbrains.hris.infrastructure.cache.Cache
import org.jetbrains.hris.infrastructure.cache.RedisCache

fun Route.healthRoutes(cache: Cache) {
    get("/health") {
        val dbStatus = try {
            transaction {
                exec("SELECT 1")
            }
            "ok"
        } catch (e: Exception) {
            "error"
        }

        // Get cache metrics if using RedisCache
        val cacheMetrics = if (cache is RedisCache) {
            val metrics = cache.getMetrics()
            CacheMetricsResponse(
                status = if (metrics.isAvailable) "ok" else "unavailable",
                hits = metrics.hits,
                misses = metrics.misses,
                errors = metrics.errors,
                hitRate = if (metrics.hits + metrics.misses > 0) {
                    "%.2f%%".format(metrics.hits.toDouble() / (metrics.hits + metrics.misses) * 100)
                } else {
                    "N/A"
                }
            )
        } else {
            CacheMetricsResponse(status = "disabled")
        }

        val isHealthy = dbStatus == "ok"
        val statusCode = if (isHealthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable

        call.respond(
            statusCode,
            HealthResponse(
                status = if (isHealthy) "ok" else "unhealthy",
                database = dbStatus,
                cache = cacheMetrics
            )
        )
    }
}
