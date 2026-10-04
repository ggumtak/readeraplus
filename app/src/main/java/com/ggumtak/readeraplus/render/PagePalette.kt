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
    /** Text shadow toward the lower right, in dp; a [shadowRadiusDp] of 0 is no shadow. */
    val shadowDxDp: Float,
    val shadowDyDp: Float,
    val shadowRadiusDp: Float,
    val shadowColor: Int,
    /** A dark page: night quote fills, the e-ink night cadence (AppSettings.einkRefreshEveryNight). */
    val dark: Boolean,
    /** Pictures are drawn through the night filter (inverted), so no white box glares on the black page. */
    val invertImages: Boolean,
) {
    val hasShadow: Boolean get() = shadowRadiusDp > 0f && (shadowColor ushr 24) != 0

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
            shadowDxDp = 0f, shadowDyDp = 0f, shadowRadiusDp = 0f, shadowColor = 0, dark = false, invertImages = false,
        )

        /** 흑백 반전: white on black, pictures inverted, no shadow (whatever the theme). */
        val NIGHT = PagePalette(
            background = OPAQUE, text = 0xFFFFFFFF.toInt(), status = 0xFFFFFFFF.toInt(),
            shadowDxDp = 0f, shadowDyDp = 0f, shadowRadiusDp = 0f, shadowColor = 0, dark = true, invertImages = true,
        )

        /**
         * MaruViewer's page, measured on the user's 1080 px phone screenshot (2026-10-04): a flat #323232, neutral
         * #DDDDDD text with a nearly black shadow ≈ 3 px right and 1.5–2 px down that falls off within ≈ 1.5 px
         * (≈ 2.6–3 px per dp), the status line in light gold. Pictures keep their colours (a colour theme, not a
         * night mode).
         */
        val MARU = PagePalette(
            background = 0xFF323232.toInt(), text = 0xFFDDDDDD.toInt(), status = 0xFFF0D096.toInt(),
            shadowDxDp = 1.0f, shadowDyDp = 0.6f, shadowRadiusDp = 0.6f, shadowColor = 0xD9000000.toInt(),
            dark = true, invertImages = false,
        )

        fun of(s: ReaderSettings): PagePalette = of(s.pageTheme, s.invert)

        fun of(theme: PageTheme, invert: Boolean): PagePalette = when {
            invert -> NIGHT
            theme == PageTheme.MARU -> MARU
            else -> PAPER
        }
    }
}
