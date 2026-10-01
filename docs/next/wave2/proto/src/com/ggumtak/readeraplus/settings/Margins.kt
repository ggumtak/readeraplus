package com.ggumtak.readeraplus.settings

/** Scroll SPEC §2.2 (as specified there). */
object SideMargin {
    const val ZERO_DP = 40
    const val LEGACY_DEFAULT_DP = 18
    const val UI_MIN = -40
    const val UI_MAX = 40
    const val UI_STEP = 2
    const val KEY = "r.marginBase"
    fun toUi(actualDp: Int): Int = actualDp - ZERO_DP
    fun toDp(ui: Int): Int = (ui + ZERO_DP).coerceAtLeast(0)
    fun label(ui: Int): String = when { ui > 0 -> "+$ui"; ui < 0 -> "−${-ui}"; else -> "0" }
    fun isLegacyDefault(hasMarker: Boolean, left: Int, right: Int): Boolean =
        !hasMarker && left == LEGACY_DEFAULT_DP && right == LEGACY_DEFAULT_DP
}

/** U3: top/bottom margins on the same "40 dp = 0" scale as [SideMargin]; stored values stay actual dp. */
object VerticalMargin {
    const val ZERO_DP = SideMargin.ZERO_DP
    /** ≤ R2 default of marginTopDp / marginBottomDp. */
    const val LEGACY_DEFAULT_DP = 16
    const val UI_MIN = SideMargin.UI_MIN
    const val UI_MAX = SideMargin.UI_MAX
    const val UI_STEP = SideMargin.UI_STEP
    /** Marker: the top/bottom margins were saved by a U3+ build (prefs, backup reader object; style JSON "marginBaseV"). */
    const val KEY = "r.marginBaseV"
    const val STYLE_KEY = "marginBaseV"
    fun toUi(actualDp: Int): Int = SideMargin.toUi(actualDp)
    fun toDp(ui: Int): Int = SideMargin.toDp(ui)
    fun label(ui: Int): String = SideMargin.label(ui)
    /** Values saved before U3 that equal the old untouched default 16/16. */
    fun isLegacyDefault(hasMarker: Boolean, top: Int, bottom: Int): Boolean =
        !hasMarker && top == LEGACY_DEFAULT_DP && bottom == LEGACY_DEFAULT_DP
}
