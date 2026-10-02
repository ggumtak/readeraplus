package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.format.DocPosition

/** The book and place saved when Android recreates a reader (pure). */
object ReaderRestore {
    class Place private constructor(val bookId: Long, val section: Int, val offset: Int, val savedAt: Long) {
        companion object {
            /** A book id is sufficient to reopen; section -1 leaves the position to the database. */
            fun from(bookId: Long, section: Int, offset: Int, savedAt: Long): Place? =
                if (bookId <= 0) null else Place(bookId, section, offset.coerceAtLeast(0), savedAt)
        }
    }

    /** Newer TTS writes and a changed TXT parse take precedence over saved activity coordinates. */
    fun start(p: Place, bookId: Long, rowWrittenAt: Long, remapped: Boolean): DocPosition? =
        if (p.bookId == bookId && p.section >= 0 && p.savedAt > 0 && !remapped && rowWrittenAt <= p.savedAt)
            DocPosition(p.section, p.offset) else null
}

/**
 * Counts page turns and decides when to flash a full e-ink refresh (pure, unit-tested).
 * [every] = 0 disables the periodic refresh; [onChapter] refreshes when a new chapter is shown. Every trigger is
 * opt-in (spec rule 5): with the defaults nothing here ever asks for a flash.
 */
class EinkCadence(var every: Int = 0, var onChapter: Boolean = false) {
    private var turns = 0
    /** Picture coverage of the page shown last ([imageDue]). */
    private var lastCoverage = 0f

    /**
     * Call once per displayed page turn. Returns true when a full refresh should follow: a new chapter
     * ([onChapter]), a picture page ([imageDue], T1-3c) or the [every]-th turn. At most one refresh per turn; each
     * restarts the count.
     */
    fun onTurn(chapterChanged: Boolean, imageDue: Boolean = false): Boolean {
        if ((onChapter && chapterChanged) || imageDue) {
            turns = 0
            return true
        }
        return count()
    }

    /**
     * A panel over the page closed (TOC, search, the reading-settings popup, the chrome; T1-3d): it counts as one
     * turn toward [every], so a refresh that falls due clears the panel's ghost. Nothing without a cadence.
     */
    fun onPanelClosed(): Boolean = count()

    private fun count(): Boolean {
        if (every <= 0) return false
        turns++
        if (turns >= every) {
            turns = 0
            return true
        }
        return false
    }

    /**
     * T1-3c, only with AppSettings.einkFlashImages on: [coverage] is the picture share of the page just shown
     * (render.ImageCoverage); true when it is a picture page, or when the share changed by as much from the page
     * before (a picture leaves its ghost on the next text page). Records [coverage] for the next call.
     */
    fun imageDue(coverage: Float): Boolean {
        val prev = lastCoverage
        lastCoverage = coverage
        return coverage >= IMAGE_COVERAGE || Math.abs(coverage - prev) >= IMAGE_COVERAGE
    }

    /** A manual refresh restarts the count. */
    fun reset() {
        turns = 0
    }

    companion object {
        /** Turns closer together than this are fast flipping: a due full refresh waits until they stop. */
        const val RAPID_TURN_MS = 450L

        /** Picture share of a page (or change of it between two pages) that makes a picture page (T1-3c). */
        const val IMAGE_COVERAGE = 0.075f

        /**
         * The cadence for the page's colours (T1-3b): [night] (AppSettings.einkRefreshEveryNight) while [inverted],
         * unless it is negative ("낮과 같게"), else [day] (AppSettings.einkRefreshEvery).
         */
        fun everyFor(day: Int, night: Int, inverted: Boolean): Int =
            if (inverted && night >= 0) night else day

        /**
         * Delay before a due full refresh: none while reading normally, but while pages are being flipped fast (the
         * previous turn was less than [RAPID_TURN_MS] before [now]) the flash would stall the panel between turns,
         * so it waits until the flipping settles (the caller re-posts it on every further turn).
         */
        fun refreshDelay(now: Long, previousTurnAt: Long): Long =
            if (now - previousTurnAt in 0 until RAPID_TURN_MS) RAPID_TURN_MS else 0L
    }
}

