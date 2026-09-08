package com.jikunli.englishreader.sync

import io.ktor.http.HttpStatusCode
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun bailianRequestsDisableThinkingForFastReadingTasks() {
        assertTrue(isDashscopeEndpoint(URI("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")))
        assertFalse(isDashscopeEndpoint(URI("https://api.deepseek.com/chat/completions")))
    }

    @Test
    fun webPhrasePromptKeepsAppRulesAndLimitsHighlightToFixedExpression() {
        val prompt = phrasePrompt("Dr Sagan has been awarded the medal for science.")
        assertContains(prompt, "touchstone=公认基准而非试金石")
        assertContains(prompt, "not only...but also")
        assertContains(prompt, "fragments=[\"has been awarded\",\"for\"]")
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
