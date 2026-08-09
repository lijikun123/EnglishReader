package com.example.englishreader.data.repository

import com.example.englishreader.ai.AiProvider
import com.example.englishreader.data.local.dao.ChapterPhraseDao
import com.example.englishreader.data.local.entity.ChapterPhrase
import com.example.englishreader.data.model.DetectedPhrase
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * 词组检测：缓存优先（chapter_phrases 表），未命中再调用 AI（自由提示词，返回 JSON）并缓存。
 */
class PhraseRepository(
    private val dao: ChapterPhraseDao,
    private val provider: AiProvider,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun cachedForChapter(itemId: Long, chapterIndex: Int): Map<Int, List<DetectedPhrase>> =
        dao.getForChapter(itemId, chapterIndex).associate { it.paragraphIndex to parse(it.phrasesJson) }

    /** 检测一段的词组；缓存优先，未命中调用 AI 并缓存。失败抛异常交上层处理（不缓存）。 */
    suspend fun detect(
        itemId: Long,
        chapterIndex: Int,
        paragraphIndex: Int,
        english: String,
    ): List<DetectedPhrase> {
        dao.get(itemId, chapterIndex, paragraphIndex)?.let { return parse(it) }
        val settings = settingsRepository.aiSettings.first()
        val raw = provider.complete(SYSTEM_PROMPT, userPrompt(english), settings)
        val phrases = parse(extractJsonArray(raw))
        dao.upsert(ChapterPhrase(itemId, chapterIndex, paragraphIndex, toJson(phrases)))
        return phrases
    }

    private fun userPrompt(english: String): String =
        "从下面这段英文里，挑出真正值得中文英语学习者深度学习的语言点，用于精读加粗。重点找这四类：\n" +
            "1. 熟词僻义：常见词在本句取了不常见的意思（如 touchstone=公认基准而非试金石、look to=求助于而非看向、rear=饲养、ballpark=大致估算、richness=物种丰富度、class=纲、soup=混合样本）。\n" +
            "2. 地道搭配 / 动词短语（如 take cues from 借鉴、chip away at 一点点啃、make up 占比、stand to lose 面临失去、a flair for 擅长）。\n" +
            "3. 可迁移到写作的高级语块（如 with the express purpose of、use A as an anchor value for B、apply the same ratio of A to B、more than triple the figure）。\n" +
            "4. 真正有嚼头的句型（倒装、强调、复杂并列等）。\n" +
            "**不要**挑过于基础、无需讲解的（如 not only...but also、in order to、as well as、such as、a lot of、there is、because of）。宁缺毋滥，本段若没有就返回空数组 []。\n" +
            "用 JSON 数组返回，每个元素：" +
            "{\"phrase\":\"词组或词\",\"type\":\"熟词僻义|固定搭配|学术语块|句型\",\"fragments\":[\"原文中要加粗的原样片段\"],\"explanation\":\"中文解释：什么意思 + 怎么用；若是熟词僻义，点明常见义与此处义的区别\"}。\n" +
            "要求：fragments 必须是原文中**原样出现**的子串（保留大小写与标点）；每段最多 6 个；只输出 JSON 数组本身，不要解释、不要代码块。\n\n" +
            "段落：\n" + english

    /** 从模型返回里截出 JSON 数组（容忍 ```json 代码块或多余文字）。 */
    private fun extractJsonArray(raw: String): String {
        val s = raw.indexOf('[')
        val e = raw.lastIndexOf(']')
        return if (s in 0 until e) raw.substring(s, e + 1) else "[]"
    }

    private fun parse(json: String): List<DetectedPhrase> = try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val phrase = o.optString("phrase").trim()
                val explanation = o.optString("explanation").trim()
                val type = o.optString("type").trim()
                val fragsArr = o.optJSONArray("fragments")
                val fragments = buildList {
                    if (fragsArr != null) {
                        for (j in 0 until fragsArr.length()) {
                            val f = fragsArr.optString(j).trim()
                            if (f.isNotEmpty()) add(f)
                        }
                    }
                }
                if (fragments.isNotEmpty()) add(DetectedPhrase(phrase.ifEmpty { fragments.first() }, explanation, fragments, type))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun toJson(phrases: List<DetectedPhrase>): String {
        val arr = JSONArray()
        phrases.forEach { p ->
            arr.put(
                JSONObject()
                    .put("phrase", p.phrase)
                    .put("type", p.type)
                    .put("explanation", p.explanation)
                    .put("fragments", JSONArray(p.fragments)),
            )
        }
        return arr.toString()
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "你是资深英语精读老师，帮中文母语者从文本里挑出值得深度学习的语言点：优先熟词僻义、地道搭配和可迁移到写作的高级语块，避开过于基础的搭配。只输出 JSON，不要寒暄、不要代码块。"
    }
}
