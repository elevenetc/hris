package org.jetbrains.hris.review

import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton

/**
 * Kodein DI module for the Review Service.
 *
 * Defines how to construct ReviewService and its dependencies.
 * This module can be imported into the main application's DI container.
 *
 * Example usage in Application.kt:
 * ```
 * val di = DI {
 *     import(reviewModule)
 * }
 * val reviewService by di.instance<ReviewService>()
 * ```
 */
val reviewModule = DI.Module("review") {
    // Repository layer
    bind<ReviewRepository>() with singleton {
        ReviewRepository()
    }

    // Service layer
    bind<ReviewService>() with singleton {
        ReviewService(
            reviewRepo = instance(),
            eventBus = instance()
        )
    }
}
