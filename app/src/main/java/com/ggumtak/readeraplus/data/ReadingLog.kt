package com.ggumtak.readeraplus.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Reading totals over a range of days. [days] = number of days with any reading. */
data class LogTotals(val seconds: Long, val pages: Int, val chars: Long, val days: Int) {
    companion object {
        val EMPTY = LogTotals(0, 0, 0, 0)
    }
}

/** One day's reading, all books. [day] = local yyyymmdd. */
data class DayTotal(val day: Int, val seconds: Long, val pages: Int, val chars: Long)

/** One book's reading over a range of days. */
data class BookTotal(val bookId: Long, val seconds: Long, val pages: Int, val chars: Long)

/**
 * The reading log (T1-6): seconds, pages and characters read per local day and book, in `reading_log` (library
 * DB v2, see LibrarySchema.CREATE_READING_LOG). Written by the reader when it pauses (ReadingTracker's delta) and by
 * TTS while it speaks with the reader in the background; read by the statistics page, the time-left estimates
 * (T1-7) and the end panel.
 *
 * Owner: DATA. Users: READER_A (add on pause, cpm in afterOpen), EXTRAS_TOOLS (TtsController screen-off time),
 * SETTINGS (StatsPage), EXTRAS_NAV (through BookInsightsHost only). Days are `Int` yyyymmdd in the device's zone
 * ([day], [addDays]: pure, any thread). Every DB function is blocking (Dispatchers.IO), thread-safe and never
 * throws for an empty log. Nothing here runs on the open path or per page turn. SQL: `LibrarySql.LOG_*` /
 * `SELECT_LOG_*`; rows of a removed book go with it (`Library.deleteBookRows`), the backup carries them per book.
 */
object ReadingLog {
    /** Speed assumed when the log knows too little (T1-7: "otherwise 600 characters per minute"). */
    const val DEFAULT_CPM = 600

    /** [cpm] tiers (T1-7): this book over the last [BOOK_DAYS] days with at least [BOOK_MIN_SECONDS] … */
    const val BOOK_DAYS = 14
    const val BOOK_MIN_SECONDS = 10 * 60L

    /** … else all books over the last [ALL_DAYS] days with at least [ALL_MIN_SECONDS]; else null. */
    const val ALL_DAYS = 30
    const val ALL_MIN_SECONDS = 30 * 60L

    /** One [add] never adds more than a day of reading (guards against clock jumps), like Library.addReadingTime. */
    private const val MAX_ADD_SECONDS = 24L * 3600
    private const val MAX_ADD_PAGES = 100_000
    private const val MAX_ADD_CHARS = 100_000_000L

    /** Local date of [epochMillis] as yyyymmdd (e.g. 20260930). Pure. */
    fun day(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val d = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }

    /** yyyymmdd → LocalDate (clamped into a valid date for malformed input). Pure. */
    fun date(day: Int): LocalDate {
        val y = (day / 10000).coerceIn(1970, 9999)
        val m = (day / 100 % 100).coerceIn(1, 12)
        val d = (day % 100).coerceIn(1, LocalDate.of(y, m, 1).lengthOfMonth())
        return LocalDate.of(y, m, d)
    }

    /** The day [n] days after [day] (negative = before), across months and years. Pure. */
    fun addDays(day: Int, n: Int): Int {
        val d = date(day).plusDays(n.toLong())
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }

    /** True for a plausible yyyymmdd (1970..9999, month 1..12, day 1..31). Pure. */
    internal fun isDay(day: Int): Boolean {
        val m = day / 100 % 100
        val d = day % 100
        return day in 19700101..99991231 && m in 1..12 && d in 1..31
    }

    /**
     * Adds [seconds], [pages] and [chars] to (day, book): `UPDATE … SET seconds = seconds + ?, …` and, when no row
     * changed, `INSERT`, both in one transaction. Non-positive deltas are ignored (a negative one counts as 0), as are
     * a malformed [day] and a book that is no longer in the library. Blocking (IO).
     */
    fun add(bookId: Long, day: Int, seconds: Long, pages: Int, chars: Long) {
        val d = Delta.of(seconds, pages, chars) ?: return
        if (bookId <= 0 || !isDay(day)) return
        Library.db().inTransaction {
            if (exec(LibrarySql.LOG_ADD, d.seconds, d.pages, d.chars, day, bookId) == 0) {
                insertRow(LibrarySql.LOG_INSERT, day, d.seconds, d.pages, d.chars, bookId)
            }
        }
    }

