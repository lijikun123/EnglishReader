package com.example.englishreader.ai

/**
 * AI 分析类型。对应 Reader 里固定的四个按钮，而不是自由聊天窗口。
 * label 直接用于按钮文案。
 */
enum class AiAnalysisType(val label: String) {
    EXPLAIN_WORD_IN_CONTEXT("解释这个词在原句中的意思"),
    TRANSLATE_SENTENCE("翻译这个句子"),
    ANALYZE_GRAMMAR("分析这个句子的语法"),
    BREAK_DOWN_SENTENCE("长难句拆解"),
    CLOSE_READING_PARAGRAPH("精读这个段落"),
}
