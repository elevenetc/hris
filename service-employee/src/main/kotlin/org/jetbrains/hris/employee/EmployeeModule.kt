package org.jetbrains.hris.employee

import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton

/**
 * Kodein DI module for the Employee Service.
 *
 * Defines how to construct EmployeeService and its dependencies.
 * This module can be imported into the main application's DI container.
 *
 * Example usage in Application.kt:
 * ```
 * val di = DI {
 *     import(employeeModule)
 * }
 * val employeeService by di.instance<EmployeeService>()
 * ```
 */
val employeeModule = DI.Module("employee") {
    // Repository layer
    bind<EmployeeRepository>() with singleton {
        EmployeeRepository()
    }

    // Service layer
    bind<EmployeeService>() with singleton {
        EmployeeService(
            employeeRepo = instance(),
            eventBus = instance(),
            cache = instance()
        )
    }
}
