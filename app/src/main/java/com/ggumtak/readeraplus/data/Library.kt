package com.ggumtak.readeraplus.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.util.Log
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocMeta
import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.render.Covers
import com.ggumtak.readeraplus.settings.LibrarySort
import java.io.File
import java.io.IOException

/**
 * The library repository (SQLite, see [LibrarySchema]). All functions are blocking and must be called off the
 * main thread (Dispatchers.IO), except [init]. Thread-safe: one [SQLiteDatabase] shared by all threads (SQLite
 * serialises writers; WAL lets readers run alongside), multi-statement changes run in transactions.
 */
object Library {
    /**
     * In-memory change counter of everything the notes hub shows (N §5.1); every notes read cache is keyed by it.
     * Bumped by [notesChanged] AFTER the write that changed it has committed, so a reader that sees the new value
     * also sees the new rows (a cache filled from the old rows carries the old value and is dropped).
     */
    @Volatile var notesGen: Long = 0; private set

    private val genLock = Any()

    /**
     * Marks the hub's data changed ([notesGen]++). Call after the commit of a write the hub shows (quotes,
     * bookmarks, reviews, lookups, book removal / trash / revive / titles / paths, backup import); never inside a
     * transaction that may still roll back.
     */
    internal fun notesChanged() {
        synchronized(genLock) { notesGen++ }
    }

    private const val TAG = "Library"

    /** Max stored lengths (defensive caps; UI passes much shorter strings). */
    private const val MAX_SNIPPET = DataLimits.SNIPPET
    private const val MAX_NOTE = DataLimits.NOTE
    private const val MAX_QUOTE = DataLimits.QUOTE
    private const val MAX_REVIEW = DataLimits.REVIEW
    private const val MAX_COLLECTION_NAME = DataLimits.COLLECTION_NAME
    /** A single addReadingTime call never adds more than a day (guards against clock jumps). */
    private const val MAX_READING_ADD_S = 24L * 3600
    /** Page-count rows are "touched" on read at most this often (LRU order without a write per open). */
    private const val TOUCH_INTERVAL_MS = 60_000L

    @Volatile private var helper: LibraryDb? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var primaryRootCache: String? = null

    /** Cheap (no disk IO on the caller's thread): creates the helper and opens the file in the background. */
    fun init(context: Context) {
        if (helper != null) return
        synchronized(this) {
            if (helper != null) return
            val app = context.applicationContext ?: context
            appContext = app
            val h = LibraryDb(app)
            helper = h
            // Normal priority on purpose: the first library query blocks on this open, so a background-priority
            // thread would be starved by the busy startup (priority inversion on a slow CPU).
            val warm = Thread({
                try {
                    h.writableDatabase
                } catch (t: Throwable) {
                    Log.w(TAG, "database warm-up failed", t)
                }
            }, "library-db-open")
            warm.isDaemon = true
            warm.start()
        }
    }

    internal fun db(): SQLiteDatabase =
        (helper ?: throw IllegalStateException("Library.init() was not called")).writableDatabase

    internal fun context(): Context? = appContext

    /** Primary shared storage root (`/storage/emulated/<user>`), used to normalise alias paths. */
    internal fun primaryRoot(): String {
        primaryRootCache?.let { return it }
        val r = try {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()?.absolutePath
        } catch (_: Throwable) {
            null
        }
        val root = r?.takeIf { it.startsWith("/") && it.length > 1 }?.trimEnd('/') ?: DataPaths.PRIMARY_ROOT
        primaryRootCache = root
        return root
    }

    internal fun normalizePath(path: String): String = DataPaths.normalize(path, primaryRoot())

    // ---- books ----

    fun books(query: LibraryQuery, sort: LibrarySort): List<Book> {
        val q = LibrarySql.booksQuery(query, sort)
        val list = db().queryList(q.sql, q.args.takeIf { it.isNotEmpty() }, BookRows::book)
        return LibrarySql.sortBooks(list, query, sort)
    }

