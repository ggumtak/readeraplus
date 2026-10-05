package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusBands

/**
 * U2: the status bands in px (pure). Since 2026-10-05 each band has a place of its own at its screen edge (heights in
 * whole dp from the settings alone: [StatusBands]), and the text box makes room for them: it starts the top margin below
 * the header's band and ends the bottom margin above the footer's (`LayoutKeys.geometry`; user: "위 여백은 위 아래
 * 애들을 제외하고 본문영역에서만 계산해야지"). This replaces U2's rule that the text box never makes room for the bands,
 * which put them inside the margins, shrank or hid them in small ones and needed the settings' '가려짐' note. Both bands
 * draw at the chosen size: the header's glyph box [EDGE_DP] below its band's top (the page view's top, or the bottom of a
 * display cutout's band: [headerBaseline]), the footer's just above the progress lane or the bottom edge gap
 * ([footerBaseline]), the progress line at the bottom of the lane ([laneBottomPx]).
 */
internal object StatusFit {
    /** Paper kept between the status glyphs and the margin or the progress lane. */
    const val PAD_DP = StatusBands.PAD_DP
    /** Progress lane height at the bottom edge (UI_SPEC PROGRESS_LANE_DP). */
    const val LANE_DP = StatusBands.LANE_DP
    /**
     * Paper kept between the status and the screen's edges: small e-ink readers hide the panel's outer pixel rows
     * under the bezel. On the Comet (5.84", 720×1440) the progress dot, drawn 0.5 mm from the edge, was cut (user,
     * 2026-10-04). At the bottom it is kept below the footer text and the progress line; at the top it is where the
     * header's glyph box starts (MaruViewer's glyphs ≈ 5 dp from the top).
     */
    const val EDGE_DP = StatusBands.EDGE_DP

    /** [dp] in whole px. */
    fun px(dp: Int, density: Float): Int = Math.round(dp * density)

    /** [EDGE_DP] in whole px. */
    fun edgePx(density: Float): Int = px(EDGE_DP, density)

    /** The progress lane: [LANE_DP] in whole px, always (it no longer shrinks in a small margin). */
    fun lanePx(density: Float): Int = px(LANE_DP, density)

    /** Bottom of the progress lane in a page view [viewH] tall: the [EDGE_DP] gap above the view's bottom. */
    fun laneBottomPx(viewH: Int, density: Float): Int = viewH - edgePx(density)

    /** Top of the progress lane above the page view's bottom ([EDGE_DP] + [LANE_DP]): the return chip sits above it. */
    fun laneTopPx(density: Float): Int = px(EDGE_DP + LANE_DP, density)

    /** The status glyph box of [s] in whole px ([StatusBands.glyphDp]). */
    fun glyphPx(s: ReaderSettings, density: Float): Int = px(StatusBands.glyphDp(s), density)

    /** The header's band of [s] in px (0 without header items). */
    fun headerBandPx(s: ReaderSettings, density: Float): Int = px(StatusBands.headerDp(s), density)

    /** The footer's band of [s] in px (0 without footer items and progress line). */
    fun footerBandPx(s: ReaderSettings, density: Float): Int = px(StatusBands.footerDp(s), density)

    /**
     * Header baseline: the glyph box [EDGE_DP] below [bandTop] (the page view's top, or below a display cutout's band),
     * [ascentPx] / [descentPx] the status paint's at the chosen size. A font taller than the band's glyph box [glyphPx]
     * (only with a system font scale above 1) keeps its proportions inside the box instead of reaching into the margin.
     */
    fun headerBaseline(bandTop: Float, ascentPx: Float, descentPx: Float, glyphPx: Float, density: Float): Float =
        bandTop + edgePx(density) + ascentPx * squeeze(ascentPx, descentPx, glyphPx)

    /**
     * Footer baseline in a page view whose bottom is [viewBottom]: the glyph box's bottom [PAD_DP] above the progress
     * lane while [lane] is on, else on the [EDGE_DP] gap; [ascentPx] / [descentPx] and [glyphPx] as in [headerBaseline].
     */
    fun footerBaseline(viewBottom: Float, lane: Boolean, ascentPx: Float, descentPx: Float, glyphPx: Float, density: Float): Float =
        viewBottom - px(EDGE_DP + (if (lane) LANE_DP + PAD_DP else 0), density) - descentPx * squeeze(ascentPx, descentPx, glyphPx)

    /** Share of a status glyph box [ascentPx] + [descentPx] that fits a band's [glyphPx] (1 when it fits). */
    private fun squeeze(ascentPx: Float, descentPx: Float, glyphPx: Float): Float {
        val box = ascentPx + descentPx
        return if (box > glyphPx && glyphPx > 0f) glyphPx / box else 1f
    }
}
