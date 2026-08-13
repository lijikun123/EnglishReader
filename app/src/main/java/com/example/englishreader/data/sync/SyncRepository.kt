package com.example.englishreader.data.sync

import android.os.Build
import androidx.room.withTransaction
import com.example.englishreader.data.local.AppDatabase
import com.example.englishreader.data.local.dao.ChapterPhraseDao
import com.example.englishreader.data.local.dao.ChapterTranslationDao
import com.example.englishreader.data.local.dao.LookupHistoryDao
import com.example.englishreader.data.local.dao.ReadingChapterDao
import com.example.englishreader.data.local.dao.ReadingItemDao
import com.example.englishreader.data.local.dao.ReadingTocItemDao
import com.example.englishreader.data.local.dao.SyncBookDao
import com.example.englishreader.data.local.dao.SyncOutboxDao
import com.example.englishreader.data.local.dao.VocabularyDao
import com.example.englishreader.data.local.entity.BookFormat
import com.example.englishreader.data.local.entity.ContentType
import com.example.englishreader.data.local.entity.ReadingChapter
import com.example.englishreader.data.local.entity.ReadingItem
import com.example.englishreader.data.local.entity.ReadingTocItem
import com.example.englishreader.data.local.entity.SyncBook
import com.example.englishreader.data.local.entity.SyncOutbox
import com.example.englishreader.data.local.entity.SyncOutboxKind
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

sealed interface SyncRunResult {
    data object Success : SyncRunResult
    /** Another caller owns the sync lock; settings should not wait indefinitely. */
    data object InProgress : SyncRunResult
    data object NotConfigured : SyncRunResult
    data object NotAuthenticated : SyncRunResult
    data class RetryableFailure(val message: String) : SyncRunResult
    data class PermanentFailure(val message: String) : SyncRunResult
}

data class SyncRuntimeState(
    val syncing: Boolean = false,
    val lastMessage: String? = null,
)

sealed interface SyncActionResult {
    data object Success : SyncActionResult
    data class Failure(val message: String) : SyncActionResult
}

@Serializable
private data class RemoteBookPayload(
    val bookId: String,
    val title: String,
    val author: String = "",
    val contentType: String,
    val format: String,
    val contentSha256: String,
    val contentBytes: Long,
    val contentRevision: Long,
)

@Serializable
private data class RemoteProgressPayload(
    val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val chapterProgress: Double,
    val bookProgress: Double,
)

@Serializable
private data class RemoteBundleReadyPayload(
    val bookId: String,
    val contentSha256: String,
    val contentBytes: Long,
    val contentRevision: Long,
)

/**
 * Local-first sync coordinator. Local Room writes always complete first; this
 * class only queues/retries network work and silently applies remote changes.
 */
