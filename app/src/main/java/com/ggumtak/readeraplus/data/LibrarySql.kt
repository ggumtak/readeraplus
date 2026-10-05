package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.settings.LibrarySort
import java.text.Normalizer

/** A SELECT with its positional (string) arguments, as passed to `SQLiteDatabase.rawQuery`. */
internal class SqlQuery(val sql: String, val args: Array<String>) {
    override fun toString(): String = "$sql  -- ${args.joinToString()}"
}

/**
 * All SQL used by the data module, as constants or pure builders (JVM-testable; SQLite itself only runs
 * on the device). Keep in sync with [LibrarySchema].
 */
internal object LibrarySql {

    /** Column list mapped by [BookRows]; the order is part of the contract with [BookRows]. */
    const val BOOK_COLUMNS = "id, path, file_name, title, author, series, series_index, format, size, mtime, " +
        "added_at, last_read_at, pos_section, pos_offset, progress, favorite, to_read, have_read, trashed, " +
        "review, encoding, language, reading_seconds, missing_at"

    const val BOOK_COLUMN_COUNT = 24

    // ---- books: reads ----
    const val SELECT_BOOK_BY_ID = "SELECT $BOOK_COLUMNS FROM books WHERE id = ?"
    const val SELECT_BOOK_BY_PATH = "SELECT $BOOK_COLUMNS FROM books WHERE path = ?"
    const val SELECT_LAST_OPENED =
        "SELECT $BOOK_COLUMNS FROM books WHERE last_read_at > 0 AND trashed = 0 ORDER BY last_read_at DESC, id DESC LIMIT 1"
    /**
     * Every book + its meta_locked flag (column index [BOOK_COLUMN_COUNT]) and review_at (v3, index
     * [BOOK_COLUMN_COUNT] + 1); used by the backup.
     */
    const val SELECT_ALL_BOOKS_FOR_BACKUP = "SELECT $BOOK_COLUMNS, meta_locked, review_at FROM books ORDER BY id"
    /** Minimal state of every book for scan / import matching. */
    const val SELECT_SCAN_STATE =
        "SELECT id, path, size, mtime, trashed, file_name, last_read_at, missing_at FROM books"
    const val SELECT_TRASHED_IDS = "SELECT id, path FROM books WHERE trashed = 1"
    /**
     * "다음 권" by series (T1-2): the next indexes of a series, nearest first (the first whose file exists wins).
     * Args: series, series_index (the stored Float widened to Double, as text), id of the current book.
     */
    const val SELECT_SERIES_NEXT = "SELECT $BOOK_COLUMNS FROM books WHERE series = ? AND series_index > ? AND id <> ? " +
        "AND trashed = 0 ORDER BY series_index, id LIMIT 8"
    const val SELECT_PATH_BY_ID = "SELECT path FROM books WHERE id = ?"
    const val SELECT_ID_BY_PATH = "SELECT id FROM books WHERE path = ?"
    /**
     * Entries a newly opened file may have been moved from (same name and size; not trashed, or trashed by the
     * scanner because the file vanished), most recently read first. Args: file_name, size.
     */
    const val SELECT_MOVE_CANDIDATES = "SELECT id, path, mtime FROM books WHERE file_name = ? AND size = ? " +
        "AND (trashed = 0 OR missing_at > 0) ORDER BY last_read_at DESC, id DESC"
    /**
     * Books carrying anything the user made (reading history, flags, edits, review, bookmarks, quotes,
     * collections): the scanner never drops these just because their folder was excluded.
     */
    const val SELECT_IDS_WITH_USER_DATA = "SELECT id FROM books WHERE last_read_at > 0 OR favorite = 1 OR to_read = 1 " +
        "OR have_read = 1 OR review <> '' OR encoding <> '' OR meta_locked = 1 OR reading_seconds > 0 " +
        "UNION SELECT book_id FROM bookmarks UNION SELECT book_id FROM quotes UNION SELECT book_id FROM book_collections " +
        "UNION SELECT book_id FROM book_prefs UNION SELECT book_id FROM reading_log UNION SELECT book_id FROM lookups"
    /**
     * Books with notes the hub shows (quotes, bookmarks, a review, lookups): a scan moves such a book to the trash
     * as missing ([SET_MISSING]) instead of dropping it when its file vanishes.
     */
    const val SELECT_IDS_WITH_NOTES = "SELECT book_id FROM quotes UNION SELECT book_id FROM bookmarks " +
        "UNION SELECT id FROM books WHERE review <> '' UNION SELECT book_id FROM lookups"
    const val COUNT_LIBRARY = "SELECT COUNT(*) FROM books WHERE trashed = 0"

