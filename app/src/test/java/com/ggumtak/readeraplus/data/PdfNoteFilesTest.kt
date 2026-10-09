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
    fun contentMeansABookmarkOrAStroke() {
        assertTrue(PdfNoteFiles.hasContent(notes()))
        assertTrue(PdfNoteFiles.hasContent("""{"v":1,"bookmarks":[3],"pages":{}}"""))
        assertTrue(PdfNoteFiles.hasContent("""{"v":1,"bookmarks":[],"pages":{"2":[{"t":0}]}}"""))
        assertFalse(PdfNoteFiles.hasContent(PdfNotes().toJson()))
        assertFalse(PdfNoteFiles.hasContent("""{"v":1,"bookmarks":[],"pages":{"2":[]}}"""))
        assertFalse(PdfNoteFiles.hasContent("{}"))
        assertFalse(PdfNoteFiles.hasContent("{{{ definitely not json"))
        assertFalse(PdfNoteFiles.hasContent("5"))
        assertFalse(PdfNoteFiles.hasContent(""))
        assertFalse(PdfNoteFiles.hasContent(null))
    }

    @Test
    fun theDevicesNotesStayAndTheBackupFillsInWhereThereAreNone() {
        val backup = notes(page = 3, mark = 8)
        val device = notes(page = 1, mark = 6)
        assertTrue(PdfNoteFiles.shouldRestore(null, backup))
        assertFalse(PdfNoteFiles.shouldRestore(device, backup))
        // The viewer reads these as no notes: the backup's may take their place.
        assertTrue(PdfNoteFiles.shouldRestore(PdfNotes().toJson(), backup))
        assertTrue(PdfNoteFiles.shouldRestore("{{{ garbage", backup))
        // Nothing to restore.
        assertFalse(PdfNoteFiles.shouldRestore(null, null))
        assertFalse(PdfNoteFiles.shouldRestore(null, PdfNotes().toJson()))
        assertFalse(PdfNoteFiles.shouldRestore(null, "garbage"))
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
        assertFalse(PdfNoteFiles.restore(dir, 7L, notes(page = 3, mark = 8)))
        assertEquals(device, PdfNoteFiles.readText(dir, 7L))
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
}
