package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.englishreader.data.local.entity.VocabularyItem
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {

    @Query("SELECT * FROM vocabulary_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VocabularyItem>>

    @Query("SELECT * FROM vocabulary_items WHERE id = :id")
    fun observeById(id: Long): Flow<VocabularyItem?>

    /**
     * 生词本按显示词条去重；调用方会先折叠空白、转成小写。
     * 这样 Word / word 和重复点开的同一词组不会生成多张卡片。
     */
    @Query("SELECT EXISTS(SELECT 1 FROM vocabulary_items WHERE LOWER(TRIM(word)) = :normalizedWord)")
    suspend fun existsNormalized(normalizedWord: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: VocabularyItem): Long

    @Update
    suspend fun update(item: VocabularyItem)

    @Delete
    suspend fun delete(item: VocabularyItem)

    /** 生词本保留来源书名和原句，只移除已删除书籍的本地关联。 */
    @Query("UPDATE vocabulary_items SET sourceReadingItemId = NULL WHERE sourceReadingItemId = :readingItemId")
    suspend fun clearBookReference(readingItemId: Long)
}
