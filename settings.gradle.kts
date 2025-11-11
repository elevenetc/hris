plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "hris"

// Core modules
include(":common")
include(":infrastructure")

// Service modules
include(":service-employee")
include(":service-review")
include(":service-notification")

// Application (composition root, bootstrap)
include(":application")

// API Gateway
include(":api")

// Database (shared schema and initialization)
include(":database")
