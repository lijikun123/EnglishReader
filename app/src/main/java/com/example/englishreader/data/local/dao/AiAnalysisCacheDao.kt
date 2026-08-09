package com.example.englishreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.englishreader.ai.AiAnalysisType
import com.example.englishreader.data.local.entity.AiAnalysisCache

@Dao
interface AiAnalysisCacheDao {

    @Query(
        "SELECT * FROM ai_analysis_cache WHERE selectedText = :selectedText AND analysisType = :type ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun find(selectedText: String, type: AiAnalysisType): AiAnalysisCache?

    @Insert
    suspend fun insert(cache: AiAnalysisCache): Long
}
