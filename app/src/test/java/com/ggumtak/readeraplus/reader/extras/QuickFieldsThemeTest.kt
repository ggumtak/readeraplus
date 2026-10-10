package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.settings.PageTheme
import com.ggumtak.readeraplus.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 흰색 picks RIDI's 리디바탕 too (user, 2026-10-10); the other colours keep the font. */
class QuickFieldsThemeTest {

    @Test
    fun whitePicksRidisFontAndWeightButNotItsSpacing() {
        val s = ReaderSettings(fontId = "nanummyeongjo", fontWeight = 450, lineHeightPct = 200, marginLeftDp = 30,
            pageTheme = PageTheme.MARU, invert = true)
        val w = QuickFields.withTheme(s, PageTheme.PAPER)
        assertEquals(PageTheme.PAPER, w.pageTheme)
        assertFalse(w.invert)
        assertEquals("ridibatang", w.fontId)
        assertEquals(400, w.fontWeight)
        // "줄 간격과 여백은 안 따라해도 돼"
        assertEquals(s.copy(pageTheme = PageTheme.PAPER, invert = false, fontId = "ridibatang", fontWeight = 400), w)
        // grey and black keep the font
        for (t in listOf(PageTheme.MARU, PageTheme.BLACK)) {
            val o = QuickFields.withTheme(s, t)
            assertEquals(s.copy(pageTheme = t, invert = false), o)
        }
    }
}
