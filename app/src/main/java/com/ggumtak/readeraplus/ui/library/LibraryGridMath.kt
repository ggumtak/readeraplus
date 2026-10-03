package com.ggumtak.readeraplus.ui.library

import com.ggumtak.readeraplus.settings.LibraryListMode

/**
 * Pure (JVM-tested) geometry of the two cover grids, 썸네일 ([LibraryListMode.GRID]) and 그리드
 * ([LibraryListMode.COVERS]), served by one GridView (NOTES_SPEC §10.2–§10.4, library.md §2.5).
 *
 * Every cell has an exact height: the title uses a fixed line count and the cell's LayoutParams height is [Cell.heightDp]
 * (or the page-fitted height when paged), so fixed-row paging ([fitRows]) never shows a cut cell.
 */
internal object LibraryGridMath {
    /** Grid padding: start / top / bottom 8 dp, end 12 dp (the fast scroller's strip, FastScrollGuard.GRAB_DP). */
    const val PAD_DP = 8
    const val PAD_END_DP = 12
    const val H_SPACING_DP = 6
    const val V_SPACING_DP = 6
    /** The platform grab zone right of which a shifted DOWN lands (FastScrollGuard: 56 dp zone + 1). */
    const val GUARD_DP = 57

    /** Canonical cover in dp, the 전체 card's (one bitmap size for every view; [CoverLoader] key). */
    const val CANON_W_DP = 96
    const val CANON_H_DP = 136

    /**
     * Cell recipe of one grid mode: column rule ([minCellDp], [minCols]), cover, the slot under it (progress line or
     * "새 책"), the title (sp, fixed lines) and the exact natural cell height.
     */
    class Cell(
        val minCellDp: Int,
        val minCols: Int,
        val coverWDp: Int,
        val coverHDp: Int,
        val slotDp: Int,
        val titleSp: Float,
        val titleLines: Int,
        val heightDp: Int,
    )

    private val THUMBS = Cell(110, 2, 96, 136, 6, 12f, 2, 184)
    private val COVERS = Cell(80, 3, 76, 108, 4, 11f, 1, 138)

    fun isGrid(mode: LibraryListMode): Boolean = mode == LibraryListMode.GRID || mode == LibraryListMode.COVERS

    /** The cell recipe of a grid mode (LIST / COMPACT get 썸네일's: never used for them). */
    fun cell(mode: LibraryListMode): Cell = if (mode == LibraryListMode.COVERS) COVERS else THUMBS

    /** `max(minCols, (W − 16 + 6) / (cell + 6))` for a screen [widthDp] wide. */
    fun columns(mode: LibraryListMode, widthDp: Float): Int {
        val c = cell(mode)
        val n = ((widthDp - 2 * PAD_DP + H_SPACING_DP) / (c.minCellDp + H_SPACING_DP)).toInt()
        return n.coerceAtLeast(c.minCols)
    }

    /** Column width in px of [cols] columns in [widthPx] with the grid's paddings and spacing. */
    fun cellWidthPx(widthPx: Int, cols: Int, density: Float): Int {
        val n = cols.coerceAtLeast(1)
        val used = px(PAD_DP, density) + px(PAD_END_DP, density) + (n - 1) * px(H_SPACING_DP, density)
        return ((widthPx - used) / n).coerceAtLeast(1)
    }

    /** Left edge (px) of the last column's cells. */
    fun lastColumnLeftPx(widthPx: Int, cols: Int, density: Float): Int {
        val w = cellWidthPx(widthPx, cols, density)
        return px(PAD_DP, density) + (cols - 1).coerceAtLeast(0) * (w + px(H_SPACING_DP, density))
    }

    /**
     * FastScrollGuard caveat (NOTES_SPEC §3.2): a DOWN shifted left of the scroller's zone still picks the last column
     * only while that column starts left of `W − inset − 57 dp`.
     */
    fun guardSafe(widthPx: Int, cols: Int, density: Float, insetEndPx: Int = 0): Boolean =
        lastColumnLeftPx(widthPx, cols, density) < widthPx - insetEndPx - GUARD_DP * density

    /**
     * Fixed rows of a paged grid: how many natural-height cells fit the list's inner height [innerH] (padding
     * excluded) and the cell height that fills it exactly: `cellH = (innerH + vSpacing) / rows − vSpacing`.
     */
    fun fitRows(innerH: Int, minCellH: Int, vSpacing: Int): Pair<Int, Int> {
        val h = innerH.coerceAtLeast(0)
        val rows = ((h + vSpacing) / (minCellH + vSpacing).coerceAtLeast(1)).coerceAtLeast(1)
        val cellH = ((h + vSpacing) / rows - vSpacing).coerceAtLeast(1)
        return rows to cellH
    }

    private fun px(dp: Int, density: Float): Int = Math.round(dp * density)
}
