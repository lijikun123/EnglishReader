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
import com.example.englishreader.data.local.dao.VocabularyDao
import com.example.englishreader.data.local.entity.AiAnalysisCache
import com.example.englishreader.data.local.entity.ChapterPhrase
import com.example.englishreader.data.local.entity.ChapterTranslation
import com.example.englishreader.data.local.entity.DictionaryEntry
import com.example.englishreader.data.local.entity.LookupHistory
import com.example.englishreader.data.local.entity.ReadingChapter
import com.example.englishreader.data.local.entity.ReadingItem
import com.example.englishreader.data.local.entity.ReadingTocItem
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
    ],
    version = 6,
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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context, scope: CoroutineScope): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context, scope).also { INSTANCE = it }
            }

        private fun build(context: Context, scope: CoroutineScope): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                // 有迁移路径时优先走迁移（保数据）；只有遇到没覆盖的版本跳变才兜底重建。
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration(dropAllTables = true)
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
