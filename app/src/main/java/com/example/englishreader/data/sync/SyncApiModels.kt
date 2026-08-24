package com.example.englishreader.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SyncApiError(val code: String, val message: String)

@Serializable
data class AuthRequest(
    val email: String,
    val password: String,
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class RefreshRequest(val refreshToken: String, val deviceId: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class SyncUser(val id: String, val email: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    val user: SyncUser,
)

@Serializable
data class SyncMutationRequest(
    val mutationId: String,
    val kind: String,
    val occurredAt: Long,
    val payload: JsonObject,
)

@Serializable
data class SyncPushRequest(val deviceId: String, val mutations: List<SyncMutationRequest>)

@Serializable
data class MutationRejection(val mutationId: String, val code: String, val message: String)

@Serializable
data class BookIdMapping(val clientBookId: String, val cloudBookId: String)

@Serializable
data class SyncPushResponse(
    val acceptedMutationIds: List<String>,
    val duplicateMutationIds: List<String>,
    val rejected: List<MutationRejection>,
    val bookIdMappings: List<BookIdMapping> = emptyList(),
    val serverNow: Long,
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
data class BundleReceipt(
    val bookId: String,
    val sha256: String,
    val contentBytes: Long,
    val updatedAt: Long,
)
