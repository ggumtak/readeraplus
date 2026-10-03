package com.ggumtak.readeraplus.data

/**
 * SQL of the notes hub's read side (N §5.3), as pure builders: JVM-testable, and every statement can be prepared
 * against a real SQLite off-device. Each builder returns a [SqlQuery] with its arguments in placeholder order.
 *
 * A list query never selects `quote_text`, `note` or `review` whole (a quote may be 100,000 chars; 50 whole rows can
 * overflow the 2 MB CursorWindow): the key statements select only the narrow key form `k, id, b, t, s, o` (kind, row
 * id, book id, sort time, section, offset), the search key scan adds `n` (has a memo, 0/1) and `st` (style; -1 for
 * kinds other than quotes), and the details read `substr(…, 1, 601 / 401)`.
 *
 * Expected query plans (`EXPLAIN QUERY PLAN`, SQLite 3.18+, asserted by `tools/check_sql.py`) are written as
 * `PLAN:` lines in each builder's KDoc.
 */
internal object NotesSql {

    /** The narrow key columns, in this order: kind, row id, book id, sort time, section, offset. */
    const val KEY_COLUMNS = 6
    /** The search key scan adds `n` (memo 0/1) and `st` (style, -1 when not a quote). */
    const val SCAN_COLUMNS = 8
    /** Kind code the counts statement gives memo rows (QM / MM relabelled). */
    const val MEMO_KIND = 5
    /** Detail chunk: at most this many ids per `IN (…)` (one page is ≤ 51 rows). */
    const val DETAIL_CHUNK = 51
    /** `countForBooks` chunk (4 × ids placeholders per statement, well under SQLite's 999). */
    const val COUNT_CHUNK = 200

    /** One source of rows. [k] is the kind code (NoteKind.code), [alias] the table alias of its WHERE terms. */
    enum class Arm(val k: Int, val alias: Char) {
        /** Quotes. */
        Q(1, 'q'),
        /** Quotes with a memo (메모 tab). */
        QM(1, 'q'),
        /** Bookmarks. */
        M(2, 'm'),
        /** Bookmarks with a memo (메모 tab). */
        MM(2, 'm'),
        /** Reviews (one per book). */
        R(3, 'b'),
        /** Lookups (단어장). */
        L(4, 'l'),
        /** Lookups, one row per word: its latest lookup (`GROUP BY word_key`). */
        L1(4, 'l'),
    }

    /** The lookups arm of a query: one row per word ([NotesQuery.wordsOnce]) or every lookup. */
    fun lookupArm(q: NotesQuery): Arm = if (q.wordsOnce) Arm.L1 else Arm.L

    /**
     * Arms of a tab. ALL = Q, M, R, L; MEMOS = QM, MM; WORDS = L (L1 when `wordsOnce`; also in ALL, so the 전체 count
     * and list agree with the 단어 tab).
     */
    fun arms(q: NotesQuery): List<Arm> = when (q.tab) {
        NotesTab.ALL -> listOf(Arm.Q, Arm.M, Arm.R, lookupArm(q))
        NotesTab.QUOTES -> listOf(Arm.Q)
        NotesTab.MEMOS -> listOf(Arm.QM, Arm.MM)
        NotesTab.BOOKMARKS -> listOf(Arm.M)
        NotesTab.REVIEWS -> listOf(Arm.R)
        NotesTab.WORDS -> listOf(lookupArm(q))
    }

    /** The colour filter applies to the 인용문 tab only. */
    fun styleFilter(q: NotesQuery): Int? = if (q.tab == NotesTab.QUOTES) q.style else null

    /** Search patterns of [text]: `%token%` with LIKE wildcards escaped, one per token (at most 8). */
    fun patterns(text: String): List<String> =
        LibrarySql.searchTokens(text).map { "%" + LibrarySql.escapeLike(it) + "%" }

    private const val ESC = " ESCAPE '\\'"
    private const val TITLE_IN = " IN (SELECT id FROM books WHERE title LIKE ?$ESC)"

