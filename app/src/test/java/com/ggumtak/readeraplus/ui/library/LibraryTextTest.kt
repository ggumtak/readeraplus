package com.ggumtak.readeraplus.ui.library

import android.view.KeyEvent
import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.data.ShelfGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTextTest {

    @Test
    fun formatSize_matchesCardStyle() {
        assertEquals("0B", LibraryText.formatSize(0))
        assertEquals("512B", LibraryText.formatSize(512))
        assertEquals("1KB", LibraryText.formatSize(1024))
        assertEquals("1.5KB", LibraryText.formatSize(1536))
        assertEquals("820KB", LibraryText.formatSize(820L * 1024))
        assertEquals("3.4MB", LibraryText.formatSize((3.4 * 1024 * 1024).toLong()))
        assertEquals("15MB", LibraryText.formatSize(15L * 1024 * 1024 + 200_000))
        assertEquals("3MB", LibraryText.formatSize(3L * 1024 * 1024))
        assertEquals("1.2GB", LibraryText.formatSize((1.2 * 1024 * 1024 * 1024).toLong()))
        // 9.97 MB rounds to 10 → no decimal
        assertEquals("10MB", LibraryText.formatSize((9.97 * 1024 * 1024).toLong()))
        // 1023.8 KB rounds up into the next unit
        assertEquals("1MB", LibraryText.formatSize((1023.8 * 1024).toLong()))
        assertEquals("0B", LibraryText.formatSize(-5))
    }

    @Test
    fun metaLine_formatCommaSize() {
        assertEquals("TXT, 3.4MB", LibraryText.metaLine("TXT", (3.4 * 1024 * 1024).toLong()))
        assertEquals("EPUB, 820KB", LibraryText.metaLine("EPUB", 820L * 1024))
    }

    @Test
    fun percent_floorsAndHidesUnopened() {
        assertEquals("", LibraryText.percent(0.5f, opened = false))
        assertEquals("0%", LibraryText.percent(0f, opened = true))
        assertEquals("34%", LibraryText.percent(0.34f, opened = true))
        assertEquals("99%", LibraryText.percent(0.997f, opened = true))
        assertEquals("100%", LibraryText.percent(1f, opened = true))
        assertEquals("100%", LibraryText.percent(1.3f, opened = true))
        assertEquals("0%", LibraryText.percent(-0.2f, opened = true))
        assertEquals("57%", LibraryText.percent(0.57f, opened = true))
    }

    @Test
    fun folderNameAndGroupTitle() {
        assertEquals("Books", LibraryText.folderName("/storage/emulated/0/Books"))
        assertEquals("Books", LibraryText.folderName("/storage/emulated/0/Books/"))
        assertEquals("/", LibraryText.folderName("/"))
        val g = ShelfGroup("/storage/emulated/0/Download/소설", "/storage/emulated/0/Download/소설", 3)
        assertEquals("소설", LibraryText.groupTitle(Shelf.FOLDERS, g))
        assertEquals("김작가", LibraryText.groupTitle(Shelf.AUTHORS, ShelfGroup("김작가", "김작가", 2)))
    }

    @Test
    fun groupedShelves() {
        val grouped = Shelf.entries.filter { LibraryText.isGrouped(it) }.toSet()
        assertEquals(setOf(Shelf.AUTHORS, Shelf.SERIES, Shelf.COLLECTIONS, Shelf.FORMATS, Shelf.FOLDERS), grouped)
    }

    @Test
    fun filterGroups_caseInsensitive() {
        val groups = listOf(
            ShelfGroup("a", "Alice Kim", 1),
            ShelfGroup("b", "홍길동", 4),
            ShelfGroup("/x/Novels", "/x/Novels", 2),
        )
        assertEquals(groups, LibraryText.filterGroups(groups, "  "))
        assertEquals(listOf(groups[0]), LibraryText.filterGroups(groups, "alice"))
        assertEquals(listOf(groups[1]), LibraryText.filterGroups(groups, "길동"))
        assertEquals(listOf(groups[2]), LibraryText.filterGroups(groups, "novels"))
        assertTrue(LibraryText.filterGroups(groups, "zzz").isEmpty())
    }

    @Test
    fun docIdToPath_externalStorageIds() {
        val root = "/storage/emulated/0"
        val ext = LibraryText.EXTERNAL_STORAGE_AUTHORITY
        assertEquals("/storage/emulated/0/Books", LibraryText.docIdToPath(ext, "primary:Books", root))
        assertEquals("/storage/emulated/0/Books/소설/a.txt", LibraryText.docIdToPath(ext, "primary:Books/소설/a.txt", root))
        assertEquals("/storage/emulated/0", LibraryText.docIdToPath(ext, "primary:", root))
        assertEquals("/storage/emulated/0", LibraryText.docIdToPath(null, "primary:", "/storage/emulated/0/"))
        assertEquals("/storage/1A2B-3C4D/Novels", LibraryText.docIdToPath(ext, "1A2B-3C4D:Novels", root))
        assertEquals("/storage/emulated/0/Documents/x", LibraryText.docIdToPath(ext, "home:x", root))
        assertEquals("/storage/emulated/0/Download/b.epub",
            LibraryText.docIdToPath(LibraryText.DOWNLOADS_AUTHORITY, "raw:/storage/emulated/0/Download/b.epub", root))
        assertNull(LibraryText.docIdToPath(LibraryText.DOWNLOADS_AUTHORITY, "msf:1234", root))
        assertNull(LibraryText.docIdToPath("com.google.android.apps.docs.storage", "primary:Books", root))
        assertNull(LibraryText.docIdToPath(ext, "noColon", root))
        assertNull(LibraryText.docIdToPath(ext, "weird-volume:Books", root))
    }

    @Test
    fun importFileName_sanitizesAndAddsExtension() {
        assertEquals("소설 1-100.txt", LibraryText.importFileName("소설 1-100.txt", "text/plain"))
        assertEquals("Book.EPUB", LibraryText.importFileName("Book.EPUB", null))
        assertEquals("a_b_c.txt", LibraryText.importFileName("a/b:c.txt", null))
        assertEquals("download.epub", LibraryText.importFileName("download", "application/epub+zip"))
        assertEquals("notes.txt", LibraryText.importFileName("notes", "text/plain"))
        assertEquals("문서.txt", LibraryText.importFileName(null, "text/plain"))
        assertEquals("hidden.txt", LibraryText.importFileName(".hidden.txt", null))
        assertNull(LibraryText.importFileName("photo.jpg", "image/jpeg"))
        assertNull(LibraryText.importFileName("archive", "application/zip"))
    }

    @Test
    fun uniqueName_appendsCounter() {
        val taken = setOf("a.txt", "a (1).txt", "noext")
        assertEquals("b.txt", LibraryText.uniqueName("b.txt") { it in taken })
        assertEquals("a (2).txt", LibraryText.uniqueName("a.txt") { it in taken })
        assertEquals("noext (1)", LibraryText.uniqueName("noext") { it in taken })
    }

    @Test
    fun gridColumns_byWidth() {
        // Comet: 720 px wide; 2.0 density → 360 dp → 3 columns
        assertEquals(3, LibraryText.gridColumns(720, 2.0f))
        assertEquals(3, LibraryText.gridColumns(720, 1.75f))
        assertEquals(6, LibraryText.gridColumns(1440, 2.0f))
        assertEquals(2, LibraryText.gridColumns(200, 2.0f))
        assertEquals(2, LibraryText.gridColumns(0, 2.0f))
        assertEquals(2, LibraryText.gridColumns(720, 0f))
    }

    @Test
    fun keyDirection_mapping() {
        val none = emptySet<Int>()
        assertEquals(1, LibraryText.keyDirection(KeyEvent.KEYCODE_VOLUME_DOWN, true, false, none, none))
        assertEquals(-1, LibraryText.keyDirection(KeyEvent.KEYCODE_VOLUME_UP, true, false, none, none))
        assertEquals(-1, LibraryText.keyDirection(KeyEvent.KEYCODE_VOLUME_DOWN, true, true, none, none))
        assertEquals(1, LibraryText.keyDirection(KeyEvent.KEYCODE_VOLUME_UP, true, true, none, none))
        assertEquals(0, LibraryText.keyDirection(KeyEvent.KEYCODE_VOLUME_DOWN, false, false, none, none))
        assertEquals(1, LibraryText.keyDirection(KeyEvent.KEYCODE_PAGE_DOWN, false, false, none, none))
        assertEquals(-1, LibraryText.keyDirection(KeyEvent.KEYCODE_PAGE_UP, false, false, none, none))
        assertEquals(0, LibraryText.keyDirection(KeyEvent.KEYCODE_A, true, false, none, none))
        assertEquals(1, LibraryText.keyDirection(KeyEvent.KEYCODE_F1, false, false, setOf(KeyEvent.KEYCODE_F1), none))
        assertEquals(-1, LibraryText.keyDirection(KeyEvent.KEYCODE_F2, false, false, none, setOf(KeyEvent.KEYCODE_F2)))
        // learned keys win over the built-in meaning
        assertEquals(-1, LibraryText.keyDirection(KeyEvent.KEYCODE_PAGE_DOWN, false, false, none, setOf(KeyEvent.KEYCODE_PAGE_DOWN)))
    }

    @Test
    fun backStep_order() {
        assertEquals(LibraryText.BackStep.CLOSE_DRAWER, LibraryText.backStep(true, true, true))
        assertEquals(LibraryText.BackStep.CLOSE_SEARCH, LibraryText.backStep(false, true, true))
        assertEquals(LibraryText.BackStep.LEAVE_GROUP, LibraryText.backStep(false, false, true))
        assertEquals(LibraryText.BackStep.FINISH, LibraryText.backStep(false, false, false))
    }

    @Test
    fun rescanDue_thirtyMinutes() {
        val now = 10_000_000_000L
        assertTrue(LibraryText.rescanDue(0L, now))
        assertFalse(LibraryText.rescanDue(now - 10 * 60 * 1000, now))
        assertTrue(LibraryText.rescanDue(now - 31 * 60 * 1000, now))
        // clock moved backwards → rescan
        assertTrue(LibraryText.rescanDue(now + 60_000, now))
    }

    @Test
    fun statusText_combinesJobs() {
        assertNull(LibraryText.statusText(false, 0, false, 0, 0))
        assertEquals("스캔 중…", LibraryText.statusText(true, 0, false, 0, 0))
        assertEquals("스캔 중… 120", LibraryText.statusText(true, 120, false, 0, 0))
        assertEquals("가져오는 중… 3/10", LibraryText.statusText(false, 0, true, 3, 10))
        assertEquals("스캔 중… 5  ·  가져오는 중… 0", LibraryText.statusText(true, 5, true, 0, 0))
    }

    @Test
    fun emptyMessage_searchAndShelves() {
        assertTrue(LibraryText.emptyMessage(Shelf.ALL, "마법", false).contains("마법"))
        assertTrue(LibraryText.emptyMessage(Shelf.TRASH, "", false).contains("휴지통"))
        assertTrue(LibraryText.emptyMessage(Shelf.AUTHORS, "", true).contains("항목"))
        for (s in Shelf.entries) assertTrue(LibraryText.emptyMessage(s, "", false).isNotBlank())
    }

    @Test
    fun addScanFolder_mergesAndDetectsCoverage() {
        assertNull(LibraryText.addScanFolder(emptySet(), "/storage/emulated/0/Books"))
        val existing = setOf("/storage/emulated/0/Books")
        assertNull(LibraryText.addScanFolder(existing, "/storage/emulated/0/Books/소설"))
        assertNull(LibraryText.addScanFolder(existing, "/storage/emulated/0/Books/"))
        assertEquals(setOf("/storage/emulated/0/Books", "/storage/emulated/0/Novels"),
            LibraryText.addScanFolder(existing, "/storage/emulated/0/Novels"))
        // "Books2" is not inside "Books"
        assertEquals(setOf("/storage/emulated/0/Books", "/storage/emulated/0/Books2"),
            LibraryText.addScanFolder(existing, "/storage/emulated/0/Books2"))
        // A parent folder replaces the folders inside it
        assertEquals(setOf("/storage/emulated/0"),
            LibraryText.addScanFolder(setOf("/storage/emulated/0/Books", "/storage/emulated/0/Novels"), "/storage/emulated/0"))
    }

    @Test
    fun isSameOrInside_segmentAware() {
        assertTrue(LibraryText.isSameOrInside("/a/b", "/a"))
        assertTrue(LibraryText.isSameOrInside("/a", "/a/"))
        assertFalse(LibraryText.isSameOrInside("/ab", "/a"))
        assertFalse(LibraryText.isSameOrInside("/a", "/a/b"))
    }

    @Test
    fun encodingLabels() {
        assertEquals("자동 감지", LibraryText.encodingLabel(""))
        assertTrue(LibraryText.encodingLabel("MS949").startsWith("CP949"))
        assertTrue(LibraryText.encodingLabel("utf-8").startsWith("UTF-8"))
        assertEquals("ISO-8859-1", LibraryText.encodingLabel("ISO-8859-1"))
        // One wording in the library and the reader.
        assertEquals(com.ggumtak.readeraplus.reader.ReaderFormat.encodingLabel("MS949"), LibraryText.encodingLabel("MS949"))
    }

    @Test
    fun bookNamesAndMime() {
        assertTrue(LibraryText.isBookName("a.TXT"))
        assertTrue(LibraryText.isBookName("b.epub"))
        assertFalse(LibraryText.isBookName("c.pdf"))
        assertFalse(LibraryText.isBookName("txt"))
        assertEquals("application/epub+zip", LibraryText.mimeFor("x.EPUB"))
        assertEquals("text/plain", LibraryText.mimeFor("x.txt"))
        assertEquals("application/octet-stream", LibraryText.mimeFor("x.bin"))
    }
    @Test
    fun groupTitleAndSubtitle_folders() {
        // Data layer labels a folder with its full path
        val full = ShelfGroup("/storage/emulated/0/Novels", "/storage/emulated/0/Novels", 5)
        assertEquals("Novels", LibraryText.groupTitle(Shelf.FOLDERS, full))
        assertEquals("/storage/emulated/0/Novels", LibraryText.groupSubtitle(Shelf.FOLDERS, full))
        // ...or with a short name: the path still comes from the key (no duplicated line)
        val short = ShelfGroup("/storage/emulated/0/Novels", "Novels", 5)
        assertEquals("Novels", LibraryText.groupTitle(Shelf.FOLDERS, short))
        assertEquals("/storage/emulated/0/Novels", LibraryText.groupSubtitle(Shelf.FOLDERS, short))
        // Nothing to add when key and title are the same
        assertNull(LibraryText.groupSubtitle(Shelf.FOLDERS, ShelfGroup("Books", "Books", 1)))
        assertEquals("Books", LibraryText.groupTitle(Shelf.FOLDERS, ShelfGroup("Books", "", 1)))
        // Other shelves: label only, no subtitle
        assertNull(LibraryText.groupSubtitle(Shelf.AUTHORS, ShelfGroup("김작가", "김작가", 2)))
        assertEquals("내 컬렉션", LibraryText.groupTitle(Shelf.COLLECTIONS, ShelfGroup("7", "내 컬렉션", 2)))
    }

    @Test
    fun keyDirection_neverTakesNavigationKeys() {
        val none = emptySet<Int>()
        // Even when learned as page keys, BACK / ESCAPE keep navigating (otherwise the library can't be left)
        assertEquals(0, LibraryText.keyDirection(KeyEvent.KEYCODE_BACK, true, false, setOf(KeyEvent.KEYCODE_BACK), none))
        assertEquals(0, LibraryText.keyDirection(KeyEvent.KEYCODE_ESCAPE, true, false, none, setOf(KeyEvent.KEYCODE_ESCAPE)))
        assertEquals(0, LibraryText.keyDirection(KeyEvent.KEYCODE_BACK, true, false, none, none))
        // Other learned keys still page
        assertEquals(1, LibraryText.keyDirection(KeyEvent.KEYCODE_ENTER, false, false, setOf(KeyEvent.KEYCODE_ENTER), none))
    }

    @Test
    fun addScanFolder_withDefaultRoots() {
        val roots = listOf("/storage/emulated/0", "/storage/1A2B-3C4D/")
        // Covered by a default root → nothing to change
        assertNull(LibraryText.addScanFolder(emptySet(), "/storage/emulated/0/Books", roots))
        assertNull(LibraryText.addScanFolder(emptySet(), "/storage/1A2B-3C4D/Novels", roots))
        // Outside every root (e.g. USB drive) → keep scanning the roots and add the folder
        assertEquals(
            setOf("/storage/emulated/0", "/storage/1A2B-3C4D", "/mnt/media_rw/USB/Books"),
            LibraryText.addScanFolder(emptySet(), "/mnt/media_rw/USB/Books/", roots),
        )
        // Configured folders take precedence over the default roots
        assertEquals(
            setOf("/storage/emulated/0/Books", "/storage/emulated/0/Novels"),
            LibraryText.addScanFolder(setOf("/storage/emulated/0/Books"), "/storage/emulated/0/Novels", roots),
        )
        // Roots unknown and nothing configured: the scanner covers the whole storage already
        assertNull(LibraryText.addScanFolder(emptySet(), "/mnt/media_rw/USB/Books", emptyList()))
    }

    @Test
    fun shouldOpenLast_onlyOnFreshLauncherStart() {
        val main = android.content.Intent.ACTION_MAIN
        val fromHistory = android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        val newTask = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        assertTrue(LibraryText.shouldOpenLast(enabled = true, restored = false, action = main, flags = newTask))
        assertFalse(LibraryText.shouldOpenLast(enabled = false, restored = false, action = main, flags = newTask))
        // Recreation (rotation, process death) keeps the library.
        assertFalse(LibraryText.shouldOpenLast(enabled = true, restored = true, action = main, flags = 0))
        // Relaunch from recents: the reader is still on top of the task.
        assertFalse(LibraryText.shouldOpenLast(enabled = true, restored = false, action = main, flags = newTask or fromHistory))
        assertFalse(LibraryText.shouldOpenLast(enabled = true, restored = false, action = null, flags = 0))
        assertFalse(LibraryText.shouldOpenLast(enabled = true, restored = false, action = "android.intent.action.VIEW", flags = 0))
    }
}
