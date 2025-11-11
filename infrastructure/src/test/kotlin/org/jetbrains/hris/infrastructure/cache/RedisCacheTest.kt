package org.jetbrains.hris.infrastructure.cache

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Tests for RedisCache implementation using Testcontainers.
 *
 * Starts a Redis container for all tests to ensure consistent behavior
 * and test isolation.
 */
class RedisCacheTest {
    private lateinit var cache: RedisCache

    companion object {
        private val redisContainer = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)

        @BeforeAll
        @JvmStatic
        fun startRedis() {
            redisContainer.start()
        }
    }

    @AfterEach
    fun cleanup() {
        if (::cache.isInitialized) {
            cache.close()
        }
    }

    private fun createCache(): RedisCache {
        return RedisCache(
            host = redisContainer.host,
            port = redisContainer.getMappedPort(6379)
        )
    }

    @Test
    fun `typed cache stores and retrieves objects correctly`() {
        // Given
        cache = createCache()
        val testData = TestData(id = 1, name = "Test Object", active = true)
        var loaderCallCount = 0

        // When: First call should invoke loader
        val result1 = cache.getTyped<TestData>("test:key1", ttlSeconds = 60) {
            loaderCallCount++
            testData
        }

        // Then
        assertEquals(testData, result1)
        assertEquals(1, loaderCallCount, "Loader should be called once")

        // When: Second call should use cached value
        val result2 = cache.getTyped<TestData>("test:key1", ttlSeconds = 60) {
            loaderCallCount++
            testData
        }

        // Then
        assertEquals(testData, result2)
        assertEquals(1, loaderCallCount, "Loader should still be 1 (value was cached)")
    }

    @Test
    fun `typed cache handles null values correctly`() {
        // Given
        cache = createCache()
        var loaderCallCount = 0

        // When: Loader returns null
        val result = cache.getTyped<TestData>("test:null-key", ttlSeconds = 60) {
            loaderCallCount++
            null
        }

        // Then
        assertNull(result)
        assertEquals(1, loaderCallCount)
    }

    @Test
    fun `typed cache with nested objects works correctly`() {
        // Given
        @Serializable
        data class Parent(val id: Long, val children: List<TestData>)

        cache = createCache()
        val parent = Parent(
            id = 100,
            children = listOf(
                TestData(1, "Child 1", true),
                TestData(2, "Child 2", false)
            )
        )

        // When
        val result = cache.getTyped<Parent>("test:nested", ttlSeconds = 60) {
            parent
        }

        // Then
        assertEquals(parent, result)
        assertEquals(2, result?.children?.size)
        assertEquals("Child 1", result?.children?.get(0)?.name)
    }

    @Test
    fun `string cache stores and retrieves strings correctly`() {
        // Given
        cache = createCache()
        var loaderCallCount = 0

        // When: First call
        val result1 = cache.get("test:string-key", ttlSeconds = 60) {
            loaderCallCount++
            "cached-value"
        }

        // Then
        assertEquals("cached-value", result1)
        assertEquals(1, loaderCallCount)

        // When: Second call should use cached value
        val result2 = cache.get("test:string-key", ttlSeconds = 60) {
            loaderCallCount++
            "cached-value"
        }

        // Then
        assertEquals("cached-value", result2)
        assertEquals(1, loaderCallCount, "Loader should still be 1 (value was cached)")
    }

    @Test
    fun `delete removes cached value`() {
        // Given
        cache = createCache()
        val testData = TestData(id = 1, name = "Test", active = true)
        var loaderCallCount = 0

        // Store value in cache
        cache.getTyped<TestData>("test:delete-key", ttlSeconds = 60) {
            loaderCallCount++
            testData
        }

        // When: Delete the key
        cache.delete("test:delete-key")

        // Then: Next get should call loader again
        val originalCount = loaderCallCount
        cache.getTyped<TestData>("test:delete-key", ttlSeconds = 60) {
            loaderCallCount++
            testData
        }

        assertEquals(originalCount + 1, loaderCallCount, "Loader should be called again after delete")
    }

    @Test
    fun `deletePattern removes all matching keys`() {
        // Given
        cache = createCache()
        val testData1 = TestData(id = 1, name = "Test 1", active = true)
        val testData2 = TestData(id = 2, name = "Test 2", active = true)
        var loaderCallCount = 0

        // Store multiple values with same prefix
        cache.getTyped<TestData>("test:pattern:key1", ttlSeconds = 60) {
            loaderCallCount++
            testData1
        }
        cache.getTyped<TestData>("test:pattern:key2", ttlSeconds = 60) {
            loaderCallCount++
            testData2
        }

        // When: Delete all keys matching pattern
        cache.deletePattern("test:pattern:*")

        // Then: Both keys should require loader calls again
        val originalCount = loaderCallCount
        cache.getTyped<TestData>("test:pattern:key1", ttlSeconds = 60) {
            loaderCallCount++
            testData1
        }
        cache.getTyped<TestData>("test:pattern:key2", ttlSeconds = 60) {
            loaderCallCount++
            testData2
        }

        assertEquals(originalCount + 2, loaderCallCount, "Loader should be called twice more after deletePattern")
    }

    @Test
    fun `cache metrics are tracked`() {
        // Given
        cache = createCache()

        // When: Perform some cache operations
        cache.get("test:metrics:key1", ttlSeconds = 60) { "value1" }
        cache.get("test:metrics:key1", ttlSeconds = 60) { "value1" } // Should hit cache
        cache.get("test:metrics:key2", ttlSeconds = 60) { "value2" }

        // Then: Metrics should be recorded
        val metrics = cache.getMetrics()

        // We should have 1 hit (second access to key1) and 2 misses (first access to key1, first access to key2)
        assertEquals(1, metrics.hits, "Should have 1 cache hit")
        assertEquals(2, metrics.misses, "Should have 2 cache misses")
        assertTrue(metrics.isAvailable, "Redis should be available")
    }

    @Test
    fun `NoOpCache always calls loader`() {
        // Given
        val noOpCache = NoOpCache()
        var callCount = 0

        // When: Multiple calls to same key
        val result1 = noOpCache.get("key", ttlSeconds = 60) {
            callCount++
            "value"
        }
        val result2 = noOpCache.get("key", ttlSeconds = 60) {
            callCount++
            "value"
        }

        // Then: Loader should be called every time
        assertEquals("value", result1)
        assertEquals("value", result2)
        assertEquals(2, callCount, "NoOpCache should always call loader")
    }

    @Test
    fun `NoOpCache handles typed objects`() {
        // Given
        val noOpCache = NoOpCache()
        val testData = TestData(id = 1, name = "Test", active = true)

        // When
        val result = noOpCache.getTyped<TestData>("key", ttlSeconds = 60) {
            testData
        }

        // Then
        assertEquals(testData, result)
    }
}

/**
 * Test data class for cache testing - completely independent from domain models.
 */
@Serializable
data class TestData(
    val id: Long,
    val name: String,
    val active: Boolean = true
)