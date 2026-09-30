package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.ui.kit.Ink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic of the second extras review: search text cache, exact page target, handle colours. */
class ExtrasReviewFixes2Test {

    @Test
    fun searchTextsAreLoadedOncePerDocument() {
        val doc = Any()
        val cache = SectionTextCache(doc, 3)
        var loads = 0
        val load = { i: Int -> loads++; "섹션 $i 본문" }
        assertEquals("섹션 0 본문", cache.text(0, load))
        assertEquals("섹션 2 본문", cache.text(2, load))
        assertEquals(2, loads)
        // A second query reuses the texts.
        assertEquals("섹션 0 본문", cache.text(0, load))
        assertEquals("섹션 2 본문", cache.text(2, load))
        assertEquals(2, loads)
        assertEquals(("섹션 0 본문".length + "섹션 2 본문".length).toLong(), cache.cachedChars)
        // Out of range: nothing loaded.
        assertNull(cache.text(3, load))
        assertNull(cache.text(-1, load))
        assertEquals(2, loads)
        assertTrue(cache.isFor(doc, 3))
        assertFalse(cache.isFor(Any(), 3))
        assertFalse(cache.isFor(doc, 4))
    }

    @Test
    fun failedLoadsAreRetriedAndTheCapIsRespected() {
        val cache = SectionTextCache(Any(), 3, maxChars = 10)
        var loads = 0
        assertNull(cache.text(0) { loads++; null })
        assertEquals("abcdef", cache.text(0) { loads++; "abcdef" })
        assertEquals(2, loads)
        // Over the cap: returned but not kept.
        assertEquals("ghijkl", cache.text(1) { loads++; "ghijkl" })
        assertEquals("ghijkl", cache.text(1) { loads++; "ghijkl" })
        assertEquals(4, loads)
        assertEquals(6L, cache.cachedChars)
        // Still room for a small one.
        assertEquals("mno", cache.text(2) { loads++; "mno" })
        assertEquals("mno", cache.text(2) { loads++; "xxx" })
        assertEquals(5, loads)
        assertEquals(9L, cache.cachedChars)
    }

    @Test
    fun cachedTextIsTheSameInstance() {
        val cache = SectionTextCache(Any(), 1)
        val t = String(charArrayOf('가', '나'))
        assertSame(t, cache.text(0) { t })
        assertSame(t, cache.text(0) { "다른 값" })
    }

    @Test
    fun pageTargetFromSectionStartPages() {
        // Section first pages: 0 → 1, 1 → 5 (empty: same start as 2), 2 → 5, 3 → 12; 20 pages in all.
        val starts = intArrayOf(1, 5, 5, 12)
        fun t(page: Int) = PageLabel.pageTarget(starts.size, page, 20) { starts[it] }.let { Triple(it.section, it.index, it.pagesInSection) }
        assertEquals(Triple(0, 0, 4), t(1))
        assertEquals(Triple(0, 3, 4), t(4))
        assertEquals(Triple(2, 0, 7), t(5))
        assertEquals(Triple(2, 6, 7), t(11))
        assertEquals(Triple(3, 0, 9), t(12))
        assertEquals(Triple(3, 8, 9), t(20))
        // Out of range: clamped into the first / last section.
        assertEquals(Triple(3, 8, 9), t(25))
        assertEquals(Triple(0, 0, 4), t(0))
        // One-section book and empty book.
        val one = PageLabel.pageTarget(1, 7, 10) { 1 }
        assertEquals(0, one.section)
        assertEquals(6, one.index)
        val none = PageLabel.pageTarget(0, 7, 10) { 1 }
        assertEquals(0, none.section)
        assertEquals(0, none.index)
    }

    @Test
    fun selectionHandlesFollowThePageColours() {
        assertEquals(Ink.BLACK, HandleColors.fill(invert = false))
        assertEquals(Ink.WHITE, HandleColors.outline(invert = false))
        assertEquals(Ink.WHITE, HandleColors.fill(invert = true))
        assertEquals(Ink.BLACK, HandleColors.outline(invert = true))
    }
}
