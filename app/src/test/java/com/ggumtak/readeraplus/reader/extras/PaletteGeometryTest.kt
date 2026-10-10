package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun rowWidth_isTheScreenMinusEightDpASide() {
        assertEquals(688, PaletteGeometry.rowWidth(720, 2f))
        assertEquals(0, PaletteGeometry.rowWidth(10, 2f))
    }

    @Test
    fun paletteCell_leavesRoomForAPhonesShadow() {
        // A phone card keeps 10 dp of shadow room a side (8 dp are already the margin): 2 dp = 4 px more at density 2.
        assertEquals((688 - 8) / 6, PaletteGeometry.paletteCell(720, 2f, extraInset = 4))
        assertEquals(114, PaletteGeometry.paletteCell(720, 2f, extraInset = 0))
        assertEquals(96, PaletteGeometry.paletteCell(400, 2f, extraInset = 4))
    }

    @Test
    fun cellPad_sharesTheLeftoverRoomAndStaysWithinBounds() {
        // Comet: 360 dp - 16 dp = 344 dp; the cells' own widths add up to 184 dp: (344 - 184) / 12 cells' sides = 13.3 dp.
        val widths = intArrayOf(30, 45, 34, 30, 30, 18).map { it * 2 }.toIntArray()
        assertEquals(26, PaletteGeometry.cellPad(widths, 688, 32))
        // A wide phone: capped at 16 dp (32 px).
        assertEquals(32, PaletteGeometry.cellPad(widths, 900, 32))
        // Too narrow: packed, never negative.
        assertEquals(0, PaletteGeometry.cellPad(widths, 300, 32))
        assertEquals(0, PaletteGeometry.cellPad(IntArray(0), 700, 32))
        // The row fits exactly with the pad it got.
        val pad = PaletteGeometry.cellPad(widths, 688, 32)
        assertTrue(widths.sum() + 2 * pad * widths.size <= 688)
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

    @Test
    fun belowY_prefersUnderTheAnchor_thenAbove_thenCentre() {
        // The ⋯ menu opens under its cell, above it only when the screen ends too soon, else centred.
        assertEquals(500 + 8, PaletteGeometry.belowY(400, 500, 300, 8, 16, 1440))
        assertEquals(1200 - 8 - 300, PaletteGeometry.belowY(1200, 1300, 300, 8, 16, 1440))
        assertEquals((700 - 600) / 2, PaletteGeometry.belowY(300, 400, 600, 8, 16, 700))
        assertEquals(16, PaletteGeometry.belowY(300, 400, 690, 8, 16, 700))
    }

    @Test
    fun rightAlignedMenu_staysOnScreen() {
        // popupX(anchorRight - w / 2, w, ...) puts the menu's right edge on the cell's, clamped inside the margins.
        assertEquals(600 - 150, PaletteGeometry.popupX(600 - 150 / 2f, 150, 720, 16))
        assertEquals(720 - 150 - 16, PaletteGeometry.popupX(718 - 150 / 2f, 150, 720, 16))
        assertEquals(16, PaletteGeometry.popupX(100 - 150 / 2f, 150, 720, 16))
    }
}
