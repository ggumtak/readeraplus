package com.ggumtak.readeraplus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notes hub's SQL builders (N §5.3, §16). SQLite does not run on the JVM; when the environment variable
 * `NOTES_SQL_DUMP` names a file, every statement is written there as JSON so it can be prepared and planned against a
 * real SQLite (`tools/check_sql.py`).
 */
class NotesSqlTest {

    private fun placeholders(sql: String): Int = sql.count { it == '?' }

    private fun assertArgs(q: SqlQuery) = assertEquals(q.sql, placeholders(q.sql), q.args.size)

    private val ALIASES = listOf(" AS k,", " AS id,", " AS b,", " AS t,", " AS s,", " AS o")

    private fun queries(): List<Pair<String, NotesQuery>> {
        val out = ArrayList<Pair<String, NotesQuery>>()
        for (tab in NotesTab.entries) for (order in NotesOrder.entries) {
            for (book in listOf(null, 7L)) for (text in listOf("", "여름 50%_x")) for (style in listOf(null, 2)) {
                for (once in listOf(false, true)) {
                    val q = NotesQuery(tab, order, book, text, style, once)
                    out += "$tab/$order/book=$book/text=$text/style=$style/once=$once" to q
                }
            }
        }
        return out
    }

    @Test
    fun tabsMapToArms() {
        val a = NotesSql.Arm.entries
        assertEquals(listOf(a[0], a[2], a[4], a[5]), NotesSql.arms(NotesQuery(NotesTab.ALL)))
        assertEquals(listOf(NotesSql.Arm.QM, NotesSql.Arm.MM), NotesSql.arms(NotesQuery(NotesTab.MEMOS)))
        assertEquals(listOf(NotesSql.Arm.Q), NotesSql.arms(NotesQuery(NotesTab.QUOTES)))
        assertEquals(listOf(NotesSql.Arm.M), NotesSql.arms(NotesQuery(NotesTab.BOOKMARKS)))
        assertEquals(listOf(NotesSql.Arm.R), NotesSql.arms(NotesQuery(NotesTab.REVIEWS)))
        assertEquals(listOf(NotesSql.Arm.L), NotesSql.arms(NotesQuery(NotesTab.WORDS)))
        assertEquals(listOf(NotesSql.Arm.L1), NotesSql.arms(NotesQuery(NotesTab.WORDS, wordsOnce = true)))
    }

    @Test
    fun everyArmAliasesEveryKeyColumn() {
        for (arm in NotesSql.Arm.entries) {
            val sb = StringBuilder()
            NotesSql.arm(arm, false, sb, ArrayList(), null, null, emptyList())
            val sql = sb.toString()
            for (a in ALIASES) assertTrue("$arm $a: $sql", sql.contains(a))
            val scan = StringBuilder()
            NotesSql.arm(arm, true, scan, ArrayList(), null, null, emptyList())
            assertTrue(scan.toString().contains(" AS n, ") && scan.toString().contains(" AS st FROM "))
        }
    }

    @Test
    fun singleArmTabsEmitAStatementWithAliasedNames() {
        for (tab in listOf(NotesTab.BOOKMARKS, NotesTab.REVIEWS, NotesTab.WORDS, NotesTab.QUOTES)) {
            val q = NotesSql.datePage(NotesQuery(tab), 0)
            assertFalse(q.sql.contains("UNION"))
            assertTrue(q.sql, q.sql.endsWith("ORDER BY t DESC, id DESC LIMIT ? OFFSET ?"))
        }
    }