    // ---- books: writes ----
    /** Args: path, file_name, folder, title, author, series, series_index, format, size, mtime, added_at, language. */
    const val INSERT_BOOK = "INSERT OR IGNORE INTO books(path, file_name, folder, title, author, series, series_index, " +
        "format, size, mtime, added_at, language) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    /** A moved file (scan). OR IGNORE: a concurrent insert of the new path wins. Args: path, id. */
    const val UPDATE_BOOK_PATH = "UPDATE OR IGNORE books SET path = ? WHERE id = ?"
    /** Args: file_name, folder, format, size, mtime, id. */
    const val UPDATE_BOOK_FILE = "UPDATE books SET file_name = ?, folder = ?, format = ?, size = ?, mtime = ? WHERE id = ?"
    /** Metadata refresh from the file (skipped once the user edited it). Args: title, author, series, series_index, language, id. */
    const val UPDATE_BOOK_META =
        "UPDATE books SET title = ?, author = ?, series = ?, series_index = ?, language = ? WHERE id = ? AND meta_locked = 0"
    /** User edit. Args: title, author, series, series_index, id. */
    const val UPDATE_BOOK_META_USER =
        "UPDATE books SET title = ?, author = ?, series = ?, series_index = ?, meta_locked = 1 WHERE id = ?"
    /** Args: pos_section, pos_offset, progress, last_read_at, id. */
    const val UPDATE_POSITION =
        "UPDATE books SET pos_section = ?, pos_offset = ?, progress = ?, last_read_at = ? WHERE id = ?"
    /** Args: seconds, id. */
    const val ADD_READING_TIME = "UPDATE books SET reading_seconds = reading_seconds + ? WHERE id = ?"
    const val SET_FAVORITE = "UPDATE books SET favorite = ? WHERE id = ?"
    /** Clears have_read too: run [CLEAR_FINISHED_AT] and [PRUNE_BOOK_PREFS] with it. */
    const val SET_TO_READ_ON = "UPDATE books SET to_read = 1, have_read = 0 WHERE id = ?"
    const val SET_TO_READ_OFF = "UPDATE books SET to_read = 0 WHERE id = ?"
    const val SET_HAVE_READ_ON = "UPDATE books SET have_read = 1, to_read = 0 WHERE id = ?"
    /** Run [CLEAR_FINISHED_AT] and [PRUNE_BOOK_PREFS] with it (a finish time belongs to a finished book only). */
    const val SET_HAVE_READ_OFF = "UPDATE books SET have_read = 0 WHERE id = ?"
    const val SET_TRASHED = "UPDATE books SET trashed = ? WHERE id = ?"
    /**
     * The user's own 휴지통: never revivable by the scanner, so a stale `missing_at` (an R2 build's 복원, a restored
     * backup) is cleared with it. Args: id.
     */
    const val TRASH = "UPDATE books SET trashed = 1, missing_at = 0 WHERE id = ?"
    /** 복원 (out of the trash): a scanner-trashed book is no longer missing either. Args: id. */
    const val UNTRASH = "UPDATE books SET trashed = 0, missing_at = 0 WHERE id = ?"
    /**
     * The scanner found the file gone and the book has notes: trash it as missing. Never a book the user put in
     * 휴지통 themselves (`trashed = 0`): that one must never become revivable. Args: missing_at, id.
     */
    const val SET_MISSING = "UPDATE books SET trashed = 1, missing_at = ? WHERE id = ? AND trashed = 0"
    /** The file of a missing book is back (same path, or moved): out of the trash again. Args: id. */
    const val CLEAR_MISSING = "UPDATE books SET trashed = 0, missing_at = 0 WHERE id = ? AND missing_at > 0"
    /** Args: review, review_at (0 when blank), id. */
    const val SET_REVIEW = "UPDATE books SET review = ?, review_at = ? WHERE id = ?"
    /**
     * Backup restore of a review that is newer than the device's (N §5.6 newer-wins by review_at; the caller decides).
     * Args: review, review_at, id.
     */
    const val RESTORE_REVIEW = "UPDATE books SET review = ?, review_at = ? WHERE id = ?"
    /** 리뷰 지우기 (hub batch). Args: id. */
    const val CLEAR_REVIEW = "UPDATE books SET review = '', review_at = 0 WHERE id = ?"
    const val SET_ENCODING = "UPDATE books SET encoding = ? WHERE id = ?"
    const val RESET_PROGRESS = "UPDATE books SET last_read_at = 0, pos_section = 0, pos_offset = 0, progress = 0, " +
        "have_read = 0, to_read = 0, reading_seconds = 0 WHERE id = ?"
    const val DELETE_BOOK = "DELETE FROM books WHERE id = ?"
    /**
     * Backup restore. Args: favorite, to_read, have_read, trashed, review (blank keeps the current one), encoding,
     * reading_seconds (the larger value wins), added_at (the earlier value wins), id.
     */
    const val RESTORE_FLAGS = "UPDATE books SET favorite = ?, to_read = ?, have_read = ?, trashed = ?, " +
        "review = COALESCE(NULLIF(?, ''), review), encoding = ?, reading_seconds = MAX(reading_seconds, ?), " +
        "added_at = MIN(added_at, ?) WHERE id = ?"

