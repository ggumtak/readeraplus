package com.ggumtak.readeraplus.render

import org.junit.Assert.*
import org.junit.Test

class DashMathTest {
    @Test fun fragmentsUseTheSameGlobalGridIncludingNegativeCoordinates() {
        for (left in -1000..1000) {
            val l = left * 0.37f
            val start = DashMath.firstDash(l, 10f)
            assertTrue(start <= l)
            assertTrue(start + 10f > l)
            assertEquals(0f, start % 10f, 0f)
        }
    }
    @Test fun clippedFragmentsNeverPaintPastTheirOwnRange() {
        fun segments(l: Float, r: Float): List<Pair<Float, Float>> {
            val out = ArrayList<Pair<Float, Float>>()
            var x = DashMath.firstDash(l, 10f)
            while (x < r) {
                val a = maxOf(x, l); val b = minOf(x + 6f, r)
                if (b > a) out.add(a to b)
                x += 10f
            }
            return out
        }
        assertEquals(listOf(3f to 6f, 10f to 16f, 20f to 22f), segments(3f, 22f))
        assertTrue(segments(4f, 4f).isEmpty())
        assertEquals(listOf(13f to 16f), segments(13f, 19f))
    }
}
