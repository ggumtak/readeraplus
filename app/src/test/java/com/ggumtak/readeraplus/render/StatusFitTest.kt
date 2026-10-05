package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusFitTest {
    private val d = 2f          // Comet density
    private val pad = StatusFit.PAD_DP * d
    private val min = StatusFit.MIN_SP * d

    @Test
    fun defaultMarginsHoldEveryStatusSize() {
        // 40 dp margins = 80 px. Header: all 80 px; status sizes 8..16 sp all fit unscaled.
        for (sp in 8..16) {
            val px = sp * d
            assertEquals(px, StatusFit.size(px, 80f, 1.45f, pad, min), 0f)
        }
        // Footer with the progress bar: 80 - 8 (edge gap) - 24 (lane) = 48 px: 8..13 sp unscaled, 14..16 sp a little
        // smaller (≈ 13.8 sp), never hidden. Without the bar (72 px) every size fits.
        val room = 80f - StatusFit.edgePx(d) - StatusFit.lane(80f - StatusFit.edgePx(d), d)
        assertEquals(48f, room, 0f)
        for (sp in 8..13) assertEquals(sp * d, StatusFit.size(sp * d, room, 1.45f, pad, min), 0f)
        for (sp in 14..16) assertTrue(StatusFit.size(sp * d, room, 1.45f, pad, min) in 27f..sp * d)
        for (sp in 8..16) assertEquals(sp * d, StatusFit.size(sp * d, 80f - StatusFit.edgePx(d), 1.45f, pad, min), 0f)
    }

    @Test
    fun theHeaderHugsTheTopEdgeOnTheCometAndTheS25() {
        // Comet (density 2, no cutout): 40 dp top margin = 80 px. 11 sp = 22 px; ascent ≈ 0.93 em, glyph box 1.45 em.
        val ts = 22f
        val ascent = 0.93f * ts
        val glyph = 1.45f * ts
        val room = StatusFit.headerRoom(80f, d)
        assertEquals(80f - 8f + 4f, room, 0f)
        assertEquals(ts, StatusFit.size(ts, room, 1.45f, pad, min), 0f)
        val baseline = StatusFit.headerBaseline(ascent, d)
        // The glyph box starts 4 dp (8 px) below the view's top edge: inside the bezel-safe gap, not centred in 80 px.
        assertEquals(8f, baseline - ascent, 0.001f)
        assertTrue(baseline - ascent + glyph < 80f / 2f)
        // S25 (density 3), fullscreen: the view starts at the screen top; its text box below a 87 px camera band and
        // the 40 dp margin. The header's glyphs sit 4 dp (12 px) from the top, in the camera band like MaruViewer's
        // (≈ 5 dp), and its size is the user's: the band only adds room.
        val s25 = 3f
        val top = 87f + 120f
        val ts3 = 33f
        assertEquals(ts3, StatusFit.size(ts3, StatusFit.headerRoom(top, s25), 1.45f, StatusFit.PAD_DP * s25, StatusFit.MIN_SP * s25), 0f)
        assertEquals(12f, StatusFit.headerBaseline(0.93f * ts3, s25) - 0.93f * ts3, 0.001f)
        // The glyph box always ends PAD_DP above the text box when it is drawn at all.
        for (topPx in listOf(20f, 30f, 36f, 40f, 60f, 80f)) {
            val size = StatusFit.size(ts, StatusFit.headerRoom(topPx, d), 1.45f, pad, min)
            if (size > 0f) assertTrue(StatusFit.edgePx(d) + 1.45f * size + pad <= topPx + 0.001f)
        }
        // Too little room: hidden (4 dp margins with 페이지 여백 off).
        assertEquals(0f, StatusFit.size(ts, StatusFit.headerRoom(8f, d), 1.45f, pad, min), 0f)
    }

    @Test
    fun theFooterSitsJustAboveTheLaneOrTheEdgeGap() {
        // Comet, 40 dp bottom margin under a text box ending at 1360: the edge gap leaves 1432, the lane 24 px.
        val ts = 22f
        val descent = 0.25f * ts
        val edgeBottom = 1440f - StatusFit.edgePx(d)
        val lane = StatusFit.lane(edgeBottom - 1360f, d)
        assertEquals(24f, lane, 0f)
        val withLane = StatusFit.footerBaseline(edgeBottom, lane, descent, d)
        // Glyph box bottom 2 dp above the lane's top (where the dot sits), not centred between the text and the lane.
        assertEquals(edgeBottom - lane - pad, withLane + descent, 0.001f)
        assertTrue(withLane + descent <= ProgressMath.yc(edgeBottom.toInt(), lane, d) - ProgressMath.rDot(lane, d))
        // Without the bar: on the edge gap, 4 dp above the screen's bottom edge.
        val noLane = StatusFit.footerBaseline(edgeBottom, 0f, descent, d)
        assertEquals(1440f - 8f, noLane + descent, 0.001f)
        // Either way the glyph box stays below the text box when [size] lets it draw.
        for (margin in listOf(20f, 30f, 40f, 80f)) for (bar in listOf(true, false)) {
            val bottom = 1440f - StatusFit.edgeGapPx(margin, d)
            val l = if (bar) StatusFit.lane(bottom - (1440f - margin), d) else 0f
            val size = StatusFit.size(ts, bottom - (1440f - margin) - l, 1.45f, pad, min)
            if (size > 0f) {
                val glyphTop = StatusFit.footerBaseline(bottom, l, 0.25f * size, d) + 0.25f * size - 1.45f * size
                assertTrue(glyphTop >= 1440f - margin + pad - 0.001f)
            }
        }
    }

    @Test
    fun theSettingsEstimateFollowsTheEdgeHuggingHeader() {
        // 4 dp edge + 7 sp × 1.45 + 2 dp: 18 dp holds the smallest header, 16 dp does not (centred, 16 dp did).
        assertTrue(StatusFit.headerFitsDp(11f, 18))
        assertFalse(StatusFit.headerFitsDp(11f, 16))
        assertTrue(StatusFit.headerFitsDp(11f, 40))
        assertFalse(StatusFit.headerFitsDp(11f, 4))
        // The pixel rule agrees at the boundary (Comet).
        assertTrue(StatusFit.size(min, StatusFit.headerRoom(18f * d, d), StatusFit.GLYPH_EM, pad, min) > 0f)
        assertEquals(0f, StatusFit.size(min, StatusFit.headerRoom(16f * d, d), StatusFit.GLYPH_EM, pad, min), 0f)
    }

    @Test
    fun narrowMarginsShrinkThenHideTheText() {
        val fit = StatusFit.size(22f, 30f, 1.45f, pad, min)
        assertTrue(fit in min..22f)
        assertEquals(0f, StatusFit.size(22f, 16f, 1.45f, pad, min), 0f)       // 8 dp: hidden
        assertEquals(0f, StatusFit.size(22f, 8f, 1.45f, pad, min), 0f)        // pageMargins off (4 dp)
    }

    @Test
    fun laneTakesTwelveDpOrTheWholeSmallMargin() {
        assertEquals(24f, StatusFit.lane(80f, d), 0f)
        assertEquals(16f, StatusFit.lane(16f, d), 0f)
        assertEquals(0f, StatusFit.lane(8f, d), 0f)
        assertTrue(StatusFit.fitsDp(11f, 40, 12f))
        assertFalse(StatusFit.fitsDp(11f, 20, 12f))
        assertFalse(StatusFit.fitsDp(11f, 4, 0f))
        // The footer keeps the 4 dp edge gap too.
        assertEquals(8, StatusFit.edgePx(d))
        assertTrue(StatusFit.footerFitsDp(11f, 40, progressBar = true))
        assertFalse(StatusFit.footerFitsDp(11f, 30, progressBar = true))
        assertTrue(StatusFit.footerFitsDp(11f, 20, progressBar = false))
        assertFalse(StatusFit.footerFitsDp(11f, 16, progressBar = false))
        // The gap shrinks in margins too small for it and the smallest lane: the bar shows wherever it did before.
        assertEquals(8, StatusFit.edgeGapPx(80f, d))
        assertEquals(8, StatusFit.edgeGapPx(20f, d))
        assertEquals(4, StatusFit.edgeGapPx(16f, d))        // 8 dp margin: lane 12 px, gap 4 px
        assertEquals(0, StatusFit.edgeGapPx(12f, d))        // 6 dp margin: lane 12 px at the edge, as before
        assertEquals(0, StatusFit.edgeGapPx(8f, d))
        for (m in listOf(12f, 16f, 20f, 80f)) assertTrue(StatusFit.lane(m - StatusFit.edgeGapPx(m, d), d) > 0f)
    }
}
