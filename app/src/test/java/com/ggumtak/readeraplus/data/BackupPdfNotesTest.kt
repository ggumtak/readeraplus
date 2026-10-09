package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.reader.pdf.InkStroke
import com.ggumtak.readeraplus.reader.pdf.InkTool
import com.ggumtak.readeraplus.reader.pdf.PdfNotes
import com.ggumtak.readeraplus.reader.pdf.PdfNotesStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files

/** R4: a PDF book's ink and bookmarks travel in the backup as one `pdfNotes` string per book. */
class BackupPdfNotesTest {

    private val roots = ArrayList<File>()

    private fun tempDir(): File = Files.createTempDirectory("backuppdf").toFile().also { roots += it }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun pdf(id: Long, name: String) = Book(
        id = id, path = "/storage/emulated/0/Books/$name.pdf", fileName = "$name.pdf", title = name, author = "",
        series = null, seriesIndex = null, format = BookFormat.PDF, sizeBytes = 1_000L + id, modifiedAt = 1,
        addedAt = 2, lastReadAt = 3,
    )

    private fun entry(id: Long, name: String): BackupBook =
        BackupJson.fromBook(pdf(id, name), false, emptyList(), emptyList(), emptyList())

    private fun notes(page: Int, mark: Int): String {
        val n = PdfNotes()
        n.add(page, InkStroke(InkTool.PEN, 0xFF000000.toInt(), 2f, floatArrayOf(1f, 2f, 3f, 4.5f)))
        n.add(page, InkStroke(InkTool.HIGHLIGHTER, 0x80FFFF00.toInt(), 12f, floatArrayOf(5f, 6f, 7f, 8f), floatArrayOf(0.5f, 1f)))
        n.toggleBookmark(mark)
        return n.toJson()
    }

    private fun streamed(data: BackupData): String = StringWriter().also { BackupJson.write(data, it) }.toString()

    private fun data(books: List<BackupBook>, loader: ((BackupBook) -> String?)? = null) =
        BackupData(1, 99, books, emptyList(), null, 0, null, null, loader)

    @Test
    fun roundTripThroughTheTreeAndTheStream() {
        val text = notes(page = 3, mark = 8)
        val b = entry(1, "논문").copy(pdfNotes = text)
        val d = data(listOf(b))
        assertEquals(text, BackupJson.parse(BackupJson.toJson(d).toString()).books.single().pdfNotes)
        val file = streamed(d)
        assertEquals(text, BackupJson.parse(file).books.single().pdfNotes)
        // One string value, not a nested object.
        assertEquals(text, JSONObject(file).getJSONArray("books").getJSONObject(0).getString("pdfNotes"))
        // The restored entry is the written one (sourceId is never written).
        assertEquals(listOf(b), BackupJson.parse(file).books)
    }

    @Test
    fun theStreamedBookCarriesTheNotesTheLoaderReadsAtWriteTime() {
        val a = notes(page = 1, mark = 2)
        val c = notes(page = 5, mark = 9)
        val files = mapOf(1L to a, 3L to c)
        val asked = ArrayList<Long>()
        val books = listOf(
            entry(1, "하나").copy(sourceId = 1),
            entry(2, "둘"), // no notes file: sourceId stays 0, the loader is not asked
            entry(3, "셋").copy(sourceId = 3),
        )
        val d = data(books) { asked += it.sourceId; files[it.sourceId] }
        val text = streamed(d)
        assertEquals(listOf(1L, 3L), asked)
        val back = BackupJson.parse(text).books
        assertEquals(listOf(a, null, c), back.map { it.pdfNotes })
        assertTrue(back.all { it.sourceId == 0L })
        assertFalse(JSONObject(text).getJSONArray("books").getJSONObject(1).has("pdfNotes"))
        assertFalse(text.contains("sourceId"))
        // The same stream the tree would give for books that carry the text themselves.
        val inline = data(books.map { it.copy(pdfNotes = files[it.sourceId], sourceId = 0) })
        assertEquals(BackupJson.parse(streamed(inline)).books, back)
    }

    @Test
    fun theLoaderIsNotAskedForABookThatCarriesItsText() {
        val own = notes(page = 1, mark = 2)
        val d = data(listOf(entry(1, "하나").copy(pdfNotes = own, sourceId = 1))) { throw AssertionError("asked") }
        assertEquals(own, BackupJson.parse(streamed(d)).books.single().pdfNotes)
    }

