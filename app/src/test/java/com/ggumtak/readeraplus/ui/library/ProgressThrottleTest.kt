package com.ggumtak.readeraplus.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThrottleTest {

    @Test
    fun firstPostWaitsAnIntervalAfterTheStartPost() {
        val t = ProgressThrottle(1000L, lastPostAt = 5_000L)
        assertFalse(t.ready(5_000L))
        assertFalse(t.ready(5_999L))
        assertTrue(t.ready(6_000L))
        assertFalse(t.ready(6_500L))
        assertTrue(t.ready(7_100L))
    }

    @Test
    fun fastImportPostsAboutOncePerSecond() {
        // 400 files skipped 2 ms apart (0.8 s) + 400 more at 10 ms (4 s): one post per elapsed second, not 800.
        val t = ProgressThrottle(1000L, lastPostAt = 0L)
        var now = 0L
        var posts = 0
        repeat(400) { now += 2; if (t.ready(now)) posts++ }
        assertEquals(0, posts)
        repeat(400) { now += 10; if (t.ready(now)) posts++ }
        assertEquals(4, posts)
    }

    @Test
    fun clockGoingBackwardsLetsAPostThrough() {
        val t = ProgressThrottle(1000L, lastPostAt = 10_000L)
        assertTrue(t.ready(9_000L))
        assertFalse(t.ready(9_500L))
        assertTrue(t.ready(10_000L))
    }
}
