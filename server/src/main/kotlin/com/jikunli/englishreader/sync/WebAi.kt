package com.jikunli.englishreader.sync

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

const val WEB_AI_CACHE_VERSION = "web-ai-v2"

interface WebAiService {
    val configured: Boolean
    val model: String

    suspend fun translate(text: String): String
    suspend fun phrases(text: String): List<AiPhrase>
}

class OpenAiCompatibleWebAiService(
    private val config: AppConfig,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : WebAiService {
    override val configured: Boolean = config.aiApiKey != null
    override val model: String = config.aiModel
    private val gate = Semaphore(2)
    private val endpoint: URI = chatEndpoint(config.aiBaseUrl)

    override suspend fun translate(text: String): String = complete(
        systemPrompt = READING_SYSTEM_PROMPT,
        userPrompt = "把下面的英文翻成自然、地道、符合中文表达习惯的译文：长句要调整语序、去翻译腔、保留原文语气。只输出译文，不加解释：\n$text",
        maxTokens = 1_500,
    ).trim().takeIf { it.isNotEmpty() }
        ?: throw ApiException(HttpStatusCode.BadGateway, "ai_invalid_response", "AI service returned an empty translation")

    override suspend fun phrases(text: String): List<AiPhrase> {
        val raw = complete(PHRASE_SYSTEM_PROMPT, phrasePrompt(text), maxTokens = 1_800)
        return parsePhraseContent(raw, text)
    }

    private suspend fun complete(systemPrompt: String, userPrompt: String, maxTokens: Int): String {
        val key = config.aiApiKey
            ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "ai_not_configured", "AI is not configured on this server")
        val payload = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("max_tokens", maxTokens)
            if (isDashscopeEndpoint(endpoint)) {
                // Qwen 3.7 Flash enables thinking by default. Translation and
                // phrase extraction are short, deterministic reading tasks, so
                // thinking only adds latency and output-token cost here.
                put("enable_thinking", false)
            }
            if (endpoint.host.equals("api.deepseek.com", ignoreCase = true)) {
                // Translation and phrase extraction do not benefit from the
                // provider's default high-effort thinking mode.
                put("thinking", buildJsonObject { put("type", "disabled") })
            }
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
            })
        }
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(85))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(apiJson.encodeToString(JsonObject.serializer(), payload)))
            .build()
        return gate.withPermit {
            val response = try {
                withContext(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            } catch (_: Exception) {
                throw ApiException(HttpStatusCode.BadGateway, "ai_unavailable", "AI service is temporarily unavailable")
            }
            when (response.statusCode()) {
                in 200..299 -> parseChatCompletionContent(response.body())
                401, 403 -> throw ApiException(
                    HttpStatusCode.ServiceUnavailable,
                    "ai_credentials_invalid",
                    "AI credentials configured on the server were rejected",
                )
                429 -> throw ApiException(HttpStatusCode.TooManyRequests, "ai_upstream_rate_limited", "AI service is busy; try again shortly")
                else -> throw ApiException(HttpStatusCode.BadGateway, "ai_upstream_error", "AI service request failed")
            }
        }
    }
}