    @Test
    fun awkwardTextSurvivesTheSplice() {
        val tricky = "{\"v\":1,\"s\":\"따옴표 \\\" 역슬래시 \\\\ </script> 줄\\n바꿈   탭\t\",\"bookmarks\":[1],\"pages\":{}}"
        val d = data(listOf(entry(1, "하나").copy(sourceId = 1), entry(2, "둘"))) { tricky }
        val text = streamed(d)
        // The whole file is valid JSON and the string comes back byte for byte.
        JSONObject(text)
        assertEquals(tricky, BackupJson.parse(text).books[0].pdfNotes)
        assertNull(BackupJson.parse(text).books[1].pdfNotes)
    }

    @Test
    fun checkpointsStillRunAfterTheHeaderAndEveryBook() {
        val books = listOf(entry(1, "하나").copy(sourceId = 1), entry(2, "둘").copy(sourceId = 2))
        var checks = 0
        BackupJson.write(data(books) { notes(1, 1) }, StringWriter(), checkpoint = { checks++ })
        assertEquals(1 + books.size, checks)
    }

    @Test
    fun notesBeyondTheTotalBudgetStayOutOfTheBackup() {
        val books = listOf(
            entry(1, "큰것").copy(sourceId = 1),
            entry(2, "큰것2").copy(sourceId = 2), // would pass the budget: left out
            entry(3, "작은것").copy(sourceId = 3), // still fits
            entry(4, "빈것").copy(sourceId = 4), // the loader found nothing
            entry(5, "딱맞는것").copy(sourceId = 5), // uses up the budget exactly
            entry(6, "넘치는것").copy(sourceId = 6),
        )
        val texts = mapOf(1L to "a".repeat(60), 2L to "b".repeat(60), 3L to "c".repeat(30), 4L to "", 5L to "e".repeat(10),
            6L to "f")
        val w = StringWriter()
        BackupJson.write(data(books) { texts[it.sourceId] }, w, pdfNotesBudget = 100)
        assertEquals(listOf("a".repeat(60), null, "c".repeat(30), null, "e".repeat(10), null),
            BackupJson.parse(w.toString()).books.map { it.pdfNotes })
        // The default budget keeps a backup far below the restore's 64 MB guard (chars here, up to 3 bytes each).
        assertTrue(PdfNoteFiles.MAX_BACKUP_TOTAL_CHARS * 3L < Backup.MAX_BYTES)
        assertTrue(PdfNoteFiles.MAX_BACKUP_BOOK_CHARS <= PdfNoteFiles.MAX_BACKUP_TOTAL_CHARS)
    }

    @Test
    fun parsingIsTolerant() {
        fun notesOf(extra: String): String? =
            BackupJson.parse("""{"books":[{"path":"/a.pdf"$extra}]}""").books.single().pdfNotes
        val ok = """{"v":1,"bookmarks":[2],"pages":{}}"""
        assertEquals(ok, notesOf(""","pdfNotes":${JSONObject.quote(ok)}"""))
        // A nested object is taken as its text; anything else is nothing.
        assertEquals(JSONObject(ok).toString(), notesOf(""","pdfNotes":$ok"""))
        assertNull(notesOf(""))
        assertNull(notesOf(",\"pdfNotes\":null"))
        assertNull(notesOf(",\"pdfNotes\":\"\""))
        assertNull(notesOf(",\"pdfNotes\":\"   \""))
        assertNull(notesOf(",\"pdfNotes\":5"))
        assertNull(notesOf(",\"pdfNotes\":[1,2]"))
        assertNull(notesOf(",\"pdfNotes\":true"))
    }

    @Test
    fun aBooksNotesOverTheCapAreDroppedOnRead() {
        val o = JSONObject().put("pdfNotes", "x".repeat(11))
        assertEquals("x".repeat(11), BackupJson.pdfNotesFromJson(o, maxChars = 11))
        assertNull(BackupJson.pdfNotesFromJson(o, maxChars = 10))
        assertEquals("x".repeat(11), BackupJson.pdfNotesFromJson(o))
    }

