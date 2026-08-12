package com.example.englishreader.data.exporter

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.englishreader.data.local.entity.VocabularyItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/** 生成可由 Android 分享面板读取的临时 UTF-8 TSV 文件。 */
class AnkiExporter(private val context: Context) {

    suspend fun createShareUri(content: String): Uri = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "vocabulary_exports")
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw IOException("无法创建导出目录")
        }

        val file = File(exportDir, "english_reader_vocabulary_${System.currentTimeMillis()}.tsv")
        file.outputStream().use { out ->
            out.write(content.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}

/**
 * Anki 导入用 TSV 格式化（纯函数，便于测试）。
 *  - 每张卡片一行：Front<TAB>Back，无表头
 *  - 正面：单词
 *  - 背面：用 <br> 连接的若干段，字段内 tab/换行被清洗为空格，避免 Anki 错列
 */
object AnkiTsv {

    fun line(item: VocabularyItem, chineseMeaning: String): String =
        "${clean(item.word)}\t${buildBack(item, chineseMeaning)}"

    private fun buildBack(item: VocabularyItem, chineseMeaning: String): String {
        val segments = ArrayList<String>()

        // 1. 【词性】中文释义（中文释义已由调用方做空值兜底，这里再兜一层）
        val pos = clean(item.partOfSpeech)
        val cn = clean(chineseMeaning).ifEmpty { "暂无释义" }
        segments += if (pos.isNotEmpty()) "【$pos】$cn" else cn

        // 2~4. 英文释义 / 例句 / 原句（为空则跳过该段）
        clean(item.englishDefinition).takeIf { it.isNotEmpty() }?.let { segments += "English definition: $it" }
        clean(item.exampleSentence).takeIf { it.isNotEmpty() }?.let { segments += "Example: $it" }
        clean(item.sourceSentence).takeIf { it.isNotEmpty() }?.let { segments += "原句：$it" }

        // 5. 来源：书名 / 文章名 - 章节名
        sourceLine(item)?.let { segments += "来源：$it" }

        // 6. 备注（始终保留，可为空）
        segments += "备注：${clean(item.note)}"

        return segments.joinToString("<br>")
    }

    private fun sourceLine(item: VocabularyItem): String? {
        val book = clean(item.sourceBookTitle)
        val chapter = clean(item.sourceChapterTitle)
        return when {
            book.isNotEmpty() && chapter.isNotEmpty() -> "$book - $chapter"
            book.isNotEmpty() -> book
            chapter.isNotEmpty() -> chapter
            else -> null
        }
    }

    /** 去掉制表符/换行/多余空格，避免破坏 TSV 的列与行。 */
    private fun clean(s: String): String =
        s.replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex(" +"), " ")
            .trim()
}
