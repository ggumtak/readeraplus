package com.ggumtak.readeraplus.reader

import org.junit.Assert.*
import org.junit.Test

class InsetsGateTest {
    private val hidden = intArrayOf(0, 0, 0, 0)
    private val bars = intArrayOf(0, 48, 0, 96)

    @Test fun firstInsetsApplyBeforeFocus() {
        val gate = InsetsGate()
        assertArrayEquals(hidden, gate.offer(hidden, settled = false, forced = false))
        assertFalse(gate.hasPending)
        assertNull(gate.offer(hidden, settled = true, forced = false))
    }

    @Test fun dialogBarsDisappearWithoutResizingThePage() {
        val gate = InsetsGate()
        gate.offer(hidden, false, false)
        assertNull(gate.offer(bars, false, false))
        assertTrue(gate.hasPending)
        assertNull(gate.offer(hidden, false, false))
        assertFalse(gate.hasPending)
        assertNull(gate.settle(hidden))
    }

    @Test fun settleReadsCurrentInsetsRatherThanReplayingTransientBars() {
        val gate = InsetsGate()
        gate.offer(hidden, false, false)
        gate.offer(bars, false, false)
        assertNull(gate.settle(hidden))
        gate.offer(bars, false, false)
        assertArrayEquals(bars, gate.settle(bars))
        assertNull(gate.settle(bars))
    }

    @Test fun configurationAndFullscreenChangesApplyImmediately() {
        val gate = InsetsGate()
        gate.offer(hidden, false, false)
        assertArrayEquals(bars, gate.offer(bars, settled = false, forced = true))
        assertArrayEquals(hidden, gate.offer(hidden, settled = true, forced = false))
        assertFalse(gate.hasPending)
    }

    @Test fun callerMutationDoesNotChangeTheAppliedValue() {
        val gate = InsetsGate()
        val supplied = bars.copyOf()
        gate.offer(supplied, false, false)
        supplied[1] = 900
        assertNull(gate.offer(bars, true, false))
    }
}
