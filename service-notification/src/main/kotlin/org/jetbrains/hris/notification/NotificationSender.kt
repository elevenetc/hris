package org.jetbrains.hris.notification

import org.jetbrains.hris.common.models.Notification
import org.jetbrains.hris.db.schemas.NotificationChannel
import org.slf4j.LoggerFactory

/**
 * Result of a notification delivery attempt.
 */
sealed class DeliveryResult {
    object Success : DeliveryResult()
    data class Failure(val error: String) : DeliveryResult()
}

/**
 * Data class combining notification and delivery information for sending.
 */
data class NotificationDelivery(
    val notification: Notification,
    val delivery: NotificationDeliveryRecord
)

/**
 * Interface for sending notifications through different channels.
 * Implementations should handle the actual delivery mechanism.
 */
interface NotificationSender {
    /**
     * The channel this sender handles.
     */
    val channel: NotificationChannel

    /**
     * Sends a notification delivery.
     * @return DeliveryResult indicating success or failure with error message
     */
    suspend fun send(delivery: NotificationDelivery): DeliveryResult
}

/**
 * Email notification sender.
 * In production, this would integrate with an email service (SendGrid, AWS SES, etc.).
 */
class EmailSender : NotificationSender {
    private val logger = LoggerFactory.getLogger(EmailSender::class.java)
    override val channel = NotificationChannel.EMAIL

    override suspend fun send(delivery: NotificationDelivery): DeliveryResult {
        val notification = delivery.notification
        logger.info(
            "EMAIL: To userId=${notification.userId} | " +
            "Subject: ${notification.title} | " +
            "Message: ${notification.message}"
        )

        // In production: call email service API
        // For now, just log and simulate success
        return DeliveryResult.Success
    }
}

/**
 * Browser push notification sender.
 * In production, this would integrate with Web Push API or similar service.
 */
class BrowserSender : NotificationSender {
    private val logger = LoggerFactory.getLogger(BrowserSender::class.java)
    override val channel = NotificationChannel.BROWSER

    override suspend fun send(delivery: NotificationDelivery): DeliveryResult {
        val notification = delivery.notification
        logger.info(
            "BROWSER: To userId=${notification.userId} | " +
            "Type: ${notification.type} | " +
            "Title: ${notification.title}"
        )

        // In production: send web push notification
        // For now, the notification exists in DB and user can fetch via API
        return DeliveryResult.Success
    }
}

/**
 * Mobile push notification sender.
 * In production, this would integrate with FCM (Firebase Cloud Messaging) or APNs (Apple Push Notification service).
 */
class MobileSender : NotificationSender {
    private val logger = LoggerFactory.getLogger(MobileSender::class.java)
    override val channel = NotificationChannel.MOBILE

    override suspend fun send(delivery: NotificationDelivery): DeliveryResult {
        val notification = delivery.notification
        logger.info(
            "MOBILE: To userId=${notification.userId} | " +
            "Type: ${notification.type} | " +
            "Title: ${notification.title} | " +
            "Message: ${notification.message}"
        )

        // In production: call FCM/APNs API
        // For now, just log and simulate success
        return DeliveryResult.Success
    }
}

/**
 * Slack notification sender.
 * In production, this would integrate with Slack's incoming webhooks or API.
 */
class SlackSender : NotificationSender {
    private val logger = LoggerFactory.getLogger(SlackSender::class.java)
    override val channel = NotificationChannel.SLACK

    override suspend fun send(delivery: NotificationDelivery): DeliveryResult {
        val notification = delivery.notification
        logger.info(
            "SLACK: To userId=${notification.userId} | " +
            "Type: ${notification.type} | " +
            "Message: ${notification.title} - ${notification.message}"
        )

        // In production: call Slack API
        // For now, just log and simulate success
        return DeliveryResult.Success
    }
}
