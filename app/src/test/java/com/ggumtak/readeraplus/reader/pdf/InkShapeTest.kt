package com.ggumtak.readeraplus.reader.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class InkShapeTest {
    @Test
    fun widthGrowsWithPressureWithinBounds() {
        assertEquals(10f * InkShape.MIN_WIDTH_RATIO, InkShape.widthAt(10f, 0f), 1e-4f)
        assertEquals(10f, InkShape.widthAt(10f, 1f), 1e-4f)
        assertEquals(10f, InkShape.widthAt(10f, 3f), 1e-4f)
        assertEquals(10f * InkShape.MIN_WIDTH_RATIO, InkShape.widthAt(10f, -1f), 1e-4f)
        assertEquals(10f, InkShape.widthAt(10f, Float.NaN), 1e-4f)
        val light = InkShape.widthAt(10f, 0.2f)
        val mid = InkShape.widthAt(10f, 0.5f)
        val firm = InkShape.widthAt(10f, 0.8f)
        assertTrue(light < mid && mid < firm)
        // Rises faster than linear at light pressure.
        assertTrue(mid > 10f * (InkShape.MIN_WIDTH_RATIO + (1 - InkShape.MIN_WIDTH_RATIO) * 0.5f))
    }

    @Test
    fun steadyEasesTowardNewReadings() {
        assertEquals(0.6f, InkShape.steady(Float.NaN, 0.6f), 1e-6f)
        val next = InkShape.steady(0.2f, 1f)
        assertEquals(0.2f + 0.8f * InkShape.PRESSURE_SMOOTHING, next, 1e-6f)
        assertEquals(1f, InkShape.steady(1f, 5f), 1e-6f)
    }

    @Test
    fun simplifyKeepsPressuresOfKeptPoints() {
        val pts = floatArrayOf(0f, 0f, 0.1f, 0f, 5f, 0f, 5.1f, 0f, 10f, 0f)
        val q = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val (p2, q2) = InkShape.simplify(pts, q, 5, 1f)
        assertArrayEquals(floatArrayOf(0f, 0f, 5f, 0f, 10f, 0f), p2, 0f)
        assertArrayEquals(floatArrayOf(0.1f, 0.3f, 0.5f), q2!!, 0f)
        val (p3, q3) = InkShape.simplify(pts, null, 5, 1f)
        assertEquals(6, p3.size)
        assertNull(q3)
        // Count beyond the data is clamped; two points stay as they are.
        assertEquals(4, InkShape.simplify(pts, q, 2, 100f).first.size)
    }

    @Test
    fun outlineOfAStraightStrokeHasTheRightWidth() {
        val pts = floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f)
        val q = floatArrayOf(1f, 1f, 1f)
        val o = InkShape.outline(pts, q, 4f)
        // Left side first: offset by half the width (2) from y = 0.
        assertEquals(0f, o[0], 1e-4f)
        assertEquals(2f, kotlin.math.abs(o[1]), 1e-4f)
        // Every outline point is within half the width of the centre line (caps included).
        for (i in 0 until o.size / 2) {
            val x = o[i * 2].coerceIn(0f, 20f)
            val d = hypot(o[i * 2] - x, o[i * 2 + 1])
            assertTrue("point $i at distance $d", d <= 2f + 1e-3f)
        }
        // Closed shape: left 3 + cap 6 + right 3 + cap 6 points.
        assertEquals((3 + 6 + 3 + 6) * 2, o.size)
    }

    @Test
    fun lightPressureMakesItThinner() {
        val pts = floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f)
        val firm = InkShape.outline(pts, floatArrayOf(1f, 1f, 1f), 4f)
        val light = InkShape.outline(pts, floatArrayOf(0.1f, 0.1f, 0.1f), 4f)
        assertTrue(kotlin.math.abs(light[3]) < kotlin.math.abs(firm[3]))
    }

    @Test
    fun aDotIsACircle() {
        val o = InkShape.outline(floatArrayOf(5f, 5f, 5f, 5f), floatArrayOf(1f, 1f), 2f)
        assertEquals(24, o.size)
        for (i in 0 until o.size / 2) assertEquals(1f, hypot(o[i * 2] - 5f, o[i * 2 + 1] - 5f), 1e-3f)
        assertEquals(0, InkShape.outline(FloatArray(0), FloatArray(0), 2f).size)
    }
}
