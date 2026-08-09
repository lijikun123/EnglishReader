package com.example.englishreader.ai

import com.example.englishreader.data.model.AiSettings

/**
 * AI 能力的抽象接口。第一版只提供 [StubAiProvider] 占位实现。
 *
 * 后续接入真实模型时，新增实现类（如 OpenAiProvider / ClaudeProvider），
 * 在 AppContainer 中替换即可，UI 与 Repository 层无需改动。
 */
interface AiProvider {
    /** 用于在缓存中标记结果来源。 */
    val name: String

    /**
     * @param type 分析类型（四个固定动作之一）
     * @param selectedText 用户选中/点击的文本（单词、句子或段落）
     * @param context 上下文（通常是原句或原段落）
     * @param settings 用户在设置页填写的 AI 配置（含 API Key）
     * @param onDelta 流式回调：每收到一段就回调一次「到目前为止的累计文本」，用于边生成边显示。
     * @return 完整分析结果文本
     */
    suspend fun analyze(
        type: AiAnalysisType,
        selectedText: String,
        context: String,
        settings: AiSettings,
        onDelta: (String) -> Unit = {},
    ): String

    /** 自由提示词补全（非流式）。用于词组检测等需要自定义 prompt / 结构化输出的场景。 */
    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        settings: AiSettings,
    ): String
}