    // ---- bookmarks ----
    /** Columns mapped by [BookRows.bookmark] (place columns 7..9). */
    private const val BOOKMARK_COLUMNS = "id, book_id, section, char_offset, snippet, created_at, note, chapter, frac, sig"
    const val SELECT_BOOKMARKS = "SELECT $BOOKMARK_COLUMNS FROM bookmarks WHERE book_id = ? ORDER BY section, char_offset, id"
    const val SELECT_ALL_BOOKMARKS = "SELECT $BOOKMARK_COLUMNS FROM bookmarks ORDER BY book_id, section, char_offset, id"
    /**
     * Args: book_id, section, char_offset, snippet, note, created_at, chapter, frac, sig. The place arguments may be
     * left unbound (NULL): they then store the "unknown" defaults ('' / -1 / ''), so a caller binding only the first
     * six (an R2-era restore) still inserts.
     */
    const val INSERT_BOOKMARK = "INSERT INTO bookmarks(book_id, section, char_offset, snippet, note, created_at, " +
        "chapter, frac, sig) VALUES (?, ?, ?, ?, ?, ?, IFNULL(?, ''), IFNULL(?, -1), IFNULL(?, ''))"
    /** Backfill of a legacy bookmark's place (only while unknown). Args: chapter, frac, id. */
    const val UPDATE_BOOKMARK_PLACE = "UPDATE bookmarks SET chapter = ?, frac = ? WHERE id = ? AND frac < 0"
    const val DELETE_BOOKMARK = "DELETE FROM bookmarks WHERE id = ?"
    const val UPDATE_BOOKMARK_NOTE = "UPDATE bookmarks SET note = ? WHERE id = ?"
    const val DELETE_BOOKMARKS_OF_BOOK = "DELETE FROM bookmarks WHERE book_id = ?"

    // ---- quotes ----
    /** Columns mapped by [BookRows.quote] (style 8, place 9..11). */
    private const val QUOTE_COLUMNS =
        "id, book_id, section, start_offset, end_offset, quote_text, note, created_at, style, chapter, frac, sig"
    const val SELECT_QUOTES = "SELECT $QUOTE_COLUMNS FROM quotes WHERE book_id = ? ORDER BY section, start_offset, end_offset, id"
    const val SELECT_ALL_QUOTES = "SELECT $QUOTE_COLUMNS FROM quotes ORDER BY book_id, section, start_offset, end_offset, id"
    /**
     * Args: book_id, section, start_offset, end_offset, quote_text, note, created_at, style, chapter, frac, sig. As with
     * [INSERT_BOOKMARK], unbound (NULL) style / place arguments store the defaults (0 / '' / -1 / '').
     */
    const val INSERT_QUOTE = "INSERT INTO quotes(book_id, section, start_offset, end_offset, quote_text, note, created_at, " +
        "style, chapter, frac, sig) VALUES (?, ?, ?, ?, ?, ?, ?, IFNULL(?, 0), IFNULL(?, ''), IFNULL(?, -1), IFNULL(?, ''))"
    const val DELETE_QUOTE = "DELETE FROM quotes WHERE id = ?"
    const val UPDATE_QUOTE_NOTE = "UPDATE quotes SET note = ? WHERE id = ?"
    /** Args: style (already clamped to 0..DataLimits.QUOTE_STYLE_MAX), id. */
    const val UPDATE_QUOTE_STYLE = "UPDATE quotes SET style = ? WHERE id = ?"
    /** Backfill of a legacy quote's place (only while unknown). Args: chapter, frac, id. */
    const val UPDATE_QUOTE_PLACE = "UPDATE quotes SET chapter = ?, frac = ? WHERE id = ? AND frac < 0"
    const val DELETE_QUOTES_OF_BOOK = "DELETE FROM quotes WHERE book_id = ?"
    const val DELETE_LOOKUPS_OF_BOOK = "DELETE FROM lookups WHERE book_id = ?"

