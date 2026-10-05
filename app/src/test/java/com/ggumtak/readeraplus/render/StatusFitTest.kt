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

    /** Roboto-like status glyphs at [ts] px: ascent 0.93 em, descent 0.24 em, ink (a parenthesis) −0.83 .. +0.2 em. */
    private class Font(ts: Float, val ascent: Float = 0.93f * ts, val descent: Float = 0.24f * ts,
                       val inkTop: Float = -0.83f * ts, val inkBottom: Float = 0.2f * ts)

    @Test
    fun bandsAreWholeDpFromTheSettingsAlone() {
        // The defaults: MaruViewer's header at 11 sp (4 dp edge + 16 dp glyph box + 2 dp) and the progress line alone
        // (4 dp edge + 12 dp lane + 2 dp, so the text never touches the dot at the lane's top).
        assertEquals(16, StatusBands.glyphDp(d))
        assertEquals(22, StatusBands.headerDp(d))
        assertEquals(18, StatusBands.footerDp(d))
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
        // The status size only counts for a band with text: the progress line alone keeps 18 dp.
        assertEquals(18, StatusBands.footerDp(d.copy(statusFontSizeSp = 16f)))
        assertEquals(0, StatusBands.headerDp(noHeader.copy(statusFontSizeSp = 16f)))
        // Px: whole dp × density (Comet, S25).
        assertEquals(44, StatusFit.headerBandPx(d, comet))
        assertEquals(66, StatusFit.headerBandPx(d, s25))
        assertEquals(36, StatusFit.footerBandPx(d, comet))
        assertEquals(54, StatusFit.footerBandPx(d, s25))
        assertEquals(32, StatusFit.glyphPx(d, comet))
        assertEquals(48, StatusFit.glyphPx(d, s25))
    }

    @Test
    fun theGlyphBoxIsRoundedUpToWholeDp() {
        // sp × 1.45 rounded up, without float creep (20 sp is exactly 29 dp).
        for ((sp, dp) in listOf(6f to 9, 8f to 12, 10f to 15, 11f to 16, 11.5f to 17, 12f to 18, 16f to 24, 20f to 29, 40f to 58))
            assertEquals("$sp sp", dp, StatusBands.glyphDp(d.copy(statusFontSizeSp = sp)))
        // Room for the status glyphs' ink (a parenthesis ≈ 1.05 em) and Roboto's ascent + descent (≈ 1.17 em) at every
        // size the settings allow.
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
        // Comet (no cutout): 11 sp = 22 px. The font box starts 4 dp (8 px) below the top edge (MaruViewer's line; the
        // ink ≈ 5 dp), inside its 32 px glyph box, 2 dp (4 px) above the band's end at 44, wherever the text box is.
        val f = Font(22f)
        val glyph = StatusFit.glyphPx(d, comet).toFloat()
        for (contentTop in listOf(44f, 80f, 200f)) {
            val baseline = StatusFit.headerBaseline(0f, contentTop, f.ascent, f.descent, f.inkTop, f.inkBottom, glyph, comet)
            assertEquals(8f, baseline - f.ascent, 0.001f)
            assertTrue(baseline + f.inkTop >= 8f && baseline + f.inkBottom <= 8f + glyph)
            assertEquals(10f, baseline + f.inkTop, 0.5f)
        }
        assertEquals(StatusFit.headerBandPx(d, comet).toFloat(), 8f + glyph + StatusFit.PAD_DP * comet, 0f)
    }

    @Test
    fun belowTheCameraBandTheHeaderIsWhereTheUsersScreenshotHasIt() {
        // The user's S25 in fullscreen (screenshot of the installed build, 2026-10-05): camera band 0..87, header ink
        // rows 147–181 ("죄와 벌 (상)"; the digits' baseline at 174.5), text box from 207. The installed build centred the
        // font box between the band and the text box: (87 + 207) / 2 + (ascent − descent) / 2, and the phone's font box
        // gives (ascent − descent) / 2 = 27.5 px at 33 px. The same formula puts the header on the same rows.
        val glyph = StatusFit.glyphPx(d, s25).toFloat()
        val phone = Font(33f, ascent = 68f, descent = 13f, inkTop = -27.5f, inkBottom = 6.5f)
        val baseline = StatusFit.headerBaseline(87f, 207f, phone.ascent, phone.descent, phone.inkTop, phone.inkBottom, glyph, s25)
        assertEquals(174.5f, baseline, 0.001f)
        assertEquals(147f, baseline + phone.inkTop, 0.001f)
        assertEquals(181f, baseline + phone.inkBottom, 0.001f)
        // The text box is where 4efdf0b and the screenshot have it: 87 + 66 (header band) + 54 (18 dp margin).
        assertEquals(207, 87 + StatusFit.headerBandPx(d, s25) + 3 * d.marginTopDp)
        // A larger top margin moves the header by half as much (it stays centred, as in the installed build).
        val lower = StatusFit.headerBaseline(87f, 207f + 30f, phone.ascent, phone.descent, phone.inkTop, phone.inkBottom, glyph, s25)
        assertEquals(baseline + 15f, lower, 0.001f)
        // Top margin 0: the text box at 87 + 66 = 153; the ink stays 2 dp above it and 4 dp below the camera band.
        val tight = StatusFit.headerBaseline(87f, 153f, phone.ascent, phone.descent, phone.inkTop, phone.inkBottom, glyph, s25)
        assertEquals(153f - 6f, tight + phone.inkBottom, 0.001f)
        assertTrue(tight + phone.inkTop >= 87f + 12f)
        // Roboto's smaller font box: centred the same way, the ink inside the paper between the band and the text.
        val f = Font(33f)
        val roboto = StatusFit.headerBaseline(87f, 207f, f.ascent, f.descent, f.inkTop, f.inkBottom, glyph, s25)
        assertEquals(147f + (f.ascent - f.descent) / 2f, roboto, 0.001f)
        assertTrue(roboto + f.inkTop >= 99f && roboto + f.inkBottom <= 201f)
    }

    @Test
    fun theGlyphsInkStaysInsideTheBandsGlyphBox() {
        // The phone's font box (81 px) is taller than the S25's 48 px glyph box but its ink (34 px) is not: the text
        // keeps its size, and the ink (not the font box) stays inside the box below the top edge (bars visible).
        val phone = Font(33f, ascent = 68f, descent = 13f, inkTop = -27.5f, inkBottom = 6.5f)
        assertEquals(33f, StatusFit.fitTextPx(33f, 34f, 48f), 0f)
        val b = StatusFit.headerBaseline(0f, 120f, phone.ascent, phone.descent, phone.inkTop, phone.inkBottom, 48f, s25)
        assertEquals(12f + 48f, b + phone.inkBottom, 0.001f)
        assertTrue(b + phone.inkTop >= 12f)
        val fb = StatusFit.footerBaseline(2340f, true, phone.descent, phone.inkTop, phone.inkBottom, 48f, s25)
        val box = 2340f - 12f - 36f - 6f
        assertEquals(box - phone.descent, fb, 0.001f)
        assertTrue(fb + phone.inkTop >= box - 48f && fb + phone.inkBottom <= box)
        // A large system font scale (the bands count the settings' sp only): ink 40 px in a 32 px box. The text is drawn
        // smaller, × 0.8, so its real ink (here −24 .. +8 px around the baseline) fits the box, not just its baseline.
        val ts = StatusFit.fitTextPx(40f, 40f, 32f)
        assertEquals(32f, ts, 0.001f)
        val big = Font(ts, ascent = 30f * 0.8f, descent = 10f * 0.8f, inkTop = -24f, inkBottom = 8f)
        val hb = StatusFit.headerBaseline(0f, 80f, big.ascent, big.descent, big.inkTop, big.inkBottom, 32f, comet)
        assertTrue(hb + big.inkTop >= 8f && hb + big.inkBottom <= 8f + 32f)
        val nb = StatusFit.footerBaseline(1440f, false, big.descent, big.inkTop, big.inkBottom, 32f, comet)
        assertTrue(nb + big.inkTop >= 1432f - 32f && nb + big.inkBottom <= 1432f)
        // An ink that still overflows (a glyph taller than the sample) keeps its top inside: never into the bezel.
        val over = StatusFit.headerBaseline(0f, 80f, 30f, 10f, -30f, 10f, 32f, comet)
        assertEquals(8f, over - 30f, 0.001f)
    }

    @Test
    fun theFooterSitsAboveTheLaneOrTheEdgeGap() {
        // Comet, footer items and the progress line: the lane is rows 1408..1432 (4 dp above the edge); the glyph box
        // ends 2 dp above it (1404) and starts 2 dp inside the band (1440 − 72 = 1368).
        val f = Font(22f)
        val glyph = StatusFit.glyphPx(footer, comet).toFloat()
        val withLane = StatusFit.footerBaseline(1440f, true, f.descent, f.inkTop, f.inkBottom, glyph, comet)
        assertEquals(1404f, withLane + f.descent, 0.001f)
        assertTrue(withLane + f.inkTop >= 1404f - glyph)
        assertEquals(1440f - StatusFit.footerBandPx(footer, comet) + StatusFit.PAD_DP * comet, 1404f - glyph, 0f)
        // The dot sits at the lane's top, below the footer's glyphs.
        assertTrue(withLane + f.descent <= ProgressMath.yc(1432, 24f, comet) - ProgressMath.rDot(24f, comet))
        // Without the line: on the edge gap, 4 dp above the bottom edge, inside a 22 dp band.
        val noLane = StatusFit.footerBaseline(1440f, false, f.descent, f.inkTop, f.inkBottom, glyph, comet)
        assertEquals(1432f, noLane + f.descent, 0.001f)
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
        // The progress line's band (18 dp) is edge + lane + 2 dp of paper: at bottom margin 0 the text box ends 2 dp
        // above the lane, and the dot (at the lane's top) stays clear of the text (Comet rows 1409..1421 under 1404).
        for ((density, viewH) in listOf(comet to 1440, s25 to 2340)) {
            val band = StatusFit.footerBandPx(d, density)
            assertEquals(StatusFit.laneTopPx(density) + StatusFit.px(StatusFit.PAD_DP, density), band)
            val lane = StatusFit.lanePx(density).toFloat()
            val dotTop = ProgressMath.yc(StatusFit.laneBottomPx(viewH, density), lane, density) - ProgressMath.rDot(lane, density)
            assertTrue(dotTop >= viewH - band + StatusFit.px(StatusFit.PAD_DP, density))
        }
    }
}
