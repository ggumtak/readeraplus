package com.ggumtak.readeraplus.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Book-order spans and the hub's page-window maths (N §5.3.2–§5.3.3). */
class NotesPagingTest {

    private fun loc(prefix: IntArray, row: Int): Pair<Int, Int> {
        val v = BookSpans.locate(prefix, row)
        return if (v < 0) -1 to -1 else (v ushr 32).toInt() to (v and 0xFFFF_FFFFL).toInt()
    }

    @Test
    fun prefixSums() {
        assertArrayEquals(intArrayOf(0), BookSpans.prefix(IntArray(0)))
        assertArrayEquals(intArrayOf(0, 3, 3, 10, 11), BookSpans.prefix(intArrayOf(3, 0, 7, 1)))
    }

    @Test
    fun locateBoundariesEmptyBooksAndLastRow() {
        val p = BookSpans.prefix(intArrayOf(3, 0, 7, 1))
        assertEquals(0 to 0, loc(p, 0))
        assertEquals(0 to 2, loc(p, 2))
        assertEquals(2 to 0, loc(p, 3)) // book 1 is empty: skipped
        assertEquals(2 to 6, loc(p, 9))
        assertEquals(3 to 0, loc(p, 10)) // last row
        assertEquals(-1 to -1, loc(p, 11))
        assertEquals(-1 to -1, loc(p, -1))
        assertEquals(-1 to -1, loc(BookSpans.prefix(IntArray(0)), 0))
        val lead = BookSpans.prefix(intArrayOf(0, 0, 2))
        assertEquals(2 to 0, loc(lead, 0))
        assertEquals(2 to 1, loc(lead, 1))
    }

    @Test
    fun locateMatchesALinearWalk() {
        val counts = intArrayOf(5, 0, 1, 50, 0, 0, 120, 2, 1)
        val p = BookSpans.prefix(counts)
        var row = 0
        for (b in counts.indices) for (inner in 0 until counts[b]) {
            assertEquals(b to inner, loc(p, row))
            row++
        }
    }

    @Test
    fun pageWindows() {
        assertEquals(0, NotesSql.pageOffset(0)); assertEquals(50, NotesSql.pageLimit(0))
        assertEquals(49, NotesSql.pageOffset(1)); assertEquals(51, NotesSql.pageLimit(1))
        assertEquals(50 * 7 - 1, NotesSql.pageOffset(7))
        assertEquals(0, NotesPaging.pageOf(0)); assertEquals(0, NotesPaging.pageOf(49)); assertEquals(1, NotesPaging.pageOf(50))
        assertEquals(0, NotesPaging.pageCount(0)); assertEquals(1, NotesPaging.pageCount(50)); assertEquals(2, NotesPaging.pageCount(51))
        assertEquals(50, NotesPaging.rowsOf(0, 120)); assertEquals(20, NotesPaging.rowsOf(2, 120)); assertEquals(0, NotesPaging.rowsOf(3, 120))
        assertEquals(100, NotesPaging.firstRow(2))
    }

    @Test
    fun prefetchVisiblePagesThenNextThenPrevious() {
        assertArrayEquals(intArrayOf(0, 1), NotesPaging.prefetch(0, 10, 500))
        assertArrayEquals(intArrayOf(2, 3, 4, 1), NotesPaging.prefetch(120, 160, 500))
        assertArrayEquals(intArrayOf(9, 8), NotesPaging.prefetch(470, 499, 500))
        assertArrayEquals(intArrayOf(0), NotesPaging.prefetch(0, 30, 30))
        assertArrayEquals(IntArray(0), NotesPaging.prefetch(0, 0, 0))
        assertArrayEquals(intArrayOf(1, 0), NotesPaging.prefetch(60, 1000, 90)) // clamped to the last row
    }
}
