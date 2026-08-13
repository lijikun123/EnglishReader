package com.example.englishreader.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class SyncApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : Exception(message)

/** Thin authenticated client for the private KReader sync API. */
class SyncApi(
    private val client: HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(ApiJson)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 45_000
            socketTimeoutMillis = 45_000
        }
        expectSuccess = false
    },
) {
    suspend fun register(baseUrl: String, request: AuthRequest): AuthResponse =
        client.post("${baseUrl.normalized()}/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireSuccess().body()

    suspend fun login(baseUrl: String, request: AuthRequest): AuthResponse =
        client.post("${baseUrl.normalized()}/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireSuccess().body()

    suspend fun refresh(baseUrl: String, request: RefreshRequest): AuthResponse =
        client.post("${baseUrl.normalized()}/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireSuccess().body()

    suspend fun logout(baseUrl: String, accessToken: String, request: LogoutRequest) {
        client.post("${baseUrl.normalized()}/v1/auth/logout") {
            bearer(accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireSuccess()
    }

    suspend fun push(baseUrl: String, accessToken: String, request: SyncPushRequest): SyncPushResponse =
        client.post("${baseUrl.normalized()}/v1/sync/push") {
            bearer(accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireSuccess().body()

    suspend fun pull(baseUrl: String, accessToken: String, cursor: Long): SyncPullResponse =
        client.get("${baseUrl.normalized()}/v1/sync/pull") {
            bearer(accessToken)
            header(HttpHeaders.Connection, "close")
            parameter("cursor", cursor)
            parameter("limit", 100)
            // Fail a stalled socket promptly. SyncRepository retries this whole
            // read call (including body decoding) on a fresh connection.
            timeout { requestTimeoutMillis = PULL_TIMEOUT_MILLIS }
        }.requireSuccess().body()

    suspend fun uploadBundle(
        baseUrl: String,
        accessToken: String,
        cloudBookId: String,
        raw: ByteArray,
        sha256: String,
        contentRevision: Long,
    ): BundleReceipt = client.put("${baseUrl.normalized()}/v1/books/$cloudBookId/bundle") {
        bearer(accessToken)
        contentType(BUNDLE_CONTENT_TYPE)
        header("X-KReader-Content-SHA256", sha256)
        header("X-KReader-Content-Revision", contentRevision)
        setBody(raw)
    }.requireSuccess().body()

    suspend fun downloadBundle(baseUrl: String, accessToken: String, cloudBookId: String): ByteArray =
        client.get("${baseUrl.normalized()}/v1/books/$cloudBookId/bundle") {
            bearer(accessToken)
            header(HttpHeaders.Connection, "close")
        }.requireSuccess().body()

    fun close() = client.close()

    private fun String.normalized(): String = trim().trimEnd('/').also {
        require(it.startsWith("https://")) { "同步服务器必须使用 HTTPS" }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(accessToken: String) {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    }

    private suspend fun HttpResponse.requireSuccess(): HttpResponse {
        if (status.isSuccess()) return this
        val body = bodyAsText()
        val apiError = runCatching { ApiJson.decodeFromString<SyncApiError>(body) }.getOrNull()
        throw SyncApiException(
            status = status,
            code = apiError?.code ?: "http_${status.value}",
            message = apiError?.message ?: "同步服务请求失败（HTTP ${status.value}）",
        )
    }

    private companion object {
        const val PULL_TIMEOUT_MILLIS = 12_000L
        val BUNDLE_CONTENT_TYPE: ContentType = ContentType.parse("application/vnd.kreader.book-bundle+json")
    }
}

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
