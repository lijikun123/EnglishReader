package com.example.englishreader.data.repository

import androidx.room.withTransaction
import com.example.englishreader.data.importer.ParsedChapter
import com.example.englishreader.data.importer.ParsedTocItem
import com.example.englishreader.data.local.AppDatabase
import com.example.englishreader.data.local.dao.ChapterMeta
import com.example.englishreader.data.local.dao.ReadingChapterDao
import com.example.englishreader.data.local.dao.ReadingItemDao
import com.example.englishreader.data.local.dao.ReadingTocItemDao
import com.example.englishreader.data.local.entity.BookFormat
import com.example.englishreader.data.local.entity.ContentType
import com.example.englishreader.data.local.entity.ReadingChapter
import com.example.englishreader.data.local.entity.ReadingItem
import com.example.englishreader.data.local.entity.ReadingTocItem
import com.example.englishreader.data.sync.SyncMutationWriter
import kotlinx.coroutines.flow.Flow

class ReadingRepository(
    private val dao: ReadingItemDao,
    private val chapterDao: ReadingChapterDao,
    private val tocDao: ReadingTocItemDao,
    private val database: AppDatabase,
    private val syncMutationWriter: SyncMutationWriter? = null,
) {

    fun observeAll(): Flow<List<ReadingItem>> = dao.observeAll()

    fun observeById(id: Long): Flow<ReadingItem?> = dao.observeById(id)

    suspend fun getById(id: Long): ReadingItem? = dao.getById(id)

    // ---- 导入 ----

    suspend fun addTextBook(
        title: String,
        content: String,
        contentType: ContentType,
        format: BookFormat,
    ): Long = database.withTransaction {
        val bookId = dao.insert(
            ReadingItem(title = title, content = content, contentType = contentType, format = format),
        )
        syncMutationWriter?.onBookImported(bookId)
        bookId
    }

    /** 插入 EPUB：书 + 章节 + 目录，放在一个事务里，保证一致性。 */
    suspend fun addEpubBook(
        title: String,
        author: String,
        contentType: ContentType,
        chapters: List<ParsedChapter>,
        toc: List<ParsedTocItem>,
    ): Long = database.withTransaction {
        val bookId = dao.insert(
            ReadingItem(
                title = title,
                content = "",
                author = author,
                contentType = contentType,
                format = BookFormat.EPUB,
            ),
        )
        val now = System.currentTimeMillis()
        chapterDao.insertAll(
            chapters.mapIndexed { index, chapter ->
                ReadingChapter(
                    readingItemId = bookId,
                    chapterIndex = index,
                    title = chapter.title,
                    content = chapter.content,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
        if (toc.isNotEmpty()) {
            tocDao.insertAll(
                toc.map { item ->
                    ReadingTocItem(
                        readingItemId = bookId,
                        chapterIndex = item.chapterIndex,
                        label = item.label,
                        href = item.href,
                        level = item.level,
                        orderIndex = item.orderIndex,
                        anchorParagraph = item.anchorParagraph,
                        createdAt = now,
                    )
                },
            )
        }
        syncMutationWriter?.onBookImported(bookId)
        bookId
    }

    // ---- 进度：单文件 ----

    suspend fun saveProgress(id: Long, position: Int, progress: Float) = database.withTransaction {
        dao.updateProgress(id, position, progress.coerceIn(0f, 1f), System.currentTimeMillis())
        syncMutationWriter?.onProgressChanged(id)
    }

    // ---- 章节（EPUB） ----

    fun observeChaptersMeta(bookId: Long): Flow<List<ChapterMeta>> = chapterDao.observeMeta(bookId)

    /** 电子书自带目录（无则为空，调用方回退到 spine 章节列表）。 */
    fun observeToc(bookId: Long): Flow<List<ReadingTocItem>> = tocDao.observe(bookId)

    suspend fun getChapter(bookId: Long, index: Int): ReadingChapter? =
        chapterDao.getChapter(bookId, index)

    /** 各章字符数（估算整本书页数用）。 */
    suspend fun chapterContentLengths(bookId: Long): List<Int> =
        chapterDao.contentLengths(bookId)

    suspend fun saveChapterProgress(bookId: Long, index: Int, position: Int, progress: Float) = database.withTransaction {
        chapterDao.updateProgress(bookId, index, position, progress.coerceIn(0f, 1f), System.currentTimeMillis())
        syncMutationWriter?.onProgressChanged(bookId)
    }

    /** 保存「当前读到第几章」+ 整本书进度。 */
    suspend fun saveBookChapterState(bookId: Long, chapterIndex: Int, bookProgress: Float) = database.withTransaction {
        dao.updateChapterState(bookId, chapterIndex, bookProgress.coerceIn(0f, 1f), System.currentTimeMillis())
        syncMutationWriter?.onProgressChanged(bookId)
    }

    suspend fun delete(item: ReadingItem) = database.withTransaction {
        syncMutationWriter?.onBookDeleted(item.id)
        dao.delete(item)
    }
}
