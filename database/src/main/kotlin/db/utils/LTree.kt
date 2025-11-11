/**
 * PostgreSQL ltree support for Exposed ORM.
 *
 * Note: This is a basic implementation using string manipulation for path operations.
 * It relies on PostgreSQL ltree format guarantees (labels separated by dots).
 * For production use with complex path operations, consider using PostgreSQL native ltree functions
 * (e.g., subpath, subltree, nlevel) instead of string concatenation.
 */
package org.jetbrains.hris.db.utils

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.hris.db.schemas.EmployeePath
import org.jetbrains.hris.db.schemas.Employees
import org.postgresql.util.PGobject
import java.sql.Clob

@JvmInline
value class LTree(val value: String) {
    override fun toString(): String = value
}

object LTreeColumnType : ColumnType<LTree>() {
    override fun sqlType(): String = "LTREE"
    override fun nonNullValueToString(value: LTree): String = "'${value.value}'"
    override fun notNullValueToDB(value: LTree): Any {
        val pg = PGobject()
        pg.type = "ltree"
        pg.value = value.value
        return pg
    }

    override fun valueFromDB(value: Any): LTree = when (value) {
        is LTree -> value
        is String -> LTree(value)
        is Clob -> LTree(value.characterStream.readText())
        else -> LTree(value.toString())
    }
}

fun Table.ltree(name: String): Column<LTree> = registerColumn(name, LTreeColumnType)

/**
 * Represents a PostgreSQL `ltree` containment operation (`<@`).
 *
 * This class is used to build SQL queries that check whether the `LTree`
 * value represented by the left-hand side (`a`) is contained within
 * the `LTree` value represented by the right-hand side (`b`).
 *
 * @constructor Creates an instance of the containment operation with the given expressions.
 * @param a The left-hand side `LTree` expression.
 * @param b The right-hand side `LTree` expression.
 */
class LtreeContainedIn(private val a: Expression<LTree>, private val b: Expression<LTree>) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder { append(a, " <@ ", b) }
    }
}

fun buildLTree(employeeId: Long, managerId: Long?): LTree {
    val label = "e$employeeId"
    return if (managerId == null) {
        LTree("company.$label")
    } else {
        val parentPath = EmployeePath
            .selectAll()
            .where { EmployeePath.employeeId eq EntityID(managerId, Employees) }
            .limit(1)
            .single()[EmployeePath.path]
        LTree(parentPath.value + "." + label)
    }
}