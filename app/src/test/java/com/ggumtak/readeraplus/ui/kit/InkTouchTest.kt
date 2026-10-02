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

 @Test fun pageDragOnlyDecidesOnceAndRespectsAxis() { val d=PageDrag(20f);d.down(0f,0f);assertEquals(0,d.up(2f,3f));d.down(0f,100f);assertEquals(1,d.up(0f,50f));d.down(0f,0f);assertEquals(-1,d.up(0f,50f));d.down(0f,0f);assertFalse(d.move(60f,45f));assertEquals(0,d.up(60f,45f));d.down(0f,0f);d.move(0f,50f);d.cancel();assertEquals(0,d.up(0f,50f));val g=PageDrag(20f,true);g.down(100f,0f);assertEquals(1,g.up(0f,0f)) }
 @Test fun fittedRowsAndPagingChoices() { assertEquals(4 to 298,PageFit.fit(1192,298));assertEquals(1 to 100,PageFit.fit(100,149));for(e in listOf(null,false,true)) { assertEquals(e==true,ListPaging.paged(0,e));assertTrue(ListPaging.paged(1,e));assertFalse(ListPaging.paged(2,e)) } }

}
