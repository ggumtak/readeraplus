package com.ggumtak.readeraplus.reader

/**
 * Pure geometry of the reader chrome (U §2.2, §2.4, §3.4). Widths in px; [density] = px per dp.
 * Everything here is decided outside layout passes (setVisible / bind), never in a layout listener.
 */
internal object ChromeMath {
    /** Room kept on each side of the centred page label: row padding 4 + rotation 48 + pin 48 + gap 8 (dp). */
    const val LABEL_RESERVE_DP = 108
    /** The top action row needs 8 + 7·48 = 344 dp plus a little spacer to also hold the bookmark (U §2.2). */
    const val BOOKMARK_MIN_ROW_DP = 352

    /**
     * Fixed width of the bottom bar's page label: the row minus [LABEL_RESERVE_DP] on both sides, so a label centred on
     * the full width can never run under the right cluster (720 px at density 2 → 288 px).
     */
    fun labelMaxWidth(rowW: Int, density: Float): Int =
        (rowW - 2 * Math.round(LABEL_RESERVE_DP * density)).coerceAtLeast(0)

    /**
     * True when the return strip must use its short side labels ("‹ 12345" / "23259 ›"): the three items plus two
     * [gap]s are wider than [rowW], or a side item ([left] from the start, [right] from the end) would reach the
     * centred [centre] box.
     */
    fun stripShort(left: Float, centre: Float, right: Float, rowW: Float, gap: Float): Boolean {
        if (left + centre + right + 2f * gap > rowW) return true
        val centreStart = (rowW - centre) / 2f
        return left + gap > centreStart || right + gap > centreStart
    }

    /** The top-row bookmark is shown only when the bar is at least [BOOKMARK_MIN_ROW_DP] wide. */
    fun bookmarkFits(rowW: Int, density: Float): Boolean = rowW >= Math.round(BOOKMARK_MIN_ROW_DP * density)
}
