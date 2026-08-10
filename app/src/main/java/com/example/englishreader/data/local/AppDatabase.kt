package com.example.englishreader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.englishreader.data.local.dao.AiAnalysisCacheDao
import com.example.englishreader.data.local.dao.ChapterPhraseDao
import com.example.englishreader.data.local.dao.ChapterTranslationDao
import com.example.englishreader.data.local.dao.DictionaryDao
import com.example.englishreader.data.local.dao.LookupHistoryDao
import com.example.englishreader.data.local.dao.ReadingChapterDao
import com.example.englishreader.data.local.dao.ReadingItemDao
import com.example.englishreader.data.local.dao.ReadingTocItemDao
import com.example.englishreader.data.local.dao.SyncBookDao
import com.example.englishreader.data.local.dao.SyncOutboxDao
import com.example.englishreader.data.local.dao.VocabularyDao
import com.example.englishreader.data.local.entity.AiAnalysisCache
import com.example.englishreader.data.local.entity.ChapterPhrase
import com.example.englishreader.data.local.entity.ChapterTranslation
import com.example.englishreader.data.local.entity.DictionaryEntry
import com.example.englishreader.data.local.entity.LookupHistory
import com.example.englishreader.data.local.entity.ReadingChapter
import com.example.englishreader.data.local.entity.ReadingItem
import com.example.englishreader.data.local.entity.ReadingTocItem
import com.example.englishreader.data.local.entity.SyncBook
import com.example.englishreader.data.local.entity.SyncOutbox
import com.example.englishreader.data.local.entity.VocabularyItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ReadingItem::class,
        ReadingChapter::class,
        ReadingTocItem::class,
        VocabularyItem::class,
        DictionaryEntry::class,
        LookupHistory::class,
        AiAnalysisCache::class,
        ChapterTranslation::class,
        ChapterPhrase::class,
        SyncBook::class,
        SyncOutbox::class,
    ],
    version = 8,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun readingItemDao(): ReadingItemDao
    abstract fun readingChapterDao(): ReadingChapterDao
    abstract fun readingTocItemDao(): ReadingTocItemDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun lookupHistoryDao(): LookupHistoryDao
    abstract fun aiAnalysisCacheDao(): AiAnalysisCacheDao
    abstract fun chapterTranslationDao(): ChapterTranslationDao
    abstract fun chapterPhraseDao(): ChapterPhraseDao
    abstract fun syncBookDao(): SyncBookDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        private const val DB_NAME = "english_reader.db"

        // v4 → v5：新增双语译文缓存表。用真正的迁移而非重建，保住用户已导入的书/词典/生词。
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chapter_translations` " +
                        "(`itemId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, " +
                        "`paragraphIndex` INTEGER NOT NULL, `translated` TEXT NOT NULL, " +
                        "PRIMARY KEY(`itemId`, `chapterIndex`, `paragraphIndex`))",
                )
            }
        }

        // v5 → v6：新增词组检测缓存表。同样用迁移保数据。
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chapter_phrases` " +
                        "(`itemId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, " +
                        "`paragraphIndex` INTEGER NOT NULL, `phrasesJson` TEXT NOT NULL, " +
                        "PRIMARY KEY(`itemId`, `chapterIndex`, `paragraphIndex`))",
                )
            }
        }

        /** v6 → v7：同步旁路表，绝不改写既有阅读条目的 Long 主键。 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_books` (" +
                        "`clientBookId` TEXT NOT NULL, " +
                        "`localReadingItemId` INTEGER, " +
                        "`cloudBookId` TEXT, " +
                        "`contentSha256` TEXT NOT NULL, " +
                        "`contentBytes` INTEGER NOT NULL, " +
                        "`contentRevision` INTEGER NOT NULL, " +
                        "`bundleUploaded` INTEGER NOT NULL, " +
                        "`remoteRevision` INTEGER NOT NULL, " +
                        "`remoteTitle` TEXT, " +
                        "`remoteAuthor` TEXT, " +
                        "`remoteContentType` TEXT, " +
                        "`remoteFormat` TEXT, " +
                        "`isDeleted` INTEGER NOT NULL, " +
                        "`lastSyncedAt` INTEGER, " +
                        "PRIMARY KEY(`clientBookId`), " +
                        "FOREIGN KEY(`localReadingItemId`) REFERENCES `reading_items`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_books_cloudBookId` ON `sync_books` (`cloudBookId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_books_localReadingItemId` ON `sync_books` (`localReadingItemId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_outbox` (" +
                        "`mutationId` TEXT NOT NULL, " +
                        "`localReadingItemId` INTEGER, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`retryCount` INTEGER NOT NULL, " +
                        "`lastError` TEXT, " +
                        "PRIMARY KEY(`mutationId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_localReadingItemId` ON `sync_outbox` (`localReadingItemId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_localReadingItemId_kind` ON `sync_outbox` (`localReadingItemId`, `kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_createdAt` ON `sync_outbox` (`createdAt`)")
            }
        }

        /** v7 → v8：暂存早到的远端阅读进度，隔离不可恢复的待发送项。 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_books` ADD COLUMN `pendingProgressJson` TEXT")
                db.execSQL("ALTER TABLE `sync_books` ADD COLUMN `pendingProgressOccurredAt` INTEGER")
                db.execSQL("ALTER TABLE `sync_outbox` ADD COLUMN `terminal` INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context, scope: CoroutineScope): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context, scope).also { INSTANCE = it }
            }

        private fun build(context: Context, scope: CoroutineScope): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                // 未覆盖的版本跳变应当显式失败，而不是静默清掉待同步的书籍。
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // 首次创建数据库时填充示例内容与假词典数据。
                        scope.launch(Dispatchers.IO) {
                            INSTANCE?.let { SeedData.populate(it) }
                        }
                    }
                })
                .build()
    }
}
