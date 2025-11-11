package org.jetbrains.hris.application

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.jetbrains.hris.api.responses.HealthResponse
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HealthApiIntegrationTest : BaseApiIntegrationTest() {

    @Test
    fun `GET health returns status with database and cache metrics`() = testApplication {
        configureWithHealthRoutes()
        val client = createJsonClient()

        // Make GET request to health endpoint
        val response = client.get("/health")

        // Assert response status
        assertEquals(HttpStatusCode.OK, response.status, "Health endpoint should return 200 OK")

        // Parse response
        val healthResponse = response.body<HealthResponse>()

        // Verify overall status
        assertEquals("ok", healthResponse.status, "Overall status should be 'ok'")

        // Verify database status
        assertEquals("ok", healthResponse.database, "Database status should be 'ok'")

        // Verify cache structure exists
        assertNotNull(healthResponse.cache, "Cache metrics should be present")

        // Using NoOpCache in this test, so status should be "disabled"
        assertEquals("disabled", healthResponse.cache.status, "Cache status should be 'disabled' with NoOpCache")
    }
}
