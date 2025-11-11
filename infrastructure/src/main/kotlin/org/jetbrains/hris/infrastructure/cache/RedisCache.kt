package org.jetbrains.hris.infrastructure.cache

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Redis-based cache implementation using Lettuce client.
 *
 * This implementation handles Redis failures gracefully:
 * - If Redis is unavailable during initialization, operations fall back to direct loading
 * - If Redis operations fail at runtime, they fall back to direct loading
 * - The application continues working even when Redis is down
 *
 * Metrics are tracked for monitoring cache effectiveness:
 * - hits: successful cache retrievals
 * - misses: cache keys not found (value loaded from source)
 * - errors: Redis operation failures
 */
class RedisCache(
    host: String,
    port: Int
) : Cache {
    private val logger = LoggerFactory.getLogger(RedisCache::class.java)

    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null
    private var commands: RedisCommands<String, String>? = null
    private var isAvailable = false

    // JSON serializer for typed cache operations
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Metrics
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val errors = AtomicLong(0)

    init {
        try {
            logger.info("Connecting to Redis at $host:$port")
            val redisUri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .build()

            client = RedisClient.create(redisUri)
            connection = client?.connect()
            commands = connection?.sync()

            // Test connection
            commands?.ping()
            isAvailable = true
            logger.info("Redis cache initialized successfully")
        } catch (e: Exception) {
            logger.warn("Redis not available - cache operations will be skipped", e)
            isAvailable = false
        }
    }

    override fun <T> getTyped(key: String, ttlSeconds: Long?, typeInfo: TypeInfo<T>, loader: () -> T?): T? {
        if (!isAvailable) {
            return loader()
        }

        return try {
            val cached = commands?.get(key)
            if (cached != null) {
                hits.incrementAndGet()
                // Deserialize from JSON
                @Suppress("UNCHECKED_CAST")
                val serializer = serializer(typeInfo.kClass.java) as kotlinx.serialization.KSerializer<T>
                json.decodeFromString(serializer, cached)
            } else {
                misses.incrementAndGet()
                val value = loader()
                if (value != null) {
                    // Serialize to JSON and cache
                    @Suppress("UNCHECKED_CAST")
                    val serializer = serializer(typeInfo.kClass.java) as kotlinx.serialization.KSerializer<T>
                    val jsonString = json.encodeToString(serializer, value)
                    set(key, jsonString, ttlSeconds)
                }
                value
            }
        } catch (e: Exception) {
            errors.incrementAndGet()
            logger.warn("Redis GET failed for key '$key', falling back to loader", e)
            loader()
        }
    }

    override fun get(key: String, ttlSeconds: Long?, loader: () -> String): String {
        if (!isAvailable) {
            return loader()
        }

        return try {
            val cached = commands?.get(key)
            if (cached != null) {
                hits.incrementAndGet()
                cached
            } else {
                misses.incrementAndGet()
                val value = loader()
                set(key, value, ttlSeconds)
                value
            }
        } catch (e: Exception) {
            errors.incrementAndGet()
            logger.warn("Redis GET failed for key '$key', falling back to loader", e)
            loader()
        }
    }

    override fun set(key: String, value: String, ttlSeconds: Long?) {
        if (!isAvailable) {
            return
        }

        try {
            if (ttlSeconds != null) {
                commands?.setex(key, ttlSeconds, value)
            } else {
                // No expiration - value persists until explicitly deleted
                commands?.set(key, value)
            }
        } catch (e: Exception) {
            errors.incrementAndGet()
            logger.warn("Redis SET failed for key '$key'", e)
        }
    }

    override fun delete(key: String) {
        if (!isAvailable) {
            return
        }

        try {
            commands?.del(key)
        } catch (e: Exception) {
            errors.incrementAndGet()
            logger.warn("Redis DELETE failed for key '$key'", e)
        }
    }

    override fun deletePattern(pattern: String) {
        if (!isAvailable) {
            return
        }

        try {
            val keys = commands?.keys(pattern)
            if (!keys.isNullOrEmpty()) {
                commands?.del(*keys.toTypedArray())
            }
        } catch (e: Exception) {
            errors.incrementAndGet()
            logger.warn("Redis DELETE PATTERN failed for pattern '$pattern'", e)
        }
    }

    /**
     * Get cache metrics for monitoring
     */
    fun getMetrics(): CacheMetrics {
        return CacheMetrics(
            hits = hits.get(),
            misses = misses.get(),
            errors = errors.get(),
            isAvailable = isAvailable
        )
    }

    /**
     * Close the Redis connection
     */
    override fun close() {
        try {
            connection?.close()
            client?.shutdown()
            logger.info("Redis cache connection closed")
        } catch (e: Exception) {
            logger.warn("Error closing Redis connection", e)
        }
    }
}