/**
 * Rate limit for a label that follows a drag (brightness overlay, seek preview): on e-ink every text change is a
 * panel update, so the label changes at most once per [intervalMs] while the drag goes on, and the caller shows the
 * final value once the drag rests ([waitMs]) or ends (pure; main thread only). Times are uptime ms.
 */
class Throttle(private val intervalMs: Long) {
    private var lastAt = NEVER

    /** True when an update may be shown at [now] (and records it); false while the last one is too recent. */
    fun tryAcquire(now: Long): Boolean {
        if (now - lastAt in 0 until intervalMs) return false
        lastAt = now
        return true
    }

    /** Ms until [tryAcquire] succeeds (0 = now): when to show a value that was held back. */
    fun waitMs(now: Long): Long {
        val d = now - lastAt
        return if (d in 0 until intervalMs) intervalMs - d else 0L
    }

    /** Records an update shown at [now] outside [tryAcquire] (a held-back value shown late). */
    fun mark(now: Long) {
        lastAt = now
    }

    /** A new drag: its first value shows at once. */
    fun reset() {
        lastAt = NEVER
    }

    companion object {
        /** 4 Hz: fast enough to follow a finger, slow enough for the panel. */
        const val LABEL_MS = 250L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}

/**
 * Page turns that arrive while the page to turn from is still being laid out (a section change, a jump, a
 * relayout): each is counted (net direction, capped) and all are applied at once when the layout is shown, so fast
 * taps are never dropped and never replayed one draw at a time (pure; main thread only).
 */
class TurnBacklog(private val cap: Int = MAX) {
    /** Net pending turns: > 0 forward, < 0 backward. */
    var net: Int = 0
        private set

    val isEmpty: Boolean get() = net == 0

    fun add(next: Boolean) {
        net = (net + if (next) 1 else -1).coerceIn(-cap, cap)
    }

    /** Puts turns that could not be applied yet back (e.g. still pending after a partial walk). */
    fun restore(turns: Int) {
        net = (net + turns).coerceIn(-cap, cap)
    }

    /** Returns and forgets the pending turns. */
    fun take(): Int {
        val n = net
        net = 0
        return n
    }

    fun clear() {
        net = 0
    }

    companion object {
        /** At most this many turns are kept (a runaway key or a long burst never flies through the whole book). */
        const val MAX = 30
    }
}

/** Where a burst of page turns lands (see [TurnMath.walk]). */
data class TurnWalk(
    val section: Int,
    /** Page index in [section]; [TurnMath.LAST_PAGE] = the last page of a section whose page count is not known. */
    val pageIndex: Int,
    /** Turns still to apply once [section] is laid out (same sign convention as the request). */
    val remaining: Int,
    /** The book's first / last page stopped the walk before all turns were used. */
    val hitEdge: Boolean,
)

/** Pure page-walk math for applying several turns at once (unit-tested). */
object TurnMath {
    const val LAST_PAGE = -2

