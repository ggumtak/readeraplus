package com.ggumtak.readeraplus.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalMarginTest {
    private val d = ReaderSettings()

    @Test
    fun eachSidesDefaultIsFortyDpFromTheEdgeLessItsBand() {
        // 2026-10-05: the margins count from the status bands; the top's default keeps the text box 40 dp from the top.
        // 2026-10-06 ("위에 여백좀 줄여줘"): the bottom's is 10 dp over the progress line's band, 28 dp from the edge.
        assertEquals(VerticalMargin.EDGE_DP - StatusBands.headerDp(d), VerticalMargin.TOP_ZERO_DP)
        assertEquals(VerticalMargin.EDGE_DP - StatusBands.footerDp(d), VerticalMargin.OLD_BOTTOM_ZERO_DP)
        assertEquals(VerticalMargin.OLD_BOTTOM_ZERO_DP - VerticalMargin.BOTTOM_SHIFT_DP, VerticalMargin.BOTTOM_ZERO_DP)
        assertEquals(15, d.marginTopDp)
        assertEquals(10, d.marginBottomDp)
        assertEquals(40, StatusBands.headerDp(d) + d.marginTopDp)
        assertEquals(28, StatusBands.footerDp(d) + d.marginBottomDp)
        // The old 11 sp default with its 18 dp top: the same 40 dp (MaruSize.keepBox moves one to the other).
        assertEquals(40, StatusBands.headerDp(d.copy(statusFontSizeSp = MaruSize.OLD_SP)) + 18)
    }

    @Test
    fun oneStepperEachSidesOwnZero() {
        // "0" is each side's default; one value moves both by the same step (step 2), each within 0..80 dp.
        assertEquals(0, VerticalMargin.toUi(15, 10))
        assertEquals(15, VerticalMargin.topDp(0))
        assertEquals(10, VerticalMargin.bottomDp(0))
        assertEquals(listOf(25, 20), listOf(VerticalMargin.topDp(10), VerticalMargin.bottomDp(10)))
        assertEquals(10, VerticalMargin.toUi(25, 20))
        // The bottom reaches 0 at −10, the top at −15 (the stepper's end): never below 0.
        assertEquals(listOf(5, 0), listOf(VerticalMargin.topDp(-10), VerticalMargin.bottomDp(-10)))
        assertEquals(-10, VerticalMargin.toUi(5, 0))
        assertEquals(-13, VerticalMargin.toUi(2, 0))
        assertEquals(-15, VerticalMargin.UI_MIN)
        assertEquals(listOf(0, 0), listOf(VerticalMargin.topDp(VerticalMargin.UI_MIN), VerticalMargin.bottomDp(VerticalMargin.UI_MIN)))
        assertEquals(VerticalMargin.UI_MIN, VerticalMargin.toUi(0, 0))
        // The top reaches 80 at +65, the bottom at +70 (the other end); past +65 the bottom tells the value.
        assertEquals(70, VerticalMargin.UI_MAX)
        assertEquals(listOf(80, 80), listOf(VerticalMargin.topDp(VerticalMargin.UI_MAX), VerticalMargin.bottomDp(VerticalMargin.UI_MAX)))
        assertEquals(VerticalMargin.UI_MAX, VerticalMargin.toUi(80, 80))
        assertEquals(68, VerticalMargin.toUi(80, 78))
        assertEquals(65, VerticalMargin.toUi(80, 75))
        assertEquals(listOf(79, 74), listOf(VerticalMargin.topDp(64), VerticalMargin.bottomDp(64)))
        // Every value the stepper can show (its even steps and its odd lower end, Fmt.stepFloat) reads back as itself.
        for (ui in listOf(VerticalMargin.UI_MIN) + (-14..VerticalMargin.UI_MAX step VerticalMargin.UI_STEP))
            assertEquals(ui, VerticalMargin.toUi(VerticalMargin.topDp(ui), VerticalMargin.bottomDp(ui)))
        for (ui in VerticalMargin.UI_MIN..VerticalMargin.UI_MAX)
            assertEquals(ui, VerticalMargin.toUi(VerticalMargin.topDp(ui), VerticalMargin.bottomDp(ui)))
        assertEquals("0", VerticalMargin.label(0))
        assertEquals("+4", VerticalMargin.label(4))
        assertEquals("−10", VerticalMargin.label(-10))
    }

    @Test
    fun aStepMovesBothSidesByTheStepFromTheMarginsThePageHas() {
        // On the defaults' line the stepper follows it, clamps included: "0" is always the defaults.
        assertEquals(listOf(17, 12), VerticalMargin.step(15, 10, 0, 2).toList())
        assertEquals(listOf(15, 10), VerticalMargin.step(17, 12, 2, 0).toList())
        assertEquals(listOf(2, 0), VerticalMargin.step(0, 0, -15, -13).toList())
        assertEquals(listOf(80, 76), VerticalMargin.step(79, 74, 64, 66).toList())
        for (ui in listOf(VerticalMargin.UI_MIN) + (-14 until VerticalMargin.UI_MAX step VerticalMargin.UI_STEP)) {
            val to = if (ui == VerticalMargin.UI_MIN) -14 else ui + 2
            val up = VerticalMargin.step(VerticalMargin.topDp(ui), VerticalMargin.bottomDp(ui), ui, to)
            assertEquals(listOf(VerticalMargin.topDp(to), VerticalMargin.bottomDp(to)), up.toList())
        }
        // Off the line, both sides move by the step and neither jumps: no header items (40/40 from the edge became 40/22,
        // "+25"), footer items (15/1, "0"), the minimal 4/4 while 여백 사용 is off ("−11"), the old default 18/22 of a
        // chosen size ("+3").
        assertEquals(listOf(42, 24), VerticalMargin.step(40, 22, 25, 27).toList())
        assertEquals(listOf(17, 3), VerticalMargin.step(15, 1, 0, 2).toList())
        assertEquals(listOf(13, 0), VerticalMargin.step(15, 1, 0, -2).toList())
        assertEquals(listOf(6, 6), VerticalMargin.step(4, 4, -11, -9).toList())
        assertEquals(listOf(16, 20), VerticalMargin.step(18, 22, 3, 1).toList())
        // Off the line the sides keep moving together down to the stepper's end, each stopping at 0.
        var tb = intArrayOf(15, 19)
        for (ui in 0 downTo -12 step VerticalMargin.UI_STEP) tb = VerticalMargin.step(tb[0], tb[1], ui, ui - 2)
        assertEquals(listOf(1, 5), tb.toList())
        assertEquals(listOf(0, 4), VerticalMargin.step(tb[0], tb[1], -14, -15).toList())
        // Each within 0..80.
        assertEquals(listOf(14, 0), VerticalMargin.step(16, 0, -2, -4).toList())
        assertEquals(listOf(80, 72), VerticalMargin.step(80, 70, 65, 67).toList())
    }

    @Test
    fun bottomMarginsSavedBeforeTheNewZeroMoveOnce() {
        // Saved with the 22 dp "0" (marker BANDS_V1, the edge's, or none): 12 dp less once, never below 0.
        assertEquals(10, VerticalMargin.shiftBottom(22))
        assertEquals(0, VerticalMargin.shiftBottom(5))
        assertEquals(48, VerticalMargin.shiftBottom(60))
        assertTrue(VerticalMargin.needsBottomShift(VerticalMargin.BANDS_V1))
        assertTrue(VerticalMargin.needsBottomShift(VerticalMargin.EDGE))
        assertTrue(VerticalMargin.needsBottomShift(null))
        assertFalse(VerticalMargin.needsBottomShift(VerticalMargin.BANDS))
        // A pair on the old defaults' line lands on the new one: the stepper shows the same value.
        for (ui in -10..60 step 2) {
            val top = VerticalMargin.topDp(ui)
            val oldBottom = (VerticalMargin.OLD_BOTTOM_ZERO_DP + ui).coerceIn(0, VerticalMargin.MAX_DP)
            assertEquals(ui, VerticalMargin.toUi(top, VerticalMargin.shiftBottom(oldBottom)))
        }
    }

    @Test
    fun marginsSavedFromTheEdgeMoveOntoTheBandsOnce() {
        // The user's devices hold 40/40 saved from the edge with the default bands: exactly the new defaults.
        val old = d.copy(marginTopDp = 40, marginBottomDp = 40)
        val moved = VerticalMargin.fromEdge(old)
        assertEquals(listOf(15, 22), listOf(moved.marginTopDp, moved.marginBottomDp))
        // ...and the bottom's one-time shift (2026-10-06) on load: the defaults.
        assertEquals(d, moved.copy(marginBottomDp = VerticalMargin.shiftBottom(moved.marginBottomDp)))
        // The bands of those same settings come off: no header (0), footer items above the line (39 dp).
        val other = old.copy(headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE)
            .withSlot(1, 1, StatusItem.PAGE)
        val movedOther = VerticalMargin.fromEdge(other)
        assertEquals(listOf(40, 1), listOf(movedOther.marginTopDp, movedOther.marginBottomDp))
        // A margin smaller than its band stops at 0 (the box moves by the difference: it can't be helped).
        assertEquals(0, VerticalMargin.topFromEdge(10, d))
        assertEquals(0, VerticalMargin.bottomFromEdge(10, d))
        // Only the sides that were saved move.
        assertEquals(listOf(15, 40), VerticalMargin.fromEdge(old, top = true, bottom = false).let { listOf(it.marginTopDp, it.marginBottomDp) })
        // Which saves count from the edge: no marker (≤ R2) and U3's 40; this build's marker never.
        assertTrue(VerticalMargin.countsFromEdge(null))
        assertTrue(VerticalMargin.countsFromEdge(VerticalMargin.EDGE))
        assertFalse(VerticalMargin.countsFromEdge(VerticalMargin.BANDS))
        assertFalse(VerticalMargin.countsFromEdge(VerticalMargin.BANDS_V1))
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
