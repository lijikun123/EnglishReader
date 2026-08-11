package com.example.englishreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.englishreader.ai.AiAnalysisType
import com.example.englishreader.data.local.dao.ChapterMeta
import com.example.englishreader.data.local.entity.BookFormat
import com.example.englishreader.data.local.entity.DictionaryEntry
import com.example.englishreader.data.local.entity.ReadingChapter
import com.example.englishreader.data.local.entity.ReadingItem
import com.example.englishreader.data.local.entity.VocabularyItem
import com.example.englishreader.data.model.AiSettings
import com.example.englishreader.data.model.DetectedPhrase
import com.example.englishreader.data.model.ReadingSettings
import com.example.englishreader.data.repository.AiRepository
import com.example.englishreader.data.repository.DictionaryRepository
import com.example.englishreader.data.repository.ReadingRepository
import com.example.englishreader.data.repository.PhraseRepository
import com.example.englishreader.data.repository.SettingsRepository
import com.example.englishreader.data.repository.TranslationRepository
import com.example.englishreader.data.repository.VocabularyRepository
import com.example.englishreader.di.container
import com.example.englishreader.ui.navigation.Destination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** 查词面板 UI 状态。 */
data class LookupUiState(
    val word: String,
    val sentence: String,
    val entries: List<DictionaryEntry>,
    val saved: Boolean = false,
)

/** AI 分析结果 UI 状态。 */
data class AiResultUiState(
    val type: AiAnalysisType,
    val loading: Boolean,
    val text: String = "",
)

/** 长按句子弹出的 AI 菜单状态。 */
data class SentenceMenuState(
    val sentence: String,
    val paragraph: String,
)

/** 目录项（真实 TOC 或 spine 回退）。 */
data class TocEntry(
    val label: String,
    val level: Int,
    val chapterIndex: Int,
    /** href fragment 对应的章节内段落索引；-1 表示跳到章节开头。 */
    val anchorParagraph: Int = -1,
)

