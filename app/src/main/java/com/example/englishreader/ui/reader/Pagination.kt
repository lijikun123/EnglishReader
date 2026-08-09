package com.example.englishreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/**
 * 把整章纯文本按「当前页面尺寸 + 文字样式」切成若干页，每页是一个字符区间（左闭右开）。
 * 区间连续无重叠，便于按字符偏移恢复阅读位置（不受字号/行距变化影响）。
 *
 * 做法：用 [TextMeasurer] 在给定宽度下测一次整章布局，再按行高度贪心地把行装进每页。
 */
fun paginate(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int,
): List<IntRange> {
    if (text.isEmpty() || widthPx <= 0 || heightPx <= 0) return listOf(0 until text.length)

    val layout = measurer.measure(
        text = AnnotatedString(text),
        style = style,
        softWrap = true,
        constraints = Constraints(maxWidth = widthPx),
    )
    val lineCount = layout.lineCount
    if (lineCount <= 0) return listOf(0 until text.length)

    // 每页起始行
    val pageStartLines = ArrayList<Int>()
    pageStartLines.add(0)
    var pageTop = layout.getLineTop(0)
    for (line in 1 until lineCount) {
        if (layout.getLineBottom(line) - pageTop > heightPx) {
            pageStartLines.add(line)
            pageTop = layout.getLineTop(line)
        }
    }

    // 行号 → 字符区间
    val pages = ArrayList<IntRange>(pageStartLines.size)
    for (i in pageStartLines.indices) {
        val startLine = pageStartLines[i]
        val endLineExclusive = if (i + 1 < pageStartLines.size) pageStartLines[i + 1] else lineCount
        val startOff = layout.getLineStart(startLine)
        val endOff = if (endLineExclusive < lineCount) layout.getLineStart(endLineExclusive) else text.length
        pages.add(startOff until endOff)
    }
    return pages.ifEmpty { listOf(0 until text.length) }
}

/** 找到包含给定字符偏移的页索引；超出末尾则返回最后一页。 */
fun pageIndexForOffset(pages: List<IntRange>, offset: Int): Int {
    if (pages.isEmpty()) return 0
    for (i in pages.indices) {
        if (offset <= pages[i].last) return i
    }
    return pages.lastIndex
}