class SyncRepository(
    private val database: AppDatabase,
    private val readingItemDao: ReadingItemDao,
    private val chapterDao: ReadingChapterDao,
    private val tocDao: ReadingTocItemDao,
    private val chapterTranslationDao: ChapterTranslationDao,
    private val chapterPhraseDao: ChapterPhraseDao,
    private val lookupHistoryDao: LookupHistoryDao,
    private val vocabularyDao: VocabularyDao,
    private val syncBookDao: SyncBookDao,
    private val outboxDao: SyncOutboxDao,
    private val settingsRepository: SyncSettingsRepository,
    private val tokenStore: SyncTokenStore,
    private val api: SyncApi,
    private val scheduler: SyncScheduler,
) : SyncMutationWriter {

    private val syncMutex = Mutex()
    /** Serializes rotating refresh-token reads/writes independently of UI work. */
    private val sessionMutex = Mutex()
    private val _runtimeState = MutableStateFlow(SyncRuntimeState())
    val runtimeState: StateFlow<SyncRuntimeState> = _runtimeState.asStateFlow()
    val settings = settingsRepository.settings

    suspend fun setServerUrl(serverUrl: String): SyncActionResult = runCatching {
        settingsRepository.setServerUrl(serverUrl)
        SyncActionResult.Success
    }.getOrElse { SyncActionResult.Failure(it.message ?: "同步地址无效") }

    suspend fun register(serverUrl: String, email: String, password: String): SyncActionResult =
        authenticate(serverUrl) { endpoint, deviceId ->
            api.register(endpoint, AuthRequest(email.trim(), password, deviceId, deviceName()))
        }

    suspend fun login(serverUrl: String, email: String, password: String): SyncActionResult =
        authenticate(serverUrl) { endpoint, deviceId ->
            api.login(endpoint, AuthRequest(email.trim(), password, deviceId, deviceName()))
        }

    suspend fun logout() {
        val configuration = settingsRepository.current()
        tokenStore.read()?.let { session ->
            runCatching {
                if (configuration.serverUrl.isNotBlank()) {
                    api.logout(configuration.serverUrl, session.accessToken, LogoutRequest(session.refreshToken))
                }
            }
        }
        tokenStore.clear()
        database.withTransaction {
            outboxDao.clearAll()
            syncBookDao.clearAll()
        }
        settingsRepository.clearAccount()
        scheduler.cancelAll()
    }

    override suspend fun onBookImported(localReadingItemId: Long) {
        database.withTransaction { ensureBookQueuedLocked(localReadingItemId) }
        scheduler.enqueueSoon()
    }

    override suspend fun onProgressChanged(localReadingItemId: Long) {
        database.withTransaction {
            // A page turn must stay cheap: only a first import needs a full book
            // bundle hash. Existing mappings already describe immutable content.
            val mapping = syncBookDao.getByLocalId(localReadingItemId)
                ?: ensureBookQueuedLocked(localReadingItemId)
                ?: return@withTransaction
            if (mapping.isDeleted) return@withTransaction
            replaceOutboxLocked(
                localId = localReadingItemId,
                bookId = mapping.cloudBookId ?: mapping.clientBookId,
                kind = SyncOutboxKind.PROGRESS_UPSERT,
            )
        }
        scheduler.enqueueSoon()
    }

    override suspend fun onBookDeleted(localReadingItemId: Long) {
        var queued = false
        database.withTransaction {
            val mapping = syncBookDao.getByLocalId(localReadingItemId) ?: return@withTransaction
            outboxDao.deleteForLocal(localReadingItemId)
            val cloudId = mapping.cloudBookId
            if (cloudId == null) {
                syncBookDao.deleteByClientBookId(mapping.clientBookId)
            } else {
                syncBookDao.markDeleted(mapping.clientBookId)
                outboxDao.insert(
                    SyncOutbox(
                        mutationId = UUID.randomUUID().toString(),
                        bookId = cloudId,
                        kind = SyncOutboxKind.BOOK_DELETE,
                        occurredAt = System.currentTimeMillis(),
                    ),
                )
                queued = true
            }
        }
        if (queued) scheduler.enqueueSoon()
    }

    suspend fun syncOnce(waitForCurrentSync: Boolean = true): SyncRunResult {
        if (waitForCurrentSync) return syncMutex.withLock { syncLocked() }
        if (!syncMutex.tryLock()) return SyncRunResult.InProgress
        return try {
            syncLocked()
        } finally {
            syncMutex.unlock()
        }
    }

    /**
     * A foreground request should never wait behind an old WorkManager job. Older
     * app builds queued one after login, and cancelling it lets the UI recover
     * without touching the durable outbox.
     */
    suspend fun syncFromSettings(): SyncRunResult {
        scheduler.cancelOneTime()
        return syncOnce(waitForCurrentSync = false).also { result ->
            // After a fresh foreground sync has completed, restore the regular
            // periodic schedule. Login deliberately cancels legacy work first so
            // no worker can carry an old access token into the new account.
            when (result) {
                SyncRunResult.Success -> scheduler.ensurePeriodic()
                is SyncRunResult.RetryableFailure -> scheduler.enqueueSoon()
                else -> Unit
            }
        }
    }

    private suspend fun syncLocked(retryAfterSessionChange: Boolean = true): SyncRunResult {
        var conflictConfiguration: SyncSettings? = null
        var conflictSession: SyncSession? = null
        return try {
            val configuration = settingsRepository.current()
            if (configuration.serverUrl.isBlank()) return SyncRunResult.NotConfigured
            if (configuration.userId.isNullOrBlank()) return SyncRunResult.NotAuthenticated
            val session = validSession(configuration) ?: return SyncRunResult.NotAuthenticated
            conflictConfiguration = configuration
            conflictSession = session

            _runtimeState.value = SyncRuntimeState(syncing = true)
            val terminalFailures = mutableListOf<String>()
            // First activation uploads pre-existing books (including books imported
            // before the user ever configured a sync account).
            database.withTransaction {
                for (item in readingItemDao.getAll()) {
                    if (syncBookDao.getByLocalId(item.id) == null) {
                        ensureBookQueuedLocked(item.id)
                    }
                }
            }
            pushBookMutations(configuration, session, terminalFailures)
            uploadPendingBundles(configuration, session)
            pushProgressMutations(configuration, session, terminalFailures)
            pushDeleteMutations(configuration, session, terminalFailures)
            pullChanges(configuration, session)
            if (terminalFailures.isNotEmpty()) {
                val message = "部分本地变更未同步：${terminalFailures.first()}"
                _runtimeState.value = SyncRuntimeState(lastMessage = message)
                return SyncRunResult.PermanentFailure(message)
            }
            settingsRepository.markSuccessfulSync()
            _runtimeState.value = SyncRuntimeState(lastMessage = "已同步")
            SyncRunResult.Success
        } catch (error: SyncApiException) {
            val result = when (error.status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> {
                    // A Worker from an older process can finish with an access
                    // token captured before the user just logged in. Never let
                    // that stale 401 erase the newer session it no longer owns.
                    val failedSession = conflictSession
                    if (retryAfterSessionChange && failedSession != null && hasSessionChangedSince(failedSession)) {
                        return syncLocked(retryAfterSessionChange = false)
                    }
                    tokenStore.clear()
                    clearLocalSyncSidecar()
                    settingsRepository.clearAccount()
                    scheduler.cancelAll()
                    SyncRunResult.NotAuthenticated
                }

                HttpStatusCode.BadRequest,
                HttpStatusCode.PayloadTooLarge,
                -> SyncRunResult.PermanentFailure(error.message)

                HttpStatusCode.Conflict -> {
                    // A concurrent device may have updated/deleted the book between
                    // metadata push and bundle upload. Reconcile before retrying.
                    if (error.code in STALE_BUNDLE_CODES) {
                        val configuration = conflictConfiguration
                        val session = conflictSession
                        if (configuration != null && session != null) {
                            runCatching { pullChanges(configuration, session) }
                        }
                        SyncRunResult.RetryableFailure("${error.message}；已重新拉取服务器状态，将自动重试")
                    } else {
                        SyncRunResult.PermanentFailure(error.message)
                    }
                }

                else -> SyncRunResult.RetryableFailure(error.message)
            }
            _runtimeState.value = SyncRuntimeState(lastMessage = error.message)
            result
        } catch (error: SyncDataException) {
            _runtimeState.value = SyncRuntimeState(lastMessage = error.message)
            SyncRunResult.PermanentFailure(error.message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = userVisibleSyncError(error)
            _runtimeState.value = SyncRuntimeState(lastMessage = message)
            SyncRunResult.RetryableFailure(message)
        } finally {
            if (_runtimeState.value.syncing) {
                _runtimeState.value = SyncRuntimeState(lastMessage = "同步已中断")
            }
        }
    }

    private suspend fun authenticate(
        serverUrl: String,
        request: suspend (endpoint: String, deviceId: String) -> AuthResponse,
    ): SyncActionResult = try {
        syncMutex.withLock {
            val previous = settingsRepository.current()
            // Do not persist or send existing session material to a new endpoint
            // until this fresh email/password authentication has actually succeeded.
            val endpoint = SyncSettingsRepository.normalizeServerUrl(serverUrl)
            require(endpoint.isNotBlank()) { "请先填写同步服务器地址" }
            val deviceId = settingsRepository.deviceId()
            val response = request(endpoint, deviceId)
            // Clear all legacy work before replacing the session. A periodic
            // worker may have captured an old access token before this explicit
            // login; periodic scheduling resumes only after the first successful
            // foreground sync with the fresh session.
            scheduler.cancelAll()
            if ((previous.userId != null && previous.userId != response.user.id) ||
                (previous.serverUrl.isNotBlank() && previous.serverUrl != endpoint)
            ) {
                // Never let an old account/server's mapping decide what gets uploaded
                // to a newly authenticated account. Local books themselves stay put.
                clearLocalSyncSidecar()
            }
            // Clearing first makes a storage failure safe: an old session can never
            // be paired with the just-authenticated endpoint. `save()` is committed
            // off the UI thread so a process replacement cannot resurrect its old
            // refresh token.
            sessionMutex.withLock {
                withContext(Dispatchers.IO) {
                    tokenStore.clear()
                    tokenStore.save(SyncSession(response.accessToken, response.accessTokenExpiresAt, response.refreshToken))
                }
            }
            settingsRepository.setServerUrl(endpoint)
            settingsRepository.setAccount(response.user.id, response.user.email)
            // The settings screen performs the initial foreground sync immediately.
            SyncActionResult.Success
        }
    } catch (error: SyncApiException) {
        SyncActionResult.Failure(error.message)
    } catch (error: Exception) {
        SyncActionResult.Failure(userVisibleSyncError(error))
    }

    private suspend fun validSession(configuration: SyncSettings): SyncSession? = sessionMutex.withLock {
        val existing = tokenStore.read() ?: return@withLock null
        if (hasEnoughAccessTokenLifetime(existing)) return@withLock existing
        refreshSessionLocked(configuration, existing, allowStoredReplacement = true)
    }

    /**
     * Refresh tokens rotate: a second caller holding the just-revoked value must
     * never erase a newer session that was already saved by another caller.
     * Call only while [sessionMutex] is held.
     */
    private suspend fun refreshSessionLocked(
        configuration: SyncSettings,
        existing: SyncSession,
        allowStoredReplacement: Boolean,
    ): SyncSession? {
        return try {
            val refreshed = api.refresh(
                configuration.serverUrl,
                RefreshRequest(existing.refreshToken, settingsRepository.deviceId()),
            )
            SyncSession(refreshed.accessToken, refreshed.accessTokenExpiresAt, refreshed.refreshToken).also { session ->
                withContext(Dispatchers.IO) { tokenStore.save(session) }
            }
        } catch (error: SyncApiException) {
            if (error.status == HttpStatusCode.Unauthorized || error.status == HttpStatusCode.Forbidden) {
                val replacement = tokenStore.read()
                if (allowStoredReplacement && replacement != null && replacement.refreshToken != existing.refreshToken) {
                    return if (hasEnoughAccessTokenLifetime(replacement)) {
                        replacement
                    } else {
                        refreshSessionLocked(configuration, replacement, allowStoredReplacement = false)
                    }
                }
                withContext(Dispatchers.IO) { tokenStore.clear() }
                clearLocalSyncSidecar()
                settingsRepository.clearAccount()
                null
            } else {
                throw error
            }
        }
    }

    private fun hasEnoughAccessTokenLifetime(session: SyncSession): Boolean =
        session.accessTokenExpiresAt > System.currentTimeMillis() + ACCESS_TOKEN_REFRESH_SKEW_MILLIS

    private fun userVisibleSyncError(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() } ?: "同步暂时失败"
        return when {
            message.contains("connection closed", ignoreCase = true) ->
                "同步连接被关闭，将自动重试。若开启了 Clash/代理，请确认它没有拦截同步服务器。"
            message.contains("timeout", ignoreCase = true) ->
                "同步网络响应超时，已安排自动重试。"
            else -> message
        }
    }

    /** Returns true when another login/refresh replaced the failed session. */
    private suspend fun hasSessionChangedSince(session: SyncSession): Boolean = sessionMutex.withLock {
        val current = tokenStore.read()
        current != null && (
            current.accessToken != session.accessToken ||
                current.refreshToken != session.refreshToken
            )
    }

    private suspend fun clearLocalSyncSidecar() {
        database.withTransaction {
            outboxDao.clearAll()
            syncBookDao.clearAll()
        }
    }

    /** Must run in a Room transaction. */
    private suspend fun ensureBookQueuedLocked(localId: Long): SyncBook? {
        val item = readingItemDao.getById(localId) ?: return null
        val encoded = BookBundleCodec.encode(item, chapterDao.getAll(localId), tocDao.getAll(localId))
        val existing = syncBookDao.getByLocalId(localId)
        val contentChanged = existing == null ||
            existing.contentSha256 != encoded.sha256 || existing.contentBytes != encoded.raw.size.toLong()
        val mapping = when {
            existing == null -> SyncBook(
                clientBookId = UUID.randomUUID().toString(),
                localReadingItemId = localId,
                contentSha256 = encoded.sha256,
                contentBytes = encoded.raw.size.toLong(),
            )

            contentChanged -> existing.copy(
                localReadingItemId = localId,
                contentSha256 = encoded.sha256,
                contentBytes = encoded.raw.size.toLong(),
                contentRevision = existing.contentRevision + 1,
                bundleUploaded = false,
                isDeleted = false,
            )

            else -> existing.copy(localReadingItemId = localId, isDeleted = false)
        }
        syncBookDao.upsert(mapping)
        if (contentChanged || existing?.cloudBookId == null || existing.isDeleted) {
            replaceOutboxLocked(localId, mapping.cloudBookId ?: mapping.clientBookId, SyncOutboxKind.BOOK_UPSERT)
        }
        return mapping
    }

    /** Must run in a Room transaction. */
    private suspend fun replaceOutboxLocked(localId: Long, bookId: String, kind: String) {
        outboxDao.deleteForLocalAndKind(localId, kind)
        outboxDao.insert(
            SyncOutbox(
                mutationId = UUID.randomUUID().toString(),
                localReadingItemId = localId,
                bookId = bookId,
                kind = kind,
                occurredAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun pushBookMutations(
        configuration: SyncSettings,
        session: SyncSession,
        terminalFailures: MutableList<String>,
    ) {
        while (true) {
            val entries = database.withTransaction {
                outboxDao.nextByKinds(listOf(SyncOutboxKind.BOOK_UPSERT), MAX_PUSH_MUTATIONS)
            }
            if (entries.isEmpty()) return
            val mutations = database.withTransaction {
                buildList {
                    for (entry in entries) {
                        bookMutationLocked(entry)?.let(::add)
                    }
                }
            }
            val presentIds = mutations.map { it.mutationId }.toSet()
            val staleIds = entries.map { it.mutationId }.filterNot(presentIds::contains)
            if (staleIds.isNotEmpty()) database.withTransaction { outboxDao.deleteByMutationIds(staleIds) }
            if (mutations.isEmpty()) continue

            val response = api.push(configuration.serverUrl, session.accessToken, SyncPushRequest(settingsRepository.deviceId(), mutations))
            val terminalFailure = database.withTransaction {
                applyBookMappingsLocked(response.bookIdMappings)
                val terminal = settleOutboxLocked(response, mutations.map { it.mutationId })
                for (rejection in response.rejected) {
                    val localId = entries.firstOrNull { it.mutationId == rejection.mutationId }?.localReadingItemId
                    if (localId != null) {
                        // A book without a cloud ID cannot ever send its queued
                        // position. Quarantine that dependent entry as well.
                        outboxDao.markTerminalForLocal(localId, rejection.message)
                    }
                }
                terminal
            }
            terminalFailure?.let(terminalFailures::add)
        }
    }

    private suspend fun bookMutationLocked(entry: SyncOutbox): SyncMutationRequest? {
        val localId = entry.localReadingItemId ?: return null
        val item = readingItemDao.getById(localId) ?: return null
        val mapping = syncBookDao.getByLocalId(localId) ?: return null
        if (mapping.isDeleted) return null
        val bookId = mapping.cloudBookId ?: mapping.clientBookId
        return SyncMutationRequest(
            mutationId = entry.mutationId,
            kind = SyncOutboxKind.BOOK_UPSERT,
            occurredAt = entry.occurredAt,
            payload = buildJsonObject {
                put("bookId", bookId)
                put("title", item.title)
                put("author", item.author)
                put("contentType", item.contentType.name)
                put("format", item.format.name)
                put("contentSha256", mapping.contentSha256)
                put("contentBytes", mapping.contentBytes)
                put("contentRevision", mapping.contentRevision)
            },
        )
    }

    private suspend fun uploadPendingBundles(configuration: SyncSettings, session: SyncSession) {
        val candidates = database.withTransaction {
            buildList {
                for (mapping in syncBookDao.pendingBundleUploads()) {
                    val localId = mapping.localReadingItemId ?: continue
                    val item = readingItemDao.getById(localId) ?: continue
                    val encoded = BookBundleCodec.encode(item, chapterDao.getAll(localId), tocDao.getAll(localId))
                    if (encoded.sha256 != mapping.contentSha256 || encoded.raw.size.toLong() != mapping.contentBytes) {
                        ensureBookQueuedLocked(localId)
                        continue
                    }
                    add(UploadCandidate(mapping, encoded))
                }
            }
        }
        for (candidate in candidates) {
            val cloudId = candidate.mapping.cloudBookId ?: continue
            api.uploadBundle(
                baseUrl = configuration.serverUrl,
                accessToken = session.accessToken,
                cloudBookId = cloudId,
                raw = candidate.bundle.raw,
                sha256 = candidate.bundle.sha256,
                contentRevision = candidate.mapping.contentRevision,
            )
            database.withTransaction {
                syncBookDao.setBundleUploaded(candidate.mapping.clientBookId, true, System.currentTimeMillis())
            }
        }
    }

    private suspend fun pushProgressMutations(
        configuration: SyncSettings,
        session: SyncSession,
        terminalFailures: MutableList<String>,
    ) {
        while (true) {
            val entries = database.withTransaction {
                outboxDao.nextByKinds(listOf(SyncOutboxKind.PROGRESS_UPSERT), MAX_PUSH_MUTATIONS)
            }
            if (entries.isEmpty()) return
            val mutations = database.withTransaction {
                buildList {
                    for (entry in entries) {
                        progressMutationLocked(entry)?.let(::add)
                    }
                }
            }
            val presentIds = mutations.map { it.mutationId }.toSet()
            val missingIds = entries.map { it.mutationId }.filterNot(presentIds::contains)
            // Missing cloud IDs are not stale: their book.upsert is still waiting.
            val trulyStale = database.withTransaction {
                buildList {
                    for (entry in entries) {
                        if (entry.mutationId !in missingIds) continue
                        val localId = entry.localReadingItemId
                        if (localId == null || readingItemDao.getById(localId) == null) {
                            add(entry.mutationId)
                        }
                    }
                }
            }
            if (trulyStale.isNotEmpty()) database.withTransaction { outboxDao.deleteByMutationIds(trulyStale) }
            if (mutations.isEmpty()) return
            val response = api.push(configuration.serverUrl, session.accessToken, SyncPushRequest(settingsRepository.deviceId(), mutations))
            val terminalFailure = database.withTransaction {
                settleOutboxLocked(response, mutations.map { it.mutationId })
            }
            terminalFailure?.let(terminalFailures::add)
        }
    }

    private suspend fun progressMutationLocked(entry: SyncOutbox): SyncMutationRequest? {
        val localId = entry.localReadingItemId ?: return null
        val item = readingItemDao.getById(localId) ?: return null
        val mapping = syncBookDao.getByLocalId(localId) ?: return null
        if (mapping.isDeleted) return null
        val cloudId = mapping.cloudBookId ?: return null
        val chapter = if (item.format == BookFormat.EPUB) chapterDao.getChapter(localId, item.currentChapterIndex) else null
        val charOffset = chapter?.lastReadPosition ?: item.lastReadPosition
        val chapterProgress = chapter?.progress ?: item.progress
        return SyncMutationRequest(
            mutationId = entry.mutationId,
            kind = SyncOutboxKind.PROGRESS_UPSERT,
            occurredAt = entry.occurredAt,
            payload = buildJsonObject {
                put("bookId", cloudId)
                put("chapterIndex", item.currentChapterIndex)
                put("charOffset", charOffset)
                put("chapterProgress", chapterProgress.toDouble())
                put("bookProgress", item.progress.toDouble())
            },
        )
    }

    private suspend fun pushDeleteMutations(
        configuration: SyncSettings,
        session: SyncSession,
        terminalFailures: MutableList<String>,
    ) {
        while (true) {
            val entries = database.withTransaction {
                outboxDao.nextByKinds(listOf(SyncOutboxKind.BOOK_DELETE), MAX_PUSH_MUTATIONS)
            }
            if (entries.isEmpty()) return
            val mutations = entries.map { entry ->
                SyncMutationRequest(
                    mutationId = entry.mutationId,
                    kind = SyncOutboxKind.BOOK_DELETE,
                    occurredAt = entry.occurredAt,
                    payload = buildJsonObject { put("bookId", entry.bookId) },
                )
            }
            val response = api.push(configuration.serverUrl, session.accessToken, SyncPushRequest(settingsRepository.deviceId(), mutations))
            val terminalFailure = database.withTransaction {
                val terminal = settleOutboxLocked(response, mutations.map { it.mutationId })
                for (mutationId in response.acceptedMutationIds + response.duplicateMutationIds) {
                    val entry = entries.firstOrNull { it.mutationId == mutationId } ?: continue
                    val mapping = syncBookDao.getByCloudBookId(entry.bookId)
                    if (mapping != null) {
                        syncBookDao.deleteByClientBookId(mapping.clientBookId)
                    }
                }
                terminal
            }
            terminalFailure?.let(terminalFailures::add)
        }
    }

    private suspend fun applyBookMappingsLocked(mappings: List<BookIdMapping>) {
        val now = System.currentTimeMillis()
        for (mapping in mappings) {
            val local = syncBookDao.getByClientBookId(mapping.clientBookId) ?: continue
            val alreadyCanonical = syncBookDao.getByCloudBookId(mapping.cloudBookId)
            if (alreadyCanonical != null && alreadyCanonical.clientBookId != local.clientBookId) {
                val duplicateLocalId = local.localReadingItemId
                if (alreadyCanonical.localReadingItemId == null && duplicateLocalId != null) {
                    // Attach the imported local content to the canonical cloud row.
                    // Only the sidecar identity moves; the reading item itself stays.
                    syncBookDao.deleteByClientBookId(local.clientBookId)
                    syncBookDao.upsert(
                        alreadyCanonical.copy(
                            localReadingItemId = duplicateLocalId,
                            contentSha256 = local.contentSha256,
                            contentBytes = local.contentBytes,
                            contentRevision = local.contentRevision,
                            bundleUploaded = alreadyCanonical.bundleUploaded || local.bundleUploaded,
                            isDeleted = local.isDeleted,
                            lastSyncedAt = now,
                        ),
                    )
                } else if (duplicateLocalId != null && duplicateLocalId != alreadyCanonical.localReadingItemId) {
                    // Preserve a manually re-imported duplicate locally, but do not
                    // keep re-uploading it as a second cloud copy of identical bytes.
                    syncBookDao.upsert(local.copy(isDeleted = true, lastSyncedAt = now))
                    outboxDao.markTerminalForLocal(duplicateLocalId, "与已有同步书籍内容相同，保留为仅本机副本")
                }
            } else {
                syncBookDao.setCloudBookId(local.clientBookId, mapping.cloudBookId, now)
            }
        }
    }

    /** Returns a user-readable error after persisting the terminal quarantine. */
    private suspend fun settleOutboxLocked(response: SyncPushResponse, sentIds: List<String>): String? {
        val completed = (response.acceptedMutationIds + response.duplicateMutationIds).filter { it in sentIds }
        if (completed.isNotEmpty()) outboxDao.deleteByMutationIds(completed)
        if (response.rejected.isNotEmpty()) {
            val rejectedIds = response.rejected.map { it.mutationId }.filter { it in sentIds }
            if (rejectedIds.isNotEmpty()) {
                val message = response.rejected.first().message
                outboxDao.markTerminal(rejectedIds, message)
                return message
            }
        }
        return null
    }

    private suspend fun pullChanges(configuration: SyncSettings, session: SyncSession) {
        var cursor = settingsRepository.current().pullCursor
        var hasMore: Boolean
        do {
            // `body()` is part of SyncApi.pull(), so this retries a stalled
            // response-body read as well as a failed connection. It is only used
            // for GET endpoints; mutations and refresh-token rotation are never
            // replayed implicitly.
            val page = retrySyncRead {
                api.pull(configuration.serverUrl, session.accessToken, cursor)
            }
            for (change in page.changes) {
                when (change.kind) {
                    SyncOutboxKind.BOOK_UPSERT -> database.withTransaction { saveRemoteBookMetadataLocked(change) }
                    "book.bundle_ready" -> applyRemoteBundle(configuration, session, change)
                    SyncOutboxKind.BOOK_DELETE -> database.withTransaction { applyRemoteDeleteLocked(change.entityId) }
                    SyncOutboxKind.PROGRESS_UPSERT -> database.withTransaction { applyRemoteProgressLocked(change) }
                }
                cursor = change.cursor
                settingsRepository.setPullCursor(cursor)
            }
            hasMore = page.hasMore
        } while (hasMore)
    }

    /**
     * Retries complete read calls, including body decoding, on a fresh HTTP/1.1
     * connection. The read endpoints do not mutate server state and the local
     * cursor only moves after a change has been applied successfully.
     */
    private suspend fun <T> retrySyncRead(request: suspend () -> T): T = retrySyncRead(
        delayBetweenAttempts = { delay(it) },
        request = request,
    )

    private suspend fun saveRemoteBookMetadataLocked(change: SyncChange) {
        val remote = ApiJson.decodeFromJsonElement<RemoteBookPayload>(change.payload)
        require(remote.bookId == change.entityId) { "书籍同步 ID 不一致" }
        val existing = syncBookDao.getByCloudBookId(remote.bookId)
        val merged = (existing ?: SyncBook(
            clientBookId = remote.bookId,
            cloudBookId = remote.bookId,
            contentSha256 = remote.contentSha256,
            contentBytes = remote.contentBytes,
            contentRevision = remote.contentRevision,
        )).copy(
            cloudBookId = remote.bookId,
            contentSha256 = remote.contentSha256,
            contentBytes = remote.contentBytes,
            contentRevision = remote.contentRevision,
            bundleUploaded = existing?.bundleUploaded == true && existing.contentSha256 == remote.contentSha256,
            remoteRevision = change.revision,
            remoteTitle = remote.title,
            remoteAuthor = remote.author,
            remoteContentType = remote.contentType,
            remoteFormat = remote.format,
            isDeleted = false,
            lastSyncedAt = System.currentTimeMillis(),
        )
        syncBookDao.upsert(merged)
    }

    private suspend fun applyRemoteBundle(configuration: SyncSettings, session: SyncSession, change: SyncChange) {
        val ready = ApiJson.decodeFromJsonElement<RemoteBundleReadyPayload>(change.payload)
        val mapping = database.withTransaction { syncBookDao.getByCloudBookId(ready.bookId) }
            ?: throw SyncDataException("缺少书籍元数据，无法下载同步内容")
        if (mapping.isDeleted) return
        if (mapping.localReadingItemId != null && mapping.contentSha256 == ready.contentSha256 && mapping.bundleUploaded) {
            database.withTransaction {
                val current = syncBookDao.getByCloudBookId(ready.bookId) ?: mapping
                val updated = current.copy(
                    remoteRevision = maxOf(current.remoteRevision, change.revision),
                    lastSyncedAt = System.currentTimeMillis(),
                )
                syncBookDao.upsert(updated)
                applyPendingProgressLocked(updated)
            }
            return
        }
        val raw = retrySyncRead {
            api.downloadBundle(configuration.serverUrl, session.accessToken, ready.bookId)
        }
        if (raw.size.toLong() != ready.contentBytes || BookBundleCodec.sha256(raw) != ready.contentSha256) {
            throw SyncDataException("下载的书籍内容校验失败")
        }
        val bundle = BookBundleCodec.decode(raw)
        database.withTransaction {
            val current = syncBookDao.getByCloudBookId(ready.bookId) ?: mapping
            val title = current.remoteTitle ?: throw SyncDataException("缺少书名元数据")
            val contentType = runCatching { ContentType.valueOf(current.remoteContentType ?: "") }
                .getOrElse { throw SyncDataException("书籍内容类型无效") }
            val format = runCatching { BookFormat.valueOf(current.remoteFormat ?: bundle.format) }
                .getOrElse { throw SyncDataException("书籍格式无效") }
            val now = System.currentTimeMillis()
            val localId = current.localReadingItemId
            val storedId = if (localId == null) {
                readingItemDao.insert(
                    ReadingItem(
                        title = title,
                        content = bundle.content,
                        author = current.remoteAuthor.orEmpty(),
                        contentType = contentType,
                        format = format,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                val old = readingItemDao.getById(localId) ?: throw SyncDataException("本地书籍不存在")
                readingItemDao.update(
                    old.copy(
                        title = title,
                        content = bundle.content,
                        author = current.remoteAuthor.orEmpty(),
                        contentType = contentType,
                        format = format,
                        progress = 0f,
                        currentChapterIndex = 0,
                        lastReadPosition = 0,
                        updatedAt = now,
                    ),
                )
                chapterDao.deleteForBook(localId)
                tocDao.deleteForBook(localId)
                localId
            }
            if (bundle.chapters.isNotEmpty()) {
                chapterDao.insertAll(bundle.chapters.map {
                    ReadingChapter(
                        readingItemId = storedId,
                        chapterIndex = it.chapterIndex,
                        title = it.title,
                        content = it.content,
                        createdAt = now,
                        updatedAt = now,
                    )
                })
            }
            if (bundle.toc.isNotEmpty()) {
                tocDao.insertAll(bundle.toc.map {
                    ReadingTocItem(
                        readingItemId = storedId,
                        chapterIndex = it.chapterIndex,
                        label = it.label,
                        href = it.href,
                        level = it.level,
                        orderIndex = it.orderIndex,
                        anchorParagraph = it.anchorParagraph,
                        createdAt = now,
                    )
                })
            }
            val updated = current.copy(
                localReadingItemId = storedId,
                contentSha256 = ready.contentSha256,
                contentBytes = ready.contentBytes,
                contentRevision = ready.contentRevision,
                bundleUploaded = true,
                remoteRevision = maxOf(current.remoteRevision, change.revision),
                lastSyncedAt = now,
            )
            syncBookDao.upsert(updated)
            applyPendingProgressLocked(updated)
        }
    }

    private suspend fun applyRemoteDeleteLocked(cloudBookId: String) {
        val mapping = syncBookDao.getByCloudBookId(cloudBookId) ?: return
        val localId = mapping.localReadingItemId
        if (localId != null) {
            val item = readingItemDao.getById(localId)
            if (item != null) {
                clearBookRelatedData(localId)
                readingItemDao.delete(item)
            }
        }
        syncBookDao.markDeleted(mapping.clientBookId)
    }

    /** Mirrors local deletion so a remote tombstone does not leave book-specific AI caches behind. */
    private suspend fun clearBookRelatedData(bookId: Long) {
        chapterTranslationDao.deleteForBook(bookId)
        chapterPhraseDao.deleteForBook(bookId)
        lookupHistoryDao.clearBookReference(bookId)
        vocabularyDao.clearBookReference(bookId)
    }

    private suspend fun applyRemoteProgressLocked(change: SyncChange) {
        val progress = ApiJson.decodeFromJsonElement<RemoteProgressPayload>(change.payload)
        val mapping = syncBookDao.getByCloudBookId(progress.bookId) ?: return
        if (mapping.isDeleted) return
        if (!applyRemoteProgressValuesLocked(mapping, progress, change.revision)) {
            syncBookDao.setPendingProgress(
                clientBookId = mapping.clientBookId,
                payload = ApiJson.encodeToString(progress),
                occurredAt = change.occurredAt,
                revision = change.revision,
                syncedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Applies a remote location only when the matching local book/EPUB chapter exists. */
    private suspend fun applyRemoteProgressValuesLocked(
        mapping: SyncBook,
        progress: RemoteProgressPayload,
        remoteRevision: Long,
    ): Boolean {
        val localId = mapping.localReadingItemId ?: return false
        val item = readingItemDao.getById(localId) ?: return false
        val now = System.currentTimeMillis()
        if (item.format == BookFormat.EPUB) {
            if (chapterDao.getChapter(localId, progress.chapterIndex) == null) return false
            chapterDao.updateProgress(
                localId,
                progress.chapterIndex,
                progress.charOffset.coerceAtLeast(0),
                progress.chapterProgress.toFloat().coerceIn(0f, 1f),
                now,
            )
            readingItemDao.updateChapterState(
                localId,
                progress.chapterIndex.coerceAtLeast(0),
                progress.bookProgress.toFloat().coerceIn(0f, 1f),
                now,
            )
        } else {
            readingItemDao.updateProgress(
                localId,
                progress.charOffset.coerceAtLeast(0),
                progress.bookProgress.toFloat().coerceIn(0f, 1f),
                now,
            )
        }
        syncBookDao.upsert(
            mapping.copy(
                remoteRevision = maxOf(mapping.remoteRevision, remoteRevision),
                pendingProgressJson = null,
                pendingProgressOccurredAt = null,
                lastSyncedAt = now,
            ),
        )
        return true
    }

    private suspend fun applyPendingProgressLocked(mapping: SyncBook) {
        val encoded = mapping.pendingProgressJson ?: return
        val progress = runCatching { ApiJson.decodeFromString<RemoteProgressPayload>(encoded) }.getOrNull()
        if (progress == null) {
            syncBookDao.upsert(mapping.copy(pendingProgressJson = null, pendingProgressOccurredAt = null))
            return
        }
        applyRemoteProgressValuesLocked(mapping, progress, mapping.remoteRevision)
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100)

    private data class UploadCandidate(val mapping: SyncBook, val bundle: EncodedBookBundle)

    private class SyncDataException(override val message: String) : Exception(message)

    private companion object {
        const val MAX_PUSH_MUTATIONS = 100
        // A first full-library upload can take a while on mobile data. Refresh
        // well before the 15-minute access JWT can expire in the middle of it.
        const val ACCESS_TOKEN_REFRESH_SKEW_MILLIS = 5 * 60 * 1_000L
        val STALE_BUNDLE_CODES = setOf("bundle_stale", "book_deleted")
    }
}

/**
 * Retries only read-side transport failures. The request lambda must not have
 * side effects: authentication, mutations, and bundle uploads deliberately do
 * not use this helper.
 */
internal suspend fun <T> retrySyncRead(
    maxAttempts: Int = SYNC_READ_MAX_ATTEMPTS,
    delayBetweenAttempts: suspend (Long) -> Unit = { delay(it) },
    request: suspend () -> T,
): T {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    var attempt = 1
    var lastFailure: IOException? = null
    while (attempt <= maxAttempts) {
        try {
            return request()
        } catch (error: IOException) {
            lastFailure = error
            if (attempt == maxAttempts) break
            delayBetweenAttempts(attempt * SYNC_READ_RETRY_DELAY_MILLIS)
            attempt += 1
        }
    }
    throw checkNotNull(lastFailure)
}

internal const val SYNC_READ_MAX_ATTEMPTS = 3
private const val SYNC_READ_RETRY_DELAY_MILLIS = 500L
