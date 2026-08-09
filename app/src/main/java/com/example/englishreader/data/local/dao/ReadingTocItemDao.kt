package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.englishreader.data.local.entity.ReadingTocItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingTocItemDao {

    @Insert
    suspend fun insertAll(items: List<ReadingTocItem>)

    @Query("SELECT * FROM reading_toc_items WHERE readingItemId = :bookId ORDER BY orderIndex")
    fun observe(bookId: Long): Flow<List<ReadingTocItem>>

    @Query("SELECT COUNT(*) FROM reading_toc_items WHERE readingItemId = :bookId")
    suspend fun count(bookId: Long): Int
}
