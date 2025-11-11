package org.jetbrains.hris.api.routes

import io.ktor.http.HttpStatusCode.Companion.Created
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.hris.api.requests.AddEmployeeRequest
import org.jetbrains.hris.api.requests.ChangeManagerRequest
import org.jetbrains.hris.api.responses.AddEmployeeResponse
import org.jetbrains.hris.api.responses.DeleteEmployeeResponse
import org.jetbrains.hris.api.utils.getLongOrFail
import org.jetbrains.hris.common.exceptions.notFoundException
import org.jetbrains.hris.employee.EmployeeService

fun Route.employeeRoutes(employeeService: EmployeeService) {
    route("/employees") {
        post {
            val req = call.receive<AddEmployeeRequest>()

            val id = employeeService.addEmployee(
                firstName = req.firstName,
                lastName = req.lastName,
                email = req.email,
                position = req.position,
                managerId = req.managerId
            )

            call.respond(Created, AddEmployeeResponse(id))
        }

        get("/{id}") {
            val employeeId = call.parameters.getLongOrFail("id")
            val employee = employeeService.getEmployeeById(employeeId)
                ?: notFoundException("Employee not found")

            call.respond(employee)
        }

        get("/{id}/homepage") {
            val employeeId = call.parameters.getLongOrFail("id")
            val homepage = employeeService.getEmployeeHomepage(employeeId)
                ?: notFoundException("Employee not found")

            call.respond(homepage)
        }

        get("/{id}/manager") {
            val employeeId = call.parameters.getLongOrFail("id")
            val manager = employeeService.getManagerOf(employeeId)
                ?: notFoundException("Manager not found for employee")

            call.respond(manager)
        }

        get("/{id}/subordinates") {
            val managerId = call.parameters.getLongOrFail("id")
            val subordinates = employeeService.getSubordinates(managerId)
            call.respond(subordinates)
        }

        get("/{id}/subordinates-full-tree") {
            val employeeId = call.parameters.getLongOrFail("id")
            val employeeWithSubordinates = employeeService.getSubordinatesFullTree(employeeId)
                ?: notFoundException("Employee `${employeeId}` not found")
            call.respond(employeeWithSubordinates)
        }

        patch("/{id}/manager") {
            val employeeId = call.parameters.getLongOrFail("id")
            val req = call.receive<ChangeManagerRequest>()

            employeeService.changeManager(employeeId, req.newManagerId)

            call.respond(mapOf("success" to true))
        }

        delete("/{id}") {
            val employeeId = call.parameters.getLongOrFail("id")
            val cascade = call.request.queryParameters["cascade"]?.toBoolean() ?: false

            if (cascade) {
                // Delete employee and all subordinates
                val removedCount = employeeService.removeEmployeeWithSubordinates(employeeId)
                call.respond(DeleteEmployeeResponse(
                    success = true,
                    removedCount = removedCount
                ))
            } else {
                // Delete employee only if they have no subordinates
                val removed = employeeService.removeEmployeeWithoutSubordinates(employeeId)
                if (removed) {
                    call.respond(DeleteEmployeeResponse(success = true))
                } else {
                    call.respond(
                        io.ktor.http.HttpStatusCode.Conflict,
                        DeleteEmployeeResponse(
                            success = false,
                            error = "Cannot delete employee with subordinates. Use cascade=true to delete the entire subtree."
                        )
                    )
                }
            }
        }
    }
}
