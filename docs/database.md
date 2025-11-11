# Database

## Schema

See sources at [schemas](../database/src/main/kotlin/db/schemas)

### Employees

- `employee` stores user data and reference to manager
- `employee_path` stores the organizational hierarchy using `PostgreSQL's` `ltree` extension for efficient lookup of
  subordinates and supervisors

### Reviews

- `review` stores review data and references to reviewer and employee.

### Notifications

- `notification` stores notification data
- `notification_delivery` stores status of notification delivery and target delivery channel

## Optimizations and indexes

See detailed optimization guides:

- [Review Write Optimization](review-optimization.md)
- [Notification Optimization](notifications-optimization.md)

## Why ltree and not closure + adjacency tables

- Ltree is simpler for this task and has similar performance for current operations
- For real production systems, migrating from ltree to closure + adjacency tables is easier than migrating backward

## Potential / future improvements

- Table partitioning by organization or by time period for reviews and notifications
- Since the organizational structure doesn't change often, read replicas would improve read performance