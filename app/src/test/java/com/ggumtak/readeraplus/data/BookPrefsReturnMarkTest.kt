package com.ggumtak.readeraplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U §3.3: `book_prefs.return_mark` storage rules (pure; the SQL itself runs in tools/check_sql.py). */
class BookPrefsReturnMarkTest {

    private fun placeholders(sql: String) = sql.count { it == '?' }

    @Test
    fun valuesAreTrimmedAndBlankClears() {
        assertNull(BookPrefs.returnMarkValue(null))
        assertNull(BookPrefs.returnMarkValue(""))
        assertNull(BookPrefs.returnMarkValue("   "))
        assertEquals("3:120:0.25", BookPrefs.returnMarkValue(" 3:120:0.25 "))
        assertNull(BookPrefs.returnMarkValue("x".repeat(BookPrefs.MAX_RETURN_MARK + 1)))
        assertEquals(BookPrefs.MAX_RETURN_MARK, BookPrefs.returnMarkValue("x".repeat(BookPrefs.MAX_RETURN_MARK))!!.length)
    }

    @Test
    fun statementsWriteOneColumnForAnExistingBook() {
        assertEquals("SELECT return_mark FROM book_prefs WHERE book_id = ?", LibrarySql.SELECT_RETURN_MARK)
        assertEquals(2, placeholders(LibrarySql.SET_PREFS_RETURN))
        assertEquals(2, placeholders(LibrarySql.INSERT_PREFS_RETURN))
        assertTrue(LibrarySql.INSERT_PREFS_RETURN.endsWith(" FROM books WHERE id = ?"))
        assertTrue(LibrarySql.SELECT_ALL_BOOK_PREFS.contains("episode_label, return_mark FROM book_prefs"))
    }

    @Test
    fun aRowWithAReturnMarkIsNeverPruned() {
        // Clearing the finish time (have_read off, reset) must not drop a row that still holds the return mark.
        assertTrue(LibrarySql.PRUNE_BOOK_PREFS.endsWith("AND return_mark IS NULL"))
        // "읽은 기록 초기화" clears it (Library.resetProgress runs CLEAR_RETURN_MARK, then PRUNE_BOOK_PREFS).
        assertEquals("UPDATE book_prefs SET return_mark = NULL WHERE book_id = ?", LibrarySql.CLEAR_RETURN_MARK)
    }
}
