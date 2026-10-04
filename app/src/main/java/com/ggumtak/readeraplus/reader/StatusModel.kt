package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.render.StatusDecor
import com.ggumtak.readeraplus.render.StatusSlot
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusItem

/** Inputs of one status update for the page on screen. Reused, primitives and existing references only. */
internal class StatusInputs {
    @JvmField var page = 0; @JvmField var total = 0              // globalPage / counts.total()
    @JvmField var percent = 0                                     // ReaderFormat.percent(progress())
    @JvmField var bar = -1f                                       // char progress of the page start; last page = 1; -1 = off
    @JvmField var chapterTitle: String? = null; @JvmField var bookTitle: String? = null
    @JvmField var chapterStartsHere = false                       // the page begins the chapter: CHAPTER draws nothing (§6 P1-16)
    @JvmField var chapterPage = -1; @JvmField var chapterPages = -1   // R2 "2 / 32"; -1 = unknown (no TOC)
    @JvmField var minutesEpisode = -1; @JvmField var minutesBook = -1
    @JvmField var epNumbered = false; @JvmField var epNumber = -1; @JvmField var epMax = -1
    @JvmField var tocIndex = -1; @JvmField var tocCount = 0
    @JvmField var minuteOfDay = -1; @JvmField var is24 = true
    @JvmField var battery = -1

    /**
     * R2 "2/32": global page [cur] in the chapter that begins on page [first] (1 for the front matter before the first
     * TOC entry) and ends before page [next] (where the next chapter begins; total + 1 after the last one). Estimates
     * that disagree never show page 0 or a page past the chapter's last.
     */
    fun setChapterPage(cur: Int, first: Int, next: Int) {
        chapterPage = (cur - first + 1).coerceAtLeast(1)
        chapterPages = (next - first).coerceAtLeast(chapterPage)
    }
}

/**
 * The page's status slots (U §5.3): fills the shared [decor] from [StatusInputs] for the 6 slots of the settings.
 * Main thread only; [update] runs in the same step that installs the frame pointing at [decor].
 */
internal class StatusModel {
    val decor = StatusDecor()
    /** Scratch characters of one slot (never shared with the decor: [StatusSlot.set] copies). */
    private val buf = CharArray(StatusSlot.CAPACITY)
    /** Dot position in track pixels at the last update; −1 = no dot. The dot "moved" only when this changes. */
    private var dotPx = -1

    /** Fills decor for the slots of [s]. Zero allocation. True when anything drawn changed (then decor.version++). */
    fun update(s: ReaderSettings, inp: StatusInputs, trackPx: Int): Boolean {
        var changed = false
        val d = decor
        if (fill(d.header.left, s.headerLeft, inp)) changed = true
        if (fill(d.header.center, s.headerCenter, inp)) changed = true
        if (fill(d.header.right, s.headerRight, inp)) changed = true
        if (fill(d.footer.left, s.footerLeft, inp)) changed = true
        if (fill(d.footer.center, s.footerCenter, inp)) changed = true
        if (fill(d.footer.right, s.footerRight, inp)) changed = true
        // The lane follows the setting, never the data: an unknown position keeps the footer geometry ([Δ] §5.2).
        val lane = s.progressBar
        if (d.lane != lane) { d.lane = lane; changed = true }
        val bar = inp.bar
        val known = lane && bar >= 0f && !bar.isNaN()
        val f = if (known) bar.coerceAtMost(1f) else -1f
        val px = if (known) Math.round(f * trackPx.coerceAtLeast(0)) else -1
        if (px != dotPx) { dotPx = px; changed = true }
        d.progress = f
        if (changed) d.version++
        return changed
    }

