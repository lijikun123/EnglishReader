package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.englishreader.data.local.entity.DictionaryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryDao {

    /** 按单词或原型匹配，可能返回多个词条（不同词性）。 */
    @Query("SELECT * FROM dictionary_entries WHERE word = :word OR lemma = :word")
    suspend fun findEntries(word: String): List<DictionaryEntry>

    @Query("SELECT COUNT(*) FROM dictionary_entries")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM dictionary_entries")
    fun observeCount(): Flow<Int>

    /** 删除指定单词的旧记录（导入时按 word 去重 / 覆盖用，调用方需分批以规避 SQLite 变量上限）。 */
    @Query("DELETE FROM dictionary_entries WHERE word IN (:words)")
    suspend fun deleteByWords(words: List<String>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<DictionaryEntry>)
}
