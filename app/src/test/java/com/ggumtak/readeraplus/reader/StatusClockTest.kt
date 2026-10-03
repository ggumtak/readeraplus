package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class StatusClockTest {
    @Test
    fun matchesCalendarInSeveralZones() {
        val zones = listOf("Asia/Seoul", "UTC", "America/New_York", "Asia/Kolkata", "Pacific/Chatham", "America/St_Johns")
        val times = longArrayOf(0L, 1_700_000_000_000L, 1_711_846_800_000L, 1_730_595_599_999L, 1_759_449_600_000L)
        for (id in zones) {
            val tz = TimeZone.getTimeZone(id)
            for (t in times) {
                val cal = Calendar.getInstance(tz).apply { timeInMillis = t }
                val want = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                assertEquals("$id $t", want, StatusClock.minuteOfDay(t, tz.getOffset(t)))
            }
        }
    }

    @Test
    fun beforeTheEpochStaysInRange() {
        // 1969-12-31 23:59 UTC, and a zone that is still on the previous day.
        assertEquals(1439, StatusClock.minuteOfDay(-1L, 0))
        assertEquals(23 * 60, StatusClock.minuteOfDay(0L, -3_600_000))
    }

    @Test
    fun edgesOfTheDay() {
        val seoul = 9 * 3_600_000
        // 2025-10-03 00:00 and 23:59 in Seoul.
        val midnight = 1_759_417_200_000L
        assertEquals(0, StatusClock.minuteOfDay(midnight, seoul))
        assertEquals(1439, StatusClock.minuteOfDay(midnight + 86_400_000L - 1L, seoul))
        assertEquals(14 * 60 + 5, StatusClock.minuteOfDay(midnight + (14 * 60 + 5) * 60_000L, seoul))
    }
}