    /** Sets [slot] to [item] for [inp]; true when anything in it changed. An unknown input leaves the slot empty. */
    private fun fill(slot: StatusSlot, item: StatusItem, inp: StatusInputs): Boolean {
        val b = buf
        return when (item) {
            StatusItem.NONE -> slot.clear()
            StatusItem.CHAPTER -> if (inp.chapterStartsHere) slot.clear() else slot.setText(inp.chapterTitle)
            StatusItem.BOOK_TITLE -> slot.setText(inp.bookTitle)
            StatusItem.BATTERY -> if (inp.battery < 0) slot.clear() else slot.set(b, 0, inp.battery)
            StatusItem.CLOCK_BATTERY -> when {
                inp.minuteOfDay < 0 && inp.battery < 0 -> slot.clear()
                inp.minuteOfDay < 0 -> slot.set(b, 0, inp.battery)
                else -> slot.set(b, StatusText.clock(b, 0, inp.minuteOfDay, inp.is24), inp.battery)
            }
            else -> {
                val n = chars(b, item, inp)
                if (n <= 0) slot.clear() else slot.set(b, n, -1)
            }
        }
    }

    /** Characters of a numeric [item] into [b] from 0; returns the length, 0 when its input is unknown. */
    private fun chars(b: CharArray, item: StatusItem, inp: StatusInputs): Int = when (item) {
        StatusItem.PAGE -> if (inp.page <= 0) 0 else StatusText.page(b, 0, inp.page, inp.total)
        StatusItem.PERCENT -> if (inp.percent < 0) 0 else StatusText.percent(b, 0, inp.percent)
        StatusItem.CHAPTER_PAGES_LEFT -> if (inp.chapterPage <= 0) 0 else StatusText.chapterPage(b, 0, inp.chapterPage, inp.chapterPages)
        StatusItem.EPISODE ->
            if ((inp.epNumbered && inp.epNumber > 0) || inp.tocIndex >= 0) {
                StatusText.episode(b, 0, inp.epNumbered, inp.epNumber, inp.epMax, inp.tocIndex, inp.tocCount)
            } else {
                0
            }
        StatusItem.TIME_LEFT_EPISODE -> if (inp.minutesEpisode < 0) 0 else StatusText.timeLeft(b, 0, false, inp.minutesEpisode)
        StatusItem.TIME_LEFT_BOOK -> if (inp.minutesBook < 0) 0 else StatusText.timeLeft(b, 0, true, inp.minutesBook)
        StatusItem.CLOCK -> if (inp.minuteOfDay < 0) 0 else StatusText.clock(b, 0, inp.minuteOfDay, inp.is24)
        else -> 0
    }

    /**
     * One-off String of [item] for the slot chooser (allocates; never on a turn). The chapter title is shown even on
     * the page that begins the chapter (the chooser describes the item, not this page). Null = nothing to show.
     */
    fun sample(item: StatusItem, inp: StatusInputs): String? {
        val b = CharArray(StatusSlot.CAPACITY)
        return when (item) {
            StatusItem.NONE -> null
            StatusItem.CHAPTER -> inp.chapterTitle?.takeIf { it.isNotEmpty() }
            StatusItem.BOOK_TITLE -> inp.bookTitle?.takeIf { it.isNotEmpty() }
            StatusItem.BATTERY -> if (inp.battery < 0) null else inp.battery.coerceAtMost(100).toString()
            StatusItem.CLOCK_BATTERY -> {
                val clock = if (inp.minuteOfDay < 0) null else String(b, 0, StatusText.clock(b, 0, inp.minuteOfDay, inp.is24))
                val battery = if (inp.battery < 0) null else inp.battery.coerceAtMost(100).toString()
                when {
                    clock != null && battery != null -> "$clock$SAMPLE_SEP$battery"
                    else -> clock ?: battery
                }
            }
            else -> {
                val n = chars(b, item, inp)
                if (n <= 0) null else String(b, 0, n)
            }
        }
    }

    private companion object {
        /** Joins the clock and the battery in a sample, as [StatusItem.CLOCK_BATTERY]'s example shows ("14:05 · 80"). */
        const val SAMPLE_SEP = " · "
    }
}

