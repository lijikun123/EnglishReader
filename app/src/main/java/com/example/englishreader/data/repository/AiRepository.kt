package com.example.englishreader.data.repository

import com.example.englishreader.ai.AiAnalysisType
import com.example.englishreader.ai.AiProvider
import com.example.englishreader.data.local.dao.AiAnalysisCacheDao
import com.example.englishreader.data.local.entity.AiAnalysisCache
import com.example.englishreader.data.model.AiSettings

/**
 * AI 仓库：统一「先查缓存，未命中再调用 provider，并写回缓存」的流程。
 * 第一版 provider 为 [com.example.englishreader.ai.StubAiProvider]。
 */
class AiRepository(
    private val cacheDao: AiAnalysisCacheDao,
    private val provider: AiProvider,
) {

    suspend fun analyze(
        type: AiAnalysisType,
        selectedText: String,
        context: String,
        settings: AiSettings,
        onDelta: (String) -> Unit = {},
    ): String {
        cacheDao.find(selectedText, type)?.let { onDelta(it.result); return it.result }

        val result = provider.analyze(type, selectedText, context, settings, onDelta)

        cacheDao.insert(
            AiAnalysisCache(
                selectedText = selectedText,
                analysisType = type,
                result = result,
                provider = provider.name,
            ),
        )
        return result
    }
}
