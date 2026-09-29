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
        val path = System.getenv("DATA_SQL_DUMP") ?: return
        File(path).writeText(root.toString(1))
    }
}
