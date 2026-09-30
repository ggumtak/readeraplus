package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.format.DocPosition

/**
 * Per-section page counts of a book with estimates for sections not counted yet (pure; main thread only).
 *
 * Unknown sections are estimated from the pages/char ratio of the counted ones × their char length
 * ([approxChars] until counted, then the real length). Global pages are 1-based.
 */
class PageCounts(approxChars: IntArray) {
    val size: Int = approxChars.size
    private val counts = IntArray(size) { -1 }
    private val chars = IntArray(size) { approxChars[it].coerceAtLeast(0) }
    private var known = 0
    private var knownPages = 0L
    private var knownChars = 0L
    /** The same sums over the counted sections of at least [SMALL_SECTION_CHARS] chars (see [pagesPerChar]). */
    private var bigKnown = 0
    private var bigPages = 0L
    private var bigChars = 0L

    /** prefix[i] = estimated pages of sections [0, i); prefix[size] = total. Rebuilt lazily. */
    private var prefix: IntArray? = null
    /** firstUnknown = smallest section index not counted yet (size if complete). */
    private var firstUnknown = 0
    /**
     * charPrefix[i] = chars of sections [0, i); charPrefix[size] = the book. Rebuilt lazily, and only when a section's
     * char length changes (its first count or layout), so the per-turn char queries are O(1).
     */
    private var charPrefix: LongArray? = null

    val isComplete: Boolean get() = known == size
    val knownCount: Int get() = known

    fun isKnown(section: Int): Boolean = section in 0 until size && counts[section] >= 0

    /** Adds ([sign] 1) or removes (-1) the counted [section]'s share of the sums. */
    private fun account(section: Int, sign: Int) {
        val p = counts[section].toLong()
        val c = chars[section].toLong()
        known += sign
        knownPages += sign * p
        knownChars += sign * c
        if (c >= SMALL_SECTION_CHARS) {
            bigKnown += sign
            bigPages += sign * p
            bigChars += sign * c
        }
    }

    /** Records the exact page count (and exact char length) of [section]. */
    fun set(section: Int, pages: Int, charLength: Int) {
        if (section !in 0 until size) return
        val p = pages.coerceAtLeast(1)
        val c = charLength.coerceAtLeast(0)
        if (counts[section] >= 0) {
            if (counts[section] == p && chars[section] == c) return
            account(section, -1)
        }
        if (chars[section] != c) charPrefix = null
        counts[section] = p
        chars[section] = c
        account(section, 1)
        prefix = null
    }

    /** Replaces everything with a complete saved array (from the page-count cache). */
    fun setAll(saved: IntArray): Boolean {
        if (saved.size != size || saved.any { it <= 0 }) return false
        clearSums()
        for (i in 0 until size) {
            counts[i] = saved[i]
            account(i, 1)
        }
        prefix = null
        return true
    }

    /**
     * Takes the counted entries of a saved, possibly partial array (A2: -1 = not counted when it was saved) and
     * returns [isComplete]. Sections counted here already keep their own count (the same layout, and their real char
     * length). Rejects, changing nothing, an array of another length (a stale save: the section split changed) or
     * one holding a value that is neither a count (≥ 1) nor -1.
     */
    fun setKnown(saved: IntArray): Boolean {
        if (saved.size != size) return false
        for (v in saved) if (v < 1 && v != -1) return false
        for (i in 0 until size) {
            val v = saved[i]
            if (v < 1 || counts[i] >= 0) continue
            counts[i] = v
            account(i, 1)
            prefix = null
        }
        return isComplete
    }

    /** Forgets all counts (new layout parameters). */
    fun reset() {
        counts.fill(-1)
        clearSums()
        prefix = null
    }

    private fun clearSums() {
        known = 0
        knownPages = 0
        knownChars = 0
        bigKnown = 0
        bigPages = 0
        bigChars = 0
    }

    fun charLength(section: Int): Int = if (section in 0 until size) chars[section] else 0

    private fun charPrefix(): LongArray {
        charPrefix?.let { return it }
        val p = LongArray(size + 1)
        for (i in 0 until size) p[i + 1] = p[i] + chars[i]
        charPrefix = p
        return p
    }

    /** Chars of the whole book (real lengths of the counted sections, estimates for the rest). */
    fun totalChars(): Long = charPrefix()[size]

    /**
     * Characters of the sections after [section] (0 for the last one, the whole book for -1): with
     * `charLength(section) - offset` the characters left to the end of the book (T1-7 "책 7시간 20분"). Called on every
     * page turn when that footer item is on: O(1), from sums rebuilt only when a section's char length changes
     * (counting / layout), like [pagesBefore]'s prefix. Main thread.
     */
    fun charsAfter(section: Int): Long {
        val p = charPrefix()
        return p[size] - p[(section + 1).coerceIn(0, size)]
    }

