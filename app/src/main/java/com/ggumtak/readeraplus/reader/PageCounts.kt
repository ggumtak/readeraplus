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

    /** prefix[i] = estimated pages of sections [0, i); prefix[size] = total. Rebuilt lazily. */
    private var prefix: IntArray? = null
    /** firstUnknown = smallest section index not counted yet (size if complete). */
    private var firstUnknown = 0

    val isComplete: Boolean get() = known == size
    val knownCount: Int get() = known

    fun isKnown(section: Int): Boolean = section in 0 until size && counts[section] >= 0

    /** Records the exact page count (and exact char length) of [section]. */
    fun set(section: Int, pages: Int, charLength: Int) {
        if (section !in 0 until size) return
        val p = pages.coerceAtLeast(1)
        val c = charLength.coerceAtLeast(0)
        if (counts[section] >= 0) {
            if (counts[section] == p && chars[section] == c) return
            known--
            knownPages -= counts[section]
            knownChars -= chars[section]
        }
        counts[section] = p
        chars[section] = c
        known++
        knownPages += p
        knownChars += c
        prefix = null
    }

    /** Replaces everything with a complete saved array (from the page-count cache). */
    fun setAll(saved: IntArray): Boolean {
        if (saved.size != size || saved.any { it <= 0 }) return false
        known = 0
        knownPages = 0
        knownChars = 0
        for (i in 0 until size) {
            counts[i] = saved[i]
            known++
            knownPages += saved[i]
            knownChars += chars[i]
        }
        prefix = null
        return true
    }

    /** Forgets all counts (new layout parameters). */
    fun reset() {
        counts.fill(-1)
        known = 0
        knownPages = 0
        knownChars = 0
        prefix = null
    }

    fun charLength(section: Int): Int = if (section in 0 until size) chars[section] else 0

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
     */
    fun pagesPerChar(): Double {
        val hint = charsPerPageHint.toDouble()
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

    /** 0..1 position by characters. */
    fun charProgress(section: Int, offset: Int): Float {
        var sum = 0L
        var before = 0L
        for (i in 0 until size) {
            if (i < section) before += chars[i]
            sum += chars[i]
        }
        if (sum <= 0L) return if (size == 0) 0f else section.toFloat() / size
        val within = if (section in 0 until size) offset.toLong().coerceIn(0, chars[section].toLong()) else 0L
        return ((before + within).toDouble() / sum).toFloat().coerceIn(0f, 1f)
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

    /** Copy of the counts when complete (for the cache), else null. */
    fun toArray(): IntArray? = if (isComplete) counts.copyOf() else null

    companion object {
        const val DEFAULT_CHARS_PER_PAGE = 700
        /** Weight of the geometry prior in pages (see [pagesPerChar]). */
        const val PRIOR_PAGES = 4.0
    }
}
