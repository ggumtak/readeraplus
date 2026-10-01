package com.ggumtak.readeraplus.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollEdgeTest {

    // The Comet: 720 px wide at 2 px per dp → edge strip 48 px, kept band 192 px.
    private val width = 720
    private val edge = 48
    private val claim = 192

    private fun x(at: Float) = FastScrollEdge.downX(at, width, edge, claim)

    @Test
    fun edgeStripStillGrabsTheScroller() {
        assertEquals(719f, x(719f))
        assertEquals(672f, x(672f)) // first pixel of the strip
    }

    @Test
    fun bandLeftOfTheStripIsMovedOutOfTheScrollersReach() {
        // The compact row's ⋮ (centre 40dp from the edge) and the card's ⋮ (about 34dp) land here.
        assertEquals(0f, x(640f))
        assertEquals(0f, x(651f))
        assertEquals(0f, x(671f)) // last pixel before the strip
        assertEquals(0f, x(528f)) // first pixel of the band
    }

    @Test
    fun touchesFurtherLeftAreUntouched() {
        assertEquals(527f, x(527f))
        assertEquals(100f, x(100f))
        assertEquals(0f, x(0f))
    }

    @Test
    fun narrowListKeepsItsStrip() {
        // A list narrower than the band: everything left of the strip is moved, the strip is not.
        assertEquals(0f, FastScrollEdge.downX(10f, 100, edge, claim))
        assertEquals(60f, FastScrollEdge.downX(60f, 100, edge, claim))
    }
}
