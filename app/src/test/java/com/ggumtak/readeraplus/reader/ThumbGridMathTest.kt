package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbGridMathTest {

    @Test
    fun cometBodyGivesFourByThreeOf78x156() {
        // 720 dp − toolbar 56 − tabs 45 − hairline 1 − pager 44 = 574.
        val g = ThumbGridMath.layout(360f, 574f, 2.0f)
        assertEquals(4, g.cols)
        assertEquals(3, g.rows)
        assertEquals(78f, g.thumbW, 0.01f)
        assertEquals(156f, g.thumbH, 0.01f)
        assertEquals(12, ThumbGridMath.perPage(g))
        // 3 columns would give only 2 rows.
        assertEquals(2, ThumbGridMath.rowsFor(574f, ThumbGridMath.thumbWidth(360f, 3) * 2f))
    }

    @Test
    fun phoneGivesFiveByThree() {
        val g = ThumbGridMath.layout(411f, 655f, 2.17f)
        assertEquals(5, g.cols)
        assertEquals(3, g.rows)
        assertEquals(15, ThumbGridMath.perPage(g))
        assertTrue(g.thumbW >= ThumbGridMath.MIN_THUMB_DP)
        // 3 and 4 columns give 2 rows.
        assertEquals(2, ThumbGridMath.rowsFor(655f, ThumbGridMath.thumbWidth(411f, 3) * 2.17f))
        assertEquals(2, ThumbGridMath.rowsFor(655f, ThumbGridMath.thumbWidth(411f, 4) * 2.17f))
    }

    @Test
    fun landscapeTabletGetsThreeRows() {
        val g = ThumbGridMath.layout(800f, 500f, 0.625f)
        assertEquals(3, g.rows)
        assertEquals(4, g.cols) // 3 columns of 253 × 158 leave no room for a 3rd row of labels
        assertEquals(188f, g.thumbW, 0.01f)
    }

    @Test
    fun cellsFitTheBody() {
        for ((w, h, a) in listOf(Triple(360f, 574f, 2f), Triple(411f, 655f, 2.17f), Triple(800f, 500f, 0.625f),
                Triple(600f, 900f, 1.5f), Triple(320f, 300f, 2f), Triple(120f, 140f, 2f))) {
            val g = ThumbGridMath.layout(w, h, a)
            assertTrue(g.cols >= 1 && g.rows >= 1)
            val right = ThumbGridMath.cellLeft(g, g.cols - 1, w) + g.thumbW
            val bottom = ThumbGridMath.cellTop(g, g.rows - 1) + g.thumbH + ThumbGridMath.LABEL_DP
            assertTrue("$w×$h right $right", right <= w + 0.01f)
            assertTrue("$w×$h bottom $bottom", bottom <= h + 0.01f || g.rows == 1)
            assertEquals(g.thumbH, g.thumbW * a, 0.01f)
        }
    }

    @Test
    fun shortBodyFallsBackToDensestFit() {
        val g = ThumbGridMath.layout(360f, 300f, 2f) // no column count gives 3 rows
        assertEquals(1, g.rows)
        assertTrue(g.cols >= 3)
        val tiny = ThumbGridMath.layout(100f, 100f, 2f)
        assertEquals(1, tiny.rows)
        assertEquals(1, tiny.cols)
        assertTrue(tiny.thumbH <= 100f)
    }

    @Test
    fun unknownAspectUsesDefault() {
        val g = ThumbGridMath.layout(360f, 574f, 0f)
        assertEquals(g.thumbW * ThumbGridMath.DEFAULT_ASPECT, g.thumbH, 0.01f)
    }

    @Test
    fun gridPageRoundTrips() {
        for (per in intArrayOf(1, 9, 12, 15)) {
            for (page in 1..200) {
                val gp = ThumbGridMath.gridPageOf(page, per)
                val first = ThumbGridMath.firstOf(gp, per)
                assertTrue(page >= first && page < first + per)
                assertEquals(gp, ThumbGridMath.gridPageOf(first, per))
            }
        }
        assertEquals(0, ThumbGridMath.gridPageOf(1, 12))
        assertEquals(0, ThumbGridMath.gridPageOf(12, 12))
        assertEquals(1, ThumbGridMath.gridPageOf(13, 12))
        assertEquals(13, ThumbGridMath.firstOf(1, 12))
        assertEquals(272, ThumbGridMath.gridPages(3259, 12))
    }

    @Test
    fun emptyAndOnePageBooks() {
        assertEquals(1, ThumbGridMath.gridPages(0, 12))
        assertEquals(1, ThumbGridMath.gridPages(1, 12))
        assertEquals(0, ThumbGridMath.gridPageOf(0, 12))
        assertEquals(0, ThumbGridMath.countOn(0, 0, 12))
        assertEquals(1, ThumbGridMath.countOn(0, 1, 12))
    }

    @Test
    fun clampsBeyondTheBook() {
        // Page 4000 of 3259 → the last grid page, which holds 3259 − 271·12 = 7 pages.
        val gp = ThumbGridMath.clampGridPage(ThumbGridMath.gridPageOf(4000, 12), 3259, 12)
        assertEquals(271, gp)
        assertEquals(7, ThumbGridMath.countOn(gp, 3259, 12))
        assertEquals(0, ThumbGridMath.clampGridPage(-3, 3259, 12))
        assertEquals(12, ThumbGridMath.countOn(0, 3259, 12))
    }
}