    @Test
    fun filtersUseEachArmsAlias() {
        val q = NotesSql.datePage(NotesQuery(NotesTab.ALL, bookId = 42L), 0)
        assertTrue(q.sql.contains("FROM quotes q WHERE 1 AND q.book_id = ?"))
        assertTrue(q.sql.contains("FROM bookmarks m WHERE 1 AND m.book_id = ?"))
        assertTrue(q.sql.contains("FROM books b WHERE b.review <> '' AND b.id = ?"))
        assertTrue(q.sql.contains("FROM lookups l WHERE 1 AND l.book_id = ?"))
        assertEquals(listOf("42", "42", "42", "42", "50", "0"), q.args.toList())
        val memo = NotesSql.datePage(NotesQuery(NotesTab.MEMOS, bookId = 3L), 0)
        assertTrue(memo.sql.contains("WHERE q.note <> '' AND q.book_id = ?"))
        assertTrue(memo.sql.contains("WHERE m.note <> '' AND m.book_id = ?"))
    }

    @Test
    fun colourFilterOnlyOnTheQuotesTab() {
        val q = NotesSql.datePage(NotesQuery(NotesTab.QUOTES, style = 3), 0)
        assertTrue(q.sql.contains("AND q.style = ?"))
        assertEquals(listOf("3", "50", "0"), q.args.toList())
        assertFalse(NotesSql.datePage(NotesQuery(NotesTab.ALL, style = 3), 0).sql.contains("style"))
        assertFalse(NotesSql.datePage(NotesQuery(NotesTab.MEMOS, style = 3), 0).sql.contains("style"))
    }

    @Test
    fun searchTokensAreEscapedAndBoundInPlaceholderOrder() {
        val q = NotesSql.datePage(NotesQuery(NotesTab.QUOTES, text = " 50%_x  a\\b "), 0)
        assertEquals(
            listOf("%50\\%\\_x%", "%50\\%\\_x%", "%50\\%\\_x%", "%a\\\\b%", "%a\\\\b%", "%a\\\\b%", "50", "0"),
            q.args.toList(),
        )
        assertTrue(q.sql.contains("(q.quote_text LIKE ? ESCAPE '\\' OR q.note LIKE ? ESCAPE '\\' OR q.book_id IN (SELECT id FROM books WHERE title LIKE ? ESCAPE '\\'))"))
        val all = NotesSql.datePage(NotesQuery(NotesTab.ALL, text = "x", bookId = 9L), 0)
        // Per arm: book first, then the search patterns (Q 3, M 3, R 2, L 4).
        assertEquals(
            listOf("9", "%x%", "%x%", "%x%", "9", "%x%", "%x%", "%x%", "9", "%x%", "%x%", "9", "%x%", "%x%", "%x%", "%x%", "50", "0"),
            all.args.toList(),
        )
        assertTrue(all.sql.contains("(m.snippet LIKE ? ESCAPE '\\' OR m.note LIKE ? ESCAPE '\\' OR m.book_id IN"))
        assertTrue(all.sql.contains("(b.review LIKE ? ESCAPE '\\' OR b.title LIKE ? ESCAPE '\\')"))
        assertTrue(all.sql.contains("(l.word LIKE ? ESCAPE '\\' OR l.context LIKE ? ESCAPE '\\' OR l.note LIKE ? ESCAPE '\\' OR l.book_id IN"))
        val many = (1..12).joinToString(" ") { "w$it" }
        assertEquals(8 * 3 + 2, NotesSql.datePage(NotesQuery(NotesTab.QUOTES, text = many), 0).args.size)
    }

    @Test
    fun dateOrdersAndPageWindows() {
        val multi = NotesSql.datePage(NotesQuery(NotesTab.ALL), 0)
        assertTrue(multi.sql.contains(" UNION ALL "))
        assertTrue(multi.sql.endsWith("ORDER BY t DESC, k, id DESC LIMIT ? OFFSET ?"))
        val old = NotesSql.datePage(NotesQuery(NotesTab.MEMOS, NotesOrder.OLDEST), 0)
        assertTrue(old.sql.endsWith("ORDER BY t ASC, k, id ASC LIMIT ? OFFSET ?"))
        val oldSingle = NotesSql.datePage(NotesQuery(NotesTab.QUOTES, NotesOrder.OLDEST), 0)
        assertTrue(oldSingle.sql.endsWith("ORDER BY t ASC, id ASC LIMIT ? OFFSET ?"))
        assertEquals(listOf("50", "0"), multi.args.takeLast(2))
        assertEquals(listOf("51", "49"), NotesSql.datePage(NotesQuery(), 1).args.takeLast(2))
        assertEquals(listOf("51", "999"), NotesSql.datePage(NotesQuery(), 20).args.takeLast(2))
        assertEquals(listOf("-1", "0"), NotesSql.dateAll(NotesQuery()).args.takeLast(2))
    }

