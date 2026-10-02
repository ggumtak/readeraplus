package com.ggumtak.readeraplus.data

/**
 * SQLite schema of `library.db` (pure constants so the statements can be checked off-device).
 *
 * Only SQL understood by SQLite 3.18 (Android 8, minSdk 26) is used: no UPSERT, no window functions,
 * no RETURNING, no NULLS FIRST/LAST. Column names avoid SQL keywords (`offset`, `end`, `text`).
 *
 * Versions (one bump per release at most):
 * - v1: books, bookmarks, quotes, collections, book_collections, page_counts, ignored.
 * - v2 (R2): `reading_log` (T1-6), `book_prefs` (T1-9 / T1-2) and the `quotes.style` column (T2-3).
 * - v3 (R3): note places, lookups, missing/review times and the return mark.
 *
 * A fresh database runs [CREATE_ALL] (which already has every column). An upgrade runs [CREATE_ALL] too
 * (`IF NOT EXISTS`: only the missing tables / indexes are made; `CREATE_ALL` never adds a column to an existing
 * table), then [upgradeStatements] for the columns newer versions added. Nothing is ever dropped or rewritten.
 */
internal object LibrarySchema {
    const val DB_NAME = "library.db"
    const val DB_VERSION = 3

    const val CREATE_BOOKS = "CREATE TABLE IF NOT EXISTS books(" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
        "path TEXT NOT NULL UNIQUE," +
        "file_name TEXT NOT NULL DEFAULT ''," +
        "folder TEXT NOT NULL DEFAULT ''," +
        "title TEXT NOT NULL DEFAULT ''," +
        "author TEXT NOT NULL DEFAULT ''," +
        "series TEXT," +
        "series_index REAL," +
        "format TEXT NOT NULL DEFAULT 'TXT'," +
        "size INTEGER NOT NULL DEFAULT 0," +
        "mtime INTEGER NOT NULL DEFAULT 0," +
        "added_at INTEGER NOT NULL DEFAULT 0," +
        "last_read_at INTEGER NOT NULL DEFAULT 0," +
        "pos_section INTEGER NOT NULL DEFAULT 0," +
        "pos_offset INTEGER NOT NULL DEFAULT 0," +
        "progress REAL NOT NULL DEFAULT 0," +
        "favorite INTEGER NOT NULL DEFAULT 0," +
        "to_read INTEGER NOT NULL DEFAULT 0," +
        "have_read INTEGER NOT NULL DEFAULT 0," +
        "trashed INTEGER NOT NULL DEFAULT 0," +
        "review TEXT NOT NULL DEFAULT ''," +
        "encoding TEXT NOT NULL DEFAULT ''," +
        "language TEXT," +
        "reading_seconds INTEGER NOT NULL DEFAULT 0," +
        // 1 once the user edited title/author/series: file refreshes then keep them.
        "meta_locked INTEGER NOT NULL DEFAULT 0," +
        "review_at INTEGER NOT NULL DEFAULT 0,missing_at INTEGER NOT NULL DEFAULT 0)"

    const val CREATE_BOOKMARKS = "CREATE TABLE IF NOT EXISTS bookmarks(" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
        "book_id INTEGER NOT NULL," +
        "section INTEGER NOT NULL DEFAULT 0," +
        "char_offset INTEGER NOT NULL DEFAULT 0," +
        "snippet TEXT NOT NULL DEFAULT ''," +
        "note TEXT NOT NULL DEFAULT ''," +
        "created_at INTEGER NOT NULL DEFAULT 0,chapter TEXT NOT NULL DEFAULT '',frac REAL NOT NULL DEFAULT -1,sig TEXT NOT NULL DEFAULT '')"

    const val CREATE_QUOTES = "CREATE TABLE IF NOT EXISTS quotes(" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
        "book_id INTEGER NOT NULL," +
        "section INTEGER NOT NULL DEFAULT 0," +
        "start_offset INTEGER NOT NULL DEFAULT 0," +
        "end_offset INTEGER NOT NULL DEFAULT 0," +
        "quote_text TEXT NOT NULL DEFAULT ''," +
        "note TEXT NOT NULL DEFAULT ''," +
        "created_at INTEGER NOT NULL DEFAULT 0," +
        // v2: highlight look (0 = the default grey fill; T2-3 adds the others). Added to v1 files by ADD_QUOTE_STYLE.
        "style INTEGER NOT NULL DEFAULT 0,chapter TEXT NOT NULL DEFAULT '',frac REAL NOT NULL DEFAULT -1,sig TEXT NOT NULL DEFAULT '')"