    /**
     * One arm: `SELECT <aliased key columns> FROM <table> WHERE <base> <filters>` (+ `GROUP BY` for L1). Every column
     * is aliased in every arm (a single-arm statement takes its names from it). [scan] adds `n` and `st`. The [book]
     * filter is either one id ([bookIn] = 1) or `IN (?, …)` of [bookIn] ids; the caller binds them.
     */
    internal fun arm(
        a: Arm, scan: Boolean, sb: StringBuilder, args: MutableList<String>,
        book: List<Long>?, style: Int?, pats: List<String>, pageBooks: List<Long>? = null,
    ) {
        val x = a.alias
        // L1 groups words before the page's books are picked: the latest lookup of a word stays the same row on
        // every page (the book list counts it under that row's book).
        val wrap = a == Arm.L1 && pageBooks != null
        if (wrap) sb.append("SELECT k, id, b, t, s, o").append(if (scan) ", n, st" else "").append(" FROM (")
        sb.append("SELECT ").append(a.k).append(" AS k, ")
        when (a) {
            Arm.Q, Arm.QM -> sb.append("q.id AS id, q.book_id AS b, q.created_at AS t, q.section AS s, q.start_offset AS o")
            Arm.M, Arm.MM -> sb.append("m.id AS id, m.book_id AS b, m.created_at AS t, m.section AS s, m.char_offset AS o")
            Arm.R -> sb.append("b.id AS id, b.id AS b, CASE WHEN b.review_at > 0 THEN b.review_at ELSE b.last_read_at END AS t, -1 AS s, -1 AS o")
            Arm.L -> sb.append("l.id AS id, l.book_id AS b, l.created_at AS t, l.section AS s, l.start_offset AS o")
            Arm.L1 -> sb.append("l.id AS id, l.book_id AS b, MAX(l.created_at) AS t, l.section AS s, l.start_offset AS o")
        }
        if (scan) when (a) {
            Arm.Q, Arm.QM -> sb.append(", (q.note <> '') AS n, q.style AS st")
            Arm.M, Arm.MM -> sb.append(", (m.note <> '') AS n, -1 AS st")
            else -> sb.append(", 0 AS n, -1 AS st")
        }
        sb.append(when (a) {
            Arm.Q, Arm.QM -> " FROM quotes q WHERE "
            Arm.M, Arm.MM -> " FROM bookmarks m WHERE "
            Arm.R -> " FROM books b WHERE "
            Arm.L, Arm.L1 -> " FROM lookups l WHERE "
        })
        sb.append(when (a) {
            Arm.QM -> "q.note <> ''"
            Arm.MM -> "m.note <> ''"
            Arm.R -> "b.review <> ''"
            else -> "1"
        })
        if (book != null) {
            sb.append(" AND ").append(x).append(if (a == Arm.R) ".id" else ".book_id")
            if (book.size == 1) sb.append(" = ?") else inList(sb, book.size)
            for (id in book) args += id.toString()
        }
        if (style != null && (a == Arm.Q || a == Arm.QM)) {
            sb.append(" AND q.style = ?")
            args += style.toString()
        }
        for (p in pats) {
            when (a) {
                Arm.Q, Arm.QM -> {
                    sb.append(" AND (q.quote_text LIKE ?$ESC OR q.note LIKE ?$ESC OR q.book_id$TITLE_IN)")
                    args += p; args += p; args += p
                }
                Arm.M, Arm.MM -> {
                    sb.append(" AND (m.snippet LIKE ?$ESC OR m.note LIKE ?$ESC OR m.book_id$TITLE_IN)")
                    args += p; args += p; args += p
                }
                Arm.R -> {
                    sb.append(" AND (b.review LIKE ?$ESC OR b.title LIKE ?$ESC)")
                    args += p; args += p
                }
                Arm.L, Arm.L1 -> {
                    sb.append(" AND (l.word LIKE ?$ESC OR l.context LIKE ?$ESC OR l.note LIKE ?$ESC OR l.book_id$TITLE_IN)")
                    args += p; args += p; args += p; args += p
                }
            }
        }
        if (a == Arm.L1) sb.append(" GROUP BY l.word_key")
        if (pageBooks != null) {
            if (wrap) sb.append(") WHERE b") else sb.append(" AND ").append(x).append(if (a == Arm.R) ".id" else ".book_id")
            if (pageBooks.size == 1) sb.append(" = ?") else inList(sb, pageBooks.size)
            for (id in pageBooks) args += id.toString()
        }
    }

