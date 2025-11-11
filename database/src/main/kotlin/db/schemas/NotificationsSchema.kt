package org.jetbrains.hris.db.schemas

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Notifications : LongIdTable(name = "notification") {
    val userId = reference("user_id", Employees, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val type = enumerationByName<NotificationType>("type", length = 64)
    val title = text("title")
    val message = text("message")
    val relatedEntityType = varchar("related_entity_type", 64).nullable()
    val relatedEntityId = long("related_entity_id").nullable()
    val createdAt = timestamp("created_at")
    val readAt = timestamp("read_at").nullable()

    init {
        index(false, userId, createdAt)
    }
}

object NotificationDeliveries : LongIdTable(name = "notification_delivery") {
    val notificationId = reference(
        "notification_id",
        Notifications,
        onDelete = ReferenceOption.CASCADE,
        onUpdate = ReferenceOption.CASCADE
    )
    val channel = enumerationByName<NotificationChannel>("channel", length = 32)
    val status = enumerationByName<DeliveryStatus>("status", length = 32).default(DeliveryStatus.PENDING)
    val attemptCount = integer("attempt_count").default(0)
    val lastAttemptAt = timestamp("last_attempt_at").nullable()
    val sentAt = timestamp("sent_at").nullable()
    val errorMessage = text("error_message").nullable()
    val nextRetryAt = timestamp("next_retry_at").nullable()

    init {
        index(false, status, nextRetryAt)
        notificationId.index("notification_delivery_notification_idx")
    }
}

enum class NotificationType {
    REVIEW_SUBMITTED,
    REVIEW_RECEIVED,
    MANAGER_CHANGED,
    NEW_DIRECT_REPORT
}

enum class NotificationChannel {
    EMAIL,
    BROWSER,
    MOBILE,
    SLACK
}

enum class DeliveryStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED
}