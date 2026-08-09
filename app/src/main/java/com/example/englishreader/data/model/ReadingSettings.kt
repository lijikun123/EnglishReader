package com.example.englishreader.data.model

/** 阅读主题：浅色 / 深色 / 护眼（米黄）。 */
enum class ThemeMode { LIGHT, DARK, SEPIA }

/**
 * 阅读排版设置。会通过 DataStore 持久化，并实时影响 ReaderScreen 的正文显示。
 *
 * @param fontSize 正文字号（sp）
 * @param lineHeight 行距倍数（相对字号），例如 1.6 表示行高 = 字号 * 1.6
 * @param paragraphSpacing 段落间距（dp）
 * @param readingWidth 正文最大宽度（dp）。在平板/横屏上限制行宽，提升阅读舒适度
 * @param themeMode 阅读主题
 */
data class ReadingSettings(
    val fontSize: Float = DEFAULT_FONT_SIZE,
    val lineHeight: Float = DEFAULT_LINE_HEIGHT,
    val paragraphSpacing: Float = DEFAULT_PARAGRAPH_SPACING,
    val readingWidth: Float = DEFAULT_READING_WIDTH,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
) {
    companion object {
        const val DEFAULT_FONT_SIZE = 18f
        const val DEFAULT_LINE_HEIGHT = 1.6f
        const val DEFAULT_PARAGRAPH_SPACING = 16f
        const val DEFAULT_READING_WIDTH = 640f

        const val MIN_FONT_SIZE = 12f
        const val MAX_FONT_SIZE = 30f
        const val MIN_LINE_HEIGHT = 1.0f
        const val MAX_LINE_HEIGHT = 2.4f
        const val MIN_PARAGRAPH_SPACING = 0f
        const val MAX_PARAGRAPH_SPACING = 40f
        const val MIN_READING_WIDTH = 360f
        const val MAX_READING_WIDTH = 1000f
    }
}
