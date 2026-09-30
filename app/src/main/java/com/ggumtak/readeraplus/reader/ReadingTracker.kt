package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.data.ReadingLog
import java.time.Instant
import java.time.ZoneId

/** Reading of one book since the last flush, all on local day [day] (yyyymmdd): what ReadingLog.add stores. */
class ReadingDelta(val day: Int, val seconds: Long, val pages: Int, val chars: Long)

/**
 * Counts real reading time, pages and characters of the open book (T1-6; pure, unit-tested, main thread only).
 *
 * A page counts once it has been shown for [MIN_PAGE_MS] (quicker pages are flipping, not reading), with at most
 * [MAX_PAGE_MS] (a page left on screen longer is idle time, not reading). Time is only counted between [resume] and
 * [pause], so the screen switched off, another app in front or TTS speaking in the background (which reports its own
 * time) count nothing. The reader flushes the delta to ReadingLog and Library.addReadingTime on IO at every pause,
 * when a book closes and when the local day changes (the next page shown after midnight starts the new day).
 *
 * Per page: a few integer operations, no allocation (a [ReadingDelta] only when there is something to flush).
 * Times are elapsed-realtime ms.
 */
class ReadingTracker {
    private var counting = false
    /** A page is on screen ([pageChars] is its length). */
    private var hasPage = false
    private var pageChars = 0
    /** When the page on screen started to count (-1 = not counting). */
    private var shownAt = -1L
    private var day = 0
    private var seconds = 0L
    private var millis = 0L
    private var pages = 0
    private var chars = 0L

    /**
     * A page ([pageChars] long) is shown at [now] on local day [today]: the page before it is closed (counted when
     * it stayed long enough). Returns the previous day's reading when [today] is a new day, else null.
     */
    fun onPageShown(now: Long, today: Int, pageChars: Int): ReadingDelta? {
        closePage(now)
        val done = if (today != day) take() else null
        day = today
        hasPage = true
        this.pageChars = pageChars.coerceAtLeast(0)
        shownAt = if (counting) now else -1L
        return done
    }

    /** The reader is in front again: the page on screen counts from [now]. */
    fun resume(now: Long) {
        counting = true
        shownAt = if (hasPage) now else -1L
    }

    /** The reader went to the background at [now]: the page on screen is closed; returns what to flush, if anything. */
    fun pause(now: Long): ReadingDelta? {
        closePage(now)
        counting = false
        shownAt = -1L
        return take()
    }

    /** Closes the page on screen as if it was turned now and returns what to flush; the page keeps counting. */
    fun flush(now: Long): ReadingDelta? {
        closePage(now)
        if (counting && hasPage) shownAt = now
        return take()
    }

    /** The book was closed: nothing is on screen until the next [onPageShown]. */
    fun forgetPage() {
        hasPage = false
        shownAt = -1L
    }

    /** Seconds counted but not flushed yet (the end panel's reading time adds them). */
    val pendingSeconds: Long get() = seconds

    private fun closePage(now: Long) {
        val from = shownAt
        if (from < 0L || !hasPage) return
        shownAt = -1L
        val d = now - from
        if (d < MIN_PAGE_MS) return
        millis += minOf(d, MAX_PAGE_MS)
        seconds = millis / 1000
        pages++
        chars += pageChars
    }

    /** The accumulated reading (whole seconds; the sub-second rest is kept for the next flush), or null when empty. */
    private fun take(): ReadingDelta? {
        if (seconds <= 0L && pages == 0) return null
        val out = ReadingDelta(day, seconds, pages, chars)
        millis -= seconds * 1000
        seconds = 0L
        pages = 0
        chars = 0L
        return out
    }

    companion object {
        /** A page shown for less than this was flipped past, not read. */
        const val MIN_PAGE_MS = 2_000L
        /** A page counts at most this long: the rest is the screen left on, not reading. */
        const val MAX_PAGE_MS = 300_000L
    }
}

/**
 * Local day (yyyymmdd, [ReadingLog.day]) of wall-clock times, worked out again only when the day ends (or the clock is
 * set back): asking it on every page turn costs two comparisons and allocates nothing. Pure; one thread.
 */
class DayClock(private val zone: ZoneId = ZoneId.systemDefault()) {
    private var day = 0
    private var from = Long.MAX_VALUE
    private var until = Long.MIN_VALUE

    fun day(nowMs: Long): Int {
        if (nowMs >= from && nowMs < until) return day
        val d = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        day = ReadingLog.day(nowMs, zone)
        from = d.atStartOfDay(zone).toInstant().toEpochMilli()
        until = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return day
    }
}