    fun groups(shelf: Shelf): List<ShelfGroup> {
        val sql = LibrarySql.groupsQuery(shelf) ?: return emptyList()
        val rows = db().queryList(sql, null) { c ->
            LibrarySql.groupRow(
                shelf,
                if (c.isNull(0)) null else c.getString(0),
                if (c.isNull(1)) null else c.getString(1),
                c.getInt(2),
            )
        }
        return LibrarySql.sortGroups(shelf, rows)
    }

    /** Number of books [query] lists (cheap COUNT, no rows mapped). */
    fun countBooks(query: LibraryQuery): Int {
        val q = LibrarySql.countQuery(query)
        return db().queryFirst(q.sql, q.args.takeIf { it.isNotEmpty() }) { it.getInt(0) } ?: 0
    }

    /**
     * Drawer counts of every shelf in one query: number of books for plain shelves, number of groups
     * (= `groups(shelf).size`) for grouped shelves.
     */
    fun shelfCounts(): Map<Shelf, Int> {
        val shelves = LibrarySql.COUNTED_SHELVES
        return db().queryFirst(LibrarySql.shelfCountsQuery(), null) { c ->
            val m = LinkedHashMap<Shelf, Int>(shelves.size * 2)
            for (i in shelves.indices) m[shelves[i]] = if (c.isNull(i)) 0 else c.getInt(i)
            m
        } ?: shelves.associateWith { 0 }
    }

    /** Drops every stored page count ("캐시 비우기"); they are recomputed when books are opened. */
    fun clearPageCounts() {
        db().execSQL("DELETE FROM page_counts")
    }

    /** Ids of books that belong to at least one collection. */
    fun collectionMemberIds(): Set<Long> =
        db().queryList(LibrarySql.SELECT_COLLECTION_MEMBER_IDS, null) { it.getLong(0) }.toHashSet()

    fun book(id: Long): Book? = db().queryFirst(LibrarySql.SELECT_BOOK_BY_ID, args(id), BookRows::book)

    fun bookByPath(path: String): Book? {
        val db = db()
        db.queryFirst(LibrarySql.SELECT_BOOK_BY_PATH, arrayOf(path), BookRows::book)?.let { return it }
        val norm = normalizePath(path)
        if (norm == path) return null
        return db.queryFirst(LibrarySql.SELECT_BOOK_BY_PATH, arrayOf(norm), BookRows::book)
    }

    /** Inserts or refreshes a file (reads metadata via Documents.readMeta when new/changed). */
    fun addOrUpdateFile(file: File): Book? = addOrUpdate(file, explicit = true)

    /**
     * [explicit] = the user asked for this file (open / import): clears a "removed from library" mark so the
     * file becomes a normal library entry again.
     */
    internal fun addOrUpdate(file: File, explicit: Boolean): Book? {
        val path = normalizePath(file.absolutePath)
        val f = File(path)
        val format = BookFormat.forFile(f.name) ?: return null
        val db = db()
        if (!f.isFile) return db.queryFirst(LibrarySql.SELECT_BOOK_BY_PATH, arrayOf(path), BookRows::book)
        val size = f.length()
        val mtime = f.lastModified()
        if (explicit) db.exec(LibrarySql.DELETE_IGNORED, path)
        var existing = db.queryFirst(LibrarySql.SELECT_BOOK_BY_PATH, arrayOf(path), BookRows::book)
        if (existing != null && existing.missingAt > 0) {
            // The file of a book the scanner trashed as missing is back at its own path: out of the trash again.
            if (db.exec(LibrarySql.CLEAR_MISSING, existing.id) > 0) {
                notesChanged()
                existing = existing.copy(trashed = false, missingAt = 0)
            }
        }
        if (existing != null && existing.sizeBytes == size && existing.modifiedAt == mtime) return existing
        val info = FileInfo(path, f.name, format, size, mtime)
        if (existing == null) {
            // Moved with a file manager and opened before the next scan: keep the old entry's history (the scan
            // would otherwise see the new path as known and drop the old entry with its bookmarks / quotes). A
            // missing entry (trashed by the scanner) is matched too, and revived.
            val from = movedEntry(db, info)
            if (from != null) {
                val meta = if (from.mtime == mtime) null else readMeta(f)
                db.inTransaction {
                    moveFile(this, from.id, info, meta)
                    exec(LibrarySql.CLEAR_MISSING, from.id)
                }
                notesChanged()
                db.queryFirst(LibrarySql.SELECT_BOOK_BY_PATH, arrayOf(path), BookRows::book)?.let { return it }
            }
        }
        val meta = readMeta(f)
        db.inTransaction { writeFile(this, info, meta, existing?.id) }
        notesChanged()
        return db.queryFirst(LibrarySql.SELECT_BOOK_BY_PATH, arrayOf(path), BookRows::book)
    }

