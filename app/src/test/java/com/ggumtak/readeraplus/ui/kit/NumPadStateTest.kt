package com.ggumtak.readeraplus.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumPadStateTest {

    @Test
    fun digitsUpToMaxLength() {
        val s = NumPadState(3)
        assertTrue(s.isEmpty)
        assertNull(s.value)
        assertTrue(s.digit(5))
        assertTrue(s.digit(4))
        assertTrue(s.digit(0))
        assertFalse(s.digit(1))
        assertEquals("540", s.text)
        assertEquals(540, s.value)
    }

    @Test
    fun noLeadingZeros() {
        val s = NumPadState(4)
        assertTrue(s.digit(0))
        assertEquals("0", s.text)
        assertFalse(s.digit(0))
        assertEquals("0", s.text)
        assertTrue(s.digit(7))
        assertEquals("7", s.text)
        assertEquals(7, s.value)
    }

    @Test
    fun rejectsNonDigits() {
        val s = NumPadState()
        assertFalse(s.digit(-1))
        assertFalse(s.digit(10))
        assertTrue(s.isEmpty)
    }

    @Test
    fun backspaceAndClear() {
        val s = NumPadState()
        assertFalse(s.backspace())
        assertFalse(s.clear())
        s.digit(1); s.digit(2); s.digit(3)
        assertTrue(s.backspace())
        assertEquals("12", s.text)
        assertTrue(s.clear())
        assertEquals("", s.text)
        assertNull(s.value)
    }

    @Test
    fun loweringMaxLengthTrims() {
        val s = NumPadState(5)
        for (d in listOf(1, 2, 3, 4, 5)) s.digit(d)
        s.maxLength = 3
        assertEquals("123", s.text)
        assertEquals(3, s.maxLength)
        s.maxLength = 0
        assertEquals(1, s.maxLength)
        assertEquals("1", s.text)
        s.maxLength = 40
        assertEquals(NumPadState.MAX_LENGTH, s.maxLength)
    }

    @Test
    fun neverOverflows() {
        val s = NumPadState(99)
        repeat(20) { s.digit(9) }
        assertEquals(NumPadState.MAX_LENGTH, s.text.length)
        assertEquals(999_999_999, s.value)
    }

    @Test
    fun clamp() {
        val s = NumPadState()
        assertNull(s.clamped(1, 100))
        s.digit(4); s.digit(0); s.digit(0); s.digit(0)
        assertEquals(3259, s.clamped(1, 3259))
        assertEquals(4000, s.clamped(1, 5000))
        s.clear(); s.digit(0)
        assertEquals(1, s.clamped(1, 3259))
        // A reversed range (an empty book) never throws.
        assertEquals(1, s.clamped(1, 0))
    }

    @Test
    fun lengthFor() {
        assertEquals(1, NumPadState.lengthFor(0))
        assertEquals(1, NumPadState.lengthFor(9))
        assertEquals(3, NumPadState.lengthFor(540))
        assertEquals(5, NumPadState.lengthFor(43828))
        assertEquals(1, NumPadState.lengthFor(-5))
        assertEquals(NumPadState.MAX_LENGTH, NumPadState.lengthFor(Int.MAX_VALUE))
    }

    // ------------------------------------------------------------------ PagerMath

    @Test
    fun pagerStep() {
        assertEquals(9, PagerMath.step(10))
        assertEquals(1, PagerMath.step(1))
        assertEquals(1, PagerMath.step(0))
    }

    @Test
    fun pagerTotal() {
        // 27 rows, 10 on screen (the last cut): pages start at 0, 9, 18.
        assertEquals(3, PagerMath.total(27, 9, 9))
        assertEquals(2, PagerMath.total(10, 9, 9))
        assertEquals(1, PagerMath.total(9, 9, 9))
        assertEquals(1, PagerMath.total(0, 9, 9))
        // Exact fit (10 whole rows, step 9): one row repeats per page.
        assertEquals(3, PagerMath.total(27, 10, 9))
    }

    @Test
    fun pagerPage() {
        assertEquals(1, PagerMath.page(0, 27, 9, 9, atEnd = false))
        assertEquals(2, PagerMath.page(9, 27, 9, 9, atEnd = false))
        assertEquals(3, PagerMath.page(18, 27, 9, 9, atEnd = true))
        // Moved by [지금] to row 5: past page 1, not the end.
        assertEquals(2, PagerMath.page(5, 27, 9, 9, atEnd = false))
        // Page 3 only at the end, even when the rows put the top row at 18.
        assertEquals(2, PagerMath.page(18, 27, 9, 9, atEnd = false))
        // Two pages, in between: never beyond the total.
        assertEquals(2, PagerMath.page(4, 10, 9, 9, atEnd = false))
        assertEquals(1, PagerMath.page(0, 5, 9, 9, atEnd = true))
    }

    @Test
    fun pagerTarget() {
        assertEquals(9, PagerMath.target(0, 1, 9, 27))
        assertEquals(0, PagerMath.target(5, -1, 9, 27))
        assertEquals(26, PagerMath.target(20, 1, 9, 27))
        assertEquals(0, PagerMath.target(0, 1, 9, 0))
        assertEquals(3, PagerMath.firstFor(6, 3))
        assertEquals(0, PagerMath.firstFor(2, 3))
    }

    @Test
    fun pagerLabel() {
        assertEquals("3 / 27", PagerMath.label(3, 27))
        assertEquals("", PagerMath.label(0, 0))
    }
}
