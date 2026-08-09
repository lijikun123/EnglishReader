package com.example.englishreader.ai

import com.example.englishreader.data.model.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 真实 AI 实现：调用 OpenAI 兼容的 Chat Completions 接口（默认 DeepSeek）。
 *
 * 仅用系统自带的 [HttpURLConnection] + org.json，不引入任何网络库依赖。
 * 连接配置与提示词都来自用户在「设置 → AI」填写的 [AiSettings]；Key 只存本机、随请求发往用户自配的服务。
 */
class DeepSeekProvider : AiProvider {

    override val name: String = "deepseek"

    override suspend fun analyze(
        type: AiAnalysisType,
        selectedText: String,
        context: String,
        settings: AiSettings,
        onDelta: (String) -> Unit,
    ): String {
        val userPrompt = settings.promptFor(type)
            .replace("{{text}}", selectedText)
            .replace("{{context}}", context.ifBlank { selectedText })
        return chat(settings.systemPrompt, userPrompt, settings, onDelta)
    }

    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        settings: AiSettings,
    ): String = chat(systemPrompt, userPrompt, settings) {}

    /** 实际的一次对话补全（流式）。失败一律抛异常，错误结果不会被缓存。 */
    private suspend fun chat(
        systemPrompt: String,
        userPrompt: String,
        settings: AiSettings,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (!settings.enabled) {
            throw IllegalStateException("AI 未启用。请到「设置 → AI」打开「启用 AI 助手」开关。")
        }
        val key = settings.apiKey.trim()
        if (key.isEmpty()) {
            throw IllegalStateException("未填写 API Key。请到「设置 → AI」填写你的 API Key（如 DeepSeek）。")
        }

        val base = settings.baseUrl.trim().ifEmpty { AiSettings.DEFAULT_BASE_URL }
        val endpoint = if (base.contains("/chat/completions")) base else base.trimEnd('/') + "/chat/completions"
        val model = settings.model.trim().ifEmpty { AiSettings.DEFAULT_MODEL }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                if (systemPrompt.isNotBlank()) {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
            put("stream", true) // 流式：边生成边返回，前台边收边显示，体感更快
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "text/event-stream")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw RuntimeException("请求失败（HTTP $code）：${extractError(err)}")
            }

            // 读 SSE 流：每行形如 "data: {json}"，从 choices[0].delta.content 累加
            val sb = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    val l = line.trim()
                    if (l.startsWith("data:")) {
                        val data = l.substring(5).trim()
                        if (data.isNotEmpty() && data != "[DONE]") {
                            runCatching {
                                val delta = JSONObject(data)
                                    .getJSONArray("choices").getJSONObject(0)
                                    .optJSONObject("delta")
                                // content 可能是 JSON null（org.json 的 optString 会返回 "null"），必须用 isNull 判断。
                                val piece = if (delta != null && !delta.isNull("content")) {
                                    delta.optString("content")
                                } else {
                                    ""
                                }
                                if (piece.isNotEmpty()) {
                                    sb.append(piece)
                                    onDelta(sb.toString())
                                }
                            }
                        }
                    }
                }
            }
            val content = sb.toString().trim()
            if (content.isEmpty()) "（模型没有返回内容）" else content
        } catch (e: Exception) {
            if (e is RuntimeException && e.message?.startsWith("请求失败") == true) throw e
            throw RuntimeException("网络出错：${e.message ?: e.javaClass.simpleName}。请检查网络、Base URL、API Key 是否正确。", e)
        } finally {
            conn?.disconnect()
        }
    }

    /** 尽量从错误响应里取出 message 字段，取不到就返回截断的原文。 */
    private fun extractError(resp: String): String = try {
        JSONObject(resp).optJSONObject("error")?.optString("message").orEmpty()
            .ifEmpty { resp.take(200) }
    } catch (e: Exception) {
        resp.take(200)
    }
}