    private fun inList(sb: StringBuilder, n: Int) {
        sb.append(" IN (")
        for (i in 0 until n) sb.append(if (i == 0) "?" else ", ?")
        sb.append(')')
    }

    private fun union(
        arms: List<Arm>, scan: Boolean, sb: StringBuilder, args: MutableList<String>,
        book: List<Long>?, style: Int?, pats: List<String>, pageBooks: List<Long>? = null,
    ) {
        for (i in arms.indices) {
            if (i > 0) sb.append(" UNION ALL ")
            arm(arms[i], scan, sb, args, book, style, pats, pageBooks)
        }
    }

    // ---- page keys ----

    /** First row a page's key statement asks for: page `p > 0` starts one row early (the previous page's last row). */
    fun pageOffset(index: Int): Int = if (index <= 0) 0 else Notes.PAGE_ROWS * index - 1

    /** Rows a page's key statement asks for: 50, or 51 for `p > 0` (the extra first row decides the day header). */
    fun pageLimit(index: Int): Int = if (index <= 0) Notes.PAGE_ROWS else Notes.PAGE_ROWS + 1

    /**
     * Page keys of a date order (NEWEST / OLDEST), without search. Multi-arm: `… UNION ALL … ORDER BY t DESC, k, id
     * DESC`; single-arm: `ORDER BY t DESC, id DESC` with no `k` (a constant `k` would force a temp B-tree). OLDEST
     * reverses t and id. `LIMIT 50 OFFSET 0`, or `LIMIT 51 OFFSET 50p − 1`. [limit] < 0 lists every row.
     *
     * PLAN (unfiltered single-arm): `SCAN q USING INDEX quotes_created` (bookmarks_created / lookups_created; MEMOS
     * `quotes_memo` + `bookmarks_memo`), no "USE TEMP B-TREE FOR ORDER BY". REVIEWS: `SCAN b` + temp B-tree (few
     * reviews). Multi-arm: `MERGE (UNION ALL)` of the per-arm index scans. With a book filter: `*_book` + a small sort.
     * WORDS once (L1): `SCAN l USING INDEX lookups_word` + temp B-tree for the order.
     */
    fun datePage(q: NotesQuery, index: Int): SqlQuery = dateKeys(q, pageLimit(index), pageOffset(index))

    /** Every key row of [q] in its date order ([refs] / export lists of a date order). */
    fun dateAll(q: NotesQuery): SqlQuery = dateKeys(q, -1, 0)

    private fun dateKeys(q: NotesQuery, limit: Int, offset: Int): SqlQuery {
        val arms = arms(q)
        val sb = StringBuilder(256 * arms.size)
        val args = ArrayList<String>(4)
        union(arms, false, sb, args, q.bookId?.let { listOf(it) }, styleFilter(q), patterns(q.text))
        val oldest = q.order == NotesOrder.OLDEST
        sb.append(" ORDER BY ").append(if (oldest) "t ASC" else "t DESC")
        if (arms.size > 1) sb.append(", k")
        sb.append(if (oldest) ", id ASC" else ", id DESC")
        sb.append(" LIMIT ? OFFSET ?")
        args += limit.toString(); args += offset.toString()
        return SqlQuery(sb.toString(), args.toTypedArray())
    }