    // ---- collections ----
    /** id, name, created_at, number of (non-trashed) books. */
    const val SELECT_COLLECTIONS = "SELECT c.id, c.name, c.created_at, COUNT(b.id) FROM collections c " +
        "LEFT JOIN book_collections bc ON bc.collection_id = c.id " +
        "LEFT JOIN books b ON b.id = bc.book_id AND b.trashed = 0 " +
        "GROUP BY c.id, c.name, c.created_at"
    const val SELECT_COLLECTION_BY_NAME = "SELECT id, name, created_at FROM collections WHERE name = ? COLLATE NOCASE"
    const val SELECT_COLLECTION_BY_ID = "SELECT id, name, created_at FROM collections WHERE id = ?"
    const val SELECT_COLLECTION_NAMES = "SELECT name FROM collections ORDER BY id"
    /** Args: name, created_at. */
    const val INSERT_COLLECTION = "INSERT INTO collections(name, created_at) VALUES (?, ?)"
    const val RENAME_COLLECTION = "UPDATE collections SET name = ? WHERE id = ?"
    const val DELETE_COLLECTION = "DELETE FROM collections WHERE id = ?"
    const val DELETE_MEMBERSHIPS_OF_COLLECTION = "DELETE FROM book_collections WHERE collection_id = ?"
    const val DELETE_MEMBERSHIPS_OF_BOOK = "DELETE FROM book_collections WHERE book_id = ?"
    const val SELECT_COLLECTIONS_OF_BOOK = "SELECT collection_id FROM book_collections WHERE book_id = ?"
    /** book_id, collection name. */
    const val SELECT_ALL_MEMBERSHIPS = "SELECT bc.book_id, c.name FROM book_collections bc " +
        "JOIN collections c ON c.id = bc.collection_id ORDER BY bc.book_id, c.name"
    /**
     * Args: book_id, collection_id. Only when both exist, so a racing delete can't leave a dangling membership
     * (which would list the book on the collections shelf without any collection).
     */
    const val INSERT_MEMBERSHIP = "INSERT OR IGNORE INTO book_collections(book_id, collection_id) " +
        "SELECT b.id, c.id FROM books b, collections c WHERE b.id = ? AND c.id = ?"
    const val DELETE_MEMBERSHIP = "DELETE FROM book_collections WHERE book_id = ? AND collection_id = ?"

    // ---- page counts ----
    const val SELECT_PAGE_COUNTS = "SELECT counts, updated_at FROM page_counts WHERE book_id = ? AND layout_key = ?"
    /** Args: updated_at, book_id, layout_key. */
    const val TOUCH_PAGE_COUNTS = "UPDATE page_counts SET updated_at = ? WHERE book_id = ? AND layout_key = ?"
    /** Args: book_id, layout_key, counts, updated_at. */
    const val REPLACE_PAGE_COUNTS =
        "INSERT OR REPLACE INTO page_counts(book_id, layout_key, counts, updated_at) VALUES (?, ?, ?, ?)"
    /** Keeps the [MAX_PAGE_COUNT_KEYS] newest layout keys of a book. Args: book_id, book_id. */
    const val PRUNE_PAGE_COUNTS = "DELETE FROM page_counts WHERE book_id = ? AND layout_key NOT IN (" +
        "SELECT layout_key FROM page_counts WHERE book_id = ? ORDER BY updated_at DESC LIMIT 3)"
    const val MAX_PAGE_COUNT_KEYS = 3
    const val DELETE_PAGE_COUNTS_OF_BOOK = "DELETE FROM page_counts WHERE book_id = ?"

