package com.jikunli.englishreader.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ApiException(
    val status: HttpStatusCode,
    val errorCode: String,
    override val message: String,
) : RuntimeException(message)

data class UserRecord(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val disabledAt: Long?,
)

data class RefreshTokenRecord(
    val tokenHash: String,
    val userId: UUID,
    val deviceId: UUID,
    val expiresAt: Long,
    val revokedAt: Long?,
)

data class BundleData(
    val bytes: ByteArray,
    val sha256: String,
    val rawBytes: Long,
    val updatedAt: Long,
)

data class ChangePage(
    val changes: List<SyncChange>,
    val hasMore: Boolean,
)

sealed interface SyncCommand {
    val kind: String
    val entityId: UUID
    val occurredAt: Long
    val payload: JsonObject

    data class BookUpsert(
        val data: BookUpsertPayload,
        override val occurredAt: Long,
        override val payload: JsonObject,
    ) : SyncCommand {
        override val kind = "book.upsert"
        override val entityId = UUID.fromString(data.bookId)
    }

    data class BookDelete(
        val data: BookDeletePayload,
        override val occurredAt: Long,
        override val payload: JsonObject,
    ) : SyncCommand {
        override val kind = "book.delete"
        override val entityId = UUID.fromString(data.bookId)
    }

    data class ProgressUpsert(
        val data: ProgressUpsertPayload,
        override val occurredAt: Long,
        override val payload: JsonObject,
    ) : SyncCommand {
        override val kind = "progress.upsert"
        override val entityId = UUID.fromString(data.bookId)
    }
}

enum class MutationApplyResult { APPLIED, DUPLICATE }

data class MutationApplyOutcome(
    val result: MutationApplyResult,
    /** Present for book.upsert, including an idempotent retry. */
    val canonicalBookId: UUID? = null,
)

private data class BookRecord(
    val id: UUID,
    val userId: UUID,
    val revision: Long,
    val occurredAt: Long,
    val sourceDeviceId: UUID,
    val contentSha256: String,
    val contentBytes: Long,
    val contentRevision: Long,
    val bundleReady: Boolean,
    val deletedAt: Long?,
)

private data class PositionRecord(
    val revision: Long,
    val occurredAt: Long,
    val sourceDeviceId: UUID,
)