    @Test
    fun wordsOnceGroupsByWordKey() {
        val q = NotesSql.datePage(NotesQuery(NotesTab.WORDS, wordsOnce = true), 0)
        assertTrue(q.sql.contains("MAX(l.created_at) AS t"))
        assertTrue(q.sql.contains("GROUP BY l.word_key ORDER BY t DESC, id DESC"))
        assertFalse(NotesSql.datePage(NotesQuery(NotesTab.WORDS), 0).sql.contains("GROUP BY"))
        assertTrue(NotesSql.counts(null, null, true).sql.contains("COUNT(DISTINCT word_key) FROM lookups"))
    }

    @Test
    fun countsUseTheScalarForm() {
        val q = NotesSql.counts(null, null, false)
        assertEquals(
            "SELECT (SELECT COUNT(*) FROM quotes), (SELECT COUNT(*) FROM bookmarks), (SELECT COUNT(*) FROM books WHERE review <> ''), " +
                "(SELECT COUNT(*) FROM lookups), (SELECT COUNT(*) FROM quotes WHERE note <> ''), (SELECT COUNT(*) FROM bookmarks WHERE note <> '')",
            q.sql,
        )
        assertEquals(0, q.args.size)
        val b = NotesSql.counts(5L, 2, false)
        assertTrue(b.sql.contains("FROM quotes WHERE book_id = ? AND style = ?"))
        assertTrue(b.sql.contains("FROM books WHERE review <> '' AND id = ?"))
        assertTrue(b.sql.contains("FROM quotes WHERE note <> '' AND book_id = ?"))
        assertEquals(listOf("5", "2", "5", "5", "5", "5", "5"), b.args.toList())
        assertFalse(b.sql.contains("UNION"))
        assertArgs(NotesSql.counts(null, 4, true))
    }

    @Test
    fun unionCountsRelabelMemosAsFive() {
        val q = NotesSql.countsUnion(NotesQuery(text = "a"))
        assertTrue(q.sql.startsWith("SELECT k, COUNT(*) FROM ("))
        assertTrue(q.sql.endsWith(") GROUP BY k"))
        assertEquals(2, Regex("SELECT 5 AS k, id, b, t, s, o FROM \\(").findAll(q.sql).count())
        assertTrue(q.sql.contains("WHERE q.note <> ''") && q.sql.contains("WHERE m.note <> ''"))
        assertArgs(q)
    }

    @Test
    fun bookPageIsOneStatementOrderedByCase() {
        val q = NotesSql.bookPage(NotesQuery(NotesTab.ALL, NotesOrder.BOOK_TITLE), listOf(11L, 4L, 9L), 51, 7)
        assertTrue(q.sql.startsWith("SELECT * FROM (SELECT 1 AS k"))
        assertTrue(q.sql.contains("q.book_id IN (?, ?, ?)"))
        assertTrue(q.sql.contains("b.id IN (?, ?, ?)"))
        assertTrue(q.sql.endsWith(") ORDER BY CASE b WHEN ? THEN 0 WHEN ? THEN 1 WHEN ? THEN 2 END, s, o, k, id LIMIT ? OFFSET ?"))
        assertEquals(listOf("11", "4", "9", "51", "7"), q.args.takeLast(5))
        assertArgs(q)
        val one = NotesSql.bookPage(NotesQuery(NotesTab.QUOTES, NotesOrder.BOOK_RECENT), listOf(3L), 50, 0)
        assertTrue(one.sql.contains("q.book_id = ?"))
        // L1 groups words first, then keeps the page's books.
        val once = NotesSql.bookPage(NotesQuery(NotesTab.WORDS, NotesOrder.BOOK_TITLE, wordsOnce = true), listOf(1L, 2L), 50, 0)
        assertTrue(once.sql, once.sql.contains("GROUP BY l.word_key) WHERE b IN (?, ?)"))
        assertArgs(once)
    }

