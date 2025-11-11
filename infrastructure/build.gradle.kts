plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

dependencies {
    // Depend on common for shared types/events
    api(project(":common"))

    // Database
    api(libs.bundles.exposed)
    api(libs.bundles.database)

    // Coroutines for EventBus
    api(libs.coroutines.core)
    api(libs.slf4j.api)

    // Redis/Cache
    api(libs.lettuce.core)
    api(libs.ktor.serialization.json)

    // Kodein DI
    api(libs.bundles.kodein)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(libs.testcontainers.core)
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
