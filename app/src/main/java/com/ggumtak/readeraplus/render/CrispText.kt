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
     * nothing else. Without LINEAR_TEXT_FLAG the font's hinting is applied and advances are whole px (minikin lays out a
     * non-linear paint at `(int) textSize`; the glyphs are drawn at the paint's own size, hence [paintTextPx]); without
     * SUBPIXEL_TEXT_FLAG Skia puts every glyph on a whole pixel (its origin rounded), as MaruViewer and TextView do, so
     * each glyph lies within half a pixel of its layout x and the gaps between the words or letters of a justified line
     * can differ by 1 px; a shadow offset of whole px ([shadowOffsetPx]) is the same under every glyph. The status lines
     * keep their own paint (already hinted).
     *
     * Letter spacing (`Paint.letterSpacing`, the 글자 간격 setting) is whole px per glyph on such a paint, as in TextView:
     * minikin (LayoutCore) rounds `letterSpacing × (int) textSize` when LinearMetrics is off. The setting's 1 % steps are
     * therefore not linear: at 47 px (17 sp on the S25) ±1 % (0.47 px) draws nothing and +2 % / +3 % both draw 1 px; at
     * 34 px (the Comet) +2 % to +4 % all draw 1 px. Measuring uses the same paint, so text never overlaps. The presets
     * use 0. (Linear spacing would need the layout to place every glyph itself: one draw call per glyph.)
     */
    const val PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG

    /** Lets a size that is whole in decimal (1.05 × 60) stay whole although its float product is a hair below it. */
    private const val WHOLE_SLACK = 0.001

    /**
     * The text size (px) a body paint gets for [px] (the em times the run's scale): whole pixels, rounded down, at least 1.
     * Load-bearing, not cosmetic: minikin lays out a non-linear paint at `(int) textSize`
     * (`MinikinUtils::prepareMinikinPaint`), but hwui draws the glyphs at the paint's own size
     * (`MinikinFontSkia::populateSkFont` sets the typeface, embolden and skew, never the size), and FreeType hints a
     * TrueType font whose head.flags bit 3 is set (both NanumMyeongjo files: 0b11111) at the rounded ppem. Unrounded,
     * 47.81 px would draw 48-ppem glyphs on 47-px advances: tighter, possibly touching, unlike MaruViewer's. Whole, the
     * drawn glyphs, their advances, the font metrics, the synthetic stroke and the underline all use one size: 17 sp on
     * the S25 (2.8125 px per dp) is 47.81 px, drawn at 47 like MaruViewer's; the Comet's sizes (2 px per dp, 0.5 sp
     * steps) are whole already. The layout's em (line height, indents, margins in em) stays the unrounded size.
     */
    fun textPx(px: Float): Float {
        if (!(px > 1f) || px.isInfinite()) return 1f
        return maxOf(1f, Math.floor(px + WHOLE_SLACK).toFloat())
    }

    /**
     * The text size of a body paint for a run at [sizeScale] (RunStyle.sizeScale; nonsense is 1, kept to 0.3–4) of the
     * layout's em [emPx]: [textPx] of the product. `AndroidTextMeasurer.createPaint` sets exactly this, so the tests hold
     * the drawn size whole at the S25's fractional density ([textPx] says why it must be).
     */
    fun paintTextPx(emPx: Float, sizeScale: Float): Float = textPx(emPx * runScale(sizeScale))

    /** A run's size scale as the paints use it: 1 for nonsense, else kept to 0.3–4. */
    fun runScale(s: Float): Float = if (s > 0f && s.isFinite()) s.coerceIn(0.3f, 4f) else 1f

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
