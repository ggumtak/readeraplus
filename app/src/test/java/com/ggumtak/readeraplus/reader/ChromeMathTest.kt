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