    /**
     * Applies [delta] page turns (> 0 forward) from page [pageIndex] of [section]. [pagesOf] gives a section's exact
     * page count, or -1 when it is not known (not laid out, not counted): the walk then stops on that section's first
     * page (forward) or last page (backward, [LAST_PAGE]) and returns the turns left for when it is laid out.
     * [pagesOf] must know [section] itself.
     */
    fun walk(section: Int, pageIndex: Int, delta: Int, sectionCount: Int, pagesOf: (Int) -> Int): TurnWalk {
        var sec = section
        var idx = pageIndex
        var left = delta
        if (sectionCount <= 0) return TurnWalk(0, 0, 0, delta != 0)
        var pages = pagesOf(sec).coerceAtLeast(1)
        idx = idx.coerceIn(0, pages - 1)
        while (left > 0) {
            val room = pages - 1 - idx
            if (left <= room) return TurnWalk(sec, idx + left, 0, false)
            left -= room
            idx = pages - 1
            if (sec + 1 >= sectionCount) return TurnWalk(sec, idx, 0, true)
            sec++
            left--
            val n = pagesOf(sec)
            if (n < 0) return TurnWalk(sec, 0, left, false)
            pages = n.coerceAtLeast(1)
            idx = 0
        }
        while (left < 0) {
            if (-left <= idx) return TurnWalk(sec, idx + left, 0, false)
            left += idx
            idx = 0
            if (sec <= 0) return TurnWalk(sec, 0, 0, true)
            sec--
            left++
            val n = pagesOf(sec)
            if (n < 0) return TurnWalk(sec, LAST_PAGE, left, false)
            pages = n.coerceAtLeast(1)
            idx = pages - 1
        }
        return TurnWalk(sec, idx, 0, false)
    }
}

/**
 * Drops only duplicate deliveries of one tap (a bouncing touch panel reporting the same touch twice): a tap within
 * [windowMs] of the previous accepted one at (nearly) the same spot. Any real second tap — even the fastest
 * drumming — is at least a finger's contact time later or somewhere else, so it always passes (pure).
 */
class TapDedup(private val windowMs: Long = DUP_MS) {
    private var lastAt = Long.MIN_VALUE / 2
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    /** [slopPx]: how far apart two reports of the same touch can be. */
    fun accept(x: Float, y: Float, upTimeMs: Long, slopPx: Float): Boolean {
        val dt = upTimeMs - lastAt
        val samePlace = Math.abs(x - lastX) <= slopPx && Math.abs(y - lastY) <= slopPx
        if (dt in 0..windowMs && samePlace) return false
        lastAt = upTimeMs
        lastX = x
        lastY = y
        return true
    }

    companion object {
        const val DUP_MS = 40L
    }
}

/**
 * Reading progress by pages as the footer shows it, and the inverse used by 페이지 이동 in percent (pure,
 * unit-tested): typing N% lands on the first page whose footer reads N%.
 */
object PageProgress {
    /** 0..1 progress of 1-based [page] of [total] pages (the last page = 1). */
    fun of(page: Int, total: Int): Float {
        if (total <= 0) return 0f
        if (page >= total) return 1f
        return (page.toFloat() / total).coerceIn(0f, 1f)
    }

    /** The percent the footer shows on [page] of [total]. */
    fun percentOf(page: Int, total: Int): Int = ReaderFormat.percent(of(page, total))

    /**
     * Global page (1..[total]) to show for [fraction] (0..1): the first page whose footer percent equals
     * floor(fraction × 100) — 0 → page 1, 1 → the last page. A book of fewer than 100 pages skips some percents:
     * then the page whose percent is nearest (ties: the first page at or past [fraction]).
     */
    fun pageFor(fraction: Float, total: Int): Int {
        if (total <= 1) return 1
        val f = if (fraction.isNaN()) 0.0 else fraction.toDouble().coerceIn(0.0, 1.0)
        val want = ReaderFormat.percent(f.toFloat())
        // First page whose progress reaches f; the tolerance absorbs the float error of a typed "52" → 0.52f.
        val g0 = Math.ceil(f * total - 1e-6 * total).toInt().coerceIn(1, total)
        var best = g0
        var bestDist = Math.abs(percentOf(g0, total) - want)
        // Rounding may put g0 one page off: the neighbours only win when strictly nearer.
        for (g in intArrayOf(g0 - 1, g0 + 1)) {
            if (g < 1 || g > total) continue
            val d = Math.abs(percentOf(g, total) - want)
            if (d < bestDist) {
                best = g
                bestDist = d
            }
        }
        return best
    }
}

enum class SwipeDir { NONE, NEXT, PREV }

/** Meaning of a finished single-finger gesture on the page (no long-press, no brightness drag). */
enum class GestureEnd { NONE, TAP, NEXT, PREV }

/** Pure gesture math used by PageView (unit-tested). */
object Gestures {
    /**
     * Classifies a finished drag. Horizontal (right-to-left = next) when [horizontal] and |dx| dominates;
     * vertical (up = next) when [vertical] and |dy| dominates. Needs more than [minDistPx].
     */
    fun classify(dx: Float, dy: Float, minDistPx: Float, horizontal: Boolean, vertical: Boolean): SwipeDir {
        val ax = Math.abs(dx)
        val ay = Math.abs(dy)
        if (horizontal && ax > minDistPx && ax > ay) return if (dx < 0) SwipeDir.NEXT else SwipeDir.PREV
        if (vertical && ay > minDistPx && ay > ax) return if (dy < 0) SwipeDir.NEXT else SwipeDir.PREV
        return SwipeDir.NONE
    }

