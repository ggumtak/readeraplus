package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReadingTrackerTest {
    private val day = 20260930

    /** A resumed tracker with page 0 ([chars] long) shown at [t]. */
    private fun started(t: Long = 0L, chars: Int = 500): ReadingTracker =
        ReadingTracker().apply {
            resume(t)
            assertNull(onPageShown(t, day, chars))
        }

    @Test
    fun aPageCountsOnceShownForTwoSeconds() {
        val tr = started()
        tr.onPageShown(1_999, day, 400) // flipped past: not read
        tr.onPageShown(1_999 + 30_000, day, 300) // read for 30 s
        val d = tr.pause(1_999 + 30_000 + 2_500)!! // read for 2.5 s
        assertEquals(day, d.day)
        assertEquals(32L, d.seconds)
        assertEquals(2, d.pages)
        assertEquals(400L + 300L, d.chars)
    }

    @Test
    fun aPageLeftOnScreenCountsFiveMinutesAtMost() {
        val tr = started(chars = 700)
        val d = tr.pause(60 * 60_000L)!!
        assertEquals(300L, d.seconds)
        assertEquals(1, d.pages)
        assertEquals(700L, d.chars)
    }

    @Test
    fun nothingCountsWhilePaused() {
        val tr = started()
        tr.onPageShown(10_000, day, 100)
        assertNotNull(tr.pause(20_000))
        // TTS turning pages with the screen off: the pages are noted, their time is not the reader's.
        assertNull(tr.onPageShown(80_000, day, 200))
        assertNull(tr.onPageShown(140_000, day, 300))
        assertNull(tr.pause(150_000))
        // Back in front: the page on screen counts from the resume.
        tr.resume(1_000_000)
        val d = tr.pause(1_010_000)!!
        assertEquals(10L, d.seconds)
        assertEquals(1, d.pages)
        assertEquals(300L, d.chars)
    }

    @Test
    fun anEmptyPauseFlushesNothing() {
        val tr = started()
        assertNull(tr.pause(1_000))
        assertNull(ReadingTracker().pause(5_000))
    }

    @Test
    fun theNextPageAfterMidnightFlushesTheDayBefore() {
        val tr = started()
        assertNull(tr.onPageShown(10_000, day, 200))
        val done = tr.onPageShown(40_000, day + 1, 300)!!
        assertEquals(day, done.day)
        assertEquals(40L, done.seconds)
        assertEquals(2, done.pages)
        val next = tr.pause(50_000)!!
        assertEquals(day + 1, next.day)
        assertEquals(10L, next.seconds)
        assertEquals(1, next.pages)
    }

    @Test
    fun flushClosesThePageAndKeepsCounting() {
        val tr = started(chars = 250)
        val d = tr.flush(20_000)!!
        assertEquals(20L, d.seconds)
        assertEquals(1, d.pages)
        // The same page keeps counting from the flush (the end panel stays on the last page).
        val later = tr.pause(26_000)!!
        assertEquals(6L, later.seconds)
        assertEquals(1, later.pages)
    }

    @Test
    fun forgetPageStopsCountingUntilTheNextBookShows() {
        val tr = started()
        tr.flush(5_000)
        tr.forgetPage()
        assertNull(tr.pause(60_000))
        tr.resume(70_000)
        assertNull(tr.onPageShown(70_000, day, 120))
        assertEquals(3L, tr.pause(73_000)!!.seconds)
    }

    @Test
    fun subSecondRestsAddUp() {
        val tr = started()
        tr.onPageShown(2_600, day, 100)
        assertEquals(5L, tr.flush(5_200)!!.seconds) // 2.6 s + 2.6 s = 5.2 s: 5 s now …
        assertEquals(3L, tr.pause(5_200 + 2_900)!!.seconds) // … and the 0.2 s rest joins the next 2.9 s
    }

    @Test
    fun dayClockFollowsTheLocalDate() {
        val zone = ZoneId.of("Asia/Seoul")
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
            LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
        val c = DayClock(zone)
        assertEquals(20260930, c.day(at(2026, 9, 30, 23, 59)))
        assertEquals(20260930, c.day(at(2026, 9, 30, 0, 0)))
        assertEquals(20261001, c.day(at(2026, 10, 1, 0, 0)))
        assertEquals(20261231, c.day(at(2026, 12, 31, 12, 0)))
        assertEquals(20270101, c.day(at(2027, 1, 1, 0, 1)))
        // A clock set back is followed too.
        assertEquals(20260930, c.day(at(2026, 9, 30, 8, 0)))
    }
}
