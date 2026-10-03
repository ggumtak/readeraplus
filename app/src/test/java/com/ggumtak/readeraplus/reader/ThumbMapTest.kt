package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbMapTest {

    private fun complete(pages: IntArray, chars: Int = 1000): PageCounts {
        val c = PageCounts(IntArray(pages.size) { chars })
        for (i in pages.indices) c.set(i, pages[i], chars)
        return c
    }

    @Test
    fun completeCountsMapEveryPageBack() {
        val c = complete(intArrayOf(3, 1, 12, 7, 2))
        val m = ThumbMap()
        for (p in 1..c.total()) {
            m.resolve(c, p, 1)
            assertEquals(1, m.size)
            assertEquals(p, c.globalPage(m.sections[0], m.indices[0]))
        }
        m.resolve(c, 1, 12)
        assertFalse(m.hasDuplicates())
        for (i in 0 until m.size) assertEquals(1 + i, c.globalPage(m.sections[i], m.indices[i]))
    }

    @Test
    fun cellsStopAtTheLastPage() {
        val c = complete(intArrayOf(5, 4))
        val m = ThumbMap()
        m.resolve(c, 7, 12)
        assertEquals(3, m.size)
        assertEquals(7, m.first)
        m.resolve(c, 50, 12) // beyond the book → the last page
        assertEquals(1, m.size)
        assertEquals(9, m.first)
    }

    @Test
    fun partialCountsConvergeWithinThreeRounds() {
        // Real pages differ a lot from the estimates (1000 chars each, no counts yet).
        val real = intArrayOf(2, 30, 1, 1, 1, 25, 3, 40)
        val c = PageCounts(IntArray(real.size) { 1000 })
        c.set(0, 2, 1000) // one known section gives the estimator a rate
        val laid = HashSet<Int>().apply { add(0) }
        val m = ThumbMap()
        for (first in intArrayOf(1, 13, 25, 37, 49, 61)) {
            val rounds = ThumbMap.converge(m, c, first, 12, { it in laid }) { s ->
                c.set(s, real[s], 1000)
                laid += s
            }
            assertTrue("rounds $rounds", rounds <= ThumbMap.MAX_ROUNDS)
            assertTrue(m.missing { it in laid }.isEmpty())
            assertFalse("first $first", m.hasDuplicates())
            val pairs = HashSet<Pair<Int, Int>>()
            for (i in 0 until m.size) {
                assertTrue(pairs.add(m.sections[i] to m.indices[i]))
                assertTrue(m.indices[i] < real[m.sections[i]])
                assertEquals(m.first + i, c.globalPage(m.sections[i], m.indices[i]))
            }
        }
    }

    @Test
    fun missingListsDistinctSectionsInOrder() {
        val c = PageCounts(intArrayOf(1000, 1000, 1000))
        c.set(0, 2, 1000)
        val m = ThumbMap()
        m.resolve(c, 1, 6)
        val need = m.missing { it == 0 }
        assertEquals(need.distinct().size, need.size)
        assertTrue(need.isNotEmpty() && need.none { it == 0 })
        for (i in 1 until need.size) assertTrue(need[i] > need[i - 1])
    }

    @Test
    fun clampWhenAnEstimateOverReaches() {
        val c = PageCounts(intArrayOf(10_000))
        c.set(0, 50, 10_000)
        val m = ThumbMap()
        m.resolve(c, 45, 12)
        assertEquals(6, m.size)
        // The layout turned out shorter than the count (another generation's estimate).
        m.clampIndices { 40 }
        for (i in 0 until m.size) assertTrue(m.indices[i] <= 39)
        assertEquals(0, ThumbMap.clampIndex(5, 0))
        assertEquals(0, ThumbMap.clampIndex(-1, 3))
        assertEquals(2, ThumbMap.clampIndex(9, 3))
    }

    @Test
    fun failedLayoutIsNotRetried() {
        val c = PageCounts(intArrayOf(1000, 1000))
        val tried = HashMap<Int, Int>()
        val done = HashSet<Int>()
        val m = ThumbMap()
        val rounds = ThumbMap.converge(m, c, 1, 4, { it in done }) { s ->
            tried[s] = (tried[s] ?: 0) + 1
            done += s // failed: marked ready without a count
        }
        assertTrue(rounds <= ThumbMap.MAX_ROUNDS)
        assertTrue(tried.values.all { it == 1 })
    }

    /**
     * PageThumbs.fillIn as the JVM sees it: [layOut] is session.layout (counts the section, stores it in a
     * MAX_CACHED-entry LRU), [thumbs] is the thumbnail LRU keyed by (section, pageIndex). Returns the cells.
     */
    private fun coldFill(
        m: ThumbMap, c: PageCounts, first: Int, count: Int, layouts: LinkedHashMap<Int, Int>,
        thumbs: HashMap<Pair<Int, Int>, String>, layOut: (Int) -> Unit,
    ): List<Pair<Int, Int>> {
        val local = HashSet<Int>()
        val ready: (Int) -> Boolean = { s -> s in local || (layouts[s] != null).also { if (it) local += s } }
        ThumbMap.converge(m, c, first, count, ready) { s -> layOut(s); local += s }
        for (s in m.missing(ready)) { layOut(s); local += s }
        m.clampIndices { c.pages(it) }
        return List(m.size) { i -> (m.sections[i] to m.indices[i]).also { thumbs[it] = "bitmap $it" } }
    }

    @Test
    fun warmGridPageMapsWithoutLayoutsAfterTheyWereEvicted() {
        // Many small chapters: one grid page touches more sections than the session keeps laid out.
        val real = IntArray(16) { 1 + it % 3 }
        val c = PageCounts(IntArray(real.size) { 1000 })
        val layouts = object : LinkedHashMap<Int, Int>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Int>?) = size > BookSession.MAX_CACHED
        }
        var layoutCalls = 0
        val layOut: (Int) -> Unit = { s -> layoutCalls++; c.set(s, real[s], 1000); layouts[s] = real[s] }
        val thumbs = HashMap<Pair<Int, Int>, String>()
        val m = ThumbMap()
        val cold = coldFill(m, c, 1, 12, layouts, thumbs, layOut)
        assertTrue(layoutCalls >= 5)
        assertTrue(cold.map { it.first }.distinct().size > BookSession.MAX_CACHED)
        val labels = List(m.size) { c.globalPage(m.sections[it], m.indices[it]) }
        // The reader turns on and prefetches its neighbours: none of the grid page's sections stays laid out.
        for (s in 10 until 10 + BookSession.MAX_CACHED) layouts[s] = real[s]
        assertTrue(cold.none { it.first in layouts })

        // The second request for the same grid page: no layout at all, every cell a hit, the same cells and labels.
        val before = layoutCalls
        m.resolve(c, 1, 12)
        val hits = arrayOfNulls<String>(m.size)
        var lookups = 0
        assertTrue(m.cached(c, hits) { s, idx -> lookups++; thumbs[s to idx] })
        assertEquals(before, layoutCalls)
        assertEquals(cold.size, m.size)
        assertEquals(m.size, lookups)
        assertEquals(m.size, hits.count { it != null })
        for (i in 0 until m.size) {
            assertEquals(cold[i], m.sections[i] to m.indices[i])
            assertEquals("bitmap ${cold[i]}", hits[i])
            assertEquals(labels[i], c.globalPage(m.sections[i], m.indices[i]))
        }
    }

    @Test
    fun anUncountedSectionOrAMissIsNotWarm() {
        val c = PageCounts(intArrayOf(1000, 1000, 1000))
        c.set(0, 3, 1000)
        c.set(1, 2, 1000)
        val m = ThumbMap()
        val all = HashMap<Pair<Int, Int>, String>()
        for (s in 0..2) for (p in 0 until 4) all[s to p] = "$s/$p"
        // Section 2 is touched and uncounted: the mapping still needs its layout, and nothing is looked up.
        m.resolve(c, 4, 4)
        var lookups = 0
        assertFalse(m.cached(c, arrayOfNulls<String>(m.size)) { s, idx -> lookups++; all[s to idx] })
        assertEquals(0, lookups)
        // Every touched section counted, one cell missing from the cache.
        m.resolve(c, 1, 5)
        assertTrue(m.cached(c, arrayOfNulls<String>(m.size)) { s, idx -> all[s to idx] })
        all.remove(1 to 1)
        assertFalse(m.cached(c, arrayOfNulls<String>(m.size)) { s, idx -> all[s to idx] })
        // Nothing resolved (an empty book) is trivially warm, as the converge path completes it empty.
        val none = PageCounts(IntArray(0))
        val empty = ThumbMap()
        empty.resolve(none, 1, 12)
        assertTrue(empty.cached(none, arrayOfNulls<String>(0)) { _, _ -> null })
    }
}
