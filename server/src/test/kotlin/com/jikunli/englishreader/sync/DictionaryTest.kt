package com.jikunli.englishreader.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class DictionaryTest {
    @Test
    fun normalizesReaderTokensAndUsesTheSameSimpleLemmaFallbackAsAndroid() {
        assertEquals("awarded", normalizeDictionaryWord("“Awarded,”"))
        assertEquals("study", dictionaryLemmaCandidate("studies"))
        assertEquals("award", dictionaryLemmaCandidate("awarded"))
        assertEquals("class", dictionaryLemmaCandidate("class"))
    }
}
