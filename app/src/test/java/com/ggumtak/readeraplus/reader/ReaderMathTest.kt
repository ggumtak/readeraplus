package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMathTest {

    @Test
    fun cadenceOff() {
        val c = EinkCadence(0, false)
        repeat(50) { assertFalse(c.onTurn(it % 5 == 0)) }
    }

    @Test
    fun cadenceEveryN() {
        val c = EinkCadence(3, false)
        val fired = (1..9).map { c.onTurn(false) }
        assertEquals(listOf(false, false, true, false, false, true, false, false, true), fired)
        c.onTurn(false)
        c.reset()
        assertFalse(c.onTurn(false))
        assertFalse(c.onTurn(false))
        assertTrue(c.onTurn(false))
    }

    @Test
    fun cadenceOnChapterRestartsCount() {
        val c = EinkCadence(3, true)
        assertFalse(c.onTurn(false))
        assertFalse(c.onTurn(false))
        assertTrue(c.onTurn(true)) // chapter start flashes and restarts the count
        assertFalse(c.onTurn(false))
        assertFalse(c.onTurn(false))
        assertTrue(c.onTurn(false))
        val chapterOnly = EinkCadence(0, true)
        assertFalse(chapterOnly.onTurn(false))
        assertTrue(chapterOnly.onTurn(true))
    }

    @Test
    fun swipeClassification() {
        val min = 120f
        assertEquals(SwipeDir.NEXT, Gestures.classify(-200f, 30f, min, true, false))
        assertEquals(SwipeDir.PREV, Gestures.classify(200f, -30f, min, true, false))
        assertEquals(SwipeDir.NONE, Gestures.classify(-100f, 0f, min, true, false))
        assertEquals(SwipeDir.NONE, Gestures.classify(-200f, 0f, min, false, false))
        // vertical only when enabled; up = next
        assertEquals(SwipeDir.NONE, Gestures.classify(10f, -300f, min, true, false))
        assertEquals(SwipeDir.NEXT, Gestures.classify(10f, -300f, min, true, true))
        assertEquals(SwipeDir.PREV, Gestures.classify(10f, 300f, min, false, true))
        // diagonal: the dominant axis decides
        assertEquals(SwipeDir.NEXT, Gestures.classify(-250f, 200f, min, true, true))
    }

    @Test
    fun brightnessDrag() {
        assertEquals(0.5f, Gestures.brightness(0.5f, 0f, 1000), 1e-6f)
        assertEquals(1f, Gestures.brightness(0.5f, -300f, 1000), 1e-6f)
        assertEquals(0.4f, Gestures.brightness(0.5f, 60f, 1000), 1e-6f)
        assertEquals(0f, Gestures.brightness(0.5f, 900f, 1000), 1e-6f)
        assertEquals(0.3f, Gestures.brightness(0.3f, 10f, 0), 1e-6f)
        assertTrue(Gestures.inBrightnessStrip(50f, 720))
        assertFalse(Gestures.inBrightnessStrip(80f, 720))
    }

    @Test
    fun gestureEnd() {
        val slop = 40f
        val min = 120f
        // still finger and a slightly sloppy tap are both taps
        assertEquals(GestureEnd.TAP, Gestures.end(0f, 0f, 0f, slop, min, true, false))
        assertEquals(GestureEnd.TAP, Gestures.end(25f, -10f, 30f, slop, min, true, false))
        // finger wandered off and came back: not a tap
        assertEquals(GestureEnd.NONE, Gestures.end(5f, 5f, 80f, slop, min, true, false))
        // short drag that is not a swipe: nothing
        assertEquals(GestureEnd.NONE, Gestures.end(-90f, 0f, 90f, slop, min, true, false))
        // swipes win
        assertEquals(GestureEnd.NEXT, Gestures.end(-200f, 10f, 200f, slop, min, true, false))
        assertEquals(GestureEnd.PREV, Gestures.end(200f, 10f, 200f, slop, min, true, false))
        // swipe turning disabled: a long drag is nothing, never a tap
        assertEquals(GestureEnd.NONE, Gestures.end(-200f, 10f, 200f, slop, min, false, false))
        assertEquals(GestureEnd.NEXT, Gestures.end(0f, -300f, 300f, slop, min, false, true))
    }

    @Test
    fun throttleLetsOneUpdateThroughPerInterval() {
        val t = Throttle(250)
        assertTrue(t.tryAcquire(1000)) // the first value of a drag shows at once
        assertEquals(250L, t.waitMs(1000))
        assertFalse(t.tryAcquire(1010))
        assertFalse(t.tryAcquire(1249))
        assertEquals(1L, t.waitMs(1249))
        assertTrue(t.tryAcquire(1250))
        assertFalse(t.tryAcquire(1300))
        assertTrue(t.tryAcquire(1600))
        assertEquals(0L, t.waitMs(2000))
        // 4 Hz: a 1 s drag with an event every 16 ms updates the label 4 times.
        val d = Throttle(Throttle.LABEL_MS)
        var shown = 0
        for (now in 10_000L until 11_000L step 16) if (d.tryAcquire(now)) shown++
        assertEquals(4, shown)
    }

    @Test
    fun throttleMarkAndReset() {
        val t = Throttle(250)
        assertTrue(t.tryAcquire(0))
        // A held-back value shown late counts as the last update.
        t.mark(200)
        assertFalse(t.tryAcquire(300))
        assertEquals(150L, t.waitMs(300))
        assertTrue(t.tryAcquire(450))
        // A new drag starts fresh.
        t.reset()
        assertTrue(t.tryAcquire(460))
        assertEquals(0L, Throttle(250).waitMs(5))
        // A clock that went backwards (should not happen with uptime) never blocks for good.
        val b = Throttle(250)
        assertTrue(b.tryAcquire(1000))
        assertTrue(b.tryAcquire(500))
    }
}
