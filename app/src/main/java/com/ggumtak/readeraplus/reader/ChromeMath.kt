package com.ggumtak.readeraplus.reader

/**
 * Pure geometry and timing of the reader chrome (U §2.1, §2.2, §2.4, §3.4). Sizes in px; [density] = px per dp.
 * Everything here is decided outside layout passes (setVisible / bind), never in a layout listener.
 */
internal object ChromeMath {
    /** Room kept on each side of the centred page label: row padding 4 + rotation 48 + pin 48 + gap 8 (dp). */
    const val LABEL_RESERVE_DP = 108
    /**
     * The top action row needs 8 + 7·48 = 344 dp (back, 듣기, 목차, 검색, 독서 노트, 북마크, ⋮) plus a little spacer to also
     * hold the bookmark (U §2.2).
     */
    const val BOOKMARK_MIN_ROW_DP = 352
    /**
     * The history row directly above the bottom panel (U §2.4): a 48 dp touch target like every other control of the
     * bars. It starts over the bottom bar's edge padding and the edge band covers its foot (ChromeBar), so on a phone
     * the bar grows by 44 dp with it.
     */
    const val HISTORY_ROW_DP = 48
    /**
     * The history row's labels pad their top by this much inside their 48 dp boxes (touch target and bar height
     * unchanged; the pressed rect starts here too, centred on the words): their text, centred in the rest, sits
     * (48 + 10) / 2 = 29 dp under the row's top, 19 dp (57 px on the S25) above the panel, and its glyphs end ≈ 9 dp
     * above the 4 dp shadow. ReadEra's, in the user's S25 split screen (2026-10-05): 18.5–18.8 dp and ≈ 8.3 dp; centred
     * in the box (24 dp) ours read 5 dp higher.
     */
    const val HISTORY_TEXT_TOP_DP = 10
    /** Every control of the bars is a touch target this tall (U §2.1); its glyph is [GLYPH_DP]. */
    const val TOUCH_DP = 48
    const val GLYPH_DP = 24
    /** The top bar's action row: its 48 dp buttons centred, so 2 dp of it lie under and over them (RIDI's ≈ 52 dp). */
    const val ACTIONS_ROW_DP = 52
    /**
     * The book title's own row under the action row (RIDI: ≈ 41 dp of title row, 15 sp text on the 19 dp keyline): the
     * whole top bar is [TOP_BAR_DP] = 93 dp, status-bar inset aside. The row takes no touch.
     */
    const val TITLE_ROW_DP = 41
    const val TITLE_START_DP = 19
    const val TOP_BAR_DP = ACTIONS_ROW_DP + TITLE_ROW_DP
    /**
     * The bottom panel's three rows, one under the other: the seek row (⏮ seek bar ⏭), the page label row (the label
     * centred, the rotation lock and the pin at the right) and the two big buttons (보기 설정, 뷰어 설정). Each control of
     * the first two keeps its 48 dp touch target, so the rows do not overlap. [PANEL_DP] of content, then the bottom gap
     * ([bottomGap]).
     */
    const val SEEK_ROW_DP = TOUCH_DP
    const val LABEL_ROW_DP = TOUCH_DP
    /** Just the glyph, its label and 6 dp around them (the user, 2026-10-11: less room under the two buttons). */
    const val BUTTONS_ROW_DP = 56
    const val PANEL_DP = SEEK_ROW_DP + LABEL_ROW_DP + BUTTONS_ROW_DP
    /**
     * Least space under the bottom panel in a window that reaches the screen's bottom: the Comet's bezel hides its outer
     * rows. It was ReadEra's 16 dp (2026-10-05); the user asked for less room under the buttons (2026-10-11).
     */
    const val BOTTOM_GAP_DP = 8
    /** The seek preview's thumbnail: [seekThumbSize]. */
    const val SEEK_THUMB_WIDTH = 0.42f
    const val SEEK_THUMB_MAX_DP = 220
    const val SEEK_THUMB_MIN_DP = 72
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
     * The space under the bottom panel (one value, never a sum): the larger of the navigation bar's inset [barInset] and
     * [minGap] ([BOTTOM_GAP_DP] in px). The strip the system keeps for its swipes [gestureInset] (home, recents) is no
     * longer kept free (the user, 2026-10-11: an edge swipe does not press the buttons); it only tells whether a
     * floating window has anything of the system's under it. A window that [floats]
     * above the display's bottom edge (the upper window of a split screen, a pop-up window) and has no bottom inset at
     * all has nothing of the system's under it, so it gets no gap (0): its panel ends at the window's edge with
     * [PANEL_DP] of rows. A window that reaches the bottom (every full-screen one, the Comet's too, and
     * any window whose position is unknown: `ReaderWindow.floatsAboveBottom` before API 30) keeps the minimum.
     */
    fun bottomGap(barInset: Int, gestureInset: Int, minGap: Int, floats: Boolean): Int =
        if (floats && barInset <= 0 && gestureInset <= 0) 0 else maxOf(barInset, minGap)

    /** [dp] in px as the views get it (`TypedValue.applyDimension`, truncated). */
    private fun px(dp: Int, density: Float): Int = (dp * density).toInt()

    /** The seek track's centre below the bottom panel's top, in px. */
    fun seekCentre(density: Float): Int = px(SEEK_ROW_DP, density) / 2

    /** The page label's centre below the bottom panel's top, in px. */
    fun labelCentre(density: Float): Int = px(SEEK_ROW_DP, density) + px(LABEL_ROW_DP, density) / 2

    /** The bottom panel's height in px: its three rows ([PANEL_DP]) and the bottom [gap] ([bottomGap]). */
    fun panelHeight(density: Float, gap: Int): Int =
        px(SEEK_ROW_DP, density) + px(LABEL_ROW_DP, density) + px(BUTTONS_ROW_DP, density) + gap

    /** The seek track's centre above the screen's (or the window's) bottom edge, in px. */
    fun seekAboveBottom(density: Float, gap: Int): Int = panelHeight(density, gap) - seekCentre(density)

    /**
     * The seek preview's page thumbnail in px, (width, height) for a page of [aspect] (height / width): at most
     * [SEEK_THUMB_MAX_DP] wide and [SEEK_THUMB_WIDTH] of the window's [windowW], at most half of [availH] (the window
     * above the bottom panel) tall. Null when that leaves less than [SEEK_THUMB_MIN_DP] of width (a tiny window).
     */
    fun seekThumbSize(windowW: Int, availH: Int, aspect: Float, density: Float): Pair<Int, Int>? {
        if (windowW <= 0 || availH <= 0 || aspect <= 0f || density <= 0f) return null
        var w = minOf(windowW * SEEK_THUMB_WIDTH, SEEK_THUMB_MAX_DP * density)
        val maxH = availH / 2f
        if (w * aspect > maxH) w = maxH / aspect
        if (w < SEEK_THUMB_MIN_DP * density) return null
        return w.toInt() to (w * aspect).toInt().coerceAtLeast(1)
    }

    /** The top-row bookmark is shown only when the bar is at least [BOOKMARK_MIN_ROW_DP] wide. */
    fun bookmarkFits(rowW: Int, density: Float): Boolean = rowW >= Math.round(BOOKMARK_MIN_ROW_DP * density)

    /**
     * The bars fade in and out only when their look allows motion (a phone, ChromePalette.motion) and the system
     * animates: an animator duration scale of 0 (개발자 옵션, or 접근성 "애니메이션 제거") shows and hides them at once.
     */
    fun animates(motion: Boolean, durationScale: Float): Boolean = motion && durationScale > 0f
}
