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
    fun stripUsesTheShortFormOnlyWhenTheLabelsCollide() {
        // Comet, density 2, 15 sp: "‹ 10 페이지로" (≈ 222 px with paddings and the chevron) + "지우기" (144 px min).
        assertFalse(ChromeMath.stripShort(222f, 154f, 0f, 720f, 16f))
        assertFalse(ChromeMath.stripShort(222f, 154f, 222f, 720f, 16f))
        // 1.3× font scale with 5-digit pages: "‹ 12345 페이지로" + "23259 페이지로 ›" ≈ 326 px each.
        assertTrue(ChromeMath.stripShort(326f, 181f, 326f, 720f, 16f))
        // A side that reaches the centred box alone is enough.
        assertTrue(ChromeMath.stripShort(300f, 154f, 0f, 720f, 16f))
        assertTrue(ChromeMath.stripShort(0f, 154f, 300f, 720f, 16f))
        // The total alone overflows a narrow row.
        assertTrue(ChromeMath.stripShort(100f, 100f, 100f, 300f, 16f))
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
