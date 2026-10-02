package com.ggumtak.readeraplus.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalMarginTest {
    @Test
    fun scaleMatchesTheSideMargins() {
        assertEquals(0, VerticalMargin.toUi(40))
        assertEquals(-24, VerticalMargin.toUi(16))
        assertEquals(80, VerticalMargin.toDp(VerticalMargin.UI_MAX))
        assertEquals(0, VerticalMargin.toDp(VerticalMargin.UI_MIN))
        assertEquals(0, VerticalMargin.toDp(-60))
        assertEquals("0", VerticalMargin.label(0))
        assertEquals("+4", VerticalMargin.label(4))
        assertEquals("−10", VerticalMargin.label(-10))
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
