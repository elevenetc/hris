package org.jetbrains.hris.db.schemas

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.hris.db.utils.ltree

object Employees : LongIdTable(name = "employee") {
    val firstName = varchar("first_name", 200)
    val lastName = varchar("last_name", 200)
    val email = varchar("email", 320).uniqueIndex()
    val position = varchar("position", 255).nullable()
    val managerId = reference(
        "manager_id",
        this,
        onDelete = ReferenceOption.SET_NULL,
        onUpdate = ReferenceOption.CASCADE
    ).nullable()

    init {
        managerId.index("emp_manager_idx")
    }
}

object EmployeePath : Table(name = "employee_path") {
    val employeeId = reference(
        "employee_id",
        Employees,
        onDelete = ReferenceOption.CASCADE,
        onUpdate = ReferenceOption.CASCADE
    ).uniqueIndex()
    val path = ltree("path")
    val depth = integer("depth")
    override val primaryKey = PrimaryKey(employeeId)

    init {
        depth.index("employee_path_depth_idx")
    }
}