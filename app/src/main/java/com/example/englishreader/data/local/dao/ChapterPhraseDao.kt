package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.englishreader.data.local.entity.ChapterPhrase

@Dao
interface ChapterPhraseDao {

    @Query("SELECT * FROM chapter_phrases WHERE itemId = :itemId AND chapterIndex = :chapterIndex")
    suspend fun getForChapter(itemId: Long, chapterIndex: Int): List<ChapterPhrase>

    @Query(
        "SELECT phrasesJson FROM chapter_phrases " +
            "WHERE itemId = :itemId AND chapterIndex = :chapterIndex AND paragraphIndex = :paragraphIndex",
    )
    suspend fun get(itemId: Long, chapterIndex: Int, paragraphIndex: Int): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ChapterPhrase)

    /** 删除书籍时一并移除其词组识别缓存，避免遗留正文片段。 */
    @Query("DELETE FROM chapter_phrases WHERE itemId = :itemId")
    suspend fun deleteForBook(itemId: Long)
}
