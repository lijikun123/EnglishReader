package com.example.englishreader.data.repository

import com.example.englishreader.data.local.dao.VocabularyDao
import com.example.englishreader.data.local.entity.VocabularyItem
import kotlinx.coroutines.flow.Flow

class VocabularyRepository(private val dao: VocabularyDao) {

    fun observeAll(): Flow<List<VocabularyItem>> = dao.observeAll()

    suspend fun exists(word: String): Boolean = dao.exists(word)

    suspend fun save(item: VocabularyItem): Long = dao.upsert(item)

    suspend fun update(item: VocabularyItem) = dao.update(item)

    suspend fun delete(item: VocabularyItem) = dao.delete(item)
}
