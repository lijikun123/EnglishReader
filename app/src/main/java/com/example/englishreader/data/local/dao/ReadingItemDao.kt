package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.englishreader.data.local.entity.ReadingItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingItemDao {

    @Query("SELECT * FROM reading_items ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ReadingItem>>

    @Query("SELECT * FROM reading_items WHERE id = :id")
    fun observeById(id: Long): Flow<ReadingItem?>

    @Query("SELECT * FROM reading_items WHERE id = :id")
    suspend fun getById(id: Long): ReadingItem?

    @Query("SELECT * FROM reading_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ReadingItem>

    @Query("SELECT COUNT(*) FROM reading_items")
    suspend fun count(): Int

    @Query("SELECT * FROM reading_items ORDER BY id")
    suspend fun getAll(): List<ReadingItem>

    @Insert
    suspend fun insert(item: ReadingItem): Long

    @Insert
    suspend fun insertAll(items: List<ReadingItem>)

    @Update
    suspend fun update(item: ReadingItem)

    @Query(
        "UPDATE reading_items SET lastReadPosition = :position, progress = :progress, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateProgress(id: Long, position: Int, progress: Float, updatedAt: Long)

    /** EPUB：保存当前章节索引 + 整本书进度。 */
    @Query(
        "UPDATE reading_items SET currentChapterIndex = :chapterIndex, progress = :progress, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateChapterState(id: Long, chapterIndex: Int, progress: Float, updatedAt: Long)

    @Delete
    suspend fun delete(item: ReadingItem)
}
