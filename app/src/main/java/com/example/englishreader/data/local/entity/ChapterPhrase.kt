package com.example.englishreader.data.local.entity

import androidx.room.Entity

/**
 * 词组检测缓存：按 书 + 章 + 段落 唯一，phrasesJson 存该段 AI 识别出的词组（JSON 数组）。
 * 检测过一次就缓存，不再重复调用 AI。
 */
@Entity(
    tableName = "chapter_phrases",
    primaryKeys = ["itemId", "chapterIndex", "paragraphIndex"],
)
data class ChapterPhrase(
    val itemId: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val phrasesJson: String,
)
