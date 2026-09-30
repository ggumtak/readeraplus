package com.ggumtak.readeraplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The v2 statements of the reading log (T1-6), the per-book prefs (T1-9 / T1-2) and "다음 권" by series, plus the pure
 * parts of ReadingLog and BookPrefs. SQLite can't run here: SqlDumpTest dumps these constants for a real-SQLite run.
 */
class ReadingLogSqlTest {

    private fun placeholders(sql: String) = sql.count { it == '?' }

    private fun constants(): Map<String, String> = LibrarySql::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .onEach { it.isAccessible = true }
        .associate { it.name to it.get(null) as String }

    @Test
    fun theUpsertIsTheContractsStatementPair() {
        assertEquals(
            "UPDATE reading_log SET seconds = seconds + ?, pages = pages + ?, chars = chars + ? WHERE day = ? AND book_id = ?",
            LibrarySql.LOG_ADD,
        )
        assertEquals(5, placeholders(LibrarySql.LOG_INSERT))
        assertEquals(5, placeholders(LibrarySql.LOG_RESTORE))
        assertTrue(LibrarySql.LOG_RESTORE.contains("seconds = MAX(seconds, ?)"))
        // Rows are only ever created for a book that exists.
        for (sql in listOf(
            LibrarySql.LOG_INSERT, LibrarySql.INSERT_PREFS_TXT, LibrarySql.INSERT_PREFS_FINISHED,
            LibrarySql.INSERT_PREFS_EPISODE, LibrarySql.INSERT_PREFS_ROW,
        )) {
            assertTrue(sql, sql.startsWith("INSERT INTO ") && sql.endsWith(" FROM books WHERE id = ?"))
        }
    }

    @Test
    fun argumentCountsMatchTheirDocs() {
        val expected = mapOf(
            "SELECT_LOG_TOTALS" to 2, "SELECT_LOG_BOOK_TOTALS" to 3, "SELECT_LOG_DAYS" to 2, "SELECT_LOG_PER_BOOK" to 2,
            "SELECT_ALL_LOG" to 0, "DELETE_LOG_OF_BOOK" to 1, "SELECT_TXT_OVERRIDE" to 1, "SELECT_FINISHED_AT" to 1,
            "SELECT_FINISHED_BETWEEN" to 2, "SELECT_BOOK_PREFS" to 1, "SELECT_ALL_BOOK_PREFS" to 0, "SET_PREFS_TXT" to 2,
            "INSERT_PREFS_TXT" to 2, "SET_PREFS_FINISHED" to 2, "INSERT_PREFS_FINISHED" to 2, "SET_PREFS_EPISODE" to 2,
            "INSERT_PREFS_EPISODE" to 2, "SET_PREFS_ROW" to 4, "INSERT_PREFS_ROW" to 4, "CLEAR_FINISHED_AT" to 1,
            "PRUNE_BOOK_PREFS" to 1, "DELETE_BOOK_PREFS_OF_BOOK" to 1, "SELECT_SERIES_NEXT" to 3,
        )
        val all = constants()
        for ((name, n) in expected) assertEquals(name, n, placeholders(all.getValue(name)))
    }

    @Test
    fun onlySqlite318() {
        val v2 = constants().filterKeys {
            it.startsWith("LOG_") || it.contains("_LOG") || it.contains("PREFS") || it.contains("FINISHED") ||
                it == "SELECT_SERIES_NEXT"
        }
        assertTrue(v2.size >= 20)
        for ((name, sql) in v2) {
            val u = sql.uppercase()
            assertFalse(name, u.contains("ON CONFLICT") || u.contains("RETURNING") || u.contains(" OVER (") ||
                u.contains("NULLS FIRST") || u.contains("NULLS LAST") || u.contains("IIF(") || u.contains("UPSERT"))
        }
    }