    // ---- reading log (v2, T1-6): one row per (day, book), day = local yyyymmdd ----
    /** Upsert, step 1 (SQLite 3.18 has no UPSERT). Args: seconds, pages, chars, day, book_id. */
    const val LOG_ADD = "UPDATE reading_log SET seconds = seconds + ?, pages = pages + ?, chars = chars + ? " +
        "WHERE day = ? AND book_id = ?"
    /**
     * Upsert, step 2, when [LOG_ADD] or [LOG_RESTORE] changed no row. Only for a book that still exists, so a write
     * racing the book's removal leaves no orphan row. Args: day, seconds, pages, chars, book_id.
     */
    const val LOG_INSERT = "INSERT INTO reading_log(day, book_id, seconds, pages, chars) " +
        "SELECT ?, id, ?, ?, ? FROM books WHERE id = ?"
    /** Backup restore: the larger value of each column wins, so restoring twice adds nothing. Args as [LOG_ADD]. */
    const val LOG_RESTORE = "UPDATE reading_log SET seconds = MAX(seconds, ?), pages = MAX(pages, ?), " +
        "chars = MAX(chars, ?) WHERE day = ? AND book_id = ?"
    /** seconds, pages, chars, number of days. Args: from day, to day (inclusive). */
    const val SELECT_LOG_TOTALS = "SELECT IFNULL(SUM(seconds), 0), IFNULL(SUM(pages), 0), IFNULL(SUM(chars), 0), " +
        "COUNT(DISTINCT day) FROM reading_log WHERE day BETWEEN ? AND ?"
    /** seconds, chars of one book. Args: book_id, from day, to day. */
    const val SELECT_LOG_BOOK_TOTALS = "SELECT IFNULL(SUM(seconds), 0), IFNULL(SUM(chars), 0) FROM reading_log " +
        "WHERE book_id = ? AND day BETWEEN ? AND ?"
    /** day, seconds, pages, chars, ascending. Args: from day, to day. */
    const val SELECT_LOG_DAYS = "SELECT day, SUM(seconds), SUM(pages), SUM(chars) FROM reading_log " +
        "WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day"
    /** book_id, seconds, pages, chars; most seconds first, books still in the library only. Args: from day, to day. */
    const val SELECT_LOG_PER_BOOK = "SELECT l.book_id, SUM(l.seconds), SUM(l.pages), SUM(l.chars) FROM reading_log l " +
        "JOIN books b ON b.id = l.book_id WHERE l.day BETWEEN ? AND ? GROUP BY l.book_id " +
        "ORDER BY SUM(l.seconds) DESC, l.book_id"
    /** Every row, for the backup: book_id, day, seconds, pages, chars. */
    const val SELECT_ALL_LOG = "SELECT book_id, day, seconds, pages, chars FROM reading_log ORDER BY book_id, day"
    const val DELETE_LOG_OF_BOOK = "DELETE FROM reading_log WHERE book_id = ?"

