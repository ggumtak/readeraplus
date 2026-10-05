package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.settings.PageTheme
import com.ggumtak.readeraplus.settings.ReaderSettings

/**
 * The page's colours for [ReaderSettings.pageTheme] and [ReaderSettings.invert] (pure, unit-tested; three shared
 * instances, so asking for one allocates nothing). [PageRenderer] paints with it (page, text, status lines, the text
 * shadow, highlight greys); the reader takes the window and blank-page colour, the selection handles and the e-ink
 * cadence from it. 흑백 반전 wins over the theme: [NIGHT] is the look of T1-3 (white on black, pictures inverted).
 */
internal class PagePalette private constructor(
    val background: Int,
    val text: Int,
    /** Status lines: their texts, the battery icon and the progress line. */
    val status: Int,
    /** Text shadow toward the lower right, in dp; a [shadowSigmaDp] of 0 is no shadow. */
    val shadowDxDp: Float,
    val shadowDyDp: Float,
    /**
     * The shadow's blur as the Gaussian's standard deviation in dp (half a CSS blur radius): what a screenshot
     * measures. It is not Paint.setShadowLayer's radius; [shadowRadiusPx] converts.
     */
    val shadowSigmaDp: Float,
    val shadowColor: Int,
    /** A dark page: night quote fills, the e-ink night cadence (AppSettings.einkRefreshEveryNight). */
    val dark: Boolean,
    /** Pictures are drawn through the night filter (inverted), so no white box glares on the black page. */
    val invertImages: Boolean,
) {
    val hasShadow: Boolean get() = shadowSigmaDp > 0f && (shadowColor ushr 24) != 0

    /** Paint.setShadowLayer's radius in px for this page's blur at [density]; 0 (no shadow) without one. */
    fun shadowRadiusPx(density: Float): Float = if (hasShadow) radiusForSigma(shadowSigmaDp * density) else 0f

    /**
     * Grey [v] of the white page (0 = black … 255 = white: selection, search and TTS fills, the ink quote greys) on
     * this page: the same share of the way from the background to the text colour. [PAPER] gives back `rgb(v, v, v)`
     * and [NIGHT] `rgb(255 - v, …)`, exactly what the renderer drew before the themes.
     */
    fun grey(v: Int): Int {
        val t = 255 - v.coerceIn(0, 255)
        return OPAQUE or (mix(background shr 16, text shr 16, t) shl 16) or
            (mix(background shr 8, text shr 8, t) shl 8) or mix(background, text, t)
    }

    private fun mix(bg: Int, fg: Int, t: Int): Int {
        val b = bg and 0xFF
        return b + Math.round(((fg and 0xFF) - b) * t / 255f)
    }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()

        /** Black on white (the default). */
        val PAPER = PagePalette(
            background = 0xFFFFFFFF.toInt(), text = OPAQUE, status = OPAQUE,
            shadowDxDp = 0f, shadowDyDp = 0f, shadowSigmaDp = 0f, shadowColor = 0, dark = false, invertImages = false,
        )

        /** 흑백 반전: white on black, pictures inverted, no shadow (whatever the theme). */
        val NIGHT = PagePalette(
            background = OPAQUE, text = 0xFFFFFFFF.toInt(), status = 0xFFFFFFFF.toInt(),
            shadowDxDp = 0f, shadowDyDp = 0f, shadowSigmaDp = 0f, shadowColor = 0, dark = true, invertImages = true,
        )

        /**
         * MaruViewer's page, measured on the user's 1080 px phone screenshot (2026-10-04): a flat #323232, neutral
         * #DDDDDD text, the status line in light gold. The text shadow is a fit of a shifted, Gaussian-blurred copy
         * of the glyphs to five text blocks (residual ≈ 1 grey level): ≈ 2.2 px right, ≈ 1.1 px down, sigma ≈ 1.25 px,
         * black at ≈ 88 %; at ≈ 2.6–3 px per dp that is 0.8 / 0.4 / 0.45 dp. Pictures keep their colours (a colour
         * theme, not a night mode); a transparent one shows the page through it.
         * Re-checked 2026-10-05 against our own page beside MaruViewer on the same phone (S25, 3 px per dp; the mean
         * darkening around the glyphs, fitted with the same model): our shadow reached ≈ 0.4 px further right; MaruViewer
         * is ≈ 2.0 px right, 1.2 px down, same blur and strength, so dx is 0.67 dp (fit residual at the noise floor).
         */
        val MARU = PagePalette(
            background = 0xFF323232.toInt(), text = 0xFFDDDDDD.toInt(), status = 0xFFF0D096.toInt(),
            shadowDxDp = 0.67f, shadowDyDp = 0.4f, shadowSigmaDp = 0.45f, shadowColor = 0xE0000000.toInt(),
            dark = true, invertImages = false,
        )

        /** Android blurs a shadow by sigma = [SIGMA_PER_RADIUS] · radius + [MIN_SIGMA_PX] px. */
        private const val SIGMA_PER_RADIUS = 0.57735f
        private const val MIN_SIGMA_PX = 0.5f

        /**
         * The Paint.setShadowLayer radius (px) that blurs by [sigmaPx]. The radius is not the blur's reach: Android
         * (HWUI's Blur::convertRadiusToSigma, on hardware and bitmap canvases alike) draws a Gaussian of
         * sigma = 0.57735 · radius + 0.5 px, so 0.5 px is the sharpest shadow it draws and a radius of 0 draws none.
         */
        fun radiusForSigma(sigmaPx: Float): Float = maxOf(0.01f, (sigmaPx - MIN_SIGMA_PX) / SIGMA_PER_RADIUS)

        /**
         * True when [a] and [b] differ at most in a 화면 색 that 흑백 반전 hides: both draw the same page, so the
         * change needs no repaint (no thumbnail redraw, no e-ink update).
         */
        fun drawSame(a: ReaderSettings, b: ReaderSettings): Boolean = of(a) === of(b) && a.copy(pageTheme = b.pageTheme) == b

        fun of(s: ReaderSettings): PagePalette = of(s.pageTheme, s.invert)

        fun of(theme: PageTheme, invert: Boolean): PagePalette = when {
            invert -> NIGHT
            theme == PageTheme.MARU -> MARU
            else -> PAPER
        }
    }
}