    @Test
    fun everyTableOfABookIsClearedWithIt() {
        val all = constants().values.toSet()
        val tables = LibrarySchema.CREATE_ALL.filter { it.startsWith("CREATE TABLE") && it.contains("book_id INTEGER") }
            .map { it.removePrefix("CREATE TABLE IF NOT EXISTS ").substringBefore('(') }
        assertEquals(
            setOf("bookmarks", "quotes", "book_collections", "page_counts", "reading_log", "book_prefs"),
            tables.toSet(),
        )
        for (t in tables) assertTrue(t, "DELETE FROM $t WHERE book_id = ?" in all)
        // The scanner keeps books with per-book settings or reading history even in an excluded folder.
        assertTrue(LibrarySql.SELECT_IDS_WITH_USER_DATA.contains("FROM book_prefs"))
        assertTrue(LibrarySql.SELECT_IDS_WITH_USER_DATA.contains("FROM reading_log"))
    }

    @Test
    fun finishTimesCountOnlyForFinishedBooks() {
        assertTrue(LibrarySql.SELECT_FINISHED_AT.contains("b.have_read = 1"))
        assertTrue(LibrarySql.SELECT_FINISHED_BETWEEN.contains("b.have_read = 1"))
        assertTrue(LibrarySql.SELECT_FINISHED_BETWEEN.contains("b.trashed = 0"))
        assertTrue(LibrarySql.SELECT_FINISHED_BETWEEN.contains("ORDER BY p.finished_at DESC"))
        assertTrue(LibrarySql.PRUNE_BOOK_PREFS.contains("txt_override IS NULL AND finished_at = 0 AND episode_label IS NULL"))
    }

    @Test
    fun deltasAreClampedAndEmptyOnesDropped() {
        assertNull(ReadingLog.Delta.of(0, 0, 0))
        assertNull(ReadingLog.Delta.of(-5, -1, -100))
        val d = ReadingLog.Delta.of(90, -1, 1200)!!
        assertEquals(90L, d.seconds)
        assertEquals(0, d.pages)
        assertEquals(1200L, d.chars)
        assertEquals(24L * 3600, ReadingLog.Delta.of(Long.MAX_VALUE, 1, 1)!!.seconds)
        assertEquals(0L, ReadingLog.Delta.of(0, 1, 0)!!.seconds)
    }

    @Test
    fun daysMustLookLikeDates() {
        assertTrue(ReadingLog.isDay(20260930))
        assertTrue(ReadingLog.isDay(19700101))
        assertTrue(ReadingLog.isDay(ReadingLog.day(System.currentTimeMillis())))
        for (bad in listOf(0, -1, 2026093, 20261301, 20260900, 20260932, 19691231, 100000101)) {
            assertFalse("$bad", ReadingLog.isDay(bad))
        }
    }

    @Test
    fun cpmTiers() {
        var asked = 0
        val all = { asked++; 3600L to 30_000L } // 500 cpm over an hour
        // This book has enough: its own speed, the all-books query is never made.
        assertEquals(720, ReadingLog.cpmOf(600L to 7200L, all))
        assertEquals(0, asked)
        // Too little for the book tier (under 10 minutes): all books.
        assertEquals(500, ReadingLog.cpmOf(599L to 7200L, all))
        assertEquals(1, asked)
        // No book asked: straight to the second tier.
        assertEquals(500, ReadingLog.cpmOf(null, all))
        // Under 30 minutes over all books: unknown (the caller uses DEFAULT_CPM).
        assertNull(ReadingLog.cpmOf(null) { 1799L to 30_000L })
        assertNull(ReadingLog.cpmOf(0L to 0L) { 0L to 0L })
        // Time without characters (TTS with nothing counted) says nothing about speed.
        assertNull(ReadingLog.cpmOf(3600L to 0L) { 3600L to 0L })
    }

    @Test
    fun overrideTextIsNullWhenEmpty() {
        assertNull(BookPrefs.overrideJson(null))
        assertNull(BookPrefs.overrideJson(TxtOverride()))
        val o = TxtOverride(blankLines = 2, replaceRules = "광고 =>")
        assertEquals(o, TxtOverride.fromJson(BookPrefs.overrideJson(o)))
    }
}
