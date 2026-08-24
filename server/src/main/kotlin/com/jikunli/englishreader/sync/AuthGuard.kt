package com.jikunli.englishreader.sync

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.util.concurrent.ConcurrentHashMap

/**
 * Small in-process guard for the password endpoints. It is deliberately simple:
 * this service is a single-instance personal backend, and the hard limit on
 * attempts keeps Argon2 work bounded on a 2 GB VPS.
 */
class AuthAttemptGuard(
    private val maxAttempts: Int,
    private val windowMillis: Long = 60_000L,
) {
    private data class Bucket(var startedAt: Long, var attempts: Int)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun check(key: String, now: Long = System.currentTimeMillis()) {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        var allowed = false
        buckets.compute(key) { _, current ->
            val bucket = current ?: Bucket(now, 0)
            if (now - bucket.startedAt >= windowMillis) {
                bucket.startedAt = now
                bucket.attempts = 0
            }
            if (bucket.attempts < maxAttempts) {
                bucket.attempts += 1
                allowed = true
            }
            bucket
        }
        if (!allowed) {
            throw ApiException(HttpStatusCode.TooManyRequests, "auth_rate_limited", "Too many attempts; try again in a minute")
        }
    }
}

fun ApplicationCall.authRateLimitKey(): String =
    request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: request.local.remoteHost

fun ApplicationCall.requireDeclaredBodyAtMost(maxBytes: Long) {
    val declared = request.headers["Content-Length"]?.toLongOrNull()
        ?: throw ApiException(HttpStatusCode.LengthRequired, "content_length_required", "Content-Length is required")
    if (declared < 0 || declared > maxBytes) {
        throw ApiException(HttpStatusCode.PayloadTooLarge, "body_too_large", "Request body exceeds the size limit")
    }
}
