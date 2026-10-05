package com.ggumtak.readeraplus.render

import android.graphics.Paint

/**
 * Body text drawn the way MaruViewer draws it: hinted, at a whole-pixel size, on whole pixels (pure, unit-tested).
 *
 * The user's S25 pairs (2026-10-05, the same TXT page in 나눔명조 400 at 17 sp, white page and MARU page: "뭔가 아직도
 * 마루뷰어가 더 선명한 느낌이야") fit NanumMyeongjo.ttf rendered by FreeType to within 0.5 grey level: MaruViewer's glyphs
 * are the font's own TrueType hinting at 47 px, with whole-px origins and advances (45 px per Hangul syllable, 14 px per
 * space); ours were the unhinted outline at 47.81 px on quarter pixels. The hinting moves points only vertically: it puts
 * horizontal strokes on two full pixel rows and glyph tops on a row boundary (27–43 % fewer mid-grey pixels on
 * horizontal edges; full/lit 0.57–0.60 instead of 0.50–0.53 on white, 0.67 instead of 0.59 on MARU). The cause was one
 * flag: Android draws a paint with LINEAR_TEXT_FLAG unhinted (hwui `Canvas::drawText` forces SkFontHinting::kNone for
 * linear metrics) whatever its hinting setting. Gamma, stem widths, the font file and the page (no layer, no scaling)
 * were the same in both apps.
 */
internal object CrispText {
    /**
     * The body paints' flags (one paint measures and draws a style, [AndroidTextMeasurer.paintFor]): anti-aliased and
     * nothing else. Without LINEAR_TEXT_FLAG the font's hinting is applied and advances are whole px (minikin lays out and
     * draws a non-linear paint at its whole-px size); without SUBPIXEL_TEXT_FLAG Skia puts every glyph on a whole pixel,
     * as MaruViewer and TextView do, so a justified line's extra space lands within half a pixel, and a shadow offset of
     * whole px ([shadowOffsetPx]) is the same under every glyph. The status lines keep their own paint (already hinted).
     */
    const val PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG

    /** Lets a size that is whole in decimal (1.05 × 60) stay whole although its float product is a hair below it. */
    private const val WHOLE_SLACK = 0.001

    /**
     * The text size (px) a body paint gets for [px] (the em times the run's scale): whole pixels, rounded down, at least 1.
     * Android lays out and draws a non-linear paint at `(int) textSize` anyway (MinikinUtils.prepareMinikinPaint), so this
     * only makes the paint's font metrics, synthetic stroke and underline use the size that is drawn: 17 sp on the S25
     * (2.8125 px per dp) is 47.81 px, drawn at 47 like MaruViewer's; the Comet's sizes (2 px per dp, 0.5 sp steps) are
     * whole already. The layout's em (line height, indents, margins in em) stays the unrounded size.
     */
    fun textPx(px: Float): Float {
        if (!(px > 1f) || px.isInfinite()) return 1f
        return maxOf(1f, Math.floor(px + WHOLE_SLACK).toFloat())
    }

    /**
     * A baseline at [y] (canvas px) on a whole pixel row. Skia rounds horizontal text's y itself; rounding first keeps the
     * text shadow's offset the same on every line: drawn at round(y + dy), it was 1 px or 2 px below the text depending on
     * where the line's fractional baseline fell.
     */
    fun baselineY(y: Float): Float = if (y.isFinite()) Math.round(y).toFloat() else y

    /**
     * The text shadow's offset in whole px for [dp] at [density]: the nearest pixel, at least 1 when [dp] is not 0. Glyphs
     * sit on whole pixels ([PAINT_FLAGS], [baselineY]), so a whole-px offset puts every glyph's shadow at the same distance;
     * a fractional one rounded differently glyph by glyph. MaruViewer's is a constant (2, 1) px on the S25.
     */
    fun shadowOffsetPx(dp: Float, density: Float): Float {
        val px = dp * density
        if (!px.isFinite() || px == 0f) return 0f
        val r = Math.round(px)
        return (if (r != 0) r else if (px > 0f) 1 else -1).toFloat()
    }
}
