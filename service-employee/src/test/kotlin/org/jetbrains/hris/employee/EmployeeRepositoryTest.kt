package org.jetbrains.hris.employee

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.db.*
import org.jetbrains.hris.db.schemas.EmployeePath
import org.jetbrains.hris.db.schemas.Employees
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(Lifecycle.PER_CLASS)
class EmployeeRepositoryTest {

    private lateinit var postgres: PostgreSQLContainer<Nothing>

    @BeforeAll
    fun startDb() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()

        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password
        )

        initDatabase()
    }

    @AfterAll
    fun stopDb() {
        postgres.stop()
    }

    @BeforeEach
    fun clean() {
        transaction {
            // Clean tables and reset ids for determinism
            exec(DatabaseTables.truncateEmployees())
        }
    }

    @Test
    fun `add root employee stores correct path and depth`() {
        val repo = EmployeeRepository()
        val id = repo.addEmployee("Ada", "Lovelace", "ada@example.com", "Engineer", managerId = null)

        transaction {
            val row = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(id, Employees) }
                .single()
            val path = row[EmployeePath.path].value
            val depth = row[EmployeePath.depth]
            assertEquals("company.e$id", path)
            assertEquals(2, depth) // "company" + "e{id}"
        }
    }

    @Test
    fun `add child employee extends parent path and increments depth`() {
        val repo = EmployeeRepository()
        val managerId = repo.addEmployee("Grace", "Hopper", "grace@example.com", "Manager", null)
        val childId = repo.addEmployee("Linus", "Torvalds", "linus@example.com", "Engineer", managerId)

        transaction {
            val parent = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(managerId, Employees) }
                .single()
            val child = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(childId, Employees) }
                .single()

            val parentPath = parent[EmployeePath.path].value
            val parentDepth = parent[EmployeePath.depth]
            val childPath = child[EmployeePath.path].value
            val childDepth = child[EmployeePath.depth]

            assertEquals("$parentPath.e$childId", childPath)
            assertEquals(parentDepth + 1, childDepth)
        }
    }

    @Test
    fun `remove employee if not manager prevents deletion when node has children`() {
        val repo = EmployeeRepository()
        val managerId = repo.addEmployee("Alan", "Turing", "alan@example.com", "Manager", null)
        val leafId = repo.addEmployee("Barbara", "Liskov", "barbara@example.com", "Engineer", managerId)

        val deletedManager = repo.removeEmployeeWithoutSubordinates(managerId)
        assertFalse(deletedManager, "Manager with children should not be deleted by removeIfLeaf")

        val deletedLeaf = repo.removeEmployeeWithoutSubordinates(leafId)
        assertTrue(deletedLeaf, "Leaf should be deleted by removeIfLeaf")

        transaction {
            // Manager still present
            val managerStillExists = Employees.selectAll()
                .where { Employees.id eq EntityID(managerId, Employees) }
                .empty().not()
            assertTrue(managerStillExists)

            // Leaf removed
            val leafExists = Employees.selectAll()
                .where { Employees.id eq EntityID(leafId, Employees) }
                .empty().not()
            assertFalse(leafExists)
        }
    }

    @Test
    fun `remove subtree deletes node and all descendants`() {
        val repo = EmployeeRepository()
        val rootId = repo.addEmployee("Root", "Boss", "root@example.com", "CEO", null)
        val alice = repo.addEmployee("Alice", "Manager", "alice.manager@company.test", "Manager", rootId)
        val bob = repo.addEmployee("Bob", "Manager", "bob.manager@company.test", "Manager", rootId)
        val charlie = repo.addEmployee("Charlie", "Engineer", "charlie.engineer@company.test", "Engineer", alice)

        val removed = repo.removeEmployeeWithSubordinates(alice)
        assertEquals(2, removed, "Should remove manager Alice and Charlie under it")

        transaction {
            // Root and Bob should remain
            val remainingIds = Employees.selectAll().map { it[Employees.id].value }.toSet()
            assertTrue(rootId in remainingIds)
            assertTrue(bob in remainingIds)
            assertFalse(alice in remainingIds)
            assertFalse(charlie in remainingIds)
        }
    }

    @Test
    fun `direct subordinates returns only direct children`() {
        val repo = EmployeeRepository()
        val rootId = repo.addEmployee("Root", "Boss", "root@example.com", "CEO", null)
        val alice = repo.addEmployee("Alice", "Manager", "alice.manager@company.test", "Manager", rootId)
        val bob = repo.addEmployee("Bob", "Manager", "bob.manager@company.test", "Manager", rootId)
        val charlie = repo.addEmployee("Charlie", "Engineer", "charlie.engineer@company.test", "Engineer", alice)
        val diana = repo.addEmployee("Diana", "Engineer", "diana.engineer@company.test", "Engineer", alice)
        val gina = repo.addEmployee("Gina", "Intern", "gina.intern@company.test", "Intern", charlie)

        // Direct reports of root are Alice and Bob
        val rootSubs = repo.getSubordinates(rootId).map { it.id }.toSet()
        assertEquals(setOf(alice, bob), rootSubs)

        // Direct reports of Alice are Charlie and Diana (not Gina)
        val aliceSubs = repo.getSubordinates(alice).map { it.id }.toSet()
        assertEquals(setOf(charlie, diana), aliceSubs)

        // Direct reports of Bob are none
        val bobSubs = repo.getSubordinates(bob)
        assertTrue(bobSubs.isEmpty())

        // Direct reports of Charlie are only Gina
        val charlieSubs = repo.getSubordinates(charlie).map { it.id }
        assertEquals(listOf(gina), charlieSubs)
    }

    @Test
    fun `get manager returns parent id and null for root or missing`() {
        val repo = EmployeeRepository()
        val rootId = repo.addEmployee("Root", "Boss", "root@example.com", "CEO", null)
        val alice = repo.addEmployee("Alice", "Manager", "alice.manager@company.test", "Manager", rootId)
        val charlie = repo.addEmployee("Charlie", "Engineer", "charlie.engineer@company.test", "Engineer", alice)

        // Root has no manager
        assertEquals(null, repo.getManagerOf(rootId))

        // Alice's manager is Root
        assertEquals(rootId, repo.getManagerOf(alice)?.id)

        // Charlie's manager is Alice
        assertEquals(alice, repo.getManagerOf(charlie)?.id)

        // Non-existent employee -> null
        assertEquals(null, repo.getManagerOf(9999))
    }

    @Test
    fun `get employee tree returns hierarchical org structure`() {
        val repo = EmployeeRepository()

        // Build org: CEO -> Alice, Bob; Alice -> Charlie, Diana; Charlie -> Gina
        val ceo = repo.addEmployee("CEO", "Boss", "ceo@example.com", "CEO", null)
        val alice = repo.addEmployee("Alice", "Manager", "alice@example.com", "Manager", ceo)
        val bob = repo.addEmployee("Bob", "Manager", "bob@example.com", "Manager", ceo)
        val charlie = repo.addEmployee("Charlie", "Engineer", "charlie@example.com", "Engineer", alice)
        val diana = repo.addEmployee("Diana", "Engineer", "diana@example.com", "Engineer", alice)
        val gina = repo.addEmployee("Gina", "Intern", "gina@example.com", "Intern", charlie)

        // Get tree starting from CEO
        val ceoTree = repo.getEmployeeTree(ceo)
        assertNotNull(ceoTree)
        assertEquals(ceo, ceoTree.id)
        assertEquals("CEO", ceoTree.firstName)
        assertEquals(2, ceoTree.subordinates.size)

        // Check Alice's subtree
        val aliceNode = ceoTree.subordinates.find { it.id == alice }
        assertNotNull(aliceNode)
        assertEquals("Alice", aliceNode.firstName)
        assertEquals(2, aliceNode.subordinates.size)

        // Check Charlie's subtree
        val charlieNode = aliceNode.subordinates.find { it.id == charlie }
        assertNotNull(charlieNode)
        assertEquals("Charlie", charlieNode.firstName)
        assertEquals(1, charlieNode.subordinates.size)
        assertEquals(gina, charlieNode.subordinates[0].id)

        // Check Bob has no subordinates
        val bobNode = ceoTree.subordinates.find { it.id == bob }
        assertNotNull(bobNode)
        assertEquals(0, bobNode.subordinates.size)

        // Get tree starting from Alice (should only include Alice's subtree)
        val aliceTree = repo.getEmployeeTree(alice)
        assertNotNull(aliceTree)
        assertEquals(alice, aliceTree.id)
        assertEquals(2, aliceTree.subordinates.size)

        // Non-existent employee returns null
        val nonExistent = repo.getEmployeeTree(9999)
        assertNull(nonExistent)
    }

    @Test
    fun `change manager updates employee's manager and path correctly`() {
        val repo = EmployeeRepository()

        // Build org: CEO -> Alice, Bob; Alice -> Charlie
        val ceo = repo.addEmployee("CEO", "Boss", "ceo@example.com", "CEO", null)
        val alice = repo.addEmployee("Alice", "Manager", "alice@example.com", "Manager", ceo)
        val bob = repo.addEmployee("Bob", "Manager", "bob@example.com", "Manager", ceo)
        val charlie = repo.addEmployee("Charlie", "Engineer", "charlie@example.com", "Engineer", alice)

        // Change Charlie's manager from Alice to Bob
        repo.changeManager(charlie, bob)

        // Verify the manager was updated
        val charlieEmployee = repo.getEmployeeById(charlie)
        assertNotNull(charlieEmployee)
        assertEquals(bob, charlieEmployee.managerId)

        // Verify the path was updated
        transaction {
            val charliePath = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(charlie, Employees) }
                .single()[EmployeePath.path].value

            val bobPath = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(bob, Employees) }
                .single()[EmployeePath.path].value

            assertTrue(charliePath.startsWith("$bobPath.e$charlie"))
        }

        // Verify Charlie is now a direct subordinate of Bob
        val bobSubs = repo.getSubordinates(bob)
        assertEquals(1, bobSubs.size)
        assertEquals(charlie, bobSubs[0].id)

        // Verify Charlie is no longer a subordinate of Alice
        val aliceSubs = repo.getSubordinates(alice)
        assertTrue(aliceSubs.isEmpty())
    }

    @Test
    fun `change manager to null makes employee a root`() {
        val repo = EmployeeRepository()

        val ceo = repo.addEmployee("CEO", "Boss", "ceo@example.com", "CEO", null)
        val alice = repo.addEmployee("Alice", "Manager", "alice@example.com", "Manager", ceo)

        // Change Alice's manager to null (make her a root)
        repo.changeManager(alice, null)

        // Verify the manager was updated to null
        val aliceEmployee = repo.getEmployeeById(alice)
        assertNotNull(aliceEmployee)
        assertNull(aliceEmployee.managerId)

        // Verify the path is now a root path
        transaction {
            val alicePath = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(alice, Employees) }
                .single()[EmployeePath.path].value

            assertEquals("company.e$alice", alicePath)
        }

        // Verify Alice is no longer a subordinate of CEO
        val ceoSubs = repo.getSubordinates(ceo)
        assertTrue(ceoSubs.isEmpty())
    }

    @Test
    fun `change manager updates all descendant paths correctly`() {
        val repo = EmployeeRepository()

        // Build org: CEO -> Alice, Bob; Alice -> Charlie -> Diana
        val ceo = repo.addEmployee("CEO", "Boss", "ceo@example.com", "CEO", null)
        val alice = repo.addEmployee("Alice", "Manager", "alice@example.com", "Manager", ceo)
        val bob = repo.addEmployee("Bob", "Manager", "bob@example.com", "Manager", ceo)
        val charlie = repo.addEmployee("Charlie", "Engineer", "charlie@example.com", "Engineer", alice)
        val diana = repo.addEmployee("Diana", "Intern", "diana@example.com", "Intern", charlie)

        // Change Charlie's manager from Alice to Bob (Diana should move with Charlie)
        repo.changeManager(charlie, bob)

        // Verify Diana's path was updated to reflect the new hierarchy
        transaction {
            val bobPath = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(bob, Employees) }
                .single()[EmployeePath.path].value

            val charliePath = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(charlie, Employees) }
                .single()[EmployeePath.path].value

            val dianaPath = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(diana, Employees) }
                .single()[EmployeePath.path].value

            // Charlie should be under Bob
            assertEquals("$bobPath.e$charlie", charliePath)

            // Diana should be under Charlie, which is under Bob
            assertEquals("$charliePath.e$diana", dianaPath)
            assertTrue(dianaPath.startsWith("$bobPath."))
        }

        // Verify depths are correct
        transaction {
            val bobDepth = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(bob, Employees) }
                .single()[EmployeePath.depth]

            val charlieDepth = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(charlie, Employees) }
                .single()[EmployeePath.depth]

            val dianaDepth = EmployeePath.selectAll()
                .where { EmployeePath.employeeId eq EntityID(diana, Employees) }
                .single()[EmployeePath.depth]

            assertEquals(bobDepth + 1, charlieDepth)
            assertEquals(charlieDepth + 1, dianaDepth)
        }
    }
}
