package com.example.englishreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cloud identity for one book. The existing Long [localReadingItemId] stays
 * untouched when present, so all reader/vocabulary foreign keys remain valid.
 * It is nullable to preserve a pending delete or a remote book that has not
 * finished downloading yet.
 */
@Entity(
    tableName = "sync_books",
    foreignKeys = [
        ForeignKey(
            entity = ReadingItem::class,
            parentColumns = ["id"],
            childColumns = ["localReadingItemId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["cloudBookId"], unique = true, name = "index_sync_books_cloudBookId"),
        Index(value = ["localReadingItemId"], unique = true, name = "index_sync_books_localReadingItemId"),
    ],
)
data class SyncBook(
    /** UUID made on this device before its first cloud upload. */
    @PrimaryKey val clientBookId: String,
    val localReadingItemId: Long? = null,
    /** Canonical cloud UUID; filled after the server accepts/deduplicates it. */
    val cloudBookId: String? = null,
    /** SHA-256 of the deterministic uncompressed BookBundleV1 JSON. */
    val contentSha256: String,
    val contentBytes: Long,
    val contentRevision: Long = 1,
    val bundleUploaded: Boolean = false,
    val remoteRevision: Long = 0,
    /** Metadata is saved when book.upsert arrives before its bundle. */
    val remoteTitle: String? = null,
    val remoteAuthor: String? = null,
    val remoteContentType: String? = null,
    val remoteFormat: String? = null,
    /** Latest remote position received before this device has the book bundle. */
    val pendingProgressJson: String? = null,
    val pendingProgressOccurredAt: Long? = null,
    val isDeleted: Boolean = false,
    val lastSyncedAt: Long? = null,
)