    @Test
    fun booksStatementJoinsCountsToBooks() {
        val q = NotesSql.books(NotesQuery(NotesTab.MEMOS, NotesOrder.BOOK_TITLE))
        assertTrue(q.sql.startsWith("SELECT n.bid, bk.title, bk.author, bk.path, bk.trashed, bk.missing_at, bk.last_read_at, n.c FROM (SELECT b AS bid, COUNT(*) AS c FROM ("))
        assertTrue(q.sql.endsWith(") GROUP BY b) n LEFT JOIN books bk ON bk.id = n.bid"))
        assertFalse(q.sql.contains("ORDER BY"))
    }

    @Test
    fun searchScanCoversEveryArmWithoutOrderOrStyle() {
        val q = NotesSql.scan(NotesQuery(NotesTab.QUOTES, NotesOrder.NEWEST, 3L, "x", 2))
        assertFalse(q.sql.contains("ORDER BY"))
        assertFalse(q.sql.contains("style = ?"))
        assertEquals(3, Regex(" UNION ALL ").findAll(q.sql).count())
        assertTrue(q.sql.contains("(q.note <> '') AS n, q.style AS st"))
        assertArgs(q)
        assertTrue(NotesSql.scan(NotesQuery(wordsOnce = true)).sql.endsWith("GROUP BY l.word_key"))
    }

    @Test
    fun detailsUseSubstrAndNeverWholeLongColumns() {
        val ids = listOf(1L, 2L, 3L)
        val q = NotesSql.quoteDetails(ids).sql
        assertTrue(q.contains("substr(q.quote_text, 1, 601)") && q.contains("substr(q.note, 1, 401)"))
        assertFalse(Regex("(?<!substr\\()q\\.(quote_text|note),").containsMatchIn(q))
        assertTrue(q.endsWith("WHERE q.id IN (?, ?, ?)"))
        val m = NotesSql.bookmarkDetails(ids).sql
        assertTrue(m.contains("substr(m.note, 1, 401)"))
        val r = NotesSql.reviewDetails(ids).sql
        assertTrue(r.contains("substr(b.review, 1, 601)"))
        val l = NotesSql.lookupDetails(listOf(5L)).sql
        assertTrue(l.contains("substr(l.note, 1, 401)") && l.endsWith("WHERE l.id = ?"))
        assertTrue(l.contains("(SELECT COUNT(*) FROM lookups l2 WHERE l2.word_key = l.word_key)"))
        // Key statements never select a long column.
        for ((name, nq) in queries()) {
            val keys = if (nq.order.byBook) NotesSql.bookPage(nq, listOf(1L, 2L), 51, 0) else NotesSql.datePage(nq, 3)
            // Long columns appear only inside LIKE filters, never in a select list.
            val bare = Regex("(quote_text|\\.note|\\.review|\\.snippet|\\.context)\\b(?! LIKE| <> '')").find(keys.sql)
            assertEquals(name, null, bare?.value)
        }
        assertEquals(51, NotesSql.quoteDetails((1L..51L).toList()).args.size)
    }

    @Test
    fun countForBooksSumsEveryKind() {
        val q = NotesSql.countForBooks(listOf(1L, 2L))
        assertEquals(
            "SELECT (SELECT COUNT(*) FROM quotes WHERE book_id IN (?, ?)) + (SELECT COUNT(*) FROM bookmarks WHERE book_id IN (?, ?)) + " +
                "(SELECT COUNT(*) FROM books WHERE review <> '' AND id IN (?, ?)) + (SELECT COUNT(*) FROM lookups WHERE book_id IN (?, ?))",
            q.sql,
        )
        assertEquals(listOf("1", "2", "1", "2", "1", "2", "1", "2"), q.args.toList())
    }