    @Test
    fun anOldBackupWithoutTheKeyRestoresAsBefore() {
        val old = """{"format":"readeraplus-backup","version":1,"createdAt":5,"collections":[],
            "books":[{"path":"/storage/emulated/0/Books/a.pdf","fileName":"a.pdf","size":10,"format":"PDF",
            "lastReadAt":7,"bookmarks":[{"section":3,"offset":0}],"quotes":[]}]}"""
        val b = BackupJson.parse(old).books.single()
        assertNull(b.pdfNotes)
        assertEquals(0L, b.sourceId)
        assertEquals(1, b.bookmarks.size)
        assertEquals(7L, b.lastReadAt)
        // Nothing is written for a book without notes, so an old build reads the file exactly as before.
        val out = streamed(data(listOf(b)))
        assertFalse(out.contains("pdfNotes"))
        assertEquals("1,1,1,0", BackupJson.readHeader(StringReader(out)).summary.toString())
    }

    @Test
    fun theHeaderCountsIgnoreTheNotes() {
        // The streaming header reader (also what an older build runs) skips the unknown key.
        val books = listOf(entry(1, "하나").copy(sourceId = 1), entry(2, "둘").copy(sourceId = 2))
        val with = streamed(data(books) { notes(1, 1) })
        val without = streamed(data(books))
        assertTrue(with.contains("pdfNotes"))
        assertEquals(
            BackupJson.readHeader(StringReader(without)).summary.toString(),
            BackupJson.readHeader(StringReader(with)).summary.toString(),
        )
        val noSummary = BackupJson.readHeader(StringReader(with)).summary
        assertEquals(2, noSummary.books)
        assertEquals(BackupJson.summaryOf(books).toString(), noSummary.toString())
    }

    @Test
    fun aBookWithOnlyPdfNotesIsWorthAPlaceholder() {
        val bare = entry(1, "하나")
        assertFalse(BackupMerge.hasNotes(bare))
        assertFalse(BackupMerge.needsPlaceholder(bare))
        val inked = bare.copy(pdfNotes = notes(page = 1, mark = 2))
        assertTrue(BackupMerge.hasNotes(inked))
        assertTrue(BackupMerge.needsPlaceholder(inked))
        // Notes that hold nothing do not count.
        assertFalse(BackupMerge.hasNotes(bare.copy(pdfNotes = PdfNotes().toJson())))
        assertFalse(BackupMerge.hasNotes(bare.copy(pdfNotes = "garbage")))
    }

    @Test
    fun backupToAnotherDeviceWritesTheNotesUnderTheNewId() {
        // Device 1: book 42 with ink. Its backup is streamed from the notes file, as Backup.snapshot sets it up.
        val dir1 = tempDir()
        val ink = notes(page = 4, mark = 2)
        assertTrue(PdfNoteFiles.writeText(dir1, 42L, ink))
        val sent = streamed(
            data(listOf(entry(42, "논문").copy(sourceId = 42))) { PdfNoteFiles.readText(dir1, it.sourceId) },
        )
        // Device 2: the same book is row 7 there (the resolver matched it by path / name + size).
        val dir2 = tempDir()
        val restored = BackupJson.parse(sent).books.single()
        assertTrue(PdfNoteFiles.restore(dir2, 7L, restored.pdfNotes!!))
        assertEquals(listOf("7.json"), dir2.list()!!.toList())
        assertEquals(ink, PdfNotesStore.load(dir2, 7L).toJson())
        assertTrue(PdfNotesStore.load(dir2, 42L).isEmpty)
        // A second restore changes nothing; one over notes the device drew meanwhile keeps them and adds the backup's.
        assertFalse(PdfNoteFiles.restore(dir2, 7L, restored.pdfNotes!!))
        val own = notes(page = 9, mark = 9)
        assertTrue(PdfNoteFiles.writeText(dir2, 7L, own))
        assertTrue(PdfNoteFiles.restore(dir2, 7L, restored.pdfNotes!!))
        val both = PdfNotesStore.load(dir2, 7L)
        assertEquals(listOf(4, 9), inked(both))
        assertEquals(listOf(2, 9), both.bookmarks().toList())
    }

    /** The pages with ink, ascending. */
    private fun inked(n: PdfNotes): List<Int> = (0..50).filter { n.strokes(it).isNotEmpty() }
}
