package org.jetbrains.hris.notification

import kotlinx.coroutines.Dispatchers
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton

/**
 * Kodein DI module for the Notification Service.
 *
 * Defines how to construct NotificationService and its dependencies.
 * This module can be imported into the main application's DI container.
 *
 * Example usage in Application.kt:
 * ```
 * val di = DI {
 *     import(notificationModule)
 * }
 * val notificationService by di.instance<NotificationService>()
 * notificationService.start()  // Manual lifecycle management
 * ```
 */
val notificationModule = DI.Module("notification") {
    // Repository layer
    bind<NotificationRepository>() with singleton {
        NotificationRepository()
    }

    // Notification senders
    bind<List<NotificationSender>>() with singleton {
        listOf(
            EmailSender(),
            BrowserSender(),
            MobileSender(),
            SlackSender()
        )
    }

    // Service layer
    bind<NotificationService>() with singleton {
        NotificationService(
            notificationRepo = instance(),
            senders = instance(),
            eventBus = instance(),
            dispatcher = Dispatchers.Default
        )
    }
}