class KreaderDatabase private constructor(
    private val dataSource: HikariDataSource,
) : AutoCloseable {

    companion object {
        fun connect(config: AppConfig): KreaderDatabase {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                username = config.databaseUser
                password = config.databasePassword
                driverClassName = "org.postgresql.Driver"
                // Keep the database well below the connection/memory ceiling of
                // the 2 GB personal VPS. The API does short transactions only.
                maximumPoolSize = 3
                minimumIdle = 1
                connectionTimeout = 10_000
                validationTimeout = 5_000
                leakDetectionThreshold = 0
                addDataSourceProperty("ApplicationName", "kreader-sync")
            }
            return KreaderDatabase(HikariDataSource(hikari))
        }
    }

    fun migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun ping() {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT 1").use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next())
                }
            }
        }
    }

    fun createUser(email: String, passwordHash: String, now: Long): UserRecord = transaction { connection ->
        val user = UserRecord(UUID.randomUUID(), email, passwordHash, null)
        try {
            connection.prepareStatement(
                "INSERT INTO users(id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, user.id)
                statement.setString(2, user.email)
                statement.setString(3, user.passwordHash)
                statement.setLong(4, now)
                statement.executeUpdate()
            }
        } catch (error: SQLException) {
            if (error.sqlState == "23505") {
                throw ApiException(HttpStatusCode.Conflict, "email_exists", "An account with this email already exists")
            }
            throw error
        }
        user
    }

    fun findUserByEmail(email: String): UserRecord? = withConnection { connection ->
        connection.prepareStatement(
            "SELECT id, email, password_hash, disabled_at FROM users WHERE email = ?",
        ).use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { result -> result.singleOrNull(::toUserRecord) }
        }
    }

    fun findUserById(userId: UUID): UserRecord? = withConnection { connection ->
        connection.prepareStatement(
            "SELECT id, email, password_hash, disabled_at FROM users WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result -> result.singleOrNull(::toUserRecord) }
        }
    }

    fun touchDevice(userId: UUID, deviceId: UUID, name: String, now: Long) = transaction { connection ->
        connection.prepareStatement("SELECT user_id FROM devices WHERE id = ? FOR UPDATE").use { statement ->
            statement.setObject(1, deviceId)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    val existingUser = result.getObject("user_id", UUID::class.java)
                    if (existingUser != userId) {
                        throw ApiException(HttpStatusCode.Forbidden, "device_owned", "This device belongs to another account")
                    }
                    connection.prepareStatement(
                        "UPDATE devices SET name = ?, last_seen_at = ? WHERE id = ?",
                    ).use { update ->
                        update.setString(1, name)
                        update.setLong(2, now)
                        update.setObject(3, deviceId)
                        update.executeUpdate()
                    }
                } else {
                    connection.prepareStatement(
                        "INSERT INTO devices(id, user_id, name, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
                    ).use { insert ->
                        insert.setObject(1, deviceId)
                        insert.setObject(2, userId)
                        insert.setString(3, name)
                        insert.setLong(4, now)
                        insert.setLong(5, now)
                        insert.executeUpdate()
                    }
                }
            }
        }
    }

    fun assertDeviceOwnedBy(userId: UUID, deviceId: UUID) = withConnection { connection ->
        connection.prepareStatement("SELECT 1 FROM devices WHERE id = ? AND user_id = ?").use { statement ->
            statement.setObject(1, deviceId)
            statement.setObject(2, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    throw ApiException(HttpStatusCode.Forbidden, "device_unknown", "The device is not registered for this account")
                }
            }
        }
    }

    fun createRefreshToken(userId: UUID, deviceId: UUID, tokenHash: String, now: Long, expiresAt: Long) = transaction { connection ->
        connection.prepareStatement(
            "INSERT INTO refresh_tokens(token_hash, user_id, device_id, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.setObject(2, userId)
            statement.setObject(3, deviceId)
            statement.setLong(4, expiresAt)
            statement.setLong(5, now)
            statement.executeUpdate()
        }
    }

    /** Atomically consumes a refresh token so it cannot be replayed. */
    fun consumeRefreshToken(tokenHash: String, deviceId: UUID, now: Long): RefreshTokenRecord? = transaction { connection ->
        val record = connection.prepareStatement(
            "SELECT token_hash, user_id, device_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.executeQuery().use { result -> result.singleOrNull(::toRefreshTokenRecord) }
        } ?: return@transaction null

        if (record.deviceId != deviceId || record.revokedAt != null || record.expiresAt <= now) {
            return@transaction null
        }
        connection.prepareStatement("UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ?").use { statement ->
            statement.setLong(1, now)
            statement.setString(2, tokenHash)
            statement.executeUpdate()
        }
        record
    }

    fun revokeRefreshToken(userId: UUID, tokenHash: String, now: Long) = transaction { connection ->
        connection.prepareStatement(
            "UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND user_id = ? AND revoked_at IS NULL",
        ).use { statement ->
            statement.setLong(1, now)
            statement.setString(2, tokenHash)
            statement.setObject(3, userId)
            statement.executeUpdate()
        }
    }

    fun applyMutation(
        userId: UUID,
        deviceId: UUID,
        mutationId: UUID,
        command: SyncCommand,
        now: Long,
    ): MutationApplyOutcome =
        transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO sync_mutations(user_id, mutation_id, processed_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, mutationId)
                statement.setLong(3, now)
                if (statement.executeUpdate() == 0) {
                    return@transaction MutationApplyOutcome(
                        result = MutationApplyResult.DUPLICATE,
                        canonicalBookId = findMutationCanonicalBookId(connection, userId, mutationId),
                    )
                }
            }

            val canonicalBookId = when (command) {
                is SyncCommand.BookUpsert -> applyBookUpsert(connection, userId, deviceId, command, now)
                is SyncCommand.BookDelete -> {
                    applyBookDelete(connection, userId, deviceId, command, now)
                    null
                }

                is SyncCommand.ProgressUpsert -> {
                    applyProgressUpsert(connection, userId, deviceId, command, now)
                    null
                }
            }
            connection.prepareStatement(
                "UPDATE sync_mutations SET canonical_book_id = ? WHERE user_id = ? AND mutation_id = ?",
            ).use { statement ->
                statement.setObject(1, canonicalBookId)
                statement.setObject(2, userId)
                statement.setObject(3, mutationId)
                statement.executeUpdate()
            }
            MutationApplyOutcome(MutationApplyResult.APPLIED, canonicalBookId)
        }

    fun changesAfter(userId: UUID, cursor: Long, limit: Int): ChangePage = withConnection { connection ->
        val changes = connection.prepareStatement(
            """
            SELECT cursor, kind, entity_id, revision, payload::text AS payload, occurred_at, server_updated_at
            FROM sync_changes
            WHERE user_id = ? AND cursor > ?
            ORDER BY cursor ASC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setLong(2, cursor)
            statement.setInt(3, limit + 1)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(toSyncChange(result))
                }
            }
        }
        return@withConnection ChangePage(changes.take(limit), changes.size > limit)
    }

    fun storeBundle(
        userId: UUID,
        bookId: UUID,
        raw: ByteArray,
        expectedSha256: String,
        expectedContentRevision: Long,
        now: Long,
    ): BundleReceipt {
        val gzip = gzip(raw)
        val sha256 = sha256Hex(raw)
        if (!sha256.equals(expectedSha256, ignoreCase = true)) {
            throw ApiException(HttpStatusCode.BadRequest, "bundle_hash_mismatch", "Book bundle does not match the declared SHA-256")
        }
        transaction { connection ->
            val book = findBookForUpdate(connection, bookId)
                ?: throw ApiException(HttpStatusCode.NotFound, "book_not_found", "Book not found")
            assertBookOwner(book, userId)
            if (book.deletedAt != null) {
                throw ApiException(HttpStatusCode.Conflict, "book_deleted", "Deleted books cannot receive bundles")
            }
            if (!book.contentSha256.equals(expectedSha256, ignoreCase = true) || book.contentRevision != expectedContentRevision) {
                throw ApiException(HttpStatusCode.Conflict, "bundle_stale", "Book metadata changed; upload the newest bundle instead")
            }
            if (book.contentBytes != raw.size.toLong()) {
                throw ApiException(HttpStatusCode.BadRequest, "bundle_size_mismatch", "Book bundle does not match the declared size")
            }
            connection.prepareStatement(
                """
                INSERT INTO book_bundles(book_id, user_id, sha256, raw_bytes, gzip_bytes, content_gzip, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (book_id) DO UPDATE SET
                    sha256 = EXCLUDED.sha256,
                    raw_bytes = EXCLUDED.raw_bytes,
                    gzip_bytes = EXCLUDED.gzip_bytes,
                    content_gzip = EXCLUDED.content_gzip,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, bookId)
                statement.setObject(2, userId)
                statement.setString(3, sha256)
                statement.setLong(4, raw.size.toLong())
                statement.setLong(5, gzip.size.toLong())
                statement.setBytes(6, gzip)
                statement.setLong(7, now)
                statement.executeUpdate()
            }
            if (!book.bundleReady) {
                val revision = book.revision + 1
                connection.prepareStatement(
                    "UPDATE books SET bundle_ready = TRUE, revision = ?, updated_at = ? WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, revision)
                    statement.setLong(2, now)
                    statement.setObject(3, bookId)
                    statement.executeUpdate()
                }
                appendChange(
                    connection = connection,
                    userId = userId,
                    kind = "book.bundle_ready",
                    entityId = bookId,
                    revision = revision,
                    payload = buildJsonObject {
                        put("bookId", bookId.toString())
                        put("contentSha256", sha256)
                        put("contentBytes", raw.size.toLong())
                        put("contentRevision", expectedContentRevision)
                    },
                    occurredAt = now,
                    serverUpdatedAt = now,
                )
            }
        }
        return BundleReceipt(bookId.toString(), sha256, raw.size.toLong(), now)
    }

    fun loadBundle(userId: UUID, bookId: UUID): BundleData = withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT bundle.content_gzip, bundle.sha256, bundle.raw_bytes, bundle.updated_at
            FROM book_bundles AS bundle
            INNER JOIN books AS book ON book.id = bundle.book_id
            WHERE bundle.book_id = ? AND bundle.user_id = ? AND book.user_id = ? AND book.deleted_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, bookId)
            statement.setObject(2, userId)
            statement.setObject(3, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    throw ApiException(HttpStatusCode.NotFound, "bundle_not_found", "Book bundle not found")
                }
                BundleData(
                    bytes = gunzip(result.getBytes("content_gzip")),
                    sha256 = result.getString("sha256"),
                    rawBytes = result.getLong("raw_bytes"),
                    updatedAt = result.getLong("updated_at"),
                )
            }
        }
    }

    private fun applyBookUpsert(
        connection: Connection,
        userId: UUID,
        deviceId: UUID,
        command: SyncCommand.BookUpsert,
        now: Long,
    ): UUID {
        lockContentHash(connection, userId, command.data.contentSha256)
        val existing = findBookForUpdate(connection, command.entityId)
        if (existing == null) {
            val canonical = findActiveBookByContentHashForUpdate(connection, userId, command.data.contentSha256)
            if (canonical != null) return canonical.id
            connection.prepareStatement(
                """
                INSERT INTO books(
                    id, user_id, title, author, content_type, format, content_sha256, content_bytes,
                    content_revision, revision, occurred_at, source_device_id, bundle_ready, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, FALSE, ?, ?, NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, command.entityId)
                statement.setObject(2, userId)
                statement.setString(3, command.data.title)
                statement.setString(4, command.data.author)
                statement.setString(5, command.data.contentType)
                statement.setString(6, command.data.format)
                statement.setString(7, command.data.contentSha256)
                statement.setLong(8, command.data.contentBytes)
                statement.setLong(9, command.data.contentRevision)
                statement.setLong(10, command.occurredAt)
                statement.setObject(11, deviceId)
                statement.setLong(12, now)
                statement.setLong(13, now)
                statement.executeUpdate()
            }
            appendChange(connection, userId, command.kind, command.entityId, 1, command.payload, command.occurredAt, now)
            return command.entityId
        }
        assertBookOwner(existing, userId)
        val canonical = findActiveBookByContentHashForUpdate(connection, userId, command.data.contentSha256, command.entityId)
        if (canonical != null) return canonical.id
        if (!winsOver(command.occurredAt, deviceId, existing.occurredAt, existing.sourceDeviceId)) return existing.id
        val revision = existing.revision + 1
        val contentChanged = existing.contentSha256 != command.data.contentSha256 ||
            existing.contentRevision != command.data.contentRevision || existing.contentBytes != command.data.contentBytes
        connection.prepareStatement(
            """
            UPDATE books SET
                title = ?, author = ?, content_type = ?, format = ?, content_sha256 = ?, content_bytes = ?,
                content_revision = ?, revision = ?, occurred_at = ?, source_device_id = ?,
                bundle_ready = ?, updated_at = ?, deleted_at = NULL
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, command.data.title)
            statement.setString(2, command.data.author)
            statement.setString(3, command.data.contentType)
            statement.setString(4, command.data.format)
            statement.setString(5, command.data.contentSha256)
            statement.setLong(6, command.data.contentBytes)
            statement.setLong(7, command.data.contentRevision)
            statement.setLong(8, revision)
            statement.setLong(9, command.occurredAt)
            statement.setObject(10, deviceId)
            statement.setBoolean(11, if (contentChanged) false else existing.bundleReady)
            statement.setLong(12, now)
            statement.setObject(13, command.entityId)
            statement.executeUpdate()
        }
        appendChange(connection, userId, command.kind, command.entityId, revision, command.payload, command.occurredAt, now)
        return command.entityId
    }

    private fun applyBookDelete(
        connection: Connection,
        userId: UUID,
        deviceId: UUID,
        command: SyncCommand.BookDelete,
        now: Long,
    ) {
        val existing = findBookForUpdate(connection, command.entityId)
            ?: throw ApiException(HttpStatusCode.NotFound, "book_not_found", "Book not found")
        assertBookOwner(existing, userId)
        if (!winsOver(command.occurredAt, deviceId, existing.occurredAt, existing.sourceDeviceId)) return
        val revision = existing.revision + 1
        connection.prepareStatement(
            "UPDATE books SET revision = ?, occurred_at = ?, source_device_id = ?, bundle_ready = FALSE, updated_at = ?, deleted_at = ? WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, revision)
            statement.setLong(2, command.occurredAt)
            statement.setObject(3, deviceId)
            statement.setLong(4, now)
            statement.setLong(5, now)
            statement.setObject(6, command.entityId)
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM book_bundles WHERE book_id = ?").use { statement ->
            statement.setObject(1, command.entityId)
            statement.executeUpdate()
        }
        appendChange(connection, userId, command.kind, command.entityId, revision, command.payload, command.occurredAt, now)
    }

    private fun applyProgressUpsert(
        connection: Connection,
        userId: UUID,
        deviceId: UUID,
        command: SyncCommand.ProgressUpsert,
        now: Long,
    ) {
        val book = findBookForUpdate(connection, command.entityId)
            ?: throw ApiException(HttpStatusCode.NotFound, "book_not_found", "Book not found")
        assertBookOwner(book, userId)
        if (book.deletedAt != null) {
            throw ApiException(HttpStatusCode.Conflict, "book_deleted", "Deleted books cannot receive progress")
        }
        val existing = connection.prepareStatement(
            "SELECT revision, occurred_at, source_device_id FROM reading_positions WHERE user_id = ? AND book_id = ? FOR UPDATE",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, command.entityId)
            statement.executeQuery().use { result -> result.singleOrNull(::toPositionRecord) }
        }
        if (existing != null && !winsOver(command.occurredAt, deviceId, existing.occurredAt, existing.sourceDeviceId)) return
        val revision = (existing?.revision ?: 0) + 1
        connection.prepareStatement(
            """
            INSERT INTO reading_positions(
                user_id, book_id, chapter_index, char_offset, chapter_progress, book_progress,
                revision, occurred_at, source_device_id, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, book_id) DO UPDATE SET
                chapter_index = EXCLUDED.chapter_index,
                char_offset = EXCLUDED.char_offset,
                chapter_progress = EXCLUDED.chapter_progress,
                book_progress = EXCLUDED.book_progress,
                revision = EXCLUDED.revision,
                occurred_at = EXCLUDED.occurred_at,
                source_device_id = EXCLUDED.source_device_id,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, command.entityId)
            statement.setInt(3, command.data.chapterIndex)
            statement.setInt(4, command.data.charOffset)
            statement.setDouble(5, command.data.chapterProgress)
            statement.setDouble(6, command.data.bookProgress)
            statement.setLong(7, revision)
            statement.setLong(8, command.occurredAt)
            statement.setObject(9, deviceId)
            statement.setLong(10, now)
            statement.executeUpdate()
        }
        appendChange(connection, userId, command.kind, command.entityId, revision, command.payload, command.occurredAt, now)
    }

    private fun appendChange(
        connection: Connection,
        userId: UUID,
        kind: String,
        entityId: UUID,
        revision: Long,
        payload: JsonObject,
        occurredAt: Long,
        serverUpdatedAt: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO sync_changes(user_id, kind, entity_id, revision, payload, occurred_at, server_updated_at)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, kind)
            statement.setObject(3, entityId)
            statement.setLong(4, revision)
            statement.setString(5, apiJson.encodeToString(JsonObject.serializer(), payload))
            statement.setLong(6, occurredAt)
            statement.setLong(7, serverUpdatedAt)
            statement.executeUpdate()
        }
    }

    private fun findMutationCanonicalBookId(connection: Connection, userId: UUID, mutationId: UUID): UUID? =
        connection.prepareStatement(
            "SELECT canonical_book_id FROM sync_mutations WHERE user_id = ? AND mutation_id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, mutationId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                result.getObject("canonical_book_id", UUID::class.java)
            }
        }

    private fun lockContentHash(connection: Connection, userId: UUID, contentSha256: String) {
        // Serialises only competing imports of the same account+book content.
        // A 32-bit hash collision can merely serialize unrelated imports; it
        // cannot grant access across accounts or corrupt data.
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
            statement.setString(1, "$userId:$contentSha256")
            statement.execute()
        }
    }

    private fun findActiveBookByContentHashForUpdate(
        connection: Connection,
        userId: UUID,
        contentSha256: String,
        excludingBookId: UUID? = null,
    ): BookRecord? = connection.prepareStatement(
        """
        SELECT id, user_id, revision, occurred_at, source_device_id, content_sha256, content_bytes,
               content_revision, bundle_ready, deleted_at
        FROM books
        WHERE user_id = ? AND content_sha256 = ? AND deleted_at IS NULL
          AND (?::uuid IS NULL OR id <> ?)
        FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setString(2, contentSha256)
        statement.setObject(3, excludingBookId)
        statement.setObject(4, excludingBookId)
        statement.executeQuery().use { result -> result.singleOrNull(::toBookRecord) }
    }

    private fun findBookForUpdate(connection: Connection, bookId: UUID): BookRecord? =
        connection.prepareStatement(
            """
            SELECT id, user_id, revision, occurred_at, source_device_id, content_sha256, content_bytes,
                   content_revision, bundle_ready, deleted_at
            FROM books WHERE id = ? FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, bookId)
            statement.executeQuery().use { result -> result.singleOrNull(::toBookRecord) }
        }

    private fun assertBookOwner(book: BookRecord, userId: UUID) {
        if (book.userId != userId) {
            throw ApiException(HttpStatusCode.NotFound, "book_not_found", "Book not found")
        }
    }

    private fun winsOver(
        candidateOccurredAt: Long,
        candidateDeviceId: UUID,
        existingOccurredAt: Long,
        existingDeviceId: UUID,
    ): Boolean = candidateOccurredAt > existingOccurredAt ||
        (candidateOccurredAt == existingOccurredAt && candidateDeviceId.toString() > existingDeviceId.toString())

    private fun toUserRecord(result: ResultSet): UserRecord = UserRecord(
        id = result.getObject("id", UUID::class.java),
        email = result.getString("email"),
        passwordHash = result.getString("password_hash"),
        disabledAt = result.getLongOrNull("disabled_at"),
    )

    private fun toRefreshTokenRecord(result: ResultSet): RefreshTokenRecord = RefreshTokenRecord(
        tokenHash = result.getString("token_hash"),
        userId = result.getObject("user_id", UUID::class.java),
        deviceId = result.getObject("device_id", UUID::class.java),
        expiresAt = result.getLong("expires_at"),
        revokedAt = result.getLongOrNull("revoked_at"),
    )

    private fun toBookRecord(result: ResultSet): BookRecord = BookRecord(
        id = result.getObject("id", UUID::class.java),
        userId = result.getObject("user_id", UUID::class.java),
        revision = result.getLong("revision"),
        occurredAt = result.getLong("occurred_at"),
        sourceDeviceId = result.getObject("source_device_id", UUID::class.java),
        contentSha256 = result.getString("content_sha256"),
        contentBytes = result.getLong("content_bytes"),
        contentRevision = result.getLong("content_revision"),
        bundleReady = result.getBoolean("bundle_ready"),
        deletedAt = result.getLongOrNull("deleted_at"),
    )

    private fun toPositionRecord(result: ResultSet): PositionRecord = PositionRecord(
        revision = result.getLong("revision"),
        occurredAt = result.getLong("occurred_at"),
        sourceDeviceId = result.getObject("source_device_id", UUID::class.java),
    )

    private fun toSyncChange(result: ResultSet): SyncChange = SyncChange(
        cursor = result.getLong("cursor"),
        kind = result.getString("kind"),
        entityId = result.getObject("entity_id", UUID::class.java).toString(),
        revision = result.getLong("revision"),
        payload = apiJson.parseToJsonElement(result.getString("payload")).jsonObject,
        occurredAt = result.getLong("occurred_at"),
        serverUpdatedAt = result.getLong("server_updated_at"),
    )

    private fun ResultSet.getLongOrNull(column: String): Long? =
        getLong(column).let { if (wasNull()) null else it }

    private fun <T> ResultSet.singleOrNull(mapper: (ResultSet) -> T): T? = if (next()) mapper(this) else null

    private fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() {
        dataSource.close()
    }
}

private fun gzip(raw: ByteArray): ByteArray = ByteArrayOutputStream().use { bytes ->
    GZIPOutputStream(bytes).use { it.write(raw) }
    bytes.toByteArray()
}

private fun gunzip(gzip: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(gzip)).use { input ->
    input.readBytes()
}
