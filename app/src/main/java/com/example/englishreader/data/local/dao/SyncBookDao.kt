package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.englishreader.data.local.entity.SyncBook

@Dao
interface SyncBookDao {

    @Query("SELECT * FROM sync_books WHERE localReadingItemId = :localId LIMIT 1")
    suspend fun getByLocalId(localId: Long): SyncBook?

    @Query("SELECT * FROM sync_books WHERE cloudBookId = :cloudBookId LIMIT 1")
    suspend fun getByCloudBookId(cloudBookId: String): SyncBook?

    @Query("SELECT * FROM sync_books WHERE clientBookId = :clientBookId LIMIT 1")
    suspend fun getByClientBookId(clientBookId: String): SyncBook?

    @Query("SELECT * FROM sync_books WHERE bundleUploaded = 0 AND cloudBookId IS NOT NULL AND localReadingItemId IS NOT NULL AND isDeleted = 0")
    suspend fun pendingBundleUploads(): List<SyncBook>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: SyncBook)

    @Update
    suspend fun update(book: SyncBook)

    @Query("UPDATE sync_books SET cloudBookId = :cloudBookId, lastSyncedAt = :syncedAt WHERE clientBookId = :clientBookId")
    suspend fun setCloudBookId(clientBookId: String, cloudBookId: String, syncedAt: Long)

    @Query("UPDATE sync_books SET bundleUploaded = :uploaded, lastSyncedAt = :syncedAt WHERE clientBookId = :clientBookId")
    suspend fun setBundleUploaded(clientBookId: String, uploaded: Boolean, syncedAt: Long)

    @Query("UPDATE sync_books SET remoteRevision = :revision, lastSyncedAt = :syncedAt WHERE clientBookId = :clientBookId")
    suspend fun setRemoteRevision(clientBookId: String, revision: Long, syncedAt: Long)

    @Query("UPDATE sync_books SET localReadingItemId = :localId, isDeleted = 0 WHERE clientBookId = :clientBookId")
    suspend fun setLocalReadingItemId(clientBookId: String, localId: Long)

    @Query("UPDATE sync_books SET localReadingItemId = NULL, isDeleted = 1 WHERE clientBookId = :clientBookId")
    suspend fun markDeleted(clientBookId: String)

    @Query(
        "UPDATE sync_books SET pendingProgressJson = :payload, pendingProgressOccurredAt = :occurredAt, " +
            "remoteRevision = :revision, lastSyncedAt = :syncedAt WHERE clientBookId = :clientBookId",
    )
    suspend fun setPendingProgress(
        clientBookId: String,
        payload: String,
        occurredAt: Long,
        revision: Long,
        syncedAt: Long,
    )

    @Query("DELETE FROM sync_books WHERE clientBookId = :clientBookId")
    suspend fun deleteByClientBookId(clientBookId: String)

    @Query("DELETE FROM sync_books")
    suspend fun clearAll()
}