    /** Char position of (section, offset) from the start of the book (offset clamped to the section). O(1). */
    private fun charPos(p: LongArray, section: Int, offset: Int): Long {
        if (section < 0) return 0L
        if (section >= size) return p[size]
        return p[section] + offset.toLong().coerceIn(0L, chars[section].toLong())
    }

    /** Characters from (section, offset) to the end of the book ([charsAfter] plus the rest of [section]). O(1). */
    fun charsFrom(section: Int, offset: Int): Long {
        val p = charPrefix()
        return p[size] - charPos(p, section, offset)
    }

    /**
     * Characters from (fromSection, fromOffset) up to (toSection, toOffset), 0 when the target is not after the start
     * (T1-7 "이 화 3분": up to where the next chapter starts, however many sections lie between). Offsets are clamped to
     * their sections; a [toSection] past the last section means the end of the book. O(1).
     */
    fun charsBetween(fromSection: Int, fromOffset: Int, toSection: Int, toOffset: Int): Long {
        val p = charPrefix()
        return (charPos(p, toSection, toOffset) - charPos(p, fromSection, fromOffset)).coerceAtLeast(0L)
    }

    /**
     * Expected chars per page for the current page geometry and font size (set by the session); the prior for
     * estimates until enough text has been counted.
     */
    var charsPerPageHint: Int = DEFAULT_CHARS_PER_PAGE
        set(v) {
            field = v.coerceIn(20, 20_000)
            prefix = null
        }

    /**
     * Pages per char: the counted sections' ratio blended with [PRIOR_PAGES] pages at [charsPerPageHint], so a
     * few tiny sections counted first (a title line = 1 page) can't blow the estimate up by orders of magnitude.
     * Sections under [SMALL_SECTION_CHARS] chars (title pages, short prefaces: mostly one short page) are left out
     * as soon as a larger one is counted.
     */
    fun pagesPerChar(): Double {
        val hint = charsPerPageHint.toDouble()
        if (bigKnown > 0) return (bigPages + PRIOR_PAGES) / (bigChars + PRIOR_PAGES * hint)
        return (knownPages + PRIOR_PAGES) / (knownChars + PRIOR_PAGES * hint)
    }

    /** Exact count when known, else the estimate (≥ 1). */
    fun pages(section: Int): Int {
        if (section !in 0 until size) return 0
        val c = counts[section]
        if (c >= 0) return c
        return Math.round(chars[section] * pagesPerChar()).toInt().coerceAtLeast(1)
    }

    private fun prefix(): IntArray {
        prefix?.let { return it }
        val p = IntArray(size + 1)
        var fu = size
        for (i in 0 until size) {
            p[i + 1] = p[i] + pages(i)
            if (fu == size && counts[i] < 0) fu = i
        }
        firstUnknown = fu
        prefix = p
        return p
    }

    fun pagesBefore(section: Int): Int = prefix()[section.coerceIn(0, size)]

    fun total(): Int = prefix()[size].coerceAtLeast(1)

    /** True when every section before [section] has an exact count. */
    fun exactBefore(section: Int): Boolean {
        prefix()
        return firstUnknown >= section
    }

    /** 1-based global page of [pageIndex] in [section]. */
    fun globalPage(section: Int, pageIndex: Int): Int = pagesBefore(section) + pageIndex.coerceAtLeast(0) + 1

