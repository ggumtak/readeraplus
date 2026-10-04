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
