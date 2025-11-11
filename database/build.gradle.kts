plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    // Exposed ORM
    implementation(libs.bundles.exposed)

    // Database
    implementation(libs.bundles.database)

    // Coroutines
    implementation(libs.coroutines.core)

    // Logging
    implementation(libs.slf4j.api)

    // Test dependencies on service modules (for OrgRepositoryTest)
    testImplementation(project(":service-employee"))

    // Testing
    testImplementation(libs.bundles.testing)
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

kotlin {
    jvmToolchain(21)
}
