package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPlanTest {

    private fun known(id: Long, path: String, size: Long = 2000, mtime: Long = 1, trashed: Boolean = false, missingAt: Long = 0) =
        FileScanner.Known(id, path, size, mtime, trashed, path.substringAfterLast('/'), missingAt)

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
    fun aRescanThatFindsNothingNewChangesNoBook() {
        // The periodic rescan of an unchanged library must not reload (and redraw) the idle library screen.
        val k = listOf(known(1, "/r/a.txt"), known(2, "/r/b.txt"))
        val same = FileScanner.plan(
            k, foundMap(found("/r/a.txt"), found("/r/b.txt")), setOf("/r/gone-ignored.txt"), emptyList(),
            vanished = { true }, userDataIds = noUserData,
        )
        // A dead "removed from library" mark is cleaned up, but no book row changes.
        assertEquals(listOf("/r/gone-ignored.txt"), same.deadIgnored)
        assertFalse(same.changesBooks)
        val added = FileScanner.plan(
            k, foundMap(found("/r/a.txt"), found("/r/b.txt"), found("/r/c.txt")), emptySet(), emptyList(),
            vanished = { false }, userDataIds = noUserData,
        )
        assertTrue(added.changesBooks)
        val removed = FileScanner.plan(k, foundMap(found("/r/a.txt")), emptySet(), emptyList(), vanished = { true }, userDataIds = noUserData)
        assertEquals(listOf(2L), removed.gone)
        assertTrue(removed.changesBooks)
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

    // ---- N §5.5: notes are never lost silently ----

    @Test
    fun vanishedWithNotesIsTrashedWithoutIsGone() {
        val k = listOf(known(1, "/r/a.txt"), known(2, "/r/b.txt"))
        val plan = FileScanner.plan(
            k, emptyMap(), emptySet(), emptyList(), vanished = { true }, userDataIds = noUserData,
            noteIds = { setOf(1L) },
        )
        assertEquals(listOf(1L), plan.trash)
        assertEquals(listOf(2L), plan.gone)
        assertTrue(plan.revive.isEmpty())
        assertTrue(plan.changesBooks)
    }

    @Test
    fun movedWithNotesIsRePointedNotTrashed() {
        val k = listOf(known(1, "/r/old/a.txt", size = 7000))
        val f = found("/r/new/a.txt", size = 7000)
        val plan = FileScanner.plan(
            k, foundMap(f), emptySet(), emptyList(), vanished = { true }, userDataIds = noUserData,
            noteIds = { setOf(1L) },
        )
        assertEquals(1L, plan.todo.single().second!!.id)
        assertTrue(plan.trash.isEmpty())
        assertTrue(plan.gone.isEmpty())
        assertTrue(plan.revive.isEmpty())
    }

    @Test
    fun missingFoundAgainAtItsPathIsRevived() {
        val k = listOf(known(4, "/r/a.txt", trashed = true, missingAt = 99), known(5, "/r/b.txt", trashed = true, missingAt = 99, mtime = 1))
        val plan = FileScanner.plan(
            k, foundMap(found("/r/a.txt"), found("/r/b.txt", mtime = 2)), emptySet(), emptyList(),
            vanished = { false }, userDataIds = noUserData,
        )
        assertEquals(listOf(4L, 5L), plan.revive)
        // Changed on disk while it was away: refreshed as usual.
        assertEquals(listOf(5L), plan.todo.map { it.second!!.id })
        assertTrue(plan.changesBooks)
    }

    @Test
    fun userTrashedIsNeverRevivedOrMarkedMissing() {
        val k = listOf(known(6, "/r/a.txt", trashed = true), known(7, "/r/gone.txt", trashed = true))
        val back = FileScanner.plan(
            k, foundMap(found("/r/a.txt")), emptySet(), emptyList(), vanished = { true }, userDataIds = noUserData,
            noteIds = { setOf(6L, 7L) },
        )
        assertTrue(back.revive.isEmpty())
        assertTrue(back.trash.isEmpty())
        assertTrue(back.gone.isEmpty())
        assertFalse(back.changesBooks)
    }

    @Test
    fun missingFileReappearingElsewhereIsRePointedAndRevived() {
        val k = listOf(known(8, "/r/old/a.txt", size = 3000, trashed = true, missingAt = 50))
        val f = found("/r/new/a.txt", size = 3000)
        val plan = FileScanner.plan(
            k, foundMap(f), emptySet(), emptyList(), vanished = { true }, userDataIds = noUserData,
            noteIds = { throw AssertionError("nothing vanished unmoved") },
        )
        assertEquals(8L, plan.todo.single().second!!.id)
        assertSame(f, plan.todo.single().first)
        assertEquals(listOf(8L), plan.revive)
        assertTrue(plan.gone.isEmpty())
    }

    @Test
    fun missingStillAwayIsLeftAsItIs() {
        val k = listOf(known(9, "/r/Ex/a.txt", trashed = true, missingAt = 50))
        for (vanished in listOf(true, false)) {
            val plan = FileScanner.plan(
                k, emptyMap(), emptySet(), listOf("/r/Ex"), vanished = { vanished }, userDataIds = { emptySet() },
                noteIds = { emptySet() },
            )
            assertTrue(plan.gone.isEmpty())
            assertTrue(plan.trash.isEmpty())
            assertTrue(plan.revive.isEmpty())
            assertFalse(plan.changesBooks)
        }
    }

    @Test
    fun noteIdsAreNotQueriedWhenNothingVanished() {
        val k = listOf(known(1, "/r/a.txt"), known(2, "/r/b.txt", mtime = 1))
        val plan = FileScanner.plan(
            k, foundMap(found("/r/a.txt"), found("/r/b.txt", mtime = 5)), emptySet(), emptyList(),
            vanished = { false }, userDataIds = noUserData, noteIds = { throw AssertionError("queried") },
        )
        assertEquals(1, plan.todo.size)
        // Not trusted as gone (vanished = false): never trashed, never dropped, no query either.
        val untrusted = FileScanner.plan(
            listOf(known(3, "/r/c.txt")), emptyMap(), emptySet(), emptyList(), vanished = { false },
            userDataIds = noUserData, noteIds = { throw AssertionError("queried") },
        )
        assertFalse(untrusted.changesBooks)
    }

    @Test
    fun noteIdsAreQueriedOnceForManyVanished() {
        var calls = 0
        val k = (1L..5L).map { known(it, "/r/$it.txt") }
        val plan = FileScanner.plan(
            k, emptyMap(), emptySet(), emptyList(), vanished = { true }, userDataIds = noUserData,
            noteIds = { calls++; setOf(2L, 4L) },
        )
        assertEquals(1, calls)
        assertEquals(listOf(2L, 4L), plan.trash)
        assertEquals(listOf(1L, 3L, 5L), plan.gone)
    }

    @Test
    fun missingButUntrashedIsJudgedAgain() {
        // An R2 build's 복원 leaves trashed = 0 with missing_at > 0: found → revived (cleared); gone → by notes.
        val back = FileScanner.plan(
            listOf(known(1, "/r/a.txt", missingAt = 5)), foundMap(found("/r/a.txt")), emptySet(), emptyList(),
            vanished = { false }, userDataIds = noUserData,
        )
        assertEquals(listOf(1L), back.revive)
        val away = FileScanner.plan(
            listOf(known(1, "/r/a.txt", missingAt = 5)), emptyMap(), emptySet(), emptyList(),
            vanished = { true }, userDataIds = noUserData, noteIds = { setOf(1L) },
        )
        assertEquals(listOf(1L), away.trash)
    }

    @Test
    fun scannerStatementsKeepTheUsersTrash() {
        assertTrue(LibrarySql.SET_MISSING.endsWith("WHERE id = ? AND trashed = 0"))
        assertTrue(LibrarySql.CLEAR_MISSING.endsWith("WHERE id = ? AND missing_at > 0"))
        assertTrue(LibrarySql.SELECT_MOVE_CANDIDATES.contains("(trashed = 0 OR missing_at > 0)"))
        assertTrue(LibrarySql.SELECT_SCAN_STATE.contains("missing_at"))
        for (t in listOf("FROM quotes", "FROM bookmarks", "review <> ''", "FROM lookups")) {
            assertTrue(t, LibrarySql.SELECT_IDS_WITH_NOTES.contains(t))
        }
        assertEquals(0, LibrarySql.SELECT_IDS_WITH_NOTES.count { it == '?' })
    }

    @Test
    fun vanishedWithReadingHistoryOnlyIsTrashedNotDropped() {
        // The kept set (SELECT_IDS_KEPT_WHEN_MISSING) holds books with progress / 다 읽음 / reading log but no notes.
        val k = listOf(known(1, "/r/a.txt"), known(2, "/r/b.txt"))
        val plan = FileScanner.plan(
            k, emptyMap(), emptySet(), emptyList(), vanished = { true }, userDataIds = noUserData,
            noteIds = { setOf(2L) },
        )
        assertEquals(listOf(2L), plan.trash)
        assertEquals(listOf(1L), plan.gone)
    }

    @Test
    fun keptWhenMissingStatementCoversNotesAndReadingHistory() {
        val sql = LibrarySql.SELECT_IDS_KEPT_WHEN_MISSING
        for (t in listOf(
            "FROM quotes", "FROM bookmarks", "review <> ''", "FROM lookups", "progress > 0", "have_read = 1",
            "reading_seconds > 0", "finished_at > 0", "FROM book_prefs", "FROM reading_log",
        )) {
            assertTrue(t, sql.contains(t))
        }
        assertEquals(0, sql.count { it == '?' })
    }

    @Test
    fun aLiveEntryWinsTheMoveOverAnOlderMissingNamesake() {
        val k = listOf(known(1, "/r/x/a.txt", size = 4000, trashed = true, missingAt = 7), known(2, "/r/y/a.txt", size = 4000))
        val plan = FileScanner.plan(
            k, foundMap(found("/r/z/a.txt", size = 4000)), emptySet(), emptyList(), vanished = { true },
            userDataIds = noUserData, noteIds = { emptySet() },
        )
        assertEquals(2L, plan.todo.single().second!!.id)
        assertTrue(plan.revive.isEmpty())
        assertTrue(plan.gone.isEmpty())
    }

    @Test
    fun trashingClearsAStaleMissingMark() {
        assertEquals("UPDATE books SET trashed = 1, missing_at = 0 WHERE id = ?", LibrarySql.TRASH)
    }
}
