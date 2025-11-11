package org.jetbrains.hris.notification

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.common.models.Notification
import org.jetbrains.hris.db.schemas.DeliveryStatus
import org.jetbrains.hris.db.schemas.Employees
import org.jetbrains.hris.db.schemas.NotificationChannel
import org.jetbrains.hris.db.schemas.NotificationDeliveries
import org.jetbrains.hris.db.schemas.NotificationType
import org.jetbrains.hris.db.schemas.Notifications
import kotlin.time.Duration.Companion.seconds

data class NotificationDeliveryRecord(
    val id: Long,
    val notificationId: Long,
    val channel: NotificationChannel,
    val status: DeliveryStatus,
    val attemptCount: Int,
    val lastAttemptAt: Instant?,
    val sentAt: Instant?,
    val errorMessage: String?,
    val nextRetryAt: Instant?
)

data class CreateNotificationRequest(
    val userId: Long,
    val type: NotificationType,
    val title: String,
    val message: String,
    val relatedEntityType: String? = null,
    val relatedEntityId: Long? = null,
    val channels: List<NotificationChannel> = NotificationChannel.entries
)

/**
 * Repository for managing notifications and their deliveries.
 */
class NotificationRepository {

    /**
     * Creates a notification and schedules it for delivery on all specified channels.
     * Returns the created notification ID.
     */
    fun createNotification(request: CreateNotificationRequest): Long = transaction {
        val now = Clock.System.now()

        val notificationId = Notifications.insertAndGetId { row ->
            row[userId] = EntityID(request.userId, Employees)
            row[type] = request.type
            row[title] = request.title
            row[message] = request.message
            row[relatedEntityType] = request.relatedEntityType
            row[relatedEntityId] = request.relatedEntityId
            row[createdAt] = now
            row[readAt] = null
        }.value

        NotificationDeliveries.batchInsert(request.channels) { channel ->
            this[NotificationDeliveries.notificationId] = EntityID(notificationId, Notifications)
            this[NotificationDeliveries.channel] = channel
            this[NotificationDeliveries.status] = DeliveryStatus.PENDING
            this[NotificationDeliveries.attemptCount] = 0
            this[NotificationDeliveries.lastAttemptAt] = null
            this[NotificationDeliveries.sentAt] = null
            this[NotificationDeliveries.errorMessage] = null
            this[NotificationDeliveries.nextRetryAt] = now
        }

        notificationId
    }

    /**
     * Fetches a notification by ID.
     */
    fun getNotificationById(id: Long): Notification? = transaction {
        Notifications
            .selectAll()
            .where { Notifications.id eq EntityID(id, Notifications) }
            .singleOrNull()
            ?.toModel()
    }

    /**
     * Fetches notifications for a specific user with optional filtering.
     * @param userId The user whose notifications to fetch
     * @param unreadOnly If true, only return unread notifications
     * @param limit Maximum number of results (default 50)
     * @param offset Offset for pagination (default 0)
     * @return List of notifications sorted by createdAt descending (most recent first)
     */
    fun getUserNotifications(
        userId: Long,
        unreadOnly: Boolean = false,
        limit: Int = 50,
        offset: Long = 0
    ): List<Notification> = transaction {
        var query = Notifications
            .selectAll()
            .where { Notifications.userId eq EntityID(userId, Employees) }

        if (unreadOnly) {
            query = query.andWhere { Notifications.readAt.isNull() }
        }

        query
            .orderBy(Notifications.createdAt, SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toModel() }
    }

    /**
     * Counts unread notifications for a user.
     */
    fun countUnreadNotifications(userId: Long): Long = transaction {
        Notifications
            .selectAll()
            .where {
                (Notifications.userId eq EntityID(userId, Employees)) and
                        Notifications.readAt.isNull()
            }
            .count()
    }

    /**
     * Marks a notification as read.
     * Returns true if successful, false if notification doesn't exist or belongs to another user.
     */
    fun markAsRead(notificationId: Long, userId: Long): Boolean = transaction {
        val updated = Notifications.update({
            (Notifications.id eq EntityID(notificationId, Notifications)) and
                    (Notifications.userId eq EntityID(userId, Employees)) and
                    Notifications.readAt.isNull()
        }) { row ->
            row[readAt] = Clock.System.now()
        }
        updated > 0
    }

    /**
     * Marks all notifications as read for a user.
     * Returns the number of notifications marked as read.
     */
    fun markAllAsRead(userId: Long): Int = transaction {
        Notifications.update({
            (Notifications.userId eq EntityID(userId, Employees)) and
                    Notifications.readAt.isNull()
        }) { row ->
            row[readAt] = Clock.System.now()
        }
    }

    /**
     * Deletes a notification.
     * Returns true if deleted, false if notification doesn't exist or belongs to another user.
     */
    fun deleteNotification(notificationId: Long, userId: Long): Boolean = transaction {
        val deleted = Notifications.deleteWhere {
            (Notifications.id eq EntityID(notificationId, Notifications)) and
                    (Notifications.userId eq EntityID(userId, Employees))
        }
        deleted > 0
    }

    // ------------------ Delivery Management ------------------

