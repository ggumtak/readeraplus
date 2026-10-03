package com.ggumtak.readeraplus.reader

import kotlin.math.floor

/**
 * Pure geometry of the 썸네일 grid (NOTES_SPEC §12.4, library.md §3.2; unit-tested). All sizes are in dp; "pages" are
 * the footer's 1-based global page numbers and grid pages are 0-based.
 *
 * A cell is the thumbnail (page aspect) with a [LABEL_DP] label band under it; cells are [GAP_DP] apart inside a
 * [PAD_DP] padding. Comet body 360 × 574 dp, aspect 2.0 → 4 × 3 of 78 × 156; a 411 dp phone → 5 × 3.
 */
internal object ThumbGridMath {
    const val PAD_DP = 12f
    const val GAP_DP = 8f
    const val LABEL_DP = 16f
    const val MIN_THUMB_DP = 56f
    const val MIN_COLS = 3
    const val MAX_COLS = 8
    const val MIN_ROWS = 3
    /** Page aspect used while the page view is not measured yet (thumbAspect() = 0). */
    const val DEFAULT_ASPECT = 1.6f

    /** [cols] × [rows] thumbnails of [thumbW] × [thumbH] dp. */
    data class Grid(val cols: Int, val rows: Int, val thumbW: Float, val thumbH: Float)

    /**
     * Smallest column count in [MIN_COLS]..[MAX_COLS] that gives ≥ [MIN_ROWS] rows with thumbs ≥ [MIN_THUMB_DP] wide;
     * else the densest grid that fits (most cells, then the larger thumbs); a body too small for any of them gets one
     * thumbnail fitted into it.
     */
    fun layout(widthDp: Float, heightDp: Float, aspect: Float): Grid {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else DEFAULT_ASPECT
        val w = if (widthDp.isFinite()) widthDp.coerceAtLeast(0f) else 0f
        val h = if (heightDp.isFinite()) heightDp.coerceAtLeast(0f) else 0f
        var best: Grid? = null
        for (c in MIN_COLS..MAX_COLS) {
            val tw = thumbWidth(w, c)
            if (tw < MIN_THUMB_DP) break
            val th = tw * a
            val r = rowsFor(h, th)
            if (r < 1) continue
            val g = Grid(c, r, tw, th)
            if (r >= MIN_ROWS) return g
            val b = best
            if (b == null || c * r > b.cols * b.rows) best = g
        }
        best?.let { return it }
        // Narrow or short body: as many columns as fit at the minimum width (at least 1), thumbs shrunk to one row.
        val cols = floor((w - 2 * PAD_DP + GAP_DP) / (MIN_THUMB_DP + GAP_DP)).toInt().coerceIn(1, MIN_COLS)
        var tw = thumbWidth(w, cols).coerceAtLeast(1f)
        val room = (h - 2 * PAD_DP - LABEL_DP).coerceAtLeast(1f)
        if (tw * a > room) tw = room / a
        return Grid(cols, 1, tw, tw * a)
    }

    /** Width of one of [cols] thumbnails across a [widthDp] body. */
    fun thumbWidth(widthDp: Float, cols: Int): Float =
        (widthDp - 2 * PAD_DP - (cols - 1) * GAP_DP) / cols.coerceAtLeast(1)

    /** Rows of [thumbH]-tall cells (+ label, gaps) that fit a [heightDp] body. */
    fun rowsFor(heightDp: Float, thumbH: Float): Int =
        floor((heightDp - 2 * PAD_DP + GAP_DP) / (thumbH + LABEL_DP + GAP_DP)).toInt().coerceAtLeast(0)

    /** Left edge of column [col] (the grid is centred horizontally in a [widthDp] body). */
    fun cellLeft(g: Grid, col: Int, widthDp: Float): Float {
        val used = g.cols * g.thumbW + (g.cols - 1) * GAP_DP
        return ((widthDp - used) / 2f).coerceAtLeast(0f) + col * (g.thumbW + GAP_DP)
    }

    /** Top edge of row [row]. */
    fun cellTop(g: Grid, row: Int): Float = PAD_DP + row * (g.thumbH + LABEL_DP + GAP_DP)

    fun perPage(g: Grid): Int = (g.cols * g.rows).coerceAtLeast(1)

    /** Grid page (0-based) that shows [page]. */
    fun gridPageOf(page: Int, perPage: Int): Int = (page - 1).coerceAtLeast(0) / perPage.coerceAtLeast(1)

    /** First page on [gridPage]. */
    fun firstOf(gridPage: Int, perPage: Int): Int = gridPage.coerceAtLeast(0) * perPage.coerceAtLeast(1) + 1

    /** Grid pages of a [total]-page book (at least 1). */
    fun gridPages(total: Int, perPage: Int): Int {
        val n = perPage.coerceAtLeast(1)
        return ((total.coerceAtLeast(0) + n - 1) / n).coerceAtLeast(1)
    }

    /** [gridPage] clamped to the book (a jump to page N > total shows the last grid page). */
    fun clampGridPage(gridPage: Int, total: Int, perPage: Int): Int =
        gridPage.coerceIn(0, gridPages(total, perPage) - 1)

    /** Pages shown on [gridPage]: [perPage], fewer on the last one, 0 for an empty book. */
    fun countOn(gridPage: Int, total: Int, perPage: Int): Int {
        if (total <= 0) return 0
        val first = firstOf(clampGridPage(gridPage, total, perPage), perPage)
        return minOf(perPage.coerceAtLeast(1), total - first + 1).coerceAtLeast(0)
    }
}
