package com.example.englishreader.ai

import com.example.englishreader.data.model.AiSettings
import kotlinx.coroutines.delay

/**
 * 占位实现：第一版不真正调用任何网络/AI 接口，仅返回示例文本，
 * 以便把「点击按钮 → 调用 → 缓存 → 展示」的链路先跑通。
 */
class StubAiProvider : AiProvider {

    override val name: String = "stub"

    override suspend fun analyze(
        type: AiAnalysisType,
        selectedText: String,
        context: String,
        settings: AiSettings,
        onDelta: (String) -> Unit,
    ): String {
        delay(400) // 模拟网络耗时，方便看到 loading 效果

        return buildString {
            appendLine("【${type.label}】(示例占位结果)")
            appendLine()
            appendLine("· 选中内容：")
            appendLine(selectedText)
            if (context.isNotBlank() && context != selectedText) {
                appendLine()
                appendLine("· 上下文：")
                appendLine(context)
            }
            appendLine()
            append("说明：AI 分析为占位实现，将在后续版本接入真实模型。请先在「设置 → AI」中填写你自己的 API Key。")
        }
    }

    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        settings: AiSettings,
    ): String {
        delay(200)
        return "[]"
    }
}
