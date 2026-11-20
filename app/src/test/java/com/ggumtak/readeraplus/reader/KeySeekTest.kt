package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySeekTest {

    @Test
    fun firstStepStartsABurstFromTheValueBeforeIt() {
        val k = KeySeek()
        assertFalse(k.pending)
        assertTrue(k.step(41))
        assertTrue(k.pending)
        assertEquals(41, k.from)
    }

    @Test
    fun heldKeyStepsStayOneBurstFromTheFirstValue() {
        val k = KeySeek()
        assertTrue(k.step(41))
        // A held D-pad: every later step restarts the wait but starts nothing new (one onSeekStart / onSeekPreview).
        assertFalse(k.step(42))
        assertFalse(k.step(43))
        assertEquals(41, k.from)
        // One jump when the steps settle, then nothing more.
        assertTrue(k.settle(touching = false))
        assertFalse(k.pending)
        assertFalse(k.settle(touching = false))
    }

    @Test
    fun aBurstAfterASettleStartsAgainFromTheNewValue() {
        val k = KeySeek()
        k.step(10)
        k.settle(touching = false)
        assertTrue(k.step(11))
        assertEquals(11, k.from)
    }

    @Test
    fun settleWhileADragTouchesTheBarCommitsNothing() {
        val k = KeySeek()
        k.step(10)
        assertFalse(k.settle(touching = true))
        assertFalse(k.pending)
    }

    @Test
    fun cancelledBurstCommitsNothing() {
        val k = KeySeek()
        k.step(10)
        k.cancel()
        assertFalse(k.pending)
        assertFalse(k.settle(touching = false))
        // The next step starts a fresh burst (onSeekStart again).
        assertTrue(k.step(12))
        assertEquals(12, k.from)
    }

    @Test
    fun aTouchTakingOverAPendingBurstStartsFromTheBurstsFirstValue() {
        val k = KeySeek()
        k.step(41)
        k.step(42)
        // The keys moved the bar to 43; a tap on the thumb there starts its seek from 41, so lifting at 43 still jumps.
        assertEquals(41, k.takeOver(43))
        assertFalse(k.pending)
        assertFalse(k.settle(touching = false))
    }

    @Test
    fun aTouchWithoutAPendingBurstStartsFromTheBarsValue() {
        val k = KeySeek()
        assertEquals(43, k.takeOver(43))
        // A settled burst is not taken over again.
        k.step(10)
        k.settle(touching = false)
        assertEquals(11, k.takeOver(11))
        assertFalse(k.pending)
    }

    @Test
    fun settleWithoutAStepCommitsNothing() {
        assertFalse(KeySeek().settle(touching = false))
    }
}
