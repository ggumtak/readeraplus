package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.engine.RunStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MeasurerFontKeyTest {
    @Test
    fun linksOfOneLookShareAPaintKey() {
        // A footnote-heavy EPUB: every href is its own RunStyle, but they all draw with the same paint.
        val a = RunStyle(link = "#fn1")
        val b = RunStyle(link = "#fn2", underline = true)
        assertEquals(AndroidTextMeasurer.fontKey(a), AndroidTextMeasurer.fontKey(b))
        assertEquals(RunStyle.PLAIN, AndroidTextMeasurer.fontKey(a))
        assertEquals(RunStyle(bold = true), AndroidTextMeasurer.fontKey(RunStyle(bold = true, strike = true, link = "x")))
    }

    @Test
    fun whatTheFontDependsOnIsKept() {
        val big = RunStyle(sizeScale = 1.5f, link = "#a")
        assertNotEquals(RunStyle.PLAIN, AndroidTextMeasurer.fontKey(big))
        assertEquals(RunStyle(sizeScale = 1.5f), AndroidTextMeasurer.fontKey(big))
        assertEquals(RunStyle(italic = true, baselineShift = 1, monospace = true),
            AndroidTextMeasurer.fontKey(RunStyle(italic = true, baselineShift = 1, monospace = true, link = "#b")))
    }

    @Test
    fun anOrdinaryRunIsItsOwnKey() {
        val s = RunStyle(bold = true, sizeScale = 1.2f)
        assertSame(s, AndroidTextMeasurer.fontKey(s))
    }
}
