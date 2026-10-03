package com.ggumtak.readeraplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N §5.6 / §16: the restore merge rules (a union: nothing is ever deleted). */
class BackupMergeTest {

    private val entry = BackupBook(path = "/b/a.txt", fileName = "a.txt", size = 10)

    private fun quote(id: Long, section: Int, start: Int, end: Int, text: String = "t", note: String = "",
                      style: Int = 0, frac: Float = -1f, sig: String = "") =
        Quote(id, 1, section, start, end, text, note, 100, style, if (frac >= 0f) "장" else "", frac, sig)

    private fun bq(section: Int, start: Int, end: Int, text: String = "t", note: String = "", style: Int = 0,
                   frac: Float = -1f, sig: String = "") =
        BackupQuote(section, start, end, text, note, 200, style, if (frac >= 0f) "백업장" else "", frac, sig)

    // ---- quotes ----

    @Test
    fun styleOnlyOntoTheDefaultLook() {
        val plan = BackupMerge.quotes(listOf(quote(1, 0, 1, 2), quote(2, 0, 3, 4, style = 5)),
            listOf(bq(0, 1, 2, style = 3), bq(0, 3, 4, style = 7)))
        assertTrue(plan.inserts.isEmpty())
        assertEquals(listOf(BackupMerge.QuoteFill(1, 3, null, null)), plan.fills)
    }

    @Test
    fun placeOnlyOntoAnUnknownPlace() {
        val plan = BackupMerge.quotes(listOf(quote(1, 0, 1, 2), quote(2, 0, 3, 4, frac = 0.2f, sig = "s")),
            listOf(bq(0, 1, 2, frac = 0.5f, sig = "s"), bq(0, 3, 4, frac = 0.9f, sig = "s")))
        assertEquals(listOf(BackupMerge.QuoteFill(1, null, NotePlace("백업장", 0.5f, "s"), null)), plan.fills)
        // An unknown backup place never fills anything.
        assertTrue(BackupMerge.quotes(listOf(quote(1, 0, 1, 2)), listOf(bq(0, 1, 2))).fills.isEmpty())
    }

    @Test
    fun noteOnlyOntoAnEmptyNote() {
        val plan = BackupMerge.quotes(listOf(quote(1, 0, 1, 2), quote(2, 0, 3, 4, note = "기기 메모")),
            listOf(bq(0, 1, 2, note = "백업 메모"), bq(0, 3, 4, note = "다른 메모")))
        assertEquals(listOf(BackupMerge.QuoteFill(1, null, null, "백업 메모")), plan.fills)
    }

    @Test
    fun newQuotesAreInsertedWithStyleAndPlaceOnce() {
        val q = bq(1, 5, 9, style = 2, frac = 0.3f, sig = "s")
        val plan = BackupMerge.quotes(emptyList(), listOf(q, q.copy(note = "메모")))
        // The same quote listed twice: one insert, filled by the second.
        assertEquals(listOf(q.copy(note = "메모")), plan.inserts)
        assertTrue(plan.fills.isEmpty())
    }

    @Test
    fun theSameTextUnderAnotherSignatureIsTheSameQuote() {
        // Another device with other TXT options: other offsets, same text, another sig → no duplicate.
        val plan = BackupMerge.quotes(listOf(quote(1, 2, 100, 120, text = "같은 문장", sig = "aaaa:1")),
            listOf(bq(3, 50, 70, text = "같은 문장", style = 4, frac = 0.4f, sig = "bbbb:1")))
        assertTrue(plan.inserts.isEmpty())
        assertEquals(listOf(BackupMerge.QuoteFill(1, 4, NotePlace("백업장", 0.4f, "bbbb:1"), null)), plan.fills)
        // Same text under the same signature at other offsets: another occurrence, inserted.
        val same = BackupMerge.quotes(listOf(quote(1, 2, 100, 120, text = "반복", sig = "aaaa:1")),
            listOf(bq(2, 300, 302, text = "반복", sig = "aaaa:1")))
        assertEquals(1, same.inserts.size)
    }

    // ---- bookmarks ----

