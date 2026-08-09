package com.example.englishreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 阅读内容类型：小说 / 外刊文章。 */
enum class ContentType { NOVEL, ARTICLE }

/** 书籍来源格式。 */
enum class BookFormat { TXT, MARKDOWN, EPUB }

/**
 * 书架中的一本书 / 一篇文章。
 *
 * - TXT / MARKDOWN / 示例内容：正文直接存在 [content]。
 * - EPUB：正文按章节存放在 reading_chapters 表，[content] 为空，
 *   阅读位置用 [currentChapterIndex] + 章节内的 lastReadPosition 表示。
 */
@Entity(tableName = "reading_items")
data class ReadingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 单文件正文（TXT / Markdown / 示例）。EPUB 为空。 */
    val content: String = "",
    val author: String = "",
    val contentType: ContentType,
    val format: BookFormat = BookFormat.TXT,
    /** 整本书阅读进度 0f..1f。 */
    val progress: Float = 0f,
    /** EPUB：当前章节索引。 */
    val currentChapterIndex: Int = 0,
    /** 单文件模式下上次阅读到的段落索引。 */
    val lastReadPosition: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
