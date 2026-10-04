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

 @Test fun pageDragOnlyDecidesOnceAndRespectsAxis() { val d=PageDrag(20f);d.down(0f,0f);assertEquals(0,d.up(2f,3f));d.down(0f,100f);assertEquals(1,d.up(0f,50f));d.down(0f,0f);assertEquals(-1,d.up(0f,50f));d.down(0f,0f);assertTrue(d.move(60f,45f));assertEquals(0,d.up(60f,45f));d.down(0f,0f);d.move(0f,50f);d.cancel();assertEquals(0,d.up(0f,50f));val g=PageDrag(20f,true);g.down(100f,0f);assertEquals(1,g.up(0f,0f)) }

    @Test fun aSidewaysDragOnAVerticalListIsTakenAndPagesNothing() {
        val d = PageDrag(20f)
        d.down(100f, 100f)
        assertFalse(d.move(115f, 104f)) // within the slop: still a tap on the row
        assertTrue(d.move(160f, 110f)) // past it sideways: the list's (the row gets a cancel, never a tap)
        assertTrue(d.dragging)
        assertEquals(0, d.up(220f, 110f))
        d.down(100f, 100f)
        assertEquals(0, d.up(20f, 95f)) // the other way, released without a move first
        // Vertical drags page as before; a tie is vertical.
        d.down(100f, 300f)
        assertEquals(1, d.up(110f, 200f))
        d.down(100f, 100f)
        assertEquals(-1, d.up(150f, 150f))
    }

    @Test fun aVerticalListPagesByWhereTheFingerLifts() {
        val d = PageDrag(20f)
        // A swipe that starts sideways and turns down or up still pages (the first move past the slop fixes nothing).
        d.down(100f, 100f)
        assertTrue(d.move(140f, 105f))
        assertEquals(-1, d.up(140f, 400f))
        d.down(100f, 100f)
        assertTrue(d.move(145f, 110f))
        assertEquals(1, d.up(160f, -200f))
        // A vertical start that ends mostly sideways, or back within the slop of the start, pages nothing.
        d.down(100f, 100f)
        assertTrue(d.move(100f, 140f))
        assertEquals(0, d.up(300f, 160f))
        d.down(100f, 100f)
        assertTrue(d.move(100f, 200f))
        assertEquals(0, d.up(105f, 110f))
    }

    @Test fun aGridStillPagesOnEitherAxis() {
        val g = PageDrag(20f, axisBoth = true)
        g.down(100f, 100f)
        assertTrue(g.move(160f, 110f))
        assertEquals(-1, g.up(220f, 110f)) // finger to the right: the page before
        g.down(220f, 100f)
        assertEquals(1, g.up(100f, 110f))
        g.down(100f, 100f)
        assertTrue(g.move(140f, 105f))
        assertEquals(-1, g.up(140f, -200f)) // the first axis wins here too: right, then far up, is the page before
    }

 @Test fun fittedRowsAndPagingChoices() { assertEquals(4 to 298,PageFit.fit(1192,298));assertEquals(1 to 100,PageFit.fit(100,149));for(e in listOf(null,false,true)) { assertEquals(e==true,ListPaging.paged(0,e));assertTrue(ListPaging.paged(1,e));assertFalse(ListPaging.paged(2,e)) } }

}
