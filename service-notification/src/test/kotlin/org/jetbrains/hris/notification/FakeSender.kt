package org.jetbrains.hris.notification

import org.jetbrains.hris.db.schemas.NotificationChannel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake notification sender for testing.
 * Captures all sent notifications instead of actually delivering them.
 *
 * Thread-safe to support concurrent delivery processing.
 */
class FakeSender(
    override val channel: NotificationChannel,
    private val shouldFail: Boolean = false,
    private val failureMessage: String = "Simulated failure"
) : NotificationSender {

    private val _sentDeliveries = CopyOnWriteArrayList<NotificationDelivery>()

    /**
     * All deliveries that were sent through this sender.
     */
    val sentDeliveries: List<NotificationDelivery>
        get() = _sentDeliveries.toList()

    /**
     * Clear the history of sent deliveries.
     */
    fun clear() {
        _sentDeliveries.clear()
    }

    override suspend fun send(delivery: NotificationDelivery): DeliveryResult {
        return if (shouldFail) {
            DeliveryResult.Failure(failureMessage)
        } else {
            _sentDeliveries.add(delivery)
            DeliveryResult.Success
        }
    }
}
