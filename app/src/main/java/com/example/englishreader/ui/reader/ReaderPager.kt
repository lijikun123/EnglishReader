package com.example.englishreader.ui.reader

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.englishreader.ai.AiAnalysisType

/**
 * 单页正文 + 触摸交互：
 *  - 点击左 1/3：上一页；右 1/3：下一页；中间：命中单词则查词
 *  - 横向滑动：左滑下一页、右滑上一页
 *  - 长按：选中整句弹 AI 菜单；长按后拖动可自由选择任意文字再翻译
 */
@Composable
fun ReaderPageContent(
    pageText: AnnotatedString,
    chapterText: String,
    pageStartOffset: Int,
    style: TextStyle,
    contentMaxWidth: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    onWord: (word: String, sentence: String) -> Unit,
    onLongPress: (sentence: String, paragraph: String) -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
    onPhrase: (paragraphIndex: Int, phraseIndex: Int) -> Unit = { _, _ -> },
    bilingual: Boolean = false,
    // 双页模式：本列在整张跨页里占的区间（起点分数 + 宽度分数），空白点击的三等分按整屏算
    zoneFractionStart: Float = 0f,
    zoneFraction: Float = 1f,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 长按后拖动 = 自由选择任意文字并高亮；selRange 为页内字符闭区间，null=未在选择
    var selAnchor by remember { mutableIntStateOf(-1) }
    var selMoved by remember { mutableStateOf(false) }
    var selRange by remember { mutableStateOf<IntRange?>(null) }
    val hlColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val displayText = remember(pageText, selRange, hlColor) {
        val r = selRange
        if (r == null) {
            pageText
        } else buildAnnotatedString {
            append(pageText)
            val a = r.first.coerceIn(0, pageText.length)
            val b = (r.last + 1).coerceIn(a, pageText.length)
            if (b > a) addStyle(SpanStyle(background = hlColor), a, b)
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = displayText,
            style = style,
            onTextLayout = { layout = it },
            modifier = Modifier
                .widthIn(max = contentMaxWidth)
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                .pointerInput(pageText) {
                    detectTapGestures(
                        onTap = { pos ->
                            // 命中检测：仅当点击点真正落在文字行内才算点到（避免末行下方大片空白被吸附到上方）
                            val off = layout?.offsetIfOnGlyph(pos)
                            val phraseAnn = if (off != null) pageText.getStringAnnotations(PHRASE_TAG, off, off).firstOrNull() else null
                            val wordAnn = if (off != null) pageText.getStringAnnotations(WORD_TAG, off, off).firstOrNull() else null
                            when {
                                phraseAnn != null -> {
                                    val parts = phraseAnn.item.split(":")
                                    val p = parts.getOrNull(0)?.toIntOrNull()
                                    val idx = parts.getOrNull(1)?.toIntOrNull()
                                    if (p != null && idx != null) onPhrase(p, idx) else onToggleControls()
                                }
                                wordAnn != null && off != null -> {
                                    val co = pageStartOffset + off
                                    val sentence = if (bilingual) extractEnglishSentence(chapterText, co) else extractSentence(chapterText, co, co)
                                    onWord(wordAnn.item, sentence)
                                }
                                else -> {
                                    // 点到空白：按「整屏」三等分（双页时本列只占一半，换算成全局位置再判断）
                                    val gx = zoneFractionStart + (pos.x / size.width.toFloat()) * zoneFraction
                                    when {
                                        gx < 1f / 3f -> onPrevPage()
                                        gx > 2f / 3f -> onNextPage()
                                        else -> onToggleControls()
                                    }
                                }
                            }
                        },
                    )
                }
                .pointerInput(pageText) {
                    // 长按不拖 = 选中整句（旧行为）；长按后拖动 = 自由选择任意文字，松手弹翻译菜单
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            val off = layout?.offsetIfOnGlyph(pos) ?: -1
                            selAnchor = off
                            selMoved = false
                            selRange = if (off >= 0) off..off else null
                        },
                        onDrag = { change, _ ->
                            val lay = layout
                            val a = selAnchor
                            if (lay != null && a >= 0) {
                                val off = lay.getOffsetForPosition(change.position)
                                selMoved = true
                                selRange = minOf(a, off)..maxOf(a, off)
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            val r = selRange
                            val a = selAnchor
                            selRange = null
                            selAnchor = -1
                            if (r != null) {
                                val s = r.first.coerceIn(0, pageText.length)
                                val e = (r.last + 1).coerceIn(s, pageText.length)
                                if (selMoved && e - s >= 2) {
                                    val sel = pageText.subSequence(s, e).text.trim()
                                    if (sel.isNotBlank()) {
                                        onLongPress(sel, extractParagraph(chapterText, pageStartOffset + (s + e) / 2))
                                    }
                                } else if (a >= 0) {
                                    val co = pageStartOffset + a
                                    val sentence = if (bilingual) extractEnglishSentence(chapterText, co) else extractSentence(chapterText, co, co)
                                    onLongPress(sentence, extractParagraph(chapterText, co))
                                }
                            }
                            selMoved = false
                        },
                        onDragCancel = {
                            selRange = null
                            selAnchor = -1
                            selMoved = false
                        },
                    )
                },
        )
    }
}