    /**
     * Books with notes under [q] (book orders and the book chooser): `(id, title, author, path, trashed, missing_at,
     * last_read_at, count)`. LEFT JOIN: a row whose book is gone (orphan after a downgrade) still gets its count, with
     * NULL book columns ("(삭제된 책)").
     *
     * PLAN: the arms as in [datePage] (no ORDER BY) into a temp B-tree for `GROUP BY b`, then `SEARCH bk USING INTEGER
     * PRIMARY KEY (rowid=?)`.
     */
    fun books(q: NotesQuery): SqlQuery {
        val sb = StringBuilder(1024)
        val args = ArrayList<String>(4)
        sb.append("SELECT n.bid, bk.title, bk.author, bk.path, bk.trashed, bk.missing_at, bk.last_read_at, n.c ")
        sb.append("FROM (SELECT b AS bid, COUNT(*) AS c FROM (")
        union(arms(q), false, sb, args, q.bookId?.let { listOf(it) }, styleFilter(q), patterns(q.text))
        sb.append(") GROUP BY b) n LEFT JOIN books bk ON bk.id = n.bid")
        return SqlQuery(sb.toString(), args.toTypedArray())
    }

    /**
     * Rows of the page's books in reading order, one statement for up to 51 books: `SELECT * FROM (<arms with AND
     * x.book_id IN (?, …)>) ORDER BY CASE b WHEN ? THEN 0 … END, s, o, k, id LIMIT ? OFFSET ?` ([offset] = the first
     * book's inner offset). The outer SELECT is required: a compound SELECT's ORDER BY may not hold an expression.
     *
     * PLAN: `SEARCH q USING INDEX quotes_book (book_id=?)` (bookmarks_book, lookups_book; reviews by PK) per arm, then
     * one temp B-tree over those books' rows. WORDS once (L1): `SCAN l USING INDEX lookups_word` (words are grouped over
     * all books first, then filtered to the page's books) + the temp B-tree.
     */
    fun bookPage(q: NotesQuery, bookIds: List<Long>, limit: Int, offset: Int): SqlQuery {
        require(bookIds.isNotEmpty())
        val sb = StringBuilder(1024)
        val args = ArrayList<String>(bookIds.size * 5 + 4)
        sb.append("SELECT * FROM (")
        union(arms(q), false, sb, args, q.bookId?.let { listOf(it) }, styleFilter(q), patterns(q.text), bookIds)
        sb.append(") ORDER BY CASE b")
        for (i in bookIds.indices) {
            sb.append(" WHEN ? THEN ").append(i)
            args += bookIds[i].toString()
        }
        sb.append(" END, s, o, k, id LIMIT ? OFFSET ?")
        args += limit.toString(); args += offset.toString()
        return SqlQuery(sb.toString(), args.toTypedArray())
    }

    /**
     * The search key scan (N §5.3.7): every arm (Q, M, R, L | L1) with the book and search filters, no style filter
     * and no ORDER BY, selecting `k, id, b, t, s, o, n, st`. Counts, style counts, both orders and the book list are
     * derived from it in memory ([NotesKeys]). Also used without search words for [Notes.refs] in book orders.
     *
     * PLAN: `SCAN q` / `SCAN m` / `SCAN b` / `SCAN l` (LIKE needs the rows; one pass each), the title subquery
     * `SCAN books` once as a LIST SUBQUERY; with a book filter `SEARCH … USING INDEX *_book`.
     */
    fun scan(q: NotesQuery): SqlQuery {
        val sb = StringBuilder(2048)
        val args = ArrayList<String>(16)
        union(listOf(Arm.Q, Arm.M, Arm.R, lookupArm(q)), true, sb, args, q.bookId?.let { listOf(it) }, null, patterns(q.text))
        return SqlQuery(sb.toString(), args.toTypedArray())
    }

    // ---- counts ----

