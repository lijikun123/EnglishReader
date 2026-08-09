package com.example.englishreader.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地词典词条（中英文）。第一版用内置假数据填充，
 * 后续支持导入 CSV / JSON 词典写入此表。
 */
@Entity(
    tableName = "dictionary_entries",
    indices = [Index("word"), Index("lemma")],
)
data class DictionaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val lemma: String = word,
    val phonetic: String = "",
    val partOfSpeech: String = "",
    /** 中文释义（点击单词时优先展示）。 */
    val chineseMeaning: String = "",
    val englishDefinition: String = "",
    val exampleSentence: String = "",
)
