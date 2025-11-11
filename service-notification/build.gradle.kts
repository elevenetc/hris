plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    // Core dependencies
    api(project(":common"))
    api(project(":infrastructure"))

    // Database module (shared schema access)
    implementation(project(":database"))

    // Coroutines for async processing
    implementation(libs.coroutines.core)

    // Logging
    implementation(libs.slf4j.api)

    // Kodein DI
    implementation(libs.bundles.kodein)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(project(":service-employee"))
    testImplementation(project(":service-review"))
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