/**
 * 返回点击点真正落在文字字形上的字符偏移；若点在末行下方、或某行行尾右侧/行首左侧的空白处，返回 null。
 * 用于避免“点击短页下方大片空白”被 getOffsetForPosition 吸附到上方正文最后一个词而误触发查词。
 */
private fun TextLayoutResult.offsetIfOnGlyph(pos: Offset): Int? {
    if (lineCount <= 0) return null
    if (pos.y < getLineTop(0) || pos.y > getLineBottom(lineCount - 1)) return null
    val line = getLineForVerticalPosition(pos.y)
    if (pos.y > getLineBottom(line)) return null // 落在行与行之间的段落间距空白
    if (pos.x < getLineLeft(line) || pos.x > getLineRight(line)) return null
    return getOffsetForPosition(pos)
}

/** 阅读器底部条：页码/进度 + 上一章/目录/下一章（按钮作为备用）。 */
@Composable
fun ReaderBottomBar(
    chapterPage: Int,
    chapterPages: Int,
    progressLabel: String,
    showChapterNav: Boolean,
    canPrevChapter: Boolean,
    canNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onToc: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (showChapterNav) {
                IconButton(onClick = onPrevChapter, enabled = canPrevChapter) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一章")
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("本章 $chapterPage / $chapterPages", style = MaterialTheme.typography.labelLarge)
                Text(
                    progressLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showChapterNav) {
                IconButton(onClick = onNextChapter, enabled = canNextChapter) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一章")
                }
            }
        }
    }
}

/**
 * 长按句子弹出的 AI 操作菜单（第一版结果为 Stub 占位）。
 * 显示选中英文原文 + 五个操作（翻译/语法/长难句/段落精读/复制）。
 */
@Composable
fun SentenceAiMenu(
    selectedText: String,
    paragraph: String,
    aiResult: AiResultUiState?,
    onAction: (type: AiAnalysisType, selectedText: String, context: String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("选中文本", style = MaterialTheme.typography.titleSmall)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Text(
                selectedText.ifBlank { "（未识别到句子）" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }

        Text(
            "AI 助手",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiActionButton("翻译选中") { onAction(AiAnalysisType.TRANSLATE_SENTENCE, selectedText, selectedText) }
            AiActionButton("语法分析") { onAction(AiAnalysisType.ANALYZE_GRAMMAR, selectedText, selectedText) }
            AiActionButton("长难句拆解") { onAction(AiAnalysisType.BREAK_DOWN_SENTENCE, selectedText, selectedText) }
            AiActionButton("段落精读") { onAction(AiAnalysisType.CLOSE_READING_PARAGRAPH, paragraph, paragraph) }
            AiActionButton("复制文本") { onCopy(selectedText) }
        }

        if (aiResult != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        aiResult.type.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (aiResult.loading && aiResult.text.isEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Text(aiResult.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AiActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}
