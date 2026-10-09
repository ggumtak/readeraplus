package com.ggumtak.readeraplus.reader

/**
 * Global pages → (section, pageIndex) for one grid page of thumbnails (library.md §3.3 steps 1–3; pure, unit-tested
 * with a real [PageCounts]). [PageCounts] estimates uncounted sections; laying out the sections a grid page touches
 * makes their counts exact, and the mapping is resolved again — at most [MAX_ROUNDS] rounds, in practice one. Earlier
 * uncounted sections can still shift the numbering by an offset: the footer shows that same offset.
 *
 * Main thread (it reads [PageCounts]). Reused per request; the arrays never shrink.
 */
internal class ThumbMap {
    /** Cells resolved (pages [first], [first] + 1, …). */
    var size = 0
        private set
    var first = 1
        private set
    var sections = IntArray(0)
        private set
    var indices = IntArray(0)
        private set

    /** Maps pages [first, first + count) ∩ [1, total] with the current counts. */
    fun resolve(counts: PageCounts, first: Int, count: Int) {
        val total = counts.total()
        val f = first.coerceIn(1, total)
        val n = count.coerceAtLeast(0).coerceAtMost(total - f + 1)
        if (sections.size < n) {
            sections = IntArray(n)
            indices = IntArray(n)
        }
        this.first = f
        size = if (counts.size == 0) 0 else n
        for (i in 0 until size) {
            val (s, p) = counts.locate(f + i)
            sections[i] = s
            indices[i] = p
        }
    }

    /** Distinct sections of the resolved cells for which [ready] is false, in cell order. */
    inline fun missing(ready: (Int) -> Boolean): IntArray {
        var out = IntArray(0)
        for (i in 0 until size) {
            val s = sections[i]
            if (ready(s) || out.contains(s)) continue
            out += s
        }
        return out
    }

    /** True when two cells share a (section, pageIndex) pair (an unconverged mapping). */
    fun hasDuplicates(): Boolean {
        for (i in 1 until size) {
            if (sections[i] == sections[i - 1] && indices[i] <= indices[i - 1]) return true
            if (sections[i] < sections[i - 1]) return true
        }
        return false
    }

    /**
     * Clamps every page index into its section's [pageCount] (an estimate over-reaching an exact count); a section
     * with no layout (pageCount ≤ 0) keeps index 0.
     */
    inline fun clampIndices(pageCount: (Int) -> Int) {
        for (i in 0 until size) indices[i] = clampIndex(indices[i], pageCount(sections[i]))
    }

    /**
     * The warm grid page (library.md §3.4): when every resolved cell's section has an exact count, the mapping needs
     * no layout. Then each cell's (section, pageIndex clamped to the count) goes to [lookup] and the hit is put in
     * [out]. True only when every cell is counted and found. False at the first uncounted section or miss: [out] and
     * the clamped indices are then partial, and the caller maps through [converge] (which resolves again).
     */
    inline fun <T : Any> cached(counts: PageCounts, out: Array<T?>, lookup: (section: Int, pageIndex: Int) -> T?): Boolean {
        for (i in 0 until size) if (!counts.isKnown(sections[i])) return false
        for (i in 0 until size) {
            val s = sections[i]
            indices[i] = clampIndex(indices[i], counts.pages(s))
            out[i] = lookup(s, indices[i]) ?: return false
        }
        return true
    }

    companion object {
        const val MAX_ROUNDS = 3

        fun clampIndex(index: Int, pageCount: Int): Int = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

        /**
         * Resolves [map] for pages [first, first + count), lays out the touched sections that are not [ready] with
         * [layOut] (which may suspend when the caller is a coroutine: this is inline) and re-resolves until nothing
         * is missing, at most [MAX_ROUNDS] layout rounds. [layOut] makes the section ready (or failed: [ready] must
         * then be true too, so it is not retried). Returns the layout rounds used.
         */
        inline fun converge(
            map: ThumbMap, counts: PageCounts, first: Int, count: Int,
            ready: (Int) -> Boolean, layOut: (Int) -> Unit,
        ): Int {
            map.resolve(counts, first, count)
            var rounds = 0
            while (rounds < MAX_ROUNDS) {
                val need = map.missing(ready)
                if (need.isEmpty()) break
                rounds++
                for (s in need) layOut(s)
                map.resolve(counts, first, count)
            }
            return rounds
        }
    }
}
