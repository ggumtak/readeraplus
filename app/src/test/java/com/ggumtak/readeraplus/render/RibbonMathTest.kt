package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RibbonMathTest {
    private val d = 2f
    private val viewW = 720

    private fun dp(v: Float) = v * d

    @Test
    fun fullHeightAboveTheHeaderBand() {
        // Header on: the text column starts below the header band, the ribbon keeps its full size.
        val top = dp(16f + 24f)
        assertEquals(dp(RibbonMath.HEIGHT_DP), RibbonMath.height(d, top, viewW - dp(18f), viewW), 0.001f)
    }

    @Test
    fun shrinksToTheMarginAboveText() {
        // Header off, default margins: the first line starts 16dp from the top.
        val h = RibbonMath.height(d, dp(16f), viewW - dp(18f), viewW)
        assertEquals(dp(16f - RibbonMath.GAP_DP), h, 0.001f)
        // Margins off: never smaller than the minimum.
        assertEquals(dp(RibbonMath.MIN_HEIGHT_DP), RibbonMath.height(d, dp(4f), viewW - dp(4f), viewW), 0.001f)
    }

    @Test
    fun wideRightMarginKeepsFullHeight() {
        val right = RibbonMath.left(viewW, d) - dp(RibbonMath.GAP_DP) - 1f
        assertEquals(dp(RibbonMath.HEIGHT_DP), RibbonMath.height(d, dp(4f), right, viewW), 0.001f)
    }

    @Test
    fun headerIsNarrowedToClearTheRibbon() {
        // Regression: a long chapter title ran under the ribbon (black on black).
        val contentRight = viewW - dp(18f)
        val ribbonH = dp(RibbonMath.HEIGHT_DP)
        val inset = RibbonMath.headerInset(d, contentRight, viewW, ribbonH, glyphTop = dp(15f))
        assertTrue(inset > 0f)
        // Right end of a centred header of width cw - 2 * inset stays left of the ribbon, with the gap.
        assertTrue(contentRight - inset <= RibbonMath.left(viewW, d) - dp(RibbonMath.GAP_DP) + 0.001f)
        // Margins off: more to reserve.
        assertTrue(RibbonMath.headerInset(d, viewW - dp(4f), viewW, ribbonH, dp(10f)) > inset)
    }

    @Test
    fun noInsetWithoutOverlap() {
        val contentRight = viewW - dp(18f)
        assertEquals(0f, RibbonMath.headerInset(d, contentRight, viewW, 0f, dp(15f)), 0f)
        // Header glyphs entirely below the ribbon (large top margin).
        assertEquals(0f, RibbonMath.headerInset(d, contentRight, viewW, dp(24f), dp(40f)), 0f)
        // Column ends well left of the ribbon (large right margin).
        assertEquals(0f, RibbonMath.headerInset(d, viewW - dp(60f), viewW, dp(24f), dp(15f)), 0f)
    }
}
