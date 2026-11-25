package com.ggumtak.readeraplus.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverRetryTest {
    private val wait = CoverRetry.WAIT_MS

    @Test
    fun aKeyThatNeverFailedMayBeTried() {
        assertTrue(CoverRetry.mayTry(null, 0L, wait))
        assertTrue(CoverRetry.mayTry(null, 123_456L, wait))
    }

    @Test
    fun aFailureBlocksTheKeyUntilTheWaitRunsOut() {
        val failed = 100_000L
        assertFalse(CoverRetry.mayTry(failed, failed, wait))
        assertFalse(CoverRetry.mayTry(failed, failed + wait - 1, wait))
        assertTrue(CoverRetry.mayTry(failed, failed + wait, wait))
        assertTrue(CoverRetry.mayTry(failed, failed + 10 * wait, wait))
    }

    @Test
    fun aClockThatWentBackDoesNotBlockTheKeyForGood() {
        assertTrue(CoverRetry.mayTry(100_000L, 99_999L, wait))
        assertTrue(CoverRetry.mayTry(100_000L, 0L, wait))
    }

    @Test
    fun pruneLeavesAMapWithinTheLimitAlone() {
        val m = hashMapOf("a" to 0L, "b" to 0L)
        CoverRetry.prune(m, 10 * wait, wait, 2)
        assertEquals(setOf("a", "b"), m.keys)
    }

    @Test
    fun pruneOverTheLimitDropsTheExpiredFirst() {
        val now = 10 * wait
        val m = hashMapOf("old1" to 0L, "old2" to 0L, "fresh" to now - 1)
        CoverRetry.prune(m, now, wait, 2)
        assertEquals(setOf("fresh"), m.keys)
    }

    @Test
    fun pruneOverTheLimitWithNothingExpiredClearsAll() {
        val now = 10 * wait
        val m = hashMapOf("a" to now, "b" to now - 1, "c" to now - 2)
        CoverRetry.prune(m, now, wait, 2)
        assertTrue(m.isEmpty())
    }
}
