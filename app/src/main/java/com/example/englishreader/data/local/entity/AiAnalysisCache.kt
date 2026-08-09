package com.example.englishreader.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.englishreader.ai.AiAnalysisType

/** AI 分析结果的本地缓存，避免重复请求。 */
@Entity(
    tableName = "ai_analysis_cache",
    indices = [Index(value = ["selectedText", "analysisType"])],
)
data class AiAnalysisCache(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val selectedText: String,
    val analysisType: AiAnalysisType,
    val result: String,
    val provider: String,
    val createdAt: Long = System.currentTimeMillis(),
)
