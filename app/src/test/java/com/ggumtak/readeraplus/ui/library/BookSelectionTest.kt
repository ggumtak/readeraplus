package com.ggumtak.readeraplus.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSelectionTest {

    @Test
    fun inactiveByDefault_andNothingCounts() {
        val s = BookSelection()
        assertFalse(s.active)
        assertEquals(0, s.size)
        assertFalse(1L in s)
        // Toggles and [전체] need selection mode.
        assertFalse(s.toggle(1L))
        assertFalse(s.toggleAll(listOf(1L, 2L)))
        assertEquals(0, s.size)
        assertNull(s.single())
    }

    @Test
    fun longPressStartsWithThatBook_tapsToggle() {
        val s = BookSelection()
        s.start(7L)
        assertTrue(s.active)
        assertTrue(7L in s)
        assertEquals(7L, s.single())
        assertTrue(s.toggle(3L))
        assertEquals(2, s.size)
        assertNull(s.single()) // [더보기] only with exactly one
        assertFalse(s.toggle(7L))
        assertEquals(listOf(3L), s.snapshot())
        // Unchecking the last book keeps selection mode (the toolbar asks to pick books).
        assertFalse(s.toggle(3L))
        assertTrue(s.active)
        assertEquals(0, s.size)
    }

    @Test
    fun startAgainReplacesTheOldSelection() {
        val s = BookSelection()
        s.start(1L)
        s.toggle(2L)
        s.start(5L)
        assertEquals(listOf(5L), s.snapshot())
    }

    @Test
    fun selectAll_thenAgainClears() {
        val s = BookSelection()
        s.start(2L)
        val shown = listOf(1L, 2L, 3L)
        assertTrue(s.toggleAll(shown))
        assertEquals(setOf(1L, 2L, 3L), s.snapshot().toSet())
        assertTrue(s.toggleAll(shown))
        assertEquals(0, s.size)
        assertTrue(s.active)
        assertFalse(s.toggleAll(emptyList()))
    }

    @Test
    fun retainDropsBooksThatLeftTheList() {
        val s = BookSelection()
        s.start(1L)
        s.toggle(2L)
        s.toggle(3L)
        assertFalse(s.retain(setOf(1L, 2L, 3L, 4L)))
        assertTrue(s.retain(setOf(1L, 3L)))
        assertEquals(listOf(1L, 3L), s.snapshot())
    }

    @Test
    fun snapshotIsACopyInCheckOrder() {
        val s = BookSelection()
        s.start(9L)
        s.toggle(4L)
        s.toggle(6L)
        val snap = s.snapshot()
        s.end()
        assertEquals(listOf(9L, 4L, 6L), snap)
        assertFalse(s.active)
        assertEquals(0, s.size)
        assertFalse(9L in s)
    }
}
