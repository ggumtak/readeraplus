package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPathsTest {

    @Test
    fun normalizeResolvesDotsSlashesAndAliases() {
        assertEquals("/storage/emulated/0/Books/a.txt", DataPaths.normalize("/sdcard/Books/a.txt"))
        assertEquals("/storage/emulated/0/Books/a.txt", DataPaths.normalize("/storage/self/primary/Books/a.txt"))
        assertEquals("/storage/emulated/0", DataPaths.normalize("/sdcard/"))
        assertEquals("/storage/emulated/0/a/c.txt", DataPaths.normalize("/storage/emulated/0//a/./b/../c.txt"))
        assertEquals("/storage/ABCD-1234/소설/b.epub", DataPaths.normalize("/storage/ABCD-1234/소설/b.epub/"))
        assertEquals("/", DataPaths.normalize("/.."))
        assertEquals("", DataPaths.normalize(""))
        // Not an alias: "/sdcardx" must not be rewritten.
        assertEquals("/sdcardx/a", DataPaths.normalize("/sdcardx/a"))
        // Secondary users.
        assertEquals("/storage/emulated/10/x.txt", DataPaths.normalize("/sdcard/x.txt", "/storage/emulated/10"))
    }

    @Test
    fun folderAndIsUnder() {
        assertEquals("/a/b", DataPaths.folderOf("/a/b/c.txt"))
        assertEquals("", DataPaths.folderOf("c.txt"))
        assertTrue(DataPaths.isUnder("/a/b/c.txt", "/a/b"))
        assertTrue(DataPaths.isUnder("/a/b", "/a/b/"))
        assertTrue(DataPaths.isUnder("/a/b", "/a/b"))
        assertFalse(DataPaths.isUnder("/a/bc/d", "/a/b"))
        assertFalse(DataPaths.isUnder("/a", "/a/b"))
        assertTrue(DataPaths.isUnder("/x", "/"))
    }

    @Test
    fun volumeRootsFromAppDirs() {
        val roots = DataPaths.volumeRootsFromAppDirs(
            listOf(
                "/storage/emulated/0/Android/data/com.ggumtak.readeraplus/files",
                null,
                "/storage/1A2B-3C4D/Android/data/com.ggumtak.readeraplus/files",
                "/weird/path",
                "/storage/emulated/0/Android/data/com.ggumtak.readeraplus/files",
            ),
        )
        assertEquals(listOf("/storage/emulated/0", "/storage/1A2B-3C4D"), roots)
    }

    @Test
    fun dedupeRootsDropsNestedAndDuplicates() {
        val r = DataPaths.dedupeRoots(
            listOf("/storage/emulated/0/Books", "/sdcard", " /storage/emulated/0 ", "relative/x", "/storage/ABCD-1234/Novels"),
        )
        assertEquals(listOf("/storage/emulated/0", "/storage/ABCD-1234/Novels"), r)
        assertEquals(listOf("/a/b", "/a/bc"), DataPaths.dedupeRoots(listOf("/a/bc", "/a/b")))
    }

    @Test
    fun bookFormatsAndSizes() {
        assertEquals(BookFormat.EPUB, DataPaths.bookFormatOf("책.EPUB"))
        assertEquals(BookFormat.TXT, DataPaths.bookFormatOf("소설.Txt"))
        assertNull(DataPaths.bookFormatOf("._소설.txt"))
        assertNull(DataPaths.bookFormatOf(".hidden.epub"))
        assertEquals(BookFormat.PDF, DataPaths.bookFormatOf("a.pdf"))
        assertNull(DataPaths.bookFormatOf("a.docx"))
        assertNull(DataPaths.bookFormatOf(""))
        assertFalse(DataPaths.acceptSize(BookFormat.TXT, 1023))
        assertTrue(DataPaths.acceptSize(BookFormat.TXT, 1024))
        assertFalse(DataPaths.acceptSize(BookFormat.EPUB, 0))
        assertTrue(DataPaths.acceptSize(BookFormat.EPUB, 10))
        assertFalse(DataPaths.acceptSize(BookFormat.PDF, 0))
        assertTrue(DataPaths.acceptSize(BookFormat.PDF, 10))
    }

    @Test
    fun skipRules() {
        assertTrue(DataPaths.skipDir("/storage/emulated/0/Android", "data"))
        assertTrue(DataPaths.skipDir("/storage/emulated/0/Android", "obb"))
        assertFalse(DataPaths.skipDir("/storage/emulated/0/Android", "media"))
        assertFalse(DataPaths.skipDir("/storage/emulated/0/Books", "data"))
        assertTrue(DataPaths.skipDir("/storage/emulated/0", ".thumbnails"))
        assertTrue(DataPaths.skipDir("/storage/emulated/0", "LOST.DIR"))
        assertFalse(DataPaths.skipDir("/storage/emulated/0", "Books"))

        assertTrue(DataPaths.obviouslyNotBookFile("IMG_0001.JPG"))
        assertTrue(DataPaths.obviouslyNotBookFile("song.flac"))
        assertTrue(DataPaths.obviouslyNotBookFile("file.crdownload"))
        assertFalse(DataPaths.obviouslyNotBookFile("Books"))
        assertFalse(DataPaths.obviouslyNotBookFile("v1.2"))
        assertFalse(DataPaths.obviouslyNotBookFile("소설.txt"))
        assertFalse(DataPaths.obviouslyNotBookFile("trailing."))
        assertFalse(DataPaths.obviouslyNotBookFile(".jpg"))
    }
}
