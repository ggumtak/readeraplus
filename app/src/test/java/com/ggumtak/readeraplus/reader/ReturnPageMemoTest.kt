package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** CI 29 13g: the return chip keeps the exact page of its origin after that section leaves the layout cache. */
class ReturnPageMemoTest {
    private val gen = Any()
    private val unknown = -1

    @Test
    fun anExactIndexIsKeptForWhenTheSectionIsNotLaidOut() {
        val m = ReturnPageMemo()
        assertEquals(1, m.resolve(gen, 1, 210, 1))       // page 3 = s:1 o:210, while section 1 is on screen
        assertEquals(1, m.resolve(gen, 1, 210, unknown)) // after the seeks: no estimate (it would read 0 = page 2)
    }

    @Test
    fun nothingRememberedAsksForTheEstimate() {
        val m = ReturnPageMemo()
        assertEquals(unknown, m.resolve(gen, 1, 210, unknown))
        m.resolve(gen, 1, 210, 1)
        assertEquals(unknown, m.resolve(gen, 1, 211, unknown)) // another place of the same section
        assertEquals(unknown, m.resolve(gen, 2, 210, unknown))
    }

    @Test
    fun aNewLayoutForgetsEverything() {
        val m = ReturnPageMemo()
        m.resolve(gen, 1, 210, 1)
        assertEquals(unknown, m.resolve(Any(), 1, 210, unknown)) // other font or margins: other pages
    }

    @Test
    fun theLaidOutSectionWins() {
        val m = ReturnPageMemo()
        m.resolve(gen, 1, 210, 1)
        assertEquals(2, m.resolve(gen, 1, 210, 2))
        assertEquals(2, m.resolve(gen, 1, 210, unknown))
    }

    @Test
    fun theLeastRecentlyAskedPlaceMakesRoom() {
        val m = ReturnPageMemo()
        for (s in 0 until ReturnPageMemo.SLOTS) m.resolve(gen, s, 0, 10 + s)
        m.resolve(gen, 0, 0, unknown) // the strip asks for place 0 again: it stays
        m.resolve(gen, 9, 0, 7)       // a fifth place replaces place 1, the least recently asked
        assertEquals(10, m.resolve(gen, 0, 0, unknown))
        assertEquals(unknown, m.resolve(gen, 1, 0, unknown))
        assertEquals(12, m.resolve(gen, 2, 0, unknown))
        assertEquals(13, m.resolve(gen, 3, 0, unknown))
        assertEquals(7, m.resolve(gen, 9, 0, unknown))
    }
}
