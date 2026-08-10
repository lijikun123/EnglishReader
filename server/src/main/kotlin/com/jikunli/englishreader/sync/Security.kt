package com.jikunli.englishreader.sync

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import de.mkammerer.argon2.Argon2Factory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.UUID

class PasswordHasher {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    fun hash(password: CharArray): String =
        try {
            // 64 MiB / 3 iterations makes password guessing expensive while still
            // leaving headroom for this small VPS.
            argon2.hash(3, 65_536, 1, password)
        } finally {
            password.fill('\u0000')
        }

    fun verify(hash: String, password: CharArray): Boolean =
        try {
            argon2.verify(hash, password)
        } finally {
            password.fill('\u0000')
        }
}

class TokenService(private val config: AppConfig) {
    private val algorithm = Algorithm.HMAC512(config.jwtSecret)
    private val ttlMillis = 15 * 60 * 1_000L

    val verifier: JWTVerifier = JWT
        .require(algorithm)
        .withIssuer(config.jwtIssuer)
        .withAudience(config.jwtAudience)
        .build()

    fun issueAccessToken(userId: UUID, deviceId: UUID, now: Long = System.currentTimeMillis()): IssuedAccessToken {
        val expiresAt = now + ttlMillis
        val value = JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(userId.toString())
            .withClaim("deviceId", deviceId.toString())
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(expiresAt))
            .sign(algorithm)
        return IssuedAccessToken(value, expiresAt)
    }
}

data class IssuedAccessToken(
    val value: String,
    val expiresAt: Long,
)

private val secureRandom = SecureRandom()

fun randomOpaqueToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))
