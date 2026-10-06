package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A selection over several pages: what shows on a page, the edge zones and the dwell state machine. */
class SelectionEdgeTest {

    // ------------------------------------------------------------------ range ∩ page

    @Test
    fun spanInsideOnePage() {
        assertTrue(SelectionSpan.visible(120, 150, 100, 200))
        assertEquals(120, SelectionSpan.start(120, 100))
        assertEquals(150, SelectionSpan.end(150, 200))
        assertTrue(SelectionSpan.startShown(120, 150, 100, 200))
        assertTrue(SelectionSpan.endShown(120, 150, 100, 200))
    }

    @Test
    fun spanStartingOnAnEarlierPageHidesTheStartHandle() {
        // Selected 50..150, page 100..200: the visible part is 100..150, only the end handle is here.
        assertTrue(SelectionSpan.visible(50, 150, 100, 200))
        assertEquals(100, SelectionSpan.start(50, 100))
        assertEquals(150, SelectionSpan.end(150, 200))
        assertFalse(SelectionSpan.startShown(50, 150, 100, 200))
        assertTrue(SelectionSpan.endShown(50, 150, 100, 200))
    }

    @Test
    fun spanEndingOnALaterPageHidesTheEndHandle() {
        assertTrue(SelectionSpan.visible(150, 250, 100, 200))
        assertEquals(200, SelectionSpan.end(250, 200))
        assertTrue(SelectionSpan.startShown(150, 250, 100, 200))
        assertFalse(SelectionSpan.endShown(150, 250, 100, 200))
    }

    @Test
    fun spanAcrossTheWholePageShowsNoHandle() {
        assertTrue(SelectionSpan.visible(50, 250, 100, 200))
        assertFalse(SelectionSpan.startShown(50, 250, 100, 200))
        assertFalse(SelectionSpan.endShown(50, 250, 100, 200))
    }

    @Test
    fun spanOffThePageIsNotVisible() {
        assertFalse(SelectionSpan.visible(10, 100, 100, 200))
        assertFalse(SelectionSpan.visible(200, 260, 100, 200))
        assertFalse(SelectionSpan.startShown(200, 260, 100, 200))
        assertFalse(SelectionSpan.endShown(10, 100, 100, 200))
        // An empty page shows nothing.
        assertFalse(SelectionSpan.visible(0, 10, 5, 5))
    }

    @Test
    fun spanTouchingPageBoundaries() {
        // Ends exactly at the page's end: that end handle is on this page.
        assertTrue(SelectionSpan.endShown(150, 200, 100, 200))
        // Starts at the page's first char: that start handle is on this page.
        assertTrue(SelectionSpan.startShown(100, 150, 100, 200))
        // Ends exactly at the page's start: nothing of it is here.
        assertFalse(SelectionSpan.visible(50, 100, 100, 200))
    }

    @Test
    fun turnStopsAtTheSectionsEnds() {
        assertTrue(SelectionSpan.canTurn(true, 100, 200, 500))
        assertFalse(SelectionSpan.canTurn(true, 400, 500, 500))
        assertTrue(SelectionSpan.canTurn(false, 100, 200, 500))
        assertFalse(SelectionSpan.canTurn(false, 0, 100, 500))
    }

    // ------------------------------------------------------------------ edge zones

    @Test
    fun zonesAtTheTextAreasEdges() {
        // Text area 100..900, zone 48.
        assertEquals(Zone.BOTTOM, EdgeZone.of(860f, 100f, 900f, 48f, allowNext = true, allowPrev = true))
        assertEquals(Zone.BOTTOM, EdgeZone.of(852f, 100f, 900f, 48f, allowNext = true, allowPrev = true))
        assertEquals(Zone.NONE, EdgeZone.of(851f, 100f, 900f, 48f, allowNext = true, allowPrev = true))
        assertEquals(Zone.TOP, EdgeZone.of(140f, 100f, 900f, 48f, allowNext = true, allowPrev = true))
        assertEquals(Zone.TOP, EdgeZone.of(148f, 100f, 900f, 48f, allowNext = true, allowPrev = true))
        assertEquals(Zone.NONE, EdgeZone.of(149f, 100f, 900f, 48f, allowNext = true, allowPrev = true))
        assertEquals(Zone.NONE, EdgeZone.of(500f, 100f, 900f, 48f, allowNext = true, allowPrev = true))
    }

