package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeGuardTest {
    private val slop = 24f

    @Test fun aSwipeUpOrDownIsTheSystemsNotTheBars() {
        assertEquals(SwipeGuard.DROP, SwipeGuard.decide(dx = 3f, dy = -40f, slop = slop))   // home / recents
        assertEquals(SwipeGuard.DROP, SwipeGuard.decide(dx = -10f, dy = 30f, slop = slop))  // notifications
        // Diagonal but more vertical: still the system's.
        assertEquals(SwipeGuard.DROP, SwipeGuard.decide(dx = 30f, dy = -31f, slop = slop))
    }

    @Test fun aSidewaysMoveIsADrag() {
        assertEquals(SwipeGuard.DRAG, SwipeGuard.decide(dx = 30f, dy = 5f, slop = slop))
        assertEquals(SwipeGuard.DRAG, SwipeGuard.decide(dx = -60f, dy = -20f, slop = slop))
    }

    @Test fun withinTheSlopItWaits() {
        assertEquals(SwipeGuard.WAIT, SwipeGuard.decide(dx = 0f, dy = 0f, slop = slop))
        assertEquals(SwipeGuard.WAIT, SwipeGuard.decide(dx = 20f, dy = -20f, slop = slop))
    }
}
