package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.settings.LibrarySort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Collects every SQL statement of the data module. When the environment variable `DATA_SQL_DUMP` names a
 * file, the statements are written there as JSON so they can be executed against a real SQLite off-device
 * (SQLite itself can't run in these JVM tests).
 */
class SqlDumpTest {

    private fun constants(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (f in LibrarySql::class.java.declaredFields) {
            if (!Modifier.isStatic(f.modifiers) || f.type != String::class.java) continue
            f.isAccessible = true
            val v = f.get(null) as? String ?: continue
            val u = v.trimStart().uppercase()
            if (u.startsWith("SELECT") || u.startsWith("INSERT") || u.startsWith("UPDATE") ||
                u.startsWith("DELETE") || u.startsWith("PRAGMA")
            ) {
                out[f.name] = v
            }
        }
        return out
    }

    private companion object {
        val CORE_STATEMENTS = listOf(
            "INSERT_QUOTE", "INSERT_BOOKMARK", "SELECT_QUOTES", "SELECT_ALL_QUOTES", "SELECT_BOOKMARKS",
            "SELECT_ALL_BOOKMARKS", "UPDATE_QUOTE_STYLE", "UPDATE_QUOTE_PLACE", "UPDATE_BOOKMARK_PLACE", "SET_REVIEW",
            "CLEAR_REVIEW", "RESTORE_REVIEW", "UNTRASH", "SET_MISSING", "CLEAR_MISSING", "SELECT_IDS_WITH_NOTES", "SELECT_IDS_KEPT_WHEN_MISSING", "SELECT_IDS_WITH_USER_DATA",
            "SELECT_MOVE_CANDIDATES", "SELECT_SCAN_STATE", "DELETE_LOOKUPS_OF_BOOK", "SELECT_RETURN_MARK",
            "SET_PREFS_RETURN", "INSERT_PREFS_RETURN", "CLEAR_RETURN_MARK", "PRUNE_BOOK_PREFS",
        )
    }

    @Test
    fun collectAndOptionallyDump() {
        val consts = constants()
        assertTrue("found ${consts.size}", consts.size >= 50)
        val root = JSONObject()
        root.put("schema", JSONArray(LibrarySchema.CREATE_ALL))
        root.put("constants", JSONObject(consts as Map<*, *>))
        val books = JSONArray()
        for (shelf in Shelf.entries) for (sort in LibrarySort.entries) {
            for (group in listOf(null, "", "EPUB", "1", "/storage/emulated/0/Books", "김작가", "계절")) {
                for (query in listOf("", "여름", "50%_x", "a b")) {
                    val q = LibrarySql.booksQuery(LibraryQuery(shelf, group, query), sort)
                    books.put(
                        JSONObject().put("shelf", shelf.name).put("sort", sort.name).put("group", group ?: JSONObject.NULL)
                            .put("query", query).put("sql", q.sql).put("args", JSONArray(q.args.toList())),
                    )
                }
            }
        }
        root.put("books", books)
        val groups = JSONObject()
        for (s in Shelf.entries) LibrarySql.groupsQuery(s)?.let { groups.put(s.name, it) }
        root.put("groups", groups)
        root.put("shelfCounts", LibrarySql.shelfCountsQuery())
        root.put("shelfOrder", JSONArray(LibrarySql.COUNTED_SHELVES.map { it.name }))
        val counts = JSONArray()
        for (shelf in Shelf.entries) for (group in listOf(null, "EPUB", "1", "김작가", "계절")) {
            val q = LibrarySql.countQuery(LibraryQuery(shelf, group, ""))
            counts.put(
                JSONObject().put("shelf", shelf.name).put("group", group ?: JSONObject.NULL)
                    .put("sql", q.sql).put("args", JSONArray(q.args.toList())),
            )
        }
        root.put("counts", counts)
        // N §16: the data core's notes statements (writes, place backfill, scanner trash / revive, return mark) and the
        // upgrade order; tools/check_sql.py prepares every one. The key "notes" is kept for the hub's NotesSql samples
        // (every tab × order × filter, added with DA-N's NotesSql), whose plans check_sql.py asserts.
        val core = JSONObject()
        for (name in CORE_STATEMENTS) {
            val sql = consts[name]
            assertTrue(name, sql != null)
            core.put(name, sql)
        }
        root.put("core", core)
        val upgrade = JSONObject()
        for (v in 1..LibrarySchema.DB_VERSION) {
            upgrade.put("v$v", JSONArray(LibraryDb.upgradeTail(v) { emptySet() }))
        }
        upgrade.put("sweep", JSONArray(LibrarySchema.UPGRADE_SWEEP))
        root.put("upgrade", upgrade)
        val path = System.getenv("DATA_SQL_DUMP") ?: return
        File(path).writeText(root.toString(1))
    }
}
