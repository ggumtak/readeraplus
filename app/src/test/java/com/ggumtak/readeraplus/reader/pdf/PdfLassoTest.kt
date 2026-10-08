package com.ggumtak.readeraplus.reader.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLassoTest {
    /** A rectangle loop as a polygon (4 points). */
    private fun box(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, t, r, b, l, b)

    /** Three lines of text, 100–500 wide, 12 high, 20 apart. */
    private val lines = floatArrayOf(
        100f, 100f, 500f, 112f,
        100f, 120f, 500f, 132f,
        100f, 140f, 500f, 152f,
    )

    @Test
    fun loopAroundAWordStartsAndStopsInsideIt() {
        // Around x 200..260 of the middle line only.
        val e = PdfLasso.ends(box(198f, 115f, 262f, 137f), 4, lines, 3)
        assertNotNull(e)
        e!!
        assertTrue(e[0] >= 198f && e[0] <= 201f)
        assertEquals(126f, e[1], 0.001f)
        assertTrue(e[2] <= 262f && e[2] >= 259f)
        assertEquals(126f, e[3], 0.001f)
    }

    @Test
    fun loopAroundABlockSpansFirstToLastLine() {
        val e = PdfLasso.ends(box(90f, 95f, 510f, 157f), 4, lines, 3)!!
        assertEquals(106f, e[1], 0.001f)
        assertEquals(146f, e[3], 0.001f)
        // Whole lines: starts at the first line's left, stops at the last line's right.
        assertTrue(e[0] < 102f)
        assertTrue(e[2] > 498f)
        assertArrayEquals(intArrayOf(0, 1, 2), PdfLasso.crossed(box(90f, 95f, 510f, 157f), 4, lines, 3))
    }

    @Test
    fun loopMissingTheMiddleOfALineDoesNotCrossIt() {
        // Covers only the top 4 points of the first line (its middle is at 106).
        assertNull(PdfLasso.ends(box(150f, 90f, 300f, 104f), 4, lines, 3))
        assertArrayEquals(IntArray(0), PdfLasso.crossed(box(150f, 90f, 300f, 104f), 4, lines, 3))
    }

    @Test
    fun noLinesOrDegenerateLoop() {
        assertNull(PdfLasso.ends(box(0f, 0f, 10f, 10f), 4, FloatArray(0), 0))
        assertNull(PdfLasso.ends(floatArrayOf(0f, 0f, 10f, 10f), 2, lines, 3))
        // Empty line rectangles are skipped.
        assertNull(PdfLasso.ends(box(0f, 0f, 600f, 600f), 4, floatArrayOf(10f, 10f, 10f, 20f), 1))
    }

    @Test
    fun selectionNeedsASizeableLoop() {
        assertFalse(PdfLasso.isSelection(box(10f, 10f, 12f, 13f), 4))
        assertTrue(PdfLasso.isSelection(box(10f, 10f, 30f, 13f), 4))
        assertFalse(PdfLasso.isSelection(floatArrayOf(0f, 0f, 50f, 50f), 2))
    }
}
