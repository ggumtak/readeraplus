package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutKeysTest {
    private val s = ReaderSettings()
    private val density = 2f
    private val statusPx = 22f // 11sp at 2x

    @Test
    fun geometryWithMarginsHeaderFooter() {
        val g = LayoutKeys.geometry(s, 720, 1440, density, statusPx)
        val band = Math.round(statusPx * LayoutKeys.STATUS_BAND)
        assertEquals(36, g.contentLeft)
        assertEquals(32 + band, g.contentTop)
        assertEquals(720 - 72, g.contentWidth)
        assertEquals(1440 - 64 - 2 * band, g.contentHeight)
    }

    @Test
    fun geometryWithoutMarginsAndBars() {
        val t = s.copy(pageMargins = false, showHeader = false, showFooter = false)
        val g = LayoutKeys.geometry(t, 720, 1440, density, statusPx)
        assertEquals(8, g.contentLeft)
        assertEquals(8, g.contentTop)
        assertEquals(704, g.contentWidth)
        assertEquals(1424, g.contentHeight)
    }

    @Test
    fun geometryNeverCollapses() {
        val t = s.copy(marginLeftDp = 400, marginRightDp = 400, marginTopDp = 900, marginBottomDp = 900)
        val g = LayoutKeys.geometry(t, 720, 1440, density, statusPx)
        assertTrue(g.contentWidth >= 16)
        assertTrue(g.contentHeight >= 16)
        assertTrue(g.contentLeft + g.contentWidth <= 720)
        assertTrue(g.contentTop + g.contentHeight <= 1440)
    }

    @Test
    fun configFromSettings() {
        val t = s.copy(lineHeightPct = 185, paragraphSpacingPct = 120, indentPct = 150, align = Align.LEFT, lineBreak = LineBreakMode.CHAR)
        val c = LayoutKeys.config(t, LayoutKeys.geometry(t, 720, 1440, density, statusPx))
        assertEquals(1.85f, c.lineHeightEm, 1e-6f)
        assertEquals(1.2f, c.paragraphSpacingEm, 1e-6f)
        assertEquals(1.5f, c.indentEm, 1e-6f)
        assertEquals(Align.LEFT, c.align)
        assertEquals(LineBreakMode.CHAR, c.lineBreak)
        assertEquals(648, c.width)
    }

    @Test
    fun layoutChangeDetection() {
        assertFalse(LayoutKeys.layoutChanged(s, s.copy(invert = true)))
        assertFalse(LayoutKeys.layoutChanged(s, s.copy(footerClock = false, footerBattery = false, footerChapterLeft = true)))
        assertTrue(LayoutKeys.layoutChanged(s, s.copy(fontSizeSp = 21f)))
        assertTrue(LayoutKeys.layoutChanged(s, s.copy(paragraphSpacingPct = 60)))
        assertTrue(LayoutKeys.layoutChanged(s, s.copy(showFooter = false)))
        assertTrue(LayoutKeys.layoutChanged(s, s.copy(fontId = "nanummyeongjo")))
    }

    @Test
    fun parseChangeDetection() {
        assertFalse(LayoutKeys.parseChanged(s, s.copy(fontSizeSp = 30f), ""))
        assertTrue(LayoutKeys.parseChanged(s, s.copy(txtBlankLines = 3), ""))
        assertTrue(LayoutKeys.parseChanged(s, s.copy(txtReplaceRules = "광고 => "), "MS949"))
    }

    @Test
    fun keyIsStableAndSensitive() {
        val g = LayoutKeys.geometry(s, 720, 1440, density, statusPx)
        fun key(t: ReaderSettings = s, enc: String = "", gg: PageGeometry = g, font: String = "BUNDLED:fonts/RIDIBatang.otf", ver: Int = 1) =
            LayoutKeys.key(t, t.parseOptions(enc), gg, density, font, ver)
        val base = key()
        assertEquals(24, base.length)
        assertEquals(base, key())
        assertEquals(base, key(s.copy(invert = true)))
        assertNotEquals(base, key(s.copy(fontSizeSp = 20.5f)))
        assertNotEquals(base, key(s.copy(letterSpacingPm = 10)))
        assertNotEquals(base, key(enc = "MS949"))
        assertNotEquals(base, key(s.copy(txtDetectChapters = false)))
        assertNotEquals(base, key(gg = LayoutKeys.geometry(s, 1440, 720, density, statusPx)))
        assertNotEquals(base, key(font = "USER:/x.ttf:100:5"))
        assertNotEquals(base, key(ver = 2))
    }
}
