package org.jetbrains.hris.notification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.hris.common.events.ReviewReceivedEvent
import org.jetbrains.hris.db.schemas.DeliveryStatus
import org.jetbrains.hris.db.schemas.NotificationChannel
import org.jetbrains.hris.db.schemas.NotificationType
import org.jetbrains.hris.review.CreateReviewRequest
import org.jetbrains.hris.review.ReviewRepository
import org.jetbrains.hris.review.ReviewService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Integration test for ReviewSubmitted and ReviewReceived events → notification pipeline.
 * Verifies that [NotificationType.REVIEW_SUBMITTED] and [NotificationType.REVIEW_RECEIVED]
 * are sent and delivered.
 *
 * Tests the full flow:
 * 1. Create and submit a review
 * 2. Verify ReviewSubmittedEvent is published
 * 3. Verify notification is created for the employee being reviewed
 * 4. Verify deliveries are created and sent
 * 5. Publish ReviewReceivedEvent manually (for 360 feedback scenario)
 * 6. Verify notification is created for the reviewer
 * 7. Verify deliveries are created and sent
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewIntegrationTest : NotificationServiceIntegrationTestBase() {

    private lateinit var reviewRepo: ReviewRepository
    private lateinit var reviewService: ReviewService

    @Test
    fun `submitting review creates notification for employee`() = runTest(testDispatcher) {
        // Initialize review service
        reviewRepo = ReviewRepository()
        reviewService = ReviewService(
            reviewRepo = reviewRepo,
            eventBus = eventBus
        )

        // Step 1: Use employees from common org structure
        val employeeId = org.seniorEngineerId
        val reviewerId = org.engineeringManagerId

        // Step 2: Create a review in DRAFT status
        val reviewId = reviewService.createReview(
            CreateReviewRequest(
                employeeId = employeeId,
                reviewerId = reviewerId,
                reviewDate = Clock.System.now(),
                performanceScore = 4,
                softSkillsScore = 5,
                independenceScore = 4,
                aspirationForGrowthScore = 5,
                notes = "Great performance this quarter"
            )
        )

        // Step 3: Submit the review
        val success = reviewService.submitReview(reviewId)
        assertEquals(true, success, "Review should be submitted successfully")

        // Step 4: Process all async events
        advanceUntilIdle()

        // Step 5: Verify notification for employee (ReviewSubmittedEvent)
        val employeeNotifications = notificationRepo.getUserNotifications(
            userId = employeeId,
            unreadOnly = false,
            limit = 10,
            offset = 0
        )

        assertEquals(1, employeeNotifications.size,
            "Employee should receive 1 notification about review submission")

        val employeeNotif = employeeNotifications.first()
        assertEquals(NotificationType.REVIEW_SUBMITTED.name, employeeNotif.type)
        assertEquals(reviewId, employeeNotif.relatedEntityId)

        // Step 6: Verify deliveries were created in database
        val employeeDeliveries = notificationRepo.getDeliveriesForNotification(employeeNotif.id)

        assertEquals(4, employeeDeliveries.size,
            "Employee notification should have 4 delivery records (one per channel)")

        // Verify notification has deliveries for all channels
        val employeeChannels = employeeDeliveries.map { it.channel }.toSet()

        assertEquals(
            NotificationChannel.entries.toSet(), employeeChannels,
            "Employee should have deliveries for all channels")

        // Step 7: Wait for deliveries to be processed and sent
        advanceUntilIdle()

        // Step 8: Verify all deliveries were sent
        val employeeDeliveriesAfterSending = notificationRepo.getDeliveriesForNotification(employeeNotif.id)

        employeeDeliveriesAfterSending.forEach { delivery ->
            assertEquals(
                DeliveryStatus.SENT, delivery.status,
                "Employee delivery for channel ${delivery.channel} should be SENT")
        }

        // Step 9: Verify fake senders received the deliveries
        assertEquals(1, emailSender.sentDeliveries.size, "Email sender should have sent 1 delivery")
        assertEquals(1, browserSender.sentDeliveries.size, "Browser sender should have sent 1 delivery")
        assertEquals(1, mobileSender.sentDeliveries.size, "Mobile sender should have sent 1 delivery")
        assertEquals(1, slackSender.sentDeliveries.size, "Slack sender should have sent 1 delivery")
    }

    @Test
    fun `receiving review feedback creates notification for reviewer`() = runTest(testDispatcher) {
        // Initialize review service
        reviewRepo = ReviewRepository()
        reviewService = ReviewService(
            reviewRepo = reviewRepo,
            eventBus = eventBus
        )

        // Step 1: Use employees from common org structure
        // In a 360 review scenario, the junior engineer provides feedback to the manager
        val employeeId = org.juniorEngineerId
        val reviewerId = org.engineeringManagerId

        // Step 2: Create a review (360 feedback scenario)
        val reviewId = reviewService.createReview(
            CreateReviewRequest(
                employeeId = employeeId,
                reviewerId = reviewerId,
                reviewDate = Clock.System.now(),
                performanceScore = 5,
                softSkillsScore = 5,
                independenceScore = 5,
                aspirationForGrowthScore = 5,
                notes = "Excellent leadership and mentorship"
            )
        )

        // Submit the review
        reviewService.submitReview(reviewId)

        // Clear previous notifications/deliveries to test ReviewReceivedEvent separately
        allSenders.forEach { it.clear() }

        // Step 3: Manually publish ReviewReceivedEvent to test the notification pipeline
        // This simulates the scenario where a reviewer receives feedback from a team member
        eventBus.publish(
            ReviewReceivedEvent(
                reviewId = reviewId,
                employeeId = employeeId,
                reviewerId = reviewerId
            )
        )

        // Step 4: Process all async events
        advanceUntilIdle()

        // Step 5: Verify notification for reviewer (ReviewReceivedEvent)
        val reviewerNotifications = notificationRepo.getUserNotifications(
            userId = reviewerId,
            unreadOnly = false,
            limit = 10,
            offset = 0
        )

        assertEquals(1, reviewerNotifications.size,
            "Reviewer should receive 1 notification about receiving feedback")

        val reviewerNotif = reviewerNotifications.first()
        assertEquals(NotificationType.REVIEW_RECEIVED.name, reviewerNotif.type)
        assertEquals("Feedback Received", reviewerNotif.title)
        assertEquals("review", reviewerNotif.relatedEntityType)
        assertEquals(reviewId, reviewerNotif.relatedEntityId)

        // Step 6: Verify deliveries were created in database
        val reviewerDeliveries = notificationRepo.getDeliveriesForNotification(reviewerNotif.id)

        assertEquals(4, reviewerDeliveries.size,
            "Reviewer notification should have 4 delivery records (one per channel)")

        // Verify notification has deliveries for all channels
        val reviewerChannels = reviewerDeliveries.map { it.channel }.toSet()

        assertEquals(
            NotificationChannel.entries.toSet(), reviewerChannels,
            "Reviewer should have deliveries for all channels")

        // Step 7: Wait for deliveries to be processed and sent
        advanceUntilIdle()

        // Step 8: Verify all deliveries were sent
        val reviewerDeliveriesAfterSending = notificationRepo.getDeliveriesForNotification(reviewerNotif.id)

        reviewerDeliveriesAfterSending.forEach { delivery ->
            assertEquals(
                DeliveryStatus.SENT, delivery.status,
                "Reviewer delivery for channel ${delivery.channel} should be SENT")
        }

        // Step 9: Verify fake senders received the deliveries
        assertEquals(1, emailSender.sentDeliveries.size, "Email sender should have sent 1 delivery")
        assertEquals(1, browserSender.sentDeliveries.size, "Browser sender should have sent 1 delivery")
        assertEquals(1, mobileSender.sentDeliveries.size, "Mobile sender should have sent 1 delivery")
        assertEquals(1, slackSender.sentDeliveries.size, "Slack sender should have sent 1 delivery")
    }
}
