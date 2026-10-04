package com.ggumtak.readeraplus.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N §9.7: the windowed list (pages of 50, LRU of 6, prefetch ±25, fetch-then-move) and the §9.4 save rule. */
class NotesWindowTest {
    private fun window(count: Int) = NotesWindow<String>().apply { reset(count) }

    @Test
    fun pagesAndIndexes() {
        val w = window(128)
        assertEquals(3, w.pageCount())
        assertEquals(0, w.pageOf(49))
        assertEquals(1, w.pageOf(50))
        assertEquals(27, w.indexIn(127))
        assertEquals(0, window(0).pageCount())
        assertEquals(1, window(50).pageCount())
    }

    @Test
    fun requestOncePerPageAndInRangeOnly() {
        val w = window(128)
        assertTrue(w.request(0))
        assertFalse(w.request(0)) // in flight
        assertFalse(w.request(3)) // out of range
        assertFalse(w.request(-1))
        assertEquals(NotesWindow.Put.STORED, w.put(w.token, 0, "p0"))
        assertFalse(w.request(0)) // loaded
        assertEquals("p0", w.pageFor(10))
        assertNull(w.pageFor(60))
        assertNull(w.pageFor(500))
    }

    @Test
    fun aFailedLoadCanBeRequestedAgain() {
        val w = window(100)
        assertTrue(w.request(1))
        assertEquals(NotesWindow.Put.STALE, w.put(w.token, 1, null))
        assertTrue(w.request(1))
    }

    @Test
    fun resetDropsPagesAndLoadsInFlight() {
        val w = window(300)
        val old = w.token
        w.request(0)
        w.put(old, 0, "p0")
        w.request(1)
        w.reset(300)
        assertFalse(w.isLoaded(0))
        assertFalse(w.isPending(1))
        assertEquals(NotesWindow.Put.STALE, w.put(old, 1, "late"))
        assertFalse(w.isLoaded(1))
    }

    @Test
    fun keepsAtMostSixPagesLeastRecentlyUsedFirst() {
        val w = window(1000)
        for (p in 0 until 6) { w.request(p); w.put(w.token, p, "p$p") }
        w.pageFor(0) // touch page 0: page 1 is now the eldest
        w.request(6); w.put(w.token, 6, "p6")
        assertEquals(setOf(0, 2, 3, 4, 5, 6), w.loadedPages())
    }

    @Test
    fun prefetchCoversTwentyFiveRowsEachWay() {
        val w = window(1000)
        assertEquals(0..1, w.prefetch(40, 49))
        assertEquals(1..2, w.prefetch(80, 90))
        assertEquals(0..0, w.prefetch(0, 10))
        assertEquals(19..19, w.prefetch(990, 999))
        assertTrue(window(0).prefetch(0, 0).isEmpty())
    }

    @Test
    fun visibleInOnlyForPagesOnScreen() {
        val w = window(1000)
        assertTrue(w.visibleIn(0, 45, 55))
        assertTrue(w.visibleIn(1, 45, 55))
        assertFalse(w.visibleIn(2, 45, 55))
        assertFalse(w.visibleIn(0, 5, 2))
    }

    @Test
    fun fetchThenMove() {
        val w = window(1000)
        w.request(0); w.put(w.token, 0, "p0")
        assertTrue(w.moveTo(10)) // loaded: move now
        assertEquals(-1, w.pendingMove)
        assertFalse(w.moveTo(620)) // page 12 not loaded: wait for it
        assertEquals(620, w.pendingMove)
        w.request(3)
        assertEquals(NotesWindow.Put.STORED, w.put(w.token, 3, "p3"))
        w.request(12)
        assertEquals(NotesWindow.Put.MOVE, w.put(w.token, 12, "p12"))
        assertEquals(620, w.takeMove())
        assertEquals(-1, w.takeMove())
    }

    @Test
    fun farJumpIsClampedAndTheTargetPageIsNotEvicted() {
        val w = window(1000)
        assertFalse(w.moveTo(5000))
        assertEquals(999, w.pendingMove)
        for (p in 0 until 6) { w.request(p); w.put(w.token, p, "p$p") }
        w.request(19)
        assertEquals(NotesWindow.Put.MOVE, w.put(w.token, 19, "p19"))
        assertTrue(w.isLoaded(19))
    }

    @Test
    fun aJumpNearAPageEndWaitsForTheNextPageToo() {
        val w = window(1000)
        assertEquals(2..3, w.movePages(140))
        assertEquals(2..2, w.movePages(110))
        assertEquals(19..19, w.movePages(990))
        assertFalse(w.moveTo(140))
        w.request(2)
        assertEquals(NotesWindow.Put.STORED, w.put(w.token, 2, "p2"))
        w.request(3)
        assertEquals(NotesWindow.Put.MOVE, w.put(w.token, 3, "p3"))
        assertEquals(140, w.takeMove())
        assertTrue(w.moveTo(145))
    }

    @Test
    fun numberPadAndSaveRules() {
        assertEquals(0, NotesWindow.rowForPage(1, 7))
        assertEquals(14, NotesWindow.rowForPage(3, 7))
        assertEquals(0, NotesWindow.rowForPage(0, 7))
        assertFalse(NotesWindow.saveToFile(20_000))
        assertTrue(NotesWindow.saveToFile(20_001))
        assertEquals(0, NotesWindow.clampFirst(40, 0))
        assertEquals(9, NotesWindow.clampFirst(40, 10))
        assertEquals(0, NotesWindow.clampFirst(-3, 10))
    }

    @Test
    fun aBigSelectionRoundTripsExactly() {
        // The exact refs come back (an unchecked row stays unchecked), not "select all".
        val refs = LongArray(30_000) { (2L shl 56) or (it * 3L) }.also { it[7] = (1L shl 56) or 99L }
        val out = java.io.ByteArrayOutputStream()
        NotesWindow.writeRefs(out, refs)
        assertTrue(refs.contentEquals(NotesWindow.readRefs(java.io.ByteArrayInputStream(out.toByteArray()))))
        // A cut file restores nothing rather than a guess.
        val cut = out.toByteArray().copyOf(out.size() - 3)
        var threw = false
        try {
            NotesWindow.readRefs(java.io.ByteArrayInputStream(cut))
        } catch (_: java.io.IOException) {
            threw = true
        }
        assertTrue(threw)
    }
}
