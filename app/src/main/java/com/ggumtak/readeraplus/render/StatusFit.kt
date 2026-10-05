package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusBands

/**
 * U2: the status bands in px (pure). Since 2026-10-05 each band has a place of its own at its screen edge (heights in
 * whole dp from the settings alone: [StatusBands]), and the text box makes room for them: it starts the top margin below
 * the header's band and ends the bottom margin above the footer's (`LayoutKeys.geometry`; user: "위 여백은 위 아래
 * 애들을 제외하고 본문영역에서만 계산해야지"). This replaces U2's rule that the text box never makes room for the bands,
 * which put them inside the margins, shrank or hid them in small ones and needed the settings' '가려짐' note. Both bands
 * draw at the chosen size (the renderer makes it smaller once only when its glyphs are taller than the band's glyph box:
 * [fitTextPx]): the header's glyph box [EDGE_DP] below the page view's top, or, below a display cutout's band (the
 * S25's camera hole in fullscreen), centred between that band and the text box as on the user's screenshot of the
 * installed build ([headerBaseline]); the footer's just above the progress lane or the bottom edge gap
 * ([footerBaseline]); the progress line and its dots low in the lane, as ReadEra draws them ([ProgressMath]).
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

    /**
     * The status glyphs whose ink the renderer measures once: a parenthesis (the tallest and lowest in most fonts), a
     * Hangul syllable, a descender and a digit.
     */
    const val INK_SAMPLE = "(가g0"

    /** [dp] in whole px. */
    fun px(dp: Int, density: Float): Int = Math.round(dp * density)

    /** [EDGE_DP] in whole px. */
    fun edgePx(density: Float): Int = px(EDGE_DP, density)

    /**
     * Top of the progress lane above the page view's bottom ([EDGE_DP] + [LANE_DP]), above the progress line's dots
     * ([ProgressMath.dotTop]): the return chip sits above it.
     */
    fun laneTopPx(density: Float): Int = px(EDGE_DP + LANE_DP, density)

    /** The status glyph box of [s] in whole px ([StatusBands.glyphDp]). */
    fun glyphPx(s: ReaderSettings, density: Float): Int = px(StatusBands.glyphDp(s), density)

    /** The header's band of [s] in px (0 without header items). */
    fun headerBandPx(s: ReaderSettings, density: Float): Int = px(StatusBands.headerDp(s), density)

    /** The footer's band of [s] in px (0 without footer items and progress line). */
    fun footerBandPx(s: ReaderSettings, density: Float): Int = px(StatusBands.footerDp(s), density)

    /**
     * Header baseline. [ascentPx] / [descentPx]: the status paint's font metrics; [inkTopPx] (negative: above the
     * baseline) / [inkBottomPx]: the ink of its tallest glyphs (the renderer measures them once), never taller than
     * [glyphPx] ([fitTextPx]).
     * - No cutout ([cutoutTop] 0: the Comet, a phone with its bars): the glyph box starts [EDGE_DP] below the page view's
     *   top, MaruViewer's line; the font's ascent below the box's top (the Comet's glyphs ≈ 5 dp from the top).
     * - Below a display cutout's band (the S25's camera hole in fullscreen, [cutoutTop] px): the font box centred between
     *   that band and the text box at [contentTop], as the installed build placed it (user, 2026-10-05: "최대한 이거랑 여백
     *   넓이랑 그리고 여백 알고리즘을 따라해봐"; the screenshot's header ink is rows 147–181 over the text box at 207). The
     *   header's band is still reserved below the cutout, so a small top margin only brings it closer to the text.
     * Either way the baseline then moves only as far as needed to keep the ink inside its room: the glyph box, or the
     * paper from [EDGE_DP] below the cutout to [PAD_DP] above the text box.
     */
    fun headerBaseline(
        cutoutTop: Float, contentTop: Float, ascentPx: Float, descentPx: Float, inkTopPx: Float, inkBottomPx: Float,
        glyphPx: Float, density: Float,
    ): Float {
        val top = cutoutTop + edgePx(density)
        if (!(cutoutTop > 0f)) return inside(top + ascentPx, top, top + glyphPx, inkTopPx, inkBottomPx)
        val centred = (cutoutTop + contentTop) / 2f + (ascentPx - descentPx) / 2f
        return inside(centred, top, contentTop - px(PAD_DP, density), inkTopPx, inkBottomPx)
    }

    /**
     * Footer baseline in a page view whose bottom is [viewBottom]: the glyph box's bottom [PAD_DP] above the progress
     * lane while [lane] is on, else on the [EDGE_DP] gap; the font's descent above the box's bottom, the ink kept inside
     * the box (as in [headerBaseline]).
     */
    fun footerBaseline(
        viewBottom: Float, lane: Boolean, descentPx: Float, inkTopPx: Float, inkBottomPx: Float, glyphPx: Float,
        density: Float,
    ): Float {
        val bottom = viewBottom - px(EDGE_DP + (if (lane) LANE_DP + PAD_DP else 0), density)
        return inside(bottom - descentPx, bottom - glyphPx, bottom, inkTopPx, inkBottomPx)
    }

    /**
     * The status text size the renderer draws: [textPx], the chosen size, unless the ink of its tallest glyphs
     * ([inkPx] high at that size) is taller than the band's glyph box [glyphPx] (a large system font scale: the bands
     * count the settings' sp only); then the size whose ink just fits. The text size, not just the baseline, so the
     * glyphs never reach the margin, the lane or the bezel's rows.
     */
    fun fitTextPx(textPx: Float, inkPx: Float, glyphPx: Float): Float =
        if (inkPx > glyphPx && glyphPx > 0f) textPx * glyphPx / inkPx else textPx

    /**
     * [baseline], moved only as far as needed to keep the ink ([inkTopPx] .. [inkBottomPx] around it) between [top] and
     * [bottom]; the top wins if the ink is taller than the room ([fitTextPx] keeps it from being taller than the box).
     */
    private fun inside(baseline: Float, top: Float, bottom: Float, inkTopPx: Float, inkBottomPx: Float): Float =
        maxOf(top - inkTopPx, minOf(baseline, bottom - inkBottomPx))
}
