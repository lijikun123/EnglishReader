package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.englishreader.data.local.entity.LookupHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface LookupHistoryDao {

    @Query("SELECT * FROM lookup_history ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<LookupHistory>>

    @Insert
    suspend fun insert(history: LookupHistory)

    /** 保留查词历史，但不再把它关联到已删除的本地书籍。 */
    @Query("UPDATE lookup_history SET readingItemId = NULL WHERE readingItemId = :readingItemId")
    suspend fun clearBookReference(readingItemId: Long)
}
