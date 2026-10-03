package com.ggumtak.readeraplus.data

/**
 * The backup's own statements over the v3 schema ([LibrarySchema]): the snapshot reads every v3 column (quote style
 * and place, bookmark place, `review_at`, `missing_at`, lookups, `return_mark`) and the restore writes them (N §5.6,
 * U §3.3). Kept apart from [LibrarySql] so the backup's column lists and their mapping below change together.
 */
internal object BackupSql {

    /** [LibrarySql.BOOK_COLUMNS] + meta_locked, review_at, missing_at (at [BOOK_EXTRA], +1, +2). */
    const val SELECT_BOOKS = "SELECT ${LibrarySql.BOOK_COLUMNS}, meta_locked, review_at, missing_at FROM books ORDER BY id"
    const val BOOK_EXTRA = LibrarySql.BOOK_COLUMN_COUNT

    /** id, book_id, section, char_offset, snippet, created_at, note, chapter, frac, sig. */
    const val SELECT_ALL_BOOKMARKS = "SELECT id, book_id, section, char_offset, snippet, created_at, note, chapter, frac, " +
        "sig FROM bookmarks ORDER BY book_id, section, char_offset, id"
    const val SELECT_BOOKMARKS_OF_BOOK = "SELECT id, book_id, section, char_offset, snippet, created_at, note, chapter, " +
        "frac, sig FROM bookmarks WHERE book_id = ? ORDER BY section, char_offset, id"
    /** Args: book_id, section, char_offset, snippet, note, created_at, chapter, frac, sig. */
    const val INSERT_BOOKMARK = "INSERT INTO bookmarks(book_id, section, char_offset, snippet, note, created_at, chapter, " +
        "frac, sig) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    /** Args: chapter, frac, sig, id; only an unknown place is filled. */
    const val FILL_BOOKMARK_PLACE = "UPDATE bookmarks SET chapter = ?, frac = ?, sig = ? WHERE id = ? AND frac < 0"

    /** id, book_id, section, start_offset, end_offset, quote_text, note, created_at, style, chapter, frac, sig. */
    const val SELECT_ALL_QUOTES = "SELECT id, book_id, section, start_offset, end_offset, quote_text, note, created_at, " +
        "style, chapter, frac, sig FROM quotes ORDER BY book_id, section, start_offset, end_offset, id"
    const val SELECT_QUOTES_OF_BOOK = "SELECT id, book_id, section, start_offset, end_offset, quote_text, note, " +
        "created_at, style, chapter, frac, sig FROM quotes WHERE book_id = ? ORDER BY section, start_offset, end_offset, id"
    /** Args: book_id, section, start_offset, end_offset, quote_text, note, created_at, style, chapter, frac, sig. */
    const val INSERT_QUOTE = "INSERT INTO quotes(book_id, section, start_offset, end_offset, quote_text, note, created_at, " +
        "style, chapter, frac, sig) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    /** Args: style, id; only onto the default look. */
    const val FILL_QUOTE_STYLE = "UPDATE quotes SET style = ? WHERE id = ? AND style = 0"
    const val FILL_QUOTE_PLACE = "UPDATE quotes SET chapter = ?, frac = ?, sig = ? WHERE id = ? AND frac < 0"

    /** book_id, word, section, start_offset, end_offset, context, chapter, frac, sig, via, app, note, created_at. */
    const val SELECT_ALL_LOOKUPS = "SELECT book_id, word, section, start_offset, end_offset, context, chapter, frac, sig, " +
        "via, app, note, created_at FROM lookups ORDER BY book_id, created_at, id"
    /** The dedupe keys of one book: word_key, section, start_offset, created_at. */
    const val SELECT_LOOKUP_KEYS_OF_BOOK =
        "SELECT word_key, section, start_offset, created_at FROM lookups WHERE book_id = ?"
    /** Args: book_id, word, word_key, section, start, end, context, chapter, frac, sig, via, app, note, created_at. */
    const val INSERT_LOOKUP = "INSERT INTO lookups(book_id, word, word_key, section, start_offset, end_offset, context, " +
        "chapter, frac, sig, via, app, note, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    /** last_read_at, favorite, to_read, have_read, trashed, review, review_at, encoding, missing_at. */
    const val SELECT_BOOK_STATE = "SELECT last_read_at, favorite, to_read, have_read, trashed, review, review_at, " +
        "encoding, missing_at FROM books WHERE id = ?"
    /**
     * The merged row ([BackupMerge.book]). Args: favorite, to_read, have_read, trashed, review, review_at, encoding,
     * missing_at, reading_seconds (the larger value wins), added_at (the earlier value wins), id.
     */
    const val RESTORE_BOOK = "UPDATE books SET favorite = ?, to_read = ?, have_read = ?, trashed = ?, review = ?, " +
        "review_at = ?, encoding = ?, missing_at = ?, reading_seconds = MAX(reading_seconds, ?), " +
        "added_at = MIN(added_at, ?) WHERE id = ?"
    /**
     * A placeholder's row (N §5.6). OR IGNORE: a path already in the library is never overwritten. Args: path,
     * file_name, folder, title, author, series, series_index, format, size, mtime, added_at, language.
     */
    const val INSERT_PLACEHOLDER = "INSERT OR IGNORE INTO books(path, file_name, folder, title, author, series, " +
        "series_index, format, size, mtime, added_at, language) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    /** book_id, txt_override, finished_at, episode_label, return_mark. */
    const val SELECT_ALL_BOOK_PREFS = "SELECT book_id, txt_override, finished_at, episode_label, return_mark " +
        "FROM book_prefs ORDER BY book_id"
    /** txt_override, finished_at, episode_label, return_mark of one book. */
    const val SELECT_BOOK_PREFS =
        "SELECT txt_override, finished_at, episode_label, return_mark FROM book_prefs WHERE book_id = ?"
    /** Args: txt_override, finished_at, episode_label, return_mark, book_id. */
    const val SET_PREFS_ROW = "UPDATE book_prefs SET txt_override = ?, finished_at = ?, episode_label = ?, " +
        "return_mark = ? WHERE book_id = ?"
    const val INSERT_PREFS_ROW = "INSERT INTO book_prefs(book_id, txt_override, finished_at, episode_label, " +
        "return_mark) SELECT id, ?, ?, ?, ? FROM books WHERE id = ?"
}
