package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocMeta
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression tests for defects found in review of the data module. */
class ReviewRegressionTest {

    @Test
    fun backupTextsAreCappedToLibraryLimits() {
        // An oversized row would overflow the 2 MB CursorWindow and make the book's quotes unreadable.
        val huge = "가".repeat(DataLimits.QUOTE + 50)
        val o = JSONObject()
            .put("path", "/a/b.txt")
            .put("review", huge)
            .put("bookmarks", JSONArray().put(JSONObject().put("snippet", huge).put("note", huge)))
            .put("quotes", JSONArray().put(JSONObject().put("text", huge).put("note", huge)))
        val b = BackupJson.bookFromJson(o)!!
        assertEquals(DataLimits.REVIEW, b.review.length)
        assertEquals(DataLimits.SNIPPET, b.bookmarks[0].snippet.length)
        assertEquals(DataLimits.NOTE, b.bookmarks[0].note.length)
        assertEquals(DataLimits.QUOTE, b.quotes[0].text.length)
        assertEquals(DataLimits.NOTE, b.quotes[0].note.length)
        // Surrogate pairs are never split by the cap.
        val emoji = "😀".repeat(DataLimits.SNIPPET)
        val e = BackupJson.bookFromJson(
            JSONObject().put("path", "/a").put("bookmarks", JSONArray().put(JSONObject().put("snippet", emoji))),
        )!!
        assertFalse(Character.isHighSurrogate(e.bookmarks[0].snippet.last()))
    }

    @Test
    fun collectionKeysFoldLikeSqliteNocase() {
        // SQLite NOCASE folds ASCII only: "Ä"/"ä" are two collections there, so the restore must not merge them.
        assertEquals(Library.collectionKey("Fav"), Library.collectionKey(" fav "))
        assertNotEquals(Library.collectionKey("Ä"), Library.collectionKey("ä"))
        assertEquals("한글 abc", MetaInfo.asciiLower("한글 ABC"))
        assertEquals("already lower", MetaInfo.asciiLower("already lower"))
        assertEquals("ÄÖ", MetaInfo.asciiLower("ÄÖ"))
    }

    @Test
    fun txtMetadataNeedsNoParser() {
        assertFalse(MetaInfo.needsParser(BookFormat.TXT))
        assertTrue(MetaInfo.needsParser(BookFormat.EPUB))
        assertFalse(MetaInfo.needsParser(null))
        // What the TXT parser's readMeta yields (title = file name without extension) maps to the same entry.
        for (name in listOf("소설 1권.txt", "a.b.TXT", "  두  칸 .txt", ".txt", "x.txt")) {
            val parserTitle = File(name).nameWithoutExtension.ifEmpty { name }
            assertEquals(name, MetaInfo.from(DocMeta(parserTitle, encoding = "MS949"), name), MetaInfo.from(null, name))
        }
    }

    @Test
    fun searchTokensAreBounded() {
        val long = "가".repeat(60_000)
        val t = LibrarySql.searchTokens("$long 짧은")
        assertEquals(LibrarySql.MAX_TOKEN_CHARS, t[0].length)
        assertEquals("짧은", t[1])
        val q = LibrarySql.booksQuery(LibraryQuery(Shelf.ALL, null, long), com.ggumtak.readeraplus.settings.LibrarySort.TITLE)
        assertTrue(q.args.all { it.length <= LibrarySql.MAX_TOKEN_CHARS * 2 + 2 })
    }

    @Test
    fun newStatementsHaveExpectedPlaceholders() {
        assertEquals(2, LibrarySql.INSERT_MEMBERSHIP.count { it == '?' })
        assertTrue(LibrarySql.INSERT_MEMBERSHIP.contains("FROM books b, collections c"))
        assertEquals(2, LibrarySql.SELECT_MOVE_CANDIDATES.count { it == '?' })
        assertTrue(LibrarySql.SELECT_MOVE_CANDIDATES.contains("trashed = 0"))
        assertEquals(0, LibrarySql.SELECT_IDS_WITH_USER_DATA.count { it == '?' })
        for (t in listOf("bookmarks", "quotes", "book_collections")) {
            assertTrue(t, LibrarySql.SELECT_IDS_WITH_USER_DATA.contains("FROM $t"))
        }
    }
}
