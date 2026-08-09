package com.example.englishreader.data.model

import com.example.englishreader.ai.AiAnalysisType

/**
 * AI 相关设置。包含连接配置（Provider / Key / Base URL / Model）和可自定义的提示词。
 *
 * 注意：API Key 仅保存在本机 DataStore 中，绝不硬编码到代码里，也不会上传第三方
 * （除了你自己配置的 AI 服务，分析请求会带上 Key 发往该服务）。
 *
 * 提示词支持占位符：`{{text}}`（点选的词/句/段）、`{{context}}`（所在句子或段落）。
 */
data class AiSettings(
    val enabled: Boolean = false,
    val provider: String = DEFAULT_PROVIDER,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val promptExplainWord: String = DEFAULT_EXPLAIN_WORD,
    val promptTranslate: String = DEFAULT_TRANSLATE,
    val promptGrammar: String = DEFAULT_GRAMMAR,
    val promptBreakdown: String = DEFAULT_BREAKDOWN,
    val promptCloseReading: String = DEFAULT_CLOSE_READING,
) {
    /** 取某个分析动作对应的用户提示词模板。 */
    fun promptFor(type: AiAnalysisType): String = when (type) {
        AiAnalysisType.EXPLAIN_WORD_IN_CONTEXT -> promptExplainWord
        AiAnalysisType.TRANSLATE_SENTENCE -> promptTranslate
        AiAnalysisType.ANALYZE_GRAMMAR -> promptGrammar
        AiAnalysisType.BREAK_DOWN_SENTENCE -> promptBreakdown
        AiAnalysisType.CLOSE_READING_PARAGRAPH -> promptCloseReading
    }

    companion object {
        const val DEFAULT_PROVIDER = "deepseek"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-flash"

        const val DEFAULT_SYSTEM_PROMPT =
            "你是一位资深英语精读老师，帮中文母语的学习者做深度阅读。回答一律用中文（可保留英文原词）：准确、有条理、点到为止不啰嗦；不寒暄、不复述题目。讲解优先揭示地道用法、熟词僻义和可迁移到写作的表达，别停在字面。"
        const val DEFAULT_EXPLAIN_WORD =
            "在下句语境里精讲「{{text}}」：\n① 词性 + 贴合本句的中文释义\n② 若此处是熟词僻义（常见义之外的用法），点明「常见义 vs 本处义」的区别\n③ 一句话说明为什么在本句取这个意思\n④ 若是地道搭配或写作可用的表达，补一句迁移用法\n句子：{{context}}"
        const val DEFAULT_TRANSLATE =
            "把下面的英文翻成自然、地道、符合中文表达习惯的译文：长句要调整语序、去翻译腔、保留原文语气。只输出译文，不加解释：\n{{text}}"
        const val DEFAULT_GRAMMAR =
            "用中文分析下面这句英文的语法（不要逐词翻译）：\n① 主干（主谓宾 / 主系表）\n② 关键结构：从句、非谓语、插入语、倒装、省略等，逐个说明作用\n③ 若有值得学的句型或固定搭配，给一个可套用的仿写例句\n{{text}}"
        const val DEFAULT_BREAKDOWN =
            "下面是英文长难句，用中文按精读方式拆解：\n① 主干（主谓宾）\n② 层级拆分：逐个列出修饰成分 / 从句 / 插入语，说明各修饰谁、起什么作用\n③ 理清彼此的逻辑关系（因果 / 转折 / 目的等）\n④ 通顺的中文翻译\n⑤ 用更简单的英文改写一遍帮助理解\n{{text}}"
        const val DEFAULT_CLOSE_READING =
            "用中文精读下面这段英文：\n① 段落大意（2-3 句）\n② 逻辑骨架：句子之间如何推进（转折 / 举例 / 因果 / 递进）\n③ 重点词汇与搭配（4-6 个，附中文；有熟词僻义要特别标出）\n④ 值得学的语法结构或可迁移到写作的地道表达\n⑤ 段落通顺翻译\n{{context}}"
    }
}
