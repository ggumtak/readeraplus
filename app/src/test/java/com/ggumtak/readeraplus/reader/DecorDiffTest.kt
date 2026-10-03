package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.render.PageDecor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecorDiffTest {
    private fun q(start: Int, end: Int, style: Int = 0, kind: HighlightKind = HighlightKind.QUOTE) =
        Highlight(start, end, kind, style)

    @Test fun equalContentIsSameEvenWithNewObjects() {
        val a = PageDecor(listOf(q(1, 5, 2), q(8, 9)), bookmarked = true, statusVersion = 3)
        val b = PageDecor(listOf(q(1, 5, 2), q(8, 9)), bookmarked = true, statusVersion = 3)
        assertTrue(DecorDiff.same(a, b))
        assertTrue(DecorDiff.same(a, a))
        assertTrue(DecorDiff.same(PageDecor(), PageDecor()))
    }

    @Test fun aRecolouredQuoteRepaints() {
        assertFalse(DecorDiff.same(PageDecor(listOf(q(1, 5, 0))), PageDecor(listOf(q(1, 5, 3)))))
    }

    @Test fun rangeKindCountBookmarkAndStatusEachRepaint() {
        val base = PageDecor(listOf(q(1, 5)), bookmarked = false, statusVersion = 1)
        assertFalse(DecorDiff.same(base, PageDecor(listOf(q(2, 5)), statusVersion = 1)))
        assertFalse(DecorDiff.same(base, PageDecor(listOf(q(1, 6)), statusVersion = 1)))
        assertFalse(DecorDiff.same(base, PageDecor(listOf(q(1, 5, kind = HighlightKind.SEARCH)), statusVersion = 1)))
        assertFalse(DecorDiff.same(base, PageDecor(listOf(q(1, 5), q(7, 8)), statusVersion = 1)))
        assertFalse(DecorDiff.same(base, PageDecor(listOf(q(1, 5)), bookmarked = true, statusVersion = 1)))
        assertFalse(DecorDiff.same(base, PageDecor(listOf(q(1, 5)), statusVersion = 2)))
    }

    @Test fun orderMatters() {
        assertFalse(DecorDiff.same(PageDecor(listOf(q(1, 2), q(3, 4))), PageDecor(listOf(q(3, 4), q(1, 2)))))
    }
}
