package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.settings.TapZoneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TapZoneModelTest {
    private val def = AppSettings().customTapZones

    @Test
    fun cells() {
        assertEquals(0, TapZoneModel.cellAt(0.1f, 0.1f))
        assertEquals(4, TapZoneModel.cellAt(0.5f, 0.5f))
        assertEquals(8, TapZoneModel.cellAt(0.99f, 0.99f))
        assertEquals(5, TapZoneModel.cellAt(0.7f, 0.5f))
        assertEquals("가운데", TapZoneModel.cellName(4))
        assertEquals("오른쪽 아래", TapZoneModel.cellName(8))
    }

    @Test
    fun leftRight() {
        val m = TapZoneMode.LEFT_RIGHT
        assertEquals(TapAction.PREV, TapZoneModel.actionAt(m, def, 0.1f, 0.1f))
        assertEquals(TapAction.PREV, TapZoneModel.actionAt(m, def, 0.2f, 0.5f))
        assertEquals(TapAction.MENU, TapZoneModel.actionAt(m, def, 0.5f, 0.5f))
        assertEquals(TapAction.NEXT, TapZoneModel.actionAt(m, def, 0.5f, 0.1f))
        assertEquals(TapAction.NEXT, TapZoneModel.actionAt(m, def, 0.9f, 0.9f))
    }

    @Test
    fun allNext() {
        val m = TapZoneMode.ALL_NEXT
        assertEquals(TapAction.PREV, TapZoneModel.actionAt(m, def, 0.05f, 0.9f))
        assertEquals(TapAction.NEXT, TapZoneModel.actionAt(m, def, 0.2f, 0.9f))
        assertEquals(TapAction.NEXT, TapZoneModel.actionAt(m, def, 0.2f, 0.5f))
        assertEquals(TapAction.MENU, TapZoneModel.actionAt(m, def, 0.5f, 0.5f))
    }

    @Test
    fun topBottom() {
        val m = TapZoneMode.TOP_BOTTOM
        assertEquals(TapAction.PREV, TapZoneModel.actionAt(m, def, 0.9f, 0.1f))
        assertEquals(TapAction.NEXT, TapZoneModel.actionAt(m, def, 0.1f, 0.9f))
        assertEquals(TapAction.MENU, TapZoneModel.actionAt(m, def, 0.5f, 0.5f))
        assertEquals(TapAction.NEXT, TapZoneModel.actionAt(m, def, 0.1f, 0.5f))
    }

    @Test
    fun customFallbacks() {
        val noMenu = List(9) { TapAction.NEXT }
        assertTrue(TapZoneModel.centreForcedToMenu(noMenu))
        assertEquals(TapAction.MENU, TapZoneModel.actionAt(TapZoneMode.CUSTOM, noMenu, 0.5f, 0.5f))
        assertEquals(TapAction.NEXT, TapZoneModel.actionAt(TapZoneMode.CUSTOM, noMenu, 0.1f, 0.1f))
        val menuCorner = TapZoneModel.withCell(noMenu, 0, TapAction.MENU)
        assertFalse(TapZoneModel.centreForcedToMenu(menuCorner))
        assertEquals(TapAction.NEXT, TapZoneModel.actionAt(TapZoneMode.CUSTOM, menuCorner, 0.5f, 0.5f))
        // Malformed list → defaults.
        assertEquals(def, TapZoneModel.effectiveCustom(listOf(TapAction.NEXT)))
    }

    @Test
    fun gridMatchesActionAtEverywhere() {
        val rnd = Random(7)
        val customs = listOf(def, TapZoneModel.PRESET_ALL_NEXT, TapZoneModel.mirrored(def), List(9) { TapAction.values()[it % TapAction.values().size] })
        for (mode in TapZoneMode.values()) {
            for (custom in customs) {
                val g = TapZoneModel.grid(mode, custom)
                assertEquals(0f, g.xs.first())
                assertEquals(1f, g.xs.last())
                assertEquals(0f, g.ys.first())
                assertEquals(1f, g.ys.last())
                repeat(2000) {
                    val fx = rnd.nextFloat()
                    val fy = rnd.nextFloat()
                    var col = 0
                    while (col < g.cols - 1 && fx >= g.xs[col + 1]) col++
                    var row = 0
                    while (row < g.rows - 1 && fy >= g.ys[row + 1]) row++
                    assertEquals("$mode ($fx,$fy)", TapZoneModel.actionAt(mode, custom, fx, fy), g.action(col, row))
                }
            }
        }
    }

    @Test
    fun oneLabelPerRegion() {
        fun regions(mode: TapZoneMode) = TapZoneModel.labels(TapZoneModel.grid(mode, def), perCell = false)
        val lr = regions(TapZoneMode.LEFT_RIGHT)
        assertEquals(3, lr.size)
        val lrNext = lr.single { it.action == TapAction.NEXT }
        assertEquals(2, lrNext.col) // right column, middle row
        assertEquals(1, lrNext.row)
        val lrPrev = lr.single { it.action == TapAction.PREV }
        assertEquals(0, lrPrev.col)
        assertEquals(1, lrPrev.row)

        val an = regions(TapZoneMode.ALL_NEXT)
        assertEquals(3, an.size)
        val anNext = an.single { it.action == TapAction.NEXT }
        assertEquals(3, anNext.col) // widest column on the right, not the narrow strip neighbour
        assertEquals(1, anNext.row)

        val tb = regions(TapZoneMode.TOP_BOTTOM)
        assertEquals(3, tb.size)
        val tbNext = tb.single { it.action == TapAction.NEXT }
        assertEquals(1, tbNext.col)
        assertEquals(2, tbNext.row)

        val perCell = TapZoneModel.labels(TapZoneModel.grid(TapZoneMode.CUSTOM, def), perCell = true)
        assertEquals(9, perCell.size)
        assertEquals(def, perCell.map { it.action })
    }

    @Test
    fun presetsAndMirror() {
        val m = TapZoneModel.mirrored(def)
        assertEquals(TapAction.NEXT, m[0])
        assertEquals(TapAction.PREV, m[2])
        assertEquals(def, TapZoneModel.mirrored(m))
        assertEquals(TapAction.MENU, TapZoneModel.PRESET_ALL_NEXT[4])
        assertEquals(8, TapZoneModel.PRESET_ALL_NEXT.count { it == TapAction.NEXT })
        assertEquals(TapAction.PREV, TapZoneModel.PRESET_TOP_PREV[1])
        assertEquals(TapAction.NEXT, TapZoneModel.PRESET_TOP_PREV[7])
        assertEquals(TapAction.TOC, TapZoneModel.withCell(def, 8, TapAction.TOC)[8])
        assertEquals(def, TapZoneModel.withCell(def, 99, TapAction.TOC))
    }

    @Test
    fun labelsForEveryAction() {
        for (a in TapAction.values()) assertTrue(TapZoneModel.shortLabel(a).isNotEmpty())
        for (m in TapZoneMode.values()) {
            assertTrue(TapZoneModel.modeName(m).isNotEmpty())
            assertTrue(TapZoneModel.modeDescription(m).isNotEmpty())
        }
    }

    @Test
    fun modeNamesAreShortKorean() {
        // The radio rows and the main list's 넘기기·터치·키 summary use these one names.
        assertEquals(
            listOf("좌우 넘김", "어디든 다음", "어디든 이전", "위아래 넘김", "직접 지정"),
            TapZoneMode.values().map { TapZoneModel.modeName(it) },
        )
        assertEquals("왼쪽 1/3 = 이전 · 나머지 = 다음", TapZoneModel.modeDescription(TapZoneMode.LEFT_RIGHT))
        assertEquals("9칸에 동작을 직접 지정", TapZoneModel.modeDescription(TapZoneMode.CUSTOM))
        // The preview and the key chooser say 듣기, as the reader does.
        assertEquals("듣기", TapZoneModel.shortLabel(TapAction.TTS))
        assertEquals("듣기", TapAction.TTS.label)
    }
}
