package com.ggumtak.readeraplus.reader

/**
 * Pure geometry and timing of the reader chrome (U §2.1, §2.2, §2.4, §3.4). Sizes in px; [density] = px per dp.
 * Everything here is decided outside layout passes (setVisible / bind), never in a layout listener.
 */
internal object ChromeMath {
    /** Room kept on each side of the centred page label: row padding 4 + rotation 48 + pin 48 + gap 8 (dp). */
    const val LABEL_RESERVE_DP = 108
    /** The top action row needs 8 + 7·48 = 344 dp plus a little spacer to also hold the bookmark (U §2.2). */
    const val BOOKMARK_MIN_ROW_DP = 352
    /**
     * The history row directly above the bottom panel (U §2.4): a 48 dp touch target like every other control of the
     * bars. It starts over the bottom bar's edge padding and the edge band covers its foot (ChromeBar), so on a phone
     * the bar grows by 44 dp with it.
     */
    const val HISTORY_ROW_DP = 48
    /** Every control of the bars is a touch target this tall (U §2.1); its glyph is [GLYPH_DP]. */
    const val TOUCH_DP = 48
    const val GLYPH_DP = 24
    /**
     * The bottom panel as ReadEra's on the user's S25 (2026-10-05, "우리 앱이 하단에 … 바가 훨씬 위로 크지"): the page
     * label's centre [LABEL_CENTRE_DP] below the panel's top, the seek track's [SEEK_CENTRE_DP] (36 dp apart, was 47), and
     * the seek row's 48 dp ending 24 dp below its track: [PANEL_DP] of content, then the bottom gap ([bottomGap]).
     */
    const val LABEL_CENTRE_DP = 25
    const val SEEK_CENTRE_DP = 61
    /** The label row: its 48 dp controls centred on [LABEL_CENTRE_DP] (1 dp spare above and below). */
    const val LABEL_ROW_DP = 2 * LABEL_CENTRE_DP
    /**
     * The seek row's top below the panel's top. It starts 12 dp inside the label row: the two rows' 48 dp touch areas
     * overlap exactly in the empty space between their 24 dp glyphs (37..49 dp), where the label row takes the touch.
     */
    const val SEEK_TOP_DP = SEEK_CENTRE_DP - TOUCH_DP / 2
    /** The panel's content (both rows), 85 dp: ReadEra's panel in the upper window of a split screen. */
    const val PANEL_DP = SEEK_TOP_DP + TOUCH_DP
    /**
     * Least space under the bottom panel in a window that reaches the screen's bottom (the user, 2026-10-05: ReadEra's
     * gap; the seek track's centre then sits 24 + 16 = 40 dp above the edge): fullscreen hides the navigation bar, so
     * its inset is 0 while the gesture handle still shows, and the Comet's bezel hides its outer rows.
     */
    const val BOTTOM_GAP_DP = 16
    /** The bars' show / hide on a phone (U §2.1 Motion): a short fade with a slide of [SLIDE_DP] toward their edge. */
    const val SHOW_MS = 180L
    const val HIDE_MS = 150L
    const val SLIDE_DP = 12

    /**
     * Fixed width of the bottom bar's page label: the row minus [LABEL_RESERVE_DP] on both sides, so a label centred on
     * the full width can never run under the right cluster (720 px at density 2 → 288 px).
     */
    fun labelMaxWidth(rowW: Int, density: Float): Int =
        (rowW - 2 * Math.round(LABEL_RESERVE_DP * density)).coerceAtLeast(0)

    /**
     * True when the history row (above the bottom panel, U §3.4) must use its short side labels ("‹ 12345" /
     * "23259 ›"): the row is three equal columns (back · 지우기 · forward), and a side label ([left],
     * [right]: text + paddings + glyph, 0 when hidden) must fit its own third of [rowW].
     */
    fun stripShort(left: Float, right: Float, rowW: Float): Boolean = maxOf(left, right) > rowW / 3f

    /**
     * The space under the bottom panel (one value, never a sum): the larger of the navigation bar's inset [barInset], the
     * strip the system keeps for its swipes [gestureInset] (home, recents) and [minGap] ([BOTTOM_GAP_DP] in px), so a
     * swipe that starts at the screen's edge never lands on the seek bar or the chapter buttons. A window that [floats]
     * above the display's bottom edge (the upper window of a split screen, a pop-up window) and has no bottom inset at
     * all has nothing of the system's under it: its panel ends at the window's edge, as ReadEra's (0). A window that
     * reaches the bottom (every full-screen one, the Comet's too) keeps the minimum.
     */
    fun bottomGap(barInset: Int, gestureInset: Int, minGap: Int, floats: Boolean): Int =
        if (floats && barInset <= 0 && gestureInset <= 0) 0 else maxOf(barInset, gestureInset, minGap)

    /** [dp] in px as the views get it (`TypedValue.applyDimension`, truncated). */
    private fun px(dp: Int, density: Float): Int = (dp * density).toInt()

    /** The page label's centre below the bottom panel's top, in px. */
    fun labelCentre(density: Float): Int = px(LABEL_ROW_DP, density) / 2

    /** The seek track's centre below the bottom panel's top, in px. */
    fun seekCentre(density: Float): Int = px(SEEK_TOP_DP, density) + px(TOUCH_DP, density) / 2

    /** The bottom panel's height in px: its rows ([PANEL_DP]) and the bottom [gap] ([bottomGap]). */
    fun panelHeight(density: Float, gap: Int): Int =
        maxOf(px(LABEL_ROW_DP, density), px(SEEK_TOP_DP, density) + px(TOUCH_DP, density)) + gap

    /** The seek track's centre above the screen's (or the window's) bottom edge, in px. */
    fun seekAboveBottom(density: Float, gap: Int): Int = panelHeight(density, gap) - seekCentre(density)

    /** The top-row bookmark is shown only when the bar is at least [BOOKMARK_MIN_ROW_DP] wide. */
    fun bookmarkFits(rowW: Int, density: Float): Boolean = rowW >= Math.round(BOOKMARK_MIN_ROW_DP * density)

    /**
     * The bars fade in and out only when their look allows motion (a phone, ChromePalette.motion) and the system
     * animates: an animator duration scale of 0 (개발자 옵션, or 접근성 "애니메이션 제거") shows and hides them at once.
     */
    fun animates(motion: Boolean, durationScale: Float): Boolean = motion && durationScale > 0f
}
