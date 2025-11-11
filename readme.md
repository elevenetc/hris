# HRIS

HRIS (Human Resources Information System) backend project.

## Setup and run

1. Copy [.env.example](.env.example) to `.env`

2. Launch database and Redis services with (alternatively, use `Docker: up` IntelliJ configuration):

```bash
docker compose up -d
```

3. Run app (alternatively, use `Run app` IntelliJ configuration):

```bash
./gradlew :application:run
```

4. Verify the app is running:

```bash
curl http://localhost:8080/health
```

5. Run an HTTP request to create organizational structure
   with [create-company.http](http-requests/scenarios/create-company.http)

6. Stop containers and clear data (alternatively, use `Docker: stop and clear` IntelliJ configuration):

```bash
docker compose down -v
```

## Testing

Run IJ configuration `Run all tests` or:

```bash
./gradlew test
```

## Documentation

### Architecture & Design

- [Architecture](docs/architecture.md) - System layers and component structure
- [Database](docs/database.md) - Database schema and structure
- [Scaling](docs/scaling.md) - Horizontal scaling considerations

### Features

- [Employees](docs/employees.md) - Employee management and organizational hierarchy
- [Reviews](docs/reviews.md) - Performance review system
- [Notifications](docs/notifications.md) - Notification delivery system
- [Homepage](docs/homepage.md) - Homepage endpoint details

### Performance & Optimization

- [Review Optimization](docs/review-optimization.md) - Write performance optimizations for reviews
- [Notification Optimization](docs/notifications-optimization.md) - Notification system performance
- [Caching](docs/caching.md) - Redis caching strategy