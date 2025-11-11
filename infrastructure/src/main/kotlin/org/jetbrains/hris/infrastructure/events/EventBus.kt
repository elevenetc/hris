package org.jetbrains.hris.infrastructure.events

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.hris.common.events.ApplicationEvent
import org.slf4j.LoggerFactory

/**
 * Simple in-memory event bus for publishing and handling domain events.
 * Uses Kotlin coroutines and channels for async event processing.
 *
 * This implementation is suitable for single-instance deployments.
 * For horizontal scaling, events could be partitioned by user_id and
 * routed to specific instances via consistent hashing at the load balancer level.
 *
 * @param dispatcher The coroutine dispatcher for async operations (injectable for testing)
 */
class EventBus(
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val logger = LoggerFactory.getLogger(EventBus::class.java)
    private val eventChannel = Channel<ApplicationEvent>(capacity = Channel.UNLIMITED)
    private val handlers = mutableListOf<suspend (ApplicationEvent) -> Unit>()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    init {
        scope.launch {
            for (event in eventChannel) {
                try {
                    handlers.forEach { handler ->
                        try {
                            handler(event)
                        } catch (e: Exception) {
                            logger.error("Error handling event ${event::class.simpleName}", e)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Fatal error in event bus", e)
                }
            }
        }
    }

    /**
     * Publishes an event to the event bus.
     * Events are processed asynchronously by registered handlers.
     */
    fun publish(event: ApplicationEvent) {
        scope.launch {
            try {
                eventChannel.send(event)
                logger.debug("Published event: ${event::class.simpleName}")
            } catch (e: Exception) {
                logger.error("Failed to publish event ${event::class.simpleName}", e)
            }
        }
    }

    /**
     * Registers a handler for domain events.
     * Handlers are called in the order they are registered.
     */
    fun registerHandler(handler: suspend (ApplicationEvent) -> Unit) {
        handlers.add(handler)
        logger.info("Registered event handler")
    }

    /**
     * Closes the event bus and cancels all coroutines.
     */
    fun close() {
        eventChannel.close()
        scope.cancel()
    }
}
