package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbBudgetTest {
    private val mb = 1024 * 1024

    @Test
    fun einkCapIs8Mb() {
        assertEquals(8 * mb, ThumbBudget.budget(256, eink = true))
        assertEquals(8 * mb, ThumbBudget.budget(512, eink = true))
    }

    @Test
    fun phoneCapIs16Mb() {
        assertEquals(16 * mb, ThumbBudget.budget(512, eink = false))
        assertEquals(16 * mb, ThumbBudget.budget(1024, eink = false))
    }

    @Test
    fun oneThirtySecondOfTheMemoryClass() {
        assertEquals(6 * mb, ThumbBudget.budget(192, eink = true))
        assertEquals(4 * mb, ThumbBudget.budget(128, eink = false))
        assertEquals(12 * mb, ThumbBudget.budget(384, eink = false))
    }

    @Test
    fun floorForTinyOrBogusClasses() {
        assertEquals(ThumbBudget.MIN_BYTES, ThumbBudget.budget(32, eink = true))
        assertEquals(ThumbBudget.MIN_BYTES, ThumbBudget.budget(0, eink = false))
        assertEquals(ThumbBudget.MIN_BYTES, ThumbBudget.budget(-1, eink = false))
        assertEquals(16 * mb, ThumbBudget.budget(Int.MAX_VALUE, eink = false))
    }
}
