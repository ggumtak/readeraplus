package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.view.View
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.reader.ReaderHost

/** CONTRACT STUB — reader-extras owner implements. Wired by ReaderActivity. See docs/ARCHITECTURE.md. */
object ReaderPanels {
    /** ReadEra-style reading settings popup anchored under the toolbar gear. */
    fun showReadingSettings(host: ReaderHost, anchor: View): Unit = TODO("reader-extras")
    /** Full-screen TOC / bookmarks / quotes (tabs 목차 · 북마크 · 인용문). */
    fun showContents(host: ReaderHost, initialTab: Int = 0): Unit = TODO("reader-extras")
    /** Full-screen in-book search with results list (snippet + page). */
    fun showSearch(host: ReaderHost, initialQuery: String = ""): Unit = TODO("reader-extras")
    /** "내 리뷰" dialog. */
    fun showReview(host: ReaderHost): Unit = TODO("reader-extras")
    /** Document properties dialog (also used from the library with document == null). */
    fun showDocumentInfo(activity: Activity, book: Book, document: BookDocument?): Unit = TODO("reader-extras")
    /** Go-to-page dialog (page number or percent). */
    fun showGoTo(host: ReaderHost): Unit = TODO("reader-extras")
}
