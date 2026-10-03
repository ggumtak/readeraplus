package com.ggumtak.readeraplus.ui.notes

/**
 * The hub list's row window (N §9.7), pure and JVM-tested (NotesWindowTest). Main thread only.
 *
 * - Rows are fetched in pages of [pageRows] by row index; at most [maxPages] pages are kept (LRU, ≈ 300 rows).
 * - A row whose page is not loaded binds a placeholder and calls [request]; the caller loads the page off main and
 *   hands it to [put] with the [token] it started under ([reset] invalidates loads in flight).
 * - Prefetch: after each move the pages of `first − 25` and `last + 25` are ensured ([prefetch]), so a normal page turn
 *   never shows a placeholder.
 * - Fetch, then move: a far jump ([moveTo]) asks for the target page first; the caller moves the list when that page
 *   arrives ([put] returns [Put.MOVE]) or after a short timeout ([takeMove]).
 */
class NotesWindow<P>(val pageRows: Int = PAGE_ROWS, val maxPages: Int = MAX_PAGES) {
    /** Rows under the current query. */
    var count = 0
        private set
    /** Bumped by [reset]: a page loaded under an older token is dropped. */
    var token = 0
        private set
    private val pages = LinkedHashMap<Int, P>(maxPages + 2, 0.75f, true)
    private val pending = HashSet<Int>()
    /** Row a far jump waits to show, or -1. */
    var pendingMove = -1
        private set

    enum class Put { STALE, STORED, MOVE }

    /** A new query or data generation: drops every page and every load in flight. */
    fun reset(count: Int) {
        pages.clear()
        pending.clear()
        pendingMove = -1
        this.count = count.coerceAtLeast(0)
        token++
    }

    fun pageCount(): Int = (count + pageRows - 1) / pageRows

    fun pageOf(row: Int): Int = row.coerceAtLeast(0) / pageRows

    fun isLoaded(page: Int): Boolean = pages.containsKey(page)

    fun isPending(page: Int): Boolean = page in pending

    /** The page holding [row], or null when not loaded yet (touches the LRU order). */
    fun pageFor(row: Int): P? = if (row !in 0 until count) null else pages[pageOf(row)]

    /** Index of [row] inside its page. */
    fun indexIn(row: Int): Int = row.coerceAtLeast(0) % pageRows

    /** True when [page] must be loaded now: in range, not loaded, not already in flight. Marks it in flight. */
    fun request(page: Int): Boolean {
        if (page < 0 || page >= pageCount() || pages.containsKey(page) || page in pending) return false
        pending += page
        return true
    }

    /** A load of [page] finished under [token] ([page] null = failed). */
    fun put(token: Int, page: Int, value: P?): Put {
        if (token != this.token) return Put.STALE
        pending -= page
        if (value == null) return Put.STALE
        pages[page] = value
        while (pages.size > maxPages) {
            val eldest = pages.keys.iterator().next()
            if (pendingMove >= 0 && eldest == pageOf(pendingMove)) break
            pages.remove(eldest)
        }
        if (pendingMove >= 0 && pageOf(pendingMove) == page) return Put.MOVE
        return Put.STORED
    }

    /** Pages to ensure around the visible rows [first]..[last]: `first − 25` to `last + 25`, clamped. */
    fun prefetch(first: Int, last: Int, margin: Int = PREFETCH_ROWS): IntRange {
        if (count == 0) return IntRange.EMPTY
        val lo = pageOf((first - margin).coerceIn(0, count - 1))
        val hi = pageOf((last + margin).coerceIn(0, count - 1))
        return lo..hi
    }

    /** True when any visible row [first]..[last] is on [page] (only then is a re-bind worth a redraw). */
    fun visibleIn(page: Int, first: Int, last: Int): Boolean {
        if (first > last || count == 0) return false
        return page in pageOf(first)..pageOf(last)
    }

    /**
     * A far jump to [row]: returns true when its page is already loaded (move now), else records it and the caller
     * requests the page and waits for [Put.MOVE] or the timeout.
     */
    fun moveTo(row: Int): Boolean {
        val r = row.coerceIn(0, (count - 1).coerceAtLeast(0))
        if (pages.containsKey(pageOf(r))) {
            pendingMove = -1
            return true
        }
        pendingMove = r
        return false
    }

    /** The pending far jump's row (once), or -1. */
    fun takeMove(): Int {
        val r = pendingMove
        pendingMove = -1
        return r
    }

    /** Pages in memory (tests, budget logs). */
    fun loadedPages(): Set<Int> = pages.keys.toSet()

    companion object {
        const val PAGE_ROWS = 50
        const val MAX_PAGES = 6
        const val PREFETCH_ROWS = 25
        /** A far jump moves after its page arrives, or after this long at most. */
        const val MOVE_WAIT_MS = 250L
        /** The first draw of the hub waits this long at most for the counts and the first page. */
        const val FIRST_DRAW_WAIT_MS = 250L
        /** Search text debounce. */
        const val SEARCH_DEBOUNCE_MS = 300L
        /** Above this many selected notes the saved state keeps the query ("select all") instead of the ids. */
        const val MAX_SAVED_SELECTION = 20_000

        /** The first row of 1-based pager page [page] that moves [step] rows a page. */
        fun rowForPage(page: Int, step: Int): Int = ((page - 1).coerceAtLeast(0)) * step.coerceAtLeast(1)

        /** True when a selection of [size] is saved as its query (Bundle stays far below the 1 MB binder limit). */
        fun saveAsQuery(size: Int): Boolean = size > MAX_SAVED_SELECTION

        /** The first visible row to keep after a reload with [count] rows (clamped; 0 for an empty list). */
        fun clampFirst(first: Int, count: Int): Int = if (count <= 0) 0 else first.coerceIn(0, count - 1)
    }
}
