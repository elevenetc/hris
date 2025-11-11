# Caching

`Redis` is used as a cache.

- The most heavy request is full subordinate tree loading. We cache it by employee id.
- We invalidate all cached trees when the organizational structure changes. This is a deliberate choice for a simple
  initial implementation, given that organizational structure changes are rare but reads are frequent.

## TTL

- Tree entries are cached forever for simplicity. A particular TTL could potentially be set based on analytics.

## Metrics

- [CacheMetrics](../infrastructure/src/main/kotlin/org/jetbrains/hris/infrastructure/cache/CacheMetrics.kt) exposed with
  health endpoint.

## References

- [EmployeeService.kt](../service-employee/src/main/kotlin/org/jetbrains/hris/employee/EmployeeService.kt)
- [RedisCache.kt](../infrastructure/src/main/kotlin/org/jetbrains/hris/infrastructure/cache/RedisCache.kt)

## Potential / future improvements

- Implement subtree invalidation (only invalidate affected branches)
- Change Data Capture (CDC) implementation for automatic cache invalidation
- Measure cache hit rate and performance impact, then add more caching to other operations