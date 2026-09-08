package com.jikunli.englishreader.sync

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebAiTest {
    private val requiredEnvironment = mapOf(
        "KREADER_DATABASE_URL" to "jdbc:postgresql://localhost/kreader",
        "KREADER_DATABASE_USER" to "kreader",
        "KREADER_DATABASE_PASSWORD" to "database-password",
        "KREADER_JWT_SECRET" to "0123456789abcdef0123456789abcdef",
    )

    @Test
    fun defaultsToBailianOpenAiCompatibility() {
        val config = AppConfig.fromEnvironment(requiredEnvironment)
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", config.aiBaseUrl)
        assertEquals("qwen-plus", config.aiModel)
    }

    @Test
    fun acceptsDashscopeApiKeyAndPrefersExplicitKreaderKey() {
        assertEquals(
            "dashscope-key",
            AppConfig.fromEnvironment(requiredEnvironment + ("DASHSCOPE_API_KEY" to "dashscope-key")).aiApiKey,
        )
        assertEquals(
            "kreader-key",
            AppConfig.fromEnvironment(
                requiredEnvironment + mapOf(
                    "DASHSCOPE_API_KEY" to "dashscope-key",
                    "KREADER_AI_API_KEY" to "kreader-key",
                ),
            ).aiApiKey,
        )
    }

    @Test
    fun parsesChatCompletionWithoutExposingOtherFields() {
        val raw = """{"id":"private-upstream-id","choices":[{"message":{"role":"assistant","content":"自然译文"}}]}"""
        assertEquals("自然译文", parseChatCompletionContent(raw))
        val error = assertFailsWith<ApiException> { parseChatCompletionContent("not json") }
        assertEquals(HttpStatusCode.BadGateway, error.status)
        assertEquals("ai_invalid_response", error.errorCode)
    }

    @Test
    fun phraseParserKeepsOnlyExactSourceFragmentsAndLimitsResults() {
        val raw = """prefix ```json
            [
              {"phrase":"take cues from","type":"固定搭配","fragments":["take cues from"],"explanation":"借鉴"},
              {"phrase":"invented","type":"固定搭配","fragments":["not in source"],"explanation":"丢弃"}
            ]
            ``` suffix"""
        assertEquals(
            listOf(AiPhrase("take cues from", "固定搭配", listOf("take cues from"), "借鉴")),
            parsePhraseContent(raw, "Writers take cues from earlier work."),
        )
    }

    @Test
    fun aiRequestGuardLimitsEachAccountIndependentlyAndResets() {
        val guard = AiRequestGuard(maxRequests = 2, windowMillis = 1_000)
        guard.check("alice", now = 10)
        guard.check("alice", now = 11)
        assertEquals("ai_rate_limited", assertFailsWith<ApiException> { guard.check("alice", now = 12) }.errorCode)
        guard.check("bob", now = 12)
        guard.check("alice", now = 1_010)
    }
}
