package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionDragTest {

    @Test
    fun clampOffsetKeepsAnOffsetInsideTheSpan() {
        assertEquals(15, SelectionDrag.clampOffset(15, 10, 20))
        assertEquals(10, SelectionDrag.clampOffset(10, 10, 20))
        assertEquals(19, SelectionDrag.clampOffset(19, 10, 20))
    }

    @Test
    fun clampOffsetMovesAnOffsetOutsideToTheNearestChar() {
        assertEquals(10, SelectionDrag.clampOffset(3, 10, 20))
        // The span's end is exclusive: the last char is end - 1.
        assertEquals(19, SelectionDrag.clampOffset(20, 10, 20))
        assertEquals(19, SelectionDrag.clampOffset(500, 10, 20))
    }

    @Test
    fun clampOffsetOfAOneCharSpanIsThatChar() {
        assertEquals(7, SelectionDrag.clampOffset(0, 7, 8))
        assertEquals(7, SelectionDrag.clampOffset(99, 7, 8))
    }

    @Test
    fun clampOffsetOfAnEmptySpanIsMinusOneInsteadOfThrowing() {
        // An empty section's page is PageInfo(0, 0): coerceIn(0, -1) threw IllegalArgumentException mid-touch.
        assertEquals(-1, SelectionDrag.clampOffset(0, 0, 0))
        assertEquals(-1, SelectionDrag.clampOffset(5, 5, 5))
        assertEquals(-1, SelectionDrag.clampOffset(5, 9, 4))
    }

    @Test
    fun memoIsEmptyUntilStored() {
        val m = DragGeometryMemo()
        assertFalse(m.matches(0, 0))
        assertFalse(m.matches(1080, 1920))
    }

    @Test
    fun memoMatchesTheSizeItWasStoredFor() {
        val m = DragGeometryMemo()
        m.store(1080, 1920, 96, 144f)
        assertTrue(m.matches(1080, 1920))
        assertEquals(96, m.contentTop)
        assertEquals(144f, m.zonePx, 0f)
        // A view of another size (rotation, split screen) never uses it.
        assertFalse(m.matches(1920, 1080))
        assertFalse(m.matches(1080, 1919))
    }

    @Test
    fun invalidateForcesARecomputeEvenAtTheSameSize() {
        val m = DragGeometryMemo()
        m.store(1080, 1920, 96, 144f)
        m.invalidate()
        assertFalse(m.matches(1080, 1920))
        m.store(1080, 1920, 120, 144f)
        assertTrue(m.matches(1080, 1920))
        assertEquals(120, m.contentTop)
    }
}
