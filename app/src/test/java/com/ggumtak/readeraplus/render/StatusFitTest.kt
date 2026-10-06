package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.reader.LayoutKeys
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
        // The defaults: MaruViewer's header at 13 sp (4 dp edge + 19 dp glyph box + 2 dp) and the progress line alone
        // (4 dp edge + 12 dp lane + 2 dp, so the text never touches the dot at the lane's top).
        assertEquals(13f, d.statusFontSizeSp, 0f)
        assertEquals(19, StatusBands.glyphDp(d))
        assertEquals(25, StatusBands.headerDp(d))
        assertEquals(18, StatusBands.footerDp(d))
        // Off: no header item; no footer item and no progress line.
        assertEquals(0, StatusBands.headerDp(noHeader))
        assertEquals(0, StatusBands.footerDp(d.copy(progressBar = false)))
        // Footer items above the line (4 + 12 + 2 + 19 + 2) or on the edge gap (4 + 19 + 2).
        assertEquals(39, StatusBands.footerDp(footer))
        assertEquals(25, StatusBands.footerDp(footer.copy(progressBar = false)))
        // Every item makes the same band: one item for another never moves the text box.
        for (item in StatusItem.entries) if (item != StatusItem.NONE) {
            assertEquals(25, StatusBands.headerDp(noHeader.withSlot(0, 2, item)))
            assertEquals(39, StatusBands.footerDp(d.withSlot(1, 0, item)))
        }
        // The status size only counts for a band with text: the progress line alone keeps 18 dp.
        assertEquals(18, StatusBands.footerDp(d.copy(statusFontSizeSp = 16f)))
        assertEquals(0, StatusBands.headerDp(noHeader.copy(statusFontSizeSp = 16f)))
        // The old 11 sp default: a 22 dp header (and 36 dp footer with text), 3 dp less than 13 sp's.
        assertEquals(22, StatusBands.headerDp(d.copy(statusFontSizeSp = 11f)))
        assertEquals(36, StatusBands.footerDp(footer.copy(statusFontSizeSp = 11f)))
        // Px: whole dp × density (Comet, S25).
        assertEquals(50, StatusFit.headerBandPx(d, comet))
        assertEquals(75, StatusFit.headerBandPx(d, s25))
        assertEquals(36, StatusFit.footerBandPx(d, comet))
        assertEquals(54, StatusFit.footerBandPx(d, s25))
        assertEquals(38, StatusFit.glyphPx(d, comet))
        assertEquals(57, StatusFit.glyphPx(d, s25))
    }

    @Test
    fun theGlyphBoxIsRoundedUpToWholeDp() {
        // sp × 1.45 rounded up, without float creep (20 sp is exactly 29 dp).
        for ((sp, dp) in listOf(6f to 9, 8f to 12, 10f to 15, 11f to 16, 11.5f to 17, 12f to 18, 13f to 19, 16f to 24, 20f to 29, 40f to 58))
            assertEquals("$sp sp", dp, StatusBands.glyphDp(d.copy(statusFontSizeSp = sp)))
        // Room for the status glyphs' ink (a parenthesis ≈ 1.05 em) and Roboto's ascent + descent (≈ 1.17 em) at every
        // size the settings allow.
        var sp = 6f
        while (sp <= 40f) {
            val box = StatusBands.glyphDp(d.copy(statusFontSizeSp = sp))
            assertTrue(box >= sp * 1.45f - 1e-3f)
            sp += 0.5f
        }
        // An unusable size is drawn, and reserved, as the default 13 sp; the size is kept within 6..40 sp.
        assertEquals(19, StatusBands.glyphDp(d.copy(statusFontSizeSp = Float.NaN)))
        assertEquals(19, StatusBands.glyphDp(d.copy(statusFontSizeSp = 0f)))
        assertEquals(58, StatusBands.glyphDp(d.copy(statusFontSizeSp = 99f)))
    }

    @Test
    fun theHeaderHugsTheTopOfItsBand() {
        // Comet (no cutout): 13 sp = 26 px. The font box starts 4 dp (8 px) below the top edge (MaruViewer's line; the
        // ink ≈ 5 dp), inside its 38 px glyph box, 2 dp (4 px) above the band's end at 50.
        val f = Font(26f)
        val glyph = StatusFit.glyphPx(d, comet).toFloat()
        val baseline = StatusFit.headerBaseline(0f, f.ascent, f.inkTop, f.inkBottom, glyph, comet)
        assertEquals(8f, baseline - f.ascent, 0.001f)
        assertTrue(baseline + f.inkTop >= 8f && baseline + f.inkBottom <= 8f + glyph)
        assertEquals(8f + 0.1f * 26f, baseline + f.inkTop, 0.01f)
        assertEquals(StatusFit.headerBandPx(d, comet).toFloat(), 8f + glyph + StatusFit.PAD_DP * comet, 0f)
    }

    @Test
    fun overTheCameraBandTheHeaderIsMaruViewersLine() {
        // The user's S25 in fullscreen (MaruViewer's screenshot, 2026-10-05: its status ink on rows 14–47 of 2340, inside
        // the 87 px camera band). 13 sp = 39 px in the phone's font (its tall font box: ascent 80 px; ink −32.5 .. +7.7
        // around the baseline, the 11 sp screenshot's scaled). The ink starts 5 dp (15 px) below the top, inside the
        // 57 px glyph box at 12..69, far above the band's end.
        val glyph = StatusFit.glyphPx(d, s25).toFloat()
        val phone = Font(39f, ascent = 80.4f, descent = 15.4f, inkTop = -32.5f, inkBottom = 7.7f)
        val baseline = StatusFit.headerBaseline(87f, phone.ascent, phone.inkTop, phone.inkBottom, glyph, s25)
        assertEquals(15f, baseline + phone.inkTop, 0.001f)
        assertTrue(baseline + phone.inkBottom <= 12f + glyph)
        assertTrue(baseline + phone.inkBottom < 87f)
        // Its digits (≈ 0.74 em, as measured at 11 sp) on rows ≈ 18–47 beside MaruViewer's 16–45.
        assertEquals(47.5f, baseline, 0.001f)
        // The text box stays where 4efdf0b, the user's screenshot and the old 11 sp header had it: 87 + 75 (header band,
        // reserved below the camera band, only paper there) + 45 (the 15 dp margin) = 207, as 87 + 66 + 54 before.
        assertEquals(207, 87 + StatusFit.headerBandPx(d, s25) + 3 * d.marginTopDp)
        val old = d.copy(statusFontSizeSp = 11f, marginTopDp = 18)
        assertEquals(207, 87 + StatusFit.headerBandPx(old, s25) + 3 * old.marginTopDp)
        // Roboto's smaller font box (the emulator's): the same ink line, whatever the font's box.
        val f = Font(39f)
        assertEquals(15f, StatusFit.headerBaseline(87f, f.ascent, f.inkTop, f.inkBottom, glyph, s25) + f.inkTop, 0.001f)
        // A chosen 11 sp: the same line too.
        val small = Font(33f, ascent = 68f, descent = 13f, inkTop = -27.5f, inkBottom = 6.5f)
        assertEquals(15f, StatusFit.headerBaseline(87f, small.ascent, small.inkTop, small.inkBottom, 48f, s25) + small.inkTop, 0.001f)
        // Without the band (bars shown, or the Comet) the font's ascent decides, as before: unchanged.
        val bars = StatusFit.headerBaseline(0f, f.ascent, f.inkTop, f.inkBottom, glyph, s25)
        assertEquals(12f + f.ascent, bars, 0.001f)
    }

    @Test
    fun theGlyphsInkStaysInsideTheBandsGlyphBox() {
        // The phone's font box (81 px) is taller than the S25's 48 px glyph box (11 sp) but its ink (34 px) is not: the
        // text keeps its size, and the ink (not the font box) stays inside the box below the top edge (bars visible).
        val phone = Font(33f, ascent = 68f, descent = 13f, inkTop = -27.5f, inkBottom = 6.5f)
        assertEquals(33f, StatusFit.fitTextPx(33f, 34f, 48f), 0f)
        val b = StatusFit.headerBaseline(0f, phone.ascent, phone.inkTop, phone.inkBottom, 48f, s25)
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
        val hb = StatusFit.headerBaseline(0f, big.ascent, big.inkTop, big.inkBottom, 32f, comet)
        assertTrue(hb + big.inkTop >= 8f && hb + big.inkBottom <= 8f + 32f)
        val nb = StatusFit.footerBaseline(1440f, false, big.descent, big.inkTop, big.inkBottom, 32f, comet)
        assertTrue(nb + big.inkTop >= 1432f - 32f && nb + big.inkBottom <= 1432f)
        // An ink that still overflows (a glyph taller than the sample) keeps its top inside: never into the bezel.
        val over = StatusFit.headerBaseline(0f, 30f, -30f, 10f, 32f, comet)
        assertEquals(8f, over - 30f, 0.001f)
        // Over a camera band too: never above the glyph box's top.
        val overBand = StatusFit.headerBaseline(87f, 30f, -60f, 10f, 57f, s25)
        assertEquals(12f, overBand - 60f, 0.001f)
    }

    @Test
    fun theHeaderSpansThePageViewNotTheTextColumn() {
        // User (2026-10-05): "윗줄은 좌우여백에 영향을 받지 않고": side margins of 0 and 80 dp move the text column, never
        // the header, which runs from its own inset to the view's width less the other (S25 45..1035, Comet 30..690).
        for ((viewW, density) in listOf(1080 to s25, 720 to comet)) {
            val inset = StatusFit.sideInset(0f, 0f, 0f, 30f, density)
            val narrow = LayoutKeys.geometry(d.copy(marginLeftDp = 0, marginRightDp = 0), viewW, 1440, density)
            val wide = LayoutKeys.geometry(d.copy(marginLeftDp = 80, marginRightDp = 80), viewW, 1440, density)
            assertTrue(narrow.contentLeft != wide.contentLeft && narrow.contentWidth != wide.contentWidth)
            val w = StatusFit.headerWidth(viewW, inset, inset)
            assertEquals(viewW - 2f * inset, w, 0f)
            assertTrue(w != narrow.contentWidth.toFloat() && w != wide.contentWidth.toFloat())
        }
        assertEquals(990f, StatusFit.headerWidth(1080, 45f, 45f), 0f)
        assertEquals(660f, StatusFit.headerWidth(720, 30f, 30f), 0f)
        // A corner that needs more on one side takes only that side's room; never below 0.
        assertEquals(981f, StatusFit.headerWidth(1080, 45f, 54f), 0f)
        assertEquals(0f, StatusFit.headerWidth(80, 45f, 45f), 0f)
    }

    @Test
    fun theHeadersSideInsetsClearOnlyTheDisplaysCorners() {
        // The glyphs' middle on the S25 over its camera band: baseline 47.5 + (−32.5 + 7.7) / 2 ≈ 35 px.
        val middle = 47.5f + (-32.5f + 7.7f) / 2f
        // MaruViewer's own inset, 15 dp: 45 px on the S25 (its status ink from x 46, to 1032 of 1080), 30 px on the Comet.
        assertEquals(45f, StatusFit.sideInset(0f, 0f, 0f, middle, s25), 0f)
        assertEquals(30f, StatusFit.sideInset(0f, 0f, 0f, 23f, comet), 0f)
        // A 44 dp corner (132 px, its centre at 132, 132 in the fullscreen view) leaves 43 px at the glyphs' middle: the
        // 15 dp inset wins. A 50 dp one needs 54 px; a 60 dp one 74.
        assertEquals(43f, StatusFit.cornerClearance(132f, 132f, 132f, middle), 0f)
        assertEquals(45f, StatusFit.sideInset(132f, 132f, 132f, middle, s25), 0f)
        assertEquals(54f, StatusFit.sideInset(150f, 150f, 150f, middle, s25), 0f)
        assertEquals(74f, StatusFit.sideInset(180f, 180f, 180f, middle, s25), 0f)
        // Bars shown: the view starts 110 px down, the corner's centre 22 px below its top, the glyphs below that: no
        // corner at their row, the base only.
        assertEquals(0f, StatusFit.cornerClearance(132f, 132f, 22f, 40f), 0f)
        assertEquals(45f, StatusFit.sideInset(132f, 132f, 22f, 40f, s25), 0f)
        // Landscape, fullscreen, the camera on the left: the view starts 87 px in, so the top-left corner's centre is 45
        // px inside it (cleared by the cutout's inset); the top-right one is at the window's corner as in portrait.
        assertEquals(0f, StatusFit.cornerClearance(132f, 45f, 132f, middle), 0f)
        assertEquals(43f, StatusFit.cornerClearance(132f, 132f, 132f, middle), 0f)
        // A row above the arc (inside the corner's square) needs the whole corner; a square corner needs nothing.
        assertEquals(132f, StatusFit.cornerClearance(132f, 132f, 132f, -1f), 0f)
        assertEquals(0f, StatusFit.cornerClearance(0f, 132f, 132f, middle), 0f)
        // Unknown corners (before API 31): 18 dp, room for a corner of up to ≈ 50 dp at the glyphs' middle.
        assertEquals(54f, StatusFit.sideInset(-1f, 0f, 0f, middle, s25), 0f)
        assertEquals(36f, StatusFit.sideInset(-1f, 0f, 0f, 23f, comet), 0f)
        assertTrue(StatusFit.cornerClearance(150f, 150f, 150f, middle) <= 54f)
        // Whole px, never negative, and never more than the corner's centre.
        for (r in 0..200 step 7) for (y in -10..120 step 3) {
            val c = StatusFit.cornerClearance(r.toFloat(), r.toFloat(), r.toFloat(), y.toFloat())
            assertEquals(Math.round(c).toFloat(), c, 0f)
            assertTrue(c >= 0f && c <= r.toFloat())
        }
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
        // The progress line's dots sit low in the lane, well below the footer's glyphs (rows 1419..1427).
        assertTrue(withLane + f.descent + StatusFit.PAD_DP * comet <= ProgressMath.dotTop(1440, comet))
        // Without the line: on the edge gap, 4 dp above the bottom edge, inside a 22 dp band.
        val noLane = StatusFit.footerBaseline(1440f, false, f.descent, f.inkTop, f.inkBottom, glyph, comet)
        assertEquals(1432f, noLane + f.descent, 0.001f)
        assertEquals(1440f - StatusFit.footerBandPx(footer.copy(progressBar = false), comet) + StatusFit.PAD_DP * comet, 1432f - glyph, 0f)
    }

    @Test
    fun theLaneIsAlwaysWholeAboveTheEdgeGap() {
        assertEquals(8, StatusFit.edgePx(comet))
        assertEquals(12, StatusFit.edgePx(s25))
        assertEquals(32, StatusFit.laneTopPx(comet))
        assertEquals(48, StatusFit.laneTopPx(s25))
        // S25 fullscreen: line rows 2303–2304, dots 2297–2310 (12 dp up since 2026-10-06; ReadEra's 8 dp before, rows
        // 2315–2316). The Comet's line row 1415, dots 1411..1419: still under the lane's top (1408 / 2292).
        assertEquals(2303, ProgressMath.lineTop(2340, s25))
        assertEquals(2297, ProgressMath.dotTop(2340, s25))
        assertEquals(1415, ProgressMath.lineTop(1440, comet))
        assertEquals(1411, ProgressMath.dotTop(1440, comet))
        // The progress line's band (18 dp) is edge + lane + 2 dp of paper: at bottom margin 0 the text box ends 2 dp
        // above the lane, and the dots stay clear of the text and of the edge gap (Comet rows 1419..1427, the box
        // ending at 1404, the bezel's rows from 1432).
        for ((density, viewH) in listOf(comet to 1440, s25 to 2340)) {
            val band = StatusFit.footerBandPx(d, density)
            assertEquals(StatusFit.laneTopPx(density) + StatusFit.px(StatusFit.PAD_DP, density), band)
            val dotTop = ProgressMath.dotTop(viewH, density)
            assertTrue(dotTop >= viewH - band + StatusFit.px(StatusFit.PAD_DP, density))
            assertTrue(dotTop + ProgressMath.dotD(density) <= viewH - StatusFit.edgePx(density))
        }
    }
}
