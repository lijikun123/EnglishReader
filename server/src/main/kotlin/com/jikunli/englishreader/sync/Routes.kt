package com.jikunli.englishreader.sync

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val REFRESH_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
private const val MAX_MUTATIONS_PER_PUSH = 100
private const val MAX_FUTURE_CLOCK_SKEW_MILLIS = 24L * 60 * 60 * 1_000

fun Route.authRoutes(
    config: AppConfig,
    database: KreaderDatabase,
    passwordHasher: PasswordHasher,
    tokenService: TokenService,
    authAttemptGuard: AuthAttemptGuard,
) {
    route("/v1/auth") {
        post("/register") {
            call.requireDeclaredBodyAtMost(config.maxJsonBytes)
            authAttemptGuard.check(call.authRateLimitKey())
            if (!config.allowRegistration) {
                throw ApiException(HttpStatusCode.Forbidden, "registration_closed", "Registration is currently closed")
            }
            val request = call.receive<AuthRequest>()
            val email = normalizeEmail(request.email)
            validatePassword(request.password)
            val deviceId = parseUuid(request.deviceId, "device_id_invalid", "Invalid device ID")
            val deviceName = normalizeDeviceName(request.deviceName)
            val now = System.currentTimeMillis()
            val user = database.createUser(email, passwordHasher.hash(request.password.toCharArray()), now)
            database.touchDevice(user.id, deviceId, deviceName, now)
            call.respond(HttpStatusCode.Created, issueSession(database, tokenService, user, deviceId, now))
        }

        post("/login") {
            call.requireDeclaredBodyAtMost(config.maxJsonBytes)
            authAttemptGuard.check(call.authRateLimitKey())
            val request = call.receive<AuthRequest>()
            val email = normalizeEmail(request.email)
            val deviceId = parseUuid(request.deviceId, "device_id_invalid", "Invalid device ID")
            val user = database.findUserByEmail(email)
            if (user == null || user.disabledAt != null || !passwordHasher.verify(user.passwordHash, request.password.toCharArray())) {
                throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Invalid email or password")
            }
            val now = System.currentTimeMillis()
            database.touchDevice(user.id, deviceId, normalizeDeviceName(request.deviceName), now)
            call.respond(issueSession(database, tokenService, user, deviceId, now))
        }

        post("/refresh") {
            call.requireDeclaredBodyAtMost(config.maxJsonBytes)
            authAttemptGuard.check(call.authRateLimitKey())
            val request = call.receive<RefreshRequest>()
            val deviceId = parseUuid(request.deviceId, "device_id_invalid", "Invalid device ID")
            val now = System.currentTimeMillis()
            val refresh = database.consumeRefreshToken(sha256Hex(request.refreshToken), deviceId, now)
                ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_refresh_token", "Refresh token is invalid or expired")
            val user = database.findUserById(refresh.userId)
                ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_refresh_token", "Refresh token is invalid or expired")
            if (user.disabledAt != null) {
                throw ApiException(HttpStatusCode.Forbidden, "account_disabled", "This account is disabled")
            }
            call.respond(issueSession(database, tokenService, user, deviceId, now))
        }
    }
}