    const val CREATE_COLLECTIONS = "CREATE TABLE IF NOT EXISTS collections(" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
        "name TEXT NOT NULL UNIQUE COLLATE NOCASE," +
        "created_at INTEGER NOT NULL DEFAULT 0)"

    const val CREATE_BOOK_COLLECTIONS = "CREATE TABLE IF NOT EXISTS book_collections(" +
        "book_id INTEGER NOT NULL," +
        "collection_id INTEGER NOT NULL," +
        "PRIMARY KEY(book_id, collection_id)) WITHOUT ROWID"

    const val CREATE_PAGE_COUNTS = "CREATE TABLE IF NOT EXISTS page_counts(" +
        "book_id INTEGER NOT NULL," +
        "layout_key TEXT NOT NULL," +
        "counts BLOB NOT NULL," +
        "updated_at INTEGER NOT NULL DEFAULT 0," +
        "PRIMARY KEY(book_id, layout_key))"

    /** Files the user removed from the library without deleting them: the scanner must not re-add them. */
    const val CREATE_IGNORED = "CREATE TABLE IF NOT EXISTS ignored(" +
        "path TEXT PRIMARY KEY NOT NULL," +
        "removed_at INTEGER NOT NULL DEFAULT 0)"

    /**
     * v2: reading time per local day and book (T1-6). [day] is the local date as yyyymmdd (20260930); one row per
     * (day, book), grown with an UPDATE-then-INSERT upsert (SQLite 3.18 has no UPSERT). Rows of a removed book are
     * deleted with it.
     */
    const val CREATE_READING_LOG = "CREATE TABLE IF NOT EXISTS reading_log(" +
        "day INTEGER NOT NULL," +
        "book_id INTEGER NOT NULL," +
        "seconds INTEGER NOT NULL DEFAULT 0," +
        "pages INTEGER NOT NULL DEFAULT 0," +
        "chars INTEGER NOT NULL DEFAULT 0," +
        "PRIMARY KEY(day, book_id)) WITHOUT ROWID"

    /**
     * v2: per-book preferences, at most one row per book (T1-9 / T1-2 / T2-13): the TXT options of this book only
     * (`txt_override`, JSON; NULL = the global defaults), when it was finished (`finished_at`, epoch millis; 0 =
     * not finished) and the file-name episode badge (`episode_label`, e.g. "123/540화"; NULL = none). Read on the
     * open path by primary key (one indexed read); deleted with the book.
     */
    const val CREATE_BOOK_PREFS = "CREATE TABLE IF NOT EXISTS book_prefs(" +
        "book_id INTEGER PRIMARY KEY," +
        "txt_override TEXT," +
        "finished_at INTEGER NOT NULL DEFAULT 0," +
        "episode_label TEXT,return_mark TEXT)"

    /** v2 column for databases created by v1 (a fresh v2 file has it from [CREATE_QUOTES]). */
    const val ADD_QUOTE_STYLE = "ALTER TABLE quotes ADD COLUMN style INTEGER NOT NULL DEFAULT 0"

    const val ADD_RETURN_MARK = "ALTER TABLE book_prefs ADD COLUMN return_mark TEXT"
    const val CREATE_LOOKUPS = "CREATE TABLE IF NOT EXISTS lookups(" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER NOT NULL," +
        "word TEXT NOT NULL DEFAULT '',word_key TEXT NOT NULL DEFAULT ''," +
        "section INTEGER NOT NULL DEFAULT 0,start_offset INTEGER NOT NULL DEFAULT 0,end_offset INTEGER NOT NULL DEFAULT 0," +
        "context TEXT NOT NULL DEFAULT '',chapter TEXT NOT NULL DEFAULT '',frac REAL NOT NULL DEFAULT -1," +
        "sig TEXT NOT NULL DEFAULT '',via INTEGER NOT NULL DEFAULT 0,app TEXT NOT NULL DEFAULT ''," +
        "note TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL DEFAULT 0)"

    /** Columns added after v1: (table, column, version that added it, ALTER statement). */
    private class AddedColumn(val table: String, val column: String, val version: Int, val sql: String)

