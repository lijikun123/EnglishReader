package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.englishreader.data.local.entity.SyncOutbox

@Dao
interface SyncOutboxDao {

    @Query("SELECT * FROM sync_outbox WHERE kind IN (:kinds) AND terminal = 0 ORDER BY createdAt ASC LIMIT :limit")
    suspend fun nextByKinds(kinds: List<String>, limit: Int): List<SyncOutbox>

    @Query("DELETE FROM sync_outbox WHERE localReadingItemId = :localId AND kind = :kind")
    suspend fun deleteForLocalAndKind(localId: Long, kind: String)

    @Query("DELETE FROM sync_outbox WHERE localReadingItemId = :localId")
    suspend fun deleteForLocal(localId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SyncOutbox)

    @Query("DELETE FROM sync_outbox WHERE mutationId IN (:mutationIds)")
    suspend fun deleteByMutationIds(mutationIds: List<String>)

    @Query("UPDATE sync_outbox SET retryCount = retryCount + 1, lastError = :message, terminal = 1 WHERE mutationId IN (:mutationIds)")
    suspend fun markTerminal(mutationIds: List<String>, message: String?)

    @Query("UPDATE sync_outbox SET retryCount = retryCount + 1, lastError = :message, terminal = 1 WHERE localReadingItemId = :localId")
    suspend fun markTerminalForLocal(localId: Long, message: String?)

    @Query("DELETE FROM sync_outbox")
    suspend fun clearAll()
}
