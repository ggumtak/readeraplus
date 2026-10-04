package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.settings.ReaderSettings

/** U2: fitting the status bands into the page margins (px; pure). The text box never makes room for them. */
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
     * Paper kept between the bottom status (footer text, progress line) and the screen's bottom edge: small e-ink
     * readers hide the panel's last pixel rows under the bezel. On the Comet (5.84", 720×1440) the progress dot, drawn
     * 0.5 mm from the edge, was cut (user, 2026-10-04). Status geometry only: the text box never moves for it.
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

    /** Settings-UI estimate (dp): does a [statusSp] band show in a [marginDp] margin minus [laneDp]? */
    fun fitsDp(statusSp: Float, marginDp: Int, laneDp: Float): Boolean =
        marginDp - laneDp >= MIN_SP * GLYPH_EM + 2f * PAD_DP

    /** [fitsDp] for the footer: its margin also keeps the [EDGE_DP] gap and, with the progress bar on, the lane. */
    fun footerFitsDp(statusSp: Float, marginDp: Int, progressBar: Boolean): Boolean =
        fitsDp(statusSp, marginDp, (if (progressBar) LANE_DP else 0f) + EDGE_DP)
}
