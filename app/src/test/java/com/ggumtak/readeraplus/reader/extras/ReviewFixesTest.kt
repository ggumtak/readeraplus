package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Pure logic added by the extras review: percent → page mapping and the shared quote cache. */
class ReviewFixesTest {

    @Test
    fun percentToPage() {
        assertEquals(1, PageLabel.pageForPercent(0f, 3259))
        assertEquals(3259, PageLabel.pageForPercent(100f, 3259))
        assertEquals(1630, PageLabel.pageForPercent(50f, 3259))
        assertEquals(1, PageLabel.pageForPercent(-5f, 3259))
        assertEquals(3259, PageLabel.pageForPercent(250f, 3259))
        assertEquals(1, PageLabel.pageForPercent(50f, 1))
        assertEquals(1, PageLabel.pageForPercent(50f, 0))
        assertEquals(2, PageLabel.pageForPercent(50f, 2))
        // Monotone and always in range.
        var prev = 0
        for (p in 0..1000) {
            val page = PageLabel.pageForPercent(p / 10f, 777)
            assert(page in 1..777)
            assert(page >= prev)
            prev = page
        }
    }

    @Test
    fun quoteCacheIsPerBook() {
        val a = listOf(Quote(1, 10, 0, 5, 9, "첫째 문장", "", 0L))
        QuoteCache.put(10, a)
        assertSame(a, QuoteCache.get(10))
        assertNull(QuoteCache.get(11))
        val b = emptyList<Quote>()
        QuoteCache.put(11, b)
        assertNull(QuoteCache.get(10))
        assertSame(b, QuoteCache.get(11))
    }
}
