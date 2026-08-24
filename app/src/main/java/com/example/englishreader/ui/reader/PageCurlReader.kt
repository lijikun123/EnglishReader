package com.example.englishreader.ui.reader

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sin

private const val EDGE_SWIPE_FRACTION = 0.12f
private const val MIN_EDGE_SWIPE_PX = 48f

/**
 * 仿真翻书（page curl）阅读器：每页先离屏渲染成位图，翻页时用 drawBitmapMesh 把“正在翻的那一页”
 * 按圆柱卷起变形，露出下面一页 —— 跟手拖动、松手吸附/回弹。
 *
 * 取舍（已与用户确认）：卷页过程用的是页面位图，所以**只有页面平铺（未翻动）时**才显示可点选的实时
 * 文本（查词/长按生效）；翻动过程中显示的是位图卷页动画。
 */
@Composable
fun PageCurlReader(
    pages: List<IntRange>,
    annotated: AnnotatedString,
    chapterText: String,
    style: TextStyle,
    contentMaxWidth: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    initialPage: Int,
    isEpub: Boolean,
    onPageChanged: (Int) -> Unit,
    onCrossNext: () -> Unit,
    onCrossPrev: () -> Unit,
    canCrossNext: Boolean = false,
    canCrossPrev: Boolean = false,
    /** Pre-rendered edge spread of the neighbouring chapter, when available. */
    crossNextSheet: List<AnnotatedString?>? = null,
    /** Pre-rendered edge spread of the neighbouring chapter, when available. */
    crossPrevSheet: List<AnnotatedString?>? = null,
    onWord: (word: String, sentence: String) -> Unit,
    onLongPress: (sentence: String, paragraph: String) -> Unit,
    onToggleControls: () -> Unit,
    onPhrase: (paragraphIndex: Int, phraseIndex: Int) -> Unit = { _, _ -> },
    bilingual: Boolean = false,
    columns: Int = 1,
    modifier: Modifier = Modifier,
) {
    // 双页（columns=2）：两个相邻文本页拼成一张「纸」（sheet），翻页以纸为单位（像摊开的书）
    val cols = columns.coerceAtLeast(1)
    val lastSheet = max(0, pages.lastIndex / cols)
    var sheet by remember { mutableIntStateOf((initialPage / cols).coerceIn(0, lastSheet)) }
    var dir by remember { mutableIntStateOf(0) }          // +1 向后翻，-1 向前翻
    var turning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }  // 0=平铺，1=翻完
    // +1/-1 means the current curl will finish in the neighbouring chapter.
    var crossingChapter by remember { mutableIntStateOf(0) }
    // When a neighbouring chapter is not renderable yet (e.g. bilingual mode),
    // a full edge swipe still changes chapter on release rather than requiring a tap.
    var edgeDragDirection by remember { mutableIntStateOf(0) }
    var edgeDragDistance by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val bg = MaterialTheme.colorScheme.surface

    LaunchedEffect(Unit) { onPageChanged(sheet * cols) }

    fun sliceFor(i: Int): AnnotatedString? {
        if (i < 0 || i > pages.lastIndex) return null
        val r = pages[i]
        val s = r.first.coerceIn(0, annotated.length)
        val e = (r.last + 1).coerceIn(s, annotated.length)
        return annotated.subSequence(s, e)
    }

    // 一张纸上的各列内容；整张越界返回 null
    fun sheetSlices(s: Int): List<AnnotatedString?>? {
        if (s < 0 || s > lastSheet) return null
        return (0 until cols).map { sliceFor(s * cols + it) }
    }

    fun commit() {
        val crossDirection = crossingChapter
        if (crossDirection != 0) {
            crossingChapter = 0
            dir = 0
            turning = false
            progress = 0f
            if (crossDirection > 0) onCrossNext() else onCrossPrev()
            return
        }
        val np = (sheet + dir).coerceIn(0, lastSheet)
        sheet = np
        onPageChanged(np * cols)
        dir = 0
        turning = false
        progress = 0f
    }

    fun startTurn(d: Int) {
        if (turning || edgeDragDirection != 0) return
        val crossForward = d > 0 && sheet >= lastSheet
        val crossBackward = d < 0 && sheet <= 0
        if (crossForward || crossBackward) {
            val canCross = if (d > 0) canCrossNext else canCrossPrev
            if (!canCross) return
            val adjacentSheet = if (d > 0) crossNextSheet else crossPrevSheet
            // With no adjacent bitmap (currently the bilingual loading fallback),
            // retain the old immediate chapter handoff for side taps.
            if (adjacentSheet == null) {
                if (d > 0) onCrossNext() else onCrossPrev()
                return
            }
            crossingChapter = d
        }
        dir = d
        turning = true
        progress = 0f
        scope.launch {
            animate(0f, 1f, animationSpec = tween(450)) { v, _ -> progress = v }
            commit()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(pages, crossNextSheet, crossPrevSheet, canCrossNext, canCrossPrev) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (edgeDragDirection != 0) {
                            val d = edgeDragDirection
                            val crossed = edgeDragDistance >= max(size.width * EDGE_SWIPE_FRACTION, MIN_EDGE_SWIPE_PX)
                            edgeDragDirection = 0
                            edgeDragDistance = 0f
                            if (crossed) {
                                if (d > 0) onCrossNext() else onCrossPrev()
                            }
                            return@detectHorizontalDragGestures
                        }
                        if (!turning) return@detectHorizontalDragGestures
                        val p = progress
                        scope.launch {
                            if (p > 0.32f) {
                                animate(p, 1f, animationSpec = tween(220)) { v, _ -> progress = v }
                                commit()
                            } else {
                                animate(p, 0f, animationSpec = tween(220)) { v, _ -> progress = v }
                                turning = false; dir = 0; crossingChapter = 0
                            }
                        }
                    },
                    onDragCancel = {
                        edgeDragDirection = 0
                        edgeDragDistance = 0f
                        if (!turning) return@detectHorizontalDragGestures
                        scope.launch {
                            animate(progress, 0f, animationSpec = tween(180)) { v, _ -> progress = v }
                            turning = false; dir = 0; crossingChapter = 0
                        }
                    },
                ) { change, dragAmount ->
                    if (!turning && edgeDragDirection == 0) {
                        val d = if (dragAmount < 0) 1 else -1
                        val crossForward = d > 0 && sheet >= lastSheet
                        val crossBackward = d < 0 && sheet <= 0
                        if (crossForward || crossBackward) {
                            val canCross = if (d > 0) canCrossNext else canCrossPrev
                            if (!canCross) return@detectHorizontalDragGestures
                            val adjacentSheet = if (d > 0) crossNextSheet else crossPrevSheet
                            if (adjacentSheet == null) {
                                // No bitmap to curl into, but keep the gesture
                                // continuous: a deliberate edge swipe crosses on release.
                                edgeDragDirection = d
                                edgeDragDistance = 0f
                            } else {
                                crossingChapter = d
                                dir = d
                                turning = true
                                progress = 0f
                            }
                        } else {
                            dir = d
                            turning = true
                            progress = 0f
                        }
                    }
                    change.consume()
                    if (edgeDragDirection != 0) {
                        edgeDragDistance = (edgeDragDistance + (-edgeDragDirection) * dragAmount).coerceAtLeast(0f)
                        return@detectHorizontalDragGestures
                    }
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    progress = (progress + (-dir) * dragAmount / w).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // 离屏捕获当前/前/后「纸」位图（不可见，仅用于卷页动画）
        val curBmp = capturedSheet(sheetSlices(sheet), style, contentMaxWidth, horizontalPadding, verticalPadding, bg)
        val nextBmp = capturedSheet(
            if (sheet < lastSheet) sheetSlices(sheet + 1) else crossNextSheet,
            style, contentMaxWidth, horizontalPadding, verticalPadding, bg,
        )
        val prevBmp = capturedSheet(
            if (sheet > 0) sheetSlices(sheet - 1) else crossPrevSheet,
            style, contentMaxWidth, horizontalPadding, verticalPadding, bg,
        )

        if (!turning) {
            // 平铺：实时文本，查词/长按/点两侧翻页/点中间呼出工具栏都生效
            if (cols == 1) {
                ReaderPageContent(
                    pageText = sliceFor(sheet) ?: AnnotatedString(""),
                    chapterText = chapterText,
                    pageStartOffset = pages.getOrNull(sheet)?.first ?: 0,
                    style = style,
                    contentMaxWidth = contentMaxWidth,
                    horizontalPadding = horizontalPadding,
                    verticalPadding = verticalPadding,
                    onWord = onWord,
                    onLongPress = onLongPress,
                    onPrevPage = { startTurn(-1) },
                    onNextPage = { startTurn(1) },
                    onToggleControls = onToggleControls,
                    onPhrase = onPhrase,
                    bilingual = bilingual,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Row(Modifier.widthIn(max = contentMaxWidth).fillMaxSize()) {
                    for (k in 0 until cols) {
                        val pi = sheet * cols + k
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (pi <= pages.lastIndex) {
                                ReaderPageContent(
                                    pageText = sliceFor(pi) ?: AnnotatedString(""),
                                    chapterText = chapterText,
                                    pageStartOffset = pages[pi].first,
                                    style = style,
                                    contentMaxWidth = contentMaxWidth,
                                    horizontalPadding = horizontalPadding,
                                    verticalPadding = verticalPadding,
                                    onWord = onWord,
                                    onLongPress = onLongPress,
                                    onPrevPage = { startTurn(-1) },
                                    onNextPage = { startTurn(1) },
                                    onToggleControls = onToggleControls,
                                    onPhrase = onPhrase,
                                    bilingual = bilingual,
                                    zoneFractionStart = k.toFloat() / cols,
                                    zoneFraction = 1f / cols,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                // 末张纸的空白右页：点击仍按整屏区域生效（左退右进中间工具栏）
                                Box(
                                    Modifier.fillMaxSize().pointerInput(Unit) {
                                        detectTapGestures { pos ->
                                            val gx = k.toFloat() / cols + (pos.x / size.width.toFloat()) / cols
                                            when {
                                                gx < 1f / 3f -> startTurn(-1)
                                                gx > 2f / 3f -> startTurn(1)
                                                else -> onToggleControls()
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val radius = (w * 0.13f).coerceAtLeast(1f)
                val top: ImageBitmap?
                val under: ImageBitmap?
                val curlX: Float
                if (dir > 0) {
                    top = curBmp; under = nextBmp; curlX = w * (1f - progress)
                } else {
                    top = prevBmp; under = curBmp; curlX = w * progress
                }
                when {
                    top != null && under != null -> drawCurl(top, under, curlX, radius)
                    top != null -> drawFlat(top)
                    under != null -> drawFlat(under)
                }
            }
        }
    }
}

/** 把一张「纸」（单页或双页并排）离屏渲染并捕获为位图（不在屏幕上显示）。内容变化时重新捕获。 */
@Composable
private fun capturedSheet(
    slices: List<AnnotatedString?>?,
    style: TextStyle,
    contentMaxWidth: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    bg: Color,
): ImageBitmap? {
    if (slices == null || slices.all { it == null }) return null
    val layer = rememberGraphicsLayer()
    var bmp by remember(slices) { mutableStateOf<ImageBitmap?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent { layer.record { this@drawWithContent.drawContent() } },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.fillMaxSize().background(bg))
        if (slices.size == 1) {
            Text(
                text = slices[0] ?: AnnotatedString(""),
                style = style,
                modifier = Modifier
                    .widthIn(max = contentMaxWidth)
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            )
        } else {
            // 布局须与平铺态的双列 Row 完全一致，否则起卷/落定瞬间会跳动
            Row(Modifier.widthIn(max = contentMaxWidth).fillMaxSize()) {
                slices.forEach { s ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (s != null) {
                            Text(
                                text = s,
                                style = style,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(slices, layer) {
        withFrameNanos { }
        withFrameNanos { }
        bmp = runCatching { layer.toImageBitmap() }.getOrNull()
    }
    return bmp
}

private fun DrawScope.drawFlat(bmp: ImageBitmap) {
    val w = size.width; val h = size.height
    drawIntoCanvas { c ->
        c.nativeCanvas.drawBitmap(
            bmp.asAndroidBitmap(), null,
            android.graphics.Rect(0, 0, w.toInt(), h.toInt()), null,
        )
    }
}

/**
 * 圆柱卷页：under 平铺在下，top 沿 x 方向按圆柱卷起。curlX = 卷起切线位置（从右向左 / 从左向右扫过）。
 */
private fun DrawScope.drawCurl(top: ImageBitmap, under: ImageBitmap, curlX: Float, radius: Float) {
    val w = size.width
    val h = size.height
    val topB = top.asAndroidBitmap()
    val underB = under.asAndroidBitmap()
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        // 下面一页（被露出的页）
        nc.drawBitmap(underB, null, android.graphics.Rect(0, 0, w.toInt(), h.toInt()), null)

        // 卷起的页：竖直为轴的圆柱，y 不变，x 随到切线的弧长绕圆柱
        val cols = 28
        val rows = 1
        val verts = FloatArray((cols + 1) * (rows + 1) * 2)
        var k = 0
        val pi = Math.PI.toFloat()
        for (j in 0..rows) {
            val y = h * j / rows
            for (i in 0..cols) {
                val ox = w * i / cols
                val sx: Float = if (ox <= curlX) {
                    ox
                } else {
                    val arc = ox - curlX
                    val ang = arc / radius
                    // 只画到脊线（半圆）；脊线之后的部分收拢到脊线（隐藏），避免镜像拖影
                    if (ang <= pi) curlX + radius * sin(ang) else curlX
                }
                verts[k++] = sx
                verts[k++] = y
            }
        }
        nc.drawBitmapMesh(topB, cols, rows, verts, 0, null, 0, null)

        if (curlX < w) {
            val crest = (curlX + radius).coerceAtMost(w)
            // 卷起的纸面（curlX..crest）：白色高光渐变，像圆柱形纸卷的受光面，淡化背面镜像文字
            val curlShade = android.graphics.LinearGradient(
                curlX, 0f, crest, 0f,
                intArrayOf(0x1AFFFFFF, 0x99FFFFFF.toInt(), 0x2EFFFFFF),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            nc.drawRect(curlX, 0f, crest, h, android.graphics.Paint().apply { shader = curlShade })
            // 落在“被露出的下一页”上的投影（crest 右侧由深到浅）
            val shadowW = (radius * 1.2f).coerceAtMost(w - crest)
            if (shadowW > 0.5f) {
                val grad = android.graphics.LinearGradient(
                    crest, 0f, crest + shadowW, 0f,
                    0x55000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP,
                )
                nc.drawRect(crest, 0f, crest + shadowW, h, android.graphics.Paint().apply { shader = grad })
            }
        }
    }
}
