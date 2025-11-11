package org.jetbrains.hris.notification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.db.*
import org.jetbrains.hris.db.initDatabase
import org.jetbrains.hris.db.schemas.NotificationChannel
import org.jetbrains.hris.employee.EmployeeRepository
import org.jetbrains.hris.employee.EmployeeService
import org.jetbrains.hris.infrastructure.cache.NoOpCache
import org.jetbrains.hris.infrastructure.events.EventBus
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Common organizational structure for tests.
 * Provides a realistic org hierarchy with multiple managers and employees.
 */
data class TestOrgStructure(
    val ceoId: Long,
    val vpEngineeringId: Long,
    val engineeringManagerId: Long,
    val seniorEngineerId: Long,
    val juniorEngineerId: Long,
    val vpSalesId: Long,
    val salesManagerId: Long,
    val salesRepId: Long
)

/**
 * Base class for notification service integration tests.
 *
 * Provides common infrastructure:
 * - PostgreSQL TestContainer
 * - EventBus, EmployeeService, NotificationService with test dispatcher
 * - Fake notification senders
 * - Common org structure (CEO, VPs, managers, employees)
 * - Database cleanup between tests
 *
 * Subclasses can:
 * - Use the common org structure via `org` property
 * - Add additional employees as needed
 * - Execute service methods
 * - Verify notifications and deliveries
 */
@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class NotificationServiceIntegrationTestBase {

    /**
     * Common organizational structure available to all tests.
     * Initialized in setup() and recreated after each test.
     */
    protected lateinit var org: TestOrgStructure

    protected val testDispatcher = UnconfinedTestDispatcher()
    protected lateinit var postgres: PostgreSQLContainer<Nothing>

    protected lateinit var eventBus: EventBus
    protected lateinit var employeeRepo: EmployeeRepository
    protected lateinit var employeeService: EmployeeService
    protected lateinit var notificationRepo: NotificationRepository
    protected lateinit var notificationService: NotificationService

    protected val emailSender = FakeSender(NotificationChannel.EMAIL)
    protected val browserSender = FakeSender(NotificationChannel.BROWSER)
    protected val mobileSender = FakeSender(NotificationChannel.MOBILE)
    protected val slackSender = FakeSender(NotificationChannel.SLACK)
    protected val allSenders = listOf(emailSender, browserSender, mobileSender, slackSender)

    @BeforeAll
    fun setup() {
        // Start PostgreSQL
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()

        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password
        )

        initDatabase()
        Dispatchers.setMain(testDispatcher)

        // Create services with test dispatcher and fake senders
        eventBus = EventBus(dispatcher = testDispatcher)
        employeeRepo = EmployeeRepository()
        employeeService = EmployeeService(
            employeeRepo = employeeRepo,
            eventBus = eventBus,
            cache = NoOpCache()
        )
        notificationRepo = NotificationRepository()
        notificationService = NotificationService(
            notificationRepo = notificationRepo,
            senders = allSenders,
            eventBus = eventBus,
            dispatcher = testDispatcher
        )

        notificationService.start()

        // Allow services to initialize
        runTest(testDispatcher) {
            advanceUntilIdle()
        }

        // Create common org structure for tests
        org = createCommonOrgStructure()
    }

    @AfterAll
    fun teardown() {
        // Only stop services if they were initialized
        if (::notificationService.isInitialized) {
            notificationService.stop()
        }
        if (::eventBus.isInitialized) {
            eventBus.close()
        }
        Dispatchers.resetMain()
        if (::postgres.isInitialized) {
            postgres.stop()
        }
    }

    @AfterEach
    fun cleanup() {
        // Clear fake senders
        allSenders.forEach { it.clear() }

        // Clear database
        transaction {
            exec(DatabaseTables.truncateAll())
        }

        // Recreate org structure for next test
        org = createCommonOrgStructure()
    }

    /**
     * Creates a common organizational structure for tests.
     *
     * Org chart:
     * - CEO
     *   - VP Engineering
     *     - Engineering Manager
     *       - Senior Engineer
     *       - Junior Engineer
     *   - VP Sales
     *     - Sales Manager
     *       - Sales Rep
     */
    private fun createCommonOrgStructure(): TestOrgStructure {
        val ceoId = employeeRepo.addEmployee(
            firstName = "Alice",
            lastName = "CEO",
            email = "alice.ceo@company.com",
            position = "Chief Executive Officer",
            managerId = null
        )

        val vpEngineeringId = employeeRepo.addEmployee(
            firstName = "Bob",
            lastName = "VP",
            email = "bob.vp@company.com",
            position = "VP Engineering",
            managerId = ceoId
        )

        val engineeringManagerId = employeeRepo.addEmployee(
            firstName = "Charlie",
            lastName = "Manager",
            email = "charlie.manager@company.com",
            position = "Engineering Manager",
            managerId = vpEngineeringId
        )

        val seniorEngineerId = employeeRepo.addEmployee(
            firstName = "Diana",
            lastName = "Senior",
            email = "diana.senior@company.com",
            position = "Senior Engineer",
            managerId = engineeringManagerId
        )

        val juniorEngineerId = employeeRepo.addEmployee(
            firstName = "Eve",
            lastName = "Junior",
            email = "eve.junior@company.com",
            position = "Junior Engineer",
            managerId = engineeringManagerId
        )

        val vpSalesId = employeeRepo.addEmployee(
            firstName = "Frank",
            lastName = "VP",
            email = "frank.vp@company.com",
            position = "VP Sales",
            managerId = ceoId
        )

        val salesManagerId = employeeRepo.addEmployee(
            firstName = "Grace",
            lastName = "Manager",
            email = "grace.manager@company.com",
            position = "Sales Manager",
            managerId = vpSalesId
        )

        val salesRepId = employeeRepo.addEmployee(
            firstName = "Henry",
            lastName = "Rep",
            email = "henry.rep@company.com",
            position = "Sales Representative",
            managerId = salesManagerId
        )

        return TestOrgStructure(
            ceoId = ceoId,
            vpEngineeringId = vpEngineeringId,
            engineeringManagerId = engineeringManagerId,
            seniorEngineerId = seniorEngineerId,
            juniorEngineerId = juniorEngineerId,
            vpSalesId = vpSalesId,
            salesManagerId = salesManagerId,
            salesRepId = salesRepId
        )
    }

}
