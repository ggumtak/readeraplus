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
}
