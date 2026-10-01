package com.ggumtak.readeraplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/** The pure day helpers and the speed rule of the R2 ReadingLog contract. */
class ReadingLogDaysTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun dayIsTheLocalDate() {
        // 2026-09-29 15:30 UTC is already the 30th in Seoul (UTC+9).
        val t = java.time.ZonedDateTime.of(2026, 9, 29, 15, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(20260930, ReadingLog.day(t, seoul))
        assertEquals(20260929, ReadingLog.day(t, ZoneId.of("UTC")))
    }

    @Test
    fun addDaysCrossesMonthsYearsAndLeapDays() {
        assertEquals(20261001, ReadingLog.addDays(20260930, 1))
        assertEquals(20260901, ReadingLog.addDays(20261001, -30))
        assertEquals(20270101, ReadingLog.addDays(20261231, 1))
        assertEquals(20280229, ReadingLog.addDays(20280228, 1))
        assertEquals(20260930, ReadingLog.addDays(20260930, 0))
        assertEquals(20260917, ReadingLog.addDays(20260930, -13)) // the 14-day window of cpm
        // Malformed input is clamped into a real date instead of throwing.
        assertEquals(20260228, ReadingLog.addDays(20260231, 0))
    }

    @Test
    fun speedNeedsEnoughTime() {
        assertEquals(720, ReadingLog.speed(600, 7200, ReadingLog.BOOK_MIN_SECONDS))
        assertNull(ReadingLog.speed(599, 7200, ReadingLog.BOOK_MIN_SECONDS))
        assertNull(ReadingLog.speed(0, 0, 0))
        assertNull(ReadingLog.speed(3600, 0, 0))
        assertEquals(1, ReadingLog.speed(3600, 1, 0))
        assertEquals(600, ReadingLog.DEFAULT_CPM)
    }

    @Test
    fun speedSaturatesInsteadOfWrapping() {
        // 1e17 chars in 10 minutes: 1e16 cpm, whose low 32 bits would be an arbitrary (even negative) speed.
        assertEquals(Int.MAX_VALUE, ReadingLog.speed(600, 100_000_000_000_000_000L, 0))
        assertEquals(Int.MAX_VALUE, ReadingLog.speed(1, Long.MAX_VALUE, 0))
    }
}
