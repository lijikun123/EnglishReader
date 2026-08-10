package com.example.englishreader.data.sync

import com.example.englishreader.data.local.entity.ReadingChapter
import com.example.englishreader.data.local.entity.ReadingItem
import com.example.englishreader.data.local.entity.ReadingTocItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Deterministic, content-only portable representation of a local book. */
@Serializable
data class BookBundleV1(
    val schemaVersion: Int = 1,
    val format: String,
    val content: String,
    val chapters: List<BookBundleChapter>,
    val toc: List<BookBundleTocItem>,
)

@Serializable
data class BookBundleChapter(
    val chapterIndex: Int,
    val title: String,
    val content: String,
)

@Serializable
data class BookBundleTocItem(
    val chapterIndex: Int,
    val label: String,
    val href: String,
    val level: Int,
    val orderIndex: Int,
    val anchorParagraph: Int,
)

data class EncodedBookBundle(
    val raw: ByteArray,
    val sha256: String,
)

object BookBundleCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
        ignoreUnknownKeys = false
    }

    fun encode(
        item: ReadingItem,
        chapters: List<ReadingChapter>,
        toc: List<ReadingTocItem>,
    ): EncodedBookBundle {
        val bundle = BookBundleV1(
            format = item.format.name,
            content = item.content,
            chapters = chapters.sortedBy { it.chapterIndex }.map {
                BookBundleChapter(it.chapterIndex, it.title, it.content)
            },
            toc = toc.sortedWith(compareBy<ReadingTocItem> { it.orderIndex }.thenBy { it.id }).map {
                BookBundleTocItem(it.chapterIndex, it.label, it.href, it.level, it.orderIndex, it.anchorParagraph)
            },
        )
        val raw = json.encodeToString(bundle).encodeToByteArray()
        return EncodedBookBundle(raw, sha256(raw))
    }

    fun decode(raw: ByteArray): BookBundleV1 {
        val bundle = json.decodeFromString<BookBundleV1>(raw.decodeToString())
        require(bundle.schemaVersion == 1) { "不支持的书籍同步格式" }
        require(bundle.format in setOf("TXT", "MARKDOWN", "EPUB")) { "不支持的书籍格式" }
        return bundle
    }

    fun sha256(raw: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
}
