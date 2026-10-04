package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.format.DocPosition
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        c.charsPerPageHint = 250
        // before anything is known: the geometry hint
        assertEquals(12, c.pages(1))
        c.set(0, 4, 1000) // 250 chars per page, same as the hint
        assertEquals(12, c.pages(1))
        assertEquals(8, c.pages(2))
        assertEquals(24, c.total())
        assertTrue(c.exactBefore(1))
        assertFalse(c.exactBefore(2))
        // A2: partial arrays are saved too (-1 = not counted yet)
        assertArrayEquals(intArrayOf(4, -1, -1), c.toArray())
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
    fun chapterStartPage() {
        val c = PageCounts(intArrayOf(100, 100, 100, 100))
        for (i in 0 until 4) c.set(i, 5, 100)
        // An entry at the top of page index 3 of section 1 (global page 9): the chapter begins there.
        assertEquals(9, c.chapterStart(1, 3, true))
        // An entry mid-page: that page belongs to the chapter before, this one begins on the next page.
        assertEquals(10, c.chapterStart(1, 3, false))
        // Mid-page on a section's last page: the next section's first page.
        assertEquals(11, c.chapterStart(1, 4, false))
        assertEquals(1, c.chapterStart(0, 0, true))
    }

    @Test
    fun chapterPageAroundAMidPageChapter() {
        val c = PageCounts(intArrayOf(100, 100, 100))
        for (i in 0 until 3) c.set(i, 5, 100)
        val inp = StatusInputs()
        // Front matter on pages 1–3 (the first entry starts mid-page 3, so page 3 is still front matter).
        val first = c.chapterStart(0, 2, false)
        assertEquals(4, first)
        inp.setChapterPage(c.globalPage(0, 2), 1, first)
        assertEquals("3/3", ReaderFormat.chapterPage(inp.chapterPage, inp.chapterPages))
        // The chapter: from page 4 up to the next entry at the top of section 2 (page 11): 7 pages.
        val next = c.chapterStart(2, 0, true)
        inp.setChapterPage(c.globalPage(0, 3), first, next)
        assertEquals("1/7", ReaderFormat.chapterPage(inp.chapterPage, inp.chapterPages))
        inp.setChapterPage(c.globalPage(1, 4), first, next)
        assertEquals("7/7", ReaderFormat.chapterPage(inp.chapterPage, inp.chapterPages))
        // The last chapter ends with the book.
        inp.setChapterPage(c.globalPage(2, 1), next, c.total() + 1)
        assertEquals("2/5", ReaderFormat.chapterPage(inp.chapterPage, inp.chapterPages))
    }

    @Test
    fun tinyFirstSectionDoesNotExplodeTheEstimate() {
        // A 10-char title section counted first (1 page) must not make a 7M-char book look like 700k pages.
        val c = PageCounts(intArrayOf(10, 7_000_000))
        c.charsPerPageHint = 400
        c.set(0, 1, 10)
        val est = c.pages(1)
        assertTrue("estimate $est", est in 10_000..25_000)
    }

    // ---------------------------------------------------------------- A2: partial counts

    @Test
    fun setKnownTakesTheCountedEntriesOfAPartialArray() {
        val c = PageCounts(intArrayOf(100, 200, 300, 400))
        assertFalse(c.setKnown(intArrayOf(2, -1, 5, -1)))
        assertTrue(c.isKnown(0))
        assertFalse(c.isKnown(1))
        assertEquals(5, c.pages(2))
        assertEquals(2, c.knownCount)
        assertArrayEquals(intArrayOf(2, -1, 5, -1), c.toArray())
        // Completing it reports completion.
        assertTrue(c.setKnown(intArrayOf(-1, 3, -1, 7)))
        assertTrue(c.isComplete)
        assertEquals(17, c.total())
    }

    @Test
    fun setKnownKeepsCountsMadeInThisSession() {
        val c = PageCounts(intArrayOf(100, 200))
        c.set(1, 4, 250) // laid out in the foreground: exact, with its real length
        assertTrue(c.setKnown(intArrayOf(2, 9)))
        assertEquals(4, c.pages(1))
        assertEquals(250, c.charLength(1))
        assertEquals(6, c.total())
    }

    @Test
    fun setKnownRejectsStaleOrCorruptArraysWithoutChangingAnything() {
        val c = PageCounts(intArrayOf(100, 200, 300))
        c.set(0, 2, 100)
        assertFalse(c.setKnown(intArrayOf(2, 3))) // another section split
        assertFalse(c.setKnown(intArrayOf(2, 3, 4, 5)))
        assertFalse(c.setKnown(intArrayOf(2, 0, 4))) // 0 is never a saved count
        assertFalse(c.setKnown(intArrayOf(2, -2, 4)))
        assertEquals(1, c.knownCount)
        assertFalse(c.isKnown(2))
        // setAll keeps rejecting unknown entries: it takes complete arrays only.
        assertFalse(c.setAll(intArrayOf(2, -1, 4)))
        assertEquals(1, c.knownCount)
    }

    @Test
    fun countedInMatchesWhatSetKnownTakes() {
        assertEquals(2, PageCounts.countedIn(intArrayOf(3, -1, 1)))
        assertEquals(0, PageCounts.countedIn(intArrayOf(-1, -1)))
        assertEquals(0, PageCounts.countedIn(intArrayOf(3, 0, 1)))
        assertEquals(0, PageCounts.countedIn(intArrayOf(3, -5)))
        assertEquals(0, PageCounts.countedIn(IntArray(0)))
    }

    @Test
    fun partialArrayRoundTrips() {
        val a = PageCounts(intArrayOf(1000, 1000, 1000, 1000, 1000))
        a.set(0, 3, 1000)
        a.set(3, 4, 1000)
        val saved = a.toArray()
        saved[0] = 99 // a copy: the caller may mask entries (failed sections) without touching the counts
        assertEquals(3, a.pages(0))
        val b = PageCounts(intArrayOf(1000, 1000, 1000, 1000, 1000))
        assertFalse(b.setKnown(a.toArray()))
        assertEquals(a.toArray().toList(), b.toArray().toList())
        assertEquals(a.total(), b.total())
    }

    // ---------------------------------------------------------------- A2: the estimator

    @Test
    fun smallSectionsStopDrivingTheEstimateOnceALargeOneIsCounted() {
        // A title page (300 chars, 1 page) and a preface (1,500 chars, 3 pages) are counted first; the real chapters
        // run at 500 chars per page. Once one real chapter is counted, only it drives the estimate.
        val c = PageCounts(intArrayOf(300, 1_500, 50_000, 50_000, 50_000))
        c.charsPerPageHint = 500
        c.set(0, 1, 300)
        c.set(1, 3, 1_500)
        val small = c.pagesPerChar()
        c.set(2, 100, 50_000)
        val expected = (100 + PageCounts.PRIOR_PAGES) / (50_000 + PageCounts.PRIOR_PAGES * 500)
        assertEquals(expected, c.pagesPerChar(), 1e-12)
        assertTrue("small sections gave $small", small > c.pagesPerChar())
        assertEquals(100, c.pages(3))
        // Only small sections known: they are all there is (the geometry prior keeps them in check).
        val d = PageCounts(intArrayOf(10, 7_000_000))
        d.charsPerPageHint = 400
        d.set(0, 1, 10)
        assertEquals((1 + PageCounts.PRIOR_PAGES) / (10 + PageCounts.PRIOR_PAGES * 400), d.pagesPerChar(), 1e-12)
    }

    @Test
    fun estimatorFollowsASectionThatGrowsPastTheThreshold() {
        // An EPUB section estimated at 1,000 chars turns out to hold 3,000 when laid out: it now counts as large.
        val c = PageCounts(intArrayOf(1_000, 500, 10_000))
        c.charsPerPageHint = 500
        c.set(1, 1, 500)
        c.set(0, 2, 1_000)
        c.set(0, 6, 3_000)
        assertEquals((6 + PageCounts.PRIOR_PAGES) / (3_000 + PageCounts.PRIOR_PAGES * 500), c.pagesPerChar(), 1e-12)
        c.reset()
        assertEquals(1.0 / 500, c.pagesPerChar(), 1e-12)
    }

    // ---------------------------------------------------------------- T1-7: characters left

    @Test
    fun charsAfterAndFromAreSuffixSums() {
        val c = PageCounts(intArrayOf(100, 300, 600))
        assertEquals(1000L, c.charsAfter(-1))
        assertEquals(900L, c.charsAfter(0))
        assertEquals(600L, c.charsAfter(1))
        assertEquals(0L, c.charsAfter(2))
        assertEquals(0L, c.charsAfter(7))
        assertEquals(1000L, c.totalChars())
        assertEquals(850L, c.charsFrom(1, 50))
        assertEquals(900L, c.charsFrom(1, -5)) // clamped to the section
        assertEquals(600L, c.charsFrom(1, 999))
        assertEquals(0L, c.charsFrom(3, 0))
        // A section's real length replaces its estimate once laid out.
        c.set(0, 1, 40)
        assertEquals(940L, c.charsAfter(-1))
        assertEquals(900L, c.charsAfter(0))
        assertEquals(920L, c.charsFrom(0, 20))
    }

    @Test
    fun charsBetweenSpansSections() {
        val c = PageCounts(intArrayOf(100, 300, 600))
        assertEquals(30L, c.charsBetween(0, 10, 0, 40)) // next chapter in the same section
        assertEquals(90L + 300 + 25, c.charsBetween(0, 10, 2, 25)) // two sections on
        assertEquals(0L, c.charsBetween(1, 50, 1, 20)) // target behind
        assertEquals(0L, c.charsBetween(1, 50, 1, 50))
        assertEquals(850L, c.charsBetween(1, 50, 3, 0)) // past the last section = end of the book
    }

    @Test
    fun charQueriesMatchAPlainScanAfterEveryChange() {
        val approx = IntArray(40) { 500 + it * 37 % 900 }
        val c = PageCounts(approx)
        val real = approx.copyOf()
        fun check() {
            val total = real.sumOf { it.toLong() }
            for (s in -1..real.size) {
                var after = 0L
                for (i in (s + 1).coerceAtLeast(0) until real.size) after += real[i]
                assertEquals(after, c.charsAfter(s))
            }
            for (s in real.indices) {
                val before = (0 until s).sumOf { real[it].toLong() }
                val off = real[s] / 3
                assertEquals(((before + off).toDouble() / total).toFloat(), c.charProgress(s, off), 1e-6f)
            }
        }
        check()
        for (k in 0 until 40 step 3) {
            real[k] = 200 + k * 11
            c.set(k, 1 + k % 4, real[k])
            check()
        }
        c.set(3, 9, real[3]) // same length: the sums stay valid
        check()
    }

    // ---------------------------------------------------------------- A2: counting order

    @Test
    fun countOrderIsForegroundThenQuarterSamplesThenEverything() {
        val order = CountOrder.plan(100, 42, null)
        assertEquals(listOf(42, 25, 50, 75), order.take(4))
        assertEquals((0 until 100).toList(), order.drop(4))
        // No foreground yet; a sample equal to the foreground isn't repeated.
        assertEquals(listOf(25, 50, 75), CountOrder.plan(100, -1, null).take(3).toList())
        assertEquals(listOf(50, 25, 75, 0), CountOrder.plan(100, 50, null).take(4).toList())
        // Tiny books: samples collapse, every section is still there.
        assertEquals(listOf(0, 0), CountOrder.plan(1, 0, null).toList())
        assertEquals(listOf(0, 1, 2), CountOrder.plan(3, -1, null).drop(CountOrder.plan(3, -1, null).size - 3).toList())
        assertEquals(0, CountOrder.plan(0, 0, null).size)
    }

    @Test
    fun epubSamplesOnlyWholeSpineItems() {
        // Sections 20..79 are parts of one split spine item: the 50% sample must not land in it.
        val samplable = BooleanArray(100) { it < 20 || it >= 80 }
        val order = CountOrder.plan(100, -1, samplable)
        val samples = order.take(order.size - 100)
        // 25 → nearest whole item within 12 sections: 19; 50: none within reach; 75 → 80.
        assertEquals(listOf(19, 80), samples)
        assertEquals((0 until 100).toList(), order.drop(samples.size))
        // Nothing samplable: plain order.
        assertEquals((0 until 10).toList(), CountOrder.plan(10, -1, BooleanArray(10)).toList())
    }
}
