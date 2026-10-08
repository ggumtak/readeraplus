package com.ggumtak.readeraplus.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PenColorsTest {
    @Test
    fun hsvPrimaries() {
        assertEquals(0xFFFF0000.toInt(), PenColors.hsvToArgb(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), PenColors.hsvToArgb(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), PenColors.hsvToArgb(240f, 1f, 1f))
        assertEquals(0xFFFFFFFF.toInt(), PenColors.hsvToArgb(200f, 0f, 1f))
        assertEquals(0xFF000000.toInt(), PenColors.hsvToArgb(200f, 1f, 0f))
        assertEquals(0xFF0000FF.toInt(), PenColors.hsvToArgb(-120f, 1f, 1f))
    }

    @Test
    fun argbToHsvKnownValues() {
        val o = FloatArray(3)
        PenColors.argbToHsv(0xFFFFFF00.toInt(), o)
        assertEquals(60f, o[0], 0.01f)
        assertEquals(1f, o[1], 0.001f)
        assertEquals(1f, o[2], 0.001f)
        PenColors.argbToHsv(0xFF000000.toInt(), o)
        assertEquals(0f, o[1], 0f)
        assertEquals(0f, o[2], 0f)
        PenColors.argbToHsv(0xFFFF0080.toInt(), o)
        assertEquals(330f, o[0], 0.6f)
    }

    @Test
    fun paletteRoundTripsAndIsOpaque() {
        val o = FloatArray(3)
        for (c in PenColors.PEN_PALETTE + PenColors.HIGHLIGHTER_PALETTE) {
            assertEquals(0xFF, (c ushr 24) and 0xFF)
            PenColors.argbToHsv(c, o)
            val back = PenColors.hsvToArgb(o[0], o[1], o[2])
            for (shift in intArrayOf(16, 8, 0)) {
                assertTrue(Math.abs(((c shr shift) and 0xFF) - ((back shr shift) and 0xFF)) <= 1)
            }
        }
        assertEquals(16, PenColors.PEN_PALETTE.size)
        assertEquals(12, PenColors.HIGHLIGHTER_PALETTE.size)
    }

    @Test
    fun widthMapping() {
        assertEquals(0.5f, PenColors.progressToWidth(0, 100, 0.5f, 8f), 0f)
        assertEquals(8f, PenColors.progressToWidth(100, 100, 0.5f, 8f), 0f)
        assertEquals(8f, PenColors.progressToWidth(500, 100, 0.5f, 8f), 0f)
        assertEquals(0, PenColors.widthToProgress(0.5f, 100, 0.5f, 8f))
        assertEquals(100, PenColors.widthToProgress(9f, 100, 0.5f, 8f))
        val p = PenColors.widthToProgress(3.3f, 100, 0.5f, 8f)
        assertEquals(3.3f, PenColors.progressToWidth(p, 100, 0.5f, 8f), 0.06f)
        assertEquals(0, PenColors.widthToProgress(2f, 100, 2f, 2f))
    }

    @Test
    fun formatWidth() {
        assertEquals("1.4", PenColors.formatWidth(1.4f))
        assertEquals("2.0", PenColors.formatWidth(2f))
        assertEquals("0.5", PenColors.formatWidth(0.5f))
        assertEquals("12.0", PenColors.formatWidth(11.96f))
    }
}
