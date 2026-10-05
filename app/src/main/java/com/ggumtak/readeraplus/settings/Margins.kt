package com.ggumtak.readeraplus.settings

/**
 * Scroll SPEC §2.2 (as specified there), with MaruViewer's side margin as the "0" since 2026-10-05 (user: "기본 좌우여백이
 * 너무 넓다 마루뷰어랑 비교해서 … 맞춰"): 20 dp, 5.6 % of the width on the S25 and on the Comet alike (dp, not px).
 * Stored values stay actual dp. [KEY] (prefs and the backup's reader object; [STYLE_KEY] in a saved style) holds the "0"
 * the margins were saved with, so margins still at an older build's untouched default ([isLegacyDefault]) move to this
 * one's, and every value the user chose stays.
 */
object SideMargin {
    const val ZERO_DP = 20
    /** ≤ R2 default, saved without [KEY]. */
    const val LEGACY_DEFAULT_DP = 18
    /** The "0" from R3 until the MaruViewer margins: [KEY] = 40. */
    const val R3_ZERO_DP = 40
    /** −20..+60: 0..80 dp, the range of the 40 dp scale. */
    const val UI_MIN = -20
    const val UI_MAX = 60
    const val UI_STEP = 2
    const val KEY = "r.marginBase"
    const val STYLE_KEY = "marginBase"
    fun toUi(actualDp: Int): Int = actualDp - ZERO_DP
    fun toDp(ui: Int): Int = (ui + ZERO_DP).coerceAtLeast(0)
    fun label(ui: Int): String = when { ui > 0 -> "+$ui"; ui < 0 -> "−${-ui}"; else -> "0" }

    /**
     * True when [left] / [right] are the untouched default of the build that saved them: [LEGACY_DEFAULT_DP] without a
     * marker ([base] null, ≤ R2), [R3_ZERO_DP] under the R3 marker. Margins saved on this scale ([ZERO_DP]) and any
     * other value are the user's and stay.
     */
    fun isLegacyDefault(base: Int?, left: Int, right: Int): Boolean = left == right && when (base) {
        null -> left == LEGACY_DEFAULT_DP
        R3_ZERO_DP -> left == R3_ZERO_DP
        else -> false
    }
}

/**
 * The status bands' heights in whole dp (pure). Since 2026-10-05 (user: "위 여백은 위 아래 애들을 제외하고 본문영역에서만
 * 계산해야지") the header, the footer and the progress line each have a band of their own at the screen's edges, and the
 * 위·아래 여백 ([VerticalMargin]) are the paper between those bands and the text: a margin of 0 puts the text right under
 * the header, and no margin ever hides or shrinks a band. Only the settings decide them, never what is on screen (an
 * empty title, a page number not known yet, the menu, a selection): showing or hiding anything never moves the text box.
 * Whole dp, so whole-dp margins put the text box on the same pixels on every density (`LayoutKeys.geometry` rounds band +
 * margin once).
 */
object StatusBands {
    /** Paper between a band and the screen's edge: the Comet's bezel hides the panel's outer rows (`StatusFit.EDGE_DP`). */
    const val EDGE_DP = 4
    /** Paper between the status glyphs and the margin or the progress lane next to them. */
    const val PAD_DP = 2
    /** The progress line's lane. */
    const val LANE_DP = ReaderSettings.PROGRESS_LANE_DP
    /**
     * Glyph box per sp of status text: at least the ascent + descent of the status font (Roboto ≈ 1.17, the CJK system
     * font ≈ 1.45), so its glyphs never leave their band.
     */
    const val GLYPH_EM = 1.45

    /** The status text size the page draws (sp): [ReaderSettings.statusFontSizeSp] within 6..40, 11 when unusable. */
    fun statusSp(s: ReaderSettings): Float =
        s.statusFontSizeSp.let { if (it.isFinite() && it > 0f) it.coerceIn(6f, 40f) else 11f }

    /** The status glyph box in whole dp, rounded up: 16 dp at 11 sp. */
    fun glyphDp(s: ReaderSettings): Int = Math.ceil(statusSp(s) * GLYPH_EM - 1e-3).toInt()

    /** The header's band: [EDGE_DP], the glyph box and [PAD_DP]; 0 without header items. 22 dp by default. */
    fun headerDp(s: ReaderSettings): Int = if (s.hasHeader) EDGE_DP + glyphDp(s) + PAD_DP else 0

    /**
     * The footer's band from the bottom edge: [EDGE_DP], the lane while the progress line is on, and with footer items
     * ([PAD_DP] above the lane) the glyph box and [PAD_DP]; 0 with neither. 16 dp by default (the progress line alone).
     */
    fun footerDp(s: ReaderSettings): Int {
        if (!s.hasFooterText) return if (s.progressBar) EDGE_DP + LANE_DP else 0
        return EDGE_DP + (if (s.progressBar) LANE_DP + PAD_DP else 0) + glyphDp(s) + PAD_DP
    }
}

