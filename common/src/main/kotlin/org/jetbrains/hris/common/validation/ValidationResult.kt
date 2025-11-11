package org.jetbrains.hris.common.validation

data class ValidationResult(
    val valid: Boolean,
    val message: String? = null
) {
    companion object {
        fun valid() = ValidationResult(true)
        fun invalid(message: String) = ValidationResult(false, message)
    }
}
