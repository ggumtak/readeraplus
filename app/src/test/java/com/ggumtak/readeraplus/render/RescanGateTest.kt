package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RescanGateTest {

    @Test
    fun listsOnceUntilInvalidated() {
        val gate = RescanGate()
        val lists = AtomicInteger()
        var committed = -1
        assertFalse(gate.isCurrent)
        gate.ensure({ lists.incrementAndGet() }) { committed = it }
        gate.ensure({ lists.incrementAndGet() }) { committed = it }
        assertEquals(1, lists.get())
        assertEquals(1, committed)
        assertTrue(gate.isCurrent)
        gate.invalidate()
        assertFalse(gate.isCurrent)
        gate.ensure({ lists.incrementAndGet() }) { committed = it }
        assertEquals(2, committed)
    }

    /**
     * Regression: a listing that started before an import (so it misses the new font file) must not be committed
     * after the import invalidated the state — before the fix the stale listing won and the imported font
     * resolved to the default font until the next manual refresh.
     */
    @Test
    fun listingInterruptedByInvalidationIsRedone() {
        val gate = RescanGate()
        val folder = ArrayList(listOf("a.ttf"))
        var committed: List<String> = emptyList()
        var calls = 0
        gate.ensure({
            val snapshot = ArrayList(folder)
            calls++
            if (calls == 1) {
                // An import lands while this listing is in flight.
                folder.add("new.ttf")
                gate.invalidate()
            }
            snapshot
        }) { committed = it }
        assertEquals(2, calls)
        assertEquals(listOf("a.ttf", "new.ttf"), committed)
        assertTrue(gate.isCurrent)
    }

    @Test
    fun staleConcurrentScanCannotOverwriteFreshOne() {
        val gate = RescanGate()
        val folder = java.util.Collections.synchronizedList(ArrayList(listOf("a.ttf")))
        val committed = AtomicReference<List<String>>(emptyList())
        val slowStarted = CountDownLatch(1)
        val slowMayFinish = CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val slow = Thread {
            gate.ensure({
                val snap = ArrayList(folder)
                if (first.getAndSet(false)) {
                    slowStarted.countDown()
                    slowMayFinish.await(5, TimeUnit.SECONDS)
                }
                snap
            }) { committed.set(it) }
        }
        slow.start()
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS))
        // Import on another thread: add the file, invalidate, rescan (as FontManager.importFont does).
        folder.add("new.ttf")
        gate.invalidate()
        gate.ensure({ ArrayList(folder) }) { committed.set(it) }
        assertEquals(listOf("a.ttf", "new.ttf"), committed.get())
        slowMayFinish.countDown()
        slow.join(5000)
        assertEquals(listOf("a.ttf", "new.ttf"), committed.get())
        assertTrue(gate.isCurrent)
    }
}
