package com.ggumtak.readeraplus.ui.library

import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.ui.kit.PageFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGridMathTest {

    private val grid = LibraryListMode.GRID
    private val covers = LibraryListMode.COVERS

    @Test
    fun columns_thumbsAndCovers() {
        assertEquals(3, LibraryGridMath.columns(grid, 360f))
        assertEquals(3, LibraryGridMath.columns(grid, 411f))
        assertEquals(6, LibraryGridMath.columns(grid, 720f))
        assertEquals(4, LibraryGridMath.columns(covers, 360f))
        assertEquals(4, LibraryGridMath.columns(covers, 411f))
        assertEquals(8, LibraryGridMath.columns(covers, 720f))
        // Minimums on a very narrow window.
        assertEquals(2, LibraryGridMath.columns(grid, 150f))
        assertEquals(3, LibraryGridMath.columns(covers, 150f))
    }

    @Test
    fun cells_holdTheirCovers() {
        for (density in floatArrayOf(2f, 2.625f, 2.8125f)) {
            for (wDp in intArrayOf(360, 411, 720)) {
                val w = Math.round(wDp * density)
                for (m in listOf(grid, covers)) {
                    val cols = LibraryGridMath.columns(m, wDp.toFloat())
                    val cell = LibraryGridMath.cellWidthPx(w, cols, density)
                    assertTrue("$m $wDp@$density", cell >= Math.round(LibraryGridMath.cell(m).coverWDp * density))
                }
            }
        }
    }

    @Test
    fun lastColumn_leftOfTheGuardedZone() {
        for (density in floatArrayOf(2f, 2.625f, 2.8125f)) {
            for (wDp in intArrayOf(360, 411, 720)) {
                val w = Math.round(wDp * density)
                for (m in listOf(grid, covers)) {
                    val cols = LibraryGridMath.columns(m, wDp.toFloat())
                    val left = LibraryGridMath.lastColumnLeftPx(w, cols, density)
                    assertTrue("$m $wDp@$density: $left", left < w - 0 - 57 * density)
                    assertTrue(LibraryGridMath.guardSafe(w, cols, density))
                }
            }
        }
        // A hypothetical 30 dp cell grid would break it.
        assertFalse(LibraryGridMath.guardSafe(720, 22, 2f))
    }

    @Test
    fun fitRows_cometPages() {
        // Comet: list 596 dp tall (640 − 44 pager bar) at density 2, 8 dp padding top and bottom.
        val d = 2
        val inner = (596 - 16) * d
        val (gRows, gCell) = LibraryGridMath.fitRows(inner, 184 * d, 6 * d)
        assertEquals(3, gRows)
        assertEquals(9, gRows * LibraryGridMath.columns(grid, 360f))
        assertTrue(gCell >= 184 * d)
        assertEquals(inner, gRows * gCell + (gRows - 1) * 6 * d + (inner + 6 * d) % gRows)
        val (cRows, cCell) = LibraryGridMath.fitRows(inner, 138 * d, 6 * d)
        assertEquals(4, cRows)
        assertEquals(16, cRows * LibraryGridMath.columns(covers, 360f))
        assertTrue(cCell >= 138 * d)
        // List views: targets only (they page by measurement).
        assertEquals(4, PageFit.fit(596, 149).first)
        assertEquals(7 to 85, PageFit.fit(596, 80))
    }

    @Test
    fun fitRows_cellHeightIsTheRowHeight_at360And411() {
        for (hDp in intArrayOf(596, 640 + 91)) { // Comet (360 dp wide) and a 411 dp phone list height
            for (m in listOf(grid, covers)) {
                val c = LibraryGridMath.cell(m)
                val inner = (hDp - 16) * 2
                val (rows, cellH) = LibraryGridMath.fitRows(inner, c.heightDp * 2, 12)
                // Exact cells: rows × cell + gaps never exceeds the page, and one more row would not fit.
                assertTrue(rows * cellH + (rows - 1) * 12 <= inner)
                assertTrue((rows + 1) * c.heightDp * 2 + rows * 12 > inner)
                assertTrue(cellH >= c.heightDp * 2)
            }
        }
    }

    @Test
    fun fitRows_tinyListStillOneRow() {
        assertEquals(1 to 50, LibraryGridMath.fitRows(50, 368, 12))
        assertEquals(1, LibraryGridMath.fitRows(0, 368, 12).first)
    }

    @Test
    fun cellRecipes() {
        val g = LibraryGridMath.cell(grid)
        assertEquals(96, g.coverWDp); assertEquals(136, g.coverHDp); assertEquals(2, g.titleLines); assertEquals(184, g.heightDp)
        val c = LibraryGridMath.cell(covers)
        assertEquals(76, c.coverWDp); assertEquals(108, c.coverHDp); assertEquals(1, c.titleLines); assertEquals(138, c.heightDp)
        assertTrue(LibraryGridMath.isGrid(grid) && LibraryGridMath.isGrid(covers))
        assertFalse(LibraryGridMath.isGrid(LibraryListMode.LIST) || LibraryGridMath.isGrid(LibraryListMode.COMPACT))
    }
}
