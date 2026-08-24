package com.example.englishreader.data.repository

import com.example.englishreader.data.local.dao.VocabularyDao
import com.example.englishreader.data.local.entity.VocabularyItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class VocabularyRepository(private val dao: VocabularyDao) {

    private val saveMutex = Mutex()

    fun observeAll(): Flow<List<VocabularyItem>> = dao.observeAll()

    suspend fun exists(word: String): Boolean = dao.existsNormalized(normalizedKey(word))

    /**
     * Keeps one card per word or phrase. The mutex closes the small gap between
     * checking and inserting when a user taps the save action repeatedly.
     */
    suspend fun saveIfAbsent(item: VocabularyItem): Boolean = saveMutex.withLock {
        val formattedWord = displayWord(item.word)
        if (formattedWord.isEmpty() || dao.existsNormalized(normalizedKey(formattedWord))) return@withLock false
        dao.upsert(
            item.copy(
                word = formattedWord,
                lemma = displayWord(item.lemma).ifEmpty { formattedWord },
            ),
        )
        true
    }

    suspend fun update(item: VocabularyItem) = dao.update(item)

    suspend fun delete(item: VocabularyItem) = dao.delete(item)

    suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    private fun normalizedKey(value: String): String = displayWord(value).lowercase(Locale.ROOT)

    private fun displayWord(value: String): String = value.trim().replace(Regex("\\s+"), " ")
}