    private class MoveCandidate(val id: Long, val path: String, val mtime: Long)

    /** A library entry [info]'s file was moved from (same name + size, its own file verifiably gone), or null. */
    private fun movedEntry(db: SQLiteDatabase, info: FileInfo): MoveCandidate? {
        val candidates = db.queryList(LibrarySql.SELECT_MOVE_CANDIDATES, arrayOf(info.fileName, info.size.toString())) {
            MoveCandidate(it.getLong(0), it.getString(1) ?: "", it.getLong(2))
        }
        if (candidates.isEmpty()) return null
        val ctx = appContext ?: return null
        return FileScanner.movedFrom(candidates, { it.path }, info.path, FileScanner.absenceTrust(ctx)) { p ->
            try {
                File(p).exists()
            } catch (_: Throwable) {
                true
            }
        }
    }

    /** Parser metadata, never throwing (unreadable / malformed files get a file-name title). */
    internal fun readMeta(f: File): MetaInfo {
        // TXT: the parser would only read 64 KB to sniff a charset the library doesn't store.
        if (!MetaInfo.needsParser(BookFormat.forFile(f.name))) return MetaInfo.from(null, f.name)
        val meta: DocMeta? = try {
            Documents.readMeta(f)
        } catch (t: Throwable) {
            Log.w(TAG, "readMeta failed: ${f.name}: $t")
            null
        }
        return MetaInfo.from(meta, f.name)
    }

    /**
     * Inserts [info] or refreshes the existing row (must run inside a transaction). Returns the book id, or -1.
     * A changed file loses its cached page counts; user-edited metadata is kept. The caller runs [notesChanged]
     * after the commit (a refreshed title shows in the notes hub).
     */
    internal fun writeFile(db: SQLiteDatabase, info: FileInfo, meta: MetaInfo, existingId: Long?): Long {
        var id = existingId ?: -1L
        if (id <= 0) {
            id = db.insertRow(
                LibrarySql.INSERT_BOOK,
                info.path, info.fileName, info.folder, meta.title, meta.author, meta.series, meta.seriesIndex,
                info.format.name, info.size, info.mtime, System.currentTimeMillis(), meta.language,
            )
            if (id > 0) return id
            // Row appeared concurrently (INSERT OR IGNORE): refresh it instead.
            id = db.queryFirst(LibrarySql.SELECT_ID_BY_PATH, arrayOf(info.path)) { it.getLong(0) } ?: return -1
        }
        db.exec(LibrarySql.UPDATE_BOOK_FILE, info.fileName, info.folder, info.format.name, info.size, info.mtime, id)
        db.exec(LibrarySql.UPDATE_BOOK_META, meta.title, meta.author, meta.series, meta.seriesIndex, meta.language, id)
        db.exec(LibrarySql.DELETE_PAGE_COUNTS_OF_BOOK, id)
        return id
    }

    /**
     * Re-points entry [id] to a moved file (must run inside a transaction). [meta] null = same content
     * (unchanged mtime): metadata and page counts stay; otherwise they are refreshed like a changed file. The caller
     * runs [notesChanged] after the commit (the hub's open check and the export read the path).
     */
    internal fun moveFile(db: SQLiteDatabase, id: Long, info: FileInfo, meta: MetaInfo?) {
        if (db.exec(LibrarySql.UPDATE_BOOK_PATH, info.path, id) == 0) return
        db.exec(LibrarySql.UPDATE_BOOK_FILE, info.fileName, info.folder, info.format.name, info.size, info.mtime, id)
        if (meta != null) {
            db.exec(LibrarySql.UPDATE_BOOK_META, meta.title, meta.author, meta.series, meta.seriesIndex, meta.language, id)
            db.exec(LibrarySql.DELETE_PAGE_COUNTS_OF_BOOK, id)
        }
    }

