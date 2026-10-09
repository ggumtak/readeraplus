package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.reader.pdf.InkStroke
import com.ggumtak.readeraplus.reader.pdf.InkTool
import com.ggumtak.readeraplus.reader.pdf.PdfNotes
import com.ggumtak.readeraplus.reader.pdf.PdfNotesStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The PDF notes files as the library and the backup reach them (same files as the viewer's `PdfNotesStore`). */
class PdfNoteFilesTest {

    private val roots = ArrayList<File>()

    private fun tempDir(): File = Files.createTempDirectory("pdfnotefiles").toFile().also { roots += it }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun notes(page: Int = 1, mark: Int = 6): String {
        val n = PdfNotes()
        n.add(page, InkStroke(InkTool.PEN, 0xFF000000.toInt(), 2f, floatArrayOf(1f, 2f, 3f, 4f)))
        n.toggleBookmark(mark)
        return n.toJson()
    }

    @Test
    fun samePathAsTheViewersStore() {
        val dir = File(tempDir(), PdfNoteFiles.DIR)
        assertEquals("pdf_notes", PdfNoteFiles.DIR)
        assertEquals(PdfNotesStore.file(dir, 42L), PdfNoteFiles.file(dir, 42L))
        assertEquals(File(dir, "42.json"), PdfNoteFiles.file(dir, 42L))
    }

    @Test
    fun writeReadDeleteRoundTrip() {
        val dir = File(tempDir(), "nested/pdf_notes") // not created yet
        val text = notes()
        assertFalse(PdfNoteFiles.exists(dir, 5L))
        assertNull(PdfNoteFiles.readText(dir, 5L))
        assertTrue(PdfNoteFiles.writeText(dir, 5L, text))
        assertTrue(PdfNoteFiles.exists(dir, 5L))
        assertEquals(text, PdfNoteFiles.readText(dir, 5L))
        // Nothing but the notes file is left behind (the temp file is renamed away), and the viewer reads it.
        assertEquals(listOf("5.json"), dir.list()!!.toList())
        assertEquals(text, PdfNotesStore.load(dir, 5L).toJson())
        // Overwrite works.
        val other = notes(page = 9, mark = 1)
        assertTrue(PdfNoteFiles.writeText(dir, 5L, other))
        assertEquals(other, PdfNoteFiles.readText(dir, 5L))
        PdfNoteFiles.delete(dir, 5L)
        assertFalse(PdfNoteFiles.exists(dir, 5L))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun deleteNeverThrows() {
        val root = tempDir()
        PdfNoteFiles.delete(File(root, "no/such/dir"), 1L) // nothing there
        PdfNoteFiles.delete(root, 1L) // a missing file
        // A directory named like the file: cannot be deleted while it has content, and that is not an error.
        val blocker = PdfNoteFiles.file(root, 2L)
        assertTrue(blocker.mkdirs())
        File(blocker, "x").writeText("x")
        PdfNoteFiles.delete(root, 2L)
        assertTrue(blocker.exists())
        // And a write that cannot happen reports false instead of throwing.
        val notADir = File(root, "file")
        notADir.writeText("not a folder")
        assertFalse(PdfNoteFiles.writeText(notADir, 3L, "{}"))
    }

    @Test
    fun readTextSkipsBlankAndOversizedFiles() {
        val dir = tempDir()
        PdfNoteFiles.file(dir, 1L).writeText("  \n ")
        assertNull(PdfNoteFiles.readText(dir, 1L))
        PdfNoteFiles.file(dir, 2L).writeText("x".repeat(100))
        assertNull(PdfNoteFiles.readText(dir, 2L, maxChars = 99))
        assertEquals(100, PdfNoteFiles.readText(dir, 2L, maxChars = 100)!!.length)
    }

    @Test
    fun idsAreTheBooksWithANotesFile() {
        val dir = tempDir()
        assertTrue(PdfNoteFiles.ids(File(dir, "none")).isEmpty())
        PdfNoteFiles.writeText(dir, 7L, notes())
        PdfNoteFiles.writeText(dir, 12L, notes())
        File(dir, "x.json").writeText("{}")
        File(dir, "3.json.tmp").writeText("{}")
        assertEquals(setOf(7L, 12L), PdfNoteFiles.ids(dir))
    }

    @Test
    fun contentMeansABookmarkOrAStroke() {
        assertTrue(PdfNoteFiles.hasContent(notes()))
        assertTrue(PdfNoteFiles.hasContent("""{"v":1,"bookmarks":[3],"pages":{}}"""))
        assertTrue(PdfNoteFiles.hasContent("""{"v":1,"bookmarks":[],"pages":{"2":[{"t":0}]}}"""))
        assertFalse(PdfNoteFiles.hasContent(PdfNotes().toJson()))
        assertFalse(PdfNoteFiles.hasContent("""{"v":1,"bookmarks":[],"pages":{"2":[]}}"""))
        assertFalse(PdfNoteFiles.hasContent("{}"))
        // Found by a scan, whatever comes first and however it is spaced; strings may hold the key names.
        assertTrue(PdfNoteFiles.hasContent(""" { "pages" : { "1" : [ ] , "5" : [ { "t" : 0 } ] } , "v" : 1 } """))
        assertTrue(PdfNoteFiles.hasContent("""{"x":{"bookmarks":[1]},"note":"\"pages\"","bookmarks":[0]}"""))
        assertFalse(PdfNoteFiles.hasContent("""{"x":{"bookmarks":[1],"pages":{"1":[{}]}},"bookmarks":[]}"""))
        assertFalse(PdfNoteFiles.hasContent("""{"v":1,"bookmarks":[-1],"pages":{"2":[3]}}"""))
        assertFalse(PdfNoteFiles.hasContent("""{"v":"unterminated"""))
        assertFalse(PdfNoteFiles.hasContent("{{{ definitely not json"))
        assertFalse(PdfNoteFiles.hasContent("5"))
        assertFalse(PdfNoteFiles.hasContent(""))
        assertFalse(PdfNoteFiles.hasContent(null))
    }

    @Test
    fun theDevicesNotesStayAndTheBackupFillsInWhereThereAreNone() {
        val backup = notes(page = 3, mark = 8)
        val device = notes(page = 1, mark = 6)
        assertEquals(backup, PdfNoteFiles.merged(null, backup))
        // The viewer reads these as no notes: the backup's take their place.
        assertEquals(backup, PdfNoteFiles.merged(PdfNotes().toJson(), backup))
        assertEquals(backup, PdfNoteFiles.merged("{{{ garbage", backup))
        // Both have notes: the device's page 1 and the backup's page 3, both bookmarks.
        val both = PdfNotes.fromJson(PdfNoteFiles.merged(device, backup)!!)
        assertEquals(listOf(1, 3), inked(both))
        assertEquals(listOf(6, 8), both.bookmarks().toList())
        // Nothing to restore.
        assertNull(PdfNoteFiles.merged(null, null))
        assertNull(PdfNoteFiles.merged(null, PdfNotes().toJson()))
        assertNull(PdfNoteFiles.merged(null, "garbage"))
        assertNull(PdfNoteFiles.merged(device, device))
    }

    @Test
    fun aPageWithInkOnTheDeviceKeepsItsOwnStrokes() {
        // The same page drawn on both: the device's strokes stay (an erase made after the backup is not undone).
        val device = PdfNotes().also { it.add(2, InkStroke(InkTool.PEN, 1, 3f, floatArrayOf(9f, 9f, 8f, 8f))) }.toJson()
        val backup = PdfNotes().also {
            it.add(2, InkStroke(InkTool.PEN, 1, 2f, floatArrayOf(1f, 1f, 2f, 2f)))
            it.add(2, InkStroke(InkTool.HIGHLIGHTER, 1, 9f, floatArrayOf(1f, 5f, 2f, 5f)))
        }.toJson()
        assertNull(PdfNoteFiles.merged(device, backup))
        val withMark = PdfNotes().also {
            it.add(2, InkStroke(InkTool.PEN, 1, 2f, floatArrayOf(1f, 1f, 2f, 2f)))
            it.toggleBookmark(4)
        }.toJson()
        val m = PdfNotes.fromJson(PdfNoteFiles.merged(device, withMark)!!)
        assertEquals(1, m.strokes(2).size)
        assertEquals(3f, m.strokes(2)[0].width)
        assertEquals(listOf(4), m.bookmarks().toList())
    }

    @Test
    fun restoreWritesUnderTheIdOfThisDevice() {
        val dir = tempDir()
        val text = notes(page = 3, mark = 8)
        // The book had id 42 on the other device and is 7 here.
        assertTrue(PdfNoteFiles.restore(dir, 7L, text))
        assertEquals(listOf("7.json"), dir.list()!!.toList())
        assertEquals(text, PdfNotesStore.load(dir, 7L).toJson())
        assertTrue(PdfNotesStore.load(dir, 42L).isEmpty)
    }

    @Test
    fun restoreKeepsTheDevicesFileButReplacesACorruptOne() {
        val dir = tempDir()
        val device = notes(page = 1, mark = 6)
        assertTrue(PdfNoteFiles.writeText(dir, 7L, device))
        // The device's notes stay; the backup's other page and bookmark join them.
        assertTrue(PdfNoteFiles.restore(dir, 7L, notes(page = 3, mark = 8)))
        val joined = PdfNotesStore.load(dir, 7L)
        assertEquals(listOf(1, 3), inked(joined))
        assertEquals(listOf(6, 8), joined.bookmarks().toList())
        assertFalse(PdfNoteFiles.restore(dir, 7L, notes(page = 3, mark = 8)))
        // Restoring the same backup twice changes nothing either.
        val fresh = tempDir()
        val backup = notes(page = 3, mark = 8)
        assertTrue(PdfNoteFiles.restore(fresh, 1L, backup))
        assertFalse(PdfNoteFiles.restore(fresh, 1L, backup))
        assertEquals(backup, PdfNoteFiles.readText(fresh, 1L))
        // A corrupt file reads as no notes in the viewer, so the backup replaces it.
        PdfNoteFiles.file(dir, 8L).writeText("{{{ not json")
        assertTrue(PdfNoteFiles.restore(dir, 8L, backup))
        assertEquals(backup, PdfNoteFiles.readText(dir, 8L))
    }

    @Test
    fun restoreOfNothingWritesNothing() {
        val dir = tempDir()
        assertFalse(PdfNoteFiles.restore(dir, 7L, PdfNotes().toJson()))
        assertFalse(PdfNoteFiles.restore(dir, 7L, "not json"))
        assertEquals(0, dir.list()!!.size)
    }

    /** The pages with ink, ascending. */
    private fun inked(n: PdfNotes): List<Int> = (0..50).filter { n.strokes(it).isNotEmpty() }
}
