package com.example.englishreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishreader.ai.AiAnalysisType
import com.example.englishreader.data.local.entity.BookFormat
import com.example.englishreader.data.local.entity.ReadingChapter
import com.example.englishreader.data.model.DetectedPhrase
import com.example.englishreader.data.model.ReadingSettings
import kotlin.math.ceil

@Composable
fun ReaderScreen(
    useTwoPane: Boolean,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel(factory = ReaderViewModel.Factory),
) {
    val item by viewModel.readingItem.collectAsStateWithLifecycle()
    val readingItemLoaded by viewModel.readingItemLoaded.collectAsStateWithLifecycle()
    val settings by viewModel.readingSettings.collectAsStateWithLifecycle()
    val lookup by viewModel.lookup.collectAsStateWithLifecycle()
    val aiResult by viewModel.aiResult.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val currentChapter by viewModel.currentChapter.collectAsStateWithLifecycle()
    val adjacentChapters by viewModel.adjacentChapters.collectAsStateWithLifecycle()
    val toc by viewModel.toc.collectAsStateWithLifecycle()
    val pendingTarget by viewModel.pendingTarget.collectAsStateWithLifecycle()
    val chapterLengths by viewModel.chapterLengths.collectAsStateWithLifecycle()
    val sentenceMenu by viewModel.sentenceMenu.collectAsStateWithLifecycle()
    val sentenceAi by viewModel.sentenceAi.collectAsStateWithLifecycle()
    val phrasePopup by viewModel.phrasePopup.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val isEpub = item?.format == BookFormat.EPUB
    val loading = !readingItemLoaded || (isEpub && currentChapter == null)

    val text = if (isEpub) currentChapter?.content else item?.content
    val restorePosition = if (isEpub) currentChapter?.lastReadPosition ?: 0 else item?.lastReadPosition ?: 0
    val restoreKey = if (isEpub) currentChapter?.id else item?.id
    val currentChapterIndex = currentChapter?.chapterIndex ?: 0
    // Neighbours are loaded independently; never curl a new current chapter
    // into a stale neighbour left over from the preceding chapter.
    val matchingAdjacentChapters = adjacentChapters.takeIf {
        it.forChapterIndex == currentChapterIndex
    } ?: AdjacentChapters()

    // 标题清洗：去掉历史数据里残留的 ￼，空则回退
    val rawTitle = if (isEpub) currentChapter?.title else item?.title
    val headerTitle = rawTitle.orEmpty().replace("￼", "").trim()
        .ifBlank { if (isEpub) "第 ${currentChapterIndex + 1} 章" else item?.title.orEmpty().ifBlank { "阅读" } }

    val chapter = remember(restoreKey, text) { text?.let(::buildChapterText) }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val style = remember(settings.fontSize, settings.lineHeight, onSurface) {
        TextStyle(
            fontSize = settings.fontSize.sp,
            lineHeight = (settings.fontSize * settings.lineHeight).sp,
            color = onSurface,
        )
    }

    val onWordAiAction: (AiAnalysisType) -> Unit = onAi@{ type ->
        val current = lookup ?: return@onAi
        val (selected, ctx) = when (type) {
            AiAnalysisType.EXPLAIN_WORD_IN_CONTEXT -> current.word to current.sentence
            else -> current.sentence to current.sentence
        }
        viewModel.runAiAnalysis(type, selected, ctx)
    }

    // 沉浸式：不再用顶部 AppBar / 底部大栏，留白交给正文
    Scaffold { padding ->
        when {
            loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            item == null -> {
                DeletedBookScreen(
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }

            chapter == null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("这本书暂时无法打开")
                }
            }

            useTwoPane -> {
                // 平板/大屏：正文占满全屏；查词面板点词才从右侧浮出（覆盖在正文上，不挤压、不重排）
                Box(Modifier.fillMaxSize().padding(padding)) {
                    ReaderArea(
                        chapter = chapter, settings = settings, style = style,
                        previousChapter = matchingAdjacentChapters.previous, nextChapter = matchingAdjacentChapters.next,
                        restoreKey = restoreKey, restorePosition = restorePosition,
                        isEpub = isEpub, currentChapterIndex = currentChapterIndex,
                        chapterCount = chapters.size, chapterLengths = chapterLengths,
                        pendingTarget = pendingTarget, viewModel = viewModel,
                        headerTitle = headerTitle, onBack = onBack,
                        onToc = { showToc = true }, onSettings = { showSettings = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (lookup != null) {
                        // 平板上的查词面板不是 BottomSheet，因此需要自己提供一层
                        // 可点击的遮罩；点正文空白处即可关闭，而面板本身仍正常接收点击。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = viewModel::dismissLookup),
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(360.dp),
                            tonalElevation = 2.dp,
                            shadowElevation = 12.dp,
                        ) {
                            LookupContent(
                                lookup = lookup, aiResult = aiResult,
                                onSave = { viewModel.saveCurrentWordToVocabulary() },
                                onAiAction = onWordAiAction,
                                onClose = viewModel::dismissLookup,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            else -> {
                ReaderArea(
                    chapter = chapter, settings = settings, style = style,
                    previousChapter = matchingAdjacentChapters.previous, nextChapter = matchingAdjacentChapters.next,
                    restoreKey = restoreKey, restorePosition = restorePosition,
                    isEpub = isEpub, currentChapterIndex = currentChapterIndex,
                    chapterCount = chapters.size, chapterLengths = chapterLengths,
                    pendingTarget = pendingTarget, viewModel = viewModel,
                    headerTitle = headerTitle, onBack = onBack,
                    onToc = { showToc = true }, onSettings = { showSettings = true },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
                if (lookup != null) {
                    ModalBottomSheet(onDismissRequest = viewModel::dismissLookup) {
                        LookupContent(
                            lookup = lookup, aiResult = aiResult,
                            onSave = { viewModel.saveCurrentWordToVocabulary() },
                            onAiAction = onWordAiAction,
                        )
                    }
                }
            }
        }

        sentenceMenu?.let { menu ->
            ModalBottomSheet(onDismissRequest = viewModel::dismissSentenceMenu) {
                SentenceAiMenu(
                    selectedText = menu.sentence, paragraph = menu.paragraph, aiResult = sentenceAi,
                    onAction = { type, sel, ctx -> viewModel.runSentenceAi(type, sel, ctx) },
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                )
            }
        }

        phrasePopup?.let { popup ->
            ModalBottomSheet(onDismissRequest = viewModel::dismissPhrase) {
                PhraseSheet(
                    phrase = popup.phrase,
                    saved = popup.saved,
                    onSave = viewModel::savePhraseToVocabulary,
                )
            }
        }

        if (showToc) {
            ModalBottomSheet(onDismissRequest = { showToc = false }) {
                TableOfContents(
                    entries = toc, currentIndex = currentChapterIndex,
                    onSelect = { entry ->
                        val code = if (entry.anchorParagraph >= 0) entry.anchorParagraph else ReaderViewModel.TARGET_TOP
                        viewModel.goToChapter(entry.chapterIndex, code)
                        showToc = false
                    },
                )
            }
        }

        if (showSettings) {
            ModalBottomSheet(onDismissRequest = { showSettings = false }) {
                ReaderSettingsSheet(settings = settings, onChange = { transform -> viewModel.updateReadingSettings(transform) })
            }
        }
    }
}

@Composable
private fun DeletedBookScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("书籍已删除", style = MaterialTheme.typography.titleLarge)
            Text(
                "它可能已在另一台设备上删除，已从当前书架移除。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onBack, modifier = Modifier.padding(top = 20.dp)) {
                Text("返回书架")
            }
        }
    }
}

@Composable
private fun ReaderArea(
    chapter: ChapterText,
    settings: ReadingSettings,
    style: TextStyle,
    previousChapter: ReadingChapter?,
    nextChapter: ReadingChapter?,
    restoreKey: Any?,
    restorePosition: Int,
    isEpub: Boolean,
    currentChapterIndex: Int,
    chapterCount: Int,
    chapterLengths: List<Int>,
    pendingTarget: Pair<Int, Int>?,
    viewModel: ReaderViewModel,
    headerTitle: String,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val hPad = 18.dp
        val vPad = 6.dp
        val footerH = 26.dp
        val footerPx = with(density) { footerH.roundToPx() }
        val hPadPx = with(density) { hPad.roundToPx() }
        val vPadPx = with(density) { vPad.roundToPx() }

        // 宽横屏（≥840dp 且横向，平板横屏/手机横屏）：像纸书摊开一样左右双页；否则单页
        val columns = if (maxWidth >= 840.dp && maxWidth > maxHeight) 2 else 1
        val contentMaxWidth = if (columns == 2) {
            minOf(settings.readingWidth.dp * 2, maxWidth)
        } else {
            minOf(settings.readingWidth.dp, maxWidth)
        }
        // 双页时每列按半宽分页排版，与渲染列宽严格一致
        val pageWidthPx = (with(density) { contentMaxWidth.roundToPx() } / columns - 2 * hPadPx).coerceAtLeast(1)
        val pageHeightPx = (constraints.maxHeight - footerPx - 2 * vPadPx).coerceAtLeast(1)

        val measurer = rememberTextMeasurer()
        val pages = remember(chapter.text, style, pageWidthPx, pageHeightPx) {
            paginate(measurer, chapter.text, style, pageWidthPx, pageHeightPx)
        }
        // Adjacent EPUB chapters are laid out with exactly the same geometry.
        // Their edge spread becomes the page beneath a cross-chapter curl.
        val previousChapterText = remember(previousChapter?.id, previousChapter?.content) {
            previousChapter?.let { buildChapterText(it.content) }
        }
        val nextChapterText = remember(nextChapter?.id, nextChapter?.content) {
            nextChapter?.let { buildChapterText(it.content) }
        }
        val previousPages = remember(previousChapterText?.text, style, pageWidthPx, pageHeightPx) {
            previousChapterText?.let { paginate(measurer, it.text, style, pageWidthPx, pageHeightPx) }.orEmpty()
        }
        val nextPages = remember(nextChapterText?.text, style, pageWidthPx, pageHeightPx) {
            nextChapterText?.let { paginate(measurer, it.text, style, pageWidthPx, pageHeightPx) }.orEmpty()
        }

        var showControls by rememberSaveable { mutableStateOf(false) }
        val bilingual by viewModel.bilingualMode.collectAsStateWithLifecycle()
        val translations by viewModel.translations.collectAsStateWithLifecycle()
        val translating by viewModel.translating.collectAsStateWithLifecycle()
        val phraseMode by viewModel.phraseMode.collectAsStateWithLifecycle()
        val phrases by viewModel.phrases.collectAsStateWithLifecycle()

        // 每章一个仿真翻书（page curl）阅读器，用 key 让换章时重建并定位到正确初始页
        key(restoreKey) {
            LaunchedEffect(bilingual, phraseMode) {
                if (bilingual) viewModel.loadChapterTranslations(currentChapterIndex)
                if (phraseMode) viewModel.loadChapterPhrases(currentChapterIndex)
            }
            val pendingForThis = pendingTarget?.takeIf { it.first == currentChapterIndex }
            val initialOffset = remember {
                when {
                    pendingForThis == null -> restorePosition.coerceAtLeast(0)
                    pendingForThis.second == ReaderViewModel.TARGET_LAST_PAGE -> pages.lastOrNull()?.first ?: 0
                    pendingForThis.second >= 0 -> chapter.paragraphOffsets.getOrElse(pendingForThis.second) { 0 }
                    else -> 0
                }
            }
            LaunchedEffect(Unit) { if (pendingForThis != null) viewModel.consumePendingTarget() }

            // 当前阅读位置：页起始字符偏移，不随字号变化；翻页时更新，重排后据此定位
            var anchorOffset by remember { mutableIntStateOf(initialOffset) }
            var currentPage by remember { mutableIntStateOf(pageIndexForOffset(pages, initialOffset)) }

            val canCrossNext = isEpub && currentChapterIndex < chapterCount - 1
            val canCrossPrev = isEpub && currentChapterIndex > 0
            // Save cross-chapter locations in one ordered ViewModel coroutine.
            // This covers one-page chapters too, which otherwise never emit an
            // in-chapter page-change event at their final page.
            val onCrossNext = {
                if (canCrossNext) {
                    viewModel.crossToNextChapter(currentChapterIndex)
                }
            }
            val onCrossPrev = {
                if (canCrossPrev) {
                    viewModel.crossToPreviousChapter(currentChapterIndex)
                }
            }
            val crossNextEnglishSheet = remember(nextChapterText, nextPages, columns) {
                edgeSheet(nextChapterText, nextPages, columns, first = true)
            }
            val crossPrevEnglishSheet = remember(previousChapterText, previousPages, columns) {
                edgeSheet(previousChapterText, previousPages, columns, first = false)
            }

            val pageCountSafe = pages.size.coerceAtLeast(1)
            // 双页时 currentPage 是这张纸的左页，进度按看到的右页算
            val viewedPage = (currentPage + columns).coerceAtMost(pageCountSafe)
            val bookPercent = if (isEpub && chapterCount > 0) {
                (((currentChapterIndex + viewedPage.toFloat() / pageCountSafe) / chapterCount) * 100).toInt().coerceIn(0, 100)
            } else {
                ((viewedPage.toFloat() / pageCountSafe) * 100).toInt().coerceIn(0, 100)
            }
            // 页码标签：单页 "3"；双页 "3-4"（末纸只剩一页时仍显示单页码）
            fun pageLabel(first: Int, total: Int): String =
                if (columns == 2 && first + 2 <= total) "${first + 1}-${first + 2}" else "${first + 1}"

            // ---- 双语翻页：英文段落 + AI 译文交错成可翻页的文本（整章译完才成页，避免翻页重排）----
            val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
            val enParagraphs = remember(chapter) {
                val offs = chapter.paragraphOffsets
                val t = chapter.text
                if (offs.isEmpty()) {
                    listOf(t.trim())
                } else {
                    offs.indices.map { i ->
                        val s = offs[i].coerceIn(0, t.length)
                        val e = if (i + 1 < offs.size) offs[i + 1].coerceIn(s, t.length) else t.length
                        t.substring(s, e).trim()
                    }
                }
            }
            // 自愈式预取：每当译文/词组有进展就重扫，补发仍缺失的段——个别段失败/漏发会自动补上，
            // 不再需要手动切「双语/原文」才继续（requestXxx 内部对进行中的段有去重，不会重复请求）。
            LaunchedEffect(bilingual, phraseMode, enParagraphs, translations, phrases) {
                enParagraphs.forEachIndexed { i, p ->
                    if (bilingual && !translations.containsKey(i)) viewModel.requestTranslation(currentChapterIndex, i, p)
                    if (phraseMode && !phrases.containsKey(i)) viewModel.requestPhrases(currentChapterIndex, i, p)
                }
            }
            val biReady = bilingual && enParagraphs.isNotEmpty() &&
                enParagraphs.indices.all { translations.containsKey(it) }
            val biChapter = remember(biReady, translations, phraseMode, phrases, enParagraphs, dimColor) {
                if (biReady) {
                    buildBilingualChapter(enParagraphs, translations, dimColor, if (phraseMode) phrases else emptyMap())
                } else {
                    null
                }
            }
            val biPages = remember(biChapter, style, pageWidthPx, pageHeightPx) {
                biChapter?.let { paginate(measurer, it.text, style, pageWidthPx, pageHeightPx) }
            }
            val displayPageCount = if (bilingual) (biPages?.size ?: 0) else pages.size

            // 纯英文模式也能词组加粗：把加粗叠到原文注解上（文字坐标不变、不重排、不丢位置）
            val enAnnotated = remember(chapter, phraseMode, phrases) {
                if (phraseMode) phraseAnnotatedFor(chapter, phrases) else chapter.annotated
            }

            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    // 字号 / 尺寸变化 → pages 重排 → 用 key 重建翻书器并按 anchorOffset 定位回原页，避免位置丢失
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (bilingual) {
                            if (biReady && biChapter != null && biPages != null && biPages.isNotEmpty()) {
                                val biParaOffsets = biChapter.paragraphOffsets
                                val initBiOffset = biParaOffsets.getOrElse(
                                    chapter.paragraphOffsets.indexOfLast { it <= anchorOffset }.coerceAtLeast(0),
                                ) { 0 }
                                key(biPages) {
                                    PageCurlReader(
                                        pages = biPages,
                                        annotated = biChapter.annotated,
                                        chapterText = biChapter.text,
                                        style = style,
                                        contentMaxWidth = contentMaxWidth,
                                        horizontalPadding = hPad,
                                        verticalPadding = vPad,
                                        initialPage = pageIndexForOffset(biPages, initBiOffset),
                                        isEpub = isEpub,
                                        onPageChanged = { p ->
                                            currentPage = p
                                            // 同英文分支：仅真实翻纸才更新锚点，重建不降级锚点
                                            val anchorPara = chapter.paragraphOffsets.indexOfLast { it <= anchorOffset }.coerceAtLeast(0)
                                            val anchorBiOff = biParaOffsets.getOrElse(anchorPara) { 0 }
                                            val anchorSheet = pageIndexForOffset(biPages, anchorBiOff) / columns
                                            if (p / columns != anchorSheet) {
                                                val biOff = biPages.getOrNull(p)?.first ?: 0
                                                val paraIdx = biParaOffsets.indexOfLast { it <= biOff }.coerceAtLeast(0)
                                                val enOff = chapter.paragraphOffsets.getOrElse(paraIdx) { 0 }
                                                anchorOffset = enOff
                                                viewModel.savePagedProgress(
                                                    enOff,
                                                    ((p + columns).toFloat() / biPages.size).coerceAtMost(1f),
                                                )
                                            }
                                        },
                                        onCrossNext = onCrossNext,
                                        onCrossPrev = onCrossPrev,
                                        canCrossNext = canCrossNext,
                                        canCrossPrev = canCrossPrev,
                                        // The adjacent chapter's bilingual layout may not be ready;
                                        // PageCurlReader falls back to one continuous edge swipe.
                                        crossNextSheet = null,
                                        crossPrevSheet = null,
                                        onWord = viewModel::onWordTapped,
                                        onLongPress = { sentence, paragraph -> viewModel.openSentenceMenu(sentence, paragraph) },
                                        onToggleControls = { showControls = !showControls },
                                        onPhrase = { pIdx, phIdx ->
                                            phrases[pIdx]?.getOrNull(phIdx)?.let { phrase ->
                                                viewModel.showPhrase(phrase, enParagraphs.getOrNull(pIdx).orEmpty())
                                            }
                                        },
                                        bilingual = true,
                                        columns = columns,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            } else {
                                BilingualTranslatingProgress(
                                    done = translations.keys.count { it in enParagraphs.indices },
                                    total = enParagraphs.size,
                                    onToggleControls = { showControls = !showControls },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else {
                            key(pages) {
                                PageCurlReader(
                                    pages = pages,
                                    annotated = enAnnotated,
                                    chapterText = chapter.text,
                                    style = style,
                                    contentMaxWidth = contentMaxWidth,
                                    horizontalPadding = hPad,
                                    verticalPadding = vPad,
                                    initialPage = pageIndexForOffset(pages, anchorOffset),
                                    isEpub = isEpub,
                                    onPageChanged = { p ->
                                        currentPage = p
                                        // 只有真正翻到别的「纸」才更新锚点并保存；几何变化（旋转/退出动画）
                                        // 引起的重建会按锚点回到原页，此时不得把锚点取整降级，否则位置逐次前移
                                        val anchorSheet = pageIndexForOffset(pages, anchorOffset) / columns
                                        if (p / columns != anchorSheet && pages.isNotEmpty()) {
                                            val off = pages.getOrNull(p)?.first ?: 0
                                            anchorOffset = off
                                            viewModel.savePagedProgress(
                                                off,
                                                ((p + columns).toFloat() / pages.size).coerceAtMost(1f),
                                            )
                                        }
                                    },
                                    onCrossNext = onCrossNext,
                                    onCrossPrev = onCrossPrev,
                                    canCrossNext = canCrossNext,
                                    canCrossPrev = canCrossPrev,
                                    crossNextSheet = crossNextEnglishSheet,
                                    crossPrevSheet = crossPrevEnglishSheet,
                                    onWord = viewModel::onWordTapped,
                                    onLongPress = { sentence, paragraph -> viewModel.openSentenceMenu(sentence, paragraph) },
                                    onToggleControls = { showControls = !showControls },
                                    onPhrase = { pIdx, phIdx ->
                                        phrases[pIdx]?.getOrNull(phIdx)?.let { phrase ->
                                            viewModel.showPhrase(phrase, enParagraphs.getOrNull(pIdx).orEmpty())
                                        }
                                    },
                                    columns = columns,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    // 极简底部：左 = 章节名，右 = 页码 / 进度（点一下可呼出工具栏）
                    Row(
                        modifier = Modifier.fillMaxWidth().height(footerH)
                            .clickable { showControls = !showControls }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Text(
                            text = when {
                                bilingual && biReady -> "双语 · ${pageLabel(currentPage, displayPageCount)} / $displayPageCount"
                                bilingual -> "双语 · 翻译中…"
                                else -> "${pageLabel(currentPage, pages.size)} / ${pages.size}　·　全书 $bookPercent%"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                // 点击中间空白切换的工具栏（覆盖在正文上，不影响分页）
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                            Text(
                                headerTitle, style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            )
                            TextButton(onClick = { viewModel.setBilingual(!bilingual) }) {
                                Text(if (bilingual) "原文" else "双语")
                            }
                            TextButton(onClick = { viewModel.setPhraseMode(!phraseMode) }) {
                                Text(if (phraseMode) "词组·开" else "词组")
                            }
                            if (isEpub) {
                                IconButton(onClick = { showControls = false; onToc() }) {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "目录")
                                }
                            }
                            IconButton(onClick = { showControls = false; onSettings() }) {
                                Icon(Icons.Filled.Settings, contentDescription = "阅读设置")
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showControls && !bilingual,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    ReaderBottomBar(
                        chapterPage = currentPage + 1,
                        chapterPages = pages.size,
                        progressLabel = progressLabel(
                            isEpub, chapterLengths, currentChapterIndex, chapterCount,
                            chapter.text.length, currentPage, pages.size,
                        ),
                        showChapterNav = isEpub,
                        canPrevChapter = currentChapterIndex > 0,
                        canNextChapter = currentChapterIndex < chapterCount - 1,
                        onPrevChapter = { viewModel.prevChapter(currentChapterIndex) },
                        onNextChapter = { viewModel.nextChapter(currentChapterIndex) },
                        onToc = onToc,
                    )
                }
            }
        }
    }
}

/** The first or last single/double-page spread from an adjacent chapter. */
private fun edgeSheet(
    chapter: ChapterText?,
    pages: List<IntRange>,
    columns: Int,
    first: Boolean,
): List<AnnotatedString?>? {
    if (chapter == null || pages.isEmpty()) return null
    val cols = columns.coerceAtLeast(1)
    val firstPage = if (first) 0 else (pages.lastIndex / cols) * cols
    return List(cols) { column ->
        val range = pages.getOrNull(firstPage + column) ?: return@List null
        val start = range.first.coerceIn(0, chapter.annotated.length)
        val end = (range.last + 1).coerceIn(start, chapter.annotated.length)
        chapter.annotated.subSequence(start, end)
    }
}

/** 双语翻页时，整章译完前显示的进度页（点一下可呼出工具栏，方便切回原文）。 */
@Composable
private fun BilingualTranslatingProgress(
    done: Int,
    total: Int,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize().clickable { onToggleControls() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            CircularProgressIndicator()
            Text(
                "正在翻译本章…  $done / $total 段",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "翻过会缓存，下次秒开；也会提前翻下一章",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 词组弹窗：词组 + 中文解释 + 收藏到生词本。 */
@Composable
private fun PhraseSheet(phrase: DetectedPhrase, saved: Boolean, onSave: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(phrase.phrase, style = MaterialTheme.typography.titleMedium)
            if (phrase.type.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(start = 10.dp),
                ) {
                    Text(
                        phrase.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Text(
            text = phrase.explanation.ifBlank { "（暂无解释）" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
        Button(
            onClick = onSave,
            enabled = !saved,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(if (saved) "已收藏到生词本" else "收藏词组")
        }
        Text(
            text = "词组会存进「生词本」，来源标注书名 / 章节。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 整本书进度 / 估算页数标签（单文件精确，EPUB 整本为估算，标注「约」）。 */
private fun progressLabel(
    isEpub: Boolean,
    chapterLengths: List<Int>,
    chapterIndex: Int,
    chapterCount: Int,
    chapterChars: Int,
    currentPage: Int,
    pageCount: Int,
): String {
    if (pageCount <= 0) return ""
    return if (isEpub && chapterLengths.size == chapterCount && chapterCount > 0) {
        val charsPerPage = (chapterChars.toFloat() / pageCount).coerceAtLeast(1f)
        val totalChars = chapterLengths.sum()
        val charsBefore = chapterLengths.take(chapterIndex).sum()
        val estTotal = ceil(totalChars / charsPerPage).toInt().coerceAtLeast(1)
        val estCurrent = (ceil(charsBefore / charsPerPage).toInt() + currentPage + 1).coerceIn(1, estTotal)
        val percent = (((chapterIndex + (currentPage + 1f) / pageCount) / chapterCount) * 100).toInt().coerceIn(0, 100)
        "本章 ${currentPage + 1}/$pageCount · 全书 $percent% · 约第 $estCurrent/$estTotal 页"
    } else {
        val percent = (((currentPage + 1f) / pageCount) * 100).toInt().coerceIn(0, 100)
        "本章 ${currentPage + 1}/$pageCount · 全书 $percent%"
    }
}

@Composable
private fun TableOfContents(
    entries: List<TocEntry>,
    currentIndex: Int,
    onSelect: (TocEntry) -> Unit,
) {
    Text(
        text = "目录（${entries.size}）",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
        itemsIndexed(entries) { _, entry ->
            val selected = entry.chapterIndex == currentIndex
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(entry) }
                    .padding(start = (20 + entry.level * 16).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            )
            HorizontalDivider()
        }
    }
}