    fun savePosition(bookId: Long, section: Int, offset: Int, progress: Float) {
        val p = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
        db().exec(
            LibrarySql.UPDATE_POSITION,
            section.coerceAtLeast(0), offset.coerceAtLeast(0), p, System.currentTimeMillis(), bookId,
        )
    }

    fun addReadingTime(bookId: Long, seconds: Long) {
        if (seconds <= 0) return
        db().exec(LibrarySql.ADD_READING_TIME, seconds.coerceAtMost(MAX_READING_ADD_S), bookId)
    }

    fun setFavorite(bookId: Long, value: Boolean) {
        db().exec(LibrarySql.SET_FAVORITE, value, bookId)
    }

    /** Setting "to read" clears "have read" (and with it the finish time, see [setHaveRead]). */
    fun setToRead(bookId: Long, value: Boolean) {
        if (!value) {
            db().exec(LibrarySql.SET_TO_READ_OFF, bookId)
            return
        }
        db().inTransaction { toReadOn(this, bookId) }
    }

    /**
     * Setting "have read" clears "to read"; progress is left untouched. Clearing it also clears the book's finish
     * time (BookPrefs.finishedAt), so a book finished again later counts from the new date.
     */
    fun setHaveRead(bookId: Long, value: Boolean) {
        if (value) {
            db().exec(LibrarySql.SET_HAVE_READ_ON, bookId)
            return
        }
        db().inTransaction { haveReadOff(this, bookId) }
    }

    private fun toReadOn(db: SQLiteDatabase, bookId: Long) {
        db.exec(LibrarySql.SET_TO_READ_ON, bookId)
        clearFinished(db, bookId)
    }

    private fun haveReadOff(db: SQLiteDatabase, bookId: Long) {
        db.exec(LibrarySql.SET_HAVE_READ_OFF, bookId)
        clearFinished(db, bookId)
    }

    /** Clears [bookId]'s finish time, dropping its prefs row when nothing else is left in it. */
    internal fun clearFinished(db: SQLiteDatabase, bookId: Long) {
        if (db.exec(LibrarySql.CLEAR_FINISHED_AT, bookId) > 0) db.exec(LibrarySql.PRUNE_BOOK_PREFS, bookId)
    }

    /**
     * [value] false (복원) also clears `missing_at`: a restored missing book is an ordinary one again. True (휴지통)
     * clears it as well, so the scanner never takes a book the user trashed out of the trash.
     */
    fun setTrashed(bookId: Long, value: Boolean) {
        db().exec(if (value) LibrarySql.TRASH else LibrarySql.UNTRASH, bookId)
        notesChanged()
    }

    // ---- batch changes (library multi-select, T1-13): one transaction each, so N books cost one commit ----

    /** [setHaveRead] for every id of [ids] (unknown ids are ignored). */
    fun setHaveRead(ids: Collection<Long>, value: Boolean) {
        if (ids.isEmpty()) return
        db().inTransaction {
            for (id in ids) if (value) exec(LibrarySql.SET_HAVE_READ_ON, id) else haveReadOff(this, id)
        }
    }

    /** [setToRead] for every id of [ids]. */
    fun setToRead(ids: Collection<Long>, value: Boolean) {
        if (ids.isEmpty()) return
        db().inTransaction {
            for (id in ids) if (value) toReadOn(this, id) else exec(LibrarySql.SET_TO_READ_OFF, id)
        }
    }

    /** Adds every book of [ids] to collection [collectionId] (books already in it stay). */
    fun addToCollection(ids: Collection<Long>, collectionId: Long) {
        if (ids.isEmpty()) return
        db().inTransaction { for (id in ids) insertRow(LibrarySql.INSERT_MEMBERSHIP, id, collectionId) }
    }

    /** Moves every book of [ids] to the trash ([setTrashed] true). */
    fun trash(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        db().inTransaction { for (id in ids) exec(LibrarySql.TRASH, id) }
        notesChanged()
    }

    /** Stores the review with `review_at` = now (0 when blank: no review, nothing to date). */
    fun setReview(bookId: Long, text: String) {
        val t = cap(text.trimEnd(), MAX_REVIEW)
        db().exec(LibrarySql.SET_REVIEW, t, if (t.isEmpty()) 0L else System.currentTimeMillis(), bookId)
        notesChanged()
    }

