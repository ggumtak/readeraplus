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
 * [fitTextPx]): the header's glyph box [EDGE_DP] below the page view's top, also over a display cutout's band (the S25's
 * camera hole in fullscreen: MaruViewer's line at the very top, [headerBaseline]; its band stays reserved below the
 * cutout, so the text box keeps its place); the footer's just above the progress lane or the bottom edge gap
 * ([footerBaseline]); the progress line and its dots low in the lane, as ReadEra draws them ([ProgressMath]). The header
 * spans the page view less its own side insets ([sideInset]: the display's rounded corners, not the text column), the
 * footer the text column.
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
     * MaruViewer's status ink on the S25 in fullscreen starts ≈ 5 dp below the screen's top (rows 14–15 of 2340 at 3 px
     * per dp, inside the camera band): where the header's ink starts over a display cutout's band ([headerBaseline]).
     */
    const val INK_TOP_DP = 5

    /**
     * Header baseline. [ascentPx]: the status paint's font ascent; [inkTopPx] (negative: above the baseline) /
     * [inkBottomPx]: the ink of its tallest glyphs (the renderer measures them once), never taller than [glyphPx]
     * ([fitTextPx]). The glyph box starts [EDGE_DP] below the page view's top, MaruViewer's line:
     * - No cutout ([cutoutTop] 0: the Comet, a phone with its bars): the font's ascent below the box's top (the Comet's
     *   glyphs ≈ 5 dp from the top).
     * - Over a display cutout's band (the S25's camera hole in fullscreen, [cutoutTop] px): the ink [INK_TOP_DP] below the
     *   top, as MaruViewer draws it (user, 2026-10-05: "마루처럼 아예 이렇게 맨 위까지 올라가는 건 안 돼?"; its ink rows
     *   14–47). The ink, not the font box: the phone's UI font reports a box far taller than its glyphs (≈ 81 px at
     *   33 px), which would put them low in the glyph box. The header's band stays reserved below the cutout (only paper
     *   is drawn there), so the text box keeps its place.
     * Either way the baseline then moves only as far as needed to keep the ink inside the glyph box.
     */
    fun headerBaseline(
        cutoutTop: Float, ascentPx: Float, inkTopPx: Float, inkBottomPx: Float, glyphPx: Float, density: Float,
    ): Float {
        val top = edgePx(density).toFloat()
        val baseline = if (cutoutTop > 0f) px(INK_TOP_DP, density) - inkTopPx else top + ascentPx
        return inside(baseline, top, top + glyphPx, inkTopPx, inkBottomPx)
    }

    /**
     * The header's own side inset (dp), MaruViewer's (user, 2026-10-05: "윗줄은 좌우여백에 영향을 받지 않고 라운드형태의
     * 디스플레이의 모서리인 것만 고려해서 좌우여백을 자체적으로 살짝만 두고"): its status line on the S25 runs from x 46 to
     * 1032 of 1080 (≈ 15 dp each side) whatever its text margins. The same 15 dp (4.2 % of the width on both devices, as
     * the side margins' 20 dp is 5.6 %) keeps the Comet's header clear of the bezel over the panel's outer columns, and the
     * S25's at MaruViewer's place whatever corner it reports. A display corner that needs more ([cornerClearance]) wins.
     */
    const val SIDE_DP = 15

    /**
     * The side inset when the display's corners are unknown (before API 31, no `WindowInsets.getRoundedCorner`): room
     * for a rounded corner of up to ≈ 50 dp at the glyphs' middle (≈ 12 dp below the top).
     */
    const val SIDE_UNKNOWN_DP = 18

    /**
     * Px a display's rounded corner takes from a row [y] px below the page view's top, measured from the view's side: the
     * corner is a quarter circle of [radius] px whose centre lies [centreIn] px inside that side and [centreY] px below the
     * top (window px made relative to the view: `InsetSplit.pageCorners`). 0 at or below the centre's row and for a square
     * corner ([radius] ≤ 0); above the arc's top row the whole corner. Whole px, rounded up.
     */
    fun cornerClearance(radius: Float, centreIn: Float, centreY: Float, y: Float): Float {
        if (!(radius > 0f)) return 0f
        val dy = centreY - y
        if (!(dy > 0f)) return 0f
        val chord = if (dy >= radius) 0.0 else Math.sqrt((radius * radius - dy * dy).toDouble())
        return maxOf(0.0, Math.ceil(centreIn - chord - 1e-3)).toFloat()
    }

    /**
     * The header's side inset in px at [density]: [SIDE_DP], or the [cornerClearance] of that side's display corner at
     * [y] (the glyphs' vertical middle) where that is more; [SIDE_UNKNOWN_DP] when the corner is unknown ([radius] < 0).
     * With the bars shown the header is far below the corner (base only); in split screen a window corner that is no
     * display corner reports none.
     */
    fun sideInset(radius: Float, centreIn: Float, centreY: Float, y: Float, density: Float): Float =
        if (radius < 0f) px(SIDE_UNKNOWN_DP, density).toFloat()
        else maxOf(px(SIDE_DP, density).toFloat(), cornerClearance(radius, centreIn, centreY, y))

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