    /**
     * Counts without search: one statement of scalar subqueries, columns (quotes, bookmarks, reviews, words, quote
     * memos, bookmark memos). [style] narrows the quote count (인용문 colour chip). WORDS once counts distinct words.
     *
     * PLAN (unfiltered): `SCAN quotes USING COVERING INDEX quotes_book` (any covering index), `SCAN bookmarks USING
     * COVERING INDEX …`, `SCAN books` (reviews), `SCAN lookups USING COVERING INDEX lookups_word`, `SCAN quotes USING
     * COVERING INDEX quotes_memo`, `SCAN bookmarks USING COVERING INDEX bookmarks_memo`; no temp B-tree. Book filter:
     * `SEARCH … USING INDEX *_book (book_id=?)`; reviews `SEARCH books USING INTEGER PRIMARY KEY`.
     */
    fun counts(bookId: Long?, style: Int?, wordsOnce: Boolean): SqlQuery {
        val args = ArrayList<String>(8)
        val bk = bookId?.toString()
        val sb = StringBuilder(512)
        sb.append("SELECT (SELECT COUNT(*) FROM quotes")
        var w = false
        if (bk != null) { sb.append(" WHERE book_id = ?"); args += bk; w = true }
        if (style != null) { sb.append(if (w) " AND" else " WHERE").append(" style = ?"); args += style.toString() }
        sb.append("), (SELECT COUNT(*) FROM bookmarks")
        if (bk != null) { sb.append(" WHERE book_id = ?"); args += bk }
        sb.append("), (SELECT COUNT(*) FROM books WHERE review <> ''")
        if (bk != null) { sb.append(" AND id = ?"); args += bk }
        sb.append(if (wordsOnce) "), (SELECT COUNT(DISTINCT word_key) FROM lookups" else "), (SELECT COUNT(*) FROM lookups")
        if (bk != null) { sb.append(" WHERE book_id = ?"); args += bk }
        sb.append("), (SELECT COUNT(*) FROM quotes WHERE note <> ''")
        if (bk != null) { sb.append(" AND book_id = ?"); args += bk }
        sb.append("), (SELECT COUNT(*) FROM bookmarks WHERE note <> ''")
        if (bk != null) { sb.append(" AND book_id = ?"); args += bk }
        sb.append(')')
        return SqlQuery(sb.toString(), args.toTypedArray())
    }

    /**
     * The UNION ALL … GROUP BY k counts form (N §5.3.5): every arm with the filters, memo arms relabelled [MEMO_KIND].
     * Not run by [Notes] (the scalar form or the search key scan replace it); kept as the reference the key scan's
     * in-memory counts are checked against (`check_sql.py`, [NotesKeys]).
     */
    fun countsUnion(q: NotesQuery): SqlQuery {
        val sb = StringBuilder(2048)
        val args = ArrayList<String>(16)
        val book = q.bookId?.let { listOf(it) }
        val pats = patterns(q.text)
        sb.append("SELECT k, COUNT(*) FROM (")
        union(listOf(Arm.Q, Arm.M, Arm.R, lookupArm(q)), false, sb, args, book, null, pats)
        for (a in listOf(Arm.QM, Arm.MM)) {
            sb.append(" UNION ALL SELECT ").append(MEMO_KIND).append(" AS k, id, b, t, s, o FROM (")
            arm(a, false, sb, args, book, null, pats)
            sb.append(')')
        }
        sb.append(") GROUP BY k")
        return SqlQuery(sb.toString(), args.toTypedArray())
    }

    /**
     * Quote count per stored style, without search: `(style, count)`.
     *
     * PLAN: `SCAN quotes` (or `SEARCH quotes USING INDEX quotes_book (book_id=?)`) + temp B-tree for GROUP BY: style has
     * no index (a v2 column on a table v1 indexes cover); one pass over the row headers, never the quote text.
     */
    fun styleCounts(bookId: Long?): SqlQuery =
        if (bookId == null) SqlQuery("SELECT style, COUNT(*) FROM quotes GROUP BY style", emptyArray())
        else SqlQuery("SELECT style, COUNT(*) FROM quotes WHERE book_id = ? GROUP BY style", arrayOf(bookId.toString()))

    /**
     * Drawer badge: `[quotes + bookmarks + reviews, lookups]`.
     *
     * PLAN: covering-index COUNTs (`quotes_book`, `bookmarks_book`, `lookups_book` or any), `SCAN books` for reviews.
     */
    const val DRAWER_COUNTS = "SELECT (SELECT COUNT(*) FROM quotes) + (SELECT COUNT(*) FROM bookmarks) + " +
        "(SELECT COUNT(*) FROM books WHERE review <> ''), (SELECT COUNT(*) FROM lookups)"