    /** (section, pageIndex) of a 1-based global page (clamped to the book). */
    fun locate(globalPage: Int): Pair<Int, Int> {
        if (size == 0) return 0 to 0
        val p = prefix()
        val target = (globalPage - 1).coerceIn(0, p[size] - 1)
        // last section whose start page <= target
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (p[mid] <= target) lo = mid else hi = mid - 1
        }
        return lo to (target - p[lo])
    }

    /** Page index of [offset] inside [section] estimated from its char length (for sections not laid out). */
    fun estimatePageIndex(section: Int, offset: Int): Int {
        if (section !in 0 until size) return 0
        val c = chars[section]
        val n = pages(section)
        if (c <= 0 || n <= 1) return 0
        return (offset.toLong().coerceIn(0, c.toLong()) * n / c).toInt().coerceIn(0, n - 1)
    }

    /** 0..1 position by characters. O(1) (the footer asks on every turn until the pages are counted). */
    fun charProgress(section: Int, offset: Int): Float {
        val p = charPrefix()
        val sum = p[size]
        if (sum <= 0L) return if (size == 0) 0f else section.toFloat() / size
        return (charPos(p, section, offset).toDouble() / sum).toFloat().coerceIn(0f, 1f)
    }

    /** Position at [fraction] (0..1) of the book by characters. */
    fun locateFraction(fraction: Float): DocPosition {
        if (size == 0) return DocPosition.START
        var sum = 0L
        for (i in 0 until size) sum += chars[i]
        if (sum <= 0L) return DocPosition((fraction * size).toInt().coerceIn(0, size - 1), 0)
        val target = (fraction.coerceIn(0f, 1f) * sum).toLong()
        var acc = 0L
        for (i in 0 until size) {
            val c = chars[i]
            if (target < acc + c || i == size - 1) {
                return DocPosition(i, (target - acc).coerceIn(0, c.toLong()).toInt())
            }
            acc += c
        }
        return DocPosition(size - 1, 0)
    }

    /**
     * The first position whose [charProgress] reaches [fraction] (go-to by percent before pages are counted): like
     * [locateFraction] but rounding up, so the page starting there or after it reads at least floor(fraction × 100)%.
     * A fraction exactly at a section's end gives the next section's start.
     */
    fun locateProgress(fraction: Float): DocPosition {
        if (size == 0) return DocPosition.START
        val f = if (fraction.isNaN()) 0.0 else fraction.toDouble().coerceIn(0.0, 1.0)
        var sum = 0L
        for (i in 0 until size) sum += chars[i]
        if (sum <= 0L) return DocPosition(Math.ceil(f * size - 1e-6 * size).toInt().coerceIn(0, size - 1), 0)
        // The tolerance absorbs the float error of a typed "52" → 0.52f (< 6e-8 relative); it stays well inside the
        // 1e-6 slack of ReaderFormat.percent, so the char found still reads the typed percent.
        val target = Math.ceil(f * sum - 1e-7 * sum).toLong().coerceIn(0L, sum)
        var acc = 0L
        for (i in 0 until size) {
            val c = chars[i]
            if (target < acc + c || i == size - 1) {
                return DocPosition(i, (target - acc).coerceIn(0, c.toLong()).toInt())
            }
            acc += c
        }
        return DocPosition(size - 1, 0)
    }

    /**
     * Pages after the current page that still belong to the current chapter, given where the next chapter
     * starts: [targetSection]/[targetPageIndex], and whether it starts exactly at that page's first char.
     */
    fun pagesLeftUntil(
        curSection: Int,
        curPageIndex: Int,
        curSectionPages: Int,
        targetSection: Int,
        targetPageIndex: Int,
        targetAtPageStart: Boolean,
    ): Int {
        val tail = if (targetAtPageStart) 0 else 1
        if (targetSection <= curSection) {
            return (targetPageIndex - curPageIndex - 1 + tail).coerceAtLeast(0)
        }
        var left = (curSectionPages - 1 - curPageIndex).coerceAtLeast(0)
        for (s in curSection + 1 until targetSection.coerceAtMost(size)) left += pages(s)
        return (left + targetPageIndex + tail).coerceAtLeast(0)
    }

    /** Copy of the counts for the cache, -1 for the sections not counted yet (partial until [isComplete]). */
    fun toArray(): IntArray = counts.copyOf()

    companion object {
        const val DEFAULT_CHARS_PER_PAGE = 700
        /** Weight of the geometry prior in pages (see [pagesPerChar]). */
        const val PRIOR_PAGES = 4.0
        /** Counted sections shorter than this don't drive the estimate once a longer one is counted. */
        const val SMALL_SECTION_CHARS = 2_000

        /** Entries of a saved array (of the right length) that [setKnown] takes as counts: 0 when it rejects it. */
        fun countedIn(saved: IntArray): Int {
            var n = 0
            for (v in saved) {
                if (v >= 1) n++ else if (v != -1) return 0
            }
            return n
        }
    }
}

/**
 * Order in which the background counter visits a book's sections (A2): the section on screen, then samples at 25, 50
 * and 75% of the book, so the estimate for everything not counted yet settles within seconds instead of following
 * whatever the first sections happen to be (a title page, a dialogue-heavy chapter), then every section in order.
 * Pure.
 */
internal object CountOrder {
    /**
     * Sections to count, in order: [foreground] (when in range), the samples, then 0 until [size]. An index may appear
     * twice; the counter skips sections already known. [samplable] (null = every section) limits the samples: an EPUB
     * samples only whole (single-part) spine items, because loading one part of a split item converts the whole item
     * and a part far away would evict the split item that the in-order pass is reusing. A sample moves to the nearest
     * samplable section within an eighth of the book of its quarter mark, or is left out.
     */
    fun plan(size: Int, foreground: Int, samplable: BooleanArray?): IntArray {
        if (size <= 0) return IntArray(0)
        val out = IntArray(size + 4)
        var n = 0
        if (foreground in 0 until size) out[n++] = foreground
        for (q in 1..3) {
            val s = nearest((size.toLong() * q / 4).toInt(), size, samplable)
            if (s < 0) continue
            var dup = false
            for (k in 0 until n) if (out[k] == s) dup = true
            if (!dup) out[n++] = s
        }
        for (i in 0 until size) out[n++] = i
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun nearest(target: Int, size: Int, samplable: BooleanArray?): Int {
        val t = target.coerceIn(0, size - 1)
        if (samplable == null) return t
        val reach = maxOf(1, size / 8)
        for (d in 0..reach) {
            if (t + d < size && t + d < samplable.size && samplable[t + d]) return t + d
            if (d > 0 && t - d >= 0 && t - d < samplable.size && samplable[t - d]) return t - d
        }
        return -1
    }
}