    /** 리뷰 지우기 for every book of [bookIds] (review = '', review_at = 0), one transaction. */
    fun clearReviews(bookIds: Collection<Long>) {
        if (bookIds.isEmpty()) return
        db().inTransaction { for (id in bookIds) exec(LibrarySql.CLEAR_REVIEW, id) }
        notesChanged()
    }

    /** Forced TXT encoding ("" = auto). Cached page counts of the old decoding are dropped. */
    fun setEncoding(bookId: Long, encoding: String) {
        val db = db()
        db.inTransaction {
            exec(LibrarySql.SET_ENCODING, encoding.trim(), bookId)
            exec(LibrarySql.DELETE_PAGE_COUNTS_OF_BOOK, bookId)
        }
    }

    /** User edit of the metadata; kept across file refreshes. Blank title → file name. */
    fun updateMeta(bookId: Long, title: String, author: String, series: String?, seriesIndex: Float?) {
        val db = db()
        var t = MetaInfo.clean(title, 500)
        if (t.isEmpty()) {
            val path = db.queryFirst(LibrarySql.SELECT_PATH_BY_ID, args(bookId)) { it.getString(0) } ?: return
            t = MetaInfo.titleFromFileName(path)
        }
        val a = MetaInfo.clean(author, 300)
        val s = series?.let { MetaInfo.clean(it, 500) }?.ifEmpty { null }
        val i = if (s == null) null else seriesIndex?.takeIf { it.isFinite() }
        db.exec(LibrarySql.UPDATE_BOOK_META_USER, t, a, s, i, bookId)
        notesChanged()
    }

    /**
     * Clears position/progress/flags, the finish time and the pinned return point ("읽은 기록 초기화", U §3.3).
     * The reading log keeps its rows: the statistics show when the user read, and that reading did happen. Touches
     * no notes, so [notesGen] stays.
     */
    fun resetProgress(bookId: Long) {
        db().inTransaction {
            exec(LibrarySql.RESET_PROGRESS, bookId)
            exec(LibrarySql.CLEAR_FINISHED_AT, bookId)
            exec(LibrarySql.CLEAR_RETURN_MARK, bookId)
            exec(LibrarySql.PRUNE_BOOK_PREFS, bookId)
        }
    }

    /** Removes the entry (and its bookmarks/quotes/caches); deletes the file too when [deleteFile]. */
    fun remove(bookId: Long, deleteFile: Boolean) {
        val db = db()
        val path = db.queryFirst(LibrarySql.SELECT_PATH_BY_ID, args(bookId)) { it.getString(0) } ?: return
        if (deleteFile) deleteFileOrThrow(path)
        val now = System.currentTimeMillis()
        db.inTransaction {
            deleteBookRows(this, bookId)
            // A kept file must not come back with the next scan; a deleted one may be re-created later.
            if (deleteFile) exec(LibrarySql.DELETE_IGNORED, path) else insertRow(LibrarySql.INSERT_IGNORED, path, now)
        }
        notesChanged()
        invalidateCover(bookId)
    }

    /**
     * "휴지통 비우기" of the books [ids] that are still in the trash. The ids are the ones the question counted notes
     * over: a book trashed since (a scan marking a vanished file) stays in the trash, so its notes are never deleted
     * without the warning (NOTES_SPEC §10.1).
     */
    fun emptyTrash(ids: Collection<Long>, deleteFiles: Boolean) {
        val db = db()
        val wanted = ids.toHashSet()
        val trashed = db.queryList(LibrarySql.SELECT_TRASHED_IDS, null) { it.getLong(0) to (it.getString(1) ?: "") }
            .filter { it.first in wanted }
        if (trashed.isEmpty()) return
        val removed = ArrayList<Pair<Long, String>>(trashed.size)
        var failed = 0
        for (entry in trashed) {
            if (deleteFiles) {
                try {
                    deleteFileOrThrow(entry.second)
                } catch (e: IOException) {
                    failed++
                    continue
                }
            }
            removed += entry
        }
        val now = System.currentTimeMillis()
        db.inTransaction {
            for ((id, path) in removed) {
                deleteBookRows(this, id)
                if (deleteFiles) exec(LibrarySql.DELETE_IGNORED, path) else insertRow(LibrarySql.INSERT_IGNORED, path, now)
            }
        }
        if (removed.isNotEmpty()) notesChanged()
        for ((id, _) in removed) invalidateCover(id)
        if (failed > 0) throw IOException("파일 ${failed}개를 삭제하지 못했습니다")
    }

