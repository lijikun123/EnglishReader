package com.example.englishreader.data.repository

import com.example.englishreader.data.local.dao.DictionaryDao
import com.example.englishreader.data.local.dao.LookupHistoryDao
import com.example.englishreader.data.local.entity.DictionaryEntry
import com.example.englishreader.data.local.entity.LookupHistory
import kotlinx.coroutines.flow.Flow

class DictionaryRepository(
    private val dictionaryDao: DictionaryDao,
    private val lookupHistoryDao: LookupHistoryDao,
) {

    /** 当前词典词条总数（实时）。 */
    fun observeCount(): Flow<Int> = dictionaryDao.observeCount()

    /**
     * 导入词条：对同名 word 先删后插（导入词典优先、避免重复），其余内置词条保留。
     * 分批删除以规避 SQLite IN(...) 变量上限。返回写入条数。
     */
    suspend fun importEntries(entries: List<DictionaryEntry>): Int {
        val words = entries.map { it.word }.distinct()
        words.chunked(500).forEach { dictionaryDao.deleteByWords(it) }
        dictionaryDao.insertAll(entries)
        return entries.size
    }

    /**
     * 查词：先按原文匹配，未命中时做一次朴素词形还原再查。
     * 返回可能的多个词条（不同词性）。
     */
    suspend fun lookup(rawWord: String): List<DictionaryEntry> {
        val normalized = normalize(rawWord)
        if (normalized.isEmpty()) return emptyList()

        val direct = dictionaryDao.findEntries(normalized)
        if (direct.isNotEmpty()) return direct

        val lemma = naiveLemma(normalized)
        return if (lemma != normalized) dictionaryDao.findEntries(lemma) else emptyList()
    }

    suspend fun recordLookup(word: String, sentence: String, readingItemId: Long?) {
        lookupHistoryDao.insert(
            LookupHistory(
                word = normalize(word),
                sourceSentence = sentence,
                readingItemId = readingItemId,
            ),
        )
    }

    /** 清洗单词：去首尾标点、转小写。 */
    fun normalize(word: String): String =
        word.trim()
            .lowercase()
            .trim('.', ',', '!', '?', ';', ':', '"', '\'', '(', ')', '[', ']', '—', '-', '“', '”', '‘', '’')

    /** 极简词形还原（仅用于本地词典回退命中，后续可替换为真正的 lemmatizer）。 */
    private fun naiveLemma(word: String): String = when {
        word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
        word.endsWith("es") && word.length > 3 -> word.dropLast(2)
        word.endsWith("s") && !word.endsWith("ss") && word.length > 2 -> word.dropLast(1)
        word.endsWith("ing") && word.length > 5 -> word.dropLast(3)
        word.endsWith("ed") && word.length > 4 -> word.dropLast(2)
        else -> word
    }
}
