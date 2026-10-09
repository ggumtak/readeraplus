package com.ggumtak.readeraplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.text.Normalizer

/** `LookupWords.key` (N §5.4): the grouping key of the 단어장. */
class LookupWordsTest {

    @Test
    fun nfc() {
        val nfd = Normalizer.normalize("비명", Normalizer.Form.NFD)
        assertEquals("비명", LookupWords.key(nfd))
    }

    @Test
    fun surroundingQuotesBracketsAndPunctuationAreStripped() {
        assertEquals("비명", LookupWords.key("“비명”"))
        assertEquals("비명", LookupWords.key("「『비명』」"))
        assertEquals("비명", LookupWords.key("(비명)..."))
        assertEquals("비명", LookupWords.key("《비명》!?"))
        assertEquals("비명", LookupWords.key("  ‘비명’…  "))
        assertEquals("light", LookupWords.key("\"Light,\""))
        assertEquals("비명", LookupWords.key("[〈비명〉]·~"))
        // Inner punctuation stays.
        assertEquals("o'brien", LookupWords.key("O'Brien"))
        assertEquals("a.b", LookupWords.key("a.b."))
        assertEquals("", LookupWords.key(" “” ... "))
    }

    @Test
    fun whitespaceCollapsesAndAsciiLowercases() {
        assertEquals("new york", LookupWords.key("  New\n\t York  "))
        assertEquals("Äbc", LookupWords.key("ÄBC")) // only ASCII letters fold
        assertEquals("a b", LookupWords.key("a b"))
    }

    @Test
    fun capsAtOneHundredChars() {
        assertEquals(100, LookupWords.key("가".repeat(300)).length)
        val emoji = "😀".repeat(80) // 160 UTF-16 units
        val k = LookupWords.key(emoji)
        assertEquals(100, k.length)
        assertEquals(false, Character.isHighSurrogate(k.last()))
    }

    @Test
    fun hangulParticlesAreNotStripped() {
        assertNotEquals(LookupWords.key("비명"), LookupWords.key("비명을"))
        assertEquals("비명을", LookupWords.key("비명을"))
        assertEquals("비명이", LookupWords.key("“비명이”"))
    }
}
