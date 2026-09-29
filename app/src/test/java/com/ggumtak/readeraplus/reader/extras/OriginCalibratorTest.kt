package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builds a synthetic page (lines of equal-width chars, paragraph gaps, optional justification gaps) and a hit
 * test that follows the engine spec (line whose [top, bottom) contains y, else the nearest line within half a
 * line height; char whose [x, x + advance) contains x, clamped to line ends), shifted by a known origin.
 */
class OriginCalibratorTest {

    private class Page(
        val lines: List<OriginCalibrator.LineBox>,
        val lefts: Map<Int, FloatArray>,
        val advances: FloatArray,
    )

    private fun page(gapAfterSpace: Float = 0f): Page {
        val adv = 20f
        val lh = 34f
        val lines = ArrayList<OriginCalibrator.LineBox>()
        val lefts = HashMap<Int, FloatArray>()
        val advances = FloatArray(2000)
        var off = 0
        var y = 0f
        // paragraphs of 3, 1, 2, 1, 4 lines; 17 chars per line; 10 px paragraph gap
        for ((pi, count) in listOf(3, 1, 2, 1, 4).withIndex()) {
            if (pi > 0) y += 10f
            for (l in 0 until count) {
                val start = off
                val n = 17
                val arr = FloatArray(n)
                var x = if (l == 0) 20f else 0f
                for (i in 0 until n) {
                    arr[i] = x
                    advances[start + i] = adv
                    x += adv
                    if (i == 5) x += gapAfterSpace
                }
                lines += OriginCalibrator.LineBox(start, start + n, y, y + lh)
                lefts[lines.size - 1] = arr
                y += lh
                off += n
            }
            off += 1 // '\n'
        }
        return Page(lines, lefts, advances)
    }

    private fun hit(p: Page, dx: Float, dy: Float): (Float, Float) -> Int = { vx, vy ->
        val x = vx - dx
        val y = vy - dy
        var li = p.lines.indexOfFirst { y >= it.top && y < it.bottom }
        if (li < 0) {
            var best = -1
            var bestD = Float.MAX_VALUE
            p.lines.forEachIndexed { i, b ->
                val d = if (y < b.top) b.top - y else y - b.bottom
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0 && bestD <= (p.lines[best].bottom - p.lines[best].top) / 2f) li = best
        }
        if (li < 0) {
            -1
        } else {
            val b = p.lines[li]
            val arr = p.lefts.getValue(li)
            var r = b.start
            for (i in arr.indices) if (x >= arr[i]) r = b.start + i
            r
        }
    }

    @Test
    fun findsExactOrigin() {
        val p = page()
        val dx = 37f
        val dy = 61f
        val h = hit(p, dx, dy)
        val gotY = OriginCalibrator.calibrateY(p.lines, 1440, 200f, h)
        assertEquals(dy, gotY, 1.01f)
        val li = 0
        val b = p.lines[li]
        val gotX = OriginCalibrator.calibrateX(b.start, b.end, p.lefts.getValue(li), p.advances, 720, (b.top + b.bottom) / 2 + gotY, h)
        assertEquals(dx, gotX, 1.01f)
    }

    @Test
    fun toleratesJustificationGaps() {
        val p = page(gapAfterSpace = 7f)
        val h = hit(p, 18f, 44f)
        val gotY = OriginCalibrator.calibrateY(p.lines, 1440, 200f, h)
        assertEquals(44f, gotY, 1.01f)
        val b = p.lines[1]
        val gotX = OriginCalibrator.calibrateX(b.start, b.end, p.lefts.getValue(1), p.advances, 720, (b.top + b.bottom) / 2 + gotY, h)
        assertEquals(18f, gotX, 1.01f)
    }

    @Test
    fun onlySingleLineParagraphsFallsBackToGapMidpoint() {
        // Every paragraph one line with a gap: no touching lines, midpoint assumption applies.
        val lines = (0 until 8).map { i -> OriginCalibrator.LineBox(i * 11, i * 11 + 10, i * 44f, i * 44f + 34f) }
        val lefts = FloatArray(10) { it * 20f }
        val p = Page(lines, lines.indices.associateWith { lefts }, FloatArray(200) { 20f })
        val h = hit(p, 10f, 30f)
        val gotY = OriginCalibrator.calibrateY(lines, 1000, 100f, h)
        assertEquals(30f, gotY, 1.01f)
    }

    @Test
    fun returnsNaNWithoutEnoughLines() {
        val one = listOf(OriginCalibrator.LineBox(0, 5, 0f, 30f))
        assertTrue(OriginCalibrator.calibrateY(one, 100, 10f) { _, _ -> 0 }.isNaN())
        assertTrue(OriginCalibrator.calibrateY(page().lines, 100, 10f) { _, _ -> -1 }.isNaN())
    }

    @Test
    fun lineOfResolvesAmbiguity() {
        val lines = listOf(
            OriginCalibrator.LineBox(0, 10, 0f, 30f),
            OriginCalibrator.LineBox(10, 20, 30f, 60f),
            OriginCalibrator.LineBox(21, 30, 70f, 100f),
        )
        assertEquals(0, OriginCalibrator.lineOf(lines, 5))
        assertEquals(-1, OriginCalibrator.lineOf(lines, 10))
        assertEquals(1, OriginCalibrator.lineOf(lines, 15))
        assertEquals(1, OriginCalibrator.lineOf(lines, 20))
        assertEquals(2, OriginCalibrator.lineOf(lines, 21))
        assertEquals(-1, OriginCalibrator.lineOf(lines, -3))
    }
}
