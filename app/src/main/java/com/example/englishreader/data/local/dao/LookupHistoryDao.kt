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
}