class AiRequestGuard(
    private val maxRequests: Int,
    private val windowMillis: Long = 60_000L,
) {
    private data class Bucket(var startedAt: Long, var requests: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun check(key: String, now: Long = System.currentTimeMillis()) {
        var allowed = false
        buckets.compute(key) { _, current ->
            val bucket = current ?: Bucket(now, 0)
            if (now - bucket.startedAt >= windowMillis) {
                bucket.startedAt = now
                bucket.requests = 0
            }
            if (bucket.requests < maxRequests) {
                bucket.requests++
                allowed = true
            }
            bucket
        }
        if (!allowed) {
            throw ApiException(HttpStatusCode.TooManyRequests, "ai_rate_limited", "Too many AI requests; try again in a minute")
        }
    }
}

internal fun parseChatCompletionContent(raw: String): String = try {
    apiJson.parseToJsonElement(raw).jsonObject["choices"]!!.jsonArray.first().jsonObject["message"]!!
        .jsonObject["content"]!!.jsonPrimitive.content
} catch (_: Exception) {
    throw ApiException(HttpStatusCode.BadGateway, "ai_invalid_response", "AI service returned an invalid response")
}

internal fun parsePhraseContent(raw: String, source: String): List<AiPhrase> {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    val array = try { apiJson.parseToJsonElement(raw.substring(start, end + 1)).jsonArray }
    catch (_: Exception) { return emptyList() }
    return array.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val fragments = (item["fragments"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { fragment -> fragment.isNotEmpty() && source.contains(fragment) }
        }.distinct().take(8)
        if (fragments.isEmpty()) return@mapNotNull null
        AiPhrase(
            phrase = (item["phrase"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                .ifEmpty { fragments.first() }.take(200),
            type = (item["type"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(40),
            fragments = fragments,
            explanation = (item["explanation"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(2_000),
        )
    }.take(6)
}

private fun chatEndpoint(baseUrl: String): URI {
    val normalized = baseUrl.trim().trimEnd('/')
    val endpoint = if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions"
    val uri = URI.create(endpoint)
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "KREADER_AI_BASE_URL must be an HTTPS URL"
    }
    return uri
}

internal fun isDashscopeEndpoint(endpoint: URI): Boolean =
    endpoint.host.equals("dashscope.aliyuncs.com", ignoreCase = true) ||
        endpoint.host.endsWith(".dashscope.aliyuncs.com", ignoreCase = true)

internal fun phrasePrompt(text: String): String = """
    从下面这段英文里，挑出真正值得中文英语学习者深度学习的语言点，用于精读加粗。重点找这四类：
    1. 熟词僻义：常见词在本句取了不常见的意思（如 touchstone=公认基准而非试金石、look to=求助于而非看向、rear=饲养、ballpark=大致估算、richness=物种丰富度、class=纲、soup=混合样本）。
    2. 地道搭配 / 动词短语（如 take cues from 借鉴、chip away at 一点点啃、make up 占比、stand to lose 面临失去、a flair for 擅长）。
    3. 可迁移到写作的高级语块（如 with the express purpose of、use A as an anchor value for B、apply the same ratio of A to B、more than triple the figure）。
    4. 真正有嚼头的句型（倒装、强调、复杂并列等）。
    **不要**挑过于基础、无需讲解的（如 not only...but also、in order to、as well as、such as、a lot of、there is、because of）。宁缺毋滥，本段若没有就返回空数组 []。
    用 JSON 数组返回，每个元素：{"phrase":"词组或词","type":"熟词僻义|固定搭配|学术语块|句型","fragments":["原文中要加粗的原样片段"],"explanation":"中文解释：什么意思 + 怎么用；若是熟词僻义，点明常见义与此处义的区别"}。
    要求：fragments 必须是原文中**原样出现**的子串（保留大小写与标点）；每段最多 6 个；只输出 JSON 数组本身，不要解释、不要代码块。
    加粗范围要尽量短，只包含值得学习的固定表达，不要把由具体人名、奖项、地点或其他内容填充的可变部分一起加粗。对于不连续结构，phrase 用省略号表示完整结构，fragments 只列固定部分。例如 has been awarded ... for ... 应返回 phrase="has been awarded ... for ..."，fragments=["has been awarded","for"]，不要把奖项名称放入 fragments。

    段落：
    $text
""".trimIndent()

private const val READING_SYSTEM_PROMPT =
    "你是一位资深英语精读老师，帮中文母语的学习者做深度阅读。回答一律用中文（可保留英文原词）：准确、有条理、点到为止不啰嗦；不寒暄、不复述题目。"

private const val PHRASE_SYSTEM_PROMPT =
    "你是资深英语精读老师，帮中文母语者挑出值得深度学习的语言点。只输出 JSON 数组，不要寒暄、不要代码块。"
