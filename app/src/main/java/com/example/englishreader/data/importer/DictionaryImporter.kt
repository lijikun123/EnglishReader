package com.example.englishreader.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.englishreader.data.local.entity.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.nio.charset.StandardCharsets

/** 词典导入结果。 */
sealed interface DictionaryImportResult {
    data class Success(val entries: List<DictionaryEntry>) : DictionaryImportResult
    data class Error(val message: String) : DictionaryImportResult
}

/**
 * 通过 SAF 读取 .csv / .json 中英文词典（UTF-8）。
 *  - CSV：第一行表头（按列名匹配，忽略大小写）
 *  - JSON：对象数组
 *
 * word / lemma 统一转小写存储，配合查词时的小写归一化实现「忽略大小写」。
 * 解析失败返回 [DictionaryImportResult.Error]，绝不抛到 UI。
 */
class DictionaryImporter(private val context: Context) {

    suspend fun read(uri: Uri): DictionaryImportResult = withContext(Dispatchers.IO) {
        try {
            val name = (queryDisplayName(uri) ?: "").lowercase()
            val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            } ?: return@withContext DictionaryImportResult.Error("无法打开该文件")

            val entries = when {
                name.endsWith(".json") -> parseJson(raw)
                name.endsWith(".csv") -> parseCsv(raw)
                else -> return@withContext DictionaryImportResult.Error("仅支持 .csv 或 .json 文件")
            }
            // 同一文件内按 word 去重（后者覆盖前者）
            val deduped = entries.associateBy { it.word }.values.toList()
            if (deduped.isEmpty()) {
                DictionaryImportResult.Error("未解析到任何词条（请检查表头或 JSON 格式）")
            } else {
                DictionaryImportResult.Success(deduped)
            }
        } catch (e: Exception) {
            DictionaryImportResult.Error("解析失败：${e.message ?: "未知错误"}")
        }
    }

    // ---------------- JSON ----------------

    private fun parseJson(text: String): List<DictionaryEntry> {
        val arr = JSONArray(text.removePrefix("\uFEFF"))
        val out = ArrayList<DictionaryEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val word = o.optString("word").trim()
            if (word.isEmpty()) continue
            out.add(
                DictionaryEntry(
                    word = word.lowercase(),
                    lemma = o.optString("lemma").trim().ifEmpty { word }.lowercase(),
                    phonetic = o.optString("phonetic").trim(),
                    partOfSpeech = o.optString("partOfSpeech").trim(),
                    chineseMeaning = o.optString("chineseMeaning").trim(),
                    englishDefinition = o.optString("englishDefinition").trim(),
                    exampleSentence = o.optString("exampleSentence").trim(),
                ),
            )
        }
        return out
    }

    // ---------------- CSV ----------------

    private fun parseCsv(text: String): List<DictionaryEntry> {
        val rows = parseCsvRows(text.removePrefix("\uFEFF"))
        if (rows.isEmpty()) return emptyList()

        val header = rows[0].map { it.trim().lowercase() }
        fun col(name: String) = header.indexOf(name.lowercase())
        val wIdx = col("word")
        if (wIdx < 0) throw IllegalArgumentException("CSV 缺少 word 列")
        val lIdx = col("lemma")
        val phIdx = col("phonetic")
        val posIdx = col("partOfSpeech")
        val cnIdx = col("chineseMeaning")
        val enIdx = col("englishDefinition")
        val exIdx = col("exampleSentence")

        fun cell(row: List<String>, idx: Int) = if (idx in row.indices) row[idx].trim() else ""

        val out = ArrayList<DictionaryEntry>(rows.size)
        for (r in rows.drop(1)) {
            val word = cell(r, wIdx)
            if (word.isEmpty()) continue
            out.add(
                DictionaryEntry(
                    word = word.lowercase(),
                    lemma = cell(r, lIdx).ifEmpty { word }.lowercase(),
                    phonetic = cell(r, phIdx),
                    partOfSpeech = cell(r, posIdx),
                    chineseMeaning = cell(r, cnIdx),
                    englishDefinition = cell(r, enIdx),
                    exampleSentence = cell(r, exIdx),
                ),
            )
        }
        return out
    }

    /** 解析 CSV，支持双引号包裹字段（含逗号、换行、转义双引号 ""）。 */
    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(field.toString()); field.setLength(0) }
                c == '\r' -> { /* 忽略，配合 \r\n */ }
                c == '\n' -> { row.add(field.toString()); rows.add(row); row = ArrayList(); field.setLength(0) }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        // 丢弃完全空白的行
        return rows.filter { cells -> cells.any { it.isNotBlank() } }
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }
}
