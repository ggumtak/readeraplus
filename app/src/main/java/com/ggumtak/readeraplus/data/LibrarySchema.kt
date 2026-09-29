package com.ggumtak.readeraplus.data

/**
 * SQLite schema of `library.db` (pure constants so the statements can be checked off-device).
 *
 * Only SQL understood by SQLite 3.18 (Android 8, minSdk 26) is used: no UPSERT, no window functions,
 * no RETURNING, no NULLS FIRST/LAST. Column names avoid SQL keywords (`offset`, `end`, `text`).
 */
internal object LibrarySchema {
    const val DB_NAME = "library.db"
    const val DB_VERSION = 1

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
        "meta_locked INTEGER NOT NULL DEFAULT 0)"

    const val CREATE_BOOKMARKS = "CREATE TABLE IF NOT EXISTS bookmarks(" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
        "book_id INTEGER NOT NULL," +
        "section INTEGER NOT NULL DEFAULT 0," +
        "char_offset INTEGER NOT NULL DEFAULT 0," +
        "snippet TEXT NOT NULL DEFAULT ''," +
        "note TEXT NOT NULL DEFAULT ''," +
        "created_at INTEGER NOT NULL DEFAULT 0)"

    const val CREATE_QUOTES = "CREATE TABLE IF NOT EXISTS quotes(" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
        "book_id INTEGER NOT NULL," +
        "section INTEGER NOT NULL DEFAULT 0," +
        "start_offset INTEGER NOT NULL DEFAULT 0," +
        "end_offset INTEGER NOT NULL DEFAULT 0," +
        "quote_text TEXT NOT NULL DEFAULT ''," +
        "note TEXT NOT NULL DEFAULT ''," +
        "created_at INTEGER NOT NULL DEFAULT 0)"

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

    val CREATE_INDEXES = listOf(
        "CREATE INDEX IF NOT EXISTS books_last_read ON books(last_read_at)",
        "CREATE INDEX IF NOT EXISTS books_title ON books(title COLLATE NOCASE)",
        "CREATE INDEX IF NOT EXISTS books_author ON books(author)",
        "CREATE INDEX IF NOT EXISTS books_series ON books(series)",
        "CREATE INDEX IF NOT EXISTS books_folder ON books(folder)",
        "CREATE INDEX IF NOT EXISTS bookmarks_book ON bookmarks(book_id)",
        "CREATE INDEX IF NOT EXISTS quotes_book ON quotes(book_id)",
        "CREATE INDEX IF NOT EXISTS book_collections_coll ON book_collections(collection_id)",
    )

    /** Every statement needed to create a fresh v1 database, in order. */
    val CREATE_ALL: List<String> = listOf(
        CREATE_BOOKS, CREATE_BOOKMARKS, CREATE_QUOTES, CREATE_COLLECTIONS, CREATE_BOOK_COLLECTIONS,
        CREATE_PAGE_COUNTS, CREATE_IGNORED,
    ) + CREATE_INDEXES
}
