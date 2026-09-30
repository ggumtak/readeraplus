package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFormatTest {

    @Test
    fun pageLabels() {
        // plain numbers, estimated or not (no "~")
        assertEquals("12 / 3259", ReaderFormat.pageLabel(12, 3259))
        assertEquals("40 / 120", ReaderFormat.pageLabel(40, 120))
        // a total below the page (estimates) never shows "50 / 30"
        assertEquals("50 / 50", ReaderFormat.pageLabel(50, 30))
    }

    @Test
    fun percent() {
        assertEquals(0, ReaderFormat.percent(0f))
        assertEquals(34, ReaderFormat.percent(0.345f))
        assertEquals(29, ReaderFormat.percent(0.29f))
        assertEquals(100, ReaderFormat.percent(1f))
        assertEquals(100, ReaderFormat.percent(1.4f))
        assertEquals(0, ReaderFormat.percent(-1f))
    }

    @Test
    fun clock() {
        assertEquals("14:05", ReaderFormat.clock(14, 5, true))
        assertEquals("09:30", ReaderFormat.clock(9, 30, true))
        assertEquals("2:05", ReaderFormat.clock(14, 5, false))
        assertEquals("12:00", ReaderFormat.clock(0, 0, false))
        assertEquals("12:59", ReaderFormat.clock(12, 59, false))
    }

    @Test
    fun footers() {
        assertEquals("34%  ·  14:05  ·  80%", ReaderFormat.footerRight(34, "14:05", 80))
        assertEquals("14:05", ReaderFormat.footerRight(null, "14:05", -1))
        assertNull(ReaderFormat.footerRight(null, null, null))
        assertEquals("12 / 3259", ReaderFormat.footerLeft("12 / 3259", null))
        assertEquals("12 / 3259  ·  챕터 5쪽 남음", ReaderFormat.footerLeft("12 / 3259", 5))
        assertEquals("챕터 마지막 쪽", ReaderFormat.footerLeft(null, 0))
        assertNull(ReaderFormat.footerLeft(null, null))
    }

    @Test
    fun snippets() {
        val text = "첫 줄입니다.\n\n  둘째   줄$OBJECT_CHAR 셋째 줄."
        assertEquals("첫 줄입니다. 둘째 줄 셋째 줄.", ReaderFormat.snippet(text, 0, text.length))
        val long = "가".repeat(200)
        val s = ReaderFormat.snippet(long, 0, long.length, max = 80)
        assertEquals(81, s.length)
        assertTrue(s.endsWith("…"))
        assertEquals("", ReaderFormat.snippet("abc", 5, 2))
        assertEquals("bc", ReaderFormat.snippet("abc", 1, 99))
    }

    @Test
    fun chipsAndPreview() {
        assertEquals("← 돌아가기 (p. 12)", ReaderFormat.returnChip(12))
        assertEquals("p. 7 · 3화 등불", ReaderFormat.previewLabel(7, " 3화 등불 "))
        assertEquals("p. 7", ReaderFormat.previewLabel(7, null))
        // nothing the reader formats shows a tilde any more
        for (s in listOf(
            ReaderFormat.pageLabel(3, 9), ReaderFormat.returnChip(3), ReaderFormat.previewLabel(3, "제목"),
            ReaderFormat.footerLeft(ReaderFormat.pageLabel(3, 9), 4)!!, ReaderFormat.chapterLeft(2),
        )) {
            assertTrue(s, '~' !in s)
        }
        assertEquals("밝기 40%", ReaderFormat.brightness(0.4f))
        assertEquals("밝기 자동", ReaderFormat.brightness(-1f))
        assertEquals("자동 넘김 켜짐 (30초)", ReaderFormat.autoTurnOn(30))
    }
}
