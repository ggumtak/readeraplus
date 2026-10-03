package com.ggumtak.readeraplus.data

import android.database.MatrixCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N §5.1 / §5.2: the notes write statements, their argument rules and the v3 row mappings (pure, no SQLite). */
class LibraryNotesWritesTest {

    private fun placeholders(sql: String) = sql.count { it == '?' }

    @Test
    fun stylesAreClampedToTheStoredRange() {
        assertEquals(0, NoteWrites.style(-3))
        assertEquals(0, NoteWrites.style(0))
        assertEquals(5, NoteWrites.style(5))
        assertEquals(DataLimits.QUOTE_STYLE_MAX, NoteWrites.style(DataLimits.QUOTE_STYLE_MAX))
        assertEquals(DataLimits.QUOTE_STYLE_MAX, NoteWrites.style(99))
        assertEquals(0, BookRows.style(16))
        assertEquals(0, BookRows.style(-1))
        assertEquals(3, BookRows.style(3))
    }

    @Test
    fun placesAreStoredCleanOrUnknown() {
        assertEquals(NotePlace.UNKNOWN, NoteWrites.place(null))
        val p = NoteWrites.place(NotePlace("  제1장\n  시작  ", 0.25f, " sig:123 "))
        assertEquals("제1장 시작", p.chapter)
        assertEquals(0.25f, p.frac, 0f)
        assertEquals("sig:123", p.sig)
        assertEquals(-1f, NoteWrites.place(NotePlace("", Float.NaN, "")).frac, 0f)
        assertEquals(-1f, NoteWrites.place(NotePlace("", -0.5f, "")).frac, 0f)
        assertEquals(1f, NoteWrites.place(NotePlace("", 1.7f, "")).frac, 0f)
        assertEquals(0f, NoteWrites.place(NotePlace("", 0f, "")).frac, 0f)
        assertEquals(DataLimits.CHAPTER, NoteWrites.place(NotePlace("가".repeat(500), 0.1f, "")).chapter.length)
        assertEquals(NoteWrites.SIG_MAX, NoteWrites.place(NotePlace("", 0.1f, "x".repeat(400))).sig.length)
    }

    @Test
    fun backfillSkipsUnknownPlacesAndBadIds() {
        val out = NoteWrites.backfill(
            linkedMapOf(
                1L to NotePlace("1장", 0.5f, "ignored"),
                2L to NotePlace("2장", -1f, ""),
                0L to NotePlace("x", 0.3f, ""),
                3L to NotePlace("", Float.NaN, ""),
                4L to NotePlace("", 0f, ""),
            ),
        )
        assertEquals(listOf(1L, 4L), out.map { it.first })
        assertEquals("1장", out[0].second.chapter)
        assertTrue(NoteWrites.backfill(emptyMap()).isEmpty())
    }

    @Test
    fun writeStatementsMatchTheSpec() {
        assertEquals(11, placeholders(LibrarySql.INSERT_QUOTE))
        assertEquals(9, placeholders(LibrarySql.INSERT_BOOKMARK))
        assertTrue(LibrarySql.INSERT_QUOTE.contains("created_at, style, chapter, frac, sig)"))
        assertTrue(LibrarySql.INSERT_BOOKMARK.contains("created_at, chapter, frac, sig)"))
        // Unbound (NULL) style / place args store the defaults instead of failing NOT NULL.
        assertTrue(LibrarySql.INSERT_QUOTE.contains("IFNULL(?, 0), IFNULL(?, ''), IFNULL(?, -1), IFNULL(?, '')"))
        assertTrue(LibrarySql.INSERT_BOOKMARK.contains("IFNULL(?, ''), IFNULL(?, -1), IFNULL(?, '')"))
        assertEquals("UPDATE quotes SET style = ? WHERE id = ?", LibrarySql.UPDATE_QUOTE_STYLE)
        assertEquals("UPDATE quotes SET chapter = ?, frac = ? WHERE id = ? AND frac < 0", LibrarySql.UPDATE_QUOTE_PLACE)
        assertEquals("UPDATE bookmarks SET chapter = ?, frac = ? WHERE id = ? AND frac < 0", LibrarySql.UPDATE_BOOKMARK_PLACE)
        assertEquals("UPDATE books SET review = ?, review_at = ? WHERE id = ?", LibrarySql.SET_REVIEW)
        assertEquals("UPDATE books SET review = '', review_at = 0 WHERE id = ?", LibrarySql.CLEAR_REVIEW)
        assertEquals("UPDATE books SET trashed = 0, missing_at = 0 WHERE id = ?", LibrarySql.UNTRASH)
        assertEquals("DELETE FROM lookups WHERE book_id = ?", LibrarySql.DELETE_LOOKUPS_OF_BOOK)
        assertTrue(LibrarySql.SELECT_IDS_WITH_USER_DATA.endsWith("UNION SELECT book_id FROM lookups"))
        for (sql in listOf(LibrarySql.SELECT_QUOTES, LibrarySql.SELECT_ALL_QUOTES)) {
            assertTrue(sql, sql.contains("created_at, style, chapter, frac, sig FROM quotes"))
            assertFalse(sql, sql.contains("SELECT *"))
        }
        for (sql in listOf(LibrarySql.SELECT_BOOKMARKS, LibrarySql.SELECT_ALL_BOOKMARKS)) {
            assertTrue(sql, sql.contains("note, chapter, frac, sig FROM bookmarks"))
        }
        assertTrue(LibrarySql.BOOK_COLUMNS.endsWith(", missing_at"))
        assertEquals(LibrarySql.BOOK_COLUMN_COUNT, LibrarySql.BOOK_COLUMNS.split(',').size)
    }