fun Route.authenticatedRoutes(
    config: AppConfig,
    database: KreaderDatabase,
    tokenService: TokenService,
) {
    route("/v1") {
        get("/me") {
            val user = database.findUserById(call.currentUserId())
                ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_token", "Account no longer exists")
            call.respond(UserResponse(user.id.toString(), user.email))
        }

        post("/auth/logout") {
            call.requireDeclaredBodyAtMost(config.maxJsonBytes)
            val request = call.receive<LogoutRequest>()
            database.revokeRefreshToken(call.currentUserId(), sha256Hex(request.refreshToken), System.currentTimeMillis())
            call.respond(HttpStatusCode.NoContent)
        }

        route("/sync") {
            post("/push") {
                call.requireDeclaredBodyAtMost(config.maxJsonBytes)
                val userId = call.currentUserId()
                val tokenDeviceId = call.currentDeviceId()
                val request = call.receive<SyncPushRequest>()
                val requestDeviceId = parseUuid(request.deviceId, "device_id_invalid", "Invalid device ID")
                if (tokenDeviceId != requestDeviceId) {
                    throw ApiException(HttpStatusCode.Forbidden, "device_mismatch", "Token and request device IDs differ")
                }
                database.assertDeviceOwnedBy(userId, requestDeviceId)
                if (request.mutations.size > MAX_MUTATIONS_PER_PUSH) {
                    throw ApiException(
                        HttpStatusCode.PayloadTooLarge,
                        "too_many_mutations",
                        "A sync request may contain at most $MAX_MUTATIONS_PER_PUSH mutations",
                    )
                }

                val now = System.currentTimeMillis()
                val accepted = mutableListOf<String>()
                val duplicates = mutableListOf<String>()
                val rejected = mutableListOf<MutationRejection>()
                val bookIdMappings = mutableListOf<BookIdMapping>()
                request.mutations.forEach { mutation ->
                    try {
                        if (mutation.occurredAt > now + MAX_FUTURE_CLOCK_SKEW_MILLIS) {
                            throw ApiException(
                                HttpStatusCode.BadRequest,
                                "clock_skew",
                                "Mutation time is more than 24 hours in the future",
                            )
                        }
                        val mutationId = parseUuid(mutation.mutationId, "mutation_id_invalid", "Invalid mutation ID")
                        val command = parseSyncCommand(mutation)
                        val outcome = database.applyMutation(userId, requestDeviceId, mutationId, command, now)
                        when (outcome.result) {
                            MutationApplyResult.APPLIED -> accepted += mutation.mutationId
                            MutationApplyResult.DUPLICATE -> duplicates += mutation.mutationId
                        }
                        if (command is SyncCommand.BookUpsert && outcome.canonicalBookId != null) {
                            bookIdMappings += BookIdMapping(
                                clientBookId = command.entityId.toString(),
                                cloudBookId = outcome.canonicalBookId.toString(),
                            )
                        }
                    } catch (error: ApiException) {
                        rejected += MutationRejection(mutation.mutationId, error.errorCode, error.message)
                    } catch (error: IllegalArgumentException) {
                        rejected += MutationRejection(mutation.mutationId, "invalid_mutation", error.message ?: "Invalid mutation")
                    } catch (error: SerializationException) {
                        rejected += MutationRejection(mutation.mutationId, "invalid_payload", "Mutation payload is invalid")
                    }
                }
                call.respond(
                    SyncPushResponse(
                        acceptedMutationIds = accepted,
                        duplicateMutationIds = duplicates,
                        rejected = rejected,
                        bookIdMappings = bookIdMappings.distinct(),
                        serverNow = now,
                    ),
                )
            }

            get("/pull") {
                val userId = call.currentUserId()
                val cursor = call.request.queryParameters["cursor"]?.toLongOrNull() ?: 0L
                if (cursor < 0) throw ApiException(HttpStatusCode.BadRequest, "cursor_invalid", "Cursor must not be negative")
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 200)
                val page = database.changesAfter(userId, cursor, limit)
                val nextCursor = page.changes.lastOrNull()?.cursor ?: cursor
                call.respond(SyncPullResponse(page.changes, nextCursor, page.hasMore, System.currentTimeMillis()))
            }
        }

        route("/books/{bookId}/bundle") {
            put {
                val userId = call.currentUserId()
                val bookId = parseUuid(call.parameters["bookId"], "book_id_invalid", "Invalid book ID")
                val expectedSha256 = call.requiredBundleSha256()
                val expectedContentRevision = call.requiredBundleRevision()
                val raw = call.receiveBundle(config.maxBundleBytes)
                call.respond(
                    HttpStatusCode.Created,
                    database.storeBundle(
                        userId = userId,
                        bookId = bookId,
                        raw = raw,
                        expectedSha256 = expectedSha256,
                        expectedContentRevision = expectedContentRevision,
                        now = System.currentTimeMillis(),
                    ),
                )
            }

            get {
                val userId = call.currentUserId()
                val bookId = parseUuid(call.parameters["bookId"], "book_id_invalid", "Invalid book ID")
                val bundle = database.loadBundle(userId, bookId)
                call.response.header("ETag", "\"${bundle.sha256}\"")
                call.respondBytes(
                    bytes = bundle.bytes,
                    contentType = ContentType.parse("application/vnd.kreader.book-bundle+json"),
                )
            }
        }
    }
}

private fun issueSession(
    database: KreaderDatabase,
    tokenService: TokenService,
    user: UserRecord,
    deviceId: UUID,
    now: Long,
): AuthResponse {
    val access = tokenService.issueAccessToken(user.id, deviceId, now)
    val rawRefresh = randomOpaqueToken()
    database.createRefreshToken(
        userId = user.id,
        deviceId = deviceId,
        tokenHash = sha256Hex(rawRefresh),
        now = now,
        expiresAt = now + REFRESH_TTL_MILLIS,
    )
    return AuthResponse(
        accessToken = access.value,
        accessTokenExpiresAt = access.expiresAt,
        refreshToken = rawRefresh,
        user = UserResponse(user.id.toString(), user.email),
    )
}

