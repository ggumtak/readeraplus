package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.data.DayTotal
import com.ggumtak.readeraplus.data.LogTotals
import com.ggumtak.readeraplus.data.ReadingLog
import com.ggumtak.readeraplus.reader.ReaderFormat
import java.util.Locale

/*
 * Pure rules of the "읽기 기록" page (T1-6): date ranges, streaks, the heatmap grid and the page's wording. Days are
 * ReadingLog's local yyyymmdd ints. Unit-tested on the JVM.
 */

/** Ranges, streaks and wording of the statistics page. */
object ReadingStats {
    /** A day counts for the streak with at least this much reading (5 minutes). */
    const val STREAK_MIN_SECONDS = 5 * 60L

    /** Days the page loads for the heatmap and the longest streak (spec: at most 400 days × books). */
    const val HISTORY_DAYS = 400

    /** Monday of [day]'s week ("이번 주" starts on Monday). */
    fun weekStart(day: Int): Int = ReadingLog.addDays(day, -(ReadingLog.date(day).dayOfWeek.value - 1))

    fun monthStart(day: Int): Int = day / 100 * 100 + 1

    fun yearStart(day: Int): Int = day / 10000 * 10000 + 101

    /**
     * (current, longest) runs of consecutive days with at least [STREAK_MIN_SECONDS] in [days] (any order). The current
     * run ends today, or yesterday while today has not reached the minimum yet (the day is not over).
     */
    fun streaks(days: List<DayTotal>, today: Int): Pair<Int, Int> {
        val read = HashSet<Int>()
        for (d in days) if (d.seconds >= STREAK_MIN_SECONDS && d.day <= today) read += d.day
        var longest = 0
        for (d in read) {
            if (ReadingLog.addDays(d, -1) in read) continue // not the first day of its run
            var n = 1
            var next = ReadingLog.addDays(d, 1)
            while (next in read) {
                n++
                next = ReadingLog.addDays(next, 1)
            }
            longest = maxOf(longest, n)
        }
        var current = 0
        var d = if (today in read) today else ReadingLog.addDays(today, -1)
        while (d in read) {
            current++
            d = ReadingLog.addDays(d, -1)
        }
        return current to longest
    }

    /** "연속 12일째 · 최장 30일"; "연속 기록 없음 · 최장 30일"; "아직 연속 기록이 없습니다". */
    fun streakLine(current: Int, longest: Int): String = when {
        longest <= 0 -> "아직 연속 기록이 없습니다"
        current <= 0 -> "연속 기록 없음 · 최장 ${longest}일"
        else -> "연속 ${current}일째 · 최장 ${longest}일"
    }

    /** 3,120 → "3,120자"; 182,000 → "18.2만 자"; 120,000,000 → "1.2억 자". */
    fun chars(n: Long): String {
        val v = n.coerceAtLeast(0)
        return when {
            v < 10_000 -> String.format(Locale.US, "%,d자", v)
            v < 100_000_000 -> "${oneDecimal(v / 10_000.0)}만 자"
            else -> "${oneDecimal(v / 100_000_000.0)}억 자"
        }
    }

    /** 18.24 → "18.2", 18.0 → "18" (rounded down, so 9.99만 never reads as "10만"). */
    private fun oneDecimal(v: Double): String {
        val tenths = Math.floor(v * 10).toLong()
        return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    }

    /** A summary cell's time ("1시간 20분"), "0분" for a range without reading (its second line is then empty). */
    fun time(t: LogTotals): String = if (t.seconds <= 0 && t.pages <= 0) "0분" else ReaderFormat.durationOfSeconds(t.seconds)

    /** A summary cell's second line: "312쪽 · 18.2만 자" ("" without reading). */
    fun amount(t: LogTotals): String = if (t.seconds <= 0 && t.pages <= 0) "" else "${t.pages}쪽 · ${chars(t.chars)}"

    /** "분당 약 720자 (최근 7일)", or when the figure shows ([ReadingLog.BOOK_MIN_SECONDS] in the last 7 days). */
    fun speedLine(cpm: Int?): String =
        if (cpm == null) "최근 7일에 ${ReadingLog.BOOK_MIN_SECONDS / 60}분 이상 읽으면 보입니다" else "분당 약 ${cpm}자 (최근 7일)"