    @Test
    fun bookmarksMatchOnSectionAndOffsetAndFillOnlyWhatIsMissing() {
        val existing = listOf(
            Bookmark(1, 1, 0, 10, "a", 1),
            Bookmark(2, 1, 0, 20, "b", 1, "메모", "장", 0.5f, "s"),
        )
        val plan = BackupMerge.bookmarks(existing, listOf(
            BackupBookmark(0, 10, "a", "새 메모", 5, "1장", 0.1f, "s"),
            BackupBookmark(0, 20, "b", "다른 메모", 5, "2장", 0.9f, "s"),
            BackupBookmark(1, 10, "c", "", 5),
        ))
        assertEquals(listOf(BackupBookmark(1, 10, "c", "", 5)), plan.inserts)
        assertEquals(listOf(BackupMerge.BookmarkFill(1, NotePlace("1장", 0.1f, "s"), "새 메모")), plan.fills)
    }

    // ---- lookups ----

    @Test
    fun lookupsAreDedupedOnWordKeySectionStartAndTime() {
        val a = BackupLookup("Apple", 1, 5, 10, createdAt = 100)
        val existing = setOf(BackupMerge.lookupKey("apple", 1, 5, 100))
        val out = BackupMerge.lookups(existing, listOf(
            a, // already here (same key)
            a.copy(createdAt = 101), // another lookup of the same word
            a.copy(word = "APPLE", createdAt = 101), // the same key as the line above
            a.copy(start = 6),
        )) { it.lowercase() }
        assertEquals(listOf(a.copy(createdAt = 101) to "apple", a.copy(start = 6) to "apple"), out)
    }

    // ---- the book row ----

    @Test
    fun positionNewerWins() {
        val older = entry.copy(lastReadAt = 1000, posSection = 3)
        val r = BackupMerge.book(BackupMerge.BookState(lastReadAt = 2000), older, fileFound = false)
        assertFalse(r.applyPosition)
        assertTrue(r.deviceNewer)
        assertTrue(BackupMerge.book(BackupMerge.BookState(lastReadAt = 500), older, false).applyPosition)
        // Never read on this device: the backup's position.
        assertTrue(BackupMerge.book(BackupMerge.BookState(lastReadAt = 0), older, false).applyPosition)
        assertTrue(BackupMerge.book(BackupMerge.BookState(lastReadAt = 0), entry, false).applyPosition)
        // A backup entry never read doesn't wipe progress made here.
        assertFalse(BackupMerge.book(BackupMerge.BookState(lastReadAt = 5), entry, false).applyPosition)
    }

    @Test
    fun readingFlagsAndEncodingStayWhenTheDeviceIsNewer() {
        val cur = BackupMerge.BookState(lastReadAt = 2000, toRead = false, haveRead = false, trashed = false,
            encoding = "UTF-8")
        val b = entry.copy(lastReadAt = 1000, haveRead = true, trashed = true, encoding = "MS949", toRead = true)
        val r = BackupMerge.book(cur, b, false)
        assertFalse(r.haveRead)
        assertFalse(r.trashed)
        assertFalse(r.toRead)
        assertEquals("UTF-8", r.encoding)
        // The backup is newer: its flags.
        val n = BackupMerge.book(cur.copy(lastReadAt = 500), b, false)
        assertTrue(n.haveRead)
        assertTrue(n.trashed)
        assertFalse(n.toRead) // never both
        assertEquals("MS949", n.encoding)
    }

    @Test
    fun favouriteIsOr() {
        assertTrue(BackupMerge.book(BackupMerge.BookState(favorite = true), entry, false).favorite)
        assertTrue(BackupMerge.book(BackupMerge.BookState(), entry.copy(favorite = true), false).favorite)
        assertFalse(BackupMerge.book(BackupMerge.BookState(), entry, false).favorite)
    }