private fun parseSyncCommand(mutation: SyncMutation): SyncCommand = when (mutation.kind) {
    "book.upsert" -> {
        val payload = apiJson.decodeFromJsonElement<BookUpsertPayload>(mutation.payload)
        validateBookPayload(payload)
        SyncCommand.BookUpsert(payload, mutation.occurredAt, mutation.payload)
    }

    "book.delete" -> {
        val payload = apiJson.decodeFromJsonElement<BookDeletePayload>(mutation.payload)
        parseUuid(payload.bookId, "book_id_invalid", "Invalid book ID")
        SyncCommand.BookDelete(payload, mutation.occurredAt, mutation.payload)
    }

    "progress.upsert" -> {
        val payload = apiJson.decodeFromJsonElement<ProgressUpsertPayload>(mutation.payload)
        parseUuid(payload.bookId, "book_id_invalid", "Invalid book ID")
        require(payload.chapterIndex >= 0) { "Chapter index must not be negative" }
        require(payload.charOffset >= 0) { "Character offset must not be negative" }
        require(payload.chapterProgress in 0.0..1.0) { "Chapter progress must be between 0 and 1" }
        require(payload.bookProgress in 0.0..1.0) { "Book progress must be between 0 and 1" }
        SyncCommand.ProgressUpsert(payload, mutation.occurredAt, mutation.payload)
    }

    else -> throw ApiException(HttpStatusCode.BadRequest, "mutation_kind_invalid", "Unsupported mutation kind")
}

private fun validateBookPayload(payload: BookUpsertPayload) {
    parseUuid(payload.bookId, "book_id_invalid", "Invalid book ID")
    require(payload.title.isNotBlank() && payload.title.length <= 512) { "Book title is required and must be at most 512 characters" }
    require(payload.author.length <= 512) { "Book author must be at most 512 characters" }
    require(payload.contentType in setOf("NOVEL", "ARTICLE")) { "Unsupported content type" }
    require(payload.format in setOf("TXT", "MARKDOWN", "EPUB")) { "Unsupported book format" }
    require(payload.contentBytes >= 0) { "Content size must not be negative" }
    require(payload.contentRevision >= 1) { "Content revision must be at least 1" }
    require(payload.contentSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
        "Content SHA-256 must be 64 hexadecimal characters"
    }
}

private suspend fun ApplicationCall.receiveBundle(maxBytes: Long): ByteArray {
    request.headers["Content-Length"]?.toLongOrNull()?.let { declaredLength ->
        if (declaredLength > maxBytes) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "bundle_too_large", "Book bundle exceeds the size limit")
        }
    }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var received = 0L
    val channel = receiveChannel()
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count == -1) break
        if (count == 0) continue
        received += count
        if (received > maxBytes) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "bundle_too_large", "Book bundle exceeds the size limit")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun ApplicationCall.requiredBundleSha256(): String {
    val value = request.headers["X-KReader-Content-SHA256"]?.lowercase()
        ?: throw ApiException(HttpStatusCode.BadRequest, "bundle_hash_required", "X-KReader-Content-SHA256 is required")
    if (!value.matches(Regex("[a-f0-9]{64}"))) {
        throw ApiException(HttpStatusCode.BadRequest, "bundle_hash_invalid", "Bundle SHA-256 must be 64 hexadecimal characters")
    }
    return value
}

private fun ApplicationCall.requiredBundleRevision(): Long =
    request.headers["X-KReader-Content-Revision"]?.toLongOrNull()?.takeIf { it >= 1 }
        ?: throw ApiException(
            HttpStatusCode.BadRequest,
            "bundle_revision_required",
            "X-KReader-Content-Revision must be a positive integer",
        )

private fun ApplicationCall.currentUserId(): UUID {
    val subject = principal<JWTPrincipal>()?.payload?.subject
        ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_token", "Authentication token is invalid")
    return parseUuid(subject, "invalid_token", "Authentication token is invalid")
}

private fun ApplicationCall.currentDeviceId(): UUID {
    val deviceId = principal<JWTPrincipal>()?.payload?.getClaim("deviceId")?.asString()
        ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_token", "Authentication token is invalid")
    return parseUuid(deviceId, "invalid_token", "Authentication token is invalid")
}

private fun normalizeEmail(value: String): String {
    val normalized = value.trim().lowercase()
    if (normalized.length !in 3..320 || !normalized.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
        throw ApiException(HttpStatusCode.BadRequest, "email_invalid", "Enter a valid email address")
    }
    return normalized
}

private fun normalizeDeviceName(value: String): String {
    val normalized = value.trim()
    if (normalized.length !in 1..100) {
        throw ApiException(HttpStatusCode.BadRequest, "device_name_invalid", "Device name must be 1 to 100 characters")
    }
    return normalized
}

private fun validatePassword(password: String) {
    if (password.length !in 10..256) {
        throw ApiException(HttpStatusCode.BadRequest, "password_invalid", "Password must be 10 to 256 characters")
    }
}

private fun parseUuid(value: String?, code: String, message: String): UUID = try {
    UUID.fromString(value)
} catch (_: IllegalArgumentException) {
    throw ApiException(HttpStatusCode.BadRequest, code, message)
}
