package org.jetbrains.hris.application

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.hris.api.requests.AddEmployeeRequest
import org.jetbrains.hris.api.responses.AddEmployeeResponse
import org.jetbrains.hris.api.responses.DeleteEmployeeResponse
import org.jetbrains.hris.common.models.Employee
import org.jetbrains.hris.employee.EmployeeRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmployeeApiIntegrationTest : BaseApiIntegrationTest() {

    @Test
    fun `POST employees creates employee and returns 201 with id`() = testApplication {
        configureWithEmployeeRoutes()
        val client = createJsonClient()

        // Create request using the proper DTO
        val request = AddEmployeeRequest(
            firstName = "Ada",
            lastName = "Lovelace",
            email = "ada.lovelace@test.com",
            position = "Software Engineer"
        )

        // Make POST request to create an employee
        val response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert POST response
        assertEquals(HttpStatusCode.Created, response.status)

        val postResponse = response.body<AddEmployeeResponse>()
        assertTrue(postResponse.id > 0, "Employee ID should be greater than 0")

        // Verify the employee was actually created in the database
        val employeeRepo = EmployeeRepository()
        val dbEmployee = transaction {
            employeeRepo.getEmployeeById(postResponse.id)
        }

        assertNotNull(dbEmployee, "Employee should exist in database")

        // Make GET request to retrieve the employee via API
        val getResponse = client.get("/employees/${postResponse.id}")
        assertEquals(HttpStatusCode.OK, getResponse.status)

        val getEmployee = getResponse.body<Employee>()

        // Compare POST request with DB entity
        assertEquals(request.firstName, dbEmployee.firstName, "POST request firstName should match DB")
        assertEquals(request.lastName, dbEmployee.lastName, "POST request lastName should match DB")
        assertEquals(request.email, dbEmployee.email, "POST request email should match DB")
        assertEquals(request.position, dbEmployee.position, "POST request title should match DB")

        // Compare DB entity with GET response
        assertEquals(dbEmployee.id, getEmployee.id, "DB id should match GET response")
        assertEquals(dbEmployee.firstName, getEmployee.firstName, "DB firstName should match GET response")
        assertEquals(dbEmployee.lastName, getEmployee.lastName, "DB lastName should match GET response")
        assertEquals(dbEmployee.email, getEmployee.email, "DB email should match GET response")
        assertEquals(dbEmployee.position, getEmployee.position, "DB title should match GET response")

        // Compare POST request with GET response (full round-trip)
        assertEquals(request.firstName, getEmployee.firstName, "POST firstName should match GET response")
        assertEquals(request.lastName, getEmployee.lastName, "POST lastName should match GET response")
        assertEquals(request.email, getEmployee.email, "POST email should match GET response")
        assertEquals(request.position, getEmployee.position, "POST title should match GET response")
    }

    @Test
    fun `GET employees subordinates returns direct reports`() = testApplication {
        configureWithEmployeeRoutes()
        val client = createJsonClient()

        // Create a manager
        val managerResponse = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Alice",
                lastName = "Manager",
                email = "alice.manager@test.com",
                position = "Engineering Manager"
            ))
        }
        val managerId = managerResponse.body<AddEmployeeResponse>().id

        // Create subordinates reporting to the manager
        val subordinate1Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Bob",
                lastName = "Engineer",
                email = "bob.engineer@test.com",
                position = "Senior Engineer",
                managerId = managerId
            ))
        }
        val subordinate1Id = subordinate1Response.body<AddEmployeeResponse>().id

        val subordinate2Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Charlie",
                lastName = "Developer",
                email = "charlie.dev@test.com",
                position = "Software Developer",
                managerId = managerId
            ))
        }
        val subordinate2Id = subordinate2Response.body<AddEmployeeResponse>().id

        // Create a nested subordinate (reports to Bob, not Alice)
        client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Diana",
                lastName = "Junior",
                email = "diana.junior@test.com",
                position = "Junior Developer",
                managerId = subordinate1Id
            ))
        }

        // Get Alice's direct subordinates
        val subordinatesResponse = client.get("/employees/$managerId/subordinates")
        assertEquals(HttpStatusCode.OK, subordinatesResponse.status)

        val subordinates = subordinatesResponse.body<List<Employee>>()

        // Should have exactly 2 direct reports (Bob and Charlie, not Diana)
        assertEquals(2, subordinates.size, "Manager should have 2 direct reports")

        val subordinateIds = subordinates.map { it.id }.toSet()
        assertTrue(subordinate1Id in subordinateIds, "Bob should be in subordinates")
        assertTrue(subordinate2Id in subordinateIds, "Charlie should be in subordinates")

        // Verify subordinate details
        val bob = subordinates.first { it.firstName == "Bob" }
        assertEquals("Engineer", bob.lastName)
        assertEquals("bob.engineer@test.com", bob.email)
        assertEquals("Senior Engineer", bob.position)
        assertEquals(managerId, bob.managerId)

        val charlie = subordinates.first { it.firstName == "Charlie" }
        assertEquals("Developer", charlie.lastName)
        assertEquals("charlie.dev@test.com", charlie.email)
        assertEquals("Software Developer", charlie.position)
        assertEquals(managerId, charlie.managerId)

        // Test employee with no subordinates returns empty list
        val noSubordinatesResponse = client.get("/employees/$subordinate2Id/subordinates")
        assertEquals(HttpStatusCode.OK, noSubordinatesResponse.status)

        val emptySubordinates = noSubordinatesResponse.body<List<Employee>>()
        assertTrue(emptySubordinates.isEmpty(), "Employee with no reports should return empty list")
    }

    @Test
    fun `DELETE employee without subordinates succeeds for leaf employees`() = testApplication {
        configureWithEmployeeRoutes()
        val client = createJsonClient()

        // Create employee without subordinates
        val response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "John",
                lastName = "Doe",
                email = "john.doe@test.com",
                position = "Developer"
            ))
        }
        val employeeId = response.body<AddEmployeeResponse>().id

        // Delete the employee (no cascade needed since no subordinates)
        val deleteResponse = client.delete("/employees/$employeeId")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val deleteResult = deleteResponse.body<DeleteEmployeeResponse>()
        assertTrue(deleteResult.success, "Delete should succeed")

        // Verify employee was deleted
        val getResponse = client.get("/employees/$employeeId")
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    @Test
    fun `DELETE employee without subordinates fails when employee has subordinates`() = testApplication {
        configureWithEmployeeRoutes()
        val client = createJsonClient()

        // Create manager
        val managerResponse = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Manager",
                lastName = "Smith",
                email = "manager.smith@test.com",
                position = "Engineering Manager"
            ))
        }
        val managerId = managerResponse.body<AddEmployeeResponse>().id

        // Create subordinate
        client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Employee",
                lastName = "Jones",
                email = "employee.jones@test.com",
                position = "Engineer",
                managerId = managerId
            ))
        }

        // Try to delete manager without cascade (should fail)
        val deleteResponse = client.delete("/employees/$managerId")
        assertEquals(HttpStatusCode.Conflict, deleteResponse.status)

        val deleteResult = deleteResponse.body<DeleteEmployeeResponse>()
        assertTrue(!deleteResult.success, "Delete should fail")
        assertNotNull(deleteResult.error)
        assertTrue(
            deleteResult.error!!.contains("Cannot delete employee with subordinates"),
            "Error message should indicate employee has subordinates"
        )

        // Verify manager still exists
        val getResponse = client.get("/employees/$managerId")
        assertEquals(HttpStatusCode.OK, getResponse.status)
    }

    @Test
    fun `DELETE employee with cascade deletes entire subtree`() = testApplication {
        configureWithEmployeeRoutes()
        val client = createJsonClient()

        // Create organizational hierarchy
        val ceoResponse = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "CEO",
                lastName = "Boss",
                email = "ceo@test.com",
                position = "Chief Executive Officer"
            ))
        }
        val ceoId = ceoResponse.body<AddEmployeeResponse>().id

        val managerResponse = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Manager",
                lastName = "Middle",
                email = "manager@test.com",
                position = "Manager",
                managerId = ceoId
            ))
        }
        val managerId = managerResponse.body<AddEmployeeResponse>().id

        val employee1Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Employee",
                lastName = "One",
                email = "employee1@test.com",
                position = "Engineer",
                managerId = managerId
            ))
        }
        val employee1Id = employee1Response.body<AddEmployeeResponse>().id

        val employee2Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Employee",
                lastName = "Two",
                email = "employee2@test.com",
                position = "Engineer",
                managerId = managerId
            ))
        }
        val employee2Id = employee2Response.body<AddEmployeeResponse>().id

        // Delete manager with cascade (should delete manager and both subordinates)
        val deleteResponse = client.delete("/employees/$managerId?cascade=true")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val deleteResult = deleteResponse.body<DeleteEmployeeResponse>()
        assertTrue(deleteResult.success, "Delete should succeed")
        assertNotNull(deleteResult.removedCount)
        assertEquals(3, deleteResult.removedCount, "Should remove 3 employees (manager + 2 subordinates)")

        // Verify manager was deleted
        val getManagerResponse = client.get("/employees/$managerId")
        assertEquals(HttpStatusCode.NotFound, getManagerResponse.status)

        // Verify subordinates were deleted
        val getEmployee1Response = client.get("/employees/$employee1Id")
        assertEquals(HttpStatusCode.NotFound, getEmployee1Response.status)

        val getEmployee2Response = client.get("/employees/$employee2Id")
        assertEquals(HttpStatusCode.NotFound, getEmployee2Response.status)

        // Verify CEO still exists
        val getCeoResponse = client.get("/employees/$ceoId")
        assertEquals(HttpStatusCode.OK, getCeoResponse.status)
    }

    @Test
    fun `Full lifecycle scenario - create org hierarchy and delete employees`() = testApplication {
        configureWithEmployeeRoutes()
        val client = createJsonClient()

        // ===== Step 1: Create organizational hierarchy =====

        // Create CEO (root employee)
        val ceoResponse = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Alice",
                lastName = "Smith",
                email = "alice.smith@company.com",
                position = "CEO"
            ))
        }
        val ceoId = ceoResponse.body<AddEmployeeResponse>().id
        assertEquals(HttpStatusCode.Created, ceoResponse.status)

        // Create VP of Engineering
        val vpEngResponse = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Bob",
                lastName = "Johnson",
                email = "bob.johnson@company.com",
                position = "VP Engineering",
                managerId = ceoId
            ))
        }
        val vpEngId = vpEngResponse.body<AddEmployeeResponse>().id

        // Create Engineering Manager
        val engManagerResponse = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Charlie",
                lastName = "Brown",
                email = "charlie.brown@company.com",
                position = "Engineering Manager",
                managerId = vpEngId
            ))
        }
        val engManagerId = engManagerResponse.body<AddEmployeeResponse>().id

        // Create two engineers under the manager
        val engineer1Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Diana",
                lastName = "Prince",
                email = "diana.prince@company.com",
                position = "Senior Engineer",
                managerId = engManagerId
            ))
        }
        val engineer1Id = engineer1Response.body<AddEmployeeResponse>().id

        val engineer2Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Eve",
                lastName = "Wilson",
                email = "eve.wilson@company.com",
                position = "Engineer",
                managerId = engManagerId
            ))
        }
        val engineer2Id = engineer2Response.body<AddEmployeeResponse>().id

        // Create VP of Sales (parallel branch)
        val vpSalesResponse = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest(
                firstName = "Frank",
                lastName = "Miller",
                email = "frank.miller@company.com",
                position = "VP Sales",
                managerId = ceoId
            ))
        }
        val vpSalesId = vpSalesResponse.body<AddEmployeeResponse>().id

        // ===== Step 2: Verify hierarchy was created correctly =====

        // Verify CEO has 2 direct reports (VP Eng and VP Sales)
        val ceoSubordinatesResponse = client.get("/employees/$ceoId/subordinates")
        val ceoSubordinates = ceoSubordinatesResponse.body<List<Employee>>()
        assertEquals(2, ceoSubordinates.size, "CEO should have 2 direct reports")

        // Verify VP Engineering has 1 direct report (Engineering Manager)
        val vpEngSubordinatesResponse = client.get("/employees/$vpEngId/subordinates")
        val vpEngSubordinates = vpEngSubordinatesResponse.body<List<Employee>>()
        assertEquals(1, vpEngSubordinates.size, "VP Eng should have 1 direct report")

        // Verify Engineering Manager has 2 direct reports
        val engManagerSubordinatesResponse = client.get("/employees/$engManagerId/subordinates")
        val engManagerSubordinates = engManagerSubordinatesResponse.body<List<Employee>>()
        assertEquals(2, engManagerSubordinates.size, "Engineering Manager should have 2 direct reports")

        // Verify VP Sales has no direct reports
        val vpSalesSubordinatesResponse = client.get("/employees/$vpSalesId/subordinates")
        val vpSalesSubordinates = vpSalesSubordinatesResponse.body<List<Employee>>()
        assertEquals(0, vpSalesSubordinates.size, "VP Sales should have no direct reports")

        // ===== Step 3: Try to delete engineer with subordinates (should fail) =====

        val deleteManagerNoSafe = client.delete("/employees/$engManagerId")
        assertEquals(HttpStatusCode.Conflict, deleteManagerNoSafe.status,
            "Should not be able to delete manager without cascade")

        val conflictResult = deleteManagerNoSafe.body<DeleteEmployeeResponse>()
        assertTrue(!conflictResult.success)
        assertNotNull(conflictResult.error)

        // ===== Step 4: Delete leaf employee (should succeed) =====

        val deleteEngineer1 = client.delete("/employees/$engineer1Id")
        assertEquals(HttpStatusCode.OK, deleteEngineer1.status)

        val deleteResult1 = deleteEngineer1.body<DeleteEmployeeResponse>()
        assertTrue(deleteResult1.success, "Should delete leaf employee")

        // Verify engineer was deleted
        val getDeletedEngineer1 = client.get("/employees/$engineer1Id")
        assertEquals(HttpStatusCode.NotFound, getDeletedEngineer1.status)

        // Verify manager still has 1 subordinate
        val engManagerSubordinatesAfterDelete = client.get("/employees/$engManagerId/subordinates")
        val remainingSubordinates = engManagerSubordinatesAfterDelete.body<List<Employee>>()
        assertEquals(1, remainingSubordinates.size, "Manager should have 1 remaining subordinate")

        // ===== Step 5: Delete VP Sales (leaf in management hierarchy) =====

        val deleteVpSales = client.delete("/employees/$vpSalesId")
        assertEquals(HttpStatusCode.OK, deleteVpSales.status)
        assertTrue(deleteVpSales.body<DeleteEmployeeResponse>().success)

        // Verify CEO now has 1 direct report
        val ceoSubordinatesAfterVpDelete = client.get("/employees/$ceoId/subordinates")
        val ceoSubsAfterDelete = ceoSubordinatesAfterVpDelete.body<List<Employee>>()
        assertEquals(1, ceoSubsAfterDelete.size, "CEO should have 1 direct report after VP Sales deletion")

        // ===== Step 6: Cascade delete Engineering Manager (should delete manager + remaining engineer) =====

        val cascadeDelete = client.delete("/employees/$engManagerId?cascade=true")
        assertEquals(HttpStatusCode.OK, cascadeDelete.status)

        val cascadeResult = cascadeDelete.body<DeleteEmployeeResponse>()
        assertTrue(cascadeResult.success)
        assertNotNull(cascadeResult.removedCount)
        assertEquals(2, cascadeResult.removedCount,
            "Should delete manager + 1 remaining engineer")

        // Verify Engineering Manager was deleted
        val getDeletedManager = client.get("/employees/$engManagerId")
        assertEquals(HttpStatusCode.NotFound, getDeletedManager.status)

        // Verify remaining engineer was also deleted
        val getDeletedEngineer2 = client.get("/employees/$engineer2Id")
        assertEquals(HttpStatusCode.NotFound, getDeletedEngineer2.status)

        // Verify VP Engineering now has no subordinates
        val vpEngSubsAfterCascade = client.get("/employees/$vpEngId/subordinates")
        val vpEngSubsEmpty = vpEngSubsAfterCascade.body<List<Employee>>()
        assertEquals(0, vpEngSubsEmpty.size, "VP Eng should have no subordinates after cascade delete")

        // ===== Step 7: Delete VP Engineering (now a leaf) =====

        val deleteVpEng = client.delete("/employees/$vpEngId")
        assertEquals(HttpStatusCode.OK, deleteVpEng.status)
        assertTrue(deleteVpEng.body<DeleteEmployeeResponse>().success)

        // ===== Step 8: Verify final state - only CEO remains =====

        val finalCeoCheck = client.get("/employees/$ceoId")
        assertEquals(HttpStatusCode.OK, finalCeoCheck.status)

        val finalCeoSubs = client.get("/employees/$ceoId/subordinates")
        val finalSubs = finalCeoSubs.body<List<Employee>>()
        assertEquals(0, finalSubs.size, "CEO should have no subordinates at the end")

        // ===== Step 9: Finally, delete CEO =====

        val deleteCeo = client.delete("/employees/$ceoId")
        assertEquals(HttpStatusCode.OK, deleteCeo.status)
        assertTrue(deleteCeo.body<DeleteEmployeeResponse>().success)

        // Verify CEO was deleted
        val finalCeoGet = client.get("/employees/$ceoId")
        assertEquals(HttpStatusCode.NotFound, finalCeoGet.status)
    }

    @Test
    fun `GET employees homepage returns organizational context`() = testApplication {
        configureWithEmployeeRoutes()
        val client = createJsonClient()

        // Create organizational structure:
        // CEO (no manager)
        // ├── VP1 (manager: CEO)
        // │   ├── Engineer1 (manager: VP1)
        // │   └── Engineer2 (manager: VP1)
        // └── VP2 (manager: CEO)
        //     └── Engineer3 (manager: VP2)

        val ceoResponse = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest("CEO", "Boss", "ceo@test.com", "Chief Executive Officer"))
        }
        val ceoId = ceoResponse.body<AddEmployeeResponse>().id

        val vp1Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest("VP1", "Smith", "vp1@test.com", "Vice President", managerId = ceoId))
        }
        val vp1Id = vp1Response.body<AddEmployeeResponse>().id

        val vp2Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest("VP2", "Jones", "vp2@test.com", "Vice President", managerId = ceoId))
        }
        val vp2Id = vp2Response.body<AddEmployeeResponse>().id

        val eng1Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest("Engineer1", "Alpha", "eng1@test.com", "Software Engineer", managerId = vp1Id))
        }
        val eng1Id = eng1Response.body<AddEmployeeResponse>().id

        val eng2Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest("Engineer2", "Beta", "eng2@test.com", "Software Engineer", managerId = vp1Id))
        }
        val eng2Id = eng2Response.body<AddEmployeeResponse>().id

        val eng3Response = client.post("/employees") {
            contentType(ContentType.Application.Json)
            setBody(AddEmployeeRequest("Engineer3", "Gamma", "eng3@test.com", "Software Engineer", managerId = vp2Id))
        }
        val eng3Id = eng3Response.body<AddEmployeeResponse>().id

        // Test 1: CEO homepage (root employee - no manager, no peers)
        val ceoHomepage = client.get("/employees/$ceoId/homepage")
        assertEquals(HttpStatusCode.OK, ceoHomepage.status)
        val ceoData = ceoHomepage.body<org.jetbrains.hris.common.models.EmployeeHomepage>()

        assertEquals(ceoId, ceoData.employee.id)
        assertEquals("CEO", ceoData.employee.firstName)
        assertEquals(null, ceoData.manager, "CEO should have no manager")
        assertEquals(0, ceoData.peers.size, "CEO should have no peers")
        assertEquals(2, ceoData.subordinates.size, "CEO should have 2 direct reports")
        assertTrue(ceoData.subordinates.any { it.id == vp1Id })
        assertTrue(ceoData.subordinates.any { it.id == vp2Id })

        // Test 2: VP1 homepage (has manager, has peer, has subordinates)
        val vp1Homepage = client.get("/employees/$vp1Id/homepage")
        assertEquals(HttpStatusCode.OK, vp1Homepage.status)
        val vp1Data = vp1Homepage.body<org.jetbrains.hris.common.models.EmployeeHomepage>()

        assertEquals(vp1Id, vp1Data.employee.id)
        assertEquals("VP1", vp1Data.employee.firstName)
        assertNotNull(vp1Data.manager, "VP1 should have a manager")
        assertEquals(ceoId, vp1Data.manager?.id, "VP1's manager should be CEO")
        assertEquals(1, vp1Data.peers.size, "VP1 should have 1 peer")
        assertEquals(vp2Id, vp1Data.peers[0].id, "VP1's peer should be VP2")
        assertEquals(2, vp1Data.subordinates.size, "VP1 should have 2 direct reports")
        assertTrue(vp1Data.subordinates.any { it.id == eng1Id })
        assertTrue(vp1Data.subordinates.any { it.id == eng2Id })

        // Test 3: Engineer1 homepage (has manager, has peer, no subordinates)
        val eng1Homepage = client.get("/employees/$eng1Id/homepage")
        assertEquals(HttpStatusCode.OK, eng1Homepage.status)
        val eng1Data = eng1Homepage.body<org.jetbrains.hris.common.models.EmployeeHomepage>()

        assertEquals(eng1Id, eng1Data.employee.id)
        assertEquals("Engineer1", eng1Data.employee.firstName)
        assertNotNull(eng1Data.manager, "Engineer1 should have a manager")
        assertEquals(vp1Id, eng1Data.manager?.id, "Engineer1's manager should be VP1")
        assertEquals(1, eng1Data.peers.size, "Engineer1 should have 1 peer")
        assertEquals(eng2Id, eng1Data.peers[0].id, "Engineer1's peer should be Engineer2")
        assertEquals(0, eng1Data.subordinates.size, "Engineer1 should have no direct reports")

        // Test 4: Engineer3 homepage (has manager, no peers, no subordinates)
        val eng3Homepage = client.get("/employees/$eng3Id/homepage")
        assertEquals(HttpStatusCode.OK, eng3Homepage.status)
        val eng3Data = eng3Homepage.body<org.jetbrains.hris.common.models.EmployeeHomepage>()

        assertEquals(eng3Id, eng3Data.employee.id)
        assertEquals("Engineer3", eng3Data.employee.firstName)
        assertNotNull(eng3Data.manager, "Engineer3 should have a manager")
        assertEquals(vp2Id, eng3Data.manager?.id, "Engineer3's manager should be VP2")
        assertEquals(0, eng3Data.peers.size, "Engineer3 should have no peers (VP2's only direct report)")
        assertEquals(0, eng3Data.subordinates.size, "Engineer3 should have no direct reports")

        // Test 5: Non-existent employee returns 404
        val notFoundHomepage = client.get("/employees/99999/homepage")
        assertEquals(HttpStatusCode.NotFound, notFoundHomepage.status)
    }
}
