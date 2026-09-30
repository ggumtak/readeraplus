package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScanWalkTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("scanwalk").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(rel: String, bytes: Int = 2048): File {
        val f = File(root, rel)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes) { 'a'.code.toByte() })
        return f
    }

    private fun rel(paths: Collection<String>): Set<String> =
        paths.map { it.removePrefix(root.path + "/") }.toSet()

    @Test
    fun findsBooksAndSkipsJunk() {
        file("Books/novel 1.txt")
        file("Books/Sub/Deep/Book.EPUB", 10)
        file("Books/small.txt", 100) // < 1 KB
        file("Books/cover.jpg")
        file("Books/._novel 1.txt") // AppleDouble
        file(".hidden/secret.txt")
        file("Android/data/com.x/files/a.txt")
        file("Android/obb/b.txt")
        file("Android/media/org.telegram/Telegram Documents/c.txt")
        file("Excluded/d.txt")
        file("Excluded2/Inner/e.txt")
        file("unpacked.epub/OEBPS/f.txt") // a folder named like a book
        file("empty.epub", 0)
        val calls = ArrayList<Int>()
        val r = FileScanner.walk(
            listOf(root.path),
            listOf(File(root, "Excluded").path, File(root, "Excluded2/Inner").path),
        ) { calls += it }
        assertEquals(
            setOf(
                "Books/novel 1.txt", "Books/Sub/Deep/Book.EPUB",
                "Android/media/org.telegram/Telegram Documents/c.txt", "unpacked.epub/OEBPS/f.txt",
            ),
            rel(r.found.keys),
        )
        val epub = r.found.values.first { it.name == "Book.EPUB" }
        assertEquals(BookFormat.EPUB, epub.format)
        assertEquals(10L, epub.size)
        assertTrue(epub.mtime > 0)
        assertEquals(listOf(root.path), r.listedRoots)
        assertEquals(4, calls.last())
    }

    @Test
    fun symlinkLoopsTerminateAndMissingRootsAreNotListed() {
        file("A/book.txt")
        val link = File(root, "A/loop").toPath()
        try {
            Files.createSymbolicLink(link, root.toPath())
        } catch (_: Exception) {
            return // filesystem without symlinks: nothing to test
        }
        val r = FileScanner.walk(listOf(root.path, File(root, "missing").path), emptyList()) {}
        assertEquals(setOf("A/book.txt"), rel(r.found.keys))
        assertEquals(listOf(root.path), r.listedRoots)
    }

    @Test
    fun progressIsReportedInSteps() {
        for (i in 0 until 120) file("Many/b$i.txt")
        val calls = ArrayList<Int>()
        val r = FileScanner.walk(listOf(root.path), emptyList()) { calls += it }
        assertEquals(120, r.found.size)
        assertEquals(listOf(50, 100, 120), calls)
    }

    @Test
    fun stopBeforeAnythingIsListed() {
        file("Books/a.txt")
        var checks = 0
        try {
            FileScanner.walk(listOf(root.path), emptyList(), stopWhen = { checks++; true }) { fail("nothing may be found") }
            fail("expected Stopped")
        } catch (_: FileScanner.Stopped) {
        }
        assertEquals(1, checks)
    }

    @Test
    fun stopIsCheckedBetweenDirectories() {
        for (d in 0 until 5) file("D$d/book.txt")
        var checks = 0
        var stopAfter = Int.MAX_VALUE
        val stop = { ++checks > stopAfter }
        // Unstopped: one check per root plus one per queued directory (5).
        val all = FileScanner.walk(listOf(root.path), emptyList(), stop) {}
        assertEquals(5, all.found.size)
        assertEquals(6, checks)
        // Stopping at the third directory: the walk ends there and returns nothing.
        checks = 0
        stopAfter = 3
        try {
            FileScanner.walk(listOf(root.path), emptyList(), stop) {}
            fail("expected Stopped")
        } catch (_: FileScanner.Stopped) {
        }
        assertEquals(4, checks)
    }

    @Test
    fun stopIsCheckedInsideALargeDirectory() {
        for (i in 0 until 300) file("Big/b$i.txt")
        var readerInFront = false
        try {
            // The reader comes to the front once 50 books are found: the walk stops within the same directory.
            FileScanner.walk(listOf(File(root, "Big").path), emptyList(), { readerInFront }) { n -> if (n >= 50) readerInFront = true }
            fail("expected Stopped")
        } catch (_: FileScanner.Stopped) {
        }
    }

    @Test
    fun excludedRootIsSkipped() {
        file("Books/a.txt")
        val r = FileScanner.walk(listOf(File(root, "Books").path), listOf(root.path)) {}
        assertTrue(r.found.isEmpty())
        assertTrue(r.listedRoots.isEmpty())
    }
}