    private val ADDED_COLUMNS = listOf(
        AddedColumn("quotes", "style", 2, ADD_QUOTE_STYLE),
        AddedColumn("quotes", "chapter", 3, "ALTER TABLE quotes ADD COLUMN chapter TEXT NOT NULL DEFAULT ''"),
        AddedColumn("quotes", "frac", 3, "ALTER TABLE quotes ADD COLUMN frac REAL NOT NULL DEFAULT -1"),
        AddedColumn("quotes", "sig", 3, "ALTER TABLE quotes ADD COLUMN sig TEXT NOT NULL DEFAULT ''"),
        AddedColumn("bookmarks", "chapter", 3, "ALTER TABLE bookmarks ADD COLUMN chapter TEXT NOT NULL DEFAULT ''"),
        AddedColumn("bookmarks", "frac", 3, "ALTER TABLE bookmarks ADD COLUMN frac REAL NOT NULL DEFAULT -1"),
        AddedColumn("bookmarks", "sig", 3, "ALTER TABLE bookmarks ADD COLUMN sig TEXT NOT NULL DEFAULT ''"),
        AddedColumn("books", "review_at", 3, "ALTER TABLE books ADD COLUMN review_at INTEGER NOT NULL DEFAULT 0"),
        AddedColumn("books", "missing_at", 3, "ALTER TABLE books ADD COLUMN missing_at INTEGER NOT NULL DEFAULT 0"),
        AddedColumn("book_prefs", "return_mark", 3, ADD_RETURN_MARK),

    )

    /** Tables whose columns [upgradeStatements] needs ([columnsOf] is asked only for these). */
    val UPGRADE_TABLES: List<String> get() = ADDED_COLUMNS.map { it.table }.distinct()

    /**
     * The ALTER statements an upgrade from [oldVersion] needs, to run AFTER [CREATE_ALL]. Guarded twice: only columns
     * added after [oldVersion], and only when [columnsOf] (the table's current column names, e.g. from
     * `PRAGMA table_info`) doesn't already list the column. The second guard matters for a file that went v2 → an
     * older build (onDowngrade keeps the data, SQLite then records version 1) → v2 again: adding an existing column
     * would fail the whole upgrade.
     */
    fun upgradeStatements(oldVersion: Int, columnsOf: (String) -> Set<String>): List<String> {
        val out = ArrayList<String>(ADDED_COLUMNS.size)
        for (c in ADDED_COLUMNS) {
            if (oldVersion >= c.version) continue
            val have = columnsOf(c.table)
            if (have.any { it.equals(c.column, ignoreCase = true) }) continue
            out += c.sql
        }
        return out
    }

    val CREATE_INDEXES = listOf(
        "CREATE INDEX IF NOT EXISTS books_last_read ON books(last_read_at)",
        "CREATE INDEX IF NOT EXISTS books_title ON books(title COLLATE NOCASE)",
        "CREATE INDEX IF NOT EXISTS books_author ON books(author)",
        "CREATE INDEX IF NOT EXISTS books_series ON books(series)",
        "CREATE INDEX IF NOT EXISTS books_folder ON books(folder)",
        "CREATE INDEX IF NOT EXISTS bookmarks_book ON bookmarks(book_id)",
        "CREATE INDEX IF NOT EXISTS quotes_book ON quotes(book_id)",
        "CREATE INDEX IF NOT EXISTS book_collections_coll ON book_collections(collection_id)",
        // v2: a book's log rows (removal, per-book speed); the primary key already serves day ranges.
        "CREATE INDEX IF NOT EXISTS reading_log_book ON reading_log(book_id)",
        "CREATE INDEX IF NOT EXISTS quotes_created ON quotes(created_at)",
        "CREATE INDEX IF NOT EXISTS bookmarks_created ON bookmarks(created_at)",
        "CREATE INDEX IF NOT EXISTS lookups_created ON lookups(created_at)",
        "CREATE INDEX IF NOT EXISTS lookups_book ON lookups(book_id)",
        "CREATE INDEX IF NOT EXISTS lookups_word ON lookups(word_key)",
        "CREATE INDEX IF NOT EXISTS quotes_memo ON quotes(created_at) WHERE note <> ''",
        "CREATE INDEX IF NOT EXISTS bookmarks_memo ON bookmarks(created_at) WHERE note <> ''",

    )

    /** Clean rows left by a downgraded build that did not know the new tables. */
    val UPGRADE_SWEEP = listOf(
        "DELETE FROM lookups WHERE book_id NOT IN (SELECT id FROM books)",
        "DELETE FROM book_prefs WHERE book_id NOT IN (SELECT id FROM books)",
        "DELETE FROM reading_log WHERE book_id NOT IN (SELECT id FROM books)",
    )

    /** Every statement needed to create a fresh database of [DB_VERSION], in order (all `IF NOT EXISTS`). */
    val CREATE_ALL: List<String> = listOf(
        CREATE_BOOKS, CREATE_BOOKMARKS, CREATE_QUOTES, CREATE_COLLECTIONS, CREATE_BOOK_COLLECTIONS,
        CREATE_PAGE_COUNTS, CREATE_IGNORED, CREATE_READING_LOG, CREATE_BOOK_PREFS, CREATE_LOOKUPS,
    ) + CREATE_INDEXES
}
