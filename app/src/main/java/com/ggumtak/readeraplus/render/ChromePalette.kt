package com.ggumtak.readeraplus.render

/**
 * The reader chrome's colours (the bars, the history row above the bottom panel, the brightness row and its options,
 * the seek preview, the return chip) for a page palette and the device class. Pure (Ints only, unit-tested); eight
 * shared instances, so asking for one allocates nothing.
 *
 * One formula for every theme, so the bars blend with the page: each tone is the page's background [PagePalette.background]
 * moved a share of the way to its text colour ([PagePalette.text]), see [Share]. On 흰 바탕 that is the greys of the RIDI
 * book app's bars (≈ #F0F0F0 surface, #DEDEDE and #CCCCCC lines, #919191 thumb, #8A8A8A and #616161 for secondary and
 * primary glyphs), and 마루뷰어, 흑백 반전 and 검은 바탕 get the same treatment on their own colours; no theme keeps an
 * accent of its own in the chrome (the status gold stays on the page's status line).
 *
 * Phones: the surface is that faint tone, bars meet the page with a 1 px line ([topEdge], [bottomEdge]), buttons show a
 * pressed overlay and the bars fade and slide in and out ([motion]). E-ink: every action stays one screen update, so the
 * surface is the page itself, lines are solid 1 px, nothing changes over time (no shadow, no pressed flash, no fade);
 * the tones are snapped to the panel's 16 grey levels ([PagePalette.inkGrey]) and kept at least [MIN_INK_STEPS] levels
 * from the page, so no element rounds into its background.
 */
