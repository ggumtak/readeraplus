package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.data.TxtOverride
import com.ggumtak.readeraplus.format.BookFormat

/**
 * The book the reader had open when it opened 설정, so 읽기 설정 can offer that book's own TXT options ("이 책의 TXT
 * 정리"), and the edits made there, which the reader takes back once in its onResume: the new override is applied
 * there (at most one re-parse for any number of changes), a new encoding re-opens the book. Main thread only.
 *
 * Nothing here is the only copy: 설정 saves every edit (BookPrefs, the library) as it is made, so a reader that lost
 * this object (the process was killed) reads them back on its next open. [SettingsActivity.open] sets [info] (null
 * when 설정 is opened from anywhere but the reader); pending [Edits] survive that, until their reader takes them.
 */
object OpenBook {
    class Info(val id: Long, val title: String, val format: BookFormat, encoding: String, override: TxtOverride?) {
        /** The book's encoding ("" = detected), as last chosen in 설정 (starts as the reader's). */
        var encoding: String = encoding
            internal set

        /** The book's TXT override as last edited in 설정 (starts as the reader's). */
        var override: TxtOverride? = override
            internal set
    }

    /** What 설정 changed for one book, not yet taken by its reader. */
    class Edits(val bookId: Long) {
        /** [override] was set in 설정 (null there = the book follows the TXT defaults again). */
        var overrideChanged = false
            internal set
        var override: TxtOverride? = null
            internal set
        /** A new encoding chosen in 설정 (already saved in the library); null = unchanged. */
        var encoding: String? = null
            internal set
    }

    /** The reader's book while 설정 is open from the reader; null otherwise. */
    var info: Info? = null
        private set

    private var edits: Edits? = null

    /** Called by [SettingsActivity.open]: the reader's book, or null. A different book drops older pending edits. */
    fun open(book: Info?) {
        info = book
        if (book != null && edits?.bookId != book.id) edits = null
    }

    fun overrideEdited(bookId: Long, o: TxtOverride?) {
        val v = o?.takeUnless { it.isEmpty }
        info?.takeIf { it.id == bookId }?.override = v
        val e = editsFor(bookId)
        e.overrideChanged = true
        e.override = v
    }

    fun encodingEdited(bookId: Long, encoding: String) {
        info?.takeIf { it.id == bookId }?.encoding = encoding
        editsFor(bookId).encoding = encoding
    }

    /** The pending edits for [bookId] (removed: each is taken once), or null. */
    fun take(bookId: Long): Edits? {
        val e = edits?.takeIf { it.bookId == bookId } ?: return null
        edits = null
        return e
    }

    private fun editsFor(bookId: Long): Edits = edits?.takeIf { it.bookId == bookId } ?: Edits(bookId).also { edits = it }
}
