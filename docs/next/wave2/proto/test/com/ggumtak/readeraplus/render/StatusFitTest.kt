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
        // 40 dp margins = 80 px; footer text band = 80 - 24 (lane) = 56 px. Status sizes 8..16 sp all fit unscaled.
        for (sp in 8..16) {
            val px = sp * d
            assertEquals(px, StatusFit.size(px, 80f, 1.45f, pad, min), 0f)
            assertEquals(px, StatusFit.size(px, 80f - StatusFit.lane(80f, d), 1.45f, pad, min), 0f)
        }
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
    }
}
