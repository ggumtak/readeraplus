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
}
