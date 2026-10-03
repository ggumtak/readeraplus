package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonTest {

    private val book = Book(
        id = 3, path = "/storage/emulated/0/Books/여름 이야기.txt", fileName = "여름 이야기.txt", title = "여름 이야기",
        author = "홍길동", series = "계절", seriesIndex = 2f, format = BookFormat.TXT, sizeBytes = 4096,
        modifiedAt = 111, addedAt = 222, lastReadAt = 333, posSection = 4, posOffset = 55, progress = 0.5f,
        favorite = true, toRead = false, haveRead = true, trashed = false, review = "재미있다\n두 줄",
        encoding = "MS949", language = "ko", readingSeconds = 777,
    )

    private val sample = BackupJson.fromBook(
        book, metaLocked = true,
        collections = listOf("좋아하는 책", "여름"),
        bookmarks = listOf(Bookmark(1, 3, 1, 10, "첫 장면 \"따옴표\"", 1000, "메모")),
        quotes = listOf(Quote(2, 3, 2, 5, 9, "인용 문장", "", 2000)),
    )

    @Test
    fun roundTrip() {
        val data = BackupData(1, 999, listOf(sample), listOf("좋아하는 책", "여름", "빈 컬렉션"), JSONObject().put("x", 1))
        val text = BackupJson.toJson(data).toString(1)
        val back = BackupJson.parse(text)
        assertEquals(1, back.version)
        assertEquals(999L, back.createdAt)
        assertEquals(listOf("좋아하는 책", "여름", "빈 컬렉션"), back.collections)
        assertEquals(listOf(sample), back.books)
        assertEquals(1, back.settings!!.getInt("x"))
        val root = JSONObject(text)
        assertEquals(BackupJson.FORMAT, root.getString("format"))
    }

    @Test
    fun fromBookCopiesFields() {
        assertEquals("/storage/emulated/0/Books/여름 이야기.txt", sample.path)
        assertEquals("TXT", sample.format)
        assertTrue(sample.metaLocked)
        assertEquals(1, sample.bookmarks.size)
        assertEquals(BackupBookmark(1, 10, "첫 장면 \"따옴표\"", "메모", 1000), sample.bookmarks[0])
        assertEquals(BackupQuote(2, 5, 9, "인용 문장", "", 2000), sample.quotes[0])
        assertEquals(777L, sample.readingSeconds)
    }

    @Test
    fun nonFiniteNumbersAreWrittenSafely() {
        val b = sample.copy(progress = Float.NaN, seriesIndex = Float.POSITIVE_INFINITY)
        val o = BackupJson.bookToJson(b)
        assertEquals(0.0, o.getDouble("progress"), 0.0)
        assertTrue(o.isNull("seriesIndex"))
        val back = BackupJson.bookFromJson(o)!!
        assertNull(back.seriesIndex)
    }

    @Test
    fun tolerantParsing() {
        val text = """
            {"version": "2", "books": [
              {"path": "/a/b.txt"},
              {"fileName": "only-name.epub", "size": "1234", "favorite": 1, "toRead": "true",
               "progress": 7, "series": null, "language": null, "posSection": -3, "title": null,
               "collections": ["x", 5, null, "", {"a":1}], "bookmarks": [3, {"offset": 9}, null],
               "quotes": [{"start": 20, "end": 10, "text": "t"}]},
              {"title": "no file"},
              "garbage", 42, null
            ], "collections": "not an array"}
        """.trimIndent()
        val d = BackupJson.parse(text)
        assertEquals(2, d.version)
        assertEquals(emptyList<String>(), d.collections)
        assertNull(d.settings)
        assertEquals(2, d.books.size)

        val a = d.books[0]
        assertEquals("/a/b.txt", a.path)
        assertEquals("b.txt", a.fileName)
        assertEquals(-1L, a.size)
        assertFalse(a.favorite)
        assertEquals(0f, a.progress, 0f)

        val b = d.books[1]
        assertEquals("", b.path)
        assertEquals("only-name.epub", b.fileName)
        assertEquals(1234L, b.size)
        assertTrue(b.favorite)
        assertTrue(b.toRead)
        assertEquals(1f, b.progress, 0f)
        assertNull(b.series)
        assertNull(b.language)
        assertEquals("", b.title) // JSON null is "missing", never the string "null"
        assertEquals(0, b.posSection)
        assertEquals(listOf("x", "5"), b.collections)
        assertEquals(listOf(BackupBookmark(0, 9, "", "", 0)), b.bookmarks)
        assertEquals(listOf(BackupQuote(0, 10, 20, "t", "", 0)), b.quotes)
    }

    @Test
    fun minimalBackupAndBom() {
        val d = BackupJson.parse("﻿ {\"books\": []} ")
        assertEquals(BackupJson.VERSION, d.version)
        assertTrue(d.books.isEmpty())
        assertTrue(BackupJson.parse("{\"format\": \"readeraplus-backup\"}").books.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun foreignJsonIsRejected() {
        BackupJson.parse("{\"docs\": [], \"version\": 3}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun notJsonThrowsIllegalArgument() {
        BackupJson.parse("<html>nope</html>")
    }

    @Test(expected = IllegalArgumentException::class)
    fun arrayRootThrowsIllegalArgument() {
        BackupJson.parse("[1,2,3]")
    }

    @Test
    fun fieldHelpers() {
        val o = JSONObject("""{"n": null, "s": "12", "d": 3.9, "big": 1e300, "b": "yes", "bad": "maybe", "neg": -5}""")
        assertNull(BackupJson.strOrNull(o, "n"))
        assertEquals("12", BackupJson.str(o, "s", ""))
        assertEquals(12, BackupJson.int(o, "s", 0))
        assertEquals(3L, BackupJson.long(o, "d", 0))
        assertEquals(Int.MAX_VALUE, BackupJson.int(o, "big", 0))
        assertEquals(7, BackupJson.int(o, "missing", 7))
        assertTrue(BackupJson.bool(o, "b", false))
        assertTrue(BackupJson.bool(o, "bad", true))
        assertEquals(-5, BackupJson.int(o, "neg", 0))
        assertNull(BackupJson.floatOrNull(o, "n"))
        assertEquals(3.9f, BackupJson.float(o, "d", 0f), 1e-6f)
        assertNull(BackupJson.floatOrNull(o, "big"))
    }

    // ---- R3: v3 note fields (N §5.6) and the header (S §3.6, C10) ----

    private val v3 = BackupJson.fromBook(
        book.copy(missingAt = 1_700_000_000_000), metaLocked = false,
        collections = emptyList(),
        bookmarks = listOf(
            Bookmark(1, 3, 1, 10, "a", 1000, "", chapter = "1장", frac = 0.25f, sig = "ab12:4096"),
            Bookmark(2, 3, 2, 20, "b", 1001),
        ),
        quotes = listOf(
            Quote(3, 3, 2, 5, 9, "인용", "", 2000, style = 4, chapter = "2장", frac = 0.5f, sig = "e:4096"),
            Quote(4, 3, 3, 1, 2, "x", "", 2001),
        ),
        reviewAt = 3000,
        lookups = listOf(Lookup(9, 3, "사과", 1, 2, 4, "사과를 먹었다", "1장", 0.1f, "e:1", 1, "com.dict", "메모", 4000)),
    )

    @Test
    fun v3FieldsRoundTrip() {
        val text = BackupJson.toJson(BackupData(1, 5, listOf(v3), emptyList(), null)).toString()
        val back = BackupJson.parse(text).books.single()
        assertEquals(v3, back)
        assertEquals(3000L, back.reviewAt)
        assertEquals(1_700_000_000_000L, back.missingAt)
        assertEquals(BackupLookup("사과", 1, 2, 4, "사과를 먹었다", "1장", 0.1f, "e:1", 1, "com.dict", "메모", 4000), back.lookups[0])

        val o = JSONObject(text).getJSONArray("books").getJSONObject(0)
        val qs = o.getJSONArray("quotes")
        // style only when ≠ 0, the place only when frac ≥ 0.
        assertEquals(4, qs.getJSONObject(0).getInt("style"))
        assertEquals("e:4096", qs.getJSONObject(0).getString("sig"))
        assertFalse(qs.getJSONObject(1).has("style"))
        assertFalse(qs.getJSONObject(1).has("frac"))
        assertFalse(qs.getJSONObject(1).has("chapter"))
        assertFalse(o.getJSONArray("bookmarks").getJSONObject(1).has("frac"))
        assertEquals("1장", o.getJSONArray("bookmarks").getJSONObject(0).getString("chapter"))
    }

    @Test
    fun v3FieldsAreOmittedWhenEmptyAndOldBackupsGetDefaults() {
        val o = BackupJson.bookToJson(sample)
        assertFalse(o.has("reviewAt"))
        assertFalse(o.has("missingAt"))
        assertFalse(o.has("lookups"))
        val old = JSONObject("""{"path": "/a.txt", "quotes": [{"start": 1, "end": 2, "text": "t"}],
            "bookmarks": [{"offset": 3}]}""")
        val b = BackupJson.bookFromJson(old)!!
        assertEquals(0L, b.reviewAt)
        assertEquals(0L, b.missingAt)
        assertTrue(b.lookups.isEmpty())
        assertEquals(0, b.quotes[0].style)
        assertEquals(-1f, b.quotes[0].frac, 0f)
        assertEquals("", b.quotes[0].sig)
        assertEquals(-1f, b.bookmarks[0].frac, 0f)
    }

    @Test
    fun v3FieldsAreCappedAndClamped() {
        val o = JSONObject()
            .put("path", "/a.txt").put("reviewAt", -4).put("missingAt", "x")
            .put("quotes", org.json.JSONArray()
                .put(JSONObject().put("start", 1).put("end", 2).put("style", 99).put("frac", 7).put("chapter", "c".repeat(500)))
                .put(JSONObject().put("start", 1).put("end", 2).put("style", -3).put("frac", -0.5).put("chapter", "z").put("sig", "s")))
            .put("lookups", org.json.JSONArray()
                .put(JSONObject().put("word", "  ").put("createdAt", 1))
                .put(JSONObject().put("word", "w".repeat(500)).put("start", 9).put("end", 3).put("context", "k".repeat(1000))
                    .put("app", "a".repeat(500)).put("via", -2))
                .put("junk"))
        val b = BackupJson.bookFromJson(o)!!
        assertEquals(0L, b.reviewAt)
        assertEquals(0L, b.missingAt)
        assertEquals(DataLimits.QUOTE_STYLE_MAX, b.quotes[0].style)
        assertEquals(1f, b.quotes[0].frac, 0f)
        assertTrue(b.quotes[0].chapter.length <= DataLimits.CHAPTER)
        assertEquals(0, b.quotes[1].style)
        // An unknown place carries no chapter or sig.
        assertEquals(-1f, b.quotes[1].frac, 0f)
        assertEquals("", b.quotes[1].chapter)
        assertEquals("", b.quotes[1].sig)
        val l = b.lookups.single()
        assertTrue(l.word.length <= DataLimits.WORD)
        assertTrue(l.context.length <= DataLimits.CONTEXT)
        assertTrue(l.app.length <= DataLimits.APP)
        assertEquals(3, l.start)
        assertEquals(9, l.end)
        assertEquals(0, l.via)
    }

    private fun header(data: BackupData): BackupHeader =
        BackupJson.readHeader(java.io.StringReader(BackupJson.toJson(data).toString(1)))

    @Test
    fun headerFieldsAreWrittenBeforeBooksAndOptionalBothWays() {
        val origin = BackupOrigin("0badc0de11112222", true, "3.0", "Bigme Comet")
        val data = BackupData(1, 99, listOf(sample, v3), listOf("c"), JSONObject().put("x", 1), 5, origin,
            BackupJson.summaryOf(listOf(sample, v3)))
        val text = BackupJson.toJson(data).toString()
        val keys = JSONObject(text).keys().asSequence().toList()
        assertEquals(listOf("format", "version", "createdAt", "origin", "summary"), keys.take(5))
        assertEquals("books", keys.last())
        val back = BackupJson.parse(text)
        assertEquals(origin, back.origin)
        assertEquals("2,2,3,3", back.summary.toString())
        // Older backups have neither; nothing is written for null.
        val old = BackupJson.toJson(BackupData(1, 99, listOf(sample), emptyList(), null))
        assertFalse(old.has("origin"))
        assertFalse(old.has("summary"))
        assertNull(BackupJson.parse(old.toString()).origin)
        assertNull(BackupJson.parse(old.toString()).summary)
        // Malformed header values fall back.
        val bad = BackupJson.parse("""{"books": [], "origin": 5, "summary": {"books": -3, "read": "2"}}""")
        assertNull(bad.origin)
        assertEquals("0,2,0,0", bad.summary.toString())
    }

    @Test
    fun headerReaderMatchesAFullParse() {
        val books = listOf(sample, v3, sample.copy(path = "/c.txt", lastReadAt = 0, bookmarks = emptyList()))
        val summary = BackupJson.summaryOf(books)
        val origin = BackupOrigin("abc", false, "", "")
        val h = header(BackupData(1, 77, books, emptyList(), JSONObject().put("a", JSONObject().put("b", 2)), 0, origin, summary))
        assertEquals(1, h.version)
        assertEquals(77L, h.createdAt)
        assertEquals(origin, h.origin)
        assertEquals(summary.toString(), h.summary.toString())
        // A file without a summary (older builds) is counted while its books stream past.
        val counted = header(BackupData(1, 77, books, emptyList(), null))
        assertEquals(summary.toString(), counted.summary.toString())
        assertEquals(BackupJson.summaryOf(BackupJson.parse(BackupJson.toJson(BackupData(1, 77, books, emptyList(), null))
            .toString()).books).toString(), counted.summary.toString())
        assertNull(counted.origin)
        // Entries without a file are dropped by the parse, so the count skips them too; a BOM is fine.
        val raw = "\uFEFF{\"books\":[{\"title\":\"x\"},{\"path\":\"/a\",\"lastReadAt\":5,\"quotes\":[{},{},3]}],\"version\":1}"
        assertEquals("1,1,0,2", BackupJson.readHeader(java.io.StringReader(raw)).summary.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun headerReaderRejectsForeignJson() {
        BackupJson.readHeader(java.io.StringReader("{\"docs\": [], \"version\": 3}"))
    }

    @Test
    fun streamedWriteEqualsTheTree() {
        val data = BackupData(1, 99, listOf(sample, v3), listOf("c", "d"), JSONObject().put("x", 1), 5,
            BackupOrigin("id", true, "v", "d"), BackupJson.summaryOf(listOf(sample, v3)))
        for (d in listOf(data, BackupData(1, 0, emptyList(), emptyList(), null))) {
            val w = java.io.StringWriter()
            var checks = 0
            BackupJson.write(d, w) { checks++ }
            assertEquals(BackupJson.toJson(d).toString(), w.toString())
            assertEquals(1 + d.books.size, checks)
        }
    }

    @Test
    fun summaryCountsOpenedBooksAndNotes() {
        val s = BackupJson.summaryOf(listOf(sample, sample.copy(lastReadAt = 0, quotes = emptyList())))
        assertEquals(2, s.books)
        assertEquals(1, s.read)
        assertEquals(2, s.bookmarks)
        assertEquals(1, s.quotes)
        assertEquals(4, s.score)
    }
}
