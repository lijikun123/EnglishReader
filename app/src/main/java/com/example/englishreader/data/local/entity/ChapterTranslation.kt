package com.example.englishreader.data.local.entity

import androidx.room.Entity

/**
 * 双语阅读的中文译文缓存：按 书 + 章 + 段落序号 唯一。翻过一次就存下来，不再重复调用 AI。
 */
@Entity(
    tableName = "chapter_translations",
    primaryKeys = ["itemId", "chapterIndex", "paragraphIndex"],
)
data class ChapterTranslation(
    val itemId: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val translated: String,
)
