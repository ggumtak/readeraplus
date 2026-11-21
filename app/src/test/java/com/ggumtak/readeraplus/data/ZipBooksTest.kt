package com.ggumtak.readeraplus.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipBooksTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("zipbooks").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun writeZip(name: String, entries: List<Pair<String, Int>>, charset: Charset = Charsets.UTF_8): File {
        val f = File(dir, name)
        ZipOutputStream(FileOutputStream(f), charset).use { z ->
            for ((entry, bytes) in entries) {
                z.putNextEntry(ZipEntry(entry))
                if (!entry.endsWith("/")) z.write(ByteArray(bytes) { 'a'.code.toByte() })
                z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun zipNames() {
        assertTrue(ZipBooks.isZipName("novels.zip"))
        assertTrue(ZipBooks.isZipName("소설.ZIP"))
        assertFalse(ZipBooks.isZipName(".hidden.zip"))
        assertFalse(ZipBooks.isZipName(".zip"))
        assertFalse(ZipBooks.isZipName("a.zip.part"))
        assertFalse(ZipBooks.isZipName("a.7z"))
    }

    @Test
    fun bookEntries() {
        assertTrue(ZipBooks.isBookEntry("소설 1권.txt"))
        assertTrue(ZipBooks.isBookEntry("dir/Sub/Book.EPUB"))
        assertTrue(ZipBooks.isBookEntry("win\\path\\a.Txt"))
        assertFalse(ZipBooks.isBookEntry("dir/"))
        assertFalse(ZipBooks.isBookEntry("a.txt/"))
        assertFalse(ZipBooks.isBookEntry("__MACOSX/a.txt"))
        assertFalse(ZipBooks.isBookEntry("x/__MACOSX/a.txt"))
        assertFalse(ZipBooks.isBookEntry("._a.txt"))
        assertFalse(ZipBooks.isBookEntry("dir/.hidden.epub"))
        assertFalse(ZipBooks.isBookEntry("cover.jpg"))
        assertFalse(ZipBooks.isBookEntry("a.pdf"))
        assertFalse(ZipBooks.isBookEntry("a.txt.bak"))
        assertFalse(ZipBooks.isBookEntry(""))
    }

    @Test
    fun outNamesUseTheBaseNameOnly() {
        assertEquals("소설 1권.txt", ZipBooks.outName("작가/소설 1권.txt"))
        assertEquals("passwd.txt", ZipBooks.outName("../../etc/passwd.txt"))
        assertEquals("b.epub", ZipBooks.outName("C:\\x\\b.epub"))
        assertEquals("a_b_c.txt", ZipBooks.outName("a:b\u0001c.txt"))
        assertEquals("a.TXT", ZipBooks.outName("  a.TXT "))
        assertEquals("book.epub", ZipBooks.outName("dir/ .epub"))
        assertEquals("book.txt", ZipBooks.outName("dir/"))
        assertEquals("book.txt", ZipBooks.outName(""))
        assertEquals("book.txt", ZipBooks.outName("x/.txt"))
        val long = ZipBooks.outName("가".repeat(300) + ".txt")
        assertTrue(long.endsWith(".txt"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 200)
        // A surrogate pair is never cut in half.
        val emoji = ZipBooks.outName("\uD83D\uDE00".repeat(100) + ".txt")
        assertFalse(emoji.removeSuffix(".txt").last().isHighSurrogate())
    }

    @Test
    fun keyChangesWithSizeAndMtime() {
        val k = ZipBooks.key("/r/a.zip", 10, 20)
        assertEquals(k, ZipBooks.key("/r/a.zip", 10, 20))
        assertNotEquals(k, ZipBooks.key("/r/a.zip", 11, 20))
        assertNotEquals(k, ZipBooks.key("/r/a.zip", 10, 21))
    }

    @Test
    fun pickFiltersBySizeAndCount() {
        val entries = listOf(
            "a.txt" to 2000L,
            "readme.txt" to 10L, // below the scanner's TXT minimum
            "unknown.txt" to -1L, // size checked while copying
            "big.epub" to ZipBooks.MAX_ENTRY_BYTES + 1,
            "small.epub" to 1L,
            "cover.jpg" to 5000L,
        )
        val picked = ZipBooks.pick(entries, { it.first }, { it.second }).map { it.first }
        assertEquals(listOf("a.txt", "unknown.txt", "small.epub"), picked)
        val many = (1..ZipBooks.MAX_ENTRIES + 5).map { "b$it.txt" to 2000L }
        assertEquals(ZipBooks.MAX_ENTRIES, ZipBooks.pick(many, { it.first }, { it.second }).size)
    }

    @Test
    fun realZipEntriesAreFiltered() {
        val f = writeZip(
            "novels.zip",
            listOf(
                "소설/1권.txt" to 2048,
                "__MACOSX/소설/._1권.txt" to 2048,
                "소설/cover.jpg" to 2048,
                "소설/" to 0,
            ),
        )
        ZipFile(f, Charsets.UTF_8).use { z ->
            val picked = ZipBooks.pick(z.entries().toList(), { it.name }, { it.size })
            assertEquals(listOf("소설/1권.txt"), picked.map { it.name })
            assertEquals("1권.txt", ZipBooks.outName(picked.single().name))
        }
    }

    @Test
    fun openReadsUtf8Names() {
        val f = writeZip("u.zip", listOf("소설.txt" to 2048))
        val opened = ZipBooks.open(f)
        opened.zip.use { assertEquals(listOf("소설.txt"), opened.entries.map { it.name }) }
    }

    @Test
    fun openFallsBackToCp949Names() {
        assumeTrue(Charset.isSupported("MS949"))
        val f = writeZip("k.zip", listOf("한글 소설.txt" to 2048), Charset.forName("MS949"))
        val opened = ZipBooks.open(f)
        opened.zip.use { assertEquals(listOf("한글 소설.txt"), opened.entries.map { it.name }) }
    }

    @Test
    fun copiesNeverTakeTheStoragesLastRoom() {
        val min = ZipBooks.MIN_FREE_BYTES
        // Exactly the reserve left after the entry: allowed; one byte less: not.
        assertTrue(ZipBooks.hasRoom(min + 5_000_000, 5_000_000))
        assertFalse(ZipBooks.hasRoom(min + 5_000_000 - 1, 5_000_000))
        // An entry of unknown size counts as nothing here (the copy's own limits still apply).
        assertTrue(ZipBooks.hasRoom(min, -1))
        assertFalse(ZipBooks.hasRoom(min - 1, -1))
        assertFalse(ZipBooks.hasRoom(0, 0))
    }

    private fun temp(text: String): File =
        File(dir, "${BookCopies.COPY_PREFIX}${System.nanoTime()}${BookCopies.PART_SUFFIX}").apply { writeText(text) }

    @Test
    fun placeCopyAddsANewCopyAtAPathNotRemoved() {
        val placed = ZipBooks.placeCopy(temp("story"), dir, "a.txt") { false }
        assertEquals(File(dir, "a.txt"), placed)
        assertEquals("story", File(dir, "a.txt").readText())
    }

    @Test
    fun placeCopyDeletesANewCopyAtARemovedPath() {
        val tmp = temp("story")
        val placed = ZipBooks.placeCopy(tmp, dir, "a.txt") { it.name == "a.txt" }
        assertEquals(null, placed)
        // Neither the copy nor the temp file stays in the copy folder.
        assertFalse(File(dir, "a.txt").exists())
        assertFalse(tmp.exists())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun placeCopyKeepsAReusedCopyAtARemovedPath() {
        // An identical earlier copy (another row may point at it) is reused, not deleted, even when ignored.
        File(dir, "a.txt").writeText("story")
        val tmp = temp("story")
        val placed = ZipBooks.placeCopy(tmp, dir, "a.txt") { it.name == "a.txt" }
        assertEquals(null, placed)
        assertEquals("story", File(dir, "a.txt").readText())
        assertFalse(tmp.exists())
    }

    @Test
    fun placeCopyDeletesOnlyTheNewNumberedCopy() {
        // A different book already holds the name: the new copy becomes "a (2).txt", and only that one goes.
        File(dir, "a.txt").writeText("other")
        val placed = ZipBooks.placeCopy(temp("story"), dir, "a.txt") { it.name == "a (2).txt" }
        assertEquals(null, placed)
        assertEquals("other", File(dir, "a.txt").readText())
        assertFalse(File(dir, "a (2).txt").exists())
    }

    @Test
    fun settleTrackedTellsNewFromReused() {
        val first = BookCopies.settleTracked(temp("story"), dir, "a.txt")
        assertTrue(first.isNew)
        val second = BookCopies.settleTracked(temp("story"), dir, "a.txt")
        assertFalse(second.isNew)
        assertEquals(first.file, second.file)
    }
}
