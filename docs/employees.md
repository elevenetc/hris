# Employees

The employee feature handles organizational hierarchy with single-manager structure.

## Implementation

- `Routes`: [`employeeRoutes.kt`](../api/src/main/kotlin/org/jetbrains/hris/api/routes/employeeRoutes.kt)
- `Service`: [`EmployeeService.kt`](../service-employee/src/main/kotlin/org/jetbrains/hris/employee/EmployeeService.kt)
- `Repository`: [`EmployeeRepository.kt`](../service-employee/src/main/kotlin/org/jetbrains/hris/employee/EmployeeRepository.kt)

## Core Endpoints

### Create employee

`POST /employees`

Creates a new employee in the organizational hierarchy.

`Required fields`:

- `firstName` - Employee first name
- `lastName` - Employee last name
- `email` - Email address
- `position` - Job title

`Optional fields`:

- `managerId` - Direct manager ID (null for root employees like CEO)

### Get employee by ID

`GET /employees/{id}`

Returns employee information by ID.

### Get employee homepage

`GET /employees/{id}/homepage`

Returns aggregated data for employee homepage including manager, peers, and subordinates.

See [Homepage](homepage.md) for details.

### Get manager

`GET /employees/{id}/manager`

Returns the direct manager for an employee.

`Returns`: Manager employee object or 404 if employee has no manager

### Get subordinates

`GET /employees/{id}/subordinates`

Returns direct reports for a manager (one level down).

`Returns`: List of employees who report directly to this manager

### Get full subordinate tree

`GET /employees/{id}/subordinates-full-tree`

Returns complete organizational tree below an employee (all levels).

Uses ltree for efficient hierarchical queries.

### Change manager

`PATCH /employees/{id}/manager`

Changes an employee's direct manager.

`Body`:

- `newManagerId` - New manager ID (null to make employee a root)

`Side effects`:

- Updates employee path in ltree structure
- Updates all descendant paths
- Sends notification to employee and new manager

### Delete employee

`DELETE /employees/{id}`

Deletes an employee from the organization.

`Query parameters`:

- `cascade` - If true, deletes employee and all subordinates. If false (default), only deletes if employee has no subordinates

`Returns`:
- Success with `removedCount` if cascade=true
- 409 Conflict if employee has subordinates and cascade=false

## Organizational hierarchy

The system uses PostgreSQL's `ltree` extension to store organizational structure efficiently.

### Structure

- Single-manager hierarchy (each employee has at most one manager)
- Root employees (like CEO) have no manager
- ltree paths enable fast queries for subordinates and ancestors

### Path format

Employee paths are stored as ltree values:
```
CEO: 1
VP: 1.2
Manager: 1.2.5
Employee: 1.2.5.10
```

### Hierarchy operations

`Get all subordinates`:
```sql
SELECT * FROM employee_path WHERE path <@ 'manager.path'
```

`Get ancestors`:
```sql
SELECT * FROM employee_path WHERE path @> 'employee.path'
```

## Notifications

Manager changes trigger notifications:

- `Manager changed` - Sent to the employee being moved
- `New direct report` - Sent to the new manager

See [Notifications](notifications.md) for delivery details.

## Caching

Employee tree queries are cached in Redis for performance.

See [Caching](caching.md) for details.
