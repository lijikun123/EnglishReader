package com.example.englishreader.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 收藏的生词。后续可导出为 Anki TSV。 */
@Entity(
    tableName = "vocabulary_items",
    indices = [Index("word")],
)
data class VocabularyItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    /** 原型（词形还原后的形式），如 replied -> reply。 */
    val lemma: String = word,
    val phonetic: String = "",
    val partOfSpeech: String = "",
    val chineseMeaning: String = "",
    val englishDefinition: String = "",
    /** 词典例句。 */
    val exampleSentence: String = "",
    /** 收藏时所在的原句（来自正文）。 */
    val sourceSentence: String = "",
    /** 来源文章/书籍 id。 */
    val sourceReadingItemId: Long? = null,
    /** 来源书名（冗余存储，便于生词本直接展示）。 */
    val sourceBookTitle: String = "",
    /** 来源章节标题（EPUB 适用，单文件为空）。 */
    val sourceChapterTitle: String = "",
    val note: String = "",
    /** 熟悉度 0=新词 .. 5=已掌握。第一版仅展示，不做复杂算法。 */
    val familiarity: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
