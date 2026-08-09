package com.example.englishreader.data.repository

import com.example.englishreader.ai.AiAnalysisType
import com.example.englishreader.ai.AiProvider
import com.example.englishreader.data.local.dao.ChapterTranslationDao
import com.example.englishreader.data.local.entity.ChapterTranslation
import kotlinx.coroutines.flow.first

/**
 * 双语阅读的段落翻译：缓存优先（chapter_translations 表），未命中再调用 AI 翻译并写回缓存。
 * 复用用户在「设置 → AI」里配置的「翻译句子」提示词，不污染 AI 分析缓存。
 */
class TranslationRepository(
    private val dao: ChapterTranslationDao,
    private val provider: AiProvider,
    private val settingsRepository: SettingsRepository,
) {
    /** 取整章已缓存的译文：段落序号 -> 中文。 */
    suspend fun cachedForChapter(itemId: Long, chapterIndex: Int): Map<Int, String> =
        dao.getForChapter(itemId, chapterIndex).associate { it.paragraphIndex to it.translated }

    /** 翻译一个段落；缓存优先，未命中调用 AI 并缓存。失败抛异常交上层处理（不写缓存）。 */
    suspend fun translateParagraph(
        itemId: Long,
        chapterIndex: Int,
        paragraphIndex: Int,
        english: String,
    ): String {
        dao.get(itemId, chapterIndex, paragraphIndex)?.let { return it }
        val settings = settingsRepository.aiSettings.first()
        val result = provider.analyze(AiAnalysisType.TRANSLATE_SENTENCE, english, english, settings)
        dao.upsert(ChapterTranslation(itemId, chapterIndex, paragraphIndex, result))
        return result
    }
}
