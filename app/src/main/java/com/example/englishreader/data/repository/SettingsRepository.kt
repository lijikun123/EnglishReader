package com.example.englishreader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.englishreader.data.model.AiSettings
import com.example.englishreader.data.model.ReadingSettings
import com.example.englishreader.data.model.ThemeMode
import com.example.englishreader.data.security.AiKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 设置仓库：阅读排版、词典偏好、AI 配置统一通过 DataStore 持久化。
 * 暴露为 Flow，供各 ViewModel 订阅；更新方法为 suspend。
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val aiKeyStore: AiKeyStore,
) {

    private object Keys {
        val FONT_SIZE = floatPreferencesKey("font_size")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val PARAGRAPH_SPACING = floatPreferencesKey("paragraph_spacing")
        val READING_WIDTH = floatPreferencesKey("reading_width")
        val THEME_MODE = stringPreferencesKey("theme_mode")

        val PREFER_CHINESE = booleanPreferencesKey("prefer_chinese_first")
        val BILINGUAL_MODE = booleanPreferencesKey("bilingual_mode")
        val PHRASE_MODE = booleanPreferencesKey("phrase_mode")

        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_SYSTEM_PROMPT = stringPreferencesKey("ai_system_prompt")
        val AI_PROMPT_EXPLAIN = stringPreferencesKey("ai_prompt_explain")
        val AI_PROMPT_TRANSLATE = stringPreferencesKey("ai_prompt_translate")
        val AI_PROMPT_GRAMMAR = stringPreferencesKey("ai_prompt_grammar")
        val AI_PROMPT_BREAKDOWN = stringPreferencesKey("ai_prompt_breakdown")
        val AI_PROMPT_CLOSEREADING = stringPreferencesKey("ai_prompt_closereading")
    }

    val readingSettings: Flow<ReadingSettings> = dataStore.data.map { p ->
        ReadingSettings(
            fontSize = p[Keys.FONT_SIZE] ?: ReadingSettings.DEFAULT_FONT_SIZE,
            lineHeight = p[Keys.LINE_HEIGHT] ?: ReadingSettings.DEFAULT_LINE_HEIGHT,
            paragraphSpacing = p[Keys.PARAGRAPH_SPACING] ?: ReadingSettings.DEFAULT_PARAGRAPH_SPACING,
            readingWidth = p[Keys.READING_WIDTH] ?: ReadingSettings.DEFAULT_READING_WIDTH,
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.LIGHT,
        )
    }

    private val aiApiKey = MutableStateFlow(aiKeyStore.read())

    val aiSettings: Flow<AiSettings> = combine(dataStore.data, aiApiKey) { preferences, apiKey ->
        aiSettingsFrom(preferences, apiKey)
    }

    private fun aiSettingsFrom(p: Preferences, apiKey: String = aiApiKey.value): AiSettings = AiSettings(
        enabled = p[Keys.AI_ENABLED] ?: false,
        provider = p[Keys.AI_PROVIDER] ?: AiSettings.DEFAULT_PROVIDER,
        apiKey = apiKey,
        baseUrl = p[Keys.AI_BASE_URL] ?: "",
        model = p[Keys.AI_MODEL] ?: "",
        systemPrompt = p[Keys.AI_SYSTEM_PROMPT] ?: AiSettings.DEFAULT_SYSTEM_PROMPT,
        promptExplainWord = p[Keys.AI_PROMPT_EXPLAIN] ?: AiSettings.DEFAULT_EXPLAIN_WORD,
        promptTranslate = p[Keys.AI_PROMPT_TRANSLATE] ?: AiSettings.DEFAULT_TRANSLATE,
        promptGrammar = p[Keys.AI_PROMPT_GRAMMAR] ?: AiSettings.DEFAULT_GRAMMAR,
        promptBreakdown = p[Keys.AI_PROMPT_BREAKDOWN] ?: AiSettings.DEFAULT_BREAKDOWN,
        promptCloseReading = p[Keys.AI_PROMPT_CLOSEREADING] ?: AiSettings.DEFAULT_CLOSE_READING,
    )

    val preferChineseFirst: Flow<Boolean> = dataStore.data.map { it[Keys.PREFER_CHINESE] ?: true }

    val bilingualMode: Flow<Boolean> = dataStore.data.map { it[Keys.BILINGUAL_MODE] ?: false }

    val phraseMode: Flow<Boolean> = dataStore.data.map { it[Keys.PHRASE_MODE] ?: false }

    suspend fun updateReadingSettings(transform: (ReadingSettings) -> ReadingSettings) {
        val updated = transform(readingSettings.first())
        dataStore.edit { p ->
            p[Keys.FONT_SIZE] = updated.fontSize
            p[Keys.LINE_HEIGHT] = updated.lineHeight
            p[Keys.PARAGRAPH_SPACING] = updated.paragraphSpacing
            p[Keys.READING_WIDTH] = updated.readingWidth
            p[Keys.THEME_MODE] = updated.themeMode.name
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setPreferChineseFirst(value: Boolean) {
        dataStore.edit { it[Keys.PREFER_CHINESE] = value }
    }

    suspend fun setBilingualMode(value: Boolean) {
        dataStore.edit { it[Keys.BILINGUAL_MODE] = value }
    }

    suspend fun setPhraseMode(value: Boolean) {
        dataStore.edit { it[Keys.PHRASE_MODE] = value }
    }

    suspend fun updateAiSettings(transform: (AiSettings) -> AiSettings) {
        val updated = transform(aiSettings.first())
        if (updated.apiKey != aiApiKey.value) {
            aiKeyStore.save(updated.apiKey)
            aiApiKey.value = updated.apiKey
        }
        // 普通 AI 设置仍在单个 edit{} 中原子更新；Key 从不写入 DataStore。
        dataStore.edit { p ->
            p[Keys.AI_ENABLED] = updated.enabled
            p[Keys.AI_PROVIDER] = updated.provider
            p.remove(Keys.AI_API_KEY)
            p[Keys.AI_BASE_URL] = updated.baseUrl
            p[Keys.AI_MODEL] = updated.model
            p[Keys.AI_SYSTEM_PROMPT] = updated.systemPrompt
            p[Keys.AI_PROMPT_EXPLAIN] = updated.promptExplainWord
            p[Keys.AI_PROMPT_TRANSLATE] = updated.promptTranslate
            p[Keys.AI_PROMPT_GRAMMAR] = updated.promptGrammar
            p[Keys.AI_PROMPT_BREAKDOWN] = updated.promptBreakdown
            p[Keys.AI_PROMPT_CLOSEREADING] = updated.promptCloseReading
        }
    }

    /** Moves a pre-sync legacy plaintext key into Keystore on the next app start. */
    suspend fun migrateLegacyAiKey() {
        dataStore.edit { preferences ->
            val legacyKey = preferences[Keys.AI_API_KEY]
            if (aiApiKey.value.isBlank() && !legacyKey.isNullOrBlank()) {
                aiKeyStore.save(legacyKey)
                aiApiKey.value = legacyKey
            }
            preferences.remove(Keys.AI_API_KEY)
        }
    }
}
