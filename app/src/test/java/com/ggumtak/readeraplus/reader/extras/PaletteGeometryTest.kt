package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Test

class PaletteGeometryTest {
    @Test
    fun paletteCell_maxOf48dpAndASixth() {
        // 360 dp (Comet, 720 px at density 2): (720 − 32) / 6 = 114 px = 57 dp.
        assertEquals(114, PaletteGeometry.paletteCell(720, 2f))
        // 411 dp phone at 2.625: (1079 − 42) / 6 = 172.
        assertEquals((1079 - 42) / 6, PaletteGeometry.paletteCell(1079, 2.625f))
        // A narrow window: the 48 dp floor.
        assertEquals(96, PaletteGeometry.paletteCell(400, 2f))
    }

    @Test
    fun selectionCells_fillTheRow() {
        assertEquals(688, PaletteGeometry.rowWidth(720, 2f))
        assertEquals(137, PaletteGeometry.selectionCell(720, 2f))
        assertEquals(114, PaletteGeometry.inlinePaletteCell(720, 2f))
        assertEquals(0, PaletteGeometry.rowWidth(10, 2f))
    }

    @Test
    fun ringInset_isGapPlusRing() {
        assertEquals(10f, PaletteGeometry.ringInset(2f), 0f)
        assertEquals(5f * 2.625f, PaletteGeometry.ringInset(2.625f), 1e-4f)
    }

    @Test
    fun placement() {
        assertEquals(16, PaletteGeometry.popupX(20f, 300, 720, 16))
        assertEquals(720 - 300 - 16, PaletteGeometry.popupX(710f, 300, 720, 16))
        assertEquals(210, PaletteGeometry.popupX(360f, 300, 720, 16))
        // Above when it fits, else below, else centred.
        assertEquals(500 - 8 - 120, PaletteGeometry.anchoredY(500, 600, 120, 8, 16, 1440))
        assertEquals(100 + 8, PaletteGeometry.anchoredY(40, 100, 120, 8, 16, 1440))
        assertEquals((300 - 200) / 2, PaletteGeometry.anchoredY(20, 280, 200, 8, 16, 300))
        assertEquals(400 - 112 - 20, PaletteGeometry.selectionY(400f, 450f, 112, 20, 88, 16, 1440))
        assertEquals(60 + 88 + 20, PaletteGeometry.selectionY(10f, 60f, 112, 20, 88, 16, 1440))
    }
}
