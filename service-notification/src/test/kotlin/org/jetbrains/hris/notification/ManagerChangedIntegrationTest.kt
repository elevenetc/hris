package org.jetbrains.hris.notification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jetbrains.hris.db.schemas.DeliveryStatus
import org.jetbrains.hris.db.schemas.NotificationChannel
import org.jetbrains.hris.db.schemas.NotificationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Integration test for ManagerChanged event → notification pipeline.
 * Verifies that [NotificationType.NEW_DIRECT_REPORT] and [NotificationType.MANAGER_CHANGED] are sent and delivered.
 *
 * Tests the full flow:
 * 1. Create employees in database
 * 2. Call employeeService.changeManager()
 * 3. Verify ManagerChangedEvent is published
 * 4. Verify NewDirectReportEvent is published
 * 5. Verify notifications are created for both affected users
 * 6. Verify deliveries are created and sent
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagerChangedIntegrationTest : NotificationServiceIntegrationTestBase() {

    @Test
    fun `changing manager creates notifications for employee and new manager`() = runTest(testDispatcher) {
        // Step 1: Use common org structure
        // Diana (Senior Engineer) currently reports to Charlie (Engineering Manager)
        // We'll change her to report to Grace (Sales Manager) instead
        val employeeId = org.seniorEngineerId
        val oldManagerId = org.engineeringManagerId
        val newManagerId = org.salesManagerId

        // Step 2: Change employee's manager
        employeeService.changeManager(employeeId, newManagerId)

        // Step 3: Process all async events
        advanceUntilIdle()

        // Step 4: Verify notification for employee (ManagerChangedEvent)
        val employeeNotifications = notificationRepo.getUserNotifications(
            userId = employeeId,
            unreadOnly = false,
            limit = 10,
            offset = 0
        )

        assertEquals(
            1, employeeNotifications.size,
            "Employee should receive 1 notification about manager change"
        )

        val employeeNotif = employeeNotifications.first()
        assertEquals(NotificationType.MANAGER_CHANGED.name, employeeNotif.type)
        assertEquals("Manager Changed", employeeNotif.title)
        assertEquals("employee", employeeNotif.relatedEntityType)
        assertEquals(employeeId, employeeNotif.relatedEntityId)

        // Step 5: Verify notification for new manager (NewDirectReportEvent)
        val newManagerNotifications = notificationRepo.getUserNotifications(
            userId = newManagerId,
            unreadOnly = false,
            limit = 10,
            offset = 0
        )

        assertEquals(
            1, newManagerNotifications.size,
            "New manager should receive 1 notification about new direct report"
        )

        val managerNotif = newManagerNotifications.first()
        assertEquals(NotificationType.NEW_DIRECT_REPORT.name, managerNotif.type)
        assertEquals("New Direct Report", managerNotif.title)
        assertEquals("employee", managerNotif.relatedEntityType)
        assertEquals(employeeId, managerNotif.relatedEntityId)

        // Step 6: Verify deliveries were created in database
        val employeeDeliveries = notificationRepo.getDeliveriesForNotification(employeeNotif.id)
        val managerDeliveries = notificationRepo.getDeliveriesForNotification(managerNotif.id)

        assertEquals(
            4, employeeDeliveries.size,
            "Employee notification should have 4 delivery records (one per channel)"
        )
        assertEquals(
            4, managerDeliveries.size,
            "Manager notification should have 4 delivery records (one per channel)"
        )

        // Verify each notification has deliveries for all channels
        val employeeChannels = employeeDeliveries.map { it.channel }.toSet()
        val managerChannels = managerDeliveries.map { it.channel }.toSet()

        assertEquals(
            NotificationChannel.entries.toSet(), employeeChannels,
            "Employee should have deliveries for all channels"
        )
        assertEquals(
            NotificationChannel.entries.toSet(), managerChannels,
            "Manager should have deliveries for all channels"
        )

        // Step 7: Wait for deliveries to be processed and sent
        advanceUntilIdle()

        // Step 8: Verify all deliveries were sent
        val employeeDeliveriesAfterSending = notificationRepo.getDeliveriesForNotification(employeeNotif.id)
        val managerDeliveriesAfterSending = notificationRepo.getDeliveriesForNotification(managerNotif.id)

        employeeDeliveriesAfterSending.forEach { delivery ->
            assertEquals(
                DeliveryStatus.SENT, delivery.status,
                "Employee delivery for channel ${delivery.channel} should be SENT"
            )
        }

        managerDeliveriesAfterSending.forEach { delivery ->
            assertEquals(
                DeliveryStatus.SENT, delivery.status,
                "Manager delivery for channel ${delivery.channel} should be SENT"
            )
        }

        // Step 9: Verify fake senders received the deliveries
        assertEquals(2, emailSender.sentDeliveries.size, "Email sender should have sent 2 deliveries")
        assertEquals(2, browserSender.sentDeliveries.size, "Browser sender should have sent 2 deliveries")
        assertEquals(2, mobileSender.sentDeliveries.size, "Mobile sender should have sent 2 deliveries")
        assertEquals(2, slackSender.sentDeliveries.size, "Slack sender should have sent 2 deliveries")
    }
}
