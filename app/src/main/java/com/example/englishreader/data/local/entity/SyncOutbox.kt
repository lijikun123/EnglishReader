package com.example.englishreader.data.local.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

/** Kinds are strings to keep migrations/querying deliberately simple. */
object SyncOutboxKind {
    const val BOOK_UPSERT = "book.upsert"
    const val BOOK_DELETE = "book.delete"
    const val PROGRESS_UPSERT = "progress.upsert"
}

/**
 * A durable, idempotent mutation queue. It deliberately has no foreign key to
 * [ReadingItem]: a delete mutation must remain sendable after the book is gone
 * locally.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["localReadingItemId"], name = "index_sync_outbox_localReadingItemId"),
        Index(value = ["localReadingItemId", "kind"], unique = true, name = "index_sync_outbox_localReadingItemId_kind"),
        Index(value = ["createdAt"], name = "index_sync_outbox_createdAt"),
    ],
)
data class SyncOutbox(
    @PrimaryKey val mutationId: String,
    val localReadingItemId: Long? = null,
    /** Client UUID for a first upload, then canonical cloud UUID. */
    val bookId: String,
    val kind: String,
    val occurredAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String? = null,
    /** A malformed/unsupported mutation is retained for diagnosis but never blocks later work. */
    @ColumnInfo(defaultValue = "0") val terminal: Boolean = false,
)
