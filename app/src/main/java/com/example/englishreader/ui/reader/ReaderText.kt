package com.example.englishreader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.example.englishreader.data.model.DetectedPhrase

const val WORD_TAG = "word"
const val PHRASE_TAG = "phrase"

/** 把正文按空行拆成段落。 */
fun splitIntoParagraphs(content: String): List<String> =
    content.split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** 章节的可分页文本：段落用空行连接的纯文本 + 单词可点击注解 + 每段起始字符偏移。 */
data class ChapterText(
    val text: String,
    val annotated: AnnotatedString,
    /** 第 i 段在 [text] 中的起始字符偏移，用于把 TOC 的段落锚点换算成字符位置。 */
    val paragraphOffsets: List<Int>,
)

fun buildChapterText(content: String): ChapterText {
    val paragraphs = splitIntoParagraphs(content)
    val sb = StringBuilder()
    val offsets = ArrayList<Int>(paragraphs.size)
    for ((i, p) in paragraphs.withIndex()) {
        if (i > 0) sb.append("\n\n")
        offsets.add(sb.length)
        sb.append(p)
    }
    val text = sb.toString()
    return ChapterText(text = text, annotated = buildWordAnnotatedString(text), paragraphOffsets = offsets)
}

/**
 * 双语翻页：把英文段落与其中文译文交错成一段可分页文本（英文 + 换行 + 中文 + 空行）。
 * 英文带单词注解 + 词组加粗（[PHRASE_TAG] 值为 "段号:词组号"）；中文变灰、无注解。
 * 英文段内换行压成空格，中英之间用单个 \n，便于取句不跨进中文。
 */
fun buildBilingualChapter(
    enParagraphs: List<String>,
    translations: Map<Int, String>,
    dimColor: Color,
    phrases: Map<Int, List<DetectedPhrase>>,
): ChapterText {
    val plain = StringBuilder()
    val offsets = ArrayList<Int>(enParagraphs.size)
    val enRanges = ArrayList<IntRange>(enParagraphs.size)
    val cnRanges = ArrayList<IntRange>(enParagraphs.size)
    for (i in enParagraphs.indices) {
        if (i > 0) plain.append("\n\n")
        offsets.add(plain.length)
        val en = enParagraphs[i].replace('\n', ' ').trim()
        val enStart = plain.length
        plain.append(en)
        enRanges.add(enStart until plain.length)
        val cn = translations[i].orEmpty()
        if (cn.isNotBlank()) {
            plain.append('\n')
            val cnStart = plain.length
            plain.append(cn)
            cnRanges.add(cnStart until plain.length)
        } else {
            cnRanges.add(IntRange.EMPTY)
        }
    }
    val text = plain.toString()
    val wordRegex = Regex("[A-Za-z][A-Za-z'’-]*")
    val annotated = buildAnnotatedString {
        append(text)
        for (i in enParagraphs.indices) {
            val r = enRanges[i]
            if (r.isEmpty()) continue
            val seg = text.substring(r.first, r.last + 1)
            for (m in wordRegex.findAll(seg)) {
                addStringAnnotation(WORD_TAG, m.value, r.first + m.range.first, r.first + m.range.last + 1)
            }
            phrases[i].orEmpty().forEachIndexed { pIdx, ph ->
                for (frag in ph.fragments) {
                    if (frag.isBlank()) continue
                    var from = seg.indexOf(frag)
                    while (from >= 0) {
                        val gs = r.first + from
                        val ge = gs + frag.length
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), gs, ge)
                        addStringAnnotation(PHRASE_TAG, "$i:$pIdx", gs, ge)
                        from = seg.indexOf(frag, from + frag.length)
                    }
                }
            }
        }
        for (cr in cnRanges) if (!cr.isEmpty()) addStyle(SpanStyle(color = dimColor), cr.first, cr.last + 1)
    }
    return ChapterText(text, annotated, offsets)
}

/**
 * 在现有英文章节的注解上叠加词组加粗（纯英文模式用）：保持原文文字与坐标不变，
 * 只新增加粗 + [PHRASE_TAG]（值 "段号:词组号"），所以不会重排、不丢位置。
 */
