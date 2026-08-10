package com.example.englishreader.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

data class SyncSettings(
    val serverUrl: String = "",
    val deviceId: String = "",
    val userId: String? = null,
    val email: String? = null,
    val pullCursor: Long = 0,
    val lastSuccessfulSyncAt: Long? = null,
)

/** Non-secret sync settings. Tokens are intentionally kept in [SyncTokenStore]. */
class SyncSettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("sync_server_url")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
        val USER_ID = stringPreferencesKey("sync_user_id")
        val EMAIL = stringPreferencesKey("sync_email")
        val PULL_CURSOR = longPreferencesKey("sync_pull_cursor")
        val LAST_SUCCESSFUL_SYNC_AT = longPreferencesKey("sync_last_successful_at")
    }

    val settings: Flow<SyncSettings> = dataStore.data.map { prefs ->
        SyncSettings(
            serverUrl = prefs[Keys.SERVER_URL] ?: DEFAULT_SERVER_URL,
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            userId = prefs[Keys.USER_ID],
            email = prefs[Keys.EMAIL],
            pullCursor = prefs[Keys.PULL_CURSOR] ?: 0L,
            lastSuccessfulSyncAt = prefs[Keys.LAST_SUCCESSFUL_SYNC_AT],
        )
    }

    suspend fun current(): SyncSettings = settings.first()

    suspend fun deviceId(): String {
        val existing = current().deviceId
        if (existing.isNotBlank()) return existing
        val generated = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            if (prefs[Keys.DEVICE_ID].isNullOrBlank()) prefs[Keys.DEVICE_ID] = generated
        }
        return current().deviceId.ifBlank { generated }
    }

    suspend fun setServerUrl(value: String) {
        val normalized = normalizeServerUrl(value)
        dataStore.edit { it[Keys.SERVER_URL] = normalized }
    }

    suspend fun setAccount(userId: String, email: String) {
        dataStore.edit {
            it[Keys.USER_ID] = userId
            it[Keys.EMAIL] = email
            it[Keys.PULL_CURSOR] = 0L
        }
    }

    suspend fun setPullCursor(cursor: Long) {
        dataStore.edit { it[Keys.PULL_CURSOR] = cursor.coerceAtLeast(0L) }
    }

    suspend fun markSuccessfulSync(now: Long = System.currentTimeMillis()) {
        dataStore.edit { it[Keys.LAST_SUCCESSFUL_SYNC_AT] = now }
    }

    suspend fun clearAccount() {
        dataStore.edit {
            it.remove(Keys.USER_ID)
            it.remove(Keys.EMAIL)
            it.remove(Keys.PULL_CURSOR)
            it.remove(Keys.LAST_SUCCESSFUL_SYNC_AT)
        }
    }

    companion object {
        // Personal VPS endpoint. It remains HTTPS-only and the account API is
        // closed by default, so shipping this address reveals no user data.
        const val DEFAULT_SERVER_URL = "https://592600.xyz/kreader-sync"

        fun normalizeServerUrl(value: String): String {
            val normalized = value.trim().trimEnd('/')
            require(normalized.isEmpty() || normalized.startsWith("https://")) {
                "同步服务器必须使用 https:// 地址"
            }
            return normalized
        }
    }
}
