package com.ggumtak.readeraplus.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalMarginTest {
    private val d = ReaderSettings()

    @Test
    fun eachSidesDefaultIsFortyDpFromTheEdgeLessItsBand() {
        // 2026-10-05: the margins count from the status bands; the defaults keep the text box 40 dp from the edges.
        assertEquals(VerticalMargin.EDGE_DP - StatusBands.headerDp(d), VerticalMargin.TOP_ZERO_DP)
        assertEquals(VerticalMargin.EDGE_DP - StatusBands.footerDp(d), VerticalMargin.BOTTOM_ZERO_DP)
        assertEquals(18, d.marginTopDp)
        assertEquals(24, d.marginBottomDp)
        assertEquals(40, StatusBands.headerDp(d) + d.marginTopDp)
        assertEquals(40, StatusBands.footerDp(d) + d.marginBottomDp)
    }

    @Test
    fun oneStepperEachSidesOwnZero() {
        // "0" is each side's default; one value moves both by the same step (step 2), each within 0..80 dp.
        assertEquals(0, VerticalMargin.toUi(18, 24))
        assertEquals(18, VerticalMargin.topDp(0))
        assertEquals(24, VerticalMargin.bottomDp(0))
        assertEquals(listOf(28, 34), listOf(VerticalMargin.topDp(10), VerticalMargin.bottomDp(10)))
        assertEquals(10, VerticalMargin.toUi(28, 34))
        // The top reaches 0 at −18, the bottom at −24 (the stepper's end): never below 0.
        assertEquals(listOf(0, 6), listOf(VerticalMargin.topDp(-18), VerticalMargin.bottomDp(-18)))
        assertEquals(-18, VerticalMargin.toUi(0, 6))
        assertEquals(-20, VerticalMargin.toUi(0, 4))
        assertEquals(-24, VerticalMargin.UI_MIN)
        assertEquals(listOf(0, 0), listOf(VerticalMargin.topDp(VerticalMargin.UI_MIN), VerticalMargin.bottomDp(VerticalMargin.UI_MIN)))
        assertEquals(VerticalMargin.UI_MIN, VerticalMargin.toUi(0, 0))
        // The bottom reaches 80 at +56, the top at +62 (the other end).
        assertEquals(62, VerticalMargin.UI_MAX)
        assertEquals(listOf(80, 80), listOf(VerticalMargin.topDp(VerticalMargin.UI_MAX), VerticalMargin.bottomDp(VerticalMargin.UI_MAX)))
        assertEquals(VerticalMargin.UI_MAX, VerticalMargin.toUi(80, 80))
        assertEquals(56, VerticalMargin.toUi(74, 80))
        assertEquals(0, (VerticalMargin.UI_MAX - VerticalMargin.UI_MIN) % VerticalMargin.UI_STEP)
        assertEquals(0, VerticalMargin.UI_MIN % VerticalMargin.UI_STEP)
        // Every step the stepper makes reads back as itself.
        for (ui in VerticalMargin.UI_MIN..VerticalMargin.UI_MAX step VerticalMargin.UI_STEP)
            assertEquals(ui, VerticalMargin.toUi(VerticalMargin.topDp(ui), VerticalMargin.bottomDp(ui)))
        assertEquals("0", VerticalMargin.label(0))
        assertEquals("+4", VerticalMargin.label(4))
        assertEquals("−10", VerticalMargin.label(-10))
    }

    @Test
    fun marginsSavedFromTheEdgeMoveOntoTheBandsOnce() {
        // The user's devices hold 40/40 saved from the edge with the default bands: exactly the new defaults.
        val old = d.copy(marginTopDp = 40, marginBottomDp = 40)
        val moved = VerticalMargin.fromEdge(old)
        assertEquals(listOf(18, 24), listOf(moved.marginTopDp, moved.marginBottomDp))
        assertEquals(d, moved)
        // The bands of those same settings come off: no header (0), footer items above the line (36 dp).
        val other = old.copy(headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE)
            .withSlot(1, 1, StatusItem.PAGE)
        val movedOther = VerticalMargin.fromEdge(other)
        assertEquals(listOf(40, 4), listOf(movedOther.marginTopDp, movedOther.marginBottomDp))
        // A margin smaller than its band stops at 0 (the box moves by the difference: it can't be helped).
        assertEquals(0, VerticalMargin.topFromEdge(10, d))
        assertEquals(0, VerticalMargin.bottomFromEdge(10, d))
        // Only the sides that were saved move.
        assertEquals(listOf(18, 40), VerticalMargin.fromEdge(old, top = true, bottom = false).let { listOf(it.marginTopDp, it.marginBottomDp) })
        // Which saves count from the edge: no marker (≤ R2) and U3's 40; this build's marker never.
        assertTrue(VerticalMargin.countsFromEdge(null))
        assertTrue(VerticalMargin.countsFromEdge(VerticalMargin.EDGE))
        assertFalse(VerticalMargin.countsFromEdge(VerticalMargin.BANDS))
        assertTrue(VerticalMargin.BANDS != VerticalMargin.EDGE)
    }

    @Test
    fun onlyTheUntouchedOldDefaultMigrates() {
        assertTrue(VerticalMargin.isLegacyDefault(hasMarker = false, top = 16, bottom = 16))
        assertFalse(VerticalMargin.isLegacyDefault(hasMarker = true, top = 16, bottom = 16))
        assertFalse(VerticalMargin.isLegacyDefault(hasMarker = false, top = 24, bottom = 24))
        assertFalse(VerticalMargin.isLegacyDefault(hasMarker = false, top = 16, bottom = 20))
        assertTrue(VerticalMargin.KEY != SideMargin.KEY)
    }
}
