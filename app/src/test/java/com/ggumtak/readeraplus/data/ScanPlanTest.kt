package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPlanTest {

    private fun known(id: Long, path: String, size: Long = 2000, mtime: Long = 1, trashed: Boolean = false) =
        FileScanner.Known(id, path, size, mtime, trashed, path.substringAfterLast('/'))

    private fun found(path: String, size: Long = 2000, mtime: Long = 1) =
        FileScanner.Found(path, path.substringAfterLast('/'), BookFormat.TXT, size, mtime)

    private fun foundMap(vararg f: FileScanner.Found) = f.associateBy { it.path }

    private val noUserData: () -> Set<Long> = { emptySet() }

    @Test
    fun excludedFolderKeepsBooksWithUserData() {
        // Regression: excluding a folder used to delete every entry below it, bookmarks and quotes included.
        val k = listOf(known(1, "/r/Download/read.txt"), known(2, "/r/Download/never.txt"), known(3, "/r/Books/x.txt"))
        val plan = FileScanner.plan(
            k, foundMap(found("/r/Books/x.txt")), emptySet(), listOf("/r/Download"),
            vanished = { false }, userDataIds = { setOf(1L) },
        )
        assertEquals(listOf(2L), plan.gone)
        assertTrue(plan.todo.isEmpty())
    }

    @Test
    fun userDataIsOnlyQueriedWhenSomethingIsExcluded() {
        val k = listOf(known(1, "/r/Books/a.txt"))
        val plan = FileScanner.plan(
            k, foundMap(found("/r/Books/a.txt")), emptySet(), listOf("/r/Download"),
            vanished = { false }, userDataIds = { throw AssertionError("queried") },
        )
        assertTrue(plan.gone.isEmpty())
    }

    @Test
    fun fileMovedOutOfExcludedFolderKeepsItsEntry() {
        // The old path is both excluded and gone: it is a move source, not an "excluded" drop.
        val k = listOf(known(7, "/r/Download/novel.txt", size = 5000))
        val f = found("/r/Books/novel.txt", size = 5000)
        val plan = FileScanner.plan(
            k, foundMap(f), emptySet(), listOf("/r/Download"),
            vanished = { it == "/r/Download/novel.txt" }, userDataIds = noUserData,
        )
        assertEquals(1, plan.todo.size)
        assertSame(f, plan.todo[0].first)
        assertEquals(7L, plan.todo[0].second!!.id)
        assertTrue(plan.gone.isEmpty())
    }

    @Test
    fun trashedEntriesAreKeptEvenWhenGoneOrExcluded() {
        val k = listOf(known(1, "/r/a.txt", trashed = true), known(2, "/r/Ex/b.txt", trashed = true))
        val plan = FileScanner.plan(k, emptyMap(), emptySet(), listOf("/r/Ex"), vanished = { true }, userDataIds = noUserData)
        assertTrue(plan.gone.isEmpty())
    }

    @Test
    fun changedNewAndUnchangedFiles() {
        val k = listOf(known(1, "/r/same.txt"), known(2, "/r/changed.txt", mtime = 1))
        val plan = FileScanner.plan(
            k,
            foundMap(found("/r/same.txt"), found("/r/changed.txt", mtime = 2), found("/r/new.txt"), found("/r/a-new.txt")),
            emptySet(), emptyList(), vanished = { false }, userDataIds = noUserData,
        )
        assertEquals(listOf("/r/a-new.txt", "/r/changed.txt", "/r/new.txt"), plan.todo.map { it.first.path })
        assertEquals(listOf(null, 2L, null), plan.todo.map { it.second?.id })
    }

    @Test
    fun onlyOneVanishedEntryMovesPerNewFile() {
        val k = listOf(known(1, "/r/A/x.txt"), known(2, "/r/B/x.txt"), known(3, "/r/C/other.txt"))
        val plan = FileScanner.plan(
            k, foundMap(found("/r/D/x.txt")), emptySet(), emptyList(),
            vanished = { true }, userDataIds = noUserData,
        )
        assertEquals(1, plan.todo.size)
        val movedId = plan.todo[0].second!!.id
        assertEquals(setOf(1L, 2L, 3L) - movedId, plan.gone.toSet())
    }

    @Test
    fun differentSizeIsNotAMove() {
        val plan = FileScanner.plan(
            listOf(known(1, "/r/A/x.txt", size = 10)), foundMap(found("/r/B/x.txt", size = 11)), emptySet(), emptyList(),
            vanished = { true }, userDataIds = noUserData,
        )
        assertNull(plan.todo.single().second)
        assertEquals(listOf(1L), plan.gone)
    }

    @Test
    fun ignoredFilesStayOutAndDeadMarksAreDropped() {
        val plan = FileScanner.plan(
            emptyList(), foundMap(found("/r/removed.txt")), setOf("/r/removed.txt", "/r/deleted.txt", "/r/hidden.txt"),
            emptyList(), vanished = { it == "/r/deleted.txt" }, userDataIds = noUserData,
        )
        assertTrue(plan.todo.isEmpty())
        assertEquals(listOf("/r/deleted.txt"), plan.deadIgnored)
    }

    @Test
    fun untrustedAbsenceKeepsEntries() {
        val plan = FileScanner.plan(
            listOf(known(1, "/r/a.txt")), emptyMap(), emptySet(), emptyList(), vanished = { false }, userDataIds = noUserData,
        )
        assertTrue(plan.gone.isEmpty())
        assertTrue(plan.todo.isEmpty())
    }

    @Test
    fun movedFromNeedsATrustedAbsence() {
        // Regression: a book moved with a file manager and opened before the next scan lost its history.
        val c = listOf("/r/Old/a.txt", "/r/Other/a.txt", "/r/Gone/a.txt")
        val gone = setOf("/r/Old/a.txt", "/r/Gone/a.txt")
        fun pick(trusted: (String) -> Boolean, newPath: String = "/r/New/a.txt") =
            FileScanner.movedFrom(c, { it }, newPath, trusted) { it !in gone }
        assertEquals("/r/Old/a.txt", pick({ true }))
        // Scoped storage may hide existing files: untrusted absence never counts as a move.
        assertNull(pick({ false }))
        assertEquals("/r/Gone/a.txt", pick({ it.startsWith("/r/Gone") }))
        // The entry of the file itself is never a move source.
        assertEquals("/r/Gone/a.txt", pick({ true }, newPath = "/r/Old/a.txt"))
        assertNull(FileScanner.movedFrom(listOf(""), { it }, "/r/a.txt", { true }) { false })
    }
}
