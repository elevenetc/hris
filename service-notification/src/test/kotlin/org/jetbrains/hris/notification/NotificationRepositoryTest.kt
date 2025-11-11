package org.jetbrains.hris.notification

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.db.*
import org.jetbrains.hris.db.schemas.DeliveryStatus
import org.jetbrains.hris.db.schemas.NotificationChannel
import org.jetbrains.hris.db.schemas.NotificationType
import org.jetbrains.hris.employee.EmployeeRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(Lifecycle.PER_CLASS)
class NotificationRepositoryTest {

    private lateinit var postgres: PostgreSQLContainer<Nothing>

    @BeforeAll
    fun startDb() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()

        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password
        )

        initDatabase()
    }

    @AfterAll
    fun stopDb() {
        postgres.stop()
    }

    @BeforeEach
    fun clean() {
        transaction {
            exec(DatabaseTables.truncateAll())
        }
    }

    @Test
    fun `createNotification creates notification with deliveries for all channels`() {
        val empRepo = EmployeeRepository()
        val notificationRepo = NotificationRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)

        val notificationId = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Review Submitted",
                message = "Your performance review has been submitted.",
                relatedEntityType = "review",
                relatedEntityId = 1L
            )
        )

        val notification = notificationRepo.getNotificationById(notificationId)
        assertNotNull(notification)
        assertEquals(employee, notification.userId)
        assertEquals(NotificationType.REVIEW_SUBMITTED.name, notification.type)
        assertEquals("Review Submitted", notification.title)
        assertEquals("Your performance review has been submitted.", notification.message)

        // Verify deliveries were created for all channels
        val deliveries = notificationRepo.getDeliveriesForNotification(notificationId)
        assertEquals(4, deliveries.size) // EMAIL, BROWSER, MOBILE, SLACK
        assertTrue(deliveries.all { it.status == DeliveryStatus.PENDING })
    }

    @Test
    fun `getUserNotifications returns user's notifications sorted by date`() {
        val empRepo = EmployeeRepository()
        val notificationRepo = NotificationRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)

        // Create 3 notifications
        val notif1 = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Review 1",
                message = "Message 1"
            )
        )

        Thread.sleep(10) // Ensure different timestamps

        val notif2 = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.MANAGER_CHANGED,
                title = "Manager Changed",
                message = "Message 2"
            )
        )

        val notifications = notificationRepo.getUserNotifications(employee)
        assertEquals(2, notifications.size)
        // Should be sorted by createdAt descending (most recent first)
        assertEquals(notif2, notifications[0].id)
        assertEquals(notif1, notifications[1].id)
    }

    @Test
    fun `markAsRead marks notification as read`() {
        val empRepo = EmployeeRepository()
        val notificationRepo = NotificationRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)

        val notificationId = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Review Submitted",
                message = "Test message"
            )
        )

        // Initially unread
        val unreadCount = notificationRepo.countUnreadNotifications(employee)
        assertEquals(1, unreadCount)

        // Mark as read
        val success = notificationRepo.markAsRead(notificationId, employee)
        assertTrue(success)

        // Verify read
        val unreadCountAfter = notificationRepo.countUnreadNotifications(employee)
        assertEquals(0, unreadCountAfter)

        // Verify notification has readAt timestamp
        val notification = notificationRepo.getNotificationById(notificationId)
        assertNotNull(notification?.readAt)
    }

    @Test
    fun `markAllAsRead marks all unread notifications as read`() {
        val empRepo = EmployeeRepository()
        val notificationRepo = NotificationRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)

        // Create 3 notifications
        repeat(3) {
            notificationRepo.createNotification(
                CreateNotificationRequest(
                    userId = employee,
                    type = NotificationType.REVIEW_SUBMITTED,
                    title = "Review $it",
                    message = "Message $it"
                )
            )
        }

        val unreadCount = notificationRepo.countUnreadNotifications(employee)
        assertEquals(3, unreadCount)

        val markedCount = notificationRepo.markAllAsRead(employee)
        assertEquals(3, markedCount)

        val unreadCountAfter = notificationRepo.countUnreadNotifications(employee)
        assertEquals(0, unreadCountAfter)
    }

    @Test
    fun `getPendingDeliveries returns deliveries ready to process`() {
        val empRepo = EmployeeRepository()
        val notificationRepo = NotificationRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)

        notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Test",
                message = "Message"
            )
        )

        val pendingDeliveries = notificationRepo.getPendingDeliveries(100)
        assertEquals(4, pendingDeliveries.size) // 4 channels
        assertTrue(pendingDeliveries.all { it.status == DeliveryStatus.PENDING })
    }

    @Test
    fun `markDeliveryAsSent marks delivery as sent`() {
        val empRepo = EmployeeRepository()
        val notificationRepo = NotificationRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)

        val notificationId = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Test",
                message = "Message"
            )
        )

        val deliveries = notificationRepo.getDeliveriesForNotification(notificationId)
        val deliveryId = deliveries.first().id

        val success = notificationRepo.markDeliveryAsSent(deliveryId)
        assertTrue(success)

        val updatedDeliveries = notificationRepo.getDeliveriesForNotification(notificationId)
        val updatedDelivery = updatedDeliveries.first { it.id == deliveryId }
        assertEquals(DeliveryStatus.SENT, updatedDelivery.status)
        assertNotNull(updatedDelivery.sentAt)
    }

    @Test
    fun `markDeliveryAsFailed schedules retry with exponential backoff`() {
        val empRepo = EmployeeRepository()
        val notificationRepo = NotificationRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)

        val notificationId = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Test",
                message = "Message",
                channels = listOf(NotificationChannel.EMAIL) // Only one channel for testing
            )
        )

        val deliveries = notificationRepo.getDeliveriesForNotification(notificationId)
        val deliveryId = deliveries.first().id

        // First failure - should retry
        notificationRepo.markDeliveryAsProcessing(deliveryId)
        val shouldRetry1 = notificationRepo.markDeliveryAsFailed(deliveryId, "Connection error")
        assertTrue(shouldRetry1)

        var delivery = notificationRepo.getDeliveriesForNotification(notificationId).first()
        assertEquals(1, delivery.attemptCount)
        assertEquals(DeliveryStatus.PENDING, delivery.status)
        assertNotNull(delivery.nextRetryAt)

        // Simulate more failures until max retries
        repeat(4) { attemptNum ->
            notificationRepo.markDeliveryAsProcessing(deliveryId)
            notificationRepo.markDeliveryAsFailed(deliveryId, "Error ${attemptNum + 2}")
        }

        // After 5 attempts, should be permanently failed
        delivery = notificationRepo.getDeliveriesForNotification(notificationId).first()
        assertEquals(5, delivery.attemptCount)
        assertEquals(DeliveryStatus.FAILED, delivery.status)
    }

    @Test
    fun `deleteNotification deletes notification and cascades to deliveries`() {
        val empRepo = EmployeeRepository()
        val notificationRepo = NotificationRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)

        val notificationId = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Test",
                message = "Message"
            )
        )

        // Verify deliveries exist
        val deliveriesBefore = notificationRepo.getDeliveriesForNotification(notificationId)
        assertEquals(4, deliveriesBefore.size)

        // Delete notification
        val success = notificationRepo.deleteNotification(notificationId, employee)
        assertTrue(success)

        // Verify notification is deleted
        val notification = notificationRepo.getNotificationById(notificationId)
        assertEquals(null, notification)

        // Verify deliveries are also deleted (cascade)
        val deliveriesAfter = notificationRepo.getDeliveriesForNotification(notificationId)
        assertEquals(0, deliveriesAfter.size)
    }

    @Test
    fun `unreadOnly filter returns only unread notifications`() {
        val empRepo = EmployeeRepository()
        val notificationRepo = NotificationRepository()

        val employee = empRepo.addEmployee("John", "Doe", "john@example.com", "Engineer", null)

        // Create 3 notifications
        val notif1 = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Review 1",
                message = "Message 1"
            )
        )

        val notif2 = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Review 2",
                message = "Message 2"
            )
        )

        notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = employee,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Review 3",
                message = "Message 3"
            )
        )

        // Mark one as read
        notificationRepo.markAsRead(notif1, employee)

        // Get only unread
        val unreadNotifications = notificationRepo.getUserNotifications(employee, unreadOnly = true)
        assertEquals(2, unreadNotifications.size)
        assertFalse(unreadNotifications.any { it.id == notif1 })
    }
}
