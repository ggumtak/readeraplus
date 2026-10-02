package com.ggumtak.readeraplus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Schema v2 (R2): the new tables, the quotes.style column and the guarded upgrade plan. SQLite can't run in these
 * JVM tests: with `DATA_SCHEMA_DUMP=<file>` the statements are written out so they can be executed against a real
 * SQLite (fresh create, v1 → v2 upgrade with data, downgrade-and-back).
 */
class LibrarySchemaV3Test {

    private val v1Columns = mapOf(
        "quotes" to setOf("id", "book_id", "section", "start_offset", "end_offset", "quote_text", "note", "created_at"),
    )

    @Test
    fun versionIsThreeAndEveryStatementIsIdempotent() {
        assertEquals(3, LibrarySchema.DB_VERSION)
        for (sql in LibrarySchema.CREATE_ALL) {
            assertTrue(sql, sql.startsWith("CREATE TABLE IF NOT EXISTS ") || sql.startsWith("CREATE INDEX IF NOT EXISTS "))
        }
        assertTrue(LibrarySchema.CREATE_READING_LOG in LibrarySchema.CREATE_ALL)
        assertTrue(LibrarySchema.CREATE_BOOK_PREFS in LibrarySchema.CREATE_ALL)
        // The index needs its table: tables first, indexes after.
        val firstIndex = LibrarySchema.CREATE_ALL.indexOfFirst { it.startsWith("CREATE INDEX") }
        assertTrue(LibrarySchema.CREATE_ALL.drop(firstIndex).all { it.startsWith("CREATE INDEX") })
        assertTrue(LibrarySchema.CREATE_ALL.any { it.contains("ON reading_log(book_id)") })
    }

    @Test
    fun newTablesHaveTheSpecifiedShape() {
        val log = LibrarySchema.CREATE_READING_LOG
        for (c in listOf("day INTEGER NOT NULL", "book_id INTEGER NOT NULL", "seconds INTEGER NOT NULL DEFAULT 0",
            "pages INTEGER NOT NULL DEFAULT 0", "chars INTEGER NOT NULL DEFAULT 0")) assertTrue(c, log.contains(c))
        assertTrue(log.endsWith("PRIMARY KEY(day, book_id)) WITHOUT ROWID"))
        val prefs = LibrarySchema.CREATE_BOOK_PREFS
        for (c in listOf("book_id INTEGER PRIMARY KEY", "txt_override TEXT", "finished_at INTEGER NOT NULL DEFAULT 0",
            "episode_label TEXT")) assertTrue(c, prefs.contains(c))
        // A fresh file gets quotes.style from CREATE TABLE itself.
        assertTrue(LibrarySchema.CREATE_QUOTES.contains("style INTEGER NOT NULL DEFAULT 0"))
        assertEquals(balanced(log), true)
        assertEquals(balanced(prefs), true)
        assertEquals(balanced(LibrarySchema.CREATE_QUOTES), true)
    }

    @Test
    fun upgradeFromV1AddsTheColumnOnce() {
        val plan = LibrarySchema.upgradeStatements(1) { v1Columns[it].orEmpty() }
        assertEquals(10, plan.size)
        assertEquals(9, LibrarySchema.upgradeStatements(2) { emptySet() }.size)
        assertEquals(10, plan.distinct().size)
        assertTrue(LibrarySchema.ADD_QUOTE_STYLE in plan)
        assertEquals("ALTER TABLE quotes ADD COLUMN style INTEGER NOT NULL DEFAULT 0", LibrarySchema.ADD_QUOTE_STYLE)
        assertEquals(setOf("quotes", "bookmarks", "books", "book_prefs"), LibrarySchema.UPGRADE_TABLES.toSet())
    }

    @Test
    fun upgradeSkipsAColumnThatAlreadyExists() {
        // v2 → an older build (onDowngrade keeps the data; SQLite records version 1) → v2 again.
        val withStyle = v1Columns.mapValues { it.value + "style" }
        assertEquals(9, LibrarySchema.upgradeStatements(1) { withStyle[it].orEmpty() }.size)
        assertEquals(9, LibrarySchema.upgradeStatements(1) { setOf("STYLE") }.size)
        // Already v2 (or newer): nothing, and the columns aren't even asked for.
        assertEquals(emptyList<String>(), LibrarySchema.upgradeStatements(3) { error("not needed") })
        assertEquals(emptyList<String>(), LibrarySchema.upgradeStatements(3) { error("not needed") })
    }

    @Test
    fun noSqliteNewerThan318() {
        val all = LibrarySchema.CREATE_ALL + LibrarySchema.ADD_QUOTE_STYLE
        for (s in all) {
            val u = s.uppercase()
            assertFalse(s, u.contains("ON CONFLICT") || u.contains("RETURNING") || u.contains(" OVER (") ||
                u.contains("NULLS FIRST") || u.contains("NULLS LAST") || u.contains("IIF(") || u.contains("DROP "))
        }
    }

    @Test
    fun dumpForARealSqliteCheck() {
        val path = System.getenv("DATA_SCHEMA_DUMP") ?: return
        val o = JSONObject()
            .put("version", LibrarySchema.DB_VERSION)
            .put("createAll", JSONArray(LibrarySchema.CREATE_ALL))
            .put("upgradeFromV1", JSONArray(LibrarySchema.upgradeStatements(1) { v1Columns[it].orEmpty() }))
        File(path).writeText(o.toString(1))
    }

    private fun balanced(sql: String): Boolean {
        var depth = 0
        for (c in sql) {
            if (c == '(') depth++ else if (c == ')') depth--
            if (depth < 0) return false
        }
        return depth == 0
    }

    @Test fun v3ColumnsAndUpgradeIndexesAreSafe() {
        assertTrue(LibrarySchema.CREATE_BOOK_PREFS.contains("return_mark TEXT"))
        for (c in listOf("chapter","frac","sig")) { assertTrue(LibrarySchema.CREATE_QUOTES.contains(c)); assertTrue(LibrarySchema.CREATE_BOOKMARKS.contains(c)) }
        assertTrue(LibrarySchema.CREATE_BOOKS.contains("review_at")); assertTrue(LibrarySchema.CREATE_BOOKS.contains("missing_at"))
        val added=LibrarySchema.upgradeStatements(1) { emptySet() }.map { it.split(" ")[5] }.toSet()
        for (sql in LibrarySchema.CREATE_INDEXES) {
            val indexed=sql.substringAfter(" ON ").substringAfter('(').lowercase()
            for (col in added) assertFalse(sql,Regex("\\b"+col+"\\b").containsMatchIn(indexed))
        }
        val i=LibrarySchema.CREATE_ALL.indexOf(LibrarySchema.CREATE_LOOKUPS)
        assertTrue(i>=0); assertTrue(LibrarySchema.CREATE_ALL.drop(i+1).any { it.contains("ON lookups(") })
        assertEquals(3,LibrarySchema.UPGRADE_SWEEP.size)
    }

}