    @Test
    fun aPointInTheMarginCountsAsTheZoneBeside() {
        assertEquals(Zone.BOTTOM, EdgeZone.of(960f, 100f, 900f, 48f, allowNext = true, allowPrev = true))
        assertEquals(Zone.TOP, EdgeZone.of(20f, 100f, 900f, 48f, allowNext = true, allowPrev = true))
    }

    @Test
    fun aHandleTurnsOnlyItsOwnWay() {
        // The end handle: forward only. The start handle: back only.
        assertEquals(Zone.NONE, EdgeZone.of(120f, 100f, 900f, 48f, allowNext = true, allowPrev = false))
        assertEquals(Zone.BOTTOM, EdgeZone.of(880f, 100f, 900f, 48f, allowNext = true, allowPrev = false))
        assertEquals(Zone.NONE, EdgeZone.of(880f, 100f, 900f, 48f, allowNext = false, allowPrev = true))
        assertEquals(Zone.TOP, EdgeZone.of(120f, 100f, 900f, 48f, allowNext = false, allowPrev = true))
    }

    @Test
    fun aShortAreaKeepsAMiddle() {
        // 90 px tall: zones shrink to 30 each.
        assertEquals(Zone.NONE, EdgeZone.of(145f, 100f, 190f, 48f, allowNext = true, allowPrev = true))
        assertEquals(Zone.BOTTOM, EdgeZone.of(165f, 100f, 190f, 48f, allowNext = true, allowPrev = true))
        assertEquals(Zone.TOP, EdgeZone.of(125f, 100f, 190f, 48f, allowNext = true, allowPrev = true))
        assertEquals(Zone.NONE, EdgeZone.of(150f, 200f, 100f, 48f, allowNext = true, allowPrev = true))
    }

    // ------------------------------------------------------------------ dwell state machine

    @Test
    fun enteringAZoneArmsAndHoldingKeeps() {
        val d = EdgeDwell()
        assertEquals(EdgeDwell.Action.NONE, d.update(Zone.NONE))
        assertEquals(EdgeDwell.Action.ARM, d.update(Zone.BOTTOM))
        assertTrue(d.armed)
        assertEquals(EdgeDwell.Action.KEEP, d.update(Zone.BOTTOM))
        assertEquals(EdgeDwell.Action.KEEP, d.update(Zone.BOTTOM))
    }

    @Test
    fun leavingTheZoneCancels() {
        val d = EdgeDwell()
        d.update(Zone.TOP)
        assertEquals(EdgeDwell.Action.CANCEL, d.update(Zone.NONE))
        assertFalse(d.armed)
        assertNull(d.fire())
    }

    @Test
    fun switchingZonesRestartsTheTimer() {
        val d = EdgeDwell()
        d.update(Zone.TOP)
        assertEquals(EdgeDwell.Action.ARM, d.update(Zone.BOTTOM))
        assertEquals(Zone.BOTTOM, d.fire())
    }

    @Test
    fun firingTurnsOnceAndWaitsForThePage() {
        val d = EdgeDwell()
        d.update(Zone.BOTTOM)
        assertEquals(Zone.BOTTOM, d.fire())
        assertTrue(d.turning)
        // A second timer, or moves while the page is on its way, do nothing.
        assertNull(d.fire())
        assertEquals(EdgeDwell.Action.NONE, d.update(Zone.BOTTOM))
        assertEquals(EdgeDwell.Action.NONE, d.update(Zone.NONE))
        assertTrue(d.turning)
    }

    @Test
    fun rearmsOnlyAfterThePageShows() {
        val d = EdgeDwell()
        d.update(Zone.BOTTOM)
        d.fire()
        assertTrue(d.landed())
        assertFalse(d.turning)
        // The finger is still in the zone on the new page: one more dwell.
        assertEquals(EdgeDwell.Action.ARM, d.update(Zone.BOTTOM))
        assertEquals(Zone.BOTTOM, d.fire())
    }

    @Test
    fun landedWithoutATurnIsNothing() {
        val d = EdgeDwell()
        assertFalse(d.landed())
        d.update(Zone.TOP)
        assertFalse(d.landed())
        assertTrue(d.armed)
    }

    @Test
    fun cancelFromAnyState() {
        val d = EdgeDwell()
        d.update(Zone.TOP)
        d.cancel()
        assertNull(d.fire())
        d.update(Zone.TOP)
        d.fire()
        d.cancel()
        assertFalse(d.turning)
        assertEquals(EdgeDwell.Action.ARM, d.update(Zone.TOP))
    }
}
