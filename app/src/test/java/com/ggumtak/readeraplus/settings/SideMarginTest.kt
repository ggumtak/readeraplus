package com.ggumtak.readeraplus.settings

import org.junit.Assert.*
import org.junit.Test

class SideMarginTest {
    @Test fun scaleAroundMaruViewersTwentyDp() {
        assertEquals(20, ReaderSettings().marginLeftDp)
        assertEquals(20, ReaderSettings().marginRightDp)
        assertEquals(0, SideMargin.toUi(20))
        assertEquals(20, SideMargin.toUi(40))
        // The stepper never goes below 0 dp and still reaches the 80 dp of the 40 dp scale.
        assertEquals(0, SideMargin.toDp(SideMargin.UI_MIN))
        assertEquals(80, SideMargin.toDp(SideMargin.UI_MAX))
        assertEquals(0, SideMargin.toDp(-40))
        assertEquals(0, (SideMargin.UI_MAX - SideMargin.UI_MIN) % SideMargin.UI_STEP)
        assertEquals("+2", SideMargin.label(2))
        assertEquals("−2", SideMargin.label(-2))
        assertEquals("0", SideMargin.label(0))
        // Top and bottom keep their own 40 dp "0".
        assertEquals(40, ReaderSettings().marginTopDp)
        assertEquals(0, VerticalMargin.toUi(40))
    }

    @Test fun onlyAnOlderBuildsUntouchedDefaultMoves() {
        // ≤ R2: 18/18 without the marker.
        assertTrue(SideMargin.isLegacyDefault(null, 18, 18))
        assertFalse(SideMargin.isLegacyDefault(null, 17, 18))
        // R3 until 2026-10-05: the marker holds 40, its "0".
        assertTrue(SideMargin.isLegacyDefault(SideMargin.R3_ZERO_DP, 40, 40))
        assertFalse(SideMargin.isLegacyDefault(SideMargin.R3_ZERO_DP, 18, 18))
        assertFalse(SideMargin.isLegacyDefault(SideMargin.R3_ZERO_DP, 40, 30))
        assertFalse(SideMargin.isLegacyDefault(SideMargin.R3_ZERO_DP, 30, 30))
        // Before the marker 40 was a choice (the default was 18).
        assertFalse(SideMargin.isLegacyDefault(null, 40, 40))
        // Saved on this scale: every value is the user's, 40 and 18 too.
        assertFalse(SideMargin.isLegacyDefault(SideMargin.ZERO_DP, 40, 40))
        assertFalse(SideMargin.isLegacyDefault(SideMargin.ZERO_DP, 18, 18))
        assertFalse(SideMargin.isLegacyDefault(-1, 40, 40))
    }
}
