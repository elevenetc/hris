# Performance Reviews

The performance review system allows managers and reviewers to create, edit, and submit performance evaluations for
employees.

## Implementation

- `Routes`: [`reviewRoutes.kt`](../api/src/main/kotlin/org/jetbrains/hris/api/routes/reviewRoutes.kt)
- `Service`: [`ReviewService.kt`](../service-review/src/main/kotlin/org/jetbrains/hris/review/ReviewService.kt)
- `Repository`: [`ReviewRepository.kt`](../service-review/src/main/kotlin/org/jetbrains/hris/review/ReviewRepository.kt)

## Core Endpoints

### Create review

`POST /employees/{id}/reviews`

Creates a new performance review in DRAFT status.

`Scores` (all required, range 1-10):

- `performanceScore` - Overall performance rating
- `softSkillsScore` - Communication and collaboration
- `independenceScore` - Autonomy and self-direction
- `aspirationForGrowthScore` - Learning and development motivation

`Optional fields`:

- `notes` - Additional feedback text

### Get employee reviews

`GET /employees/{id}/reviews`

Returns all reviews for a specific employee with optional filtering.

`Query parameters`:

- `fromDate` - Filter reviews after this date (ISO-8601)
- `toDate` - Filter reviews before this date (ISO-8601)
- `status` - Filter by status (DRAFT or SUBMITTED)
- `limit` - Maximum results (default: 50)
- `offset` - Pagination offset (default: 0)

`Returns`: Reviews sorted by review date descending (most recent first)

### Get review by ID

`GET /reviews/{id}`

Returns a single review by ID.

### Update review

`PUT /reviews/{id}`

Updates a DRAFT review. Only DRAFT reviews can be modified.

`Returns`: Error if review is already SUBMITTED or doesn't exist

### Submit review

`POST /reviews/{id}/submit`

Changes review status from DRAFT to SUBMITTED. After submission, the review becomes `immutable`.

`Side effects`:

- Review status changes to SUBMITTED
- Notification sent to the employee being reviewed
- Review can no longer be edited or deleted

### Delete review

`DELETE /reviews/{id}`

Deletes a DRAFT review. Only DRAFT reviews can be deleted.

### DRAFT status

- Review is editable
- No notifications sent

### SUBMITTED status

- Review is immutable (cannot be edited or deleted)
- Visible in employee's review history
- Notification is sent to the employee being reviewed

## Optimizations and indexes

See [review optimization](review-optimization.md)