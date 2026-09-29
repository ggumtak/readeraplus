package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UriPathsTest {

    @Test
    fun documentIds() {
        assertEquals("/storage/emulated/0/Books/a.txt", UriPaths.fromDocumentId("primary:Books/a.txt"))
        assertEquals("/storage/emulated/0", UriPaths.fromDocumentId("primary:"))
        assertEquals("/storage/1A2B-3C4D/소설/b.epub", UriPaths.fromDocumentId("1A2B-3C4D:소설/b.epub"))
        assertEquals("/storage/emulated/0/Download/c.txt", UriPaths.fromDocumentId("raw:/storage/emulated/0/Download/c.txt"))
        assertEquals("/storage/emulated/0/Documents/d.txt", UriPaths.fromDocumentId("home:d.txt"))
        assertEquals("/sdcard/e.txt", UriPaths.fromDocumentId("/sdcard/e.txt"))
        assertNull(UriPaths.fromDocumentId("msf:1234"))
        assertNull(UriPaths.fromDocumentId("12345"))
        assertNull(UriPaths.fromDocumentId(""))
        assertNull(UriPaths.fromDocumentId(null))
    }

    @Test
    fun pathShapedUris() {
        assertEquals("/storage/emulated/0/Books/a.txt", UriPaths.fromUriPath("/root/storage/emulated/0/Books/a.txt"))
        assertEquals("/storage/ABCD-1234/x.epub", UriPaths.fromUriPath("/storage/ABCD-1234/x.epub"))
        assertEquals("/storage/emulated/0/Novels/y.txt", UriPaths.fromUriPath("/external_files/Novels/y.txt"))
        assertEquals("/storage/emulated/0/Download/z.txt", UriPaths.fromUriPath("/files/sdcard/Download/z.txt"))
        assertNull(UriPaths.fromUriPath("/document/primary:Books/a.txt"))
        assertNull(UriPaths.fromUriPath(null))
    }

    @Test
    fun safeNames() {
        assertEquals("소설 1권.txt", UriPaths.safeFileName("소설 1권.txt", null, "book"))
        assertEquals("a_b_c.epub", UriPaths.safeFileName("a:b?c.epub", null, "book"))
        assertEquals("story.epub", UriPaths.safeFileName("story", "application/epub+zip", "book"))
        assertEquals("story.txt", UriPaths.safeFileName("story", "text/plain", "book"))
        assertEquals("book.txt", UriPaths.safeFileName("", "text/plain", "book"))
        assertEquals("book", UriPaths.safeFileName(null, null, "book"))
        assertEquals("x.txt", UriPaths.safeFileName("/some/dir/x.txt", null, "book"))
        assertEquals("hidden.txt", UriPaths.safeFileName("..hidden.txt", null, "book"))
        val long = "가".repeat(300) + ".txt"
        val cut = UriPaths.safeFileName(long, null, "book")
        assertEquals(144, cut.length)
        assertEquals("txt", cut.substringAfterLast('.'))
        assertEquals("ARCHIVE.EPUB", UriPaths.safeFileName("ARCHIVE.EPUB", null, "book"))
    }

    @Test
    fun numberedCopies() {
        assertEquals("소설.txt", UriPaths.numberedName("소설.txt", 1))
        assertEquals("소설 (2).txt", UriPaths.numberedName("소설.txt", 2))
        assertEquals("a.b (3).epub", UriPaths.numberedName("a.b.epub", 3))
        assertEquals("noext (2)", UriPaths.numberedName("noext", 2))
    }
}