    /**
     * Decides what a released gesture means: a swipe when [classify] says so, else a tap when the finger never
     * strayed more than [tapSlopPx] from where it went down ([maxDistPx]) — e-ink users often move a little
     * while tapping, and a slightly sloppy tap must still turn the page — else nothing.
     */
    fun end(
        dx: Float,
        dy: Float,
        maxDistPx: Float,
        tapSlopPx: Float,
        minSwipePx: Float,
        horizontal: Boolean,
        vertical: Boolean,
    ): GestureEnd = when (classify(dx, dy, minSwipePx, horizontal, vertical)) {
        SwipeDir.NEXT -> GestureEnd.NEXT
        SwipeDir.PREV -> GestureEnd.PREV
        SwipeDir.NONE -> if (maxDistPx <= tapSlopPx) GestureEnd.TAP else GestureEnd.NONE
    }

    /** Brightness after dragging [dy] px (up = brighter) from [start]; a drag over 60% of [heightPx] = full range. */
    fun brightness(start: Float, dy: Float, heightPx: Int): Float {
        if (heightPx <= 0) return start.coerceIn(0f, 1f)
        val v = start.coerceIn(0f, 1f) - dy / (heightPx * 0.6f)
        return (Math.round(v * 100f) / 100f).coerceIn(0f, 1f)
    }

    /** True when the gesture starts on the brightness strip at the left edge. */
    fun inBrightnessStrip(x: Float, widthPx: Int): Boolean = widthPx > 0 && x < widthPx * 0.10f
}

/**
 * Saved reading positions are (section, offset) in the coordinates of the parse that produced them. A TXT parse
 * depends on global options (chapter detection, blank lines, replace rules, ...) and the book's encoding, so the
 * reader keeps, per book, the text signature of that parse ([LayoutKeys.textSignature]) and the position's char
 * fraction. When the book is opened under a different signature, the position is found again by that fraction
 * instead of reading stale coordinates (pure, unit-tested).
 */
object TextPositions {
    /** How far the stored char fraction may be from the library's progress before the library wins (it is newer). */
    const val MAX_DRIFT = 0.1f

    fun encode(signature: String, fraction: Float): String =
        signature + "|" + fraction.coerceIn(0f, 1f).let { if (it.isNaN()) 0f else it }

    /** (signature, fraction) or null for a missing / malformed value. */
    fun decode(value: String?): Pair<String, Float>? {
        if (value.isNullOrEmpty()) return null
        val bar = value.lastIndexOf('|')
        if (bar <= 0 || bar == value.length - 1) return null
        val f = value.substring(bar + 1).toFloatOrNull() ?: return null
        if (f.isNaN()) return null
        return value.substring(0, bar) to f.coerceIn(0f, 1f)
    }

    /**
     * Char fraction to reopen at when the saved position belongs to another parse, or null to use the saved
     * (section, offset) as is: no signature for this format ([current] null), nothing recorded yet, same parse, or
     * the very start (the start of any parse). A library progress far from the recorded fraction means the position
     * was changed elsewhere since (reset, restore): then that progress is used.
     */
    fun remapFraction(stored: String?, current: String?, section: Int, offset: Int, libraryProgress: Float): Float? {
        if (current == null) return null
        val s = decode(stored) ?: return null
        if (s.first == current) return null
        if (section <= 0 && offset <= 0) return null
        val p = if (libraryProgress.isNaN()) s.second else libraryProgress.coerceIn(0f, 1f)
        return if (Math.abs(p - s.second) <= MAX_DRIFT) s.second else p
    }
}
