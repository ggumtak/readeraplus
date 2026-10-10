package com.ggumtak.readeraplus.reader

/**
 * Where the chapter marks go on the page seek bar (pure). The bar runs from progress 0 to [max] (= total pages − 1) and a
 * chapter starting on 0-based page p sits at `width · p / max`, exactly where the thumb stands on that page.
 *
 * Too many chapters for the track (a web novel's hundreds of episodes) draw no marks at all: a dense ruler says nothing
 * and only adds ink. Marks closer than a third of the gap to the previous one are dropped (two short chapters).
 */
object ChapterTicks {
    /**
     * Fills [out] with the x offsets (px from the track's left edge) of the chapter starts [starts] (0-based pages, any
     * order, duplicates allowed) on a track [widthPx] wide, and returns how many it wrote. Page 0 (the book's start)
     * and pages past [max] get no mark. Returns 0 when more distinct marks than `widthPx / minGapPx` would be needed.
     * [out] must hold at least `starts.size` values. Allocates only for the sort copy.
     */
    fun place(starts: IntArray, max: Int, widthPx: Int, minGapPx: Int, out: FloatArray): Int {
        if (max <= 0 || widthPx <= 0 || minGapPx <= 0 || starts.isEmpty()) return 0
        val sorted = starts.copyOf()
        sorted.sort()
        var distinct = 0
        var prev = 0
        for (p in sorted) {
            if (p <= 0 || p > max || p == prev) continue
            distinct++
            prev = p
        }
        if (distinct == 0 || distinct > widthPx / minGapPx) return 0
        val minSep = minGapPx / 3f
        var n = 0
        var lastX = Float.NEGATIVE_INFINITY
        prev = 0
        for (p in sorted) {
            if (p <= 0 || p > max || p == prev) continue
            prev = p
            val x = widthPx.toFloat() * p / max
            if (x - lastX < minSep) continue
            out[n++] = x
            lastX = x
        }
        return n
    }
}
