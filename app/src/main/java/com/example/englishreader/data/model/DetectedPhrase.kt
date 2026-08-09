package com.example.englishreader.data.model

/**
 * AI 识别出的一个值得深度学习的语言点（熟词僻义 / 地道搭配 / 学术语块 / 句型）。
 * @param phrase 词组或词（如 "take cues from"、"touchstone"）
 * @param explanation 中文解释（含用法；若为熟词僻义，含常见义 vs 本处义的区别）
 * @param fragments 原文中要加粗的片段（必须是原文原样子串，可能多段，如 ["not only","but also"]）
 * @param type 分类标签：熟词僻义 / 固定搭配 / 学术语块 / 句型（可空）
 */
data class DetectedPhrase(
    val phrase: String,
    val explanation: String,
    val fragments: List<String>,
    val type: String = "",
)
