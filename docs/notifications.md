# Notifications

The service delivers 4 types of notifications:

- Review submitted
- Review received
- Manager changed
- New direct report

## Implementation

[NotificationService](../service-notification/src/main/kotlin/org/jetbrains/hris/notification/NotificationService.kt)
subscribes to [EventBus](../infrastructure/src/main/kotlin/org/jetbrains/hris/infrastructure/events/EventBus.kt) for
notification requests.

Every notification passes through 3 states: `PENDING`, `PROCESSING`, `SENT` or `FAILED`:

1. `eventBus.publish(ApplicationEvent)`
2. `NotificationService` handles event by type and creates `NotificationDeliveries` database record with state
   `DeliveryStatus.PENDING`
3. The request is enqueued to `kotlinx.coroutines.channels.Channel`
4. Every request is then handled in `processDelivery`
    - Verify if the request is not processed yet
    - Verify that notification exists
5. Send to appropriate channel and set delivery status to either `SENT` or `FAILED`
6. In `FAILED` case we update `attemptCount` and `nextRetryAt` with exponential backoff

## Optimistic locking for duplicate prevention

On step `4` we verify that notification request is not processed yet, so other nodes don't send the duplicated
notification.

## Potential / future improvements

- The optimistic locking implementation is very basic, for a production, large-scale system, a proper
  solution with an external queue might be more suitable.
- We deliberately keep `FAILED` undelivered deliberately for simplicity. A future implementation might include an admin
  endpoint to retry failed deliveries, potentially triggered by a scheduled job.

## Optimizations

See [notifications-optimization](notifications-optimization.md) for performance details.