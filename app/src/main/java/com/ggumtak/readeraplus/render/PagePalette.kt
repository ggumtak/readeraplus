package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.settings.PageTheme
import com.ggumtak.readeraplus.settings.ReaderSettings

/**
 * The page's colours for [ReaderSettings.pageTheme] and [ReaderSettings.invert] (pure, unit-tested; three shared
 * instances, so asking for one allocates nothing). [PageRenderer] paints with it (page, text, status lines, the progress
 * line, the text shadow, highlight greys); the reader takes the window and blank-page colour, the selection handles and
 * the e-ink cadence from it. 흑백 반전 wins over the theme: [NIGHT] is the look of T1-3 (white on black, pictures
 * inverted).
 */
internal class PagePalette private constructor(
    val background: Int,
    val text: Int,
    /**
     * Status lines: their texts and the battery icon (the progress line has its own greys: [progressLine]). Drawn flat,
     * without the text shadow on every look: MaruViewer's own status line has none (measured 2026-10-05, [MARU]). On
     * 흰 바탕 and 흑백 반전 a quiet grey of the page itself since 2026-10-10 ([quietStatus]; user: "상태바도 리디처럼 …
     * 배경색에 따라서 다 은은하게 … 있는 듯 없는 듯"); 마루뷰어 and 검은 바탕 keep MaruViewer's gold #FFD387 (user, the same
     * day: "회색하고 검은색배경은 상태표시줄 원래색으로").
     */
    val status: Int,
    /**
     * Text shadow toward the lower right, in dp; a [shadowSigmaDp] of 0 is no shadow. The renderer draws the offset in
     * whole px (`CrispText.shadowOffsetPx`).
     */
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

    /** A light page by its background's luma: the progress line darkens it, on a dark page it lightens it. */
    private val lightPage = luma(background) >= 128

    /**
     * The progress line and its dots ([ProgressMath]), copied from ReadEra's 탐색줄 on the user's S25 screenshots
     * (2026-10-05: "대놓고 빡!! 하고 보이는 게 아니라 있었구나 하면서 볼 정도로"): the page moved toward black on a light
     * page, toward white on a dark one, by [LIGHT_LINE] and [LIGHT_DOT] or [DARK_LINE] and [DARK_DOT] levels of 255. That
     * is the screenshots exactly: #D1D1D1 / #B4B4B4 on white, #1F1F1F / #323232 on black; on MARU's #323232 it gives
     * #4B4B4B / #5A5A5A. Never the status colour (MARU's gold), so the line is found, not seen first. Fixed per palette:
     * drawing allocates nothing.
     */
    val progressLine: Int = toward(background, lightPage, if (lightPage) LIGHT_LINE else DARK_LINE)
    val progressDot: Int = toward(background, lightPage, if (lightPage) LIGHT_DOT else DARK_DOT)

    /**
     * [progressLine] and [progressDot] on e-ink: their greys on the panel's 16 levels ([inkGrey]), the line at least
     * [INK_LINE_STEPS] levels from the page (one level of a 1 px line next to the page's own is too little to find) and
     * the dots one past the line, so neither vanishes nor dithers. 흰 바탕 #CCCCCC / #BBBBBB (3 and 4 levels off the
     * page), 흑백 반전 #222222 / #333333 (2 and 3; ReadEra's #1F1F1F on black is ≈ 2 levels too), MARU #555555 / #666666
     * (its page shows as #333333: 2 and 3, as on the black page; the nearest levels #444444 / #555555 would put the line
     * one level off it).
     */
    val inkProgressLine: Int = rgb(inkGrey(luma(progressLine), luma(background), lightPage, INK_LINE_STEPS))
    val inkProgressDot: Int = rgb(inkGrey(luma(progressDot), luma(inkProgressLine), lightPage))

    /**
     * MaruViewer's battery icon at one bar (≤ 25 %), on phones (user, 2026-10-05: "25때는 약간 빨간색으로 바뀌고"): the
     * [status] colour [LOW_SHARE] of the way to [LOW_RED], per channel, so it stays this look's colour, only redder:
     * 흰 바탕 #952522 (dark red on white, 8.2:1), 흑백 반전 #EE7E7C (light red on black, 7.9:1), MARU #EE6F52 (salmon beside
     * the gold on #323232, 4.3:1 against the gold's 9.1:1). E-ink keeps [status] (the renderer).
     */
    val batteryLow: Int = blend(status, LOW_RED, LOW_SHARE)

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

        /** The progress line's and its dots' share of the way to black on a light page (of 255: 18 % and 29.4 %). */
        private const val LIGHT_LINE = 46
        private const val LIGHT_DOT = 75
        /** The same toward white on a dark page (12.2 % and 19.6 %). */
        private const val DARK_LINE = 31
        private const val DARK_DOT = 50
        /** The red [batteryLow] moves the status colour toward (Material Red 600), and how far (of 1). */
        private const val LOW_RED = 0xFFE53935.toInt()
        private const val LOW_SHARE = 0.65f

        /** MaruViewer's status gold (measured 2026-10-05, [MARU]): the status line of [MARU] and [BLACK]. */
        const val MARU_GOLD = 0xFFFFD387.toInt()

        /** How far the status grey sits from the page toward the text (of 1): [quietStatus]. */
        private const val STATUS_SHARE = 0.4f

        /**
         * The status lines' colour on a page of [background] with [text]: a neutral grey [STATUS_SHARE] of the way from
         * the page to the text by luma, on the nearest of an e-ink panel's 16 levels (so a Comet shows it flat, never
         * dithered). 흰 바탕 #999999, 흑백 반전 #666666 ([MARU] and [BLACK] keep their gold).
         */
        fun quietStatus(background: Int, text: Int): Int {
            val b = luma(background)
            val v = b + (luma(text) - b) * STATUS_SHARE
            return rgb((Math.round(v / INK_STEP) * INK_STEP).coerceIn(0, 255))
        }

        /** [a] moved [share] (0..1) of the way to [b], per channel, opaque. */
        fun blend(a: Int, b: Int, share: Float): Int {
            fun ch(shift: Int): Int {
                val x = a shr shift and 0xFF
                return x + Math.round(((b shr shift and 0xFF) - x) * share)
            }
            return OPAQUE or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** One of an e-ink panel's 16 grey levels (0x00, 0x11 … 0xFF). */
        private const val INK_STEP = 17
        /** The e-ink progress line's least distance from the page, in panel levels ([inkProgressLine]). */
        private const val INK_LINE_STEPS = 2

        /** [c] moved [levels] of 255 of the way to black ([darker]) or to white, per channel. */
        private fun toward(c: Int, darker: Boolean, levels: Int): Int {
            fun ch(v: Int): Int {
                val target = if (darker) 0 else 255
                return v + Math.round((target - v) * levels / 255f)
            }
            return OPAQUE or (ch(c shr 16 and 0xFF) shl 16) or (ch(c shr 8 and 0xFF) shl 8) or ch(c and 0xFF)
        }

        /** The grey an e-ink panel shows for [c] (0..255): its luma. */
        private fun luma(c: Int): Int =
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114 + 500) / 1000

        private fun rgb(v: Int): Int = OPAQUE or (v shl 16) or (v shl 8) or v

        /**
         * Grey [v] (0..255) on an e-ink panel's 16 levels: the nearest, then moved on until it is at least [steps]
         * levels past [from]'s, darker ([darker]) or lighter. What the panel would round it to anyway, but chosen here,
         * so a line meant to be faint never rounds into the page (or the dots into the line).
         */
        fun inkGrey(v: Int, from: Int, darker: Boolean, steps: Int = 1): Int {
            val level = (v.coerceIn(0, 255) + INK_STEP / 2) / INK_STEP * INK_STEP
            val base = (from.coerceIn(0, 255) + INK_STEP / 2) / INK_STEP * INK_STEP
            return if (darker) minOf(level, base - steps * INK_STEP).coerceAtLeast(0)
            else maxOf(level, base + steps * INK_STEP).coerceAtMost(255)
        }

        /** Black on white (the default). */
        val PAPER = PagePalette(
            background = 0xFFFFFFFF.toInt(), text = OPAQUE, status = quietStatus(0xFFFFFFFF.toInt(), OPAQUE),
            shadowDxDp = 0f, shadowDyDp = 0f, shadowSigmaDp = 0f, shadowColor = 0, dark = false, invertImages = false,
        )

        /** 흑백 반전: white on black, pictures inverted, no shadow (whatever the theme). */
        val NIGHT = PagePalette(
            background = OPAQUE, text = 0xFFFFFFFF.toInt(), status = quietStatus(OPAQUE, 0xFFFFFFFF.toInt()),
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
         * Colours re-measured 2026-10-05 on the original 1080 × 2340 PNGs (user: "위에 상태표시 글꼴과 색상도
         * 최대한 똑같게"): the status gold is #FFD387, not ≈ #F0D096 (the JPEG copies agree once their 4:2:0 chroma
         * is modelled: a luma plateau of 215 and a chroma fit of #FFD387 on four screenshots, text and battery icon
         * alike, where our own #F0D096 comes back as #F1D097), and the shadow is opaque black, not 88 % (its darkest
         * pixels: luma 4–6 against our 10–12 at 0xE0, the 1st percentile 11 against 14; 0xE0 cannot go below 6.1);
         * offset and blur unchanged. A least-squares fit on the shadow-only pixels returns 0.87–0.88 on our own page
         * (0xE0 = 0.878) and ≈ 0.92 to ≥ 1 on MaruViewer's four, so 0xFF is the top of that range (0xF0–0xF4 the middle,
         * if the S25 side by side reads ours heavier). On e-ink the shadow is drawn as before: ≈ 5–7 % of its pixels sit
         * one panel level darker than at 0xE0, none two.
         * Its status line has no shadow: no pixel around those glyphs or the battery icon is more than 2 levels darker
         * than the page (the darker-looking ones are chroma fringes at the page's luma), nor its white page's status
         * (#323232), so [status] stays flat.
         * Re-fitted 2026-10-05 on lossless PNG pairs of the same page (user: "글씨의 선명도, 그림자 크기 이런 것들 똑같이"),
         * with each app's own glyphs as the shadow's source (MaruViewer's hinted 47 px, ours unhinted 47.81 px): the S25
         * runs at 2.8125 px per dp, not 3 (our em is 17 sp × 2.8125, our text origins whole dp at 2.8125). MaruViewer's
         * shadow is a constant (2, 1) px, opaque, sigma ≈ 1.37–1.38 px: 8 % wider than our 0.45 dp (1.27 px), its
         * darkening of the page 6 % more in sum. Ours varied 1.75–2.25 px right and 1–2 px down with the glyph's
         * quarter-pixel phase and the line's fractional baseline. Now 0.71 / 0.36 dp (2.0 / 1.0 px, drawn in whole px:
         * `CrispText.shadowOffsetPx`) and sigma 0.49 dp (1.38 px; radius 1.52 px); simulated with the hinted glyphs its
         * shadow sum is within 1 % of MaruViewer's on two lines. Its white page has no shadow (a fit gives alpha 0, its
         * extra faint pixels are the hinting), so [PAPER] stays without one. On the Comet (2 px per dp) the shadow is
         * (1, 1) px (was ≈ 1.25 / 1 px) with sigma 0.98 px (was 0.90).
         * Re-measured 2026-10-10 on one lossless S25 screenshot with both apps on the same page of the same book (user:
         * "그림자랑 글자 선명도는 정말 중요해 최대한 똑같아야"): the glyphs are now drawn alike (six lines aligned with no shift,
         * every glyph pixel within one level, 93 % equal), so the shadow alone differed: ours lighter in its core (up to
         * 0.6 level) and darker in its rim (up to 0.26): a wider blur. Regressing the difference on the blur's width
         * gives ours 0.045 px wider (+0.0452 on either half of the lines), offset and strength the same: sigma 0.474 dp
         * (1.333 px, radius 1.44 px; was 0.49 dp, 1.378 px). On the Comet 0.95 px.
         */
        val MARU = PagePalette(
            background = 0xFF323232.toInt(), text = 0xFFDDDDDD.toInt(), status = MARU_GOLD,
            shadowDxDp = 0.71f, shadowDyDp = 0.36f, shadowSigmaDp = 0.474f, shadowColor = 0xFF000000.toInt(),
            dark = true, invertImages = false,
        )

        /**
         * 검은 바탕 (user, 2026-10-05: "검은색도 회색(마루)랑 똑같은 흰색으로"): [MARU]'s #DDDDDD text and #FFD387 status
         * line on #000000, with [MARU]'s shadow too (user, 2026-10-05: "그림자는 흰색만 없애고 검은색 회색은 다 있게").
         * Pictures keep their colours.
         */
        val BLACK = PagePalette(
            background = OPAQUE, text = 0xFFDDDDDD.toInt(), status = MARU_GOLD,
            shadowDxDp = 0.71f, shadowDyDp = 0.36f, shadowSigmaDp = 0.474f, shadowColor = 0xFF000000.toInt(),
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
            theme == PageTheme.BLACK -> BLACK
            else -> PAPER
        }
    }
}
