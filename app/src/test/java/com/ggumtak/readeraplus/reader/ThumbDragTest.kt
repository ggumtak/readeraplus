package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.ui.kit.PageDrag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 썸네일 grid's swipes: `PageDrag(axisBoth = true)` (library.md §3.8). */
class ThumbDragTest {
    private fun swipe(dx: Float, dy: Float): Int {
        val d = PageDrag(20f, axisBoth = true)
        d.down(100f, 100f)
        d.move(100f + dx / 2, 100f + dy / 2)
        return d.up(100f + dx, 100f + dy)
    }

    @Test
    fun horizontalSwipesPage() {
        assertEquals(1, swipe(-80f, 5f))  // finger to the left → next grid page
        assertEquals(-1, swipe(80f, -5f))
    }

    @Test
    fun verticalSwipesPage() {
        assertEquals(1, swipe(4f, -80f))  // finger up → next
        assertEquals(-1, swipe(-4f, 80f))
    }

    @Test
    fun smallMoveStaysATap() {
        val d = PageDrag(20f, axisBoth = true)
        d.down(10f, 10f)
        assertFalse(d.move(18f, 15f))
        assertEquals(0, d.up(18f, 15f))
    }

    @Test
    fun verticalOnlyDragTakesHorizontalWithoutPaging() {
        val d = PageDrag(20f)
        d.down(0f, 0f)
        assertTrue(d.move(80f, 5f)) // the list's, never a tap on a row
        assertEquals(0, d.up(80f, 5f))
        val both = PageDrag(20f, axisBoth = true)
        both.down(0f, 0f)
        assertTrue(both.move(80f, 5f))
        assertEquals(-1, both.up(80f, 5f))
    }
}
