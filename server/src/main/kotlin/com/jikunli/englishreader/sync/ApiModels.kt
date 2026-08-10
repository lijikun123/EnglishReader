package com.jikunli.englishreader.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

@Serializable
data class HealthResponse(
    val status: String,
    val now: Long,
)

@Serializable
data class AuthRequest(
    val email: String,
    val password: String,
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
    val deviceId: String,
)

@Serializable
data class LogoutRequest(
    val refreshToken: String,
)

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    val user: UserResponse,
)

@Serializable
data class SyncMutation(
    val mutationId: String,
    val kind: String,
    val occurredAt: Long,
    val payload: JsonObject,
)

@Serializable
data class SyncPushRequest(
    val deviceId: String,
    val mutations: List<SyncMutation>,
)

@Serializable
data class MutationRejection(
    val mutationId: String,
    val code: String,
    val message: String,
)

@Serializable
data class SyncPushResponse(
    val acceptedMutationIds: List<String>,
    val duplicateMutationIds: List<String>,
    val rejected: List<MutationRejection>,
    val bookIdMappings: List<BookIdMapping> = emptyList(),
    val serverNow: Long,
)

/** Maps a first-upload client UUID to the canonical cloud book UUID. */
@Serializable
data class BookIdMapping(
    val clientBookId: String,
    val cloudBookId: String,
)

@Serializable
data class SyncChange(
    val cursor: Long,
    val kind: String,
    val entityId: String,
    val revision: Long,
    val payload: JsonObject,
    val occurredAt: Long,
    val serverUpdatedAt: Long,
)

@Serializable
data class SyncPullResponse(
    val changes: List<SyncChange>,
    val nextCursor: Long,
    val hasMore: Boolean,
    val serverNow: Long,
)

@Serializable
data class BookUpsertPayload(
    val bookId: String,
    val title: String,
    val author: String = "",
    val contentType: String,
    val format: String,
    /** SHA-256 of the canonical, uncompressed BookBundleV1 JSON bytes. */
    val contentSha256: String,
    val contentBytes: Long = 0,
    val contentRevision: Long = 1,
)

@Serializable
data class BookDeletePayload(
    val bookId: String,
)

@Serializable
data class ProgressUpsertPayload(
    val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val chapterProgress: Double,
    val bookProgress: Double,
)

@Serializable
data class BundleReceipt(
    val bookId: String,
    val sha256: String,
    val contentBytes: Long,
    val updatedAt: Long,
)
