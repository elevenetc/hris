package org.jetbrains.hris.common.models

import kotlinx.serialization.Serializable

/**
 * Domain model representing a notification.
 * Used across all layers: Repository, Service, and API.
 */
@Serializable
data class Notification(
    val id: Long,
    val userId: Long,
    val type: String,
    val title: String,
    val message: String,
    val relatedEntityType: String? = null,
    val relatedEntityId: Long? = null,
    val createdAt: String,
    val readAt: String? = null
)
