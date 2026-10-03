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
