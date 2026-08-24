package com.example.englishreader.data.exporter

import com.example.englishreader.data.local.entity.VocabularyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiTsvTest {

    @Test
    fun `exports a phrase as one Anki card with its reading context`() {
        val item = VocabularyItem(
            word = "take cues from",
            partOfSpeech = "固定搭配",
            chineseMeaning = "从……获得提示或借鉴",
            sourceSentence = "Young writers take cues from experienced editors.",
            sourceBookTitle = "Writing Well",
            sourceChapterTitle = "Chapter 2",
        )

        assertEquals(
            "take cues from\t【固定搭配】从……获得提示或借鉴" +
                "<br>原句：Young writers take cues from experienced editors." +
                "<br>来源：Writing Well - Chapter 2<br>备注：",
            AnkiTsv.line(item, item.chineseMeaning),
        )
    }

    @Test
    fun `sanitizes tabs and newlines so export remains exactly two columns`() {
        val item = VocabularyItem(
            word = "make\tprogress",
            partOfSpeech = "固定\n搭配",
            chineseMeaning = "取得\t进展",
            englishDefinition = "move\nforward",
            note = "review\rlater",
        )

        val line = AnkiTsv.line(item, item.chineseMeaning)

        assertEquals(1, line.count { it == '\t' })
        assertTrue(line.startsWith("make progress\t【固定 搭配】取得 进展"))
        assertTrue(line.contains("English definition: move forward"))
        assertTrue(line.endsWith("备注：review later"))
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
    }
}
