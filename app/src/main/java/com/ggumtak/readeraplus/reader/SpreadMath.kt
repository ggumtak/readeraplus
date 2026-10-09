package com.ggumtak.readeraplus.reader

/**
 * Pure page math of the landscape spread (two pages side by side, [PageGeometry.columns] 2; unit-tested). A spread is
 * named by its LEFT page, which is the reader's current page: spreads start at even page indices of a section (0, 2,
 * 4 …), so the page a jump names (k) is shown as the spread that starts at k − (k % 2). The right page is the next one
 * of the same section, else blank: a chapter (section) ends on its own spread and the next one starts on the left of
 * the next spread, as in a printed book; no page is ever shown twice and nothing depends on what is laid out yet.
 * Turns move by whole spreads; a section's last spread turns to the next section's page 0 on the left.
 */
object SpreadMath {
    /** What the right side of a spread shows. */
    enum class Right { SAME_SECTION, NEXT_SECTION, BLANK }

    /** The left page of the spread that shows page [pageIndex]. */
    fun start(pageIndex: Int): Int = if (pageIndex <= 0) 0 else pageIndex - pageIndex % 2

    /** Spreads in a section of [pageCount] pages. */
    fun spreads(pageCount: Int): Int = (pageCount.coerceAtLeast(1) + 1) / 2

    /**
     * The right side of the spread whose left page is [left] of a section of [pageCount] pages. [hasNext]: a section
     * follows; [nextLoaded]: its layout is cached (the first page is then shown on the right).
     */
    fun right(left: Int, pageCount: Int, hasNext: Boolean, nextLoaded: Boolean): Right = when {
        left + 1 < pageCount -> Right.SAME_SECTION
        else -> Right.BLANK // the next section starts the next spread (never its page 0 here, then again on the left)
    }

    /**
     * [TurnMath.walk] in whole spreads: [delta] turns from the spread starting at page [pageIndex] of [section].
     * [pagesOf] gives a section's page count (-1 = not known). The page index of the result is a spread's left page
     * (even), [TurnMath.LAST_PAGE] when the walk stops on the last spread of an unknown section.
     */
    fun walk(section: Int, pageIndex: Int, delta: Int, sectionCount: Int, pagesOf: (Int) -> Int): TurnWalk {
        val w = TurnMath.walk(section, start(pageIndex) / 2, delta, sectionCount) { sec ->
            val n = pagesOf(sec)
            if (n < 0) -1 else spreads(n)
        }
        return if (w.pageIndex >= 0) TurnWalk(w.section, w.pageIndex * 2, w.remaining, w.hitEdge) else w
    }

    /** [walk] that knows only [section]'s own [pageCount]. */
    fun walkInSection(section: Int, pageIndex: Int, delta: Int, sectionCount: Int, pageCount: Int): TurnWalk =
        walk(section, pageIndex, delta, sectionCount) { if (it == section) pageCount else -1 }

    /** The page [pageIndex] a layout of [pageCount] pages is shown at in a spread: a spread's left page (-2 = the last). */
    fun shownAt(pageIndex: Int, pageCount: Int): Int =
        start(if (pageIndex == TurnMath.LAST_PAGE) pageCount - 1 else pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
}
