package com.ggumtak.readeraplus.reader

import android.app.Activity
import android.view.View
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.settings.ReaderSettings

/**
 * What ReaderActivity exposes to reader components (settings panel, TOC, search, selection, TTS, dialogs).
 * Implemented by ReaderActivity (reader core owner). All methods are main-thread only.
 */
/** Scroll mode exposes the current virtual viewport as currentPage. Never retain a PageInfo across calls.
 * Navigation and selection suppress motion before reading positions; frame updates do not save or rebuild decor.
 * goTo(pos, remember=true) creates a return point. Main thread unless explicitly documented. */
interface ReaderHost {
    val activity: Activity
    val book: Book
    /** Null while the document is still opening. */
    val document: BookDocument?

    /** Layout of the section currently shown, or null while laying out. */
    val currentLayout: SectionLayout?
    val currentPageIndex: Int
    val currentPage: PageInfo?

    /** First char of the current page. */
    fun currentPosition(): DocPosition

    /**
     * Navigates to [pos] (lays out the section if needed). When [remember] the position before the jump is
     * pushed so the "돌아가기" chip can return to it.
     */
    fun goTo(pos: DocPosition, remember: Boolean = true)

    /** One page on (TTS, auto turn). False when nothing turned: the book's edge, or a page already on its way. */
    fun nextPage(): Boolean
    fun prevPage(): Boolean

    /**
     * A turn asked for now would take effect at once: no page, jump or scroll step is on its way and no turn waits
     * behind one. The selection's edge dwell turns only then (a turn that queues would be flushed with the next).
     */
    fun canTurnNow(): Boolean = true

    /**
     * "page / total": global 1-based page number of [pos] and total pages, plain numbers (no "~"). They are estimates
     * until counting finishes ([totalPagesKnown]).
     */
    fun pageLabel(pos: DocPosition): String
    fun totalPagesKnown(): Boolean

    /**
     * What to show instead of a page number while the pages are not counted ([ReaderFormat.PAGES_COUNTING], or
     * [ReaderFormat.PAGES_FAILED] when counting stopped); null once the numbers are exact. Displays only: navigation
     * may still use [pageLabel]'s estimate.
     */
    fun pagesPending(): String? = if (totalPagesKnown()) null else ReaderFormat.PAGES_COUNTING

    /**
     * [l] runs (main thread) once the pages are exact, or counting has failed, on a phone: a display that showed
     * [pagesPending] re-reads it. Not on e-ink, where numbers change with the user's next action. Add while the display is
     * visible and [removeCountsListener] (the same lambda) when it goes; adding twice is the same as once.
     */
    fun addCountsListener(l: () -> Unit) {}
    fun removeCountsListener(l: () -> Unit) {}

    /** Replaces highlights of a given owner key (e.g. "tts", "search", "selection", "quotes") and redraws. */
    fun setHighlights(owner: String, section: Int, highlights: List<Highlight>)

    /** Applies new reader settings: re-layout keeping the current position. */
    fun applySettings(settings: ReaderSettings)

    /** Show/hide the top/bottom chrome. */
    fun setChromeVisible(visible: Boolean)

    /** The page view (for anchoring popups and converting coordinates). */
    val pageView: View

    /**
     * Px at the page view's top that a display cutout covers (fullscreen, the S25's camera band): the header's band and
     * the text box start below them (`LayoutKeys.geometry`'s extraTop). 0 without one.
     */
    val pageCutoutTop: Int get() = 0

    /**
     * Scroll mode: [section] stays the one [currentPage] / [hitTest] describe after a settle (a selection running over
     * screens; a settle otherwise returns to the anchor's section) until the selection ends. Paged: nothing to do.
     */
    fun holdSection(section: Int) {}

    /** Converts view coordinates to (section offset) on the current page, or -1. */
    fun hitTest(x: Float, y: Float): Int

    /** Text of [section] between [start] and [end] (loads the section if needed; main-thread safe only if cached). */
    fun textOf(section: Int, start: Int, end: Int): String

    /** Bookmark toggling on the current page (used by tap actions and menus). */
    fun toggleBookmark()

    fun redraw()
}
