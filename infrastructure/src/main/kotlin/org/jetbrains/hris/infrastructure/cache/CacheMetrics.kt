package org.jetbrains.hris.infrastructure.cache

data class CacheMetrics(
    val hits: Long,
    val misses: Long,
    val errors: Long,
    val isAvailable: Boolean
)