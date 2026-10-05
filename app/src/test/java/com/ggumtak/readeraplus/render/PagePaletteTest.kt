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
        // A short, nearly black shadow toward the lower right, fitted to the screenshot: ≈ 2.2 px right, 1.1 px down,
        // sigma ≈ 1.25 px, 88 % black (at ≈ 2.75 px per dp).
        assertTrue(m.hasShadow)
        assertEquals(0.67f, m.shadowDxDp, 0f)
        assertEquals(0.4f, m.shadowDyDp, 0f)
        assertEquals(0.45f, m.shadowSigmaDp, 0f)
        assertEquals(0xE0000000.toInt(), m.shadowColor)
        // A dark page (night quote fills, the night e-ink cadence) whose pictures keep their colours.
        assertTrue(m.dark)
        assertFalse(m.invertImages)
        // ≈ 9.4 : 1, like the screenshot.
        assertEquals(9.4, contrast(m.text, m.background), 0.1)
    }

    @Test
    fun shadowRadiusIsAndroidsForTheMeasuredBlur() {
        // Android blurs by sigma = 0.57735 · radius + 0.5 px: the radius is what gives the measured sigma back.
        for (sigma in listOf(0.6f, 1.0f, 1.24f, 2f, 5f)) {
            assertEquals("sigma $sigma", sigma, 0.57735f * PagePalette.radiusForSigma(sigma) + 0.5f, 1e-4f)
        }
        // 0.5 px is the sharpest Android draws; a radius of 0 would draw no shadow at all.
        assertEquals(0.01f, PagePalette.radiusForSigma(0.5f), 0f)
        assertEquals(0.01f, PagePalette.radiusForSigma(0.2f), 0f)
        // On a 2.75 density phone the 마루뷰어 blur (sigma 1.24 px) is a radius of ≈ 1.28 px, not the sigma itself.
        val m = PagePalette.MARU
        assertEquals(1.28f, m.shadowRadiusPx(2.75f), 0.01f)
        assertTrue(m.shadowRadiusPx(1f) > 0f)
        assertEquals(0f, PagePalette.PAPER.shadowRadiusPx(2.75f), 0f)
        assertEquals(0f, PagePalette.NIGHT.shadowRadiusPx(2.75f), 0f)
    }

    @Test
    fun aThemeThatInvertHidesDrawsTheSamePage() {
        val night = ReaderSettings(invert = true)
        assertTrue(PagePalette.drawSame(night, night.copy(pageTheme = PageTheme.MARU)))
        assertTrue(PagePalette.drawSame(night.copy(pageTheme = PageTheme.MARU), night))
        // Without 흑백 반전 the theme is seen; anything else changed is a repaint too.
        assertFalse(PagePalette.drawSame(ReaderSettings(), ReaderSettings(pageTheme = PageTheme.MARU)))
        assertFalse(PagePalette.drawSame(night, night.copy(pageTheme = PageTheme.MARU, fontWeight = 600)))
        assertFalse(PagePalette.drawSame(night, ReaderSettings()))
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

    @Test
    fun progressLineIsReadErasFaintGreys() {
        // The user's ReadEra screenshots (S25, 2026-10-05): line #D1D1D1 and dots #B4B4B4 on white, #1F1F1F and
        // #323232 on black. Not the status colour: on the MaruViewer page no gold, the same rule toward white.
        assertEquals(rgb(0xD1), PagePalette.PAPER.progressLine)
        assertEquals(rgb(0xB4), PagePalette.PAPER.progressDot)
        assertEquals(rgb(0x1F), PagePalette.NIGHT.progressLine)
        assertEquals(rgb(0x32), PagePalette.NIGHT.progressDot)
        assertEquals(rgb(0x4B), PagePalette.MARU.progressLine)
        assertEquals(rgb(0x5A), PagePalette.MARU.progressDot)
        // 흑백 반전 over the MaruViewer theme is the black page's.
        assertSame(PagePalette.NIGHT, PagePalette.of(PageTheme.MARU, true))
        for (p in listOf(PagePalette.PAPER, PagePalette.NIGHT, PagePalette.MARU)) {
            // Faint: far closer to the page than the text is; the dots a little stronger than the line.
            assertTrue(contrast(p.progressLine, p.background) < 1.6)
            assertTrue(contrast(p.progressDot, p.background) > contrast(p.progressLine, p.background))
            assertTrue(contrast(p.progressDot, p.background) < 2.2)
            assertTrue(contrast(p.text, p.background) > 4 * contrast(p.progressDot, p.background))
        }
    }

    @Test
    fun progressLineOnEinkIsWholePanelLevels() {
        // The Comet's 16 greys: 흰 바탕 #CCCCCC line, #BBBBBB dots; 흑백 반전 #222222 / #333333; MaruViewer's page (shown
        // as #333333) #555555 / #666666. Each a level of its own: the line two off the page, the dots one off the line.
        assertEquals(rgb(0xCC), PagePalette.PAPER.inkProgressLine)
        assertEquals(rgb(0xBB), PagePalette.PAPER.inkProgressDot)
        assertEquals(rgb(0x22), PagePalette.NIGHT.inkProgressLine)
        assertEquals(rgb(0x33), PagePalette.NIGHT.inkProgressDot)
        assertEquals(rgb(0x55), PagePalette.MARU.inkProgressLine)
        assertEquals(rgb(0x66), PagePalette.MARU.inkProgressDot)
        for (p in listOf(PagePalette.PAPER, PagePalette.NIGHT, PagePalette.MARU)) {
            // In panel levels (the page as the panel shows it): the line ≥ 2 from the page, the dots ≥ 1 past the line.
            val page = ((p.background and 0xFF) + 8) / 17
            val line = (p.inkProgressLine and 0xFF) / 17
            val dot = (p.inkProgressDot and 0xFF) / 17
            assertTrue("line $line on page $page", Math.abs(line - page) >= 2)
            assertTrue("dot $dot past line $line", Math.abs(dot - page) >= Math.abs(line - page) + 1)
        }
        // A grey that would round into the page (or the line) moves on one level, the page's way.
        assertEquals(0xEE, PagePalette.inkGrey(250, 255, darker = true))
        assertEquals(0xCC, PagePalette.inkGrey(209, 255, darker = true))
        assertEquals(0x11, PagePalette.inkGrey(5, 0, darker = false))
        assertEquals(0x33, PagePalette.inkGrey(50, 0x22, darker = false))
        assertEquals(0x44, PagePalette.inkGrey(52, 0x33, darker = false))
        // Or [steps] levels: MaruViewer's line (#4B4B4B, nearest #444444) on its page (#323232, shown as #333333).
        assertEquals(0x55, PagePalette.inkGrey(0x4B, 0x32, darker = false, steps = 2))
        assertEquals(0xDD, PagePalette.inkGrey(250, 255, darker = true, steps = 2))
        assertEquals(0xCC, PagePalette.inkGrey(209, 255, darker = true, steps = 2))
        // At the ends of the scale it stays on the panel.
        assertEquals(0, PagePalette.inkGrey(3, 0, darker = true))
        assertEquals(255, PagePalette.inkGrey(250, 255, darker = false))
        assertEquals(255, PagePalette.inkGrey(240, 0xEE, darker = false, steps = 2))
        for (v in 0..255) assertEquals("$v", 0, PagePalette.inkGrey(v, 255, darker = true) % 17)
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
