package com.example.englishreader.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.englishreader.data.local.entity.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/** 文件导入结果。 */
sealed interface ImportResult {
    /** 单文件文本（TXT / Markdown）。 */
    data class Text(val title: String, val content: String, val format: BookFormat) : ImportResult

    /** EPUB 书籍（已解析出章节与目录）。 */
    data class Epub(
        val title: String,
        val author: String,
        val chapters: List<ParsedChapter>,
        val toc: List<ParsedTocItem>,
    ) : ImportResult

    data class Error(val message: String) : ImportResult
}

/**
 * 通过 Storage Access Framework 读取用户选择的 .txt / .md / .epub 文件。
 *
 * 说明：导入时直接把内容读出并存入 Room（不持有长期 Uri 权限），
 * 因此导入后即使原文件移动/删除也不影响已导入内容。
 */
class DocumentImporter(private val context: Context) {

    private val epubParser = EpubParser()

    suspend fun read(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val displayName = queryDisplayName(uri) ?: "导入文档"
            val lower = displayName.lowercase()
            when {
                lower.endsWith(".epub") -> readEpub(uri, displayName)
                lower.endsWith(".txt") -> readText(uri, displayName, BookFormat.TXT)
                lower.endsWith(".md") || lower.endsWith(".markdown") ->
                    readText(uri, displayName, BookFormat.MARKDOWN)
                else -> ImportResult.Error("仅支持 .txt / .md / .epub 文件")
            }
        } catch (e: Exception) {
            ImportResult.Error("读取失败：${e.message ?: "未知错误"}")
        }
    }

    private fun readText(uri: Uri, displayName: String, format: BookFormat): ImportResult {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        } ?: return ImportResult.Error("无法打开该文件")

        val content = normalize(raw)
        if (content.isBlank()) return ImportResult.Error("文件内容为空")
        return ImportResult.Text(title = stripExtension(displayName), content = content, format = format)
    }

    private fun readEpub(uri: Uri, displayName: String): ImportResult {
        // EPUB 是 zip，需要随机访问；先拷贝到 cache 临时文件再用 ZipFile 解析。
        val temp = File.createTempFile("import_", ".epub", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return ImportResult.Error("无法打开该文件")

            val parsed = try {
                epubParser.parse(temp)
            } catch (e: EpubParseException) {
                return ImportResult.Error(e.message ?: "EPUB 解析失败")
            } catch (e: Exception) {
                return ImportResult.Error("EPUB 解析失败：${e.message ?: "未知错误"}")
            }

            return ImportResult.Epub(
                title = parsed.title.ifBlank { stripExtension(displayName) },
                author = parsed.author,
                chapters = parsed.chapters,
                toc = parsed.toc,
            )
        } finally {
            temp.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 去掉 BOM、统一换行，方便按空行切分段落。 */
    private fun normalize(text: String): String =
        text.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
}