    @Test
    fun reviewNewerWinsByItsTime() {
        val cur = BackupMerge.BookState(review = "기기 리뷰", reviewAt = 2000)
        // An old backup (no reviewAt) fills only an empty review.
        val old = BackupMerge.book(cur, entry.copy(review = "옛 리뷰"), false)
        assertEquals("기기 리뷰", old.review)
        assertEquals(2000L, old.reviewAt)
        assertEquals("옛 리뷰", BackupMerge.book(BackupMerge.BookState(), entry.copy(review = "옛 리뷰"), false).review)
        val newer = BackupMerge.book(cur, entry.copy(review = "새 리뷰", reviewAt = 3000), false)
        assertEquals("새 리뷰", newer.review)
        assertEquals(3000L, newer.reviewAt)
        assertEquals("기기 리뷰", BackupMerge.book(cur, entry.copy(review = "옛", reviewAt = 1000), false).review)
        // A blank backup review never clears one.
        assertEquals("기기 리뷰", BackupMerge.book(cur, entry.copy(review = " ", reviewAt = 9000), false).review)
    }

    @Test
    fun aMissingBookFoundHereIsRevived() {
        val b = entry.copy(trashed = true, missingAt = 1234)
        val found = BackupMerge.book(BackupMerge.BookState(), b, fileFound = true)
        assertFalse(found.trashed)
        assertEquals(0L, found.missingAt)
        // Not found: it stays trashed and revivable.
        val lost = BackupMerge.book(BackupMerge.BookState(), b, fileFound = false)
        assertTrue(lost.trashed)
        assertEquals(1234L, lost.missingAt)
        // A book the user trashed here (missing_at 0) never becomes revivable.
        val userTrash = BackupMerge.book(BackupMerge.BookState(trashed = true), b, fileFound = false)
        assertTrue(userTrash.trashed)
        assertEquals(0L, userTrash.missingAt)
        val keptTrash = BackupMerge.book(BackupMerge.BookState(lastReadAt = 9, trashed = true), b, fileFound = true)
        assertTrue(keptTrash.trashed)
    }

    @Test
    fun unresolvedBooksWithNotesBecomePlaceholders() {
        assertTrue(BackupMerge.needsPlaceholder(entry.copy(quotes = listOf(bq(0, 1, 2)))))
        assertTrue(BackupMerge.needsPlaceholder(entry.copy(bookmarks = listOf(BackupBookmark(0, 1, "", "", 0)))))
        assertTrue(BackupMerge.needsPlaceholder(entry.copy(review = "리뷰")))
        assertTrue(BackupMerge.needsPlaceholder(entry.copy(lookups = listOf(BackupLookup("w")))))
        // Without notes: skipped (a 500-book backup doesn't flood the trash).
        assertFalse(BackupMerge.needsPlaceholder(entry.copy(lastReadAt = 5, favorite = true)))
        assertFalse(BackupMerge.needsPlaceholder(entry.copy(path = "", quotes = listOf(bq(0, 1, 2)))))
        // The placeholder row is trashed and missing (revivable when the file arrives), whatever the backup said.
        val ph = BackupMerge.book(BackupMerge.BookState(), entry.copy(review = "리뷰", lastReadAt = 7, favorite = true),
            fileFound = false, placeholderAt = 5000)
        assertTrue(ph.trashed)
        assertEquals(5000L, ph.missingAt)
        assertEquals("리뷰", ph.review)
        assertTrue(ph.favorite)
        assertTrue(ph.applyPosition)
        // Its notes all go in: nothing on the placeholder yet.
        val plan = BackupMerge.quotes(emptyList(), listOf(bq(0, 1, 2), bq(0, 3, 4)))
        assertEquals(2, plan.inserts.size)
    }

    @Test
    fun restoreStatementsHaveTheirPlaceholders() {
        fun marks(sql: String) = sql.count { it == '?' }
        assertEquals(11, marks(BackupSql.INSERT_QUOTE))
        assertEquals(9, marks(BackupSql.INSERT_BOOKMARK))
        assertEquals(14, marks(BackupSql.INSERT_LOOKUP))
        assertEquals(11, marks(BackupSql.RESTORE_BOOK))
        assertEquals(12, marks(BackupSql.INSERT_PLACEHOLDER))
        assertEquals(5, marks(BackupSql.SET_PREFS_ROW))
        assertEquals(5, marks(BackupSql.INSERT_PREFS_ROW))
        assertEquals(4, marks(BackupSql.FILL_QUOTE_PLACE))
        assertEquals(4, marks(BackupSql.FILL_BOOKMARK_PLACE))
    }
}
