package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeMathTest {

    @Test
    fun labelWidthKeepsTheRightClusterFree() {
        assertEquals(288, ChromeMath.labelMaxWidth(720, 2f))
        assertEquals(144, ChromeMath.labelMaxWidth(360, 1f))
        assertEquals(0, ChromeMath.labelMaxWidth(100, 2f))
    }

    @Test
    fun labelCentreIsTheRowCentre() {
        for (rowW in listOf(720, 1080, 1440)) {
            val max = ChromeMath.labelMaxWidth(rowW, 2f)
            // The label box is centred on the full row (Gravity.CENTER), whatever text it holds.
            val left = (rowW - max) / 2f
            assertEquals(rowW / 2f, left + max / 2f, 0.5f)
            // ...and it never reaches the right cluster (4 + 48 + 48 dp from the end, plus an 8 dp gap).
            assertTrue(left + max <= rowW - 2 * 104)
        }
    }

    @Test
    fun historyRowUsesTheShortFormOnlyWhenASideOverflowsItsThird() {
        // Comet, density 2, 14 sp: "‹ 10쪽으로" ≈ 199 px with paddings and the chevron, in a 240 px third.
        assertFalse(ChromeMath.stripShort(199f, 0f, 720f))
        assertFalse(ChromeMath.stripShort(199f, 199f, 720f))
        // 1.3× font scale with 5-digit pages: "‹ 12345쪽으로" ≈ 293 px; one side is enough, whichever it is.
        assertTrue(ChromeMath.stripShort(293f, 0f, 720f))
        assertTrue(ChromeMath.stripShort(0f, 293f, 720f))
        // Exactly a third still fits.
        assertFalse(ChromeMath.stripShort(240f, 240f, 720f))
        assertTrue(ChromeMath.stripShort(240.5f, 0f, 720f))
        // Hidden sides measure 0: an empty side never shortens the other.
        assertFalse(ChromeMath.stripShort(0f, 0f, 720f))
    }

    @Test
    fun historyRowThirdsFollowTheRowWidth() {
        // Three equal columns (a hidden side is INVISIBLE, so its column stays): the S25's third is 360 px, the
        // Comet's 240 px, so the same label is short on the Comet only.
        assertTrue(ChromeMath.stripShort(293f, 0f, 720f))
        assertFalse(ChromeMath.stripShort(293f, 0f, 1080f))
        assertFalse(ChromeMath.stripShort(360f, 360f, 1080f))
        // The row sits right above the panel and is a 48 dp touch target, like every other control of the bars.
        assertEquals(48, ChromeMath.HISTORY_ROW_DP)
    }

    /**
     * The px helpers mirror the views' arithmetic; ReaderChrome builds the three rows' LayoutParams from the same
     * constants (SEEK_ROW_DP, LABEL_ROW_DP, BUTTONS_ROW_DP), ReturnNav the history labels' padding from
     * HISTORY_TEXT_TOP_DP, and the title row from TITLE_ROW_DP. The views themselves are checked by CI 13t / 13u and the
     * device checklist (11f-14 to 11f-18).
     */
    @Test
    fun theBottomPanelStacksSeekLabelAndButtons() {
        // Seek row 0..48 dp under the panel's top, label row 48..96, the two big buttons 96..152.
        assertEquals(48, ChromeMath.SEEK_ROW_DP)
        assertEquals(48, ChromeMath.LABEL_ROW_DP)
        assertEquals(152, ChromeMath.PANEL_DP)
        for (density in listOf(2f, 3f)) {
            assertEquals(Math.round(24 * density), ChromeMath.seekCentre(density))
            assertEquals(Math.round(72 * density), ChromeMath.labelCentre(density))
            assertEquals(Math.round(152 * density), ChromeMath.panelHeight(density, gap = 0))
        }
        // A 2.625 phone (420 dpi) truncates like the views: within a px of the same dp.
        assertEquals(24 * 2.625f, ChromeMath.seekCentre(2.625f).toFloat(), 1f)
        assertEquals(152 * 2.625f, ChromeMath.panelHeight(2.625f, gap = 0).toFloat(), 3f)
    }

    @Test
    fun theTwoControlRowsKeepTheirFull48dpTargets() {
        // Rows one under the other: the seek row's controls (⏮, the bar, ⏭) and the label row's (the label, the rotation
        // lock, the pin) are 48 dp tall boxes in 48 dp rows, so no touch area overlaps another's and none is clipped.
        assertEquals(ChromeMath.TOUCH_DP, ChromeMath.SEEK_ROW_DP)
        assertEquals(ChromeMath.TOUCH_DP, ChromeMath.LABEL_ROW_DP)
        assertEquals(ChromeMath.PANEL_DP, ChromeMath.SEEK_ROW_DP + ChromeMath.LABEL_ROW_DP + ChromeMath.BUTTONS_ROW_DP)
        // The big buttons hold a 24 dp glyph over a 14 sp label (≈ 17 dp line + 3 dp gap) with room to spare.
        assertTrue(ChromeMath.BUTTONS_ROW_DP >= ChromeMath.GLYPH_DP + 3 + 17 + 12)
    }

    @Test
    fun s25FullScreenNoLongerKeepsTheGestureStripFree() {
        // 전체 화면 with gesture navigation: no bar inset; the 48 px mandatory gesture strip (16 dp) is not kept free
        // (2026-10-11), only the 8 dp minimum.
        val gap = ChromeMath.bottomGap(barInset = 0, gestureInset = 48, minGap = 24, floats = false)
        assertEquals(24, gap)
        assertEquals(408, ChromeMath.seekAboveBottom(3f, gap))   // the track's centre 136 dp above the edge
        assertEquals(480, ChromeMath.panelHeight(3f, gap))       // 160 dp
        // 전체 화면 off: the navigation bar's inset is kept (the panel never sits on the bar); never a sum.
        assertEquals(63, ChromeMath.bottomGap(barInset = 63, gestureInset = 48, minGap = 24, floats = false))
    }

    @Test
    fun s25SplitScreenUpperWindowEndsAtItsEdge() {
        // Our upper window has no bottom inset at all (the system's strip is under the lower window): no gap, 152 dp of
        // rows.
        val gap = ChromeMath.bottomGap(barInset = 0, gestureInset = 0, minGap = 24, floats = true)
        assertEquals(0, gap)
        assertEquals(456, ChromeMath.panelHeight(3f, gap))       // 152 dp
        assertEquals(384, ChromeMath.seekAboveBottom(3f, gap))   // 128 dp
        // The lower window reaches the screen's bottom: its navigation bar stays under the panel.
        assertEquals(48, ChromeMath.bottomGap(barInset = 48, gestureInset = 48, minGap = 24, floats = false))
        // A floating window over the strip (a pop-up) keeps the minimum, not the strip.
        assertEquals(24, ChromeMath.bottomGap(barInset = 0, gestureInset = 48, minGap = 24, floats = true))
    }

    @Test
    fun aWindowOfUnknownPositionKeepsTheGap() {
        // Before API 30 ReaderWindow.floatsAboveBottom answers false for every window: a lower split window in full
        // screen reports no bottom inset there (insetsOf zeroes the bars, no gesture inset before API 29), so taken as
        // floating it would lose the minimum gap and end its seek row on the screen's edge.
        assertEquals(48, ChromeMath.bottomGap(barInset = 0, gestureInset = 0, minGap = 48, floats = false))
        assertEquals(0, ChromeMath.bottomGap(barInset = 0, gestureInset = 0, minGap = 48, floats = true))
    }

    @Test
    fun theHistoryRowTextSitsAsLowAsReadEras() {
        // ReadEra in the user's S25 split screen (2026-10-05): its history text's centre 55–56.5 px (≈ 18.5 dp) above its
        // panel, the glyphs ≈ 25 px (8.3 dp) above the shadow. Ours, centred in the 48 dp box, sat 72 px (24 dp) above.
        val fromTop = (ChromeMath.HISTORY_ROW_DP + ChromeMath.HISTORY_TEXT_TOP_DP) / 2
        val abovePanel = ChromeMath.HISTORY_ROW_DP - fromTop
        assertEquals(29, fromTop)
        assertEquals(19, abovePanel)
        assertEquals(56.5f, abovePanel * 3f, 1f)                 // 57 px on the S25
        // A 14 sp line's glyphs reach ≈ 6 dp under their centre: ≈ 9 dp clear of the 4 dp shadow over the row's foot.
        assertTrue(ChromeMath.HISTORY_ROW_DP - 4 - (fromTop + 6) in 8..10)
        // The box keeps the bars' 48 dp touch target.
        assertEquals(ChromeMath.TOUCH_DP, ChromeMath.HISTORY_ROW_DP)
    }

    @Test
    fun theTopBarIsRidisHeight() {
        // RIDI on the user's 1080 × 2340 S25 (2.8125 px per dp): the bar ≈ 93 dp, the icons' row ≈ 52 dp and the title's
        // row ≈ 41 dp, the title's text on the 19 dp keyline.
        assertEquals(52, ChromeMath.ACTIONS_ROW_DP)
        assertEquals(41, ChromeMath.TITLE_ROW_DP)
        assertEquals(93, ChromeMath.TOP_BAR_DP)
        assertEquals(19, ChromeMath.TITLE_START_DP)
        // The 48 dp buttons are centred in the action row, 2 dp spare over and under.
        assertEquals(2, (ChromeMath.ACTIONS_ROW_DP - ChromeMath.TOUCH_DP) / 2)
        // Back and six icons (듣기, 목차, 검색, 독서 노트, 북마크, ⋮) at 48 dp with 4 dp of padding on both sides: the
        // width the bookmark guard asks for holds all of them with a spacer to spare.
        val needed = 2 * 4 + 7 * ChromeMath.TOUCH_DP
        assertEquals(344, needed)
        assertTrue(ChromeMath.BOOKMARK_MIN_ROW_DP > needed)
    }

    @Test
    fun cometFullScreenKeepsTheMinimumGap() {
        // No gesture navigation, no bar in full screen, a bezel over the outer rows: the 8 dp minimum, never dropped
        // for a window that reaches the bottom.
        val gap = ChromeMath.bottomGap(barInset = 0, gestureInset = 0, minGap = 16, floats = false)
        assertEquals(16, gap)
        assertEquals(272, ChromeMath.seekAboveBottom(2f, gap))   // 136 dp, as on the S25
        assertEquals(320, ChromeMath.panelHeight(2f, gap))       // 152 + 8 dp
        assertEquals(144, ChromeMath.labelCentre(2f))
        assertEquals(48, ChromeMath.seekCentre(2f))
        assertEquals(8, ChromeMath.BOTTOM_GAP_DP)
    }

    @Test
    fun barsFadeOnlyWithMotionAndSystemAnimations() {
        assertTrue(ChromeMath.animates(motion = true, durationScale = 1f))
        assertTrue(ChromeMath.animates(motion = true, durationScale = 0.5f))
        assertTrue(ChromeMath.animates(motion = true, durationScale = 10f))
        // 개발자 옵션 "애니메이션 꺼짐" or 접근성 "애니메이션 제거": the scale is 0, the bars switch at once.
        assertFalse(ChromeMath.animates(motion = true, durationScale = 0f))
        // E-ink (or a device class not probed yet): never, whatever the system says.
        assertFalse(ChromeMath.animates(motion = false, durationScale = 1f))
        assertFalse(ChromeMath.animates(motion = false, durationScale = 0f))
    }

    @Test
    fun showAndHideAreShort() {
        // The user's 150–200 ms.
        assertTrue(ChromeMath.SHOW_MS in 150L..200L)
        assertTrue(ChromeMath.HIDE_MS in 150L..200L)
        assertTrue(ChromeMath.HIDE_MS <= ChromeMath.SHOW_MS)
        assertTrue(ChromeMath.SLIDE_DP in 1..24)
    }

    @Test
    fun bookmarkFitsFlipsAt352dp() {
        assertFalse(ChromeMath.bookmarkFits(703, 2f))
        assertTrue(ChromeMath.bookmarkFits(704, 2f))
        assertTrue(ChromeMath.bookmarkFits(720, 2f))
        assertFalse(ChromeMath.bookmarkFits(351, 1f))
        assertTrue(ChromeMath.bookmarkFits(352, 1f))
        assertTrue(ChromeMath.bookmarkFits(1080, 3f))
        assertFalse(ChromeMath.bookmarkFits(1050, 3f))
    }

    @Test
    fun seekThumbnailTakesPartOfTheWidthCappedByHeight() {
        // S25 portrait at 3x (1080 wide, 2200 above the panel), a 1.6 page: 42 % of the width = 453 px (< 660 px cap).
        assertEquals(453 to 725, ChromeMath.seekThumbSize(1080, 2200, 1.6f, 3f))
        // The Comet at 2x (720 wide): 42 % = 302 px, under the 440 px cap.
        assertEquals(302 to 483, ChromeMath.seekThumbSize(720, 1100, 1.6f, 2f))
        // Landscape at 2x, a 1.5 page: half of the 600 px left above the panel caps the height, the width follows.
        assertEquals(200 to 300, ChromeMath.seekThumbSize(2200, 600, 1.5f, 2f))
        // At 3x the same room leaves under 72 dp of width: no thumbnail.
        assertEquals(null, ChromeMath.seekThumbSize(2200, 600, 1.5f, 3f))
        // A wide window: the 220 dp cap.
        assertEquals(660 to 1056, ChromeMath.seekThumbSize(4000, 4000, 1.6f, 3f))
        // Too small, or nothing to draw for.
        assertEquals(null, ChromeMath.seekThumbSize(300, 200, 1.6f, 3f))
        assertEquals(null, ChromeMath.seekThumbSize(1080, 2200, 0f, 3f))
    }
}