    /**
     * `COUNT_NOTES_OF_BOOKS`: quotes + bookmarks + reviews + lookups of [ids] (≤ [COUNT_CHUNK] per statement).
     *
     * PLAN: `SEARCH quotes USING COVERING INDEX quotes_book (book_id=?)` (bookmarks_book, lookups_book), `SEARCH books
     * USING INTEGER PRIMARY KEY (rowid=?)`.
     */
    fun countForBooks(ids: List<Long>): SqlQuery {
        require(ids.isNotEmpty())
        val sb = StringBuilder(128 + ids.size * 12)
        val args = ArrayList<String>(ids.size * 4)
        fun part(prefix: String) {
            sb.append(prefix)
            inList(sb, ids.size)
            sb.append(')')
            for (id in ids) args += id.toString()
        }
        sb.append("SELECT ")
        part("(SELECT COUNT(*) FROM quotes WHERE book_id")
        part(" + (SELECT COUNT(*) FROM bookmarks WHERE book_id")
        part(" + (SELECT COUNT(*) FROM books WHERE review <> '' AND id")
        part(" + (SELECT COUNT(*) FROM lookups WHERE book_id")
        return SqlQuery(sb.toString(), args.toTypedArray())
    }

    // ---- details (second phase; primary-key lookups) ----

    /** Detail columns of a quote: id, book, section, start, end, text ≤ 601, note ≤ 401, style, chapter, frac, sig, time. PLAN: `SEARCH q USING INTEGER PRIMARY KEY (rowid=?)`. */
    fun quoteDetails(ids: List<Long>, full: Boolean = false): SqlQuery = details(
        "SELECT q.id, q.book_id, q.section, q.start_offset, q.end_offset, " +
            (if (full) "q.quote_text, q.note" else "substr(q.quote_text, 1, ${Notes.BODY_CHARS + 1}), substr(q.note, 1, ${Notes.NOTE_CHARS + 1})") +
            ", q.style, q.chapter, q.frac, q.sig, q.created_at FROM quotes q WHERE q.id", ids,
    )

    /** id, book, section, offset, snippet, note ≤ 401, chapter, frac, sig, time. PLAN: `SEARCH m USING INTEGER PRIMARY KEY (rowid=?)`. */
    fun bookmarkDetails(ids: List<Long>, full: Boolean = false): SqlQuery = details(
        "SELECT m.id, m.book_id, m.section, m.char_offset, m.snippet, " +
            (if (full) "m.note" else "substr(m.note, 1, ${Notes.NOTE_CHARS + 1})") +
            ", m.chapter, m.frac, m.sig, m.created_at FROM bookmarks m WHERE m.id", ids,
    )

    /** id, review ≤ 601, review_at, last_read_at, progress. PLAN: `SEARCH b USING INTEGER PRIMARY KEY (rowid=?)`. */
    fun reviewDetails(ids: List<Long>, full: Boolean = false): SqlQuery = details(
        "SELECT b.id, " + (if (full) "b.review" else "substr(b.review, 1, ${Notes.BODY_CHARS + 1})") +
            ", b.review_at, b.last_read_at, b.progress FROM books b WHERE b.id", ids,
    )

    /**
     * id, book, word, section, start, end, context, note ≤ 401, chapter, frac, sig, via, app, time, lookups of the word.
     * PLAN: `SEARCH l USING INTEGER PRIMARY KEY (rowid=?)` + correlated `SEARCH l2 USING COVERING INDEX lookups_word (word_key=?)`.
     */
    fun lookupDetails(ids: List<Long>, full: Boolean = false): SqlQuery = details(
        "SELECT l.id, l.book_id, l.word, l.section, l.start_offset, l.end_offset, l.context, " +
            (if (full) "l.note" else "substr(l.note, 1, ${Notes.NOTE_CHARS + 1})") +
            ", l.chapter, l.frac, l.sig, l.via, l.app, l.created_at, " +
            "(SELECT COUNT(*) FROM lookups l2 WHERE l2.word_key = l.word_key) FROM lookups l WHERE l.id", ids,
    )

