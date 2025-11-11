package org.jetbrains.hris.api.routes

import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.hris.api.utils.getLongOrFail
import org.jetbrains.hris.common.exceptions.notFoundException
import org.jetbrains.hris.notification.NotificationRepository

fun Route.notificationRoutes(notificationRepo: NotificationRepository) {
    route("/notifications") {
        get {
            val userId = call.request.queryParameters.getLongOrFail("userId")
            val unreadOnly = call.request.queryParameters["unreadOnly"]?.toBoolean() ?: false
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
            val offset = call.request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0

            val notifications = notificationRepo.getUserNotifications(userId, unreadOnly, limit, offset)
            call.respond(notifications)
        }

        get("/unread/count") {
            val userId = call.request.queryParameters.getLongOrFail("userId")

            val count = notificationRepo.countUnreadNotifications(userId)
            call.respond(mapOf("count" to count))
        }

        patch("/read-all") {
            val userId = call.request.queryParameters.getLongOrFail("userId")

            val count = notificationRepo.markAllAsRead(userId)
            call.respond(mapOf("success" to true, "count" to count))
        }

        patch("/{id}/read") {
            val notificationId = call.parameters.getLongOrFail("id")
            val userId = call.request.queryParameters.getLongOrFail("userId")

            val success = notificationRepo.markAsRead(notificationId, userId)

            if (!success) {
                notFoundException("Notification not found or already read")
            }

            call.respond(mapOf("success" to true))
        }

        delete("/{id}") {
            val notificationId = call.parameters.getLongOrFail("id")
            val userId = call.request.queryParameters.getLongOrFail("userId")

            val success = notificationRepo.deleteNotification(notificationId, userId)

            if (!success) {
                notFoundException("Notification not found")
            }

            call.respond(mapOf("success" to true))
        }
    }
}