    /** A clamped, non-empty [add] delta (pure). */
    internal class Delta private constructor(val seconds: Long, val pages: Int, val chars: Long) {
        companion object {
            /** Null when nothing positive is left to add. */
            fun of(seconds: Long, pages: Int, chars: Long): Delta? {
                val s = seconds.coerceIn(0, MAX_ADD_SECONDS)
                val p = pages.coerceIn(0, MAX_ADD_PAGES)
                val c = chars.coerceIn(0, MAX_ADD_CHARS)
                return if (s == 0L && p == 0 && c == 0L) null else Delta(s, p, c)
            }
        }
    }

    /** Totals of all books over [fromDay, toDay] (inclusive). Blocking (IO). */
    fun summary(fromDay: Int, toDay: Int): LogTotals {
        if (fromDay > toDay) return LogTotals.EMPTY
        return Library.db().queryFirst(LibrarySql.SELECT_LOG_TOTALS, args(fromDay, toDay)) {
            LogTotals(it.getLong(0), clampInt(it.getLong(1)), it.getLong(2), it.getInt(3))
        } ?: LogTotals.EMPTY
    }

    /** Per-day totals of all books over [fromDay, toDay] (inclusive), ascending; days without reading are absent. */
    fun days(fromDay: Int, toDay: Int): List<DayTotal> {
        if (fromDay > toDay) return emptyList()
        return Library.db().queryList(LibrarySql.SELECT_LOG_DAYS, args(fromDay, toDay)) {
            DayTotal(it.getInt(0), it.getLong(1), clampInt(it.getLong(2)), it.getLong(3))
        }
    }

    /**
     * Per-book totals over [fromDay, toDay] (inclusive), most seconds first (ties: lower id first). Books no longer in
     * the library are left out; trashed ones stay (their reading happened). Blocking (IO).
     */
    fun perBook(fromDay: Int, toDay: Int): List<BookTotal> {
        if (fromDay > toDay) return emptyList()
        return Library.db().queryList(LibrarySql.SELECT_LOG_PER_BOOK, args(fromDay, toDay)) {
            BookTotal(it.getLong(0), it.getLong(1), clampInt(it.getLong(2)), it.getLong(3))
        }
    }

    /**
     * Characters per minute: [bookId]'s last [BOOK_DAYS] days when they hold at least [BOOK_MIN_SECONDS], else all
     * books' last [ALL_DAYS] days with at least [ALL_MIN_SECONDS] ([bookId] null starts there), else null (the
     * caller then uses [DEFAULT_CPM]). [today] = the last day counted. Blocking (IO); READER_A loads it in afterOpen.
     */
    fun cpm(bookId: Long?, today: Int = day(System.currentTimeMillis())): Int? {
        val db = Library.db()
        return cpmOf(
            book = bookId?.let { id ->
                db.queryFirst(LibrarySql.SELECT_LOG_BOOK_TOTALS, args(id, addDays(today, -(BOOK_DAYS - 1)), today)) {
                    it.getLong(0) to it.getLong(1)
                }
            },
            all = { summary(addDays(today, -(ALL_DAYS - 1)), today).let { it.seconds to it.chars } },
        )
    }

    /**
     * [cpm]'s tiers without the queries (pure): [book] = this book's (seconds, chars) over its window (null = no book
     * asked), [all] = every book's over theirs, asked only when the book tier gives nothing.
     */
    internal fun cpmOf(book: Pair<Long, Long>?, all: () -> Pair<Long, Long>): Int? {
        if (book != null) speed(book.first, book.second, BOOK_MIN_SECONDS)?.let { return it }
        val a = all()
        return speed(a.first, a.second, ALL_MIN_SECONDS)
    }

    private fun clampInt(v: Long): Int = v.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    /** Chars per minute of [seconds] / [chars] (rounded), or null below [minSeconds] or without characters. Pure. */
    fun speed(seconds: Long, chars: Long, minSeconds: Long): Int? {
        if (seconds <= 0 || seconds < minSeconds || chars <= 0) return null
        return Math.round(chars * 60.0 / seconds).toInt().coerceAtLeast(1)
    }
}