/**
 * U3: top/bottom margins, stored as actual dp. Since 2026-10-05 they count from the status bands ([StatusBands]), not
 * from the screen's edge: the top margin is the paper between the header's band and the text, the bottom one between the
 * text and the footer's band. The defaults keep the text box where 40 dp from the edge put it with the default bands
 * (user: "코멧에서 본문 지금 자리 그대로 되게 숫자 맞춰줘"): [TOP_ZERO_DP] = 40 − 22, [BOTTOM_ZERO_DP] = 40 − 16, rows
 * 80..1360 on the Comet as before. Each side's default is its "0"; the one 상하 여백 stepper moves both by its value.
 * [KEY] (prefs and the backup's reader object; [STYLE_KEY] in a saved style) says how the values were saved: [BANDS] now,
 * [EDGE] from U3 until the bands ("40 dp = 0" from the edge), nothing ≤ R2. Values saved from the edge move once when
 * they are read ([fromEdge]: the text box stays where it was); the next save writes [BANDS], so nothing moves twice.
 */
object VerticalMargin {
    /** Where the text box starts and ends from the screen's edges with the default margins and bands (dp). */
    const val EDGE_DP = 40
    /** The top margin's "0": [EDGE_DP] less the default header band (22 dp). */
    const val TOP_ZERO_DP = 18
    /** The bottom margin's "0": [EDGE_DP] less the default footer band (the progress line, 16 dp). */
    const val BOTTOM_ZERO_DP = 24
    /** Either margin's largest value on the steppers. */
    const val MAX_DP = 80
    /** ≤ R2 default of marginTopDp / marginBottomDp. */
    const val LEGACY_DEFAULT_DP = 16
    /** −24..+62: both margins 0..80 dp (each stops at its end). */
    const val UI_MIN = -BOTTOM_ZERO_DP
    const val UI_MAX = MAX_DP - TOP_ZERO_DP
    const val UI_STEP = SideMargin.UI_STEP
    /** Marker: how the top/bottom margins were saved (prefs, backup reader object; style JSON "marginBaseV"). */
    const val KEY = "r.marginBaseV"
    const val STYLE_KEY = "marginBaseV"
    /** [KEY]: counted from the status bands (2026-10-05). */
    const val BANDS = 2
    /** [KEY] from U3 until the bands: counted from the screen's edge, 40 dp = "0". */
    const val EDGE = EDGE_DP

    fun topDp(ui: Int): Int = (TOP_ZERO_DP + ui).coerceIn(0, MAX_DP)
    fun bottomDp(ui: Int): Int = (BOTTOM_ZERO_DP + ui).coerceIn(0, MAX_DP)

    /** The stepper value of [top] / [bottom]: the top margin's, or the bottom one's where the top stopped at 0. */
    fun toUi(top: Int, bottom: Int): Int =
        (if (top <= 0) bottom - BOTTOM_ZERO_DP else top - TOP_ZERO_DP).coerceIn(UI_MIN, UI_MAX)

    fun label(ui: Int): String = SideMargin.label(ui)

    /** Values saved before U3 that equal the old untouched default 16/16. */
    fun isLegacyDefault(hasMarker: Boolean, top: Int, bottom: Int): Boolean =
        !hasMarker && top == LEGACY_DEFAULT_DP && bottom == LEGACY_DEFAULT_DP

    /** True when margins saved with marker [base] (null: none) count from the screen's edge. */
    fun countsFromEdge(base: Int?): Boolean = base != BANDS

    /** A top margin saved from the screen's edge, counted from the header band of [s] instead (never below 0). */
    fun topFromEdge(top: Int, s: ReaderSettings): Int = (top - StatusBands.headerDp(s)).coerceAtLeast(0)

    /** A bottom margin saved from the screen's edge, counted from the footer band of [s] instead (never below 0). */
    fun bottomFromEdge(bottom: Int, s: ReaderSettings): Int = (bottom - StatusBands.footerDp(s)).coerceAtLeast(0)

    /**
     * [s] with the margins it read from the edge ([top], [bottom]: those that were saved) counted from its own bands, so
     * its text box stays where it was: 40/40 with the default bands become the defaults 18/24.
     */
    fun fromEdge(s: ReaderSettings, top: Boolean = true, bottom: Boolean = true): ReaderSettings = s.copy(
        marginTopDp = if (top) topFromEdge(s.marginTopDp, s) else s.marginTopDp,
        marginBottomDp = if (bottom) bottomFromEdge(s.marginBottomDp, s) else s.marginBottomDp,
    )
}
