package org.jetbrains.hris.notification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.db.*
import org.jetbrains.hris.db.initDatabase
import org.jetbrains.hris.db.schemas.DeliveryStatus
import org.jetbrains.hris.db.schemas.NotificationChannel
import org.jetbrains.hris.db.schemas.NotificationType
import org.jetbrains.hris.employee.EmployeeRepository
import org.jetbrains.hris.employee.EmployeeService
import org.jetbrains.hris.infrastructure.cache.NoOpCache
import org.jetbrains.hris.infrastructure.events.EventBus
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals

/**
 * Integration test for pending delivery recovery on service startup.
 * Verifies that undelivered notifications are requeued and delivered when the service starts.
 *
 * Tests the recovery flow:
 * 1. Create notifications with PENDING deliveries directly in the database
 * 2. Start NotificationService (which triggers requeuePendingDeliveries)
 * 3. Verify all pending deliveries are requeued and processed
 * 4. Verify deliveries are marked as SENT
 * 5. Verify fake senders received the deliveries
 */
@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PendingDeliveryRecoveryIntegrationTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var postgres: PostgreSQLContainer<Nothing>

    private lateinit var eventBus: EventBus
    private lateinit var employeeRepo: EmployeeRepository
    private lateinit var employeeService: EmployeeService
    private lateinit var notificationRepo: NotificationRepository
    private lateinit var notificationService: NotificationService

    private val emailSender = FakeSender(NotificationChannel.EMAIL)
    private val browserSender = FakeSender(NotificationChannel.BROWSER)
    private val mobileSender = FakeSender(NotificationChannel.MOBILE)
    private val slackSender = FakeSender(NotificationChannel.SLACK)
    private val allSenders = listOf(emailSender, browserSender, mobileSender, slackSender)

    private var employeeId: Long = 0

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

        // Create services (but don't start notification service yet)
        eventBus = EventBus(dispatcher = testDispatcher)
        employeeRepo = EmployeeRepository()
        employeeService = EmployeeService(
            employeeRepo = employeeRepo,
            eventBus = eventBus,
            cache = NoOpCache()
        )
        notificationRepo = NotificationRepository()

        // Create a test employee
        employeeId = employeeRepo.addEmployee(
            firstName = "Test",
            lastName = "Employee",
            email = "test@company.com",
            position = "Software Engineer",
            managerId = null
        )
    }

    @AfterAll
    fun teardown() {
        if (::notificationService.isInitialized) {
            notificationService.stop()
        }
        eventBus.close()
        Dispatchers.resetMain()
        postgres.stop()
    }

    @AfterEach
    fun cleanup() {
        // Stop notification service if running
        if (::notificationService.isInitialized) {
            notificationService.stop()
        }

        // Clear fake senders
        allSenders.forEach { it.clear() }

        // Clear database (except employees)
        transaction {
            exec(DatabaseTables.truncateNotifications())
        }
    }

    @Test
    fun `pending deliveries are requeued and delivered on service startup`() = runTest(testDispatcher) {
        // Step 1: Create multiple notifications with PENDING deliveries directly in the database
        // This simulates notifications that were not delivered before the app was shut down
        val notification1Id = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employeeId,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Review 1",
                message = "First review notification",
                relatedEntityType = "review",
                relatedEntityId = 1L
            )
        )

        val notification2Id = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employeeId,
                type = NotificationType.MANAGER_CHANGED,
                title = "Manager Changed",
                message = "Your manager has been updated",
                relatedEntityType = "employee",
                relatedEntityId = employeeId
            )
        )

        val notification3Id = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employeeId,
                type = NotificationType.NEW_DIRECT_REPORT,
                title = "New Direct Report",
                message = "You have a new direct report",
                relatedEntityType = "employee",
                relatedEntityId = 1L
            )
        )

        // Step 2: Verify all deliveries are in PENDING status
        val deliveries1 = notificationRepo.getDeliveriesForNotification(notification1Id)
        val deliveries2 = notificationRepo.getDeliveriesForNotification(notification2Id)
        val deliveries3 = notificationRepo.getDeliveriesForNotification(notification3Id)

        assertEquals(4, deliveries1.size, "Notification 1 should have 4 deliveries")
        assertEquals(4, deliveries2.size, "Notification 2 should have 4 deliveries")
        assertEquals(4, deliveries3.size, "Notification 3 should have 4 deliveries")

        deliveries1.forEach { delivery ->
            assertEquals(
                DeliveryStatus.PENDING, delivery.status,
                "Delivery ${delivery.id} should be PENDING before service starts")
        }
        deliveries2.forEach { delivery ->
            assertEquals(
                DeliveryStatus.PENDING, delivery.status,
                "Delivery ${delivery.id} should be PENDING before service starts")
        }
        deliveries3.forEach { delivery ->
            assertEquals(
                DeliveryStatus.PENDING, delivery.status,
                "Delivery ${delivery.id} should be PENDING before service starts")
        }

        // Step 3: Initialize and start NotificationService
        // This should trigger requeuePendingDeliveries() and process all pending deliveries
        notificationService = NotificationService(
            notificationRepo = notificationRepo,
            senders = allSenders,
            eventBus = eventBus,
            dispatcher = testDispatcher
        )

        notificationService.start()

        // Step 4: Allow time for all deliveries to be requeued and processed
        advanceUntilIdle()

        // Step 5: Verify all deliveries were processed and marked as SENT
        val deliveries1AfterProcessing = notificationRepo.getDeliveriesForNotification(notification1Id)
        val deliveries2AfterProcessing = notificationRepo.getDeliveriesForNotification(notification2Id)
        val deliveries3AfterProcessing = notificationRepo.getDeliveriesForNotification(notification3Id)

        deliveries1AfterProcessing.forEach { delivery ->
            assertEquals(
                DeliveryStatus.SENT, delivery.status,
                "Notification 1 delivery for channel ${delivery.channel} should be SENT")
        }

        deliveries2AfterProcessing.forEach { delivery ->
            assertEquals(
                DeliveryStatus.SENT, delivery.status,
                "Notification 2 delivery for channel ${delivery.channel} should be SENT")
        }

        deliveries3AfterProcessing.forEach { delivery ->
            assertEquals(
                DeliveryStatus.SENT, delivery.status,
                "Notification 3 delivery for channel ${delivery.channel} should be SENT")
        }

        // Step 6: Verify fake senders received all deliveries
        // 3 notifications × 4 channels = 12 deliveries per sender
        assertEquals(3, emailSender.sentDeliveries.size,
            "Email sender should have sent 3 deliveries (one per notification)")
        assertEquals(3, browserSender.sentDeliveries.size,
            "Browser sender should have sent 3 deliveries (one per notification)")
        assertEquals(3, mobileSender.sentDeliveries.size,
            "Mobile sender should have sent 3 deliveries (one per notification)")
        assertEquals(3, slackSender.sentDeliveries.size,
            "Slack sender should have sent 3 deliveries (one per notification)")

        // Step 7: Verify the deliveries are for the correct notifications
        val emailDeliveryNotificationIds = emailSender.sentDeliveries.map { it.notification.id }.toSet()
        assertEquals(setOf(notification1Id, notification2Id, notification3Id), emailDeliveryNotificationIds,
            "Email sender should have sent deliveries for all 3 notifications")
    }

    @Test
    fun `only PENDING deliveries are requeued on startup`() = runTest(testDispatcher) {
        // Step 1: Create notifications with different delivery statuses
        val notification1Id = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employeeId,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Review 1",
                message = "First review notification",
                relatedEntityType = "review",
                relatedEntityId = 1L
            )
        )

        val notification2Id = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employeeId,
                type = NotificationType.MANAGER_CHANGED,
                title = "Manager Changed",
                message = "Your manager has been updated",
                relatedEntityType = "employee",
                relatedEntityId = employeeId
            )
        )

        // Step 2: Mark some deliveries as already SENT or PROCESSING (simulate previously delivered notifications)
        val deliveries1 = notificationRepo.getDeliveriesForNotification(notification1Id)
        val deliveries2 = notificationRepo.getDeliveriesForNotification(notification2Id)

        // Mark notification1's email delivery as SENT
        notificationRepo.markDeliveryAsSent(deliveries1.first { it.channel == NotificationChannel.EMAIL }.id)

        // Mark notification1's browser delivery as PROCESSING
        notificationRepo.markDeliveryAsProcessing(deliveries1.first { it.channel == NotificationChannel.BROWSER }.id)

        // Step 3: Start NotificationService
        notificationService = NotificationService(
            notificationRepo = notificationRepo,
            senders = allSenders,
            eventBus = eventBus,
            dispatcher = testDispatcher
        )

        notificationService.start()
        advanceUntilIdle()

        // Step 4: Verify only PENDING deliveries were processed
        val deliveries1AfterProcessing = notificationRepo.getDeliveriesForNotification(notification1Id)
        val deliveries2AfterProcessing = notificationRepo.getDeliveriesForNotification(notification2Id)

        // Notification1's email delivery should still be SENT (not re-delivered)
        assertEquals(
            DeliveryStatus.SENT,
            deliveries1AfterProcessing.first { it.channel == NotificationChannel.EMAIL }.status,
            "Email delivery should remain SENT")

        // Notification1's browser delivery was PROCESSING, so it should remain PROCESSING (not requeued)
        // Only PENDING deliveries are requeued on startup
        assertEquals(
            DeliveryStatus.PROCESSING,
            deliveries1AfterProcessing.first { it.channel == NotificationChannel.BROWSER }.status,
            "Browser delivery should remain PROCESSING (not requeued)")

        // Notification1's other deliveries (mobile, slack) were PENDING and should now be SENT
        assertEquals(
            DeliveryStatus.SENT,
            deliveries1AfterProcessing.first { it.channel == NotificationChannel.MOBILE }.status,
            "Mobile delivery should be processed from PENDING to SENT")
        assertEquals(
            DeliveryStatus.SENT,
            deliveries1AfterProcessing.first { it.channel == NotificationChannel.SLACK }.status,
            "Slack delivery should be processed from PENDING to SENT")

        // All of notification2's deliveries were PENDING and should now be SENT
        deliveries2AfterProcessing.forEach { delivery ->
            assertEquals(
                DeliveryStatus.SENT, delivery.status,
                "Notification 2 delivery for channel ${delivery.channel} should be SENT")
        }

        // Step 5: Verify fake senders received only the PENDING deliveries
        // Notification1 has 4 channels: EMAIL(SENT-skip), BROWSER(PROCESSING-skip), MOBILE(PENDING), SLACK(PENDING)
        // So notification1 will send: MOBILE, SLACK = 2 deliveries
        // Notification2 has 4 channels all PENDING = 4 deliveries
        // Total = 2 + 4 = 6 deliveries

        // Each sender handles one channel, so:
        // EMAIL: 0 (notif1) + 1 (notif2) = 1
        // BROWSER: 0 (notif1) + 1 (notif2) = 1
        // MOBILE: 1 (notif1) + 1 (notif2) = 2
        // SLACK: 1 (notif1) + 1 (notif2) = 2

        assertEquals(1, emailSender.sentDeliveries.size,
            "Email sender should have sent 1 delivery (only notification2)")
        assertEquals(1, browserSender.sentDeliveries.size,
            "Browser sender should have sent 1 delivery (only notification2)")
        assertEquals(2, mobileSender.sentDeliveries.size,
            "Mobile sender should have sent 2 deliveries (notification1 and notification2)")
        assertEquals(2, slackSender.sentDeliveries.size,
            "Slack sender should have sent 2 deliveries (notification1 and notification2)")
    }
}