    @Test
    fun lookupWriteStatements() {
        assertEquals(13, placeholders(NotesSql.LOOKUP_INSERT))
        assertEquals(5, placeholders(NotesSql.LOOKUP_FIND_RECENT))
        assertEquals(5, placeholders(NotesSql.LOOKUP_REFRESH))
        assertEquals("DELETE FROM lookups WHERE id IN (?, ?, ?)", NotesSql.lookupDelete(3))
        assertEquals("DELETE FROM lookups WHERE id = ?", NotesSql.lookupDelete(1))
    }

    @Test
    fun everyStatementHasMatchingArgsAndOptionalDump() {
        val dump = JSONArray()
        fun put(kind: String, name: String, q: SqlQuery) {
            assertArgs(q)
            dump.put(JSONObject().put("kind", kind).put("name", name).put("sql", q.sql).put("args", JSONArray(q.args.toList())))
        }
        for ((name, q) in queries()) {
            put("page", name, NotesSql.datePage(q, 0))
            put("page", "$name/p3", NotesSql.datePage(q, 3))
            put("books", name, NotesSql.books(q))
            put("bookPage", name, NotesSql.bookPage(q, listOf(1L, 2L, 3L), 51, 2))
            put("scan", name, NotesSql.scan(q))
            put("countsUnion", name, NotesSql.countsUnion(q))
        }
        for (book in listOf(null, 1L)) for (style in listOf(null, 1)) for (once in listOf(false, true)) {
            put("counts", "book=$book/style=$style/once=$once", NotesSql.counts(book, style, once))
        }
        put("styleCounts", "all", NotesSql.styleCounts(null))
        put("styleCounts", "book", NotesSql.styleCounts(1L))
        put("countForBooks", "3", NotesSql.countForBooks(listOf(1L, 2L, 3L)))
        for (full in listOf(false, true)) {
            put("details", "quote/$full", NotesSql.quoteDetails(listOf(1L, 2L), full))
            put("details", "bookmark/$full", NotesSql.bookmarkDetails(listOf(1L, 2L), full))
            put("details", "review/$full", NotesSql.reviewDetails(listOf(1L, 2L), full))
            put("details", "lookup/$full", NotesSql.lookupDetails(listOf(1L, 2L), full))
        }
        for (k in NoteKind.entries) put("exportKeys", k.name, NotesSql.exportKeys(k, listOf(1L, 2L)))
        put("bookInfo", "2", NotesSql.bookInfo(listOf(1L, 2L)))
        val path = System.getenv("NOTES_SQL_DUMP") ?: return
        val root = JSONObject()
        root.put("schema", JSONArray(LibrarySchema.CREATE_ALL))
        root.put("statements", dump)
        root.put("constants", JSONObject()
            .put("DRAWER_COUNTS", NotesSql.DRAWER_COUNTS).put("LOOKUP_FIND_RECENT", NotesSql.LOOKUP_FIND_RECENT)
            .put("LOOKUP_REFRESH", NotesSql.LOOKUP_REFRESH).put("LOOKUP_INSERT", NotesSql.LOOKUP_INSERT)
            .put("LOOKUP_SET_NOTE", NotesSql.LOOKUP_SET_NOTE).put("LOOKUP_COUNT", NotesSql.LOOKUP_COUNT)
            .put("LOOKUP_CLEAR", NotesSql.LOOKUP_CLEAR).put("BOOK_EXISTS", NotesSql.BOOK_EXISTS)
            .put("LOOKUP_DELETE", NotesSql.lookupDelete(2))
            .put("FULL_QUOTE", NotesSql.fullText(NoteKind.QUOTE)).put("FULL_BOOKMARK", NotesSql.fullText(NoteKind.BOOKMARK))
            .put("FULL_REVIEW", NotesSql.fullText(NoteKind.REVIEW)).put("FULL_LOOKUP", NotesSql.fullText(NoteKind.LOOKUP)))
        File(path).writeText(root.toString(1))
    }
}
