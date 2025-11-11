# Notifications feature optimization

`Notifications` are tightly bound with `reviews`, hence there supposed to be lots of notifications.

## Optimizations

### 1. Indexes

- `Notifications (userId, createdAt)` - Critical for `getUserNotifications()`
- `NotificationDeliveries (status, nextRetryAt)` - Critical for `getPendingDeliveries()` 
- `NotificationDeliveries (notificationId)` - Critical for `deleteNotification()` and cascade delete of `Notifications`
- Partial index for reading `unread` notifications.
  See [initDatabase.kt](../database/src/main/kotlin/db/initDatabase.kt)

### 2. Notifications

- Notifications about new reviews are deliver asynchronously and not bound directly to write performance. See
  details [review-optimization.md](review-optimization.md)

### 3. Batch inserts

- `NotificationDeliveries` inserted as a batch for every notification channel.

## Possible/future optimizations

### 1. DB indexes

- `(type)` - when analytics and/or monitoring of notifications would require improved reading by type (or similar)

### 2. Implementation

- Currently notifications depend
  on [EventBus](../infrastructure/src/main/kotlin/org/jetbrains/hris/infrastructure/events/EventBus.kt), which is basic
  local implementation. Increased amount of notifications would require external and advanced queue implementation,
  which can be further employ partitioning and throttling.