    fun lastOpened(): Book? = db().queryFirst(LibrarySql.SELECT_LAST_OPENED, null, BookRows::book)

    /**
     * Deletes a book row and everything hanging off it, its lookups included (must run inside a transaction). The
     * caller runs [notesChanged] after the commit.
     */
    internal fun deleteBookRows(db: SQLiteDatabase, bookId: Long) {
        db.exec(LibrarySql.DELETE_BOOKMARKS_OF_BOOK, bookId)
        db.exec(LibrarySql.DELETE_QUOTES_OF_BOOK, bookId)
        db.exec(LibrarySql.DELETE_LOOKUPS_OF_BOOK, bookId)
        db.exec(LibrarySql.DELETE_MEMBERSHIPS_OF_BOOK, bookId)
        db.exec(LibrarySql.DELETE_PAGE_COUNTS_OF_BOOK, bookId)
        db.exec(LibrarySql.DELETE_LOG_OF_BOOK, bookId)
        db.exec(LibrarySql.DELETE_BOOK_PREFS_OF_BOOK, bookId)
        db.exec(LibrarySql.DELETE_BOOK, bookId)
    }

    private fun deleteFileOrThrow(path: String) {
        if (path.isEmpty()) return
        val f = File(path)
        if (f.exists() && !f.delete() && f.exists()) throw IOException("파일을 삭제할 수 없습니다: ${f.name}")
    }

    internal fun invalidateCover(bookId: Long) {
        val ctx = appContext ?: return
        try {
            Covers.invalidate(ctx, bookId)
        } catch (t: Throwable) {
            Log.w(TAG, "cover invalidate failed: $t")
        }
    }

    /** Number of (non-trashed) books. */
    internal fun count(): Int = db().queryFirst(LibrarySql.COUNT_LIBRARY, null) { it.getInt(0) } ?: 0

    // ---- bookmarks & quotes ----

    fun bookmarks(bookId: Long): List<Bookmark> =
        db().queryList(LibrarySql.SELECT_BOOKMARKS, args(bookId), BookRows::bookmark)

    /** [place] = where the bookmark sits (reader's NotePlaceHost); null stores "unknown" ('' / -1 / ''). */
    fun addBookmark(bookId: Long, section: Int, offset: Int, snippet: String, place: NotePlace? = null): Bookmark {
        val now = System.currentTimeMillis()
        val s = cap(snippet.trim(), MAX_SNIPPET)
        val sec = section.coerceAtLeast(0)
        val off = offset.coerceAtLeast(0)
        val p = NoteWrites.place(place)
        val id = db().insertRow(LibrarySql.INSERT_BOOKMARK, bookId, sec, off, s, "", now, p.chapter, p.frac, p.sig)
        if (id > 0) notesChanged()
        return Bookmark(
            id = id, bookId = bookId, section = sec, offset = off, snippet = s, createdAt = now,
            chapter = p.chapter, frac = p.frac, sig = p.sig,
        )
    }

    fun deleteBookmark(id: Long) {
        if (db().exec(LibrarySql.DELETE_BOOKMARK, id) > 0) notesChanged()
    }

    /** Deletes every bookmark of [ids] in one transaction (hub multi-select). */
    fun deleteBookmarks(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val n = db().inTransaction { ids.sumOf { exec(LibrarySql.DELETE_BOOKMARK, it) } }
        if (n > 0) notesChanged()
    }

    fun updateBookmarkNote(id: Long, note: String) {
        if (db().exec(LibrarySql.UPDATE_BOOKMARK_NOTE, cap(note.trim(), MAX_NOTE), id) > 0) notesChanged()
    }

    fun quotes(bookId: Long): List<Quote> = db().queryList(LibrarySql.SELECT_QUOTES, args(bookId), BookRows::quote)