    private val quoteColumns = arrayOf(
        "id", "book_id", "section", "start_offset", "end_offset", "quote_text", "note", "created_at",
        "style", "chapter", "frac", "sig",
    )

    @Test
    fun quoteRowsMapStyleAndPlace() {
        val c = MatrixCursor(quoteColumns).apply {
            addRow(arrayOf<Any?>(5L, 2L, 3L, 10L, 20L, "인용", "메모", 1000L, 4L, "3장", 0.5, "e:99"))
            addRow(arrayOf<Any?>(6L, 2L, 0L, 0L, 1L, null, null, 0L, 42L, null, -1.0, null))
        }
        c.moveToFirst()
        val q = BookRows.quote(c)
        assertEquals(5L, q.id)
        assertEquals(4, q.style)
        assertEquals("3장", q.chapter)
        assertEquals(0.5f, q.frac, 0f)
        assertEquals("e:99", q.sig)
        c.moveToNext()
        val legacy = BookRows.quote(c)
        assertEquals(0, legacy.style)
        assertEquals("", legacy.chapter)
        assertEquals(-1f, legacy.frac, 0f)
        assertEquals("", legacy.sig)
        assertEquals("", legacy.text)
        // An R2-shaped select (8 columns) still maps, with the defaults.
        val old = MatrixCursor(quoteColumns.copyOf(8).requireNoNulls()).apply {
            addRow(arrayOf<Any?>(7L, 2L, 0L, 0L, 1L, "t", "", 5L))
            moveToFirst()
        }
        val q8 = BookRows.quote(old)
        assertEquals(0, q8.style)
        assertEquals(-1f, q8.frac, 0f)
    }

    @Test
    fun bookmarkRowsMapPlace() {
        val cols = arrayOf("id", "book_id", "section", "char_offset", "snippet", "created_at", "note", "chapter", "frac", "sig")
        val c = MatrixCursor(cols).apply {
            addRow(arrayOf<Any?>(1L, 2L, 3L, 4L, "s", 9L, "n", "끝", 1.0, "sig:5"))
            moveToFirst()
        }
        val b = BookRows.bookmark(c)
        assertEquals("끝", b.chapter)
        assertEquals(1f, b.frac, 0f)
        assertEquals("sig:5", b.sig)
        assertEquals("n", b.note)
        val old = MatrixCursor(cols.copyOf(7).requireNoNulls()).apply {
            addRow(arrayOf<Any?>(1L, 2L, 3L, 4L, "s", 9L, "n"))
            moveToFirst()
        }
        assertEquals(-1f, BookRows.bookmark(old).frac, 0f)
    }

    @Test
    fun bookRowsReadMissingAtByName() {
        val cols = LibrarySql.BOOK_COLUMNS.split(',').map { it.trim() }.toTypedArray()
        val row = arrayOfNulls<Any?>(cols.size)
        row[0] = 1L; row[1] = "/a.txt"; row[2] = "a.txt"; row[7] = "TXT"
        for (i in 8 until cols.size) if (row[i] == null && cols[i] != "review" && cols[i] != "encoding") row[i] = 0L
        row[cols.indexOf("missing_at")] = 1234L
        val c = MatrixCursor(cols).apply { addRow(row); moveToFirst() }
        assertEquals(1234L, BookRows.book(c).missingAt)
        // The backup select appends meta_locked after BOOK_COLUMNS: index BOOK_COLUMN_COUNT.
        assertTrue(LibrarySql.SELECT_ALL_BOOKS_FOR_BACKUP.contains("${LibrarySql.BOOK_COLUMNS}, meta_locked, review_at FROM books"))
    }

    @Test
    fun upgradeTailRunsTheSweepAfterTheAltersBelowV3() {
        val noColumns: (String) -> Set<String> = { emptySet() }
        val v1 = LibraryDb.upgradeTail(1, noColumns)
        val v2 = LibraryDb.upgradeTail(2, noColumns)
        val v3 = LibraryDb.upgradeTail(3) { error("not asked") }
        val sweep = LibrarySchema.UPGRADE_SWEEP + LibraryDb.NOTES_SWEEP
        assertEquals(sweep, v2.takeLast(sweep.size))
        assertEquals(sweep, v1.takeLast(sweep.size))
        assertTrue(v1.contains(LibrarySchema.ADD_QUOTE_STYLE))
        assertFalse(v2.contains(LibrarySchema.ADD_QUOTE_STYLE))
        assertEquals(10 + sweep.size, v1.size)
        assertEquals(9 + sweep.size, v2.size)
        assertTrue(v3.isEmpty())
        // Constant cost: every statement is an ALTER or a set-based sweep, and each table is asked once.
        val asked = HashMap<String, Int>()
        LibraryDb.upgradeTail(2) { t -> asked[t] = (asked[t] ?: 0) + 1; emptySet() }
        assertTrue(asked.values.all { it == 1 })
        for (s in v1) assertTrue(s, s.startsWith("ALTER TABLE ") || s.startsWith("DELETE FROM "))
        // A file that already has the v3 columns (v3 → "v2 build" → v3) gets no ALTER, only the sweep.
        val all = setOf("style", "chapter", "frac", "sig", "review_at", "missing_at", "return_mark")
        assertEquals(sweep, LibraryDb.upgradeTail(2) { all })
    }
}
