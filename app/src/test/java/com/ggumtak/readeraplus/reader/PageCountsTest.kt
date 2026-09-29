package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.format.DocPosition
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCountsTest {

    @Test
    fun exactCountsGivePagesAndTotals() {
        val c = PageCounts(intArrayOf(1000, 2000, 500))
        c.set(0, 3, 1000)
        c.set(1, 5, 2000)
        c.set(2, 1, 500)
        assertTrue(c.isComplete)
        assertEquals(9, c.total())
        assertEquals(1, c.globalPage(0, 0))
        assertEquals(3, c.globalPage(0, 2))
        assertEquals(4, c.globalPage(1, 0))
        assertEquals(9, c.globalPage(2, 0))
        assertTrue(c.exactBefore(2))
        assertArrayEquals(intArrayOf(3, 5, 1), c.toArray())
    }

    @Test
    fun locateIsInverseOfGlobalPage() {
        val c = PageCounts(intArrayOf(10, 10, 10, 10))
        val pages = intArrayOf(2, 1, 4, 3)
        for (i in pages.indices) c.set(i, pages[i], 10)
        for (s in pages.indices) for (p in 0 until pages[s]) {
            assertEquals(s to p, c.locate(c.globalPage(s, p)))
        }
        assertEquals(0 to 0, c.locate(-5))
        assertEquals(3 to 2, c.locate(1000))
    }

    @Test
    fun estimatesUseCountedRatio() {
        val c = PageCounts(intArrayOf(1000, 3000, 2000))
        assertFalse(c.isComplete)
        // before anything is known: default ratio
        assertEquals(Math.round(3000.0 / PageCounts.DEFAULT_CHARS_PER_PAGE).toInt(), c.pages(1))
        c.set(0, 4, 1000) // 250 chars per page
        assertEquals(12, c.pages(1))
        assertEquals(8, c.pages(2))
        assertEquals(24, c.total())
        assertTrue(c.exactBefore(1))
        assertFalse(c.exactBefore(2))
        assertNull(c.toArray())
        // real char length replaces the approximation once counted
        c.set(1, 10, 2500)
        assertEquals(10, c.pages(1))
        assertTrue(c.exactBefore(2))
    }

    @Test
    fun recountReplacesValues() {
        val c = PageCounts(intArrayOf(100, 100))
        c.set(0, 2, 100)
        c.set(0, 3, 100)
        c.set(1, 1, 100)
        assertEquals(4, c.total())
        assertEquals(2, c.knownCount)
        c.reset()
        assertEquals(0, c.knownCount)
        assertFalse(c.isKnown(0))
    }

    @Test
    fun setAllFromCache() {
        val c = PageCounts(intArrayOf(100, 200))
        assertFalse(c.setAll(intArrayOf(1)))
        assertFalse(c.setAll(intArrayOf(1, 0)))
        assertTrue(c.setAll(intArrayOf(2, 7)))
        assertTrue(c.isComplete)
        assertEquals(9, c.total())
    }

    @Test
    fun emptySectionsCountAsOnePage() {
        val c = PageCounts(intArrayOf(0, 0, 700))
        assertEquals(1, c.pages(0))
        c.set(0, 0, 0)
        assertEquals(1, c.pages(0))
    }

    @Test
    fun estimatePageIndex() {
        val c = PageCounts(intArrayOf(1000))
        c.set(0, 10, 1000)
        assertEquals(0, c.estimatePageIndex(0, 0))
        assertEquals(5, c.estimatePageIndex(0, 500))
        assertEquals(9, c.estimatePageIndex(0, 999))
        assertEquals(9, c.estimatePageIndex(0, 5000))
        assertEquals(0, c.estimatePageIndex(3, 10))
    }

    @Test
    fun charProgressAndLocateFraction() {
        val c = PageCounts(intArrayOf(100, 300, 600))
        assertEquals(0f, c.charProgress(0, 0), 1e-6f)
        assertEquals(0.1f, c.charProgress(1, 0), 1e-6f)
        assertEquals(0.25f, c.charProgress(1, 150), 1e-6f)
        assertEquals(1f, c.charProgress(2, 600), 1e-6f)
        assertEquals(DocPosition(0, 0), c.locateFraction(0f))
        assertEquals(DocPosition(1, 150), c.locateFraction(0.25f))
        assertEquals(DocPosition(2, 0), c.locateFraction(0.4f))
        assertEquals(DocPosition(2, 600), c.locateFraction(1f))
        assertEquals(DocPosition.START, PageCounts(IntArray(0)).locateFraction(0.5f))
    }

    @Test
    fun pagesLeftInChapter() {
        val c = PageCounts(intArrayOf(100, 100, 100, 100))
        for (i in 0 until 4) c.set(i, 5, 100)
        // same section: next chapter starts at the top of page 4, reader on page 1 → pages 2, 3 left
        assertEquals(2, c.pagesLeftUntil(0, 1, 5, 0, 4, true))
        // next chapter starts mid-page 4 → page 4 still holds this chapter's tail
        assertEquals(3, c.pagesLeftUntil(0, 1, 5, 0, 4, false))
        // next chapter starts on this page
        assertEquals(0, c.pagesLeftUntil(0, 4, 5, 0, 4, false))
        // next chapter = start of section 2: rest of section 0 (2 pages) + section 1 (5 pages)
        assertEquals(7, c.pagesLeftUntil(0, 2, 5, 2, 0, true))
        // next chapter = next section start while on the last page
        assertEquals(0, c.pagesLeftUntil(0, 4, 5, 1, 0, true))
    }
}
