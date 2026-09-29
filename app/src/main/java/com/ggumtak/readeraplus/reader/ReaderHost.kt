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

    fun nextPage(): Boolean
    fun prevPage(): Boolean

    /** Global 1-based page number of [pos] and total pages; estimated (prefixed "~") until counting finishes. */
    fun pageLabel(pos: DocPosition): String
    fun totalPagesKnown(): Boolean

    /** Replaces highlights of a given owner key (e.g. "tts", "search", "selection", "quotes") and redraws. */
    fun setHighlights(owner: String, section: Int, highlights: List<Highlight>)

    /** Applies new reader settings: re-layout keeping the current position. */
    fun applySettings(settings: ReaderSettings)

    /** Show/hide the top/bottom chrome. */
    fun setChromeVisible(visible: Boolean)

    /** The page view (for anchoring popups and converting coordinates). */
    val pageView: View

    /** Converts view coordinates to (section offset) on the current page, or -1. */
    fun hitTest(x: Float, y: Float): Int

    /** Text of [section] between [start] and [end] (loads the section if needed; main-thread safe only if cached). */
    fun textOf(section: Int, start: Int, end: Int): String

    /** Bookmark toggling on the current page (used by tap actions and menus). */
    fun toggleBookmark()

    fun redraw()
}
