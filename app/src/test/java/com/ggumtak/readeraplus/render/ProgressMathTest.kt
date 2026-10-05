package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.AllocCounter
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusBands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ReadEra's 탐색줄 on the user's S25 screenshots (2026-10-05), and the same on the Comet. */
class ProgressMathTest {
    private val s25 = 3f        // 1080×2340
    private val comet = 2f      // 720×1440

    @Test
    fun s25IsTheScreenshotToThePixel() {
        // The line: 2 px, rows 2315–2316. The dots: 14 px, rows 2309–2322, centred on the line (y 2316.0, 8 dp up).
        assertEquals(2, ProgressMath.lineH(s25))
        assertEquals(14, ProgressMath.dotD(s25))
        assertEquals(2315, ProgressMath.lineTop(2340, s25))
        assertEquals(2309, ProgressMath.dotTop(2340, s25))
        assertEquals(2316f, ProgressMath.centreY(2340, s25), 0f)
        // End dots x 21–34 and 1045–1058 (7 dp from the screen's sides), the line between their centres 28 and 1052.
        assertEquals(21, ProgressMath.dotLeft(0f, 1080, s25))
        assertEquals(1045, ProgressMath.dotLeft(1f, 1080, s25))
        assertEquals(28f, ProgressMath.dotX(0f, 1080, s25), 0f)
        assertEquals(1052f, ProgressMath.dotX(1f, 1080, s25), 0f)
        assertEquals(1024, ProgressMath.trackPx(1080, s25))
        // Page 1749 of 3259 (index 1748 of 3258): the screenshot's position dot, x 570–583.
        assertEquals(570, ProgressMath.dotLeft(1748f / 3258f, 1080, s25))
        assertEquals(577f, ProgressMath.dotX(1748f / 3258f, 1080, s25), 0f)
        // Beyond the ends, the dot stays on the end dots.
        assertEquals(21, ProgressMath.dotLeft(-0.5f, 1080, s25))
        assertEquals(1045, ProgressMath.dotLeft(1.5f, 1080, s25))
    }

    @Test
    fun cometIsTheSameInItsOwnPixels() {
        // 1 px line, 9 px dots (4.5 dp, the nearest odd size to 4.67 dp: both centred on a pixel's middle, row 1423).
        assertEquals(1, ProgressMath.lineH(comet))
        assertEquals(9, ProgressMath.dotD(comet))
        assertEquals(1423, ProgressMath.lineTop(1440, comet))
        assertEquals(1419, ProgressMath.dotTop(1440, comet))
        assertEquals(1423.5f, ProgressMath.centreY(1440, comet), 0f)
        // End dots x 14–22 and 697–705, centres 18.5 and 701.5.
        assertEquals(14, ProgressMath.dotLeft(0f, 720, comet))
        assertEquals(697, ProgressMath.dotLeft(1f, 720, comet))
        assertEquals(18.5f, ProgressMath.dotX(0f, 720, comet), 0f)
        assertEquals(701.5f, ProgressMath.dotX(1f, 720, comet), 0f)
        assertEquals(683, ProgressMath.trackPx(720, comet))
        // Half way: x 356–364 (341.5 px of track rounds up).
        assertEquals(356, ProgressMath.dotLeft(0.5f, 720, comet))
    }

    @Test
    fun lineAndDotsShareOneCentreOnEveryDensity() {
        for (density in listOf(0.75f, 1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)) {
            val line = ProgressMath.lineH(density)
            val dot = ProgressMath.dotD(density)
            assertTrue("$density: line $line", line >= 1)
            assertEquals("$density: dot $dot, line $line, same parity", 0, (dot - line) % 2)
            assertTrue("$density: dot $dot over line $line", dot > line)
            assertTrue("$density: dot $dot near 4.67 dp", Math.abs(dot - ProgressMath.DOT_DP * density) <= 1f)
            val viewH = 2000
            val centre = ProgressMath.centreY(viewH, density)
            assertEquals(centre, ProgressMath.dotTop(viewH, density) + dot / 2f, 0f)
            assertEquals(ProgressMath.CENTRE_DP * density, viewH - centre, 0.5f)
        }
    }

    @Test
    fun theDotsStayInTheFooterBandsLane() {
        // The footer band keeps its height (the text box does not move): EDGE 4 + lane 12 + PAD 2 dp. The dots sit in
        // the lane, ≥ EDGE_DP above the bottom (the Comet's bezel: 12 px of paper under the dots, 8 needed) and under
        // the lane's top, which the return chip sits above.
        assertEquals(18, StatusBands.footerDp(ReaderSettings()))
        for ((density, viewH) in listOf(comet to 1440, s25 to 2340, 2.625f to 2400, 1f to 800)) {
            val dotBottom = ProgressMath.dotTop(viewH, density) + ProgressMath.dotD(density)
            assertTrue("$density: dot bottom $dotBottom", viewH - dotBottom >= StatusFit.edgePx(density))
            assertTrue("$density: dot top under the lane's top",
                ProgressMath.dotTop(viewH, density) >= viewH - StatusFit.laneTopPx(density))
        }
        assertEquals(12, 1440 - (ProgressMath.dotTop(1440, comet) + ProgressMath.dotD(comet)))
        assertEquals(17, 2340 - (ProgressMath.dotTop(2340, s25) + ProgressMath.dotD(s25)))
    }

    @Test
    fun theStatusModelCountsTheDotsPixels() {
        // StatusModel's dot pixel is round(f · trackPx): one more exactly when the drawn dot moves one column.
        for (f in listOf(0f, 0.001f, 0.25f, 0.5365f, 0.75f, 0.999f, 1f)) {
            assertEquals("$f", ProgressMath.sidePx(s25) + Math.round(f * ProgressMath.trackPx(1080, s25)),
                ProgressMath.dotLeft(f, 1080, s25))
        }
        // A view narrower than the two end dots: no track, every dot on the left one.
        assertEquals(0, ProgressMath.trackPx(40, s25))
        assertEquals(21, ProgressMath.dotLeft(0.7f, 40, s25))
    }

    @Test
    fun geometryAllocatesNothing() {
        if (!AllocCounter.supported) return
        var sink = 0f
        val loop = {
            for (i in 0 until 10_000) {
                val f = i / 10_000f
                sink += ProgressMath.lineTop(2340, s25) + ProgressMath.centreY(2340, s25) + ProgressMath.dotX(0f, 1080, s25) +
                    ProgressMath.dotX(1f, 1080, s25) + ProgressMath.dotX(f, 1080, s25) + ProgressMath.trackPx(1080, s25) +
                    PagePalette.PAPER.progressLine + PagePalette.MARU.inkProgressDot
            }
        }
        repeat(3) { loop() }
        val bytes = AllocCounter.measure(loop)!!
        assertEquals("10 000 frames' geometry allocated $bytes bytes", 0L, bytes)
        assertTrue(sink != 0f)
    }
}
