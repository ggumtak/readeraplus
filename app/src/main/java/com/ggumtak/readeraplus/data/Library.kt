package com.ggumtak.readeraplus.data

import android.content.Context
import com.ggumtak.readeraplus.settings.LibrarySort
import java.io.File

/**
 * CONTRACT STUB — the library repository (SQLite). Implemented by the data module owner.
 * All functions are blocking and must be called off the main thread (Dispatchers.IO), except init().
 * Thread-safe. See docs/ARCHITECTURE.md "data".
 */
object Library {
    fun init(context: Context): Unit = TODO("data")

    // ---- books ----
    fun books(query: LibraryQuery, sort: LibrarySort): List<Book> = TODO("data")
    fun groups(shelf: Shelf): List<ShelfGroup> = TODO("data")
    fun book(id: Long): Book? = TODO("data")
    fun bookByPath(path: String): Book? = TODO("data")
    /** Inserts or refreshes a file (reads metadata via Documents.readMeta when new/changed). */
    fun addOrUpdateFile(file: File): Book? = TODO("data")
    fun savePosition(bookId: Long, section: Int, offset: Int, progress: Float): Unit = TODO("data")
    fun addReadingTime(bookId: Long, seconds: Long): Unit = TODO("data")
    fun setFavorite(bookId: Long, value: Boolean): Unit = TODO("data")
    fun setToRead(bookId: Long, value: Boolean): Unit = TODO("data")
    fun setHaveRead(bookId: Long, value: Boolean): Unit = TODO("data")
    fun setTrashed(bookId: Long, value: Boolean): Unit = TODO("data")
    fun setReview(bookId: Long, text: String): Unit = TODO("data")
    fun setEncoding(bookId: Long, encoding: String): Unit = TODO("data")
    fun updateMeta(bookId: Long, title: String, author: String, series: String?, seriesIndex: Float?): Unit = TODO("data")
    /** Clears position/progress/flags ("읽은 기록 초기화"). */
    fun resetProgress(bookId: Long): Unit = TODO("data")
    /** Removes the entry (and its bookmarks/quotes/caches); deletes the file too when [deleteFile]. */
    fun remove(bookId: Long, deleteFile: Boolean): Unit = TODO("data")
    fun emptyTrash(deleteFiles: Boolean): Unit = TODO("data")
    fun lastOpened(): Book? = TODO("data")

    // ---- bookmarks & quotes ----
    fun bookmarks(bookId: Long): List<Bookmark> = TODO("data")
    fun addBookmark(bookId: Long, section: Int, offset: Int, snippet: String): Bookmark = TODO("data")
    fun deleteBookmark(id: Long): Unit = TODO("data")
    fun updateBookmarkNote(id: Long, note: String): Unit = TODO("data")
    fun quotes(bookId: Long): List<Quote> = TODO("data")
    fun addQuote(bookId: Long, section: Int, start: Int, end: Int, text: String, note: String = ""): Quote = TODO("data")
    fun deleteQuote(id: Long): Unit = TODO("data")
    fun updateQuoteNote(id: Long, note: String): Unit = TODO("data")

    // ---- collections ----
    fun collections(): List<BookCollection> = TODO("data")
    fun createCollection(name: String): BookCollection = TODO("data")
    fun renameCollection(id: Long, name: String): Unit = TODO("data")
    fun deleteCollection(id: Long): Unit = TODO("data")
    fun collectionsOf(bookId: Long): Set<Long> = TODO("data")
    fun setInCollection(bookId: Long, collectionId: Long, member: Boolean): Unit = TODO("data")

    // ---- caches ----
    /** Per-section page counts for a layout key (see reader), or null. */
    fun pageCounts(bookId: Long, layoutKey: String): IntArray? = TODO("data")
    fun savePageCounts(bookId: Long, layoutKey: String, counts: IntArray): Unit = TODO("data")
}

/**
 * CONTRACT STUB — finds book files on storage and syncs them into Library.
 */
object FileScanner {
    /** Scans roots (AppSettings.scanFolders or primary storage), adds new files, drops vanished ones.
     *  Calls [progress] with the number of files found so far. Returns number of books in library. */
    fun scan(context: Context, progress: (Int) -> Unit = {}): Int = TODO("data")

    /** Default roots when the user configured none. */
    fun defaultRoots(context: Context): List<File> = TODO("data")
}

/**
 * CONTRACT STUB — JSON export/import of library state (flags, positions, bookmarks, quotes,
 * collections, reviews) + settings.
 */
object Backup {
    /** Writes a backup JSON to [out]. */
    fun export(context: Context, out: java.io.OutputStream): Unit = TODO("data")
    /** Restores from JSON; books are matched by path, then by file name + size. Returns restored book count. */
    fun import(context: Context, input: java.io.InputStream): Int = TODO("data")
}
