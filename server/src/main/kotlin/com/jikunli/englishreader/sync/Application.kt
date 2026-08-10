package com.jikunli.englishreader.sync

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

fun main() {
    val config = AppConfig.fromEnvironment()
    embeddedServer(Netty, host = config.host, port = config.port) {
        kreaderModule(config)
    }.start(wait = true)
}

fun Application.kreaderModule(
    config: AppConfig = AppConfig.fromEnvironment(),
    database: KreaderDatabase = KreaderDatabase.connect(config),
) {
    val logger = LoggerFactory.getLogger("com.jikunli.englishreader.sync.Application")
    val passwordHasher = PasswordHasher()
    val tokenService = TokenService(config)
    val authAttemptGuard = AuthAttemptGuard(config.maxAuthAttemptsPerMinute)
    database.migrate()
    monitor.subscribe(ApplicationStopped) { database.close() }
    monitor.subscribe(ApplicationStarted) {
        logger.info("KReader sync API started on {}:{}", config.host, config.port)
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path() != "/healthz" }
    }
    install(Compression) {
        gzip()
    }
    install(ContentNegotiation) {
        json(apiJson)
    }
    install(StatusPages) {
        exception<ApiException> { call, error ->
            call.respond(error.status, ApiError(error.errorCode, error.message))
        }
        exception<Throwable> { call, error ->
            logger.error("Unhandled request error", error)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "An unexpected server error occurred"))
        }
    }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "kreader-sync"
            verifier(tokenService.verifier)
            validate { credential ->
                credential.payload.subject?.let { io.ktor.server.auth.jwt.JWTPrincipal(credential.payload) }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid access token is required"))
            }
        }
    }

    routing {
        get("/healthz") {
            call.respond(HealthResponse("ok", System.currentTimeMillis()))
        }
        get("/readyz") {
            database.ping()
            call.respond(HealthResponse("ready", System.currentTimeMillis()))
        }
        authRoutes(config, database, passwordHasher, tokenService, authAttemptGuard)
        authenticate("auth-jwt") {
            authenticatedRoutes(config, database, tokenService)
        }
    }
}
