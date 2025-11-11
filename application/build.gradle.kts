plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // API module (routes, DTOs)
    implementation(project(":api"))

    // Service modules
    implementation(project(":service-review"))
    implementation(project(":service-employee"))
    implementation(project(":service-notification"))

    // Database module (shared schema and initialization)
    implementation(project(":database"))

    // Ktor server
    implementation(libs.bundles.ktor)

    // Kodein DI
    implementation(libs.bundles.kodein)

    // Logging
    implementation(libs.logback.classic)

    // Metrics
    implementation(libs.micrometer.registry.prometheus)

    // DB connection
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)

    // Utils
    implementation(libs.dotenv.vault)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(libs.slf4j.api)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

application {
    mainClass.set("org.jetbrains.hris.application.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}
