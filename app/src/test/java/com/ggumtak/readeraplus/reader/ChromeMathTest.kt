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
     * The px helpers mirror the views' arithmetic; ReaderChrome builds the label and seek rows' LayoutParams from the
     * same constants (LABEL_ROW_DP, SEEK_TOP_DP, TOUCH_DP), ReturnNav the history labels' padding from
     * HISTORY_TEXT_TOP_DP, and the title's margin from TITLE_LIFT_DP. The views themselves are checked by CI 13t / 13u
     * and the device checklist (11f-14 to 11f-18).
     */
    @Test
    fun theBottomPanelIsAsLowAsReadEras() {
        // The user's S25 split screen beside ReadEra (2026-10-05; ReadEra in the lower window, above its own navigation
        // strip): its page label 25 dp under the panel's top, its seek track 61 dp (36 dp apart; ours were 47), 24 dp
        // from the track to the panel's content bottom: 85 dp.
        for (density in listOf(2f, 3f)) {
            assertEquals(Math.round(25 * density), ChromeMath.labelCentre(density))
            assertEquals(Math.round(61 * density), ChromeMath.seekCentre(density))
            assertEquals(Math.round(85 * density), ChromeMath.panelHeight(density, gap = 0))
        }
        assertEquals(85, ChromeMath.PANEL_DP)
        // A 2.625 phone (420 dpi) truncates like the views: within a px of the same dp.
        assertEquals(61 * 2.625f, ChromeMath.seekCentre(2.625f).toFloat(), 1f)
    }

    @Test
    fun theTwoRowsShareOnlyTheSpaceBetweenTheirGlyphs() {
        // Every control keeps its 48 dp target: the label row's (rotation, pin, the label) run 1..49 dp under the panel's
        // top, the seek row's (⏮, the seek bar, ⏭) 37..85. They overlap by 12 dp, exactly the empty space between the
        // 24 dp glyphs (the label row's end at 37, the seek row's start at 49): no glyph lies in the other row's target.
        val half = ChromeMath.TOUCH_DP / 2
        val glyph = ChromeMath.GLYPH_DP / 2
        val labelTouch = ChromeMath.LABEL_CENTRE_DP - half..ChromeMath.LABEL_CENTRE_DP + half
        val seekTouch = ChromeMath.SEEK_TOP_DP..ChromeMath.SEEK_TOP_DP + ChromeMath.TOUCH_DP
        assertEquals(1..49, labelTouch)
        assertEquals(37..85, seekTouch)
        assertEquals(ChromeMath.LABEL_CENTRE_DP + glyph, seekTouch.first)
        assertEquals(ChromeMath.SEEK_CENTRE_DP - glyph, labelTouch.last)
        assertTrue(labelTouch.first >= 0)
        assertEquals(ChromeMath.PANEL_DP, seekTouch.last)
        assertTrue(ChromeMath.LABEL_ROW_DP <= ChromeMath.PANEL_DP)
    }

    @Test
    fun s25FullScreenKeepsTheGestureStrip() {
        // 전체 화면 with gesture navigation: no bar inset, the 48 px mandatory gesture strip (16 dp) under the panel. The
        // seek track's centre sits 40 dp above the edge (ReadEra's ≈ 39), the panel 101 dp (ReadEra's 100).
        val gap = ChromeMath.bottomGap(barInset = 0, gestureInset = 48, minGap = 48, floats = false)
        assertEquals(48, gap)
        assertEquals(120, ChromeMath.seekAboveBottom(3f, gap))   // 40 dp
        assertEquals(303, ChromeMath.panelHeight(3f, gap))       // 101 dp
        // 전체 화면 off: the navigation bar's inset is the larger one; never the sum.
        assertEquals(63, ChromeMath.bottomGap(barInset = 63, gestureInset = 48, minGap = 48, floats = false))
    }

    @Test
    fun s25SplitScreenUpperWindowEndsAtItsEdge() {
        // Our upper window has no bottom inset at all (the system's strip is under the lower window): no gap, 85 dp of
        // rows (ReadEra's panel content, measured in the lower window above its own 15 dp navigation strip).
        val gap = ChromeMath.bottomGap(barInset = 0, gestureInset = 0, minGap = 48, floats = true)
        assertEquals(0, gap)
        assertEquals(255, ChromeMath.panelHeight(3f, gap))       // 85 dp
        assertEquals(72, ChromeMath.seekAboveBottom(3f, gap))    // 24 dp
        // The lower window reaches the screen's bottom: its strip stays under the panel.
        assertEquals(48, ChromeMath.bottomGap(barInset = 48, gestureInset = 48, minGap = 48, floats = false))
        // A floating window that still has a bottom inset (a pop-up over the strip) keeps it.
        assertEquals(48, ChromeMath.bottomGap(barInset = 0, gestureInset = 48, minGap = 48, floats = true))
    }

    @Test
    fun aWindowOfUnknownPositionKeepsTheGap() {
        // Before API 30 ReaderWindow.floatsAboveBottom answers false for every window: a lower split window in full
        // screen reports no bottom inset there (insetsOf zeroes the bars, no gesture inset before API 29), so taken as
        // floating it would lose the 16 dp and end its seek row on the navigation bar.
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
    fun theTitleStartsInTheActionRowsEmptyFoot() {
        // ReadEra (the user's S25 split screen, 2026-10-05): the title's glyph centre ≈ 59.8 dp under the bar's top, the
        // surface's end ≈ 83.7 dp; ours were 68.8 / 91.7 dp, the action icons at the same 28 dp. At 18 sp the title's
        // glyphs start ≈ 4 dp (13 px) under its box's top and centre ≈ 12.7 dp (38 px) into it; the box is ≈ 36 dp.
        val titleTop = ChromeMath.ACTIONS_ROW_DP - ChromeMath.TITLE_LIFT_DP
        assertEquals(47, titleTop)
        assertEquals(59.8f, titleTop + 38 / 3f, 1f)
        assertEquals(83.7f, titleTop + 36f, 1f)
        // The buttons' 40 dp pressed circle ends at 48 dp: the glyphs stay under it; the title takes no touch.
        val circleBottom = (ChromeMath.ACTIONS_ROW_DP + 40) / 2
        assertEquals(48, circleBottom)
        assertTrue(titleTop + 4 > circleBottom)
    }

    @Test
    fun cometFullScreenKeepsTheMinimumGap() {
        // No gesture navigation, no bar in full screen, a bezel over the outer rows: the 16 dp minimum, never dropped
        // for a window that reaches the bottom.
        val gap = ChromeMath.bottomGap(barInset = 0, gestureInset = 0, minGap = 32, floats = false)
        assertEquals(32, gap)
        assertEquals(80, ChromeMath.seekAboveBottom(2f, gap))    // 40 dp, as on the S25
        assertEquals(202, ChromeMath.panelHeight(2f, gap))       // 85 + 16 dp
        assertEquals(50, ChromeMath.labelCentre(2f))
        assertEquals(122, ChromeMath.seekCentre(2f))
        assertEquals(16, ChromeMath.BOTTOM_GAP_DP)
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
}
