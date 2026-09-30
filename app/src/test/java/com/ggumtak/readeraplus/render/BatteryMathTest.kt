package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryMathTest {

    @Test
    fun sizesFollowTheStatusTextInWholePixels() {
        // 11 sp status text on the Comet (density 2): 22 px.
        val ts = 22f
        assertEquals(20f, BatteryMath.bodyWidth(ts), 0f)
        assertEquals(11f, BatteryMath.bodyHeight(ts), 0f)
        assertEquals(2f, BatteryMath.nubWidth(ts), 0f)
        assertEquals(6f, BatteryMath.nubHeight(ts), 0f)
        assertEquals(5.5f, BatteryMath.gap(ts), 0.001f)
        // Tiny text still leaves room for a 1 px outline, 1 px of paper and a fill.
        assertEquals(6f, BatteryMath.bodyWidth(2f), 0f)
        assertEquals(5f, BatteryMath.bodyHeight(2f), 0f)
        assertEquals(1f, BatteryMath.nubWidth(2f), 0f)
        assertEquals(1f, BatteryMath.nubHeight(2f), 0f)
        for (t in listOf(12f, 22f, 33f, 48f)) {
            for (v in listOf(BatteryMath.bodyWidth(t), BatteryMath.bodyHeight(t), BatteryMath.nubWidth(t), BatteryMath.nubHeight(t))) {
                assertEquals(Math.round(v).toFloat(), v, 0f)
            }
            assertTrue(BatteryMath.bodyWidth(t) > BatteryMath.bodyHeight(t))
            assertTrue(BatteryMath.nubHeight(t) < BatteryMath.bodyHeight(t))
        }
    }

    @Test
    fun fillIsProportionalToTheLevel() {
        val l = 102f
        val r = 118f // 16 px inside
        assertEquals(l, BatteryMath.fillRight(l, r, 0), 0f)
        assertEquals(r, BatteryMath.fillRight(l, r, 100), 0f)
        assertEquals(110f, BatteryMath.fillRight(l, r, 50), 0f)
        assertEquals(106f, BatteryMath.fillRight(l, r, 25), 0f)
        // Any charge shows at least one px; out-of-range levels are clamped.
        assertEquals(103f, BatteryMath.fillRight(l, r, 1), 0f)
        assertEquals(r, BatteryMath.fillRight(l, r, 250), 0f)
        assertEquals(l, BatteryMath.fillRight(l, r, -1), 0f)
        // Monotonic, whole px.
        var prev = l
        for (lv in 0..100) {
            val x = BatteryMath.fillRight(l, r, lv)
            assertTrue(x >= prev && x <= r)
            assertEquals(Math.round(x).toFloat(), x, 0f)
            prev = x
        }
        // No room: no fill.
        assertEquals(10f, BatteryMath.fillRight(10f, 10f, 80), 0f)
        assertEquals(10f, BatteryMath.fillRight(10f, 9f, 80), 0f)
    }
}
