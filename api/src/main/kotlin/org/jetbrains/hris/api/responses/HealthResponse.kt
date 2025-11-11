package org.jetbrains.hris.api.responses

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
    val cache: CacheMetricsResponse
)

@Serializable
data class CacheMetricsResponse(
    val status: String,
    val hits: Long? = null,
    val misses: Long? = null,
    val errors: Long? = null,
    val hitRate: String? = null
)