    /** 20260928 → "9월 28일". */
    fun dayLabel(day: Int): String = "${day / 100 % 100}월 ${day % 100}일"

    /**
     * The heatmap's line for a tapped day: "9월 28일 · 1시간 12분 · 소설A, 소설B" (at most [MAX_TITLES] titles, then
     * "외 N권"); "9월 28일 · 기록 없음".
     */
    fun dayLine(day: Int, seconds: Long, titles: List<String>): String {
        if (seconds <= 0 && titles.isEmpty()) return "${dayLabel(day)} · 기록 없음"
        val sb = StringBuilder(dayLabel(day)).append(" · ").append(ReaderFormat.durationOfSeconds(seconds))
        if (titles.isNotEmpty()) {
            sb.append(" · ").append(titles.take(MAX_TITLES).joinToString(", "))
            if (titles.size > MAX_TITLES) sb.append(" 외 ${titles.size - MAX_TITLES}권")
        }
        return sb.toString()
    }

    const val MAX_TITLES = 3

    /** "34%" of a 0..1 progress (a finished book reads "다 읽음"). */
    fun progress(p: Float, haveRead: Boolean): String =
        if (haveRead) "다 읽음" else "${Math.round(p.coerceIn(0f, 1f) * 100)}%"
}

/**
 * The 잔디 grid (T1-6): [HeatmapModel.WEEKS] columns of weeks, oldest on the left, the current week last; rows are
 * Monday..Sunday. [days] holds each cell's yyyymmdd (0 = after today: not drawn), [seconds] its reading time;
 * cell index = column * 7 + row. [months] = (column, "9월") where a month label goes.
 */
class HeatGrid(val days: IntArray, val seconds: LongArray, val months: List<Pair<Int, String>>) {
    fun index(column: Int, row: Int): Int = column * 7 + row
}

object HeatmapModel {
    const val WEEKS = 20

    /** Level thresholds (spec): none / under 15 minutes / under 60 minutes / 60 minutes or more. */
    const val LEVEL_1_MAX_SECONDS = 15 * 60L
    const val LEVEL_2_MAX_SECONDS = 60 * 60L

    /** First day the grid shows (Monday, [WEEKS] − 1 weeks before this week's). */
    fun firstDay(today: Int): Int = ReadingLog.addDays(ReadingStats.weekStart(today), -7 * (WEEKS - 1))

    fun build(today: Int, totals: List<DayTotal>): HeatGrid {
        val byDay = HashMap<Int, Long>(totals.size * 2)
        for (t in totals) byDay[t.day] = (byDay[t.day] ?: 0L) + t.seconds
        val first = firstDay(today)
        val n = WEEKS * 7
        val days = IntArray(n)
        val seconds = LongArray(n)
        var d = first
        for (i in 0 until n) {
            if (d > today) break
            days[i] = d
            seconds[i] = byDay[d] ?: 0L
            d = ReadingLog.addDays(d, 1)
        }
        return HeatGrid(days, seconds, monthLabels(first))
    }

    /**
     * A label over each column whose Monday starts a new month compared with the column before; the first column gets
     * its month too unless a new month starts within the next [MIN_LABEL_GAP] columns (the labels would overlap).
     */
    internal fun monthLabels(first: Int): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var prevMonth = -1
        for (c in 0 until WEEKS) {
            val monday = ReadingLog.addDays(first, 7 * c)
            val month = monday / 100 % 100
            if (c > 0 && month != prevMonth) out += c to "${month}월"
            prevMonth = month
        }
        val firstChange = out.firstOrNull()?.first ?: WEEKS
        if (firstChange >= MIN_LABEL_GAP) out.add(0, 0 to "${first / 100 % 100}월")
        return out
    }

    /** Columns a month label needs ("12월" is about 2.5 cells wide). */
    internal const val MIN_LABEL_GAP = 3

    /** 0 = no reading, 1 = under 15 minutes, 2 = under 60 minutes, 3 = 60 minutes or more. */
    fun level(seconds: Long): Int = when {
        seconds <= 0 -> 0
        seconds < LEVEL_1_MAX_SECONDS -> 1
        seconds < LEVEL_2_MAX_SECONDS -> 2
        else -> 3
    }
}
