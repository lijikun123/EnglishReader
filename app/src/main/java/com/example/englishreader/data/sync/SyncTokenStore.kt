package com.example.englishreader.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SyncSession(
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
)

/**
 * Stores refresh/access tokens encrypted with a non-exportable Android Keystore
 * AES-GCM key. The ciphertext preference file is excluded from Android backup.
 */
class SyncTokenStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun read(): SyncSession? = runCatching {
        val access = preferences.getString(KEY_ACCESS, null)?.let(::decrypt) ?: return null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        val refresh = preferences.getString(KEY_REFRESH, null)?.let(::decrypt) ?: return null
        if (expiresAt <= 0L) return null
        SyncSession(access, expiresAt, refresh)
    }.getOrElse {
        clear()
        null
    }

    @Synchronized
    fun save(session: SyncSession) {
        preferences.edit()
            .putString(KEY_ACCESS, encrypt(session.accessToken))
            .putLong(KEY_EXPIRES_AT, session.accessTokenExpiresAt)
            .putString(KEY_REFRESH, encrypt(session.refreshToken))
            .apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(ByteBuffer.allocate(cipher.iv.size + cipherText.size)
            .put(cipher.iv)
            .put(cipherText)
            .array(), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Invalid encrypted sync session" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val cipherText = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    private companion object {
        const val FILE_NAME = "sync_secure_session"
        const val KEY_ALIAS = "english_reader_sync_session_v1"
        const val KEY_ACCESS = "access"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_REFRESH = "refresh"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
