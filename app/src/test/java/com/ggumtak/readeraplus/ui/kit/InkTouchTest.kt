package com.ggumtak.readeraplus.ui.kit

import com.ggumtak.readeraplus.ui.library.LibraryText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTouchTest {
    @Test fun thePlatformZoneOnlyKeepsItsTwelveDpStrip() {
        for (density in listOf(2f, 2.625f, 2.8125f)) {
            val width = (360 * density).toInt()
            val zone = width - 56 * density
            val grab = width - 12 * density
            assertEquals(zone - 2, FastScrollGuard.shieldedX(zone - 2, width, density))
            assertEquals(zone - 1, FastScrollGuard.shieldedX(zone, width, density))
            assertEquals(zone - 1, FastScrollGuard.shieldedX(grab - 1, width, density))
            assertEquals(grab, FastScrollGuard.shieldedX(grab, width, density))
            assertEquals(width - 1f, FastScrollGuard.shieldedX(width - 1f, width, density))
        }
    }

    @Test fun insideScrollbarsMoveTheGuardWithTheirEndInset() {
        for (density in listOf(2f, 2.625f, 2.8125f)) {
            val width = (360 * density).toInt()
            val inset = (8 * density).toInt()
            val guarded = FastScrollGuard.shieldedX(width - inset - 20 * density, width, density, inset)
            assertEquals(width - inset - 56 * density - 1, guarded)
            assertTrue(guarded < width - inset - 48 * density)
        }
    }

    @Test fun aNotYetMeasuredViewDoesNotMoveTouches() {
        assertEquals(30f, FastScrollGuard.shieldedX(30f, 0, 2f))
    }

    @Test fun tapsKeepTheReadersToleranceAndVerticalDragsReturnToTheList() {
        assertEquals(40f, TapSlop.px(16, 2f))
        assertEquals(80f, TapSlop.px(40, 2f))
        assertFalse(TapSlop.releaseToList(0f, 39f, 40f))
        assertTrue(TapSlop.releaseToList(0f, 41f, 40f))
        assertFalse(TapSlop.releaseToList(50f, 41f, 40f))
        assertTrue(TapSlop.releaseToList(-3f, -41f, 40f))
    }

    @Test fun shiftedGridTouchesStayInsideTheLastColumn() {
        for (dpWidth in listOf(360, 411, 720)) {
            val density = 2f
            val width = (dpWidth * density).toInt()
            val columns = LibraryText.gridColumns(width, density)
            val cell = (width - 20 * density - (columns - 1) * 6 * density) / columns
            val lastLeft = 8 * density + (columns - 1) * (cell + 6 * density)
            assertTrue("width=$dpWidth", lastLeft < width - 57 * density)
        }
    }
}