    /**
     * Fetches pending deliveries that are ready to be processed.
     * @param limit Maximum number of deliveries to fetch
     * @return List of deliveries that need processing
     */
    fun getPendingDeliveries(limit: Int): List<NotificationDeliveryRecord> = transaction {
        val now = Clock.System.now()

        NotificationDeliveries
            .selectAll()
            .where {
                (NotificationDeliveries.status eq DeliveryStatus.PENDING) and
                        (NotificationDeliveries.nextRetryAt lessEq now)
            }
            .limit(limit)
            .map { it.toDeliveryRecord() }
    }

    /**
     * Marks a delivery as processing.
     */
    fun markDeliveryAsProcessing(deliveryId: Long): Boolean = transaction {
        val updated = NotificationDeliveries.update({
            (NotificationDeliveries.id eq EntityID(deliveryId, NotificationDeliveries)) and
                    (NotificationDeliveries.status eq DeliveryStatus.PENDING)
        }) { row ->
            row[status] = DeliveryStatus.PROCESSING
            row[lastAttemptAt] = Clock.System.now()
        }
        updated > 0
    }

    /**
     * Marks a delivery as sent successfully.
     */
    fun markDeliveryAsSent(deliveryId: Long): Boolean = transaction {
        val updated = NotificationDeliveries.update({
            NotificationDeliveries.id eq EntityID(deliveryId, NotificationDeliveries)
        }) { row ->
            row[status] = DeliveryStatus.SENT
            row[sentAt] = Clock.System.now()
            row[nextRetryAt] = null
        }
        updated > 0
    }

    /**
     * Marks a delivery as failed and schedules retry with exponential backoff.
     * @param deliveryId The delivery ID
     * @param errorMsg Error message to store
     * @param maxRetries Maximum retry attempts (default 5)
     * @return true if retry scheduled, false if max retries reached
     */
    fun markDeliveryAsFailed(
        deliveryId: Long,
        errorMsg: String,
        maxRetries: Int = 5
    ): Boolean = transaction {
        val delivery = NotificationDeliveries
            .selectAll()
            .where { NotificationDeliveries.id eq EntityID(deliveryId, NotificationDeliveries) }
            .singleOrNull()
            ?: return@transaction false

        val newAttemptCount = delivery[NotificationDeliveries.attemptCount] + 1

        if (newAttemptCount >= maxRetries) {
            // Max retries reached, mark as permanently failed
            NotificationDeliveries.update({
                NotificationDeliveries.id eq EntityID(deliveryId, NotificationDeliveries)
            }) { row ->
                row[status] = DeliveryStatus.FAILED
                row[attemptCount] = newAttemptCount
                row[errorMessage] = errorMsg
                row[nextRetryAt] = null
            }
            return@transaction false
        } else {
            // Schedule retry with exponential backoff: 1s, 2s, 4s, 8s, 16s
            val backoffSeconds = 1L shl (newAttemptCount - 1) // 2^(attempts-1)
            val nextRetry = Clock.System.now().plus(backoffSeconds.seconds)

            NotificationDeliveries.update({
                NotificationDeliveries.id eq EntityID(deliveryId, NotificationDeliveries)
            }) { row ->
                row[status] = DeliveryStatus.PENDING
                row[attemptCount] = newAttemptCount
                row[errorMessage] = errorMsg
                row[nextRetryAt] = nextRetry
            }
            return@transaction true
        }
    }

    /**
     * Gets deliveries for a notification.
     */
    fun getDeliveriesForNotification(notificationId: Long): List<NotificationDeliveryRecord> = transaction {
        NotificationDeliveries
            .selectAll()
            .where { NotificationDeliveries.notificationId eq EntityID(notificationId, Notifications) }
            .map { it.toDeliveryRecord() }
    }

    /**
     * Gets a single delivery by its ID.
     */
    fun getDeliveryById(deliveryId: Long): NotificationDeliveryRecord? = transaction {
        NotificationDeliveries
            .selectAll()
            .where { NotificationDeliveries.id eq EntityID(deliveryId, NotificationDeliveries) }
            .singleOrNull()
            ?.toDeliveryRecord()
    }

    private fun ResultRow.toModel() = Notification(
        id = this[Notifications.id].value,
        userId = this[Notifications.userId].value,
        type = this[Notifications.type].name,
        title = this[Notifications.title],
        message = this[Notifications.message],
        relatedEntityType = this[Notifications.relatedEntityType],
        relatedEntityId = this[Notifications.relatedEntityId],
        createdAt = this[Notifications.createdAt].toString(),
        readAt = this[Notifications.readAt]?.toString()
    )

    private fun ResultRow.toDeliveryRecord() = NotificationDeliveryRecord(
        id = this[NotificationDeliveries.id].value,
        notificationId = this[NotificationDeliveries.notificationId].value,
        channel = this[NotificationDeliveries.channel],
        status = this[NotificationDeliveries.status],
        attemptCount = this[NotificationDeliveries.attemptCount],
        lastAttemptAt = this[NotificationDeliveries.lastAttemptAt],
        sentAt = this[NotificationDeliveries.sentAt],
        errorMessage = this[NotificationDeliveries.errorMessage],
        nextRetryAt = this[NotificationDeliveries.nextRetryAt]
    )
}
