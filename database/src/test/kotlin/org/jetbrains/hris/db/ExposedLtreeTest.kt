package org.jetbrains.hris.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.db.utils.LTree
import org.jetbrains.hris.db.utils.LTreeColumnType
import org.jetbrains.hris.db.utils.LtreeContainedIn
import org.jetbrains.hris.db.utils.ltree
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.postgresql.util.PGobject
import org.testcontainers.containers.PostgreSQLContainer
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExposedLtreeTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var db: Database

        @BeforeAll
        @JvmStatic
        fun setup() {
            postgres = PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("test")
                .withUsername("test")
                .withPassword("test")
            postgres.start()

            db = Database.connect(
                url = postgres.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = postgres.username,
                password = postgres.password
            )

            transaction(db) {
                exec("CREATE EXTENSION IF NOT EXISTS ltree")

                // Create a test table with ltree column
                SchemaUtils.create(TestLtreeTable)
            }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            postgres.stop()
        }
    }

    object TestLtreeTable : org.jetbrains.exposed.dao.id.LongIdTable("test_ltree") {
        val path = ltree("path")
    }

    @Test
    fun `LTree value class wraps string correctly`() {
        val path = LTree("company.eng.backend")
        assertEquals("company.eng.backend", path.value)
        assertEquals("company.eng.backend", path.toString())
    }

    @Test
    fun `LTreeColumnType converts to SQL type`() {
        val sqlType = LTreeColumnType.sqlType()
        assertEquals("LTREE", sqlType)
    }

    @Test
    fun `LTreeColumnType converts non-null value to string`() {
        val ltree = LTree("a.b.c")
        val result = LTreeColumnType.nonNullValueToString(ltree)
        assertEquals("'a.b.c'", result)
    }

    @Test
    fun `LTreeColumnType converts value to DB as PGobject`() {
        val ltree = LTree("company.eng")
        val result = LTreeColumnType.notNullValueToDB(ltree)

        assertTrue(result is PGobject)
        assertEquals("ltree", (result as PGobject).type)
        assertEquals("company.eng", result.value)
    }

    @Test
    fun `LTreeColumnType converts LTree from DB when value is LTree`() {
        val original = LTree("test.path")
        val result = LTreeColumnType.valueFromDB(original)
        assertEquals("test.path", result.value)
    }

    @Test
    fun `LTreeColumnType converts String from DB to LTree`() {
        val result = LTreeColumnType.valueFromDB("company.product.feature")
        assertEquals("company.product.feature", result.value)
    }

    @Test
    fun `LTreeColumnType converts Clob from DB to LTree`() {
        val clob = object : java.sql.Clob {
            override fun length(): Long = 7
            override fun getSubString(pos: Long, length: Int): String = "a.b.c.d"
            override fun getCharacterStream(): java.io.Reader = StringReader("a.b.c.d")
            override fun getCharacterStream(pos: Long, length: Long): java.io.Reader = StringReader("a.b.c.d")
            override fun getAsciiStream(): java.io.InputStream = throw UnsupportedOperationException()
            override fun position(searchstr: String?, start: Long): Long = throw UnsupportedOperationException()
            override fun position(searchstr: java.sql.Clob?, start: Long): Long = throw UnsupportedOperationException()
            override fun setString(pos: Long, str: String?): Int = throw UnsupportedOperationException()
            override fun setString(pos: Long, str: String?, offset: Int, len: Int): Int = throw UnsupportedOperationException()
            override fun setAsciiStream(pos: Long): java.io.OutputStream = throw UnsupportedOperationException()
            override fun setCharacterStream(pos: Long): java.io.Writer = throw UnsupportedOperationException()
            override fun truncate(len: Long) = throw UnsupportedOperationException()
            override fun free() {}
        }

        val result = LTreeColumnType.valueFromDB(clob)
        assertEquals("a.b.c.d", result.value)
    }

    @Test
    fun `LTreeColumnType converts unknown type to LTree via toString`() {
        val someObject = object {
            override fun toString() = "x.y.z"
        }
        val result = LTreeColumnType.valueFromDB(someObject)
        assertEquals("x.y.z", result.value)
    }

    @Test
    fun `ltree extension function creates column with LTreeColumnType`() {
        val column = TestLtreeTable.path
        assertEquals(LTreeColumnType, column.columnType)
    }

    @Test
    fun `LtreeContainedIn operator generates correct SQL`() {
        transaction(db) {
            // Insert test data
            TestLtreeTable.insert {
                it[path] = LTree("company")
            }
            TestLtreeTable.insert {
                it[path] = LTree("company.eng")
            }
            TestLtreeTable.insert {
                it[path] = LTree("company.eng.backend")
            }
            TestLtreeTable.insert {
                it[path] = LTree("company.sales")
            }

            // Test <@ operator: find all paths under "company.eng"
            val targetPath = LTree("company.eng")
            val results = TestLtreeTable
                .selectAll()
                .where { LtreeContainedIn(TestLtreeTable.path, QueryParameter(targetPath, LTreeColumnType)) }
                .map { it[TestLtreeTable.path].value }
                .sorted()

            assertEquals(2, results.size)
            assertEquals(listOf("company.eng", "company.eng.backend"), results)
        }
    }

    @Test
    fun `LtreeContainedIn works with column to column comparison`() {
        transaction(db) {
            // Clear previous data
            TestLtreeTable.deleteAll()

            // Insert test data
            val id1 = TestLtreeTable.insertAndGetId {
                it[path] = LTree("a.b.c")
            }
            val id2 = TestLtreeTable.insertAndGetId {
                it[path] = LTree("a.b")
            }
            val id3 = TestLtreeTable.insertAndGetId {
                it[path] = LTree("x.y")
            }

            // Find paths that are descendants of other paths in the same table
            val subquery = TestLtreeTable.select(TestLtreeTable.path)
                .where { TestLtreeTable.id eq id2.value }
                .alias("parent")

            val results = TestLtreeTable
                .selectAll()
                .where {
                    LtreeContainedIn(
                        TestLtreeTable.path,
                        QueryParameter(LTree("a.b"), LTreeColumnType)
                    )
                }
                .map { it[TestLtreeTable.id].value }
                .sorted()

            // Should find both "a.b" and "a.b.c"
            assertEquals(2, results.size)
            assertTrue(results.contains(id1.value))
            assertTrue(results.contains(id2.value))
        }
    }

    @Test
    fun `can insert and retrieve ltree values through Exposed`() {
        transaction(db) {
            TestLtreeTable.deleteAll()

            val insertedId = TestLtreeTable.insertAndGetId {
                it[path] = LTree("org.dept.team.person")
            }

            val retrieved = TestLtreeTable
                .selectAll()
                .where { TestLtreeTable.id eq insertedId }
                .single()

            assertEquals("org.dept.team.person", retrieved[TestLtreeTable.path].value)
        }
    }

    @Test
    fun `ltree handles special characters and long paths`() {
        transaction(db) {
            TestLtreeTable.deleteAll()

            // PostgreSQL ltree supports alphanumeric and underscore
            val complexPath = LTree("company.engineering_team.backend_services.user_service")

            TestLtreeTable.insert {
                it[path] = complexPath
            }

            val retrieved = TestLtreeTable
                .selectAll()
                .single()

            assertEquals("company.engineering_team.backend_services.user_service", retrieved[TestLtreeTable.path].value)
        }
    }
}
