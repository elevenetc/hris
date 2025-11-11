package org.jetbrains.hris.employee

import org.jetbrains.hris.common.validation.ValidationResult

object EmployeeValidator {
    fun validateAddEmployee(
        firstName: String,
        lastName: String,
        email: String
    ): ValidationResult {
        return when {
            firstName.isBlank() -> ValidationResult.invalid("firstName is required and cannot be blank")
            lastName.isBlank() -> ValidationResult.invalid("lastName is required and cannot be blank")
            email.isBlank() -> ValidationResult.invalid("email is required and cannot be blank")
            else -> ValidationResult.valid()
        }
    }
}
