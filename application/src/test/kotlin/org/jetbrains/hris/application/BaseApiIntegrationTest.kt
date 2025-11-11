package org.jetbrains.hris.application

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.hris.api.routes.employeeRoutes
import org.jetbrains.hris.api.routes.healthRoutes
import org.jetbrains.hris.application.config.installPlugins
import org.jetbrains.hris.db.initDatabase
import org.jetbrains.hris.employee.EmployeeRepository
import org.jetbrains.hris.employee.EmployeeService
import org.jetbrains.hris.infrastructure.cache.NoOpCache
import org.jetbrains.hris.infrastructure.events.EventBus
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.db.DatabaseTables
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlinx.coroutines.Dispatchers

/**
 * Base class for API integration tests.
 *
 * Provides:
 * - PostgreSQL testcontainer setup/teardown
 * - Lightweight test application without background services
 * - Common HTTP client configuration
 * - Only initializes what's needed for API testing
 *
 * This avoids race conditions from NotificationService background coroutines.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseApiIntegrationTest {

    private lateinit var postgres: PostgreSQLContainer<Nothing>

    @BeforeAll
    fun setup() {
        // Start PostgreSQL TestContainer
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()

        // Connect to the test database
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password
        )
    }

    @AfterAll
    fun teardown() {
        postgres.stop()
    }

    /**
     * Configure a lightweight test application for API testing.
     *
     * Only includes:
     * - Database initialization
     * - Ktor plugins (ContentNegotiation, etc.)
     * - Specified routes
     *
     * Does NOT include:
     * - NotificationService (avoids background coroutines)
     * - Full dependency injection
     * - Other services unless explicitly needed
     */
    protected fun ApplicationTestBuilder.configureLightweightApp(
        configureRoutes: Routing.() -> Unit
    ) {
        application {
            initDatabase()

            // Clean all tables before each test to avoid state pollution
            transaction {
                exec(DatabaseTables.truncateAll())
            }

            installPlugins()
            routing(configureRoutes)
        }
    }

    /**
     * Configure test application with employee routes and minimal dependencies.
     */
    protected fun ApplicationTestBuilder.configureWithEmployeeRoutes() {
        configureLightweightApp {
            val eventBus = EventBus(dispatcher = Dispatchers.Default)
            val employeeRepo = EmployeeRepository()
            val employeeService = EmployeeService(
                employeeRepo = employeeRepo,
                eventBus = eventBus,
                cache = NoOpCache()
            )
            employeeRoutes(employeeService)
        }
    }

    /**
     * Configure test application with health routes.
     */
    protected fun ApplicationTestBuilder.configureWithHealthRoutes() {
        configureLightweightApp {
            healthRoutes(NoOpCache())
        }
    }

    /**
     * Create an HTTP client with JSON support for testing.
     */
    protected fun ApplicationTestBuilder.createJsonClient(): HttpClient {
        return createClient {
            install(ContentNegotiation) {
                json()
            }
        }
    }
}
