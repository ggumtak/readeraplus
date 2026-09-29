package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.view.View
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.reader.ReaderHost

/**
 * Reader panels (ReadEra-like): reading settings popup, contents (목차 · 북마크 · 인용문), in-book search,
 * review, document properties and go-to-page. Wired by ReaderActivity. All calls on the main thread.
 */
object ReaderPanels {
    /** ReadEra-style reading settings popup anchored under the toolbar gear. */
    fun showReadingSettings(host: ReaderHost, anchor: View) {
        ReadingSettingsPopup(host, anchor).show()
    }

    /** Full-screen TOC / bookmarks / quotes (tabs 목차 · 북마크 · 인용문). */
    fun showContents(host: ReaderHost, initialTab: Int = 0) {
        ContentsDialog(host, initialTab).show()
    }

    /** Full-screen in-book search with results list (snippet + page). */
    fun showSearch(host: ReaderHost, initialQuery: String = "") {
        SearchNavBar.remove()
        SearchPanel.show(host, initialQuery)
    }

    /** "내 리뷰" dialog. */
    fun showReview(host: ReaderHost) {
        InfoDialogs.review(host)
    }

    /** Document properties dialog (also used from the library with document == null). */
    fun showDocumentInfo(activity: Activity, book: Book, document: BookDocument?) {
        InfoDialogs.documentInfo(activity, book, document)
    }

    /** Go-to-page dialog (page number or percent). */
    fun showGoTo(host: ReaderHost) {
        InfoDialogs.goTo(host)
    }

    /**
     * Closes the search-results bar over the page (and its highlight), if shown. Returns true when something was
     * closed — lets the reader's BACK handling close it first.
     */
    fun closeSearchBar(host: ReaderHost): Boolean {
        val shown = SearchNavBar.isShown()
        SearchPanel.clearHighlight(host)
        SearchNavBar.remove()
        return shown
    }
}
