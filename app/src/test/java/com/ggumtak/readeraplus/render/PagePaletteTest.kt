package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.settings.PageTheme
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StylePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The page colours of 화면 색 and 흑백 반전 (웹소설 = the MaruViewer page, 2026-10-04). */
class PagePaletteTest {

    private fun rgb(v: Int): Int = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v

    @Test
    fun invertWinsOverTheTheme() {
        assertSame(PagePalette.PAPER, PagePalette.of(ReaderSettings()))
        assertSame(PagePalette.MARU, PagePalette.of(ReaderSettings(pageTheme = PageTheme.MARU)))
        assertSame(PagePalette.NIGHT, PagePalette.of(ReaderSettings(invert = true)))
        assertSame(PagePalette.NIGHT, PagePalette.of(ReaderSettings(invert = true, pageTheme = PageTheme.MARU)))
        // The 웹소설 preset brings the MaruViewer page; 흑백 반전 still wins over it.
        assertSame(PagePalette.MARU, PagePalette.of(StylePreset.MARU.applyTo(ReaderSettings())))
        assertSame(PagePalette.NIGHT, PagePalette.of(StylePreset.MARU.applyTo(ReaderSettings(invert = true))))
    }

    @Test
    fun paperAndNightAreTheColoursOfBefore() {
        // Nobody who never picks a theme sees a change: black on white, white on black, no shadow.
        val p = PagePalette.PAPER
        assertEquals(rgb(255), p.background)
        assertEquals(rgb(0), p.text)
        assertEquals(rgb(0), p.status)
        assertFalse(p.hasShadow)
        assertFalse(p.dark)
        assertFalse(p.invertImages)
        val n = PagePalette.NIGHT
        assertEquals(rgb(0), n.background)
        assertEquals(rgb(255), n.text)
        assertEquals(rgb(255), n.status)
        assertFalse(n.hasShadow)
        assertTrue(n.dark)
        assertTrue(n.invertImages)
        // The highlight greys: exactly rgb(v) by day and rgb(255 - v) at night, as the renderer drew them.
        for (v in 0..255) {
            assertEquals("paper $v", rgb(v), p.grey(v))
            assertEquals("night $v", rgb(255 - v), n.grey(v))
        }
    }

    @Test
    fun maruIsTheMeasuredMaruViewerPage() {
        val m = PagePalette.MARU
        assertEquals(0xFF323232.toInt(), m.background)
        assertEquals(0xFFDDDDDD.toInt(), m.text)
        assertEquals(0xFFF0D096.toInt(), m.status)
        // A short, nearly black shadow toward the lower right (≈ 3 px right, 1.5–2 px down at ≈ 2.75 px per dp).
        assertTrue(m.hasShadow)
        assertEquals(1.0f, m.shadowDxDp, 0f)
        assertEquals(0.6f, m.shadowDyDp, 0f)
        assertEquals(0.6f, m.shadowRadiusDp, 0f)
        assertEquals(0xD9000000.toInt(), m.shadowColor)
        // A dark page (night quote fills, the night e-ink cadence) whose pictures keep their colours.
        assertTrue(m.dark)
        assertFalse(m.invertImages)
        // ≈ 9.4 : 1, like the screenshot.
        assertEquals(9.4, contrast(m.text, m.background), 0.1)
    }

    @Test
    fun greysRunFromTheBackgroundToTheText() {
        val m = PagePalette.MARU
        assertEquals(m.background, m.grey(255))
        assertEquals(m.text, m.grey(0))
        assertEquals(m.background, m.grey(999))
        assertEquals(m.text, m.grey(-5))
        // Selection (0xAA) > search (0xBB) > TTS (0xEE) away from the page, all between the page and the text.
        val sel = m.grey(0xAA) and 0xFF
        val search = m.grey(0xBB) and 0xFF
        val tts = m.grey(0xEE) and 0xFF
        assertEquals(107, sel)
        assertTrue(sel > search && search > tts && tts > 0x32 && sel < 0xDD)
        // Neutral greys stay neutral.
        val g = m.grey(0xAA)
        assertEquals(g and 0xFF, (g shr 8) and 0xFF)
        assertEquals(g and 0xFF, (g shr 16) and 0xFF)
        // The text stays readable on a selected word.
        assertTrue(contrast(m.text, m.grey(0xAA)) >= 3.0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
}
