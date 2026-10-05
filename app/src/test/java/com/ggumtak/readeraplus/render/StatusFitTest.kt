package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusBands
import com.ggumtak.readeraplus.settings.StatusItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusFitTest {
    private val comet = 2f      // 720×1440, no cutout
    private val s25 = 3f        // 1080×2340, 87 px camera band in fullscreen
    private val d = ReaderSettings()
    private val noHeader = d.copy(headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE)
    private val footer = d.withSlot(1, 1, StatusItem.PAGE)

    @Test
    fun bandsAreWholeDpFromTheSettingsAlone() {
        // The defaults: MaruViewer's header at 11 sp (4 dp edge + 16 dp glyph box + 2 dp) and the progress line alone
        // (4 dp edge + 12 dp lane).
        assertEquals(16, StatusBands.glyphDp(d))
        assertEquals(22, StatusBands.headerDp(d))
        assertEquals(16, StatusBands.footerDp(d))
        // Off: no header item; no footer item and no progress line.
        assertEquals(0, StatusBands.headerDp(noHeader))
        assertEquals(0, StatusBands.footerDp(d.copy(progressBar = false)))
        // Footer items above the line (4 + 12 + 2 + 16 + 2) or on the edge gap (4 + 16 + 2).
        assertEquals(36, StatusBands.footerDp(footer))
        assertEquals(22, StatusBands.footerDp(footer.copy(progressBar = false)))
        // Every item makes the same band: one item for another never moves the text box.
        for (item in StatusItem.entries) if (item != StatusItem.NONE) {
            assertEquals(22, StatusBands.headerDp(noHeader.withSlot(0, 2, item)))
            assertEquals(36, StatusBands.footerDp(d.withSlot(1, 0, item)))
        }
        // The status size only counts for a band with text: the progress line alone keeps 16 dp.
        assertEquals(16, StatusBands.footerDp(d.copy(statusFontSizeSp = 16f)))
        assertEquals(0, StatusBands.headerDp(noHeader.copy(statusFontSizeSp = 16f)))
        // Px: whole dp × density (Comet, S25).
        assertEquals(44, StatusFit.headerBandPx(d, comet))
        assertEquals(66, StatusFit.headerBandPx(d, s25))
        assertEquals(32, StatusFit.footerBandPx(d, comet))
        assertEquals(48, StatusFit.footerBandPx(d, s25))
        assertEquals(32, StatusFit.glyphPx(d, comet))
        assertEquals(48, StatusFit.glyphPx(d, s25))
    }

    @Test
    fun theGlyphBoxIsRoundedUpToWholeDp() {
        // sp × 1.45 rounded up, without float creep (20 sp is exactly 29 dp).
        for ((sp, dp) in listOf(6f to 9, 8f to 12, 10f to 15, 11f to 16, 11.5f to 17, 12f to 18, 16f to 24, 20f to 29, 40f to 58))
            assertEquals("$sp sp", dp, StatusBands.glyphDp(d.copy(statusFontSizeSp = sp)))
        // At least the status font's ascent + descent at every size the settings allow: Roboto ≈ 0.93 + 0.24, the CJK
        // fallback ≈ 1.16 + 0.29.
        var sp = 6f
        while (sp <= 40f) {
            val box = StatusBands.glyphDp(d.copy(statusFontSizeSp = sp))
            assertTrue(box >= sp * 1.45f - 1e-3f)
            sp += 0.5f
        }
        // An unusable size is drawn, and reserved, as 11 sp; the size is kept within 6..40 sp.
        assertEquals(16, StatusBands.glyphDp(d.copy(statusFontSizeSp = Float.NaN)))
        assertEquals(16, StatusBands.glyphDp(d.copy(statusFontSizeSp = 0f)))
        assertEquals(58, StatusBands.glyphDp(d.copy(statusFontSizeSp = 99f)))
    }

    @Test
    fun theHeaderHugsTheTopOfItsBand() {
        // Comet (no cutout): 11 sp = 22 px, Roboto's ascent 0.93 em, descent 0.24 em. The glyph box starts 4 dp (8 px)
        // below the top edge and ends inside its 32 px box, 2 dp (4 px) above the band's end at 44.
        val ts = 22f
        val a = 0.93f * ts
        val dd = 0.24f * ts
        val glyph = StatusFit.glyphPx(d, comet).toFloat()
        val baseline = StatusFit.headerBaseline(0f, a, dd, glyph, comet)
        assertEquals(8f, baseline - a, 0.001f)
        assertTrue(baseline + dd <= 8f + glyph)
        assertEquals(StatusFit.headerBandPx(d, comet).toFloat(), 8f + glyph + StatusFit.PAD_DP * comet, 0f)
        // S25 fullscreen: the band starts below the 87 px camera band, as on the user's screenshot (the header under the
        // camera hole, not in it): glyph box 99..147, band 87..153.
        val ts3 = 33f
        val a3 = 0.93f * ts3
        val b3 = StatusFit.headerBaseline(87f, a3, 0.24f * ts3, StatusFit.glyphPx(d, s25).toFloat(), s25)
        assertEquals(99f, b3 - a3, 0.001f)
        assertEquals(153, 87 + StatusFit.headerBandPx(d, s25))
    }

    @Test
    fun aTallerFontStaysInsideTheBandsGlyphBox() {
        // A system font scale above 1: a 40 px glyph box in a 32 px one keeps its proportions (× 0.8) inside it.
        val baseline = StatusFit.headerBaseline(0f, 30f, 10f, 32f, comet)
        assertEquals(8f + 24f, baseline, 0.001f)
        assertEquals(8f + 32f, baseline + 8f, 0.001f)
        val footerBaseline = StatusFit.footerBaseline(1440f, false, 30f, 10f, 32f, comet)
        assertEquals(1432f - 8f, footerBaseline, 0.001f)
    }

    @Test
    fun theFooterSitsAboveTheLaneOrTheEdgeGap() {
        // Comet, footer items and the progress line: the lane is rows 1408..1432 (4 dp above the edge); the glyph box
        // ends 2 dp above it (1404) and starts 2 dp inside the band (1440 − 72 = 1368).
        val ts = 22f
        val a = 0.93f * ts
        val dd = 0.24f * ts
        val glyph = StatusFit.glyphPx(footer, comet).toFloat()
        val withLane = StatusFit.footerBaseline(1440f, true, a, dd, glyph, comet)
        assertEquals(1404f, withLane + dd, 0.001f)
        assertEquals(1440f - StatusFit.footerBandPx(footer, comet) + StatusFit.PAD_DP * comet, 1404f - glyph, 0f)
        // The dot sits at the lane's top, below the footer's glyphs.
        assertTrue(withLane + dd <= ProgressMath.yc(1432, 24f, comet) - ProgressMath.rDot(24f, comet))
        // Without the line: on the edge gap, 4 dp above the bottom edge, inside a 22 dp band.
        val noLane = StatusFit.footerBaseline(1440f, false, a, dd, glyph, comet)
        assertEquals(1432f, noLane + dd, 0.001f)
        assertEquals(1440f - StatusFit.footerBandPx(footer.copy(progressBar = false), comet) + StatusFit.PAD_DP * comet, 1432f - glyph, 0f)
    }

    @Test
    fun theLaneIsAlwaysWholeAboveTheEdgeGap() {
        assertEquals(8, StatusFit.edgePx(comet))
        assertEquals(12, StatusFit.edgePx(s25))
        assertEquals(24, StatusFit.lanePx(comet))
        assertEquals(36, StatusFit.lanePx(s25))
        assertEquals(32, StatusFit.laneTopPx(comet))
        assertEquals(48, StatusFit.laneTopPx(s25))
        assertEquals(1432, StatusFit.laneBottomPx(1440, comet))
        assertEquals(2328, StatusFit.laneBottomPx(2340, s25))
        // S25 fullscreen: the dot's row is 2302, where the user's screenshot has it (rows 2293–2311); the Comet's 1415.
        assertEquals(2302, ProgressMath.yc(StatusFit.laneBottomPx(2340, s25), StatusFit.lanePx(s25).toFloat(), s25))
        assertEquals(1415, ProgressMath.yc(StatusFit.laneBottomPx(1440, comet), StatusFit.lanePx(comet).toFloat(), comet))
        // The lane is the footer band's bottom part: the band (16 dp) is exactly edge + lane.
        assertEquals(StatusFit.footerBandPx(d, comet), StatusFit.laneTopPx(comet))
        assertEquals(StatusFit.footerBandPx(d, s25), StatusFit.laneTopPx(s25))
    }
}
