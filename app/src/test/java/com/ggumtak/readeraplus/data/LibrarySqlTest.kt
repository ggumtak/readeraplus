package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.settings.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySqlTest {

    private fun placeholders(sql: String): Int = sql.count { it == '?' }

    @Test
    fun escapeLikeEscapesWildcardsAndEscapeChar() {
        assertEquals("abc", LibrarySql.escapeLike("abc"))
        assertEquals("100\\%", LibrarySql.escapeLike("100%"))
        assertEquals("a\\_b", LibrarySql.escapeLike("a_b"))
        assertEquals("c:\\\\x", LibrarySql.escapeLike("c:\\x"))
        assertEquals("\\%\\_\\\\", LibrarySql.escapeLike("%_\\"))
        assertEquals("한글 제목", LibrarySql.escapeLike("한글 제목"))
    }

    @Test
    fun searchTokensSplitTrimDedupeAndNfc() {
        assertEquals(emptyList<String>(), LibrarySql.searchTokens("   "))
        assertEquals(listOf("해리", "포터"), LibrarySql.searchTokens("  해리   포터 해리 "))
        // Decomposed jamo (NFD, e.g. from macOS zips) match stored NFC titles.
        val nfd = java.text.Normalizer.normalize("한글", java.text.Normalizer.Form.NFD)
        assertEquals(listOf("한글"), LibrarySql.searchTokens(nfd))
        val many = (1..20).joinToString(" ") { "w$it" }
        assertEquals(8, LibrarySql.searchTokens(many).size)
    }

    @Test
    fun everyShelfSortComboHasMatchingArgs() {
        for (shelf in Shelf.entries) for (sort in LibrarySort.entries) {
            for (group in listOf(null, "g")) for (query in listOf("", "a b", "50%_off")) {
                val q = LibrarySql.booksQuery(LibraryQuery(shelf, group, query), sort)
                assertEquals("$shelf/$sort/$group/$query", placeholders(q.sql), q.args.size)
                assertTrue(q.sql.startsWith("SELECT " + LibrarySql.BOOK_COLUMNS + " FROM books WHERE "))
                assertTrue(q.sql.contains(" ORDER BY "))
                if (shelf != Shelf.TRASH) assertTrue(q.sql.contains("trashed = 0"))
                else assertTrue(q.sql.contains("trashed = 1"))
            }
        }
    }

    @Test
    fun countQueriesMirrorBookQueries() {
        for (shelf in Shelf.entries) for (group in listOf(null, "g")) for (query in listOf("", "x y")) {
            val lq = LibraryQuery(shelf, group, query)
            val c = LibrarySql.countQuery(lq)
            val b = LibrarySql.booksQuery(lq, LibrarySort.TITLE)
            assertTrue(c.sql.startsWith("SELECT COUNT(*) FROM books WHERE "))
            assertFalse(c.sql.contains("ORDER BY"))
            assertEquals(b.args.toList(), c.args.toList())
            assertEquals(placeholders(c.sql), c.args.size)
        }
        val sc = LibrarySql.shelfCountsQuery()
        assertEquals(0, placeholders(sc))
        assertEquals(Shelf.entries.size, LibrarySql.COUNTED_SHELVES.size)
        // One column per shelf: top-level commas outside parentheses.
        var depth = 0
        var cols = 1
        for (ch in sc.substringAfter("SELECT ").substringBeforeLast(" FROM books")) {
            if (ch == '(') depth++ else if (ch == ')') depth-- else if (ch == ',' && depth == 0) cols++
        }
        assertEquals(Shelf.entries.size, cols)
    }

    @Test
    fun columnListMatchesCount() {
        assertEquals(LibrarySql.BOOK_COLUMN_COUNT, LibrarySql.BOOK_COLUMNS.split(',').size)
    }

    @Test
    fun searchUsesEscapedPatternsForAllThreeFields() {
        val q = LibrarySql.booksQuery(LibraryQuery(Shelf.ALL, null, "100% 소설"), LibrarySort.TITLE)
        assertEquals(listOf("%100\\%%", "%100\\%%", "%100\\%%", "%소설%", "%소설%", "%소설%"), q.args.toList())
        assertEquals(6, Regex("LIKE \\? ESCAPE '\\\\'").findAll(q.sql).count())
        assertTrue(q.sql.contains("title LIKE ?"))
        assertTrue(q.sql.contains("author LIKE ?"))
        assertTrue(q.sql.contains("file_name LIKE ?"))
    }

    @Test
    fun groupFilters() {
        val a = LibrarySql.booksQuery(LibraryQuery(Shelf.AUTHORS, ""), LibrarySort.TITLE)
        assertTrue(a.sql.contains("author = ?"))
        assertEquals(listOf(""), a.args.toList())

        val f = LibrarySql.booksQuery(LibraryQuery(Shelf.FORMATS, "epub"), LibrarySort.TITLE)
        assertEquals(listOf("EPUB"), f.args.toList())
        assertEquals("TXT", LibrarySql.groupArg(Shelf.FORMATS, "TXT"))
        assertEquals("weird", LibrarySql.groupArg(Shelf.FORMATS, "weird"))

        val c = LibrarySql.booksQuery(LibraryQuery(Shelf.COLLECTIONS, " 12 "), LibrarySort.RECENT)
        assertTrue(c.sql.contains("collection_id = ?"))
        assertEquals(listOf("12"), c.args.toList())

        val d = LibrarySql.booksQuery(LibraryQuery(Shelf.FOLDERS, "/storage/emulated/0/Books"), LibrarySort.SIZE)
        assertTrue(d.sql.contains("folder = ?"))

        // Shelves without groups ignore a stray group.
        val all = LibrarySql.booksQuery(LibraryQuery(Shelf.ALL, "x"), LibrarySort.RECENT)
        assertEquals(0, all.args.size)
        assertNull(LibrarySql.groupWhere(Shelf.DOWNLOADS))
    }

    @Test
    fun readingNowIsAlwaysRecentFirst() {
        for (sort in LibrarySort.entries) {
            assertEquals(
                LibrarySql.orderBy(Shelf.ALL, LibrarySort.RECENT),
                LibrarySql.orderBy(Shelf.READING_NOW, sort),
            )
        }
        assertTrue(LibrarySql.shelfWhere(Shelf.READING_NOW).contains("last_read_at > 0"))
        assertTrue(LibrarySql.shelfWhere(Shelf.READING_NOW).contains("have_read = 0"))
    }

    @Test
    fun groupQueriesOnlyForGroupedShelves() {
        val grouped = setOf(Shelf.AUTHORS, Shelf.SERIES, Shelf.COLLECTIONS, Shelf.FORMATS, Shelf.FOLDERS)
        for (s in Shelf.entries) {
            val sql = LibrarySql.groupsQuery(s)
            if (s in grouped) {
                assertTrue(s.name, sql != null && sql.startsWith("SELECT ") && placeholders(sql) == 0)
            } else {
                assertNull(s.name, sql)
            }
        }
    }

    @Test
    fun groupRowLabels() {
        assertEquals(ShelfGroup("", "작가 미상", 3), LibrarySql.groupRow(Shelf.AUTHORS, "", null, 3))
        assertEquals(ShelfGroup("", "작가 미상", 1), LibrarySql.groupRow(Shelf.AUTHORS, null, null, 1))
        assertEquals(ShelfGroup("김작가", "김작가", 2), LibrarySql.groupRow(Shelf.AUTHORS, "김작가", null, 2))
        assertEquals(ShelfGroup("EPUB", BookFormat.EPUB.label, 5), LibrarySql.groupRow(Shelf.FORMATS, "EPUB", null, 5))
        assertEquals(ShelfGroup("7", "좋아하는 책", 0), LibrarySql.groupRow(Shelf.COLLECTIONS, "7", "좋아하는 책", 0))
        assertEquals(
            ShelfGroup("/storage/emulated/0/Books", "/storage/emulated/0/Books", 4),
            LibrarySql.groupRow(Shelf.FOLDERS, "/storage/emulated/0/Books", null, 4),
        )
    }

    @Test
    fun groupsSortNaturallyWithUnknownAuthorLast() {
        val g = listOf(
            ShelfGroup("", "작가 미상", 1),
            ShelfGroup("나작가", "나작가", 1),
            ShelfGroup("Alice", "Alice", 1),
            ShelfGroup("가작가", "가작가", 1),
        )
        assertEquals(listOf("Alice", "가작가", "나작가", "작가 미상"), LibrarySql.sortGroups(Shelf.AUTHORS, g).map { it.label })
        val s = listOf(ShelfGroup("a10", "시리즈 10", 1), ShelfGroup("a2", "시리즈 2", 1), ShelfGroup("a1", "시리즈 1", 1))
        assertEquals(listOf("시리즈 1", "시리즈 2", "시리즈 10"), LibrarySql.sortGroups(Shelf.SERIES, s).map { it.label })
    }

    private fun book(id: Long, title: String, author: String = "", seriesIndex: Float? = null) = Book(
        id = id, path = "/b/$id.txt", fileName = "$id.txt", title = title, author = author,
        series = if (seriesIndex != null) "S" else null, seriesIndex = seriesIndex, format = BookFormat.TXT,
        sizeBytes = 10, modifiedAt = 0, addedAt = 0,
    )

    @Test
    fun sortBooksNaturalTitles() {
        val books = listOf(book(1, "소설 10권"), book(2, "소설 2권"), book(3, "소설 1권"), book(4, "Apple"))
        val sorted = LibrarySql.sortBooks(books, LibraryQuery(Shelf.ALL), LibrarySort.TITLE)
        assertEquals(listOf("Apple", "소설 1권", "소설 2권", "소설 10권"), sorted.map { it.title })
        // Other sorts keep SQL order.
        assertEquals(books, LibrarySql.sortBooks(books, LibraryQuery(Shelf.ALL), LibrarySort.SIZE))
        assertEquals(books, LibrarySql.sortBooks(books, LibraryQuery(Shelf.READING_NOW), LibrarySort.TITLE))
    }

    @Test
    fun sortBooksByAuthorUnknownLast() {
        val books = listOf(book(1, "b", ""), book(2, "a", "나"), book(3, "c", "가"), book(4, "a", "가"))
        val sorted = LibrarySql.sortBooks(books, LibraryQuery(Shelf.ALL), LibrarySort.AUTHOR)
        assertEquals(listOf(4L, 3L, 2L, 1L), sorted.map { it.id })
    }

    @Test
    fun seriesGroupOrdersBySeriesIndex() {
        val books = listOf(book(1, "Z", seriesIndex = 3f), book(2, "Y", seriesIndex = 1f), book(3, "X", seriesIndex = null), book(4, "W", seriesIndex = 2f))
        val sorted = LibrarySql.sortBooks(books, LibraryQuery(Shelf.SERIES, "S"), LibrarySort.TITLE)
        assertEquals(listOf(2L, 4L, 1L, 3L), sorted.map { it.id })
        // Without a group the series shelf sorts by title.
        val flat = LibrarySql.sortBooks(books, LibraryQuery(Shelf.SERIES), LibrarySort.TITLE)
        assertEquals(listOf("W", "X", "Y", "Z"), flat.map { it.title })
    }

    @Test
    fun updatePositionIsNewerWinsAndBindOrderMatchesTheCallers() {
        val sql = LibrarySql.UPDATE_POSITION
        // One UPDATE; the guard is in its WHERE, after the id, so the binds run
        // pos_section, pos_offset, progress, last_read_at, id, read time (Library.savePosition, Backup restore).
        assertTrue(sql.startsWith("UPDATE books SET pos_section = ?, pos_offset = ?, progress = ?, last_read_at = ? "))
        assertTrue(sql, sql.endsWith("WHERE id = ? AND last_read_at <= ?"))
        assertEquals(4, placeholders(sql.substringBefore(" WHERE ")))
        assertEquals(2, placeholders(sql.substringAfter(" WHERE ")))
        assertTrue(sql.indexOf("id = ?") < sql.indexOf("last_read_at <= ?"))
    }

    @Test
    fun statementsHaveExpectedPlaceholderCounts() {
        assertEquals(12, placeholders(LibrarySql.INSERT_BOOK))
        assertEquals(6, placeholders(LibrarySql.UPDATE_BOOK_FILE))
        assertEquals(6, placeholders(LibrarySql.UPDATE_BOOK_META))
        assertEquals(5, placeholders(LibrarySql.UPDATE_BOOK_META_USER))
        assertEquals(6, placeholders(LibrarySql.UPDATE_POSITION))
        assertEquals(9, placeholders(LibrarySql.RESTORE_FLAGS))
        assertEquals(9, placeholders(LibrarySql.INSERT_BOOKMARK))
        assertEquals(11, placeholders(LibrarySql.INSERT_QUOTE))
        assertEquals(4, placeholders(LibrarySql.REPLACE_PAGE_COUNTS))
        assertEquals(2, placeholders(LibrarySql.PRUNE_PAGE_COUNTS))
        assertTrue(LibrarySql.PRUNE_PAGE_COUNTS.contains("LIMIT ${LibrarySql.MAX_PAGE_COUNT_KEYS}"))
        // No SQLite ≥ 3.24 syntax (minSdk 26 ships 3.18).
        val all = LibrarySchema.CREATE_ALL + sqlConstants()
        for (s in all) {
            val u = s.uppercase()
            assertFalse(s, u.contains("ON CONFLICT") || u.contains("RETURNING") || u.contains(" OVER (") ||
                u.contains("NULLS FIRST") || u.contains("NULLS LAST") || u.contains("IIF("))
        }
    }

    private fun sqlConstants(): List<String> = listOf(
        LibrarySql.SELECT_BOOK_BY_ID, LibrarySql.SELECT_BOOK_BY_PATH, LibrarySql.SELECT_LAST_OPENED,
        LibrarySql.INSERT_BOOK, LibrarySql.UPDATE_BOOK_FILE, LibrarySql.UPDATE_BOOK_META, LibrarySql.RESTORE_FLAGS,
        LibrarySql.SELECT_COLLECTIONS, LibrarySql.PRUNE_PAGE_COUNTS, LibrarySql.REPLACE_PAGE_COUNTS,
        LibrarySql.INSERT_MEMBERSHIP, LibrarySql.INSERT_IGNORED,
        // v3 (N §5.2)
        LibrarySql.INSERT_QUOTE, LibrarySql.INSERT_BOOKMARK, LibrarySql.UPDATE_QUOTE_STYLE, LibrarySql.UPDATE_QUOTE_PLACE,
        LibrarySql.UPDATE_BOOKMARK_PLACE, LibrarySql.SET_MISSING, LibrarySql.CLEAR_MISSING, LibrarySql.UNTRASH,
        LibrarySql.SET_REVIEW, LibrarySql.CLEAR_REVIEW, LibrarySql.RESTORE_REVIEW, LibrarySql.SELECT_IDS_WITH_NOTES,
        LibrarySql.SELECT_MOVE_CANDIDATES, LibrarySql.SELECT_RETURN_MARK, LibrarySql.SET_PREFS_RETURN,
        LibrarySql.INSERT_PREFS_RETURN, LibrarySql.CLEAR_RETURN_MARK, LibrarySql.PRUNE_BOOK_PREFS,
    )
}
