package org.jetbrains.hris.notification

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.hris.common.events.*
import org.jetbrains.hris.db.schemas.NotificationType
import org.jetbrains.hris.infrastructure.events.EventBus
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service that manages notification creation, delivery, and retries.
 *
 * Architecture:
 * - Listens to application events and creates notifications
 * - Maintains an in-memory queue for pending deliveries
 * - Processes deliveries asynchronously with exponential backoff retry logic
 * - Persists all state to database for durability
 *
 * @param dispatcher The coroutine dispatcher for async operations (injectable for testing)
 */
class NotificationService(
    private val notificationRepo: NotificationRepository,
    senders: List<NotificationSender>,
    private val eventBus: EventBus,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val deliveryQueue = Channel<Long>(capacity = Channel.UNLIMITED)
    private val isRunning = AtomicBoolean(false)
    private val senderMap = senders.associateBy { it.channel }

    /**
     * Starts the notification service.
     * - Registers event handlers
     * - Starts the delivery processing loop
     * - Requeues any pending deliveries from database (for recovery after restart)
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            logger.info("Starting NotificationService")
            registerEventHandlers()
            startDeliveryProcessor()
            requeuePendingDeliveries()
            logger.info("NotificationService started successfully")
        }
    }

    /**
     * Stops the notification service gracefully.
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            logger.info("Stopping NotificationService")
            scope.cancel()
            deliveryQueue.close()
            logger.info("NotificationService stopped")
        }
    }

    private fun registerEventHandlers() {
        eventBus.registerHandler { event ->
            when (event) {
                is ReviewSubmittedEvent -> handleReviewSubmitted(event)
                is ReviewReceivedEvent -> handleReviewReceived(event)
                is ManagerChangedEvent -> handleManagerChanged(event)
                is NewDirectReportEvent -> handleNewDirectReport(event)
            }
        }
    }

    private fun startDeliveryProcessor() {
        scope.launch {
            logger.info("Delivery processor started")

            for (deliveryId in deliveryQueue) {
                // Process each delivery asynchronously (don't block the queue)
                launch {
                    processDelivery(deliveryId)
                }
            }

            logger.info("Delivery processor stopped")
        }
    }

    private fun requeuePendingDeliveries() {
        scope.launch {
            try {
                val pendingDeliveries = notificationRepo.getPendingDeliveries(limit = 1000)
                logger.info("Requeuing ${pendingDeliveries.size} pending deliveries from database")

                pendingDeliveries.forEach { delivery ->
                    deliveryQueue.send(delivery.id)
                }
            } catch (e: Exception) {
                logger.error("Failed to requeue pending deliveries", e)
            }
        }
    }

    private suspend fun processDelivery(deliveryId: Long) {
        try {
            // Mark as processing (optimistic locking to prevent duplicate processing)
            if (!notificationRepo.markDeliveryAsProcessing(deliveryId)) {
                logger.debug("Delivery $deliveryId already being processed or not pending")
                return
            }

            // Fetch delivery details
            val deliveryRecord = notificationRepo.getDeliveryById(deliveryId)
            if (deliveryRecord == null) {
                logger.error("Delivery $deliveryId not found")
                return
            }

            val notification = notificationRepo.getNotificationById(deliveryRecord.notificationId)
            if (notification == null) {
                logger.error("Notification ${deliveryRecord.notificationId} not found for delivery $deliveryId")
                return
            }

            // Get the appropriate sender for this channel
            val sender = senderMap[deliveryRecord.channel]
            if (sender == null) {
                logger.error("No sender configured for channel ${deliveryRecord.channel}")
                notificationRepo.markDeliveryAsFailed(deliveryId, "No sender for channel ${deliveryRecord.channel}")
                return
            }

            // Attempt delivery
            val delivery = NotificationDelivery(notification, deliveryRecord)
            when (val result = sender.send(delivery)) {
                is DeliveryResult.Success -> {
                    notificationRepo.markDeliveryAsSent(deliveryId)
                    logger.info("Successfully delivered notification ${notification.id} via ${deliveryRecord.channel}")
                }
                is DeliveryResult.Failure -> {
                    val shouldRetry = notificationRepo.markDeliveryAsFailed(deliveryId, result.error)
                    if (shouldRetry) {
                        logger.warn("Delivery $deliveryId failed: ${result.error}. Will retry.")
                        // Schedule retry (will be picked up by periodic polling or requeue)
                        scheduleRetry(deliveryId, deliveryRecord.attemptCount + 1)
                    } else {
                        logger.error("Delivery $deliveryId failed permanently: ${result.error}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing delivery $deliveryId", e)
            notificationRepo.markDeliveryAsFailed(deliveryId, e.message ?: "Unknown error")
        }
    }

    private fun scheduleRetry(deliveryId: Long, attemptCount: Int) {
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s
        val backoffSeconds = 1L shl (attemptCount - 1)

        scope.launch {
            delay(backoffSeconds * 1000)
            deliveryQueue.send(deliveryId)
        }
    }

    /**
     * Enqueues a delivery for processing.
     */
    private fun enqueueDelivery(deliveryId: Long) {
        scope.launch {
            try {
                deliveryQueue.send(deliveryId)
            } catch (e: Exception) {
                logger.error("Failed to enqueue delivery $deliveryId", e)
            }
        }
    }

    // ------------------ Event Handlers ------------------

    private fun handleReviewSubmitted(event: ReviewSubmittedEvent) {
        // Notify the employee that their review was submitted
        val notificationId = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = event.employeeId,
                type = NotificationType.REVIEW_SUBMITTED,
                title = "Performance Review Submitted",
                message = "Your performance review has been submitted by your reviewer.",
                relatedEntityType = "review",
                relatedEntityId = event.reviewId
            )
        )

        // Enqueue all deliveries for this notification
        notificationRepo.getDeliveriesForNotification(notificationId).forEach { delivery ->
            enqueueDelivery(delivery.id)
        }
    }

    private fun handleReviewReceived(event: ReviewReceivedEvent) {
        // Notify the reviewer that they received feedback (360 review)
        val notificationId = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = event.reviewerId,
                type = NotificationType.REVIEW_RECEIVED,
                title = "Feedback Received",
                message = "You have received performance feedback from a team member.",
                relatedEntityType = "review",
                relatedEntityId = event.reviewId
            )
        )

        notificationRepo.getDeliveriesForNotification(notificationId).forEach { delivery ->
            enqueueDelivery(delivery.id)
        }
    }

    private fun handleManagerChanged(event: ManagerChangedEvent) {
        val notificationId = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = event.employeeId,
                type = NotificationType.MANAGER_CHANGED,
                title = "Manager Changed",
                message = "Your manager has been updated.",
                relatedEntityType = "employee",
                relatedEntityId = event.employeeId
            )
        )

        notificationRepo.getDeliveriesForNotification(notificationId).forEach { delivery ->
            enqueueDelivery(delivery.id)
        }
    }

    private fun handleNewDirectReport(event: NewDirectReportEvent) {
        val employeeName = event.employeeName ?: "An employee"
        val notificationId = notificationRepo.createNotification(
            CreateNotificationRequest(
                userId = event.managerId,
                type = NotificationType.NEW_DIRECT_REPORT,
                title = "New Direct Report",
                message = "$employeeName is now reporting to you.",
                relatedEntityType = "employee",
                relatedEntityId = event.employeeId
            )
        )

        notificationRepo.getDeliveriesForNotification(notificationId).forEach { delivery ->
            enqueueDelivery(delivery.id)
        }
    }
}
