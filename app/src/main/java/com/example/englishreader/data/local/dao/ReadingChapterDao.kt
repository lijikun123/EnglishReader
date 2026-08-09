package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.englishreader.data.local.entity.ReadingChapter
import kotlinx.coroutines.flow.Flow

/** 章节目录用的轻量投影（不含正文，避免一次性把整本书载入内存）。 */
data class ChapterMeta(
    val chapterIndex: Int,
    val title: String,
)

@Dao
interface ReadingChapterDao {

    @Insert
    suspend fun insertAll(chapters: List<ReadingChapter>)

    @Query(
        "SELECT chapterIndex, title FROM reading_chapters WHERE readingItemId = :bookId ORDER BY chapterIndex",
    )
    fun observeMeta(bookId: Long): Flow<List<ChapterMeta>>

    @Query("SELECT COUNT(*) FROM reading_chapters WHERE readingItemId = :bookId")
    suspend fun count(bookId: Long): Int

    /** 各章节正文字符数（按章节顺序），用于估算整本书页数。 */
    @Query("SELECT LENGTH(content) FROM reading_chapters WHERE readingItemId = :bookId ORDER BY chapterIndex")
    suspend fun contentLengths(bookId: Long): List<Int>

    @Query(
        "SELECT * FROM reading_chapters WHERE readingItemId = :bookId AND chapterIndex = :index LIMIT 1",
    )
    suspend fun getChapter(bookId: Long, index: Int): ReadingChapter?

    @Query(
        "UPDATE reading_chapters SET lastReadPosition = :position, progress = :progress, updatedAt = :updatedAt " +
            "WHERE readingItemId = :bookId AND chapterIndex = :index",
    )
    suspend fun updateProgress(bookId: Long, index: Int, position: Int, progress: Float, updatedAt: Long)
}