internal class ChromePalette private constructor(
    /** The page colour, [PagePalette.background] itself: the history row and the brightness row sit on it. */
    val page: Int,
    /** The bars' fill: a faint tone of the page on a phone, the page itself on e-ink. */
    val surface: Int,
    /** Icons, labels, the book title and the current page: the strongest tone. */
    val text: Int,
    /** Secondary text and glyphs: the page label's total, the back arrow, subtitles, a disabled title. */
    val text2: Int,
    /** An active switch: the strongest tone ([text]); the chrome has no colour accent. */
    val accent: Int,
    /** Low-contrast lines inside a panel (between option rows). */
    val divider: Int,
    /** The line above the brightness options: [divider] on phones, a firmer grey on e-ink. */
    val rule: Int,
    /**
     * A 1 px outline of the boxes that float over the page on e-ink (the return chip, the seek preview): a firm grey
     * there; on a phone they use [track] instead, and this is [bottomEdge].
     */
    val edge: Int,
    /** The top bar's 1 px line where it meets the page: the faintest line. */
    val topEdge: Int,
    /** The bottom bar's 1 px line where it meets the page. */
    val bottomEdge: Int,
    /**
     * A slider's inactive track (and the progress of the automatic brightness look); on a phone also the 1 px border
     * of the boxes that float over the page text (the return chip and its inner line, the seek preview).
     */
    val track: Int,
    /** A slider's thumb and the progress laid over its [track]. */
    val thumb: Int,
    /** The history row's text and the brightness row's icons on the page colour: the same tone as [text]. */
    val hist: Int,
    /** ARGB at a bar's edge, fading linearly to nothing over [SHADOW_DP] toward the page; 0 = no shadow (every look now). */
    val shadow: Int,
    /** The pressed overlay of buttons and rows; 0 = no pressed state. */
    val pressed: Int,
    /** The 40 dp circle behind an active toggle; 0 = none (a state is shown by swapping icons, on every look now). */
    val active: Int,
    /** The bars may fade and slide in and out (150–200 ms): phones only. */
    val motion: Boolean,
    /** The e-ink set: solid lines across the full width, nothing that changes over time. */
    val eink: Boolean,
    val dark: Boolean,
) {
    /**
     * The shares of the way from the page's background to its text colour that make each tone (measured on RIDI's
     * 1080 × 2340 white page, where background #FFFFFF and text #000000 give its greys).
     */
    object Share {
        const val SURFACE = 0.06f
        const val TOP_EDGE = 0.13f
        const val BOTTOM_EDGE = 0.20f
        const val TRACK = 0.20f
        const val THUMB = 0.43f
        const val SECONDARY = 0.46f
        const val PRIMARY = 0.62f
    }

    companion object {
        /** Height of the shadow band where a bar meets the page, were a look to ask for one ([ChromePalette.shadow]). */
        const val SHADOW_DP = 4

        /** E-ink: least distance, in the panel's 16 grey levels, of any element from the page colour. */
        const val MIN_INK_STEPS = 2

        /** Alpha of the pressed overlay on a phone (of 255): the text colour at 10 %. */
        private const val PRESSED_ALPHA = 0x1A

        private const val OPAQUE = 0xFF000000.toInt()

        /** The grey an e-ink panel shows for [c] (0..255): its luma. */
        private fun luma(c: Int): Int =
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114 + 500) / 1000

        /**
         * [share] of the way from [p]'s background to its text colour; on e-ink the nearest of the panel's 16 levels
         * that is at least [MIN_INK_STEPS] from the page ([PagePalette.inkGrey]).
         */
        internal fun tone(p: PagePalette, share: Float, eink: Boolean): Int {
            val c = PagePalette.blend(p.background, p.text, share)
            if (!eink) return c
            val g = PagePalette.inkGrey(luma(c), luma(p.background), darker = !p.dark, steps = MIN_INK_STEPS)
            return OPAQUE or (g shl 16) or (g shl 8) or g
        }

        private fun build(p: PagePalette, eink: Boolean): ChromePalette {
            val primary = tone(p, Share.PRIMARY, eink)
            val secondary = tone(p, Share.SECONDARY, eink)
            val bottomEdge = tone(p, Share.BOTTOM_EDGE, eink)
            val divider = tone(p, Share.TOP_EDGE, eink)
            return ChromePalette(
                page = p.background,
                surface = if (eink) p.background else tone(p, Share.SURFACE, false),
                text = primary,
                text2 = secondary,
                accent = primary,
                divider = divider,
                rule = if (eink) secondary else divider,
                edge = if (eink) secondary else bottomEdge,
                topEdge = tone(p, Share.TOP_EDGE, eink),
                bottomEdge = bottomEdge,
                track = tone(p, Share.TRACK, eink),
                thumb = tone(p, Share.THUMB, eink),
                hist = primary,
                shadow = 0,
                pressed = if (eink) 0 else (PRESSED_ALPHA shl 24) or (p.text and 0xFFFFFF),
                active = 0,
                motion = !eink,
                eink = eink,
                dark = p.dark,
            )
        }

        private val PAPER = build(PagePalette.PAPER, false)
        private val MARU = build(PagePalette.MARU, false)
        private val NIGHT = build(PagePalette.NIGHT, false)
        private val BLACK = build(PagePalette.BLACK, false)
        private val EINK_PAPER = build(PagePalette.PAPER, true)
        private val EINK_MARU = build(PagePalette.MARU, true)
        private val EINK_NIGHT = build(PagePalette.NIGHT, true)
        private val EINK_BLACK = build(PagePalette.BLACK, true)

        /** The e-ink chrome on 흰 바탕 (no motion): the look until the reader pushes its own. */
        val DEFAULT: ChromePalette get() = EINK_PAPER

        /**
         * The chrome for [page] on a device whose class is [eink] (`DeviceClass.cached`); null = not known yet, which
         * gets the e-ink set (no motion, nothing that ghosts) until the probe after the first page answers.
         */
        fun of(page: PagePalette, eink: Boolean?): ChromePalette {
            val phone = eink == false
            return when {
                page === PagePalette.NIGHT -> if (phone) NIGHT else EINK_NIGHT
                page === PagePalette.MARU -> if (phone) MARU else EINK_MARU
                page === PagePalette.BLACK -> if (phone) BLACK else EINK_BLACK
                else -> if (phone) PAPER else EINK_PAPER
            }
        }
    }
}
