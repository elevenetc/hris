# Scaling

The service is designed for stateless, horizontal scaling with deliberate simplifications. All application logic is
stateless:

- No in-memory session storage
- No sticky sessions required
- Any node can handle any request

## What scales horizontally

- API requests (stateless - add more nodes)
- Notification delivery (optimistic locking prevents duplicates)
- Cache reads (Redis shared across nodes)

## Current bottlenecks:

- Database writes (single PostgreSQL instance)
- Notification polling (all nodes poll for pending deliveries - inefficient at scale)

## Implemented optimizations

- [Review Write Optimization](review-optimization.md) - Connection pooling, batch inserts
- [Notification Optimization](notifications-optimization.md) - Async delivery, retry logic
- [Caching](caching.md) - Redis for employee tree caching

## Future scaling strategies

### Phase 1: Read replicas

- Offload read queries to `PostgreSQL` replicas
- Keep primary for writes only

### Phase 2: External queue

- Replace in-memory Channel with Kafka/RabbitMQ
- Distribute notification delivery across nodes

### Phase 3: Database partitioning

- Partition reviews by date
- Partition notifications by user_id

