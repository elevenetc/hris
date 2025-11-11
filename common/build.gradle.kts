plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

dependencies {
    // Shared dependencies available to all modules
    api(libs.coroutines.core)
    api(libs.slf4j.api)
    api(libs.kotlinx.datetime)

    // Serialization for DTOs
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(21)
}
