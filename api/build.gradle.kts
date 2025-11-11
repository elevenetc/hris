plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
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

    // DB connection (explicit here so :api doesn't rely on :db's transitive deps)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)

    // Utils
    implementation(libs.dotenv.vault)
}

kotlin {
    jvmToolchain(21)
}