    /**
     * [style] = QuoteStyles id (clamped to 0..[DataLimits.QUOTE_STYLE_MAX]); [place] = where the quote sits (null
     * stores "unknown").
     */
    fun addQuote(bookId: Long, section: Int, start: Int, end: Int, text: String, note: String = "", style: Int = 0, place: NotePlace? = null): Quote {
        val now = System.currentTimeMillis()
        val s = minOf(start, end).coerceAtLeast(0)
        val e = maxOf(start, end).coerceAtLeast(0)
        val t = cap(text, MAX_QUOTE)
        val n = cap(note.trim(), MAX_NOTE)
        val sec = section.coerceAtLeast(0)
        val st = NoteWrites.style(style)
        val p = NoteWrites.place(place)
        val id = db().insertRow(LibrarySql.INSERT_QUOTE, bookId, sec, s, e, t, n, now, st, p.chapter, p.frac, p.sig)
        if (id > 0) notesChanged()
        return Quote(
            id = id, bookId = bookId, section = sec, start = s, end = e, text = t, note = n, createdAt = now,
            style = st, chapter = p.chapter, frac = p.frac, sig = p.sig,
        )
    }

    fun deleteQuote(id: Long) {
        if (db().exec(LibrarySql.DELETE_QUOTE, id) > 0) notesChanged()
    }

    /** Deletes every quote of [ids] in one transaction (hub multi-select). */
    fun deleteQuotes(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val n = db().inTransaction { ids.sumOf { exec(LibrarySql.DELETE_QUOTE, it) } }
        if (n > 0) notesChanged()
    }

    fun updateQuoteNote(id: Long, note: String) {
        if (db().exec(LibrarySql.UPDATE_QUOTE_NOTE, cap(note.trim(), MAX_NOTE), id) > 0) notesChanged()
    }

    /** 색 바꾸기: [style] clamped to 0..[DataLimits.QUOTE_STYLE_MAX]. */
    fun updateQuoteStyle(id: Long, style: Int) {
        if (db().exec(LibrarySql.UPDATE_QUOTE_STYLE, NoteWrites.style(style), id) > 0) notesChanged()
    }

    /** Recolours every quote of [ids] in one transaction (hub batch 색 바꾸기). */
    fun setQuoteStyles(ids: Collection<Long>, style: Int) {
        if (ids.isEmpty()) return
        val st = NoteWrites.style(style)
        val n = db().inTransaction { ids.sumOf { exec(LibrarySql.UPDATE_QUOTE_STYLE, st, it) } }
        if (n > 0) notesChanged()
    }

    /**
     * Backfill of legacy notes' places, computed by the reader for book [bookId] (keys = note ids). Only rows whose
     * place is still unknown (`frac < 0`) are touched, and their `sig` stays '' (it was not known at creation). An
     * entry with an unknown place itself is skipped. One transaction. ([bookId] names the book the places were
     * computed for; the note ids alone address the rows.)
     */
    fun fillNotePlaces(bookId: Long, quotes: Map<Long, NotePlace>, bookmarks: Map<Long, NotePlace>) {
        val q = NoteWrites.backfill(quotes)
        val b = NoteWrites.backfill(bookmarks)
        if (q.isEmpty() && b.isEmpty()) return
        val n = db().inTransaction {
            var changed = 0
            for ((id, p) in q) changed += exec(LibrarySql.UPDATE_QUOTE_PLACE, p.chapter, p.frac, id)
            for ((id, p) in b) changed += exec(LibrarySql.UPDATE_BOOKMARK_PLACE, p.chapter, p.frac, id)
            changed
        }
        if (n > 0) notesChanged()
    }

    // ---- collections ----

