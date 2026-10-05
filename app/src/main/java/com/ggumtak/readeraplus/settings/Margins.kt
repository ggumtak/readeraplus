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
 * U3: top/bottom margins, "40 dp = 0" (stored values stay actual dp). Unchanged by the MaruViewer side margins (user,
 * 2026-10-05: "여백만 맞추라는 거야 일단은" was about the sides).
 */
object VerticalMargin {
    const val ZERO_DP = 40
    /** ≤ R2 default of marginTopDp / marginBottomDp. */
    const val LEGACY_DEFAULT_DP = 16
    const val UI_MIN = -40
    const val UI_MAX = 40
    const val UI_STEP = SideMargin.UI_STEP
    /** Marker: the top/bottom margins were saved by a U3+ build (prefs, backup reader object; style JSON "marginBaseV"). */
    const val KEY = "r.marginBaseV"
    const val STYLE_KEY = "marginBaseV"
    fun toUi(actualDp: Int): Int = actualDp - ZERO_DP
    fun toDp(ui: Int): Int = (ui + ZERO_DP).coerceAtLeast(0)
    fun label(ui: Int): String = SideMargin.label(ui)
    /** Values saved before U3 that equal the old untouched default 16/16. */
    fun isLegacyDefault(hasMarker: Boolean, top: Int, bottom: Int): Boolean =
        !hasMarker && top == LEGACY_DEFAULT_DP && bottom == LEGACY_DEFAULT_DP
}
