package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.reader.Corner
import com.ggumtak.readeraplus.reader.TapZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RibbonMathTest {
    private val d = 2f
    private val viewW = 720

    private fun dp(v: Float) = v * d

    @Test
    fun readErasRibbonInWholePixels() {
        // ReadEra on the user's S25 (2026-10-05, 3 px per dp): 42 × 62 px from the screen's top, x 987–1028, its right
        // edge 51 px from the screen's, a V notch ≈ 12 px deep.
        val s25 = 3f
        assertEquals(987f, RibbonMath.left(1080, s25), 0f)
        assertEquals(1029f, RibbonMath.right(1080, s25), 0f)
        assertEquals(62f, RibbonMath.height(s25, 207f, 1080 - 60f, 1080), 0f)
        assertEquals(12f, RibbonMath.notch(62f), 0f)
        // The Comet (2 px per dp): 28 × 41 px, x 658–685, 34 px from the right edge, an 8 px notch.
        assertEquals(658f, RibbonMath.left(viewW, d), 0f)
        assertEquals(686f, RibbonMath.right(viewW, d), 0f)
        assertEquals(41f, RibbonMath.height(d, 80f, viewW - dp(20f), viewW), 0f)
        assertEquals(8f, RibbonMath.notch(41f), 0f)
        // Smaller than before (14 × 24 dp, 14 dp from the edge) and blue on phones.
        assertTrue(RibbonMath.HEIGHT_DP < 24f)
        assertEquals(0xFF4286F5.toInt(), RibbonMath.COLOR)
    }

    @Test
    fun fullHeightAboveTheText() {
        // The default text box starts at 80 (Comet) / 207 (S25 fullscreen): the ribbon from the very top keeps its size.
        assertEquals(RibbonMath.px(RibbonMath.HEIGHT_DP, d), RibbonMath.height(d, 80f, viewW - dp(20f), viewW), 0f)
        assertTrue(RibbonMath.height(d, 80f, viewW - dp(20f), viewW) + dp(RibbonMath.GAP_DP) <= 80f)
    }

    @Test
    fun shrinksToTheMarginAboveText() {
        // No header, a small margin: the first line starts 16 dp from the top.
        val h = RibbonMath.height(d, dp(16f), viewW - dp(18f), viewW)
        assertEquals(dp(16f - RibbonMath.GAP_DP), h, 0f)
        // Margins off: never smaller than the minimum.
        assertEquals(dp(RibbonMath.MIN_HEIGHT_DP), RibbonMath.height(d, dp(4f), viewW - dp(4f), viewW), 0f)
        // Whole px.
        val odd = RibbonMath.height(3f, 50.5f, 1080 - 12f, 1080)
        assertEquals(Math.round(odd).toFloat(), odd, 0f)
    }

    @Test
    fun wideRightMarginKeepsFullHeight() {
        val right = RibbonMath.left(viewW, d) - dp(RibbonMath.GAP_DP) - 1f
        assertEquals(RibbonMath.px(RibbonMath.HEIGHT_DP, d), RibbonMath.height(d, dp(4f), right, viewW), 0f)
    }

    @Test
    fun theHeadersRightSlotKeepsClearOfTheRibbon() {
        // The header spans the page view less its own 15 dp insets (MaruViewer's line), not the text column: on the S25
        // its band ends at 1035, the ribbon starts at 987, so 57 px are kept at its right end (the page number moves in
        // by that much on a bookmarked page) and its glyphs (ink from row 15) never run under the ribbon (rows 0..62).
        val s25 = 3f
        val bandRight = 1080 - 45f
        val inset = RibbonMath.headerInset(s25, bandRight, 1080, 62f, glyphTop = 15f)
        assertEquals(57f, inset, 0f)
        assertTrue(bandRight - inset <= RibbonMath.left(1080, s25) - RibbonMath.GAP_DP * s25)
        // The Comet: the band ends at 690, 38 px kept.
        assertEquals(38f, RibbonMath.headerInset(d, viewW - 30f, viewW, 41f, glyphTop = 10f), 0f)
        // Regression: a long chapter title ran under the ribbon (black on black). The reserve keeps any band clear.
        for (right in listOf(viewW - 10f, viewW - 30f, viewW - dp(18f))) {
            val r = RibbonMath.headerInset(d, right, viewW, 41f, dp(5f))
            assertTrue(right - r <= RibbonMath.left(viewW, d) - dp(RibbonMath.GAP_DP) + 0.001f)
        }
    }

    @Test
    fun noInsetWithoutOverlap() {
        val bandRight = viewW - 30f
        assertEquals(0f, RibbonMath.headerInset(d, bandRight, viewW, 0f, dp(5f)), 0f)
        // Header glyphs entirely below the ribbon.
        assertEquals(0f, RibbonMath.headerInset(d, bandRight, viewW, 41f, 41f + dp(RibbonMath.GAP_DP)), 0f)
        // A band that ends well left of the ribbon.
        assertEquals(0f, RibbonMath.headerInset(d, viewW - dp(60f), viewW, 41f, dp(5f)), 0f)
    }

    @Test
    fun theBookmarksTapCornerCoversTheRibbon() {
        // The tap corner (TapZones, measured below the S25's camera band, which counts as its top row) holds the whole
        // ribbon, which hangs from the very top over that band.
        for ((w, h, band, density) in listOf(Quad(1080, 2340, 87, 3f), Quad(720, 1440, 0, 2f), Quad(1080, 2340, 0, 3f))) {
            val ribbonH = RibbonMath.px(RibbonMath.HEIGHT_DP, density)
            val zh = h - band
            for (x in listOf(RibbonMath.left(w, density), RibbonMath.right(w, density) - 1f))
                for (y in listOf(0f, ribbonH - 1f))
                    assertEquals("$w × $h at $x, $y", Corner.TOP_RIGHT, TapZones.corner(x, y - band, w, zh))
        }
    }

    private data class Quad(val w: Int, val h: Int, val band: Int, val density: Float)
}
