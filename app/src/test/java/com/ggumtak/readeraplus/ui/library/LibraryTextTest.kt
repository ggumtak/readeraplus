package com.ggumtak.readeraplus.ui.library

import android.view.KeyEvent
import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.data.ShelfGroup
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

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
        assertEquals("가져온 책.txt", LibraryText.importFileName(null, "text/plain"))
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
    fun backStep_leavesSelectionBeforeSearchAndGroup() {
        assertEquals(LibraryText.BackStep.END_SELECTION, LibraryText.backStep(false, true, true, selecting = true))
        assertEquals(LibraryText.BackStep.END_SELECTION, LibraryText.backStep(false, false, false, selecting = true))
        // The drawer can't be open while selecting, but if it were it closes first.
        assertEquals(LibraryText.BackStep.CLOSE_DRAWER, LibraryText.backStep(true, false, false, selecting = true))
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
    fun emptyMessage_glossaryAndShelfLabels() {
        // A8: 책, never 문서, in every variant.
        for (s in Shelf.entries) for (flags in listOf(true, false)) for (inGroup in listOf(true, false)) {
            val m = LibraryText.emptyMessage(s, "", inGroup, flags)
            assertFalse("$s: $m", m.contains("문서"))
        }
        // The shelf names come from Shelf.X.label.
        assertTrue(LibraryText.emptyMessage(Shelf.READING_NOW, "", false).startsWith("읽고 있는 책이 없습니다."))
        assertTrue(LibraryText.emptyMessage(Shelf.TO_READ, "", false).startsWith("읽을 책이 없습니다."))
        assertTrue(LibraryText.emptyMessage(Shelf.HAVE_READ, "", false).startsWith("다 읽은 책이 없습니다."))
        assertEquals("이 항목에 책이 없습니다.", LibraryText.emptyMessage(Shelf.AUTHORS, "", true))
    }

    @Test
    fun emptyMessage_hintFollowsTheView() {
        // Cards have flag buttons; the other views point at multi-select or the book menu.
        assertTrue(LibraryText.emptyMessage(Shelf.TO_READ, "", false, flagButtons = true).contains("시계 버튼"))
        val compact = LibraryText.emptyMessage(Shelf.TO_READ, "", false, flagButtons = false)
        assertFalse(compact.contains("버튼"))
        assertTrue(compact.contains("‘읽을 책으로’"))
        assertTrue(LibraryText.emptyMessage(Shelf.HAVE_READ, "", false, flagButtons = false).contains("‘다 읽음으로’"))
        assertTrue(LibraryText.emptyMessage(Shelf.FAVORITES, "", false, flagButtons = false).contains("책 메뉴"))
    }

    @Test
    fun statusTag_finishedBeforeNew() {
        assertEquals("새 책", LibraryText.statusTag(opened = false, haveRead = false))
        assertEquals("완독", LibraryText.statusTag(opened = true, haveRead = true))
        // Marked read without opening it here (read elsewhere): 완독, not 새 책.
        assertEquals("완독", LibraryText.statusTag(opened = false, haveRead = true))
        assertNull(LibraryText.statusTag(opened = true, haveRead = false))
    }

    @Test
    fun compactLine_authorProgressAndAgo() {
        assertEquals("김작가 · 34% · 3일 전", LibraryText.compactLine("김작가", true, false, "34%") { "3일 전" })
        assertEquals("34% · 어제", LibraryText.compactLine("  ", true, false, "34%") { "어제" })
        assertEquals("김작가 · 새 책", LibraryText.compactLine("김작가", false, false, "") { error("not read") })
        assertEquals("김작가 · 완독", LibraryText.compactLine(" 김작가 ", true, true, "100%") { error("not read") })
        assertEquals("새 책", LibraryText.compactLine("", false, false, "") { error("not read") })
    }

    @Test
    fun ago_countsCalendarDays() {
        val zone = ZoneId.of("Asia/Seoul")
        fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
            LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
        val now = t(2026, 9, 30, 9)
        assertEquals("오늘", LibraryText.ago(t(2026, 9, 30, 0, 5), now, zone))
        // Last night 23:50 is 어제 although it's under 24 hours ago.
        assertEquals("어제", LibraryText.ago(t(2026, 9, 29, 23, 50), now, zone))
        assertEquals("3일 전", LibraryText.ago(t(2026, 9, 27, 12), now, zone))
        assertEquals("29일 전", LibraryText.ago(t(2026, 9, 1, 12), now, zone))
        assertEquals("1개월 전", LibraryText.ago(t(2026, 8, 31, 12), now, zone))
        assertEquals("12개월 전", LibraryText.ago(t(2025, 10, 1, 12), now, zone))
        assertEquals("1년 전", LibraryText.ago(t(2025, 9, 30, 12), now, zone))
        assertEquals("3년 전", LibraryText.ago(t(2023, 6, 1, 12), now, zone))
        // A clock that went backwards: never "-1일 전".
        assertEquals("오늘", LibraryText.ago(t(2026, 10, 2, 12), now, zone))
    }

    @Test
    fun nextListMode_cyclesListCompactGrid() {
        assertEquals(LibraryListMode.COMPACT, LibraryText.nextListMode(LibraryListMode.LIST))
        assertEquals(LibraryListMode.GRID, LibraryText.nextListMode(LibraryListMode.COMPACT))
        assertEquals(LibraryListMode.COVERS, LibraryText.nextListMode(LibraryListMode.GRID))
        assertEquals(LibraryListMode.LIST, LibraryText.nextListMode(LibraryListMode.COVERS))
    }

    @Test
    fun flagMenuLabels_useShelfNames() {
        assertEquals("읽을 책에 추가", LibraryText.flagMenuLabel(Shelf.TO_READ, on = false))
        assertEquals("읽을 책에서 빼기", LibraryText.flagMenuLabel(Shelf.TO_READ, on = true))
        assertEquals("다 읽은 책에 추가", LibraryText.flagMenuLabel(Shelf.HAVE_READ, on = false))
        assertEquals("즐겨찾기에서 빼기", LibraryText.flagMenuLabel(Shelf.FAVORITES, on = true))
    }

    @Test
    fun selectionAndBatchMessages() {
        assertEquals("3권 선택", LibraryText.selectionTitle(3))
        assertEquals("책을 고르세요", LibraryText.selectionTitle(0))
        assertEquals("다 읽은 책에 3권을 추가했습니다", LibraryText.addedToShelf(Shelf.HAVE_READ, 3))
        assertEquals("읽을 책에 1권을 추가했습니다", LibraryText.addedToShelf(Shelf.TO_READ, 1))
        assertEquals("‘무협’에 12권을 추가했습니다", LibraryText.addedToCollection("무협", 12))
        assertEquals("휴지통으로 이동했습니다", LibraryText.trashedMessage(1))
        assertEquals("5권을 휴지통으로 이동했습니다", LibraryText.trashedMessage(5))
        assertTrue(LibraryText.trashQuestion(5).contains("5권"))
    }

    @Test
    fun importAndScanMessages_countBooks() {
        assertEquals("책 3권을 추가했습니다", LibraryText.importedMessage(3))
        assertEquals("추가한 책이 없습니다", LibraryText.importedMessage(0))
        assertEquals("책 12권을 가져왔습니다", LibraryText.treeImportedMessage(12))
        assertEquals("가져온 책이 없습니다", LibraryText.treeImportedMessage(0))
        assertEquals("스캔 완료: 책 120권", LibraryText.scanDoneMessage(120))
    }

    @Test
    fun pageDirection_bindingsFirst() {
        val vol = AppSettings(volumeKeysTurn = true)
        // No binding: the legacy rules (volume keys page when volumeKeysTurn).
        assertEquals(1, LibraryText.pageDirection(KeyEvent.KEYCODE_VOLUME_DOWN, vol))
        assertEquals(-1, LibraryText.pageDirection(KeyEvent.KEYCODE_PAGE_UP, vol))
        // A binding overrides volumeKeysTurn, in both directions.
        val bound = vol.copy(
            keyBindings = mapOf(
                KeyEvent.KEYCODE_VOLUME_DOWN to TapAction.PREV,
                KeyEvent.KEYCODE_VOLUME_UP to TapAction.NONE,
                KeyEvent.KEYCODE_F5 to TapAction.NEXT_CHAPTER,
                KeyEvent.KEYCODE_PAGE_DOWN to TapAction.TOC,
                KeyEvent.KEYCODE_BACK to TapAction.NEXT,
            ),
        )
        assertEquals(-1, LibraryText.pageDirection(KeyEvent.KEYCODE_VOLUME_DOWN, bound))
        // "없음(시스템에 맡김)": the volume key changes the volume.
        assertEquals(0, LibraryText.pageDirection(KeyEvent.KEYCODE_VOLUME_UP, bound))
        assertEquals(1, LibraryText.pageDirection(KeyEvent.KEYCODE_F5, bound))
        // A reader-only action is not a library key.
        assertEquals(0, LibraryText.pageDirection(KeyEvent.KEYCODE_PAGE_DOWN, bound))
        // Navigation keys are never taken.
        assertEquals(0, LibraryText.pageDirection(KeyEvent.KEYCODE_BACK, bound))
        assertEquals(0, LibraryText.boundDirection(TapAction.MENU))
        assertEquals(-1, LibraryText.boundDirection(TapAction.PREV_CHAPTER))
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
        // A recents root restart lost its activities: startMode, rather than this preference, restores the reader.
        assertFalse(LibraryText.shouldOpenLast(enabled = true, restored = false, action = main, flags = newTask or fromHistory))
        assertFalse(LibraryText.shouldOpenLast(enabled = true, restored = false, action = null, flags = 0))
        assertFalse(LibraryText.shouldOpenLast(enabled = true, restored = false, action = "android.intent.action.VIEW", flags = 0))
    }

    // ---- LIB (R3): meta lines, view chooser, notes warnings, restore offer

    @Test
    fun metaLine_lastReadSeriesAndMissing() {
        val mb = (3.4 * 1024 * 1024).toLong()
        assertEquals("TXT, 3.4MB · 3일 전", LibraryText.metaLine("TXT", mb, null, null, "3일 전", missing = false))
        assertEquals("TXT, 3.4MB", LibraryText.metaLine("TXT", mb, null, null, "", missing = false))
        assertEquals("EPUB, 3.4MB · 삼국지 3", LibraryText.metaLine("EPUB", mb, "삼국지", 3f, "어제", missing = false))
        assertEquals("EPUB, 3.4MB · 삼국지", LibraryText.metaLine("EPUB", mb, " 삼국지 ", null, "", missing = false))
        assertEquals("TXT, 3.4MB · 파일 없음", LibraryText.metaLine("TXT", mb, "삼국지", 3f, "어제", missing = true))
        assertEquals("TXT, 3.4MB · 3일 전", LibraryText.metaLine("TXT", mb, "  ", 2f, "3일 전", missing = false))
        assertEquals("삼국지 2.5", LibraryText.seriesLabel("삼국지", 2.5f))
    }

    @Test
    fun lastRead_emptyForUnopened() {
        val zone = ZoneId.of("Asia/Seoul")
        val now = LocalDateTime.of(2026, 10, 3, 12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("", LibraryText.lastRead(now, 0L, zone))
        assertEquals("오늘", LibraryText.lastRead(now, now - 60_000, zone))
        assertEquals("3일 전", LibraryText.lastRead(now, now - 3L * 86_400_000, zone))
    }

    @Test
    fun compactMeta_flagsAuthorSizeState() {
        val mb = (3.4 * 1024 * 1024).toLong()
        assertEquals("★ 김작가 · TXT 3.4MB · 다 읽음", LibraryText.compactMeta("김작가", "TXT", mb, true, true, true, false))
        assertEquals("김작가 · TXT 3.4MB · 새 책", LibraryText.compactMeta(" 김작가 ", "TXT", mb, false, false, false, false))
        assertEquals("EPUB 3.4MB · 읽을 책", LibraryText.compactMeta("", "EPUB", mb, false, true, false, true))
        assertEquals("EPUB 3.4MB", LibraryText.compactMeta("", "EPUB", mb, false, true, false, false))
        assertEquals("EPUB 3.4MB · 파일 없음", LibraryText.compactMeta("", "EPUB", mb, false, true, false, false, missing = true))
    }

    @Test
    fun modeChoice_labelsInEnumOrder() {
        assertEquals(
            listOf("전체 — 표지 · 정보 · 버튼", "요약 — 작은 표지와 한 줄 정보", "썸네일 — 표지 3열", "그리드 — 작은 표지 4열"),
            LibraryListMode.entries.map { LibraryText.modeChoice(it) },
        )
        assertEquals(listOf("전체", "요약", "썸네일", "그리드"), LibraryListMode.entries.map { it.label })
    }

    @Test
    fun deleteMessages_mentionNotesOnlyWhenThereAreSome() {
        assertEquals("‘책’을(를) 서재에서 삭제합니다.", LibraryText.deleteMessage("책", 0))
        assertEquals(
            "‘책’을(를) 서재에서 삭제합니다.\n\n이 책의 인용문·메모·북마크·리뷰·단어 5개도 함께 지워집니다. 먼저 독서 노트에서 내보낼 수 있습니다.",
            LibraryText.deleteMessage("책", 5),
        )
        assertEquals("휴지통의 모든 책을 서재에서 삭제합니다.", LibraryText.emptyTrashMessage(0))
        assertTrue(LibraryText.emptyTrashMessage(2).endsWith("단어 2개도 함께 지워집니다. 먼저 독서 노트에서 내보낼 수 있습니다."))
    }

    @Test
    fun restoreOffer_messageAndLateLine() {
        val zone = ZoneId.of("Asia/Seoul")
        val at = LocalDateTime.of(2026, 9, 30, 21, 5).atZone(zone).toInstant().toEpochMilli()
        val msg = LibraryText.restoreOfferMessage(at, 120, 14, 3, 9, "다운로드/ReaderaPlus/backup", false, zone)
        assertEquals(
            "이전 설정과 읽기 기록을 복원할까요?\n\n2026-09-30 21:05 백업 · 책 120권 (읽던 책 14권) · 북마크 3개 · 인용문 9개\n" +
                "위치: 다운로드/ReaderaPlus/backup\n책 파일은 지금 있는 곳에서 다시 찾습니다.",
            msg,
        )
        val late = LibraryText.restoreOfferMessage(at, 1, 1, 0, 0, "문서", true, zone)
        assertTrue(late.endsWith("\n지금 설정은 백업의 설정으로 바뀌고, 책마다 더 최근에 읽은 위치가 남습니다."))
        assertEquals(
            "2026-09-30 21:05 · 책 120권 · 읽던 책 14권 · 북마크 3개 · 인용문 9개 · 자동",
            LibraryText.backupChoice(at, 120, 14, 3, 9, true, zone),
        )
        assertTrue(LibraryText.backupChoice(at, 1, 0, 0, 0, false, zone).endsWith("· 직접 내보냄"))
        assertEquals("다운로드/ReaderaPlus/backup", LibraryText.backupLocation(true, "backup", "다운로드/ReaderaPlus/backup"))
        assertEquals("문서", LibraryText.backupLocation(false, "Documents", "x"))
        assertEquals("다운로드", LibraryText.backupLocation(false, "Download", "x"))
        assertEquals("다운로드", LibraryText.backupLocation(false, null, "x"))
        assertEquals("책 3권의 기록을 복원했습니다", LibraryText.restoredMessage(3))
        assertEquals(
            "자동 백업을 다운로드/ReaderaPlus/backup에 저장했습니다 · 설정 → 백업 및 복원에서 끌 수 있습니다",
            LibraryText.autoBackupNotice("다운로드/ReaderaPlus/backup"),
        )
    }
}