    fun collections(): List<BookCollection> =
        db().queryList(LibrarySql.SELECT_COLLECTIONS, null, BookRows::collection)
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }

    /** Creates a collection; an existing one with the same name (ignoring case) is returned instead. */
    fun createCollection(name: String): BookCollection {
        val n = collectionName(name)
        require(n.isNotEmpty()) { "컬렉션 이름이 비어 있습니다" }
        val db = db()
        return db.inTransaction {
            val existing = queryFirst(LibrarySql.SELECT_COLLECTION_BY_NAME, arrayOf(n), BookRows::collection)
            existing ?: run {
                val now = System.currentTimeMillis()
                BookCollection(insertRow(LibrarySql.INSERT_COLLECTION, n, now), n, now, 0)
            }
        }
    }

    /** Id of the collection named [name] (case-insensitive), creating it (no own transaction). -1 for blank names. */
    internal fun ensureCollection(db: SQLiteDatabase, name: String, createdAt: Long): Long {
        val n = collectionName(name)
        if (n.isEmpty()) return -1
        val existing = db.queryFirst(LibrarySql.SELECT_COLLECTION_BY_NAME, arrayOf(n)) { it.getLong(0) }
        return existing ?: db.insertRow(LibrarySql.INSERT_COLLECTION, n, createdAt)
    }

    /** Throws (SQLiteConstraintException) when another collection already has [name]. */
    fun renameCollection(id: Long, name: String) {
        val n = collectionName(name)
        require(n.isNotEmpty()) { "컬렉션 이름이 비어 있습니다" }
        db().exec(LibrarySql.RENAME_COLLECTION, n, id)
    }

    fun deleteCollection(id: Long) {
        db().inTransaction {
            exec(LibrarySql.DELETE_MEMBERSHIPS_OF_COLLECTION, id)
            exec(LibrarySql.DELETE_COLLECTION, id)
        }
    }

    fun collectionsOf(bookId: Long): Set<Long> =
        db().queryList(LibrarySql.SELECT_COLLECTIONS_OF_BOOK, args(bookId)) { it.getLong(0) }.toHashSet()

    fun setInCollection(bookId: Long, collectionId: Long, member: Boolean) {
        if (member) {
            db().insertRow(LibrarySql.INSERT_MEMBERSHIP, bookId, collectionId)
        } else {
            db().exec(LibrarySql.DELETE_MEMBERSHIP, bookId, collectionId)
        }
    }

    internal fun collectionName(name: String): String = MetaInfo.clean(name, MAX_COLLECTION_NAME)

    /** Identity of a collection name, folded like the `name` column's NOCASE collation (ASCII only). */
    internal fun collectionKey(name: String): String = MetaInfo.asciiLower(collectionName(name))

    // ---- caches ----

    /**
     * Per-section page counts for a layout key (see reader), or null. R2 (A2): the array may be PARTIAL — -1
     * ([PageCountCodec.UNKNOWN]) marks a section not counted yet (PageCounts.setKnown takes only the entries ≥ 0).
     * A malformed blob (any value below -1) reads as null: recounted, never wrong counts.
     */
    fun pageCounts(bookId: Long, layoutKey: String): IntArray? {
        val db = db()
        val row = db.queryFirst(LibrarySql.SELECT_PAGE_COUNTS, arrayOf(bookId.toString(), layoutKey)) {
            it.getBlob(0) to it.getLong(1)
        } ?: return null
        val counts = PageCountCodec.decode(row.first) ?: return null
        val now = System.currentTimeMillis()
        if (now - row.second > TOUCH_INTERVAL_MS || now < row.second) {
            try {
                db.exec(LibrarySql.TOUCH_PAGE_COUNTS, now, bookId, layoutKey)
            } catch (t: Throwable) {
                Log.w(TAG, "page count touch failed: $t")
            }
        }
        return counts
    }

    /**
     * Stores counts for [layoutKey]; only the 3 most recently used keys per book are kept. R2 (A2): [counts] may be
     * partial (-1 = unknown section); the reader saves every 25 counted sections and on close, copying the array on
     * the main thread first. One small BLOB write (≈ 6 KB for 1,565 sections).
     */
    fun savePageCounts(bookId: Long, layoutKey: String, counts: IntArray) {
        val blob = PageCountCodec.encode(counts)
        val now = System.currentTimeMillis()
        db().inTransaction {
            insertRow(LibrarySql.REPLACE_PAGE_COUNTS, bookId, layoutKey, blob, now)
            exec(LibrarySql.PRUNE_PAGE_COUNTS, bookId, bookId)
        }
    }

    internal fun cap(s: String, max: Int): String = MetaInfo.truncate(s, max)
}
