package org.jetbrains.hris.infrastructure.cache

/**
 * Cache interface for storing and retrieving values.
 *
 * Implementations should handle failures gracefully and not throw exceptions
 * when cache operations fail. Instead, they should fall back to the loader function.
 *
 * This interface provides both string-based methods (for raw caching) and typed methods
 * (for automatic JSON serialization/deserialization).
 */
interface Cache {
    /**
     * Get a typed object from the cache, or load it using the loader function if not found.
     * Handles JSON serialization/deserialization automatically.
     *
     * @param key The cache key
     * @param ttlSeconds Time-to-live in seconds (null = no expiration, relies on explicit invalidation)
     * @param loader Function to load the value if not in cache (returns null if not found)
     * @return The cached or loaded object, or null if not found
     */
    fun <T> getTyped(key: String, ttlSeconds: Long? = null, typeInfo: TypeInfo<T>, loader: () -> T?): T?

    /**
     * Get a value from the cache, or load it using the loader function if not found.
     *
     * @param key The cache key
     * @param ttlSeconds Time-to-live in seconds (null = no expiration, relies on explicit invalidation)
     * @param loader Function to load the value if not in cache
     * @return The cached or loaded string value
     */
    fun get(key: String, ttlSeconds: Long? = null, loader: () -> String): String

    /**
     * Set a string value in the cache.
     *
     * @param key The cache key
     * @param value The string value to cache
     * @param ttlSeconds Time-to-live in seconds (null = no expiration)
     */
    fun set(key: String, value: String, ttlSeconds: Long?)

    /**
     * Delete a value from the cache.
     *
     * @param key The cache key
     */
    fun delete(key: String)

    /**
     * Delete multiple values from the cache matching a pattern.
     *
     * @param pattern The key pattern (e.g., "employee:*")
     */
    fun deletePattern(pattern: String)

    /**
     * Close the cache connection and release resources.
     * Should be called when the application shuts down.
     */
    fun close()
}

/**
 * Type information wrapper for deserialization.
 * Holds the Kotlin class reference needed for JSON deserialization.
 */
data class TypeInfo<T>(val kClass: kotlin.reflect.KClass<*>)

/**
 * Inline reified helper for getting typed values from cache.
 * This provides a cleaner API: cache.getTyped<Employee>(key) { ... }
 */
inline fun <reified T> Cache.getTyped(key: String, ttlSeconds: Long? = null, noinline loader: () -> T?): T? {
    return getTyped(key, ttlSeconds, TypeInfo(T::class), loader)
}
