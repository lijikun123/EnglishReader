package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.englishreader.data.local.entity.ChapterTranslation

@Dao
interface ChapterTranslationDao {

    @Query("SELECT * FROM chapter_translations WHERE itemId = :itemId AND chapterIndex = :chapterIndex")
    suspend fun getForChapter(itemId: Long, chapterIndex: Int): List<ChapterTranslation>

    @Query(
        "SELECT translated FROM chapter_translations " +
            "WHERE itemId = :itemId AND chapterIndex = :chapterIndex AND paragraphIndex = :paragraphIndex",
    )
    suspend fun get(itemId: Long, chapterIndex: Int, paragraphIndex: Int): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ChapterTranslation)
}
