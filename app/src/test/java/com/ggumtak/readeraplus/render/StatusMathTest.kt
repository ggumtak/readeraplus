package com.ggumtak.readeraplus.render

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class StatusMathTest {
    private fun fit(w: Float, l: Float, c: Float, r: Float, el: Boolean = false,
                    ec: Boolean = false, er: Boolean = false): FloatArray = FloatArray(3).also {
        StatusMath.allocate(w, 11f, l, c, r, el, ec, er, 33f, it)
    }

    @Test fun centreStaysExactlyCentredWithUnequalFixedSides() {
        val a = fit(280f, 55f, 200f, 28f, ec = true)
        assertArrayEquals(floatArrayOf(55f, 148f, 28f), a, 0f)
        assertEquals(140f, (280f - a[1]) / 2f + a[1] / 2f, 0f)
    }
    @Test fun fixedCentreMakesElasticSidesShrinkSymmetrically() {
        assertArrayEquals(floatArrayOf(64f, 130f, 64f), fit(280f, 100f, 130f, 200f, el = true, er = true), 0f)
    }
    @Test fun shortElasticSideKeepsItsNaturalWidth() {
        assertArrayEquals(floatArrayOf(40f, 0f, 149f), fit(200f, 40f, 0f, 200f, el = true, er = true), 0f)
    }
    @Test fun bothLongElasticSidesSplitAvailableWidth() {
        assertArrayEquals(floatArrayOf(94.5f, 0f, 94.5f), fit(200f, 300f, 0f, 250f, el = true, er = true), 0f)
    }
    @Test fun onlyTheElasticSideShrinks() {
        assertArrayEquals(floatArrayOf(100f, 0f, 89f), fit(200f, 100f, 0f, 150f, er = true), 0f)
    }
    @Test fun overflowingFixedSidesHideTheLeftWithoutCuttingDigits() {
        assertArrayEquals(floatArrayOf(0f, 0f, 150f), fit(200f, 100f, 0f, 150f), 0f)
    }
    @Test fun elasticBelowThreeEmDisappearsButANaturallyShortTitleFits() {
        assertArrayEquals(floatArrayOf(100f, 0f, 0f), fit(130f, 100f, 0f, 150f, er = true), 0f)
        assertArrayEquals(floatArrayOf(20f, 0f, 0f), fit(20f, 20f, 0f, 0f, el = true), 0f)
    }
    @Test fun fixedSidesThatWouldTouchTheCentreAreHidden() {
        assertArrayEquals(floatArrayOf(0f, 150f, 20f), fit(220f, 55f, 150f, 20f), 0f)
    }
    @Test fun aSingleSlotDoesNotPayAGap() {
        assertArrayEquals(floatArrayOf(0f, 0f, 200f), fit(200f, 0f, 0f, 300f, er = true), 0f)
    }
    @Test fun zeroWidthAndOversizedFixedSlotDrawNothing() {
        assertArrayEquals(FloatArray(3), fit(0f, 30f, 30f, 30f), 0f)
        assertArrayEquals(FloatArray(3), fit(20f, 0f, 0f, 100f), 0f)
    }
    @Test fun ribbonReserveLeavesTheDefaultHeaderAsItIs() {
        // S25 defaults (px): a 960 px column, gap 33; battery icon · "오전 08:53" 229 | a long book title | "12 / 3259"
        // 150; the bookmark ribbon's place at the right end: 33. The slots do not depend on whether the page is
        // bookmarked (the reserve is always passed), and match the band without a ribbon here.
        val a = FloatArray(3)
        StatusMath.allocate(960f, 33f, 229f, 600f, 150f, false, true, false, 99f, a, reserveRight = 33f)
        assertArrayEquals(floatArrayOf(229f, 436f, 150f), a, 0f)
        val plain = FloatArray(3)
        StatusMath.allocate(960f, 33f, 229f, 600f, 150f, false, true, false, 99f, plain)
        assertArrayEquals(plain, a, 0f)
        // On a bookmarked page the right slot is drawn 33 px in: still a gap after the centre.
        assertTrue((960f + a[1]) / 2f + 33f <= 960f - 33f - a[2])
    }
    @Test fun ribbonReserveKeepsEverySlotClearOfTheRibbon() {
        // No right item: the centre alone keeps clear of the ribbon's place (still centred on the whole band).
        val c = FloatArray(3)
        StatusMath.allocate(960f, 33f, 0f, 2000f, 0f, false, true, false, 99f, c, reserveRight = 33f)
        assertEquals(960f - 2f * (33f + 33f), c[1], 0f)
        // A fixed centre too wide for that is hidden, as one too wide for the band.
        StatusMath.allocate(300f, 11f, 0f, 260f, 0f, false, false, false, 33f, c, reserveRight = 30f)
        assertEquals(0f, c[1], 0f)
        // A lone left title stops before it.
        StatusMath.allocate(960f, 33f, 2000f, 0f, 0f, true, false, false, 99f, c, reserveRight = 33f)
        assertArrayEquals(floatArrayOf(927f, 0f, 0f), c, 0f)
        // Left and right without a centre share the band less the reserve.
        StatusMath.allocate(300f, 10f, 200f, 0f, 200f, true, false, true, 33f, c, reserveRight = 30f)
        assertEquals(270f, c[0] + c[2] + 10f, 0f)
    }
    @Test fun randomWidthsWithARibbonReserveNeverOverlapIt() {
        val rnd = Random(5821)
        val g = 11f
        val a = FloatArray(3)
        repeat(20_000) {
            val w = rnd.nextInt(1, 600).toFloat()
            val res = rnd.nextInt(0, 60).toFloat()
            val natural = FloatArray(3) { rnd.nextInt(0, 500).toFloat() }
            val elastic = BooleanArray(3) { rnd.nextBoolean() }
            StatusMath.allocate(w, g, natural[0], natural[1], natural[2], elastic[0], elastic[1], elastic[2], 33f, a, res)
            for (i in 0..2) {
                assertTrue(a[i] >= 0f && a[i] <= w && a[i] <= natural[i])
                if (!elastic[i]) assertTrue(a[i] == 0f || a[i] == natural[i])
            }
            // Worst case: the page is bookmarked, the right slot is drawn `res` in, and nothing may reach the ribbon.
            val ribbon = w - minOf(res, w)
            val rightLeft = ribbon - a[2]
            if (a[1] > 0f) {
                val centreLeft = (w - a[1]) / 2f
                val centreRight = centreLeft + a[1]
                assertTrue(centreRight <= ribbon + 0.001f)
                if (a[0] > 0f) assertTrue(a[0] + g <= centreLeft + 0.001f)
                if (a[2] > 0f) assertTrue(centreRight + g <= rightLeft + 0.001f)
            } else {
                assertTrue(a[0] <= ribbon + 0.001f)
                if (a[0] > 0f && a[2] > 0f) assertTrue(a[0] + g <= rightLeft + 0.001f)
                if (a[2] > 0f) assertTrue(rightLeft >= -0.001f)
            }
        }
    }
    @Test fun randomWidthsNeverOverlapOrShortenFixedItems() {
        val rnd = Random(4203)
        repeat(20_000) {
            val w = rnd.nextInt(1, 600).toFloat()
            val natural = FloatArray(3) { rnd.nextInt(0, 500).toFloat() }
            val elastic = BooleanArray(3) { rnd.nextBoolean() }
            val a = fit(w, natural[0], natural[1], natural[2], elastic[0], elastic[1], elastic[2])
            for (i in 0..2) {
                assertTrue(a[i] >= 0f && a[i] <= w && a[i] <= natural[i])
                if (!elastic[i]) assertTrue(a[i] == 0f || a[i] == natural[i])
                if (elastic[i] && a[i] > 0f) assertTrue(a[i] >= minOf(natural[i], 33f))
            }
            if (a[1] > 0f) {
                val centreLeft = (w - a[1]) / 2f
                if (a[0] > 0f) assertTrue(a[0] + 11f <= centreLeft + 0.001f)
                if (a[2] > 0f) assertTrue(centreLeft + a[1] + 11f <= w - a[2] + 0.001f)
            } else if (a[0] > 0f && a[2] > 0f) assertTrue(a[0] + 11f + a[2] <= w + 0.001f)
        }
    }
}