/** Adjacent EPUB chapters kept ready for a cross-chapter page curl. */
data class AdjacentChapters(
    val forChapterIndex: Int? = null,
    val previous: ReadingChapter? = null,
    val next: ReadingChapter? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    private val readingItemId: Long,
    private val readingRepository: ReadingRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val settingsRepository: SettingsRepository,
    private val aiRepository: AiRepository,
    private val translationRepository: TranslationRepository,
    private val phraseRepository: PhraseRepository,
) : ViewModel() {

    val readingItem: StateFlow<ReadingItem?> = readingRepository.observeById(readingItemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val readingSettings: StateFlow<ReadingSettings> = settingsRepository.readingSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingSettings())

    val bilingualMode: StateFlow<Boolean> = settingsRepository.bilingualMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val phraseMode: StateFlow<Boolean> = settingsRepository.phraseMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val aiSettings: StateFlow<AiSettings> = settingsRepository.aiSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettings())

    // ---- 章节 / 目录 ----

    val chapters: StateFlow<List<ChapterMeta>> = readingRepository.observeChaptersMeta(readingItemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val toc: StateFlow<List<TocEntry>> = combine(
        readingRepository.observeToc(readingItemId),
        chapters,
    ) { tocItems, chapterMetas ->
        if (tocItems.isNotEmpty()) {
            tocItems.map { TocEntry(it.label, it.level, it.chapterIndex, it.anchorParagraph) }
        } else {
            chapterMetas.map { TocEntry(it.title, 0, it.chapterIndex, -1) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 各章字符数（估算整本书页数用）。 */
    private val _chapterLengths = MutableStateFlow<List<Int>>(emptyList())
    val chapterLengths: StateFlow<List<Int>> = _chapterLengths.asStateFlow()

    /** 跳章目标 (章节索引, code)；code: [TARGET_TOP] / [TARGET_LAST_PAGE] / >=0 段落索引。阅读器消费后清空。 */
    private val _pendingTarget = MutableStateFlow<Pair<Int, Int>?>(null)
    val pendingTarget: StateFlow<Pair<Int, Int>?> = _pendingTarget.asStateFlow()

    fun consumePendingTarget() {
        _pendingTarget.value = null
    }

    private val _chapterIndex = MutableStateFlow<Int?>(null)

    /**
     * Progress writes originate from page turns and must not race a chapter
     * transition. A stale write from the chapter just left would otherwise put
     * its index back into the book record (and sync that bad location).
     */
    private val progressWriteMutex = Mutex()
    private val chapterTransitionMutex = Mutex()
    private var progressGeneration = 0L

    private fun invalidatePendingProgressWrites(): Long {
        progressGeneration += 1
        return progressGeneration
    }

    val currentChapter: StateFlow<ReadingChapter?> = _chapterIndex
        .filterNotNull()
        .mapLatest { index -> readingRepository.getChapter(readingItemId, index) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Preload only the neighbouring chapter records. ReaderScreen lays them out
     * with the current font and viewport so a page curl can reveal the adjoining
     * chapter instead of making the reader tap a separate chapter control.
     */
    val adjacentChapters: StateFlow<AdjacentChapters> = _chapterIndex
        .filterNotNull()
        .mapLatest { index ->
            AdjacentChapters(
                forChapterIndex = index,
                previous = if (index > 0) readingRepository.getChapter(readingItemId, index - 1) else null,
                next = readingRepository.getChapter(readingItemId, index + 1),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdjacentChapters())

    private val _lookup = MutableStateFlow<LookupUiState?>(null)
    val lookup: StateFlow<LookupUiState?> = _lookup.asStateFlow()

    private val _aiResult = MutableStateFlow<AiResultUiState?>(null)
    val aiResult: StateFlow<AiResultUiState?> = _aiResult.asStateFlow()

    private val _sentenceMenu = MutableStateFlow<SentenceMenuState?>(null)
    val sentenceMenu: StateFlow<SentenceMenuState?> = _sentenceMenu.asStateFlow()

    private val _sentenceAi = MutableStateFlow<AiResultUiState?>(null)
    val sentenceAi: StateFlow<AiResultUiState?> = _sentenceAi.asStateFlow()

    init {
        viewModelScope.launch {
            val book = readingRepository.getById(readingItemId)
            if (book != null && book.format == BookFormat.EPUB) {
                _chapterIndex.value = book.currentChapterIndex
            }
            _chapterLengths.value = readingRepository.chapterContentLengths(readingItemId)
        }
    }

    // ---- 查词 ----

    fun onWordTapped(rawWord: String, sentence: String) {
        viewModelScope.launch {
            val cleaned = dictionaryRepository.normalize(rawWord)
            if (cleaned.isEmpty()) return@launch
            val entries = dictionaryRepository.lookup(cleaned)
            dictionaryRepository.recordLookup(cleaned, sentence, readingItemId)
            _aiResult.value = null
            _sentenceMenu.value = null
            _lookup.value = LookupUiState(word = cleaned, sentence = sentence, entries = entries)
        }
    }

    fun dismissLookup() {
        _lookup.value = null
        _aiResult.value = null
    }

    fun saveCurrentWordToVocabulary(note: String = "") {
        val state = _lookup.value ?: return
        viewModelScope.launch {
            val first = state.entries.firstOrNull()
            vocabularyRepository.save(
                VocabularyItem(
                    word = state.word,
                    lemma = first?.lemma ?: state.word,
                    phonetic = first?.phonetic ?: "",
                    partOfSpeech = first?.partOfSpeech ?: "",
                    chineseMeaning = first?.chineseMeaning ?: "",
                    englishDefinition = first?.englishDefinition ?: "",
                    exampleSentence = first?.exampleSentence ?: "",
                    sourceSentence = state.sentence,
                    sourceReadingItemId = readingItemId,
                    sourceBookTitle = readingItem.value?.title.orEmpty(),
                    sourceChapterTitle = currentChapter.value?.title.orEmpty(),
                    note = note,
                ),
            )
            _lookup.value = state.copy(saved = true)
        }
    }

    // ---- 长按句子 → AI 菜单（占位） ----

    fun openSentenceMenu(sentence: String, paragraph: String) {
        _lookup.value = null
        _aiResult.value = null
        _sentenceAi.value = null
        _sentenceMenu.value = SentenceMenuState(sentence = sentence, paragraph = paragraph)
    }

    fun dismissSentenceMenu() {
        _sentenceMenu.value = null
        _sentenceAi.value = null
    }

    /** 查词面板里的 AI 按钮。 */
    fun runAiAnalysis(type: AiAnalysisType, selectedText: String, context: String) {
        viewModelScope.launch {
            _aiResult.value = AiResultUiState(type = type, loading = true)
            val result = try {
                aiRepository.analyze(type, selectedText, context, settingsRepository.aiSettings.first()) { partial ->
                    _aiResult.value = AiResultUiState(type = type, loading = true, text = partial)
                }
            } catch (e: Exception) {
                e.message ?: "出错了"
            }
            _aiResult.value = AiResultUiState(type = type, loading = false, text = result)
        }
    }

    /** 句子菜单里的 AI 按钮。 */
    fun runSentenceAi(type: AiAnalysisType, selectedText: String, context: String) {
        viewModelScope.launch {
            _sentenceAi.value = AiResultUiState(type = type, loading = true)
            val result = try {
                aiRepository.analyze(type, selectedText, context, settingsRepository.aiSettings.first()) { partial ->
                    _sentenceAi.value = AiResultUiState(type = type, loading = true, text = partial)
                }
            } catch (e: Exception) {
                e.message ?: "出错了"
            }
            _sentenceAi.value = AiResultUiState(type = type, loading = false, text = result)
        }
    }

    fun updateReadingSettings(transform: (ReadingSettings) -> ReadingSettings) {
        viewModelScope.launch { settingsRepository.updateReadingSettings(transform) }
    }

    // ---- 双语阅读：段落中文译文 ----

    private val _translations = MutableStateFlow<Map<Int, String>>(emptyMap())
    val translations: StateFlow<Map<Int, String>> = _translations.asStateFlow()

    private val _translating = MutableStateFlow<Set<Int>>(emptySet())
    val translating: StateFlow<Set<Int>> = _translating.asStateFlow()

    // 限制并发翻译数，避免预取时一次性打爆 API / 触发限速。
    private val translateGate = Semaphore(2)

    fun setBilingual(on: Boolean) {
        viewModelScope.launch { settingsRepository.setBilingualMode(on) }
    }

    /** 进入某章双语模式时调用：清空并载入该章已缓存的译文。 */
    fun loadChapterTranslations(chapterIndex: Int) {
        viewModelScope.launch {
            _translating.value = emptySet()
            _translations.value = translationRepository.cachedForChapter(readingItemId, chapterIndex)
        }
    }

    /** 请求翻译某段（缓存优先）；已翻 / 正在翻则跳过。供「当前可见 + 提前几段」预取调用。 */
    fun requestTranslation(chapterIndex: Int, paragraphIndex: Int, english: String) {
        if (english.isBlank()) return
        if (_translations.value.containsKey(paragraphIndex) || _translating.value.contains(paragraphIndex)) return
        _translating.update { it + paragraphIndex }
        viewModelScope.launch {
            try {
                var attempt = 0
                while (true) {
                    try {
                        translateGate.withPermit {
                            if (_translations.value.containsKey(paragraphIndex)) return@withPermit
                            val zh = translationRepository.translateParagraph(
                                readingItemId, chapterIndex, paragraphIndex, english,
                            )
                            _translations.update { it + (paragraphIndex to zh) }
                        }
                        return@launch // 成功（或已被别处翻好）
                    } catch (e: Exception) {
                        attempt++
                        if (attempt >= 3) {
                            // 连续失败：放占位，避免一直卡在"翻译中"。不写库缓存，重载会重试。
                            _translations.update { it + (paragraphIndex to "（翻译失败，可在设置检查 AI / 网络后重读）") }
                            return@launch
                        }
                        delay(800L * attempt) // 退避后重试，抗网络抖动
                    }
                }
            } finally {
                _translating.update { it - paragraphIndex }
            }
        }
    }

    // ---- 词组加粗：AI 识别 + 点开解释 + 收藏 ----

    private val _phrases = MutableStateFlow<Map<Int, List<DetectedPhrase>>>(emptyMap())
    val phrases: StateFlow<Map<Int, List<DetectedPhrase>>> = _phrases.asStateFlow()

    private val _detectingPhrases = MutableStateFlow<Set<Int>>(emptySet())
    val detectingPhrases: StateFlow<Set<Int>> = _detectingPhrases.asStateFlow()

    private val phraseGate = Semaphore(2)

    private val _phrasePopup = MutableStateFlow<DetectedPhrase?>(null)
    val phrasePopup: StateFlow<DetectedPhrase?> = _phrasePopup.asStateFlow()

    fun setPhraseMode(on: Boolean) {
        viewModelScope.launch { settingsRepository.setPhraseMode(on) }
    }

    fun loadChapterPhrases(chapterIndex: Int) {
        viewModelScope.launch {
            _detectingPhrases.value = emptySet()
            _phrases.value = phraseRepository.cachedForChapter(readingItemId, chapterIndex)
        }
    }

    fun requestPhrases(chapterIndex: Int, paragraphIndex: Int, english: String) {
        if (english.isBlank()) return
        if (_phrases.value.containsKey(paragraphIndex) || _detectingPhrases.value.contains(paragraphIndex)) return
        _detectingPhrases.update { it + paragraphIndex }
        viewModelScope.launch {
            try {
                var attempt = 0
                while (true) {
                    try {
                        phraseGate.withPermit {
                            if (_phrases.value.containsKey(paragraphIndex)) return@withPermit
                            val list = phraseRepository.detect(readingItemId, chapterIndex, paragraphIndex, english)
                            _phrases.update { it + (paragraphIndex to list) }
                        }
                        return@launch
                    } catch (e: Exception) {
                        attempt++
                        if (attempt >= 3) {
                            // 连续失败：标记为已尝试（空结果），避免自愈预取无限重发。
                            _phrases.update { it + (paragraphIndex to emptyList()) }
                            return@launch
                        }
                        delay(800L * attempt)
                    }
                }
            } finally {
                _detectingPhrases.update { it - paragraphIndex }
            }
        }
    }

    fun showPhrase(phrase: DetectedPhrase) {
        _phrasePopup.value = phrase
    }

    fun dismissPhrase() {
        _phrasePopup.value = null
    }

    fun savePhraseToVocabulary() {
        val p = _phrasePopup.value ?: return
        viewModelScope.launch {
            vocabularyRepository.save(
                VocabularyItem(
                    word = p.phrase,
                    lemma = p.phrase.lowercase(),
                    phonetic = "",
                    partOfSpeech = p.type.ifBlank { "词组" },
                    chineseMeaning = p.explanation,
                    englishDefinition = "",
                    exampleSentence = "",
                    sourceSentence = "",
                    sourceReadingItemId = readingItemId,
                    sourceBookTitle = readingItem.value?.title.orEmpty(),
                    sourceChapterTitle = currentChapter.value?.title.orEmpty(),
                    note = "",
                ),
            )
            _phrasePopup.value = null
        }
    }

    // ---- 章节导航 ----

    fun goToChapter(index: Int, code: Int = TARGET_TOP) {
        val total = chapters.value.size
        if (total == 0) return
        val clamped = index.coerceIn(0, total - 1)
        if (!chapterTransitionMutex.tryLock()) return
        val generation = invalidatePendingProgressWrites()
        viewModelScope.launch {
            try {
                progressWriteMutex.withLock {
                    if (generation != progressGeneration) return@withLock
                    val chapterProgress = readingRepository.getChapter(readingItemId, clamped)?.progress ?: 0f
                    readingRepository.saveBookChapterState(readingItemId, clamped, (clamped + chapterProgress) / total)
                    _pendingTarget.value = clamped to code
                    _chapterIndex.value = clamped
                }
            } finally {
                chapterTransitionMutex.unlock()
            }
        }
    }

    fun nextChapter(expectedSourceIndex: Int) {
        if (_chapterIndex.value != expectedSourceIndex) return
        goToChapter(expectedSourceIndex + 1, TARGET_TOP)
    }

    fun prevChapter(expectedSourceIndex: Int) {
        if (_chapterIndex.value != expectedSourceIndex) return
        goToChapter(expectedSourceIndex - 1, TARGET_TOP)
    }

    /** 翻页越过章首时，进入上一章并停在其最后一页。 */
    fun prevChapterToLastPage(expectedSourceIndex: Int) {
        if (_chapterIndex.value != expectedSourceIndex) return
        goToChapter(expectedSourceIndex - 1, TARGET_LAST_PAGE)
    }

    /** Complete this chapter and turn directly to the following chapter's first page. */
    fun crossToNextChapter(expectedSourceIndex: Int) {
        val from = _chapterIndex.value ?: return
        if (from != expectedSourceIndex) return
        val total = chapters.value.size
        val target = from + 1
        if (total == 0 || target >= total) return
        if (!chapterTransitionMutex.tryLock()) return
        val generation = invalidatePendingProgressWrites()
        viewModelScope.launch {
            try {
                progressWriteMutex.withLock {
                    if (generation != progressGeneration || _chapterIndex.value != from) return@withLock
                    // Store the real end offset, not the current layout's page start.
                    // That remains correct after font/viewport changes and on another device.
                    val finalOffset = readingRepository.getChapter(readingItemId, from)
                        ?.content
                        ?.let { buildChapterText(it).text.length }
                        ?: 0
                    readingRepository.saveChapterProgress(readingItemId, from, finalOffset, 1f)
                    readingRepository.saveBookChapterState(readingItemId, target, target.toFloat() / total)
                    _pendingTarget.value = target to TARGET_TOP
                    _chapterIndex.value = target
                }
            } finally {
                chapterTransitionMutex.unlock()
            }
        }
    }

    /** Turn directly from a chapter's first page to the prior chapter's last page. */
    fun crossToPreviousChapter(expectedSourceIndex: Int) {
        val from = _chapterIndex.value ?: return
        if (from != expectedSourceIndex) return
        val total = chapters.value.size
        val target = from - 1
        if (total == 0 || target < 0) return
        if (!chapterTransitionMutex.tryLock()) return
        val generation = invalidatePendingProgressWrites()
        viewModelScope.launch {
            try {
                progressWriteMutex.withLock {
                    if (generation != progressGeneration || _chapterIndex.value != from) return@withLock
                    // Do not use a possibly-unready neighbouring page layout here:
                    // the raw chapter length is the stable final reading offset.
                    val finalOffset = readingRepository.getChapter(readingItemId, target)
                        ?.content
                        ?.let { buildChapterText(it).text.length }
                        ?: 0
                    readingRepository.saveChapterProgress(readingItemId, target, finalOffset, 1f)
                    readingRepository.saveBookChapterState(readingItemId, target, (target + 1f) / total)
                    _pendingTarget.value = target to TARGET_LAST_PAGE
                    _chapterIndex.value = target
                }
            } finally {
                chapterTransitionMutex.unlock()
            }
        }
    }

    // ---- 进度（页 / 字符偏移级别） ----

    /**
     * 保存阅读位置。[charOffset] 为当前页起始字符偏移（不随字号变化），
     * [withinChapterFraction] 为章内进度（0..1）。
     */
    fun savePagedProgress(charOffset: Int, withinChapterFraction: Float) {
        val index = _chapterIndex.value
        val total = chapters.value.size
        val generation = progressGeneration
        viewModelScope.launch {
            progressWriteMutex.withLock {
                // A navigation request invalidates UI events from the old page.
                if (generation != progressGeneration || _chapterIndex.value != index) return@withLock
                if (index != null && total > 0) {
                    readingRepository.saveChapterProgress(readingItemId, index, charOffset, withinChapterFraction)
                    readingRepository.saveBookChapterState(
                        readingItemId,
                        index,
                        (index + withinChapterFraction) / total,
                    )
                } else {
                    readingRepository.saveProgress(readingItemId, charOffset, withinChapterFraction)
                }
            }
        }
    }

    companion object {
        const val TARGET_TOP = -1
        const val TARGET_LAST_PAGE = -2

        val Factory = viewModelFactory {
            initializer {
                val id = createSavedStateHandle().get<Long>(Destination.Reader.ARG) ?: 0L
                val c = container
                ReaderViewModel(
                    readingItemId = id,
                    readingRepository = c.readingRepository,
                    dictionaryRepository = c.dictionaryRepository,
                    vocabularyRepository = c.vocabularyRepository,
                    settingsRepository = c.settingsRepository,
                    aiRepository = c.aiRepository,
                    translationRepository = c.translationRepository,
                    phraseRepository = c.phraseRepository,
                )
            }
        }
    }
}
