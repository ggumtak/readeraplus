package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.render.FooterFit.end
import org.junit.Assert.assertEquals
import org.junit.Test

class FooterFitTest {

    private val sep = "  ·  "

    /** Monospace: 10 px per char. */
    private fun fit(text: String, avail: Float): Int = end(text, sep, avail) { a, b -> (b - a) * 10f }

    @Test
    fun wholeTextWhenItFits() {
        val t = "12 / 3259${sep}123/540화"
        assertEquals(t.length, fit(t, t.length * 10f))
        assertEquals(t.length, fit(t, 10_000f))
    }

    @Test
    fun dropsWholeItemsFromTheEnd() {
        val t = "12 / 3259${sep}123/540화${sep}챕터 5쪽 남음${sep}이 화 3분"
        val two = "12 / 3259${sep}123/540화"
        val three = "$two${sep}챕터 5쪽 남음"
        assertEquals(three.length, fit(t, (t.length - 1) * 10f))
        assertEquals(two.length, fit(t, (three.length - 1) * 10f))
        assertEquals("12 / 3259".length, fit(t, (two.length - 1) * 10f))
        // The kept part never ends with a separator.
        assertEquals(two, t.substring(0, fit(t, (two.length + sep.length) * 10f)))
    }

    @Test
    fun minusOneWhenNotEvenTheFirstItemFits() {
        val t = "12 / 3259${sep}123/540화"
        assertEquals(-1, fit(t, 8 * 10f))
        assertEquals(-1, fit("12 / 3259", 5 * 10f))
    }

    @Test
    fun theReadersFooterUsesTheSameSeparator() {
        // PageRenderer cuts at its own copy of the separator: it must match the strings the reader builds.
        assertEquals(ReaderFormat.SEP, sep)
        val t = listOf("1 / 20", "3/40화", "이 화 3분").joinToString(ReaderFormat.SEP)
        assertEquals("1 / 20${sep}3/40화".length, fit(t, (t.length - 2) * 10f))
    }
}
