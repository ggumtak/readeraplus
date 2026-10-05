package com.ggumtak.readeraplus.render

/**
 * The progress line's pixel geometry (pure), shared by the renderer and the status-change detection. Since 2026-10-05
 * it copies ReadEra's 탐색줄 on the user's S25 screenshots (1080×2340, 3 px per dp; user: "대놓고 빡!! 하고 보이는 게
 * 아니라 있었구나 하면서 볼 정도로 사진을 최대한 카피해"): a [LINE_DP] line (2 px, rows 2315–2316) between two end dots,
 * and the position dot, all three dots alike ([DOT_DP]: 14 px, rows 2309–2322), across the whole page view: the end
 * dots' outer edges [SIDE_DP] from its sides (x 21–34 and 1045–1058), their centres [CENTRE_DP] above its bottom (24 px).
 * The line runs from one end dot's centre to the other's, under the dots; the position dot is at the fraction read
 * between those centres. The colours are the palette's ([PagePalette.progressLine], [PagePalette.progressDot]).
 *
 * Whole pixels throughout, so every edge is crisp: the line's height and the dot's diameter share their parity, so both
 * centre on the same row boundary (S25: 2 and 14 px) or pixel middle (Comet, 720×1440 at 2 px per dp: 1 px and a 9 px
 * dot, the nearest odd size to 9.3 px), and the position dot moves in whole pixels. All of it sits in the footer band's
 * lane (`StatusBands`: [StatusFit.EDGE_DP] above the bottom, [StatusFit.LANE_DP] high), so the band, and with it the
 * text box, keeps its height; the dots stay ≥ [StatusFit.EDGE_DP] clear of the bottom (the Comet's bezel hides its outer
 * rows) and under the lane's top, which the return chip sits above.
 */
internal object ProgressMath {
    /** The line's height: 2 px on the S25. */
    const val LINE_DP = 2f / 3f
    /** One diameter for the end dots and the position dot: 14 px on the S25. */
    const val DOT_DP = 14f / 3f
    /** From each side of the page view to the outer edge of the end dot there: 21 px on the S25. */
    const val SIDE_DP = 7
    /** From the page view's bottom up to the dots' and the line's centre: 24 px on the S25. */
    const val CENTRE_DP = 8

    /** The line's height in whole px, never under 1: 2 on the S25, 1 on the Comet. */
    fun lineH(density: Float): Int = maxOf(1, Math.round(LINE_DP * density))

    /**
     * The dots' diameter in whole px: the size nearest [DOT_DP] with [lineH]'s parity, so the line and the dots share a
     * centre that is either a row boundary or a pixel's middle: 14 on the S25, 9 on the Comet (4.5 dp).
     */
    fun dotD(density: Float): Int {
        val line = lineH(density)
        return line + 2 * Math.round((DOT_DP * density - line) / 2f).coerceAtLeast(0)
    }

    /** The line's first row in a page view [viewH] tall: 2315 on the S25 (2340 px), 1423 on the Comet (1440 px). */
    fun lineTop(viewH: Int, density: Float): Int = viewH - Math.round(CENTRE_DP * density + lineH(density) / 2f)

    /** The dots' first row: centred on the line (S25 2309, Comet 1419). */
    fun dotTop(viewH: Int, density: Float): Int = lineTop(viewH, density) - (dotD(density) - lineH(density)) / 2

    /** The centre of the line and the dots (y): S25 2316.0 (between rows 2315 and 2316), Comet 1423.5. */
    fun centreY(viewH: Int, density: Float): Float = lineTop(viewH, density) + lineH(density) / 2f

    /** [SIDE_DP] in whole px: the left end dot's first column. */
    fun sidePx(density: Float): Int = Math.round(SIDE_DP * density)

    /**
     * Pixels the position dot travels in a page view [viewW] wide, from the left end dot's place to the right one's
     * (S25 1024, Comet 683); 0 when the view is too narrow.
     */
    fun trackPx(viewW: Int, density: Float): Int = (viewW - 2 * sidePx(density) - dotD(density)).coerceAtLeast(0)

    /**
     * First column of the dot at fraction [f] (0..1) of the track, in whole px; 0 and 1 are the end dots (S25 21 and
     * 1045). The status model counts the same `round(f · track)`, so the dot "moved" exactly when this changes.
     */
    fun dotLeft(f: Float, viewW: Int, density: Float): Int =
        sidePx(density) + Math.round(f.coerceIn(0f, 1f) * trackPx(viewW, density))

    /** Centre (x) of the dot at fraction [f]: the end dots' are the line's ends (S25 28.0 and 1052.0). */
    fun dotX(f: Float, viewW: Int, density: Float): Float = dotLeft(f, viewW, density) + dotD(density) / 2f
}
