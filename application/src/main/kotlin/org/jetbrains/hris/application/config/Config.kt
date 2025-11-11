package org.jetbrains.hris.application.config

import org.dotenv.vault.dotenvVault

object Config {
    val DB_NAME by lazy { getEnvVar("DB_NAME") }
    val DB_USER by lazy { getEnvVar("DB_USER") }
    val DB_PASSWORD by lazy { getEnvVar("DB_PASSWORD") }
    val DB_PORT by lazy { getEnvVar("DB_PORT") }
    val DB_URL by lazy { "jdbc:postgresql://localhost:$DB_PORT/$DB_NAME" }

    val DB_MAX_POOL_SIZE = 50
    val DB_MIN_IDLE = 10
    val DB_MAX_LIFETIME = 1800000L // 30 min
    val DB_IDLE_TIMEOUT = 600000L // 10 min
    val DB_CONNECTION_TIMEOUT = 30000L // 30 sec
    val DB_LEAK_DETECTION_THRESCHOLD = 60000L // 60 sec

    val APP_HOST by lazy { getEnvVar("APP_HOST") }
    val APP_PORT by lazy { getEnvVar("APP_PORT").toInt() }

    val REDIS_HOST by lazy { getEnvVar("REDIS_HOST", "localhost") }
    val REDIS_PORT by lazy { getEnvVar("REDIS_PORT", "6380").toInt() }
}

private fun getEnvVar(name: String, default: String? = null): String {
    return try {
        dotenvVault().get(name) ?: default ?: error("Environment variable $name is not set")
    } catch (e: Exception) {
        // In test environment, dotenv might not be available
        System.getenv(name) ?: default ?: error("Environment variable $name is not set")
    }
}