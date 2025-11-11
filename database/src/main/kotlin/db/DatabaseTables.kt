package org.jetbrains.hris.db

import org.jetbrains.hris.db.schemas.EmployeePath
import org.jetbrains.hris.db.schemas.Employees
import org.jetbrains.hris.db.schemas.NotificationDeliveries
import org.jetbrains.hris.db.schemas.Notifications
import org.jetbrains.hris.db.schemas.Reviews

/**
 * Central utility for accessing database table names in a type-safe way.
 *
 * This provides:
 * - Single source of truth for table names (via Exposed table objects)
 * - Compile-time safety when referencing tables
 * - Convenient helper methods for common operations
 *
 * Example usage:
 * ```
 * // Access individual table names
 * val tableName = DatabaseTables.employees
 *
 * // Use helper methods in tests
 * exec(DatabaseTables.truncateAll())
 * exec(DatabaseTables.truncateNotifications())
 * ```
 */
object DatabaseTables {
    // Employee tables
    val employees: String get() = Employees.tableName
    val employeePath: String get() = EmployeePath.tableName

    // Review tables
    val reviews: String get() = Reviews.tableName

    // Notification tables
    val notifications: String get() = Notifications.tableName
    val notificationDeliveries: String get() = NotificationDeliveries.tableName

    /**
     * Generates SQL to truncate all tables in dependency order with CASCADE.
     * Useful for cleaning up the entire database in tests.
     */
    fun truncateAll(): String {
        return buildString {
            append("TRUNCATE TABLE ")
            append(listOf(
                notificationDeliveries,
                notifications,
                reviews,
                employeePath,
                employees
            ).joinToString(", "))
            append(" RESTART IDENTITY CASCADE;")
        }
    }

    /**
     * Generates SQL to truncate only notification-related tables.
     * Useful for tests that only need to clean notification data.
     */
    fun truncateNotifications(): String {
        return buildString {
            append("TRUNCATE TABLE ")
            append(listOf(
                notificationDeliveries,
                notifications
            ).joinToString(", "))
            append(" RESTART IDENTITY CASCADE;")
        }
    }

    /**
     * Generates SQL to truncate employee tables.
     * Useful for tests that need to clean core HR data but not reviews or notifications.
     */
    fun truncateEmployees(): String {
        return buildString {
            append("TRUNCATE TABLE ")
            append(listOf(
                employeePath,
                employees
            ).joinToString(", "))
            append(" RESTART IDENTITY CASCADE;")
        }
    }

    /**
     * Generates SQL to truncate employee and review tables.
     * Useful for tests that need to clean most data except notifications.
     */
    fun truncateEmployeesAndReviews(): String {
        return buildString {
            append("TRUNCATE TABLE ")
            append(listOf(
                reviews,
                employeePath,
                employees
            ).joinToString(", "))
            append(" RESTART IDENTITY CASCADE;")
        }
    }
}
