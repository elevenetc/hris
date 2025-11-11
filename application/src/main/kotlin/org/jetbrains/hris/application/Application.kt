package org.jetbrains.hris.application

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.hris.api.routes.*
import org.jetbrains.hris.application.config.Config
import org.jetbrains.hris.application.config.installPlugins
import org.jetbrains.hris.application.config.installMetrics
import org.jetbrains.hris.db.initDatabase
import org.jetbrains.hris.employee.EmployeeService
import org.jetbrains.hris.employee.employeeModule
import org.jetbrains.hris.infrastructure.infrastructureModule
import org.jetbrains.hris.notification.NotificationRepository
import org.jetbrains.hris.notification.NotificationService
import org.jetbrains.hris.notification.notificationModule
import org.jetbrains.hris.review.ReviewService
import org.jetbrains.hris.review.reviewModule
import org.kodein.di.DI
import org.kodein.di.instance

fun main() {
    embeddedServer(Netty, port = Config.APP_PORT, host = Config.APP_HOST) {
        init(connectDb = true)
    }.start(wait = true)
}

internal fun Application.init(connectDb: Boolean) {
    if(connectDb) connectDatabase()
    initDatabase()
    installPlugins()
    installMetrics()
    initServicesAndRouting()
}

private fun Application.initServicesAndRouting() {
    val di = DI {
        import(infrastructureModule(
            redisHost = Config.REDIS_HOST,
            redisPort = Config.REDIS_PORT
        ))
        import(reviewModule)
        import(employeeModule)
        import(notificationModule)
    }
    val reviewService by di.instance<ReviewService>()
    val employeeService by di.instance<EmployeeService>()
    val notificationRepo by di.instance<NotificationRepository>()
    val notificationService by di.instance<NotificationService>()
    val cache by di.instance<org.jetbrains.hris.infrastructure.cache.Cache>()

    notificationService.start()

    monitor.subscribe(ApplicationStopped) {
        notificationService.stop()
        cache.close()
    }

    routing {
        this.healthRoutes(cache)
        this.employeeRoutes(employeeService)
        this.reviewRoutes(reviewService)
        this.notificationRoutes(notificationRepo)
    }
}

private fun connectDatabase() {
    Database.connect(
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = Config.DB_URL
                username = Config.DB_USER
                password = Config.DB_PASSWORD
                driverClassName = "org.postgresql.Driver"

                // Pool sizing
                maximumPoolSize = Config.DB_MAX_POOL_SIZE
                minimumIdle = Config.DB_MIN_IDLE

                // Connection lifecycle
                maxLifetime = Config.DB_MAX_LIFETIME
                idleTimeout = Config.DB_IDLE_TIMEOUT
                connectionTimeout = Config.DB_CONNECTION_TIMEOUT

                // Monitoring
                leakDetectionThreshold = Config.DB_LEAK_DETECTION_THRESCHOLD
            }
        )
    )
}