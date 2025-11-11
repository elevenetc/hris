package org.jetbrains.hris.db

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.db.schemas.*

/**
 * Initializes database schema and indexes.
 *
 * Assumes that Database.connect(...) has already been called by the caller.
 */
fun initDatabase() {
    transaction {
        exec("CREATE EXTENSION IF NOT EXISTS ltree;")

        SchemaUtils.createMissingTablesAndColumns(
            Employees, EmployeePath,
            Reviews,
            Notifications, NotificationDeliveries
        )

        // Create GIST index for LTREE path (must use raw SQL - GIST not supported by Exposed DSL)
        exec("CREATE INDEX IF NOT EXISTS employee_path_gist_idx ON employee_path USING GIST (path);")

        // Partial index for countUnreadNotifications()
        exec("""
            CREATE INDEX IF NOT EXISTS idx_notifications_unread
            ON notification(user_id)
            WHERE read_at IS NULL;
        """)
    }
}
