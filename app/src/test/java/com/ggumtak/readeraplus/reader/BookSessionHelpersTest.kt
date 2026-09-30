package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BookSession's pure helpers: [Once] (one episode parse per session, T1-1 / T1-5) and [CountSaves] (when partial page
 * counts are written, A2).
 */
class BookSessionHelpersTest {

    @Test
    fun countsAreSavedWhenTheyAddSomethingAndTheLayoutSettled() {
        val settled = BookSession.SAVE_SETTLE_MS
        assertTrue(CountSaves.due(known = 30, savedKnown = 0, complete = false, ageMs = settled))
        // Nothing new since the load / last save.
        assertFalse(CountSaves.due(known = 30, savedKnown = 30, complete = false, ageMs = settled))
        assertFalse(CountSaves.due(known = 30, savedKnown = 30, complete = true, ageMs = settled))
        // A layout tried for a few seconds (settings popup) never writes a partial array...
        assertFalse(CountSaves.due(known = 30, savedKnown = 0, complete = false, ageMs = settled - 1))
        // ... but complete counts are written at once, as before.
        assertTrue(CountSaves.due(known = 30, savedKnown = 0, complete = true, ageMs = 0))
        assertEquals(30_000L, settled)
        assertEquals(25, BookSession.SAVE_EVERY)
    }

    @Test
    fun failedSectionsAreNeverCached() {
        val arr = intArrayOf(3, -1, 5, 2)
        assertEquals(2, CountSaves.maskFailed(arr, listOf(2, 1, 9)))
        assertEquals(listOf(3, -1, -1, 2), arr.toList())
        assertEquals(0, CountSaves.maskFailed(intArrayOf(4), listOf(0)))
        assertEquals(1, CountSaves.maskFailed(intArrayOf(4), emptyList()))
    }

    @Test
    fun firstCallerStartsTheWorkAndEveryoneGetsTheOneResult() {
        val once = Once<String>()
        var starts = 0
        val got = ArrayList<String?>()
        once.get({ got.add("a:$it") }) { starts++ }
        once.get({ got.add("b:$it") }) { starts++ }
        assertEquals(1, starts)
        assertTrue(got.isEmpty())
        assertFalse(once.isDone)
        once.complete("x")
        assertEquals(listOf("a:x", "b:x"), got)
        // Later callers get it at once, without new work.
        once.get({ got.add("c:$it") }) { starts++ }
        assertEquals(listOf("a:x", "b:x", "c:x"), got)
        assertEquals(1, starts)
    }

    @Test
    fun onlyTheFirstResultCounts() {
        val once = Once<String>()
        val got = ArrayList<String?>()
        once.get({ got.add(it) }) {}
        once.complete(null) // e.g. the session closed while parsing
        once.complete("late")
        once.get({ got.add(it) }) {}
        assertEquals(listOf(null, null), got)
    }

    @Test
    fun aResultReadyInsideStartReachesTheFirstCaller() {
        val once = Once<String>()
        val got = ArrayList<String?>()
        once.get({ got.add(it) }) { once.complete(null) } // empty TOC: nothing to parse
        assertEquals(listOf<String?>(null), got)
        assertTrue(once.isDone)
    }

    @Test
    fun completedBeforeAnyoneAskedAndReentrantCallers() {
        val once = Once<String>()
        once.complete(null) // closed before anyone asked
        var started = false
        var got: String? = "unset"
        once.get({ got = it }) { started = true }
        assertFalse(started)
        assertEquals(null, got)

        val again = Once<String>()
        val order = ArrayList<String>()
        again.get({ v -> order.add("outer:$v"); again.get({ order.add("inner:$it") }) {} }) {}
        again.complete("e")
        assertEquals(listOf("outer:e", "inner:e"), order)
    }
}
