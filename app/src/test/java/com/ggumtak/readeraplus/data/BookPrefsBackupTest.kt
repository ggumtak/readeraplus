package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** U §3.3: the return mark travels in the book's `book_prefs` backup entry as "returnMark", both ways. */
class BookPrefsBackupTest {

    private val book = Book(
        id = 7, path = "/storage/emulated/0/Books/a.txt", fileName = "a.txt", title = "a", author = "",
        series = null, seriesIndex = null, format = BookFormat.TXT, sizeBytes = 10, modifiedAt = 1, addedAt = 2,
    )
    private val mark = "0123456789abcdef|3|120|0.25"

    @Test
    fun returnMarkRoundTrip() {
        val b = BackupJson.fromBook(book, false, emptyList(), emptyList(), emptyList(), emptyList(),
            BackupPrefs(returnMark = mark))
        val text = BackupJson.toJson(BackupData(1, 5, listOf(b), emptyList(), null)).toString()
        val o = JSONObject(text).getJSONArray("books").getJSONObject(0)
        // Keyed by path with the book's other prefs.
        assertEquals(book.path, o.getString("path"))
        assertEquals(mark, o.getJSONObject("prefs").getString("returnMark"))
        assertEquals(mark, BackupJson.parse(text).books.single().prefs!!.returnMark)
    }

    @Test
    fun anOldBackupWithoutItRestoresUnchanged() {
        val p = BackupJson.prefsFromJson(JSONObject().put("finishedAt", 5))!!
        assertNull(p.returnMark)
        val cur = PrefsRow(null, 0, null, mark)
        assertEquals(cur.copy(finishedAt = 5), BackupJson.mergePrefs(cur, p, haveRead = true))
        assertEquals(cur, BackupJson.mergePrefs(cur, null, haveRead = false))
        // Only a mark: the row is kept, not pruned.
        assertEquals(PrefsRow(null, 0, null, mark), BackupJson.mergePrefs(null, BackupPrefs(returnMark = mark), false))
    }

    @Test
    fun theMarkFollowsTheNewerPosition() {
        val cur = PrefsRow(null, 0, null, "device")
        assertEquals("backup", BackupJson.mergePrefs(cur, BackupPrefs(returnMark = "backup"), false)!!.returnMark)
        // The device read the book later: its own mark stays.
        assertEquals("device",
            BackupJson.mergePrefs(cur, BackupPrefs(returnMark = "backup"), false, deviceNewer = true)!!.returnMark)
        assertEquals("backup",
            BackupJson.mergePrefs(PrefsRow(null, 0, null), BackupPrefs(returnMark = "backup"), false, true)!!.returnMark)
    }

    @Test
    fun blankOrHugeMarksAreDropped() {
        assertNull(BackupJson.prefsFromJson(JSONObject().put("returnMark", "  ")))
        assertNull(BackupJson.prefsFromJson(JSONObject().put("returnMark", "x".repeat(5_000))))
        assertNull(BackupJson.prefsFromJson(JSONObject().put("returnMark", JSONObject.NULL)))
        val noMark = BackupJson.bookToJson(BackupJson.fromBook(book, false, emptyList(), emptyList(), emptyList()))
        assertFalse(noMark.has("prefs"))
    }
}
