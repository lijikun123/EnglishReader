package com.example.englishreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EPUB（或其他多章节书籍）的单个章节。
 * 通过外键挂在 [ReadingItem] 上，删除书籍时级联删除其章节。
 */
@Entity(
    tableName = "reading_chapters",
    indices = [
        Index("readingItemId"),
        Index(value = ["readingItemId", "chapterIndex"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ReadingItem::class,
            parentColumns = ["id"],
            childColumns = ["readingItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingChapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val readingItemId: Long,
    val chapterIndex: Int,
    val title: String,
    val content: String,
    /** 本章阅读进度 0f..1f。 */
    val progress: Float = 0f,
    /** 本章上次阅读到的段落索引。 */
    val lastReadPosition: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