    // ---- book prefs (v2, T1-9 / T1-2): at most one row per book, created by the first write ----
    /** The open path's one extra read (primary key). */
    const val SELECT_TXT_OVERRIDE = "SELECT txt_override FROM book_prefs WHERE book_id = ?"
    /** A finish time counts only while the book is marked have_read (guards rows older builds left behind). */
    const val SELECT_FINISHED_AT = "SELECT p.finished_at FROM book_prefs p JOIN books b ON b.id = p.book_id " +
        "WHERE p.book_id = ? AND b.have_read = 1"
    /** book_id, finished_at; newest first. Args: from ms (inclusive), to ms (exclusive). */
    const val SELECT_FINISHED_BETWEEN = "SELECT p.book_id, p.finished_at FROM book_prefs p " +
        "JOIN books b ON b.id = p.book_id WHERE p.finished_at >= ? AND p.finished_at < ? AND p.finished_at > 0 " +
        "AND b.trashed = 0 AND b.have_read = 1 ORDER BY p.finished_at DESC, p.book_id DESC"
    /** The book's return history (U §3.3; ReturnHistoryCodec text), NULL = none. */
    const val SELECT_RETURN_MARK = "SELECT return_mark FROM book_prefs WHERE book_id = ?"
    /** txt_override, finished_at, episode_label of one book. */
    const val SELECT_BOOK_PREFS = "SELECT txt_override, finished_at, episode_label FROM book_prefs WHERE book_id = ?"
    /** Every row, for the backup: book_id, txt_override, finished_at, episode_label, return_mark. */
    const val SELECT_ALL_BOOK_PREFS = "SELECT book_id, txt_override, finished_at, episode_label, return_mark " +
        "FROM book_prefs ORDER BY book_id"
    /** One-column writes: UPDATE first; when no row changed, the matching INSERT (for an existing book only). */
    const val SET_PREFS_TXT = "UPDATE book_prefs SET txt_override = ? WHERE book_id = ?"
    const val INSERT_PREFS_TXT = "INSERT INTO book_prefs(book_id, txt_override) SELECT id, ? FROM books WHERE id = ?"
    const val SET_PREFS_FINISHED = "UPDATE book_prefs SET finished_at = ? WHERE book_id = ?"
    const val INSERT_PREFS_FINISHED = "INSERT INTO book_prefs(book_id, finished_at) SELECT id, ? FROM books WHERE id = ?"
    const val SET_PREFS_EPISODE = "UPDATE book_prefs SET episode_label = ? WHERE book_id = ?"
    const val INSERT_PREFS_EPISODE = "INSERT INTO book_prefs(book_id, episode_label) SELECT id, ? FROM books WHERE id = ?"
    const val SET_PREFS_RETURN = "UPDATE book_prefs SET return_mark = ? WHERE book_id = ?"
    const val INSERT_PREFS_RETURN = "INSERT INTO book_prefs(book_id, return_mark) SELECT id, ? FROM books WHERE id = ?"
    /** "읽은 기록 초기화": the return history goes with the position (U §3.3). Prune the row afterwards. */
    const val CLEAR_RETURN_MARK = "UPDATE book_prefs SET return_mark = NULL WHERE book_id = ?"
    /** Backup restore of a whole row. Args: txt_override, finished_at, episode_label, book_id. */
    const val SET_PREFS_ROW = "UPDATE book_prefs SET txt_override = ?, finished_at = ?, episode_label = ? WHERE book_id = ?"
    const val INSERT_PREFS_ROW = "INSERT INTO book_prefs(book_id, txt_override, finished_at, episode_label) " +
        "SELECT id, ?, ?, ? FROM books WHERE id = ?"
    const val CLEAR_FINISHED_AT = "UPDATE book_prefs SET finished_at = 0 WHERE book_id = ?"
    /** Drops a row that no longer holds anything (keeps the table as small as the set of books with prefs). */
    const val PRUNE_BOOK_PREFS = "DELETE FROM book_prefs WHERE book_id = ? AND txt_override IS NULL AND finished_at = 0 " +
        "AND episode_label IS NULL AND return_mark IS NULL"
    const val DELETE_BOOK_PREFS_OF_BOOK = "DELETE FROM book_prefs WHERE book_id = ?"

    // ---- ignored (removed-but-kept) files ----
    const val SELECT_IGNORED = "SELECT path FROM ignored"
    /** Args: path, removed_at. */
    const val INSERT_IGNORED = "INSERT OR REPLACE INTO ignored(path, removed_at) VALUES (?, ?)"
    const val DELETE_IGNORED = "DELETE FROM ignored WHERE path = ?"

    // ---- pragmas ----
    const val PRAGMA_SYNCHRONOUS = "PRAGMA synchronous=NORMAL"

    // ---- shelf queries ----

    private const val NOT_TRASHED = "trashed = 0"
    private const val LIKE_ESCAPE = '\\'
    private const val MAX_TOKENS = 8
    /** Longer words are cut (SQLite rejects LIKE patterns over 50 000 bytes; no title needs more). */
    const val MAX_TOKEN_CHARS = 100

