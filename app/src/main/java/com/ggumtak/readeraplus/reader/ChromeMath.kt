package com.ggumtak.readeraplus.reader

/**
 * Pure geometry and timing of the reader chrome (U §2.1, §2.2, §2.4, §3.4). Widths in px; [density] = px per dp.
 * Everything here is decided outside layout passes (setVisible / bind), never in a layout listener.
 */
internal object ChromeMath {
    /** Room kept on each side of the centred page label: row padding 4 + rotation 48 + pin 48 + gap 8 (dp). */
    const val LABEL_RESERVE_DP = 108
    /** The top action row needs 8 + 7·48 = 344 dp plus a little spacer to also hold the bookmark (U §2.2). */
    const val BOOKMARK_MIN_ROW_DP = 352
    /** The history row directly above the bottom panel (U §2.4). */
    const val HISTORY_ROW_DP = 44
    /** The bars' show / hide on a phone (U §2.1 Motion): a short fade with a slide of [SLIDE_DP] toward their edge. */
    const val SHOW_MS = 180L
    const val HIDE_MS = 150L
    const val SLIDE_DP = 12

    /**
     * Fixed width of the bottom bar's page label: the row minus [LABEL_RESERVE_DP] on both sides, so a label centred on
     * the full width can never run under the right cluster (720 px at density 2 → 288 px).
     */
    fun labelMaxWidth(rowW: Int, density: Float): Int =
        (rowW - 2 * Math.round(LABEL_RESERVE_DP * density)).coerceAtLeast(0)

    /**
     * True when the history row (above the bottom panel, U §3.4) must use its short side labels ("‹ 12345" /
     * "23259 ›"): the row is three equal columns (the mark · 지우기 · the other place), and a side label ([left],
     * [right]: text + paddings + glyph, 0 when hidden) must fit its own third of [rowW].
     */
    fun stripShort(left: Float, right: Float, rowW: Float): Boolean = maxOf(left, right) > rowW / 3f

    /** The top-row bookmark is shown only when the bar is at least [BOOKMARK_MIN_ROW_DP] wide. */
    fun bookmarkFits(rowW: Int, density: Float): Boolean = rowW >= Math.round(BOOKMARK_MIN_ROW_DP * density)

    /**
     * The bars fade in and out only when their look allows motion (a phone, ChromePalette.motion) and the system
     * animates: an animator duration scale of 0 (개발자 옵션, or 접근성 "애니메이션 제거") shows and hides them at once.
     */
    fun animates(motion: Boolean, durationScale: Float): Boolean = motion && durationScale > 0f
}