    /** Full (body, note) of one note ([Notes.fullText]). PLAN: by primary key. */
    fun fullText(kind: NoteKind): String = when (kind) {
        NoteKind.QUOTE -> "SELECT quote_text, note FROM quotes WHERE id = ?"
        NoteKind.BOOKMARK -> "SELECT snippet, note FROM bookmarks WHERE id = ?"
        NoteKind.REVIEW -> "SELECT review, '' FROM books WHERE id = ?"
        NoteKind.LOOKUP -> "SELECT context, note FROM lookups WHERE id = ?"
    }

    /**
     * Book rows for the hub's title map: id, title, author, path, trashed, missing_at, last_read_at.
     * PLAN: `SEARCH books USING INTEGER PRIMARY KEY (rowid=?)`.
     */
    fun bookInfo(ids: List<Long>): SqlQuery =
        details("SELECT id, title, author, path, trashed, missing_at, last_read_at FROM books WHERE id", ids)

    /**
     * Export pre-pass of one kind: id, book, section, start, has memo, style (−1 when not a quote). The memo test reads
     * the row; nothing long is returned. PLAN: by primary key.
     */
    fun exportKeys(kind: NoteKind, ids: List<Long>): SqlQuery = details(
        when (kind) {
            NoteKind.QUOTE -> "SELECT id, book_id, section, start_offset, note <> '', style FROM quotes WHERE id"
            NoteKind.BOOKMARK -> "SELECT id, book_id, section, char_offset, note <> '', -1 FROM bookmarks WHERE id"
            NoteKind.REVIEW -> "SELECT id, id, -1, -1, 0, -1 FROM books WHERE review <> '' AND id"
            NoteKind.LOOKUP -> "SELECT id, book_id, section, start_offset, 0, -1 FROM lookups WHERE id"
        }, ids,
    )

    private fun details(prefix: String, ids: List<Long>): SqlQuery {
        require(ids.isNotEmpty())
        val sb = StringBuilder(prefix.length + 8 + ids.size * 3)
        sb.append(prefix)
        if (ids.size == 1) sb.append(" = ?") else inList(sb, ids.size)
        return SqlQuery(sb.toString(), Array(ids.size) { ids[it].toString() })
    }

    // ---- lookups (writes; N §5.4) ----

    /** The dedupe probe: the latest row of the same book + word + place within the window. PLAN: `SEARCH lookups USING INDEX lookups_word (word_key=?)`. */
    const val LOOKUP_FIND_RECENT = "SELECT id FROM lookups WHERE book_id = ? AND word_key = ? AND section = ? " +
        "AND start_offset = ? AND created_at >= ? ORDER BY created_at DESC LIMIT 1"
    /** Refreshes the deduped row. PLAN: by primary key. */
    const val LOOKUP_REFRESH = "UPDATE lookups SET created_at = ?, via = ?, app = ?, context = ? WHERE id = ?"
    const val LOOKUP_INSERT = "INSERT INTO lookups(book_id, word, word_key, section, start_offset, end_offset, context, " +
        "chapter, frac, sig, via, app, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    const val LOOKUP_SET_NOTE = "UPDATE lookups SET note = ? WHERE id = ?"
    const val LOOKUP_COUNT = "SELECT COUNT(*) FROM lookups"
    const val LOOKUP_CLEAR = "DELETE FROM lookups"
    const val BOOK_EXISTS = "SELECT 1 FROM books WHERE id = ?"

    /** `DELETE FROM lookups WHERE id IN (?, …)` for [n] ids. PLAN: by primary key. */
    fun lookupDelete(n: Int): String {
        val sb = StringBuilder(40 + n * 3).append("DELETE FROM lookups WHERE id")
        if (n == 1) sb.append(" = ?") else inList(sb, n)
        return sb.toString()
    }
}
