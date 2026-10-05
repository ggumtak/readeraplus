package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.settings.ReaderSettings

/**
 * U2: fitting the status bands into the page margins (px; pure). The text box never makes room for them. Since
 * 2026-10-05 the bands hug the screen edges like MaruViewer's status line (user: "제목이 좀 위에 딱 달라붙어있었으면"):
 * the header's glyph box sits [EDGE_DP] below the top edge ([headerBaseline]), the footer's just above the progress
 * lane or the bottom edge gap ([footerBaseline]); neither is centred in its margin any more.
 */
internal object StatusFit {
    /** Paper kept above and below the status glyphs. */
    const val PAD_DP = 2f
    /** Smallest status text drawn; a band too small even for it stays empty. */
    const val MIN_SP = 7f
    /** Progress lane height at the bottom edge (UI_SPEC PROGRESS_LANE_DP). */
    const val LANE_DP = ReaderSettings.PROGRESS_LANE_DP * 1f
    /** Below this bottom margin (above [EDGE_DP]) the progress line is not drawn. */
    const val LANE_MIN_DP = 6f
    /**
     * Paper kept between the status and the screen's edges: small e-ink readers hide the panel's outer pixel rows
     * under the bezel. On the Comet (5.84", 720×1440) the progress dot, drawn 0.5 mm from the edge, was cut (user,
     * 2026-10-04). At the bottom it is kept below the footer text and the progress line; at the top it is where the
     * header's glyph box starts (MaruViewer's glyphs ≈ 5 dp from the top). Status geometry only: the text box never
     * moves for it.
     */
    const val EDGE_DP = 4f
    /** Ascent + descent per px of text size assumed for dp estimates in the settings UI (CJK system fonts ≈ 1.45). */
    const val GLYPH_EM = 1.45f

    /**
     * Status text size for a band [roomPx] tall: [wantPx] when its glyph box ([glyphPerPx] × size) plus 2 × [padPx]
     * fits, else the largest size that fits, or 0 (draw nothing) when that is below [minPx].
     */
    fun size(wantPx: Float, roomPx: Float, glyphPerPx: Float, padPx: Float, minPx: Float): Float {
        if (!(wantPx > 0f) || !(roomPx > 0f) || !(glyphPerPx > 0f)) return 0f
        val fit = (roomPx - 2f * padPx) / glyphPerPx
        if (wantPx <= fit) return wantPx
        return if (fit >= minPx) fit else 0f
    }

    /** [EDGE_DP] in whole px. */
    fun edgePx(density: Float): Int = Math.round(EDGE_DP * density)

    /**
     * The edge gap a bottom margin of [marginPx] keeps: [edgePx], less in a margin too small for it and the smallest
     * progress lane ([LANE_MIN_DP]), so the bar still shows wherever it showed before the gap.
     */
    fun edgeGapPx(marginPx: Float, density: Float): Int =
        minOf(edgePx(density), maxOf(0, Math.floor((marginPx - LANE_MIN_DP * density).toDouble()).toInt()))

    /**
     * Height of the progress lane in [marginPx], the bottom margin above the [EDGE_DP] gap: min(12 dp, margin), 0
     * under [LANE_MIN_DP].
     */
    fun lane(marginPx: Float, density: Float): Float =
        if (marginPx < LANE_MIN_DP * density) 0f else minOf(LANE_DP * density, marginPx)

    /**
     * The room [size] gets for the header above a text box whose top is [contentTopPx] below the page view's top edge:
     * the glyph box starts [EDGE_DP] below the edge and keeps [PAD_DP] above the text ([size] counts a pad on each
     * side, so the edge gap replaces the top one).
     */
    fun headerRoom(contentTopPx: Float, density: Float): Float = contentTopPx - edgePx(density) + PAD_DP * density

    /** Header baseline: the glyph box's top [EDGE_DP] below the page view's top edge; [ascentPx] at the drawn size. */
    fun headerBaseline(ascentPx: Float, density: Float): Float = edgePx(density) + ascentPx

    /**
     * Footer baseline: the glyph box's bottom [PAD_DP] above a progress lane [lanePx] tall, or at [edgeBottomPx] (the
     * view's bottom less the edge gap) without one; [descentPx] at the drawn size. [size] of the room between the text
     * box and the lane keeps the glyphs below the text.
     */
    fun footerBaseline(edgeBottomPx: Float, lanePx: Float, descentPx: Float, density: Float): Float =
        edgeBottomPx - (if (lanePx > 0f) lanePx + PAD_DP * density else 0f) - descentPx

    /** Settings-UI estimate (dp): does a [statusSp] band show in a [marginDp] margin minus [laneDp]? */
    fun fitsDp(statusSp: Float, marginDp: Int, laneDp: Float): Boolean =
        marginDp - laneDp >= MIN_SP * GLYPH_EM + 2f * PAD_DP

    /**
     * [fitsDp] for the header ([headerRoom]): the glyph box starts [EDGE_DP] below the top edge. [cutoutDp]: a display
     * cutout band above the margin that the page view reaches into (the S25's camera band in fullscreen, about 37 dp;
     * 0 without one, as on the Comet): the header draws there too, so it adds room.
     */
    fun headerFitsDp(statusSp: Float, marginDp: Int, cutoutDp: Int = 0): Boolean =
        fitsDp(statusSp, marginDp + cutoutDp.coerceAtLeast(0), EDGE_DP - PAD_DP)

    /** [fitsDp] for the footer: its margin also keeps the [EDGE_DP] gap and, with the progress bar on, the lane. */
    fun footerFitsDp(statusSp: Float, marginDp: Int, progressBar: Boolean): Boolean =
        fitsDp(statusSp, marginDp, (if (progressBar) LANE_DP else 0f) + EDGE_DP)
}
