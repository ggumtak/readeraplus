package com.ggumtak.readeraplus.reader.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenPresetsTest {
    @Test
    fun roundTrip() {
        val list = listOf(
            PenPreset(InkTool.PEN, 0xFF123456.toInt(), 2.5f, true),
            PenPreset(InkTool.HIGHLIGHTER, 0xFFFFF176.toInt(), 12f, false),
        )
        val back = PenPresets.decode(PenPresets.encode(list))
        assertEquals(2, back.size)
        assertEquals(InkTool.PEN, back[0].tool)
        assertEquals(0xFF123456.toInt(), back[0].color)
        assertEquals(2.5f, back[0].width, 0f)
        assertTrue(back[0].pressure)
        assertEquals(InkTool.HIGHLIGHTER, back[1].tool)
        assertFalse(back[1].pressure)
    }

    @Test
    fun tolerantDecode() {
        assertEquals(PenPresets.DEFAULTS, PenPresets.decode(null))
        assertEquals(PenPresets.DEFAULTS, PenPresets.decode(""))
        assertEquals(PenPresets.DEFAULTS, PenPresets.decode("garbage;1,2"))
        // Bad entries skipped, widths clamped, colours made opaque.
        val l = PenPresets.decode("0,255,100,1;7,0,1,0;1,-1,1,0;x,1,1,1")
        assertEquals(2, l.size)
        assertEquals(PenPresets.PEN_MAX, l[0].width, 0f)
        assertEquals(0xFF0000FF.toInt(), l[0].color)
        assertEquals(PenPresets.HIGHLIGHTER_MIN, l[1].width, 0f)
    }

    @Test
    fun recentColorsMoveToFrontWithoutDuplicates() {
        var r = IntArray(0)
        r = PenPresets.pushRecent(r, 1)
        r = PenPresets.pushRecent(r, 2)
        r = PenPresets.pushRecent(r, 1)
        assertArrayEquals(intArrayOf(1, 2), r)
        for (c in 3..20) r = PenPresets.pushRecent(r, c)
        assertEquals(PenPresets.MAX_RECENT, r.size)
        assertEquals(20, r[0])
        assertArrayEquals(r, PenPresets.decodeColors(PenPresets.encodeColors(r)))
        assertArrayEquals(IntArray(0), PenPresets.decodeColors(null))
    }
}