fun phraseAnnotatedFor(chapter: ChapterText, phrases: Map<Int, List<DetectedPhrase>>): AnnotatedString {
    if (phrases.isEmpty()) return chapter.annotated
    val text = chapter.text
    val offs = chapter.paragraphOffsets
    return buildAnnotatedString {
        append(chapter.annotated) // 保留原有单词注解
        for (i in offs.indices) {
            val start = offs[i].coerceIn(0, text.length)
            val end = if (i + 1 < offs.size) offs[i + 1].coerceIn(start, text.length) else text.length
            val seg = text.substring(start, end)
            phrases[i].orEmpty().forEachIndexed { pIdx, ph ->
                for (frag in ph.fragments) {
                    if (frag.isBlank()) continue
                    var from = seg.indexOf(frag)
                    while (from >= 0) {
                        val gs = start + from
                        val ge = gs + frag.length
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), gs, ge)
                        addStringAnnotation(tag = PHRASE_TAG, annotation = "$i:$pIdx", start = gs, end = ge)
                        from = seg.indexOf(frag, from + frag.length)
                    }
                }
            }
        }
    }
}

/** 双语交错文本里取「英文句子」：在 . ! ? 和换行处断句，避免跨进中文行。 */
fun extractEnglishSentence(text: String, pos: Int): String {
    if (text.isEmpty()) return ""
    val enders = setOf('.', '!', '?', '\n')
    var start = pos.coerceIn(0, text.length)
    while (start > 0 && text[start - 1] !in enders) start--
    var end = pos.coerceIn(0, text.length)
    while (end < text.length && text[end] !in enders) end++
    return text.substring(start, end).trim()
}

/**
 * 构造可点击的 AnnotatedString：为每个英文单词加上 [WORD_TAG] 注解。
 * 注解字符串与原文一一对应，因此注解的 start/end 即为原文下标。
 */
fun buildWordAnnotatedString(text: String): AnnotatedString = buildAnnotatedString {
    val wordRegex = Regex("[A-Za-z][A-Za-z'’-]*")
    var last = 0
    for (match in wordRegex.findAll(text)) {
        if (match.range.first > last) append(text.substring(last, match.range.first))
        val start = length
        append(match.value)
        addStringAnnotation(tag = WORD_TAG, annotation = match.value, start = start, end = length)
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

/**
 * 在单词注解基础上，给识别出的词组片段加粗 + [PHRASE_TAG] 注解（值为该词组在列表中的下标）。
 * fragments 按原文原样子串匹配；点击加粗处即可取到对应词组。
 */
fun buildPhraseAnnotatedString(text: String, phrases: List<DetectedPhrase>): AnnotatedString {
    val base = buildWordAnnotatedString(text)
    if (phrases.isEmpty()) return base
    return buildAnnotatedString {
        append(base) // 保留单词注解
        phrases.forEachIndexed { idx, ph ->
            for (frag in ph.fragments) {
                if (frag.isBlank()) continue
                var from = text.indexOf(frag)
                while (from >= 0) {
                    val to = from + frag.length
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), from, to)
                    addStringAnnotation(tag = PHRASE_TAG, annotation = idx.toString(), start = from, end = to)
                    from = text.indexOf(frag, to)
                }
            }
        }
    }
}

/** 取出某字符位置所在的句子（用作查词来源句 / AI 选中文本）。 */
fun extractSentence(text: String, wordStart: Int, wordEnd: Int): String {
    if (text.isEmpty()) return ""
    val enders = setOf('.', '!', '?')
    var start = wordStart.coerceIn(0, text.length)
    while (start > 0 && text[start - 1] !in enders) start--
    var end = wordEnd.coerceIn(0, text.length)
    while (end < text.length && text[end - 1] !in enders) end++
    return text.substring(start, end).trim()
}

/** 取出某字符位置所在的段落（用作「段落精读」选中文本）。段落以空行分隔。 */
fun extractParagraph(text: String, pos: Int): String {
    if (text.isEmpty()) return ""
    val p = pos.coerceIn(0, text.length - 1)
    val before = text.lastIndexOf("\n\n", p).let { if (it < 0) 0 else it + 2 }
    val after = text.indexOf("\n\n", p).let { if (it < 0) text.length else it }
    return text.substring(before.coerceAtMost(after), after).trim()
}
