package org.jetbrains.hris.infrastructure

import kotlinx.coroutines.Dispatchers
import org.jetbrains.hris.infrastructure.cache.Cache
import org.jetbrains.hris.infrastructure.cache.RedisCache
import org.jetbrains.hris.infrastructure.events.EventBus
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton

/**
 * Kodein DI module for infrastructure components.
 *
 * This module provides core infrastructure services like EventBus and Cache
 * that are shared across the application.
 *
 * @param redisHost Redis server hostname (default: localhost)
 * @param redisPort Redis server port (default: 6379)
 */
fun infrastructureModule(
    redisHost: String,
    redisPort: Int
) = DI.Module("infrastructure") {
    bind<EventBus>() with singleton {
        EventBus(dispatcher = Dispatchers.Default)
    }

    bind<Cache>() with singleton {
        RedisCache(host = redisHost, port = redisPort)
    }
}
