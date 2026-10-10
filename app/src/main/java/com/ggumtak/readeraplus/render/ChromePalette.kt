package com.ggumtak.readeraplus.render

/**
 * The reader chrome's colours (the bars, the history row above the bottom panel, the brightness row and its options,
 * the seek preview, the return chip) for a page palette and the device class. Pure (Ints only, unit-tested); eight
 * shared instances, so asking for one allocates nothing.
 *
 * On a phone's 흰 바탕 and 흑백 반전 one formula, so the bars blend with the page: each tone is the page's background
 * [PagePalette.background] moved a share of the way to its text colour ([PagePalette.text]), see [Share]. On 흰 바탕 that
 * is the greys of the RIDI book app's bars (≈ #F0F0F0 surface, #DEDEDE and #CCCCCC lines, #919191 thumb, #8A8A8A and
 * #616161 for secondary and primary glyphs). 마루뷰어 and 검은 바탕 keep the chrome they had before (user, 2026-10-10:
 * "회색하고 검은색배경은 상태표시줄 원래색으로 … 탭 했을 때 나오던 것도 색 똑같이"): #DDDDDD glyphs on a bar a step off
 * the page, MaruViewer's gold ([PagePalette.MARU_GOLD]) for what is on, a shadow (마루뷰어) or a #333333 line (검은 바탕)
 * where a bar meets the page.
 *
 * Phones: the surface is that faint tone, bars meet the page with a 1 px line ([topEdge], [bottomEdge]), buttons show a
 * pressed overlay and the bars fade and slide in and out ([motion]). E-ink: every action stays one screen update, so the
 * surface is the page itself, nothing changes over time (no shadow, no pressed flash, no fade), and the chrome keeps the
 * reader's black-and-white look ([InkShare]: glyphs, thumb and the bars' 1 px lines in the page's full ink, secondary
 * glyphs at #555, a #999 track): RIDI's light greys on a page-coloured bar would leave only a hairline between bar and
 * page and thin grey glyphs a fast e-ink update can drop. The tones are snapped to the panel's 16 grey levels
 * ([PagePalette.inkGrey]) and kept at least [MIN_INK_STEPS] levels from the page.
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
    /** What is on (a switch, a toggled icon): [text] on the formula's looks, MaruViewer's gold on 마루뷰어 and 검은 바탕. */
    val accent: Int,
    /** Low-contrast lines inside a panel (between option rows). */
    val divider: Int,
    /** The line above the brightness options: [divider] on phones, the full ink on e-ink. */
    val rule: Int,
    /**
     * A 1 px outline of the boxes that float over the page on e-ink (the return chip, the seek preview): the full ink
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
    /** ARGB at a bar's edge, fading linearly to nothing over [SHADOW_DP] toward the page; 0 = no shadow (all but 마루뷰어). */
    val shadow: Int,
    /** The pressed overlay of buttons and rows; 0 = no pressed state. */
    val pressed: Int,
    /** The 40 dp circle behind an active toggle (마루뷰어 and 검은 바탕: gold); 0 = none (the icon swap alone shows it). */
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

    /** The same shares on e-ink: the reader's ink look (black glyphs and lines, #555, #999, #CCC on 흰 바탕). */
    object InkShare {
        const val PRIMARY = 1f
        const val LINE = 1f
        const val SECONDARY = 0.667f
        const val TRACK = 0.4f
        const val DIVIDER = 0.2f
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
            if (eink) {
                val ink = tone(p, InkShare.PRIMARY, true)
                val line = tone(p, InkShare.LINE, true)
                return ChromePalette(
                    page = p.background, surface = p.background, text = ink, text2 = tone(p, InkShare.SECONDARY, true),
                    accent = ink, divider = tone(p, InkShare.DIVIDER, true), rule = line, edge = line, topEdge = line,
                    bottomEdge = line, track = tone(p, InkShare.TRACK, true), thumb = ink, hist = ink, shadow = 0,
                    pressed = 0, active = 0, motion = false, eink = true, dark = p.dark,
                )
            }
            val primary = tone(p, Share.PRIMARY, false)
            val divider = tone(p, Share.TOP_EDGE, false)
            val bottomEdge = tone(p, Share.BOTTOM_EDGE, false)
            return ChromePalette(
                page = p.background,
                surface = tone(p, Share.SURFACE, false),
                text = primary,
                text2 = tone(p, Share.SECONDARY, false),
                accent = primary,
                divider = divider,
                rule = divider,
                edge = bottomEdge,
                topEdge = divider,
                bottomEdge = bottomEdge,
                track = tone(p, Share.TRACK, false),
                thumb = tone(p, Share.THUMB, false),
                hist = primary,
                shadow = 0,
                pressed = (PRESSED_ALPHA shl 24) or (p.text and 0xFFFFFF),
                active = 0,
                motion = true,
                eink = false,
                dark = p.dark,
            )
        }

        private fun rgb(v: Int): Int = OPAQUE or v

        private val PAPER = build(PagePalette.PAPER, false)
        private val NIGHT = build(PagePalette.NIGHT, false)

        /** 마루뷰어 on a phone: its chrome before the RIDI bars (#3C3C3C bars with a shadow, gold for what is on). */
        private val MARU = ChromePalette(
            page = PagePalette.MARU.background, surface = rgb(0x3C3C3C), text = rgb(0xDDDDDD), text2 = rgb(0xA8A8A8),
            accent = PagePalette.MARU_GOLD, divider = rgb(0x4E4E4E), rule = rgb(0x4E4E4E), edge = 0, topEdge = 0,
            bottomEdge = 0, track = rgb(0x606060), thumb = PagePalette.MARU_GOLD, hist = rgb(0xA8A8A8),
            shadow = 0x80000000.toInt(), pressed = 0x1AFFFFFF, active = 0x4DFFD387, motion = true, eink = false,
            dark = true,
        )

        /** 검은 바탕 on a phone: [MARU]'s glyphs and gold on black, a #333333 line instead of the shadow. */
        private val BLACK = ChromePalette(
            page = PagePalette.BLACK.background, surface = rgb(0x1A1A1A), text = rgb(0xDDDDDD), text2 = rgb(0xA8A8A8),
            accent = PagePalette.MARU_GOLD, divider = rgb(0x333333), rule = rgb(0x333333), edge = rgb(0x333333),
            topEdge = rgb(0x333333), bottomEdge = rgb(0x333333), track = rgb(0x4A4A4A), thumb = PagePalette.MARU_GOLD,
            hist = rgb(0xA8A8A8), shadow = 0, pressed = 0x1AFFFFFF, active = 0x4DFFD387, motion = true, eink = false,
            dark = true,
        )
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
