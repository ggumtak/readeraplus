package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // The whole icon, and the gap to the slot's text (MaruViewer's corner: icon, then the time).
        assertEquals(22f, BatteryMath.iconWidth(ts), 0f)
        assertEquals(11f, BatteryMath.labelGap(ts), 0.001f)
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
    fun theIconFirstHasMaruViewersShape() {
        // MaruViewer's corner icon on the user's S25 screenshot (2026-10-05, beside 39 px text: 13 sp at density 3):
        // 70 × 30 px, the nub on the left (x 46–50, 10 px tall), the body x 51–115 drawn as a 3 px outline with 3 px of
        // paper inside, four 11 px bars 3 px apart (x 71–81, 85–95, 99–109; the fourth, next to the nub, empty).
        val ts = 39f
        assertEquals(3f, BatteryMath.firstStroke(ts), 0f)
        assertEquals(11f, BatteryMath.barWidth(ts), 0f)
        assertEquals(65f, BatteryMath.bodyWidth(ts, first = true), 0f)
        assertEquals(30f, BatteryMath.bodyHeight(ts, first = true), 0f)
        assertEquals(5f, BatteryMath.nubWidth(ts, first = true), 0f)
        assertEquals(10f, BatteryMath.firstNubHeight(ts), 0f)
        assertEquals(70f, BatteryMath.iconWidth(ts, first = true), 0f)
        // The bars' right edges in the screenshot's pixels: the body from x 51 ends at 116.
        assertEquals(110f, BatteryMath.barRight(116f, ts, 0), 0f)
        assertEquals(96f, BatteryMath.barRight(116f, ts, 1), 0f)
        assertEquals(82f, BatteryMath.barRight(116f, ts, 2), 0f)
        assertEquals(68f, BatteryMath.barRight(116f, ts, 3), 0f)
        // 17 px from the icon to "오후".
        assertEquals(17f, BatteryMath.firstGap(ts), 0f)
        // The Comet at 13 sp (26 px): 2 px outline and paper, 7 px bars: 45 × 20 px.
        assertEquals(2f, BatteryMath.firstStroke(26f), 0f)
        assertEquals(42f, BatteryMath.bodyWidth(26f, first = true), 0f)
        assertEquals(20f, BatteryMath.bodyHeight(26f, first = true), 0f)
        assertEquals(6f, BatteryMath.firstNubHeight(26f), 0f)
        assertEquals(45f, BatteryMath.iconWidth(26f, first = true), 0f)
        // The icon with its number keeps its size.
        assertEquals(22f, BatteryMath.iconWidth(22f), 0f)
        for (t in listOf(2f, 8f, 12f, 22f, 26f, 33f, 39f, 48f, 80f)) {
            val w = BatteryMath.bodyWidth(t, true)
            val h = BatteryMath.bodyHeight(t, true)
            val s = BatteryMath.firstStroke(t)
            val nub = BatteryMath.firstNubHeight(t)
            for (v in listOf(w, h, s, BatteryMath.barWidth(t), BatteryMath.nubWidth(t, true), nub, BatteryMath.firstGap(t)))
                assertEquals(Math.round(v).toFloat(), v, 0f)
            assertTrue(w > BatteryMath.bodyWidth(t))
            assertTrue(w > h)
            // A bar's height inside the outline and its paper; the nub centred on the body in whole px.
            assertTrue(h - 4f * s >= 1f)
            assertTrue(nub in 1f..h - 2f)
            assertEquals(0f, (h - nub) % 2f, 0f)
            // The four bars fill the inside exactly: the last one starts a stroke of paper after the left outline.
            val bodyRight = 100f + w
            assertEquals(100f + 2f * s, BatteryMath.barRight(bodyRight, t, 3) - BatteryMath.barWidth(t), 0f)
        }
    }

    @Test
    fun theIconFirstShowsTheLevelInQuarters() {
        // User (2026-10-05): "배터리 100, 75, 50,25에 따라 배터리 아이콘이 바뀌어": 76–100 % four bars, 51–75 three, 26–50 two,
        // 0–25 one (then red on phones); unknown none (no icon).
        for ((level, bars) in listOf(100 to 4, 99 to 4, 76 to 4, 75 to 3, 51 to 3, 50 to 2, 26 to 2, 25 to 1, 1 to 1, 0 to 1, 250 to 4))
            assertEquals("$level %", bars, BatteryMath.bars(level))
        assertEquals(0, BatteryMath.bars(-1))
        // The slot keeps the step, so only a new step changes the page.
        assertEquals(100, BatteryMath.stepLevel(80))
        assertEquals(75, BatteryMath.stepLevel(75))
        assertEquals(50, BatteryMath.stepLevel(26))
        assertEquals(25, BatteryMath.stepLevel(0))
        assertEquals(-1, BatteryMath.stepLevel(-5))
        for (lv in 0..100) {
            assertEquals(BatteryMath.bars(lv), BatteryMath.bars(BatteryMath.stepLevel(lv)))
            assertEquals(lv <= 25, BatteryMath.low(lv))
        }
        assertTrue(BatteryMath.low(BatteryMath.stepLevel(10)))
        assertFalse(BatteryMath.low(-1))
    }

    @Test
    fun theIconFirstTurnsRedOnlyOnPhonesAtOneBar() {
        val status = 0xFFD7BC86.toInt()
        val low = 0xFFE96E57.toInt()
        for (lv in -1..100) {
            // E-ink: greys only, never the red.
            assertEquals(status, BatteryMath.firstColor(eink = true, level = lv, statusColor = status, lowColor = low))
            assertEquals("$lv %", if (lv in 0..25) low else status,
                BatteryMath.firstColor(eink = false, level = lv, statusColor = status, lowColor = low))
            // Charging: MaruViewer's gold at any level (21 % on the S25).
            assertEquals(status, BatteryMath.firstColor(eink = false, level = lv, statusColor = status, lowColor = low, charging = true))
        }
    }

    @Test
    fun theChargingBoltLiesOnItsSideInsideTheBody() {
        val b = BatteryMath.CHARGING_BOLT
        assertEquals(14, b.size)
        for (v in b) assertTrue("$v", v > 0.1f && v < 0.9f)
        var area = 0f
        var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
        for (i in 0 until 7) {
            val x0 = b[2 * i]; val y0 = b[2 * i + 1]
            val x1 = b[(2 * i + 2) % 14]; val y1 = b[(2 * i + 3) % 14]
            area += x0 * y1 - x1 * y0
            minX = minOf(minX, x0); maxX = maxOf(maxX, x0); minY = minOf(minY, y0); maxY = maxOf(maxY, y0)
        }
        // A real shape (about a fifth of the body), wider than tall in px (the body is 65 × 30), pointing right.
        assertTrue("area $area", Math.abs(area / 2f) in 0.12f..0.3f)
        assertTrue((maxX - minX) * 65f > (maxY - minY) * 30f)
        assertEquals(maxX, b[6])
    }

    @Test
    fun theIconFirstStandsWhereMaruViewersDoes() {
        // The S25 in fullscreen, 13 sp = 39 px in the phone's font: the line's ink (Hangul, parenthesis) from −31.3 px,
        // so its top on row 15 puts the baseline at 46.3; digits −29 .. −0.6 px around it (rows ≈ 17–46). MaruViewer's
        // icon body is on rows 15–44 beside its digits on 16–45: slightly high, its top level with the Hangul's. Ours too.
        val ts = 39f
        val baseline = StatusFit.headerBaseline(87f, 80.4f, -31.3f, 7.7f, 57f, 3f)
        assertEquals(46.3f, baseline, 0.001f)
        val top = BatteryMath.firstTop(baseline, (-29f - 0.6f) / 2f, ts)
        assertEquals(15f, top, 0f)
        assertEquals(44f, top + BatteryMath.bodyHeight(ts, first = true) - 1f, 0f)
        // At every size its middle is FIRST_RAISE above the digits' middle (within the whole-px rounding).
        for (t in 16..80) {
            val size = t.toFloat()
            val digitMiddle = -0.37f * size
            val middle = BatteryMath.firstTop(100f, digitMiddle, size) + BatteryMath.bodyHeight(size, true) / 2f
            assertEquals("$t px", 100f + digitMiddle - BatteryMath.FIRST_RAISE * size, middle, 0.501f)
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
