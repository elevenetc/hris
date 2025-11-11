package org.jetbrains.hris.infrastructure.cache

/**
 * No-operation cache implementation that always calls the loader function.
 *
 * Useful for:
 * - Testing when you want to bypass caching
 * - Fallback when Redis is not configured
 * - Development environments
 */
class NoOpCache : Cache {
    override fun <T> getTyped(key: String, ttlSeconds: Long?, typeInfo: TypeInfo<T>, loader: () -> T?): T? {
        return loader()
    }

    override fun get(key: String, ttlSeconds: Long?, loader: () -> String): String {
        return loader()
    }

    override fun set(key: String, value: String, ttlSeconds: Long?) {
        // No-op
    }

    override fun delete(key: String) {
        // No-op
    }

    override fun deletePattern(pattern: String) {
        // No-op
    }

    override fun close() {
        // No-op - nothing to close
    }
}
