package com.ggumtak.readeraplus.render

/**
 * The reader chrome's colours (the bars, the history row above the bottom panel, the brightness options, the seek
 * preview, the return chip) for a page palette and the device class: the design tokens of UI_SPEC §2.1 (2026-10-05).
 * Pure (Ints only, unit-tested); six shared instances, so asking for one allocates nothing.
 *
 * The bars follow the page: a surface close to the page colour (light on 흰 바탕, dark on 마루뷰어 and 흑백 반전),
 * never a white bar over a dark page. The accent is the page's own status colour (black, MaruViewer's gold, white), so
 * every theme keeps one accent of its own. Phones get low-contrast lines, a short shadow where a bar meets the page, a
 * pressed overlay and the short show/hide transition ([motion]). On e-ink every action stays one screen update: the
 * surface is the page itself, lines are solid 1 px, and nothing changes over time (no shadow, no pressed flash, no
 * fade); its 흰 바탕 set is exactly the black-on-white chrome of before, and the dark pages get that set's greys on
 * their colours ([PagePalette.grey]).
 */
internal class ChromePalette private constructor(
    /** The page colour (the history row sits on it). */
    val page: Int,
    /** The bars' fill. */
    val surface: Int,
    /** Primary text and icons. */
    val text: Int,
    /** Secondary text: the page label's total, subtitles, a disabled title. */
    val text2: Int,
    /** Active toggles, the slider's progress and thumb. */
    val accent: Int,
    /** Low-contrast lines inside a panel (between option rows). */
    val divider: Int,
    /** The line under the title and above the options panel: [divider] on phones, the old black rule on e-ink. */
    val rule: Int,
    /** A 1 px line where a bar meets the page when there is no [shadow]; 0 = none. */
    val edge: Int,
    /**
     * A slider's inactive track (and the progress of the automatic brightness look); on a phone also the 1 px border
     * of the boxes that float over the page text (the return chip and its inner line, the seek preview).
     */
    val track: Int,
    /** The history row's text on the page colour, below the page label's emphasis. */
    val hist: Int,
    /** The history row's "N쪽" while the pinned page is on screen (not a link): dimmer than [hist], still readable. */
    val histOff: Int,
    /** ARGB at a bar's edge, fading linearly to nothing over [SHADOW_DP] toward the page; 0 = no shadow. */
    val shadow: Int,
    /** The pressed overlay of buttons and rows; 0 = no pressed state. */
    val pressed: Int,
    /** The 40 dp circle behind an active toggle (bookmark, pin, rotation lock), set apart from [pressed]; 0 = none. */
    val active: Int,
    /** The bars may fade and slide in and out (150–200 ms): phones only. */
    val motion: Boolean,
    /** The e-ink set: solid lines across the full width, nothing that changes over time. */
    val eink: Boolean,
    val dark: Boolean,
) {
    companion object {
        /** Height of the shadow band where a bar meets the page (ReadEra's ≈ 3.5 dp, measured on the user's screen). */
        const val SHADOW_DP = 4

        private const val OPAQUE = 0xFF000000.toInt()
        private fun rgb(v: Int): Int = OPAQUE or v

        private val PAPER = ChromePalette(
            page = rgb(0xFFFFFF), surface = rgb(0xF5F5F5), text = rgb(0x1A1A1A), text2 = rgb(0x5E5E5E),
            accent = rgb(0x000000), divider = rgb(0xDDDDDD), rule = rgb(0xDDDDDD), edge = 0, track = rgb(0xC8C8C8),
            hist = rgb(0x5E5E5E), histOff = rgb(0x808080), shadow = 0x33000000, pressed = 0x14000000,
            active = 0x38000000, motion = true, eink = false, dark = false,
        )

        private val MARU = ChromePalette(
            page = rgb(0x323232), surface = rgb(0x3C3C3C), text = rgb(0xDDDDDD), text2 = rgb(0xA8A8A8),
            accent = rgb(0xF0D096), divider = rgb(0x4E4E4E), rule = rgb(0x4E4E4E), edge = 0, track = rgb(0x606060),
            hist = rgb(0xA8A8A8), histOff = rgb(0x8A8A8A), shadow = 0x80000000.toInt(), pressed = 0x1AFFFFFF,
            active = 0x4DF0D096, motion = true, eink = false, dark = true,
        )

        /** 흑백 반전: a shadow is invisible on black, so the bars get a 1 px line instead. */
        private val NIGHT = ChromePalette(
            page = rgb(0x000000), surface = rgb(0x1A1A1A), text = rgb(0xFFFFFF), text2 = rgb(0xB3B3B3),
            accent = rgb(0xFFFFFF), divider = rgb(0x333333), rule = rgb(0x333333), edge = rgb(0x333333),
            track = rgb(0x4A4A4A), hist = rgb(0xB3B3B3), histOff = rgb(0x6E6E6E), shadow = 0, pressed = 0x1AFFFFFF,
            active = 0x42FFFFFF, motion = true, eink = false, dark = true,
        )

        /**
         * The e-ink set of page [p]: the old chrome's colours (black, `Ink.GRAY` #555, `Ink.LINE_LIGHT` #CCC,
         * `Ink.DISABLED` #999 on white) as [p]'s greys, so 흰 바탕 is exactly the chrome of before; solid colours only.
         */
        private fun eink(p: PagePalette) = ChromePalette(
            page = p.background, surface = p.background, text = p.grey(0), text2 = p.grey(0x55), accent = p.status,
            divider = p.grey(0xCC), rule = p.grey(0), edge = p.grey(0), track = p.grey(0x99), hist = p.grey(0),
            histOff = p.grey(0x55), shadow = 0, pressed = 0, active = 0, motion = false, eink = true, dark = p.dark,
        )

        private val EINK_PAPER = eink(PagePalette.PAPER)
        private val EINK_MARU = eink(PagePalette.MARU)
        private val EINK_NIGHT = eink(PagePalette.NIGHT)

        /** The chrome of before (black on white, no motion): the look until the reader pushes its own. */
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
                else -> if (phone) PAPER else EINK_PAPER
            }
        }
    }
}
