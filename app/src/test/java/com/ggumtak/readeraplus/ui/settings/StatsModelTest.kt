package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.data.DayTotal
import com.ggumtak.readeraplus.data.LogTotals
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsModelTest {
    private fun day(d: Int, seconds: Long) = DayTotal(d, seconds, 10, 1000)

    @Test
    fun ranges() {
        // 2026-09-30 is a Wednesday.
        assertEquals(20260928, ReadingStats.weekStart(20260930))
        assertEquals(20260928, ReadingStats.weekStart(20260928))
        assertEquals(20260928, ReadingStats.weekStart(20261004))
        assertEquals(20251229, ReadingStats.weekStart(20260101))
        assertEquals(20260901, ReadingStats.monthStart(20260930))
        assertEquals(20260101, ReadingStats.yearStart(20260930))
    }

    @Test
    fun streakRunsThroughYesterdayUntilTodayCounts() {
        val days = listOf(day(20260928, 600), day(20260929, 300), day(20260930, 100))
        // Today has under 5 minutes so far: the run ending yesterday still stands.
        assertEquals(2 to 2, ReadingStats.streaks(days, 20260930))
        assertEquals(3 to 3, ReadingStats.streaks(days.dropLast(1) + day(20260930, 400), 20260930))
    }

    @Test
    fun longestStreakAcrossMonthsAndGaps() {
        val days = (20260827..20260831).map { day(it, 900) } + (20260901..20260903).map { day(it, 900) } +
            listOf(day(20260910, 900), day(20260929, 299), day(20260930, 3600))
        assertEquals(1 to 8, ReadingStats.streaks(days, 20260930))
        // Nothing yesterday or today: no current run.
        assertEquals(0 to 8, ReadingStats.streaks(days, 20261003))
        assertEquals(0 to 0, ReadingStats.streaks(emptyList(), 20260930))
        // Days after "today" (a clock that went back) are ignored.
        assertEquals(0 to 0, ReadingStats.streaks(listOf(day(20261001, 900)), 20260930))
    }

    @Test
    fun streakLine() {
        assertEquals("연속 12일째 · 최장 30일", ReadingStats.streakLine(12, 30))
        assertEquals("연속 기록 없음 · 최장 30일", ReadingStats.streakLine(0, 30))
        assertEquals("아직 연속 기록이 없습니다", ReadingStats.streakLine(0, 0))
    }

    @Test
    fun chars() {
        assertEquals("0자", ReadingStats.chars(0))
        assertEquals("3,120자", ReadingStats.chars(3120))
        assertEquals("1만 자", ReadingStats.chars(10_000))
        assertEquals("18.2만 자", ReadingStats.chars(182_000))
        assertEquals("18만 자", ReadingStats.chars(180_000))
        // Rounded down: never shows a unit it hasn't reached.
        assertEquals("9.9만 자", ReadingStats.chars(99_999))
        assertEquals("9999.9만 자", ReadingStats.chars(99_999_999))
        assertEquals("1.2억 자", ReadingStats.chars(120_000_000))
    }

    @Test
    fun summaryCells() {
        val none = LogTotals.EMPTY
        assertEquals("0분", ReadingStats.time(none))
        assertEquals("", ReadingStats.amount(none))
        val t = LogTotals(seconds = 4800, pages = 312, chars = 182_000, days = 1)
        assertEquals("1시간 20분", ReadingStats.time(t))
        assertEquals("312쪽 · 18.2만 자", ReadingStats.amount(t))
        // Pages under a minute still count as reading.
        assertEquals("1분 미만", ReadingStats.time(LogTotals(30, 2, 800, 1)))
    }

    @Test
    fun lines() {
        assertEquals("분당 약 720자 (최근 7일)", ReadingStats.speedLine(720))
        assertEquals("최근 7일에 10분 이상 읽으면 보입니다", ReadingStats.speedLine(null))
        assertEquals("9월 28일", ReadingStats.dayLabel(20260928))
        assertEquals("9월 28일 · 1시간 12분 · 소설A, 소설B", ReadingStats.dayLine(20260928, 4320, listOf("소설A", "소설B")))
        assertEquals("1월 5일 · 3분 · A, B, C 외 2권", ReadingStats.dayLine(20260105, 180, listOf("A", "B", "C", "D", "E")))
        assertEquals("9월 28일 · 기록 없음", ReadingStats.dayLine(20260928, 0, emptyList()))
        assertEquals("34%", ReadingStats.progress(0.344f, haveRead = false))
        assertEquals("다 읽음", ReadingStats.progress(0.5f, haveRead = true))
        assertEquals("100%", ReadingStats.progress(1.5f, haveRead = false))
    }

    @Test
    fun heatmapGrid() {
        val today = 20260930
        val g = HeatmapModel.build(today, listOf(day(20260518, 60), day(20260929, 1000), day(20260930, 4000)))
        assertEquals(HeatmapModel.WEEKS * 7, g.days.size)
        // Oldest Monday top-left; today (Wednesday) in the last column; the rest of this week is not drawn.
        assertEquals(20260518, g.days[g.index(0, 0)])
        assertEquals(60L, g.seconds[g.index(0, 0)])
        assertEquals(20260519, g.days[g.index(0, 1)])
        assertEquals(20260525, g.days[g.index(1, 0)])
        assertEquals(20260928, g.days[g.index(19, 0)])
        assertEquals(1000L, g.seconds[g.index(19, 1)])
        assertEquals(today, g.days[g.index(19, 2)])
        assertEquals(4000L, g.seconds[g.index(19, 2)])
        assertEquals(0, g.days[g.index(19, 3)])
        assertEquals(0, g.days[g.index(19, 6)])
        assertEquals(0L, g.seconds[g.index(10, 3)])
        // Days outside the grid are ignored.
        assertEquals(0L, HeatmapModel.build(today, listOf(day(20260101, 900))).seconds.sum())
    }

    @Test
    fun heatmapMonthLabels() {
        // Grid from 2026-05-18: June starts in column 2 (too close for a "5월" label), then July, August, September.
        assertEquals(
            listOf(2 to "6월", 7 to "7월", 11 to "8월", 16 to "9월"),
            HeatmapModel.build(20260930, emptyList()).months,
        )
        // Grid from 2026-06-08: July starts in column 4, so the first column gets "6월".
        assertEquals(0 to "6월", HeatmapModel.build(20261021, emptyList()).months.first())
        assertEquals(4 to "7월", HeatmapModel.build(20261021, emptyList()).months[1])
        // Across the new year.
        assertEquals(true, HeatmapModel.build(20260204, emptyList()).months.any { it.second == "1월" })
    }

    @Test
    fun heatmapLevels() {
        assertEquals(0, HeatmapModel.level(0))
        assertEquals(1, HeatmapModel.level(1))
        assertEquals(1, HeatmapModel.level(15 * 60 - 1L))
        assertEquals(2, HeatmapModel.level(15 * 60L))
        assertEquals(2, HeatmapModel.level(60 * 60 - 1L))
        assertEquals(3, HeatmapModel.level(60 * 60L))
    }
}