    /** Escapes `%`, `_` and the escape char itself for `LIKE ? ESCAPE '\'`. */
    fun escapeLike(s: String): String {
        if (s.indexOf('%') < 0 && s.indexOf('_') < 0 && s.indexOf(LIKE_ESCAPE) < 0) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) sb.append(LIKE_ESCAPE)
            sb.append(c)
        }
        return sb.toString()
    }

    /** Whitespace-separated search words (NFC, distinct, at most [MAX_TOKENS] of at most [MAX_TOKEN_CHARS]). */
    fun searchTokens(query: String): List<String> {
        val q = nfc(query).trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<String>(4)
        for (w in q.split(WHITESPACE)) {
            val t = MetaInfo.truncate(w, MAX_TOKEN_CHARS)
            if (t.isEmpty() || t in out) continue
            out += t
            if (out.size == MAX_TOKENS) break
        }
        return out
    }

    /** SQL condition for a shelf (without group / search filters). */
    fun shelfWhere(shelf: Shelf): String = when (shelf) {
        Shelf.READING_NOW -> "last_read_at > 0 AND have_read = 0 AND trashed = 0"
        Shelf.ALL, Shelf.AUTHORS, Shelf.FORMATS, Shelf.FOLDERS -> NOT_TRASHED
        Shelf.FAVORITES -> "favorite = 1 AND trashed = 0"
        Shelf.TO_READ -> "to_read = 1 AND trashed = 0"
        Shelf.HAVE_READ -> "have_read = 1 AND trashed = 0"
        Shelf.SERIES -> "series IS NOT NULL AND series <> '' AND trashed = 0"
        Shelf.COLLECTIONS -> "id IN (SELECT book_id FROM book_collections) AND trashed = 0"
        Shelf.DOWNLOADS -> "(path LIKE '%/Download/%' OR path LIKE '%/Downloads/%') AND trashed = 0"
        Shelf.TRASH -> "trashed = 1"
    }

    /** Column condition narrowing a grouped shelf to one group (null for shelves without groups). */
    fun groupWhere(shelf: Shelf): String? = when (shelf) {
        Shelf.AUTHORS -> "author = ?"
        Shelf.SERIES -> "series = ?"
        Shelf.COLLECTIONS -> "id IN (SELECT book_id FROM book_collections WHERE collection_id = ?)"
        Shelf.FORMATS -> "format = ?"
        Shelf.FOLDERS -> "folder = ?"
        else -> null
    }

    /** The bound value for [groupWhere]: formats accept their name or label in any case. */
    fun groupArg(shelf: Shelf, group: String): String = when (shelf) {
        Shelf.FORMATS -> BookFormat.entries.firstOrNull {
            it.name.equals(group.trim(), ignoreCase = true) || it.label.equals(group.trim(), ignoreCase = true)
        }?.name ?: group
        Shelf.COLLECTIONS -> group.trim()
        else -> group
    }

    /** ORDER BY clause; READING_NOW is always most-recent-first. */
    fun orderBy(shelf: Shelf, sort: LibrarySort): String {
        val s = if (shelf == Shelf.READING_NOW) LibrarySort.RECENT else sort
        return when (s) {
            LibrarySort.RECENT -> "last_read_at DESC, added_at DESC, id DESC"
            LibrarySort.TITLE -> "title COLLATE NOCASE, id"
            LibrarySort.AUTHOR -> "author = '', author COLLATE NOCASE, title COLLATE NOCASE, id"
            LibrarySort.ADDED -> "added_at DESC, id DESC"
            LibrarySort.SIZE -> "size DESC, title COLLATE NOCASE, id"
            LibrarySort.PROGRESS -> "progress DESC, last_read_at DESC, id DESC"
        }
    }

    /** The books SELECT for a shelf / group / search query. */
    fun booksQuery(q: LibraryQuery, sort: LibrarySort): SqlQuery {
        val args = ArrayList<String>()
        val sb = StringBuilder(256)
        sb.append("SELECT ").append(BOOK_COLUMNS).append(" FROM books WHERE ").append(shelfWhere(q.shelf))
        val group = q.group
        val gw = groupWhere(q.shelf)
        if (group != null && gw != null) {
            sb.append(" AND ").append(gw)
            args += groupArg(q.shelf, group)
        }
        for (t in searchTokens(q.query)) {
            val pattern = "%" + escapeLike(t) + "%"
            sb.append(" AND (title LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\' OR file_name LIKE ? ESCAPE '\\')")
            args += pattern
            args += pattern
            args += pattern
        }
        sb.append(" ORDER BY ").append(orderBy(q.shelf, sort))
        return SqlQuery(sb.toString(), args.toTypedArray())
    }

    /** `SELECT COUNT(*)` for the books a [LibraryQuery] lists. */
    fun countQuery(q: LibraryQuery): SqlQuery {
        val full = booksQuery(q, LibrarySort.RECENT)
        val where = full.sql.substring(full.sql.indexOf(" FROM books WHERE "), full.sql.lastIndexOf(" ORDER BY "))
        return SqlQuery("SELECT COUNT(*)$where", full.args)
    }

    /** Shelves in the column order of [shelfCountsQuery]. */
    val COUNTED_SHELVES: List<Shelf> = Shelf.entries.toList()

    /**
     * One row with a count per shelf ([COUNTED_SHELVES] order): books for plain shelves, groups for grouped
     * ones (matching [groupsQuery]: unknown author is a group, collections include empty ones).
     */
    fun shelfCountsQuery(): String {
        val cols = COUNTED_SHELVES.map { s ->
            when (s) {
                Shelf.AUTHORS -> "COUNT(DISTINCT CASE WHEN trashed = 0 THEN author END)"
                Shelf.SERIES -> "COUNT(DISTINCT CASE WHEN ${shelfWhere(Shelf.SERIES)} THEN series END)"
                Shelf.FORMATS -> "COUNT(DISTINCT CASE WHEN trashed = 0 THEN format END)"
                Shelf.FOLDERS -> "COUNT(DISTINCT CASE WHEN trashed = 0 THEN folder END)"
                Shelf.COLLECTIONS -> "(SELECT COUNT(*) FROM collections)"
                else -> "IFNULL(SUM(CASE WHEN ${shelfWhere(s)} THEN 1 ELSE 0 END), 0)"
            }
        }
        return "SELECT " + cols.joinToString(", ") + " FROM books"
    }

    const val SELECT_COLLECTION_MEMBER_IDS = "SELECT DISTINCT book_id FROM book_collections"

    /**
     * Group rows of a grouped shelf: columns (key, label-or-null, count). Null for shelves without groups.
     * Final ordering is done in Kotlin (natural order).
     */
    fun groupsQuery(shelf: Shelf): String? = when (shelf) {
        Shelf.AUTHORS -> "SELECT author, NULL, COUNT(*) FROM books WHERE trashed = 0 GROUP BY author"
        Shelf.SERIES ->
            "SELECT series, NULL, COUNT(*) FROM books WHERE series IS NOT NULL AND series <> '' AND trashed = 0 GROUP BY series"
        Shelf.FORMATS -> "SELECT format, NULL, COUNT(*) FROM books WHERE trashed = 0 GROUP BY format"
        Shelf.FOLDERS -> "SELECT folder, NULL, COUNT(*) FROM books WHERE trashed = 0 GROUP BY folder"
        Shelf.COLLECTIONS -> "SELECT c.id, c.name, COUNT(b.id) FROM collections c " +
            "LEFT JOIN book_collections bc ON bc.collection_id = c.id " +
            "LEFT JOIN books b ON b.id = bc.book_id AND b.trashed = 0 " +
            "GROUP BY c.id, c.name"
        else -> null
    }

    const val UNKNOWN_AUTHOR = "작가 미상"

    /** Builds a [ShelfGroup] from a [groupsQuery] row. */
    fun groupRow(shelf: Shelf, key: String?, name: String?, count: Int): ShelfGroup {
        val k = key ?: ""
        val label = when (shelf) {
            Shelf.AUTHORS -> k.ifBlank { UNKNOWN_AUTHOR }
            Shelf.FORMATS -> BookFormat.entries.firstOrNull { it.name == k }?.label ?: k
            Shelf.COLLECTIONS -> name ?: k
            else -> k
        }
        return ShelfGroup(k, label, count)
    }

    /** Orders groups: natural order by label; unknown author last. */
    fun sortGroups(shelf: Shelf, groups: List<ShelfGroup>): List<ShelfGroup> {
        val byLabel = Comparator<ShelfGroup> { a, b -> NaturalOrder.compare(a.label, b.label) }
        return if (shelf == Shelf.AUTHORS) {
            groups.sortedWith(compareBy<ShelfGroup> { it.key.isBlank() }.then(byLabel))
        } else {
            groups.sortedWith(byLabel)
        }
    }

    /**
     * Final in-memory ordering on top of SQL's: natural title order ("2권" before "10권") for TITLE and
     * AUTHOR sorts, series index order inside a series group. Stable for everything else.
     */
    fun sortBooks(books: List<Book>, q: LibraryQuery, sort: LibrarySort): List<Book> {
        if (books.size < 2 || q.shelf == Shelf.READING_NOW) return books
        val byTitle = Comparator<Book> { a, b -> NaturalOrder.compare(a.title, b.title) }
        return when {
            q.shelf == Shelf.SERIES && q.group != null && (sort == LibrarySort.TITLE || sort == LibrarySort.AUTHOR) ->
                books.sortedWith(
                    compareBy<Book> { it.seriesIndex == null }
                        .thenBy { it.seriesIndex ?: 0f }
                        .then(byTitle),
                )
            sort == LibrarySort.TITLE -> books.sortedWith(byTitle)
            sort == LibrarySort.AUTHOR -> books.sortedWith(
                compareBy<Book> { it.author.isBlank() }
                    .then { a, b -> NaturalOrder.compare(a.author, b.author) }
                    .then(byTitle),
            )
            else -> books
        }
    }

    private val WHITESPACE = Regex("\\s+")

    fun nfc(s: String): String =
        if (Normalizer.isNormalized(s, Normalizer.Form.NFC)) s else Normalizer.normalize(s, Normalizer.Form.NFC)
}
