package com.ggumtak.readeraplus.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsVoicesTest {
    private fun v(name: String, lang: String, language: String, quality: Int = 400, network: Boolean = false, notInstalled: Boolean = false) =
        TtsVoices.Info(name, lang, language, quality, network, notInstalled)

    private val voices = listOf(
        v("en-us-x-sfg-local", "en", "영어", 300),
        v("ko-kr-x-ism-network", "ko", "한국어", 400, network = true),
        v("ja-jp-x-htm-local", "ja", "일본어", 200),
        v("ko-kr-x-ism-local", "ko", "한국어", 400),
        v("en-gb-x-rjs-local", "en", "영어", 400, notInstalled = true),
    )

    @Test
    fun koreanVoicesOnlyWhenTheEngineHasThem() {
        val list = TtsVoices.list(voices)
        assertEquals(
            listOf("한국어 · 목소리 1 (고음질, 오프라인)", "한국어 · 목소리 2 (고음질, 온라인)"),
            list.map { it.second },
        )
        assertEquals("ko-kr-x-ism-local", list[0].first.name)
    }

    @Test
    fun theSavedVoicesLanguageStaysListedAfterKorean() {
        // A saved English voice keeps English in the list (numbered within its language); Japanese stays out.
        assertEquals(
            listOf(
                "한국어 · 목소리 1 (고음질, 오프라인)",
                "한국어 · 목소리 2 (고음질, 온라인)",
                "영어 · 목소리 1 (고음질, 오프라인, 설치 필요)",
                "영어 · 목소리 2 (보통 음질, 오프라인)",
            ),
            TtsVoices.list(voices, keepLang = "en").map { it.second },
        )
    }

    @Test
    fun everyLanguageWithoutAKoreanVoice() {
        val list = TtsVoices.list(voices.filter { it.lang != "ko" })
        assertEquals(
            listOf("영어 · 목소리 1 (고음질, 오프라인, 설치 필요)", "영어 · 목소리 2 (보통 음질, 오프라인)", "일본어 · 목소리 1 (저음질, 오프라인)"),
            list.map { it.second },
        )
    }

    @Test
    fun languageNameFallsBackToTheCode() {
        val list = TtsVoices.list(listOf(v("xx-voice", "xx", "")))
        assertEquals("xx · 목소리 1 (고음질, 오프라인)", list.single().second)
        assertTrue(TtsVoices.list(emptyList()).isEmpty())
        assertEquals("기본 목소리", TtsVoices.DEFAULT)
    }

    @Test
    fun quality() {
        assertEquals("고음질", TtsVoices.quality(500))
        assertEquals("고음질", TtsVoices.quality(400))
        assertEquals("보통 음질", TtsVoices.quality(300))
        assertEquals("저음질", TtsVoices.quality(100))
    }
}
