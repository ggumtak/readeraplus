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
        // The header's right end, inset from the column's, stays left of the ribbon, with the gap.
        assertTrue(contentRight - inset <= RibbonMath.left(viewW, d) - dp(RibbonMath.GAP_DP) + 0.001f)
        // Margins off: more to reserve.
        assertTrue(RibbonMath.headerInset(d, viewW - dp(4f), viewW, ribbonH, dp(10f)) > inset)
    }

    @Test
    fun belowACameraBandTopsAndHeightsCountFromItsBottom() {
        // S25 fullscreen (density 3): the ribbon hangs from the 87 px camera band's bottom, as in the installed build
        // (87..159), and the renderer passes the text box's top (207) and the header's ink top (147, the user's
        // screenshot) less the band: full height, and the header keeps the ribbon's place at its right end.
        val s25 = 3f
        val band = 87f
        val right = 1080 - 60f
        val h = RibbonMath.height(s25, 207f - band, right, 1080)
        assertEquals(RibbonMath.HEIGHT_DP * s25, h, 0.001f)
        assertTrue(band + h + RibbonMath.GAP_DP * s25 <= 207f)
        assertTrue(RibbonMath.headerInset(s25, right, 1080, h, 147f - band) > 0f)
        // Top margin 0 (text box at 87 + 66): the ribbon shrinks to the paper above the text, still below the band.
        val tight = RibbonMath.height(s25, 153f - band, right, 1080)
        assertEquals(153f - band - RibbonMath.GAP_DP * s25, tight, 0.001f)
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
