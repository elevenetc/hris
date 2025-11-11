# Homepage endpoint

The `/employees/{id}/homepage` endpoint provides aggregated data for rendering an employee's homepage.

## Implementation

- `Route`: [`GET /employees/{id}/homepage`](../api/src/main/kotlin/org/jetbrains/hris/api/routes/employeeRoutes.kt)
- `Service`: [
  `EmployeeService.getHomepageData()`](../service-employee/src/main/kotlin/org/jetbrains/hris/employee/EmployeeService.kt)

## Response data

Returns the following information:

- `Employee information` - The employee's profile data
- `Manager information` - Direct manager details (if exists)
- `Peers` - Other employees with the same manager (same level in hierarchy)
- `Subordinates` - Direct reports (only if the requested employee is a manager)

## Related endpoints

- `Expand subordinates`: `GET /employees/{id}/subordinates` - Returns direct reports for a manager
- `Get manager`: `GET /employees/{id}/manager` - Returns the direct manager for an employee
- `Full tree`: `GET /employees/{id}/tree` - Returns the complete organizational tree below an employee

## Caching

This endpoint uses [Redis caching](caching.md) for the full employee tree structure, significantly improving response
time for large organizational hierarchies.