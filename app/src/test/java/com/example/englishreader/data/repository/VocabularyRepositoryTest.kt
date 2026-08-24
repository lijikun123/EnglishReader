package com.example.englishreader.data.repository

import com.example.englishreader.data.local.dao.VocabularyDao
import com.example.englishreader.data.local.entity.VocabularyItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VocabularyRepositoryTest {

    @Test
    fun `saves normalized word once and rejects later case or spacing variants`() = runBlocking {
        val dao = FakeVocabularyDao()
        val repository = VocabularyRepository(dao)

        assertTrue(repository.saveIfAbsent(VocabularyItem(word = "  take   cues from  ")))
        assertFalse(repository.saveIfAbsent(VocabularyItem(word = "TAKE cues from")))
        assertFalse(repository.saveIfAbsent(VocabularyItem(word = "   ")))

        assertEquals(listOf("take cues from"), dao.saved.map { it.word })
    }

    private class FakeVocabularyDao : VocabularyDao {
        val saved = mutableListOf<VocabularyItem>()

        override fun observeAll(): Flow<List<VocabularyItem>> = flowOf(saved.toList())

        override fun observeById(id: Long): Flow<VocabularyItem?> = flowOf(saved.firstOrNull { it.id == id })

        override suspend fun existsNormalized(normalizedWord: String): Boolean =
            saved.any { it.word.trim().lowercase(Locale.ROOT) == normalizedWord }

        override suspend fun upsert(item: VocabularyItem): Long {
            saved += item.copy(id = saved.size + 1L)
            return saved.last().id
        }

        override suspend fun update(item: VocabularyItem) = Unit

        override suspend fun delete(item: VocabularyItem) {
            saved.removeAll { it.id == item.id }
        }

        override suspend fun deleteByIds(ids: List<Long>) {
            saved.removeAll { it.id in ids }
        }

        override suspend fun clearBookReference(readingItemId: Long) = Unit
    }
}