/** Allocation-free formatters; output identical to their ReaderFormat twins (tested). Return the new length. */
internal object StatusText {
    private const val PAGE_SEP = " / "
    private const val EPISODE_SUFFIX = "화"
    private const val SCOPE_BOOK = "책 "
    private const val SCOPE_CHAPTER = "챕터 "
    private const val UNDER_MINUTE = "1분 미만"
    private const val MINUTES = "분"
    private const val HOURS = "시간"

    fun page(buf: CharArray, at: Int, page: Int, total: Int): Int {        // "12 / 3259" (total ≥ page)
        var n = int(buf, at, page)
        n = put(buf, n, PAGE_SEP)
        return int(buf, n, maxOf(total, page))
    }

    fun percent(buf: CharArray, at: Int, p: Int): Int = put(buf, int(buf, at, p), '%')   // "34%"

    fun clock(buf: CharArray, at: Int, minuteOfDay: Int, is24: Boolean): Int {   // "14:05" / "2:05"
        val m = Math.floorMod(minuteOfDay, 1440)
        val hour = m / 60
        val minute = m % 60
        val h = if (is24) hour else ((hour + 11) % 12) + 1
        var n = at
        if (is24 && h < 10) n = put(buf, n, '0')
        n = int(buf, n, h)
        n = put(buf, n, ':')
        if (minute < 10) n = put(buf, n, '0')
        return int(buf, n, minute)
    }

    fun chapterPage(buf: CharArray, at: Int, page: Int, total: Int): Int =   // "2 / 32" (total ≥ page), as [page]
        int(buf, put(buf, int(buf, at, page), PAGE_SEP), maxOf(total, page))

    fun episode(buf: CharArray, at: Int, numbered: Boolean, n: Int, max: Int, idx: Int, count: Int): Int {  // "123/540화" / "87/612"
        if (numbered && n > 0) {
            return put(buf, int(buf, put(buf, int(buf, at, n), '/'), maxOf(max, n)), EPISODE_SUFFIX)
        }
        return int(buf, put(buf, int(buf, at, idx + 1), '/'), maxOf(count, idx + 1))
    }

    fun timeLeft(buf: CharArray, at: Int, book: Boolean, minutes: Int): Int {   // "챕터 3분" / "책 7시간 20분" / "… 1분 미만"
        val n = put(buf, at, if (book) SCOPE_BOOK else SCOPE_CHAPTER)
        if (minutes < 1) return put(buf, n, UNDER_MINUTE)
        if (minutes < 60) return put(buf, int(buf, n, minutes), MINUTES)
        val h = minutes / 60
        val m = minutes % 60
        val hours = put(buf, int(buf, n, h), HOURS)
        if (m == 0 || h >= 10) return hours
        return put(buf, int(buf, put(buf, hours, ' '), m), MINUTES)
    }

    fun int(buf: CharArray, at: Int, v: Int): Int {
        if (v == Int.MIN_VALUE) return put(buf, at, "-2147483648")
        var n = at
        var x = v
        if (x < 0) { n = put(buf, n, '-'); x = -x }
        var digits = 1
        var p = 10
        while (digits < 10 && x >= p) { digits++; p *= 10 }
        val end = minOf(n + digits, buf.size)
        var i = n + digits - 1
        while (i >= n) {
            if (i < buf.size) buf[i] = ('0'.code + x % 10).toChar()
            x /= 10
            i--
        }
        return maxOf(end, n)
    }

    /** Copies [s] at [at] (truncated at the buffer's end; String.getChars, no allocation); returns the new length. */
    private fun put(buf: CharArray, at: Int, s: String): Int {
        val n = minOf(s.length, buf.size - at)
        if (n <= 0) return at.coerceAtMost(buf.size)
        s.toCharArray(buf, at, 0, n)
        return at + n
    }

    private fun put(buf: CharArray, at: Int, c: Char): Int {
        if (at >= buf.size) return buf.size
        buf[at] = c
        return at + 1
    }
}
