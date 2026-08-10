package com.example.englishreader.di

import android.content.Context
import com.example.englishreader.ai.DeepSeekProvider
import com.example.englishreader.data.repository.TranslationRepository
import com.example.englishreader.data.repository.PhraseRepository
import com.example.englishreader.data.datastore.settingsDataStore
import com.example.englishreader.data.exporter.AnkiExporter
import com.example.englishreader.data.importer.DictionaryImporter
import com.example.englishreader.data.importer.DocumentImporter
import com.example.englishreader.data.local.AppDatabase
import com.example.englishreader.data.repository.AiRepository
import com.example.englishreader.data.repository.DictionaryRepository
import com.example.englishreader.data.repository.ReadingRepository
import com.example.englishreader.data.repository.SettingsRepository
import com.example.englishreader.data.repository.VocabularyRepository
import com.example.englishreader.data.security.AiKeyStore
import com.example.englishreader.data.sync.SyncApi
import com.example.englishreader.data.sync.SyncRepository
import com.example.englishreader.data.sync.SyncScheduler
import com.example.englishreader.data.sync.SyncSettingsRepository
import com.example.englishreader.data.sync.SyncTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 手写的轻量依赖容器（Service Locator）。
 * 整个 App 共享一个实例，由 [com.example.englishreader.EnglishReaderApp] 持有。
 *
 * 选择手写 DI 而非 Hilt：减少注解处理器与插件，让骨架更易编译、易读、易扩展。
 * 后续如需 Hilt，可平滑替换。
 */
class AppContainer(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext, scope)

    val documentImporter = DocumentImporter(appContext)
    val dictionaryImporter = DictionaryImporter(appContext)
    val ankiExporter = AnkiExporter(appContext)
    private val aiKeyStore = AiKeyStore(appContext)
    val settingsRepository = SettingsRepository(appContext.settingsDataStore, aiKeyStore)
    private val syncSettingsRepository = SyncSettingsRepository(appContext.settingsDataStore)
    private val syncScheduler = SyncScheduler(appContext)
    private val syncTokenStore = SyncTokenStore(appContext)
    private val syncApi = SyncApi()
    val syncRepository = SyncRepository(
        database = database,
        readingItemDao = database.readingItemDao(),
        chapterDao = database.readingChapterDao(),
        tocDao = database.readingTocItemDao(),
        syncBookDao = database.syncBookDao(),
        outboxDao = database.syncOutboxDao(),
        settingsRepository = syncSettingsRepository,
        tokenStore = syncTokenStore,
        api = syncApi,
        scheduler = syncScheduler,
    )
    val readingRepository = ReadingRepository(
        dao = database.readingItemDao(),
        chapterDao = database.readingChapterDao(),
        tocDao = database.readingTocItemDao(),
        database = database,
        syncMutationWriter = syncRepository,
    )
    val dictionaryRepository = DictionaryRepository(
        dictionaryDao = database.dictionaryDao(),
        lookupHistoryDao = database.lookupHistoryDao(),
    )
    val vocabularyRepository = VocabularyRepository(database.vocabularyDao())

    init {
        scope.launch(Dispatchers.IO) { settingsRepository.migrateLegacyAiKey() }
    }

    // 真实 AI：OpenAI 兼容的 Chat Completions（默认 DeepSeek）。连接配置 / 提示词来自「设置 → AI」。
    private val aiProvider = DeepSeekProvider()
    val aiRepository = AiRepository(
        cacheDao = database.aiAnalysisCacheDao(),
        provider = aiProvider,
    )

    // 双语阅读：段落中文译文（缓存优先 + AI 翻译）。
    val translationRepository = TranslationRepository(
        dao = database.chapterTranslationDao(),
        provider = aiProvider,
        settingsRepository = settingsRepository,
    )

    // 词组加粗：AI 识别词组（缓存优先）。
    val phraseRepository = PhraseRepository(
        dao = database.chapterPhraseDao(),
        provider = aiProvider,
        settingsRepository = settingsRepository,
    )
}
