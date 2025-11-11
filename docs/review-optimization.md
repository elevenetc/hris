# Review feature optimization

Since review is heavy-write feature we have several optimizations.

## Optimizations

### 1. DB connection

- increased pool size
- increased amount of warm connection
- decreased connection timeout to fail fast

### 2. Indexes

- `(employeeId, reviewDate)` - Critical for `getEmployeeReviews()` query
- `reviewerId` - Critical for `getReviewsByReviewer()` query
- no other indexes to increase write performance

### 3. Notifications

- Notifications about new reviews are deliver asynchronously and not bound directly to write performance. See
  details [notifications-optimization.md](notifications-optimization.md).

## Possible/future optimizations

### 1. DB partitioning

- by `review_date` with monthly ranges