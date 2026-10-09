package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupQueryTest {
    @Test
    fun queryTrimsCollapsesAndAppendsMeaning() {
        assertEquals("비명 뜻", LookupQuery.query("  비명 "))
        assertEquals("가 나 다 뜻", LookupQuery.query("가\n\n 나\t 다　"))
        assertEquals("", LookupQuery.query(" \n\t "))
        assertEquals("", LookupQuery.query(""))
    }

    @Test
    fun queryIsCappedAt100Chars() {
        val q = LookupQuery.query("가".repeat(300))
        assertEquals(100 + LookupQuery.SUFFIX.length, q.length)
        assertTrue(q.endsWith(" 뜻"))
        // The cut never leaves a space before the suffix, nor half a surrogate pair.
        assertEquals("가".repeat(99) + " 뜻", LookupQuery.query("가".repeat(99) + " " + "나".repeat(50)))
        val emoji = "😀"
        assertEquals("a" + emoji.repeat(49) + " 뜻", LookupQuery.query("a" + emoji.repeat(60)))
    }

    @Test
    fun urlEncodesSpacesAsPercent20() {
        assertEquals(
            "https://m.search.naver.com/search.naver?query=%EB%B9%84%EB%AA%85%20%EB%9C%BB",
            LookupQuery.naverUrl(LookupQuery.query("비명")),
        )
        assertEquals(
            "https://m.search.naver.com/search.naver?query=a%2Bb%26c%20%EB%9C%BB",
            LookupQuery.naverUrl(LookupQuery.query("a+b&c")),
        )
    }

    @Test
    fun heightIsClampedToThirtyToNinetyPercent() {
        assertEquals(1200, LookupQuery.startHeight(2000))
        assertEquals(600, LookupQuery.clampHeight(100, 2000))
        assertEquals(1800, LookupQuery.clampHeight(5000, 2000))
        assertEquals(1000, LookupQuery.clampHeight(1000, 2000))
    }

    @Test
    fun wordIsTheCleanedSelectionWithoutMeaning() {
        assertEquals("비명", LookupQuery.word("  비명 "))
        assertEquals("가 나 다", LookupQuery.word("가\n\n 나\t 다　"))
        assertEquals("", LookupQuery.word(" \n "))
        assertEquals(100, LookupQuery.word("가".repeat(300)).length)
    }

    @Test
    fun otherAppsGetMeaningExceptAnkiAndTranslators() {
        assertEquals("사과 뜻", LookupQuery.appText("com.openai.chatgpt", "ChatGPT에게 물어보세요", " 사과 "))
        assertEquals(" 사과 ", LookupQuery.appText("com.ichi2.anki", "Anki 카드", " 사과 "))
        assertEquals("사과", LookupQuery.appText("com.naver.labs.translator", "파파고", " 사과 "))
        // A translator is found by its label as well (the phone's own "번역" entry).
        assertEquals("사과", LookupQuery.appText("com.some.unknown", "번역", " 사과 "))
        assertEquals("apple", LookupQuery.appText("com.some.unknown", "Google Translate", " apple "))
        assertEquals("   ", LookupQuery.appText("com.some.unknown", "번역", "   "))
        assertTrue(LookupQuery.isAnki("com.ichi2.anki"))
        assertFalse(LookupQuery.isAnki("com.google.android.apps.translate"))
    }

    @Test
    fun translatorsByPackageAndLabel() {
        for (pkg in listOf(
            "com.google.android.apps.translate", "com.naver.labs.translator", "com.samsung.android.app.interpreter",
            "com.microsoft.translator", "com.deepl.mobiletranslator",
        )) assertTrue(pkg, LookupQuery.isTranslator(pkg, "x"))
        for (label in listOf("번역", "Translate", "translate", "파파고", "Papago", "DeepL", "DEEPL 번역기")) {
            assertTrue(label, LookupQuery.isTranslator("com.some.unknown", label))
        }
        assertFalse(LookupQuery.isTranslator("com.anthropic.claude", "Claude에게 묻기"))
        assertFalse(LookupQuery.isTranslator("com.google.android.googlequicksearchbox", "Gemini에게 물어보기"))
    }
}
