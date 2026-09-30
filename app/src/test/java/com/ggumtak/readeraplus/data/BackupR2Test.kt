package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R2 backup additions: the reading log and the per-book prefs, carried per book (keyed by path). */
class BackupR2Test {

    private val book = Book(
        id = 7, path = "/storage/emulated/0/Books/소설A 1-100화.txt", fileName = "소설A 1-100화.txt", title = "소설A",
        author = "", series = null, seriesIndex = null, format = BookFormat.TXT, sizeBytes = 14_800_000,
        modifiedAt = 1, addedAt = 2, haveRead = true,
    )

    private val log = listOf(
        BackupLogDay(20260929, 1800, 40, 22_000),
        BackupLogDay(20260930, 600, 12, 7_000),
    )

    private val prefs = BackupPrefs(
        txtOverride = TxtOverride(blankLines = 2, joinWrapped = 1, chapterRegex = "", replaceRules = "## 광고\n광고 =>"),
        finishedAt = 1_790_000_000_000,
        episodeLabel = "100/540화",
    )

    @Test
    fun roundTrip() {
        val b = BackupJson.fromBook(book, false, emptyList(), emptyList(), emptyList(), log.reversed(), prefs)
        assertEquals(log, b.readingLog) // sorted by day
        val text = BackupJson.toJson(BackupData(1, 5, listOf(b), emptyList(), null)).toString(1)
        val back = BackupJson.parse(text).books.single()
        assertEquals(b, back)
        assertEquals(prefs, back.prefs)
        val o = JSONObject(text).getJSONArray("books").getJSONObject(0)
        // The override travels as a JSON object, not as escaped text.
        assertEquals(2, o.getJSONObject("prefs").getJSONObject("txtOverride").getInt("blankLines"))
        assertEquals(20260929, o.getJSONArray("readingLog").getJSONObject(0).getInt("day"))
    }

    @Test
    fun nothingNewIsWrittenForABookWithoutIt() {
        val b = BackupJson.fromBook(book, false, emptyList(), emptyList(), emptyList(), emptyList(), BackupPrefs())
        assertNull(b.prefs)
        val o = BackupJson.bookToJson(b)
        assertFalse(o.has("readingLog"))
        assertFalse(o.has("prefs"))
        // The old fromBook call (five arguments) still means "none".
        assertEquals(b, BackupJson.fromBook(book, false, emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun oldBackupsRestoreWithoutTheNewKeys() {
        val old = JSONObject().put("path", book.path).put("fileName", book.fileName).put("size", 1)
        val b = BackupJson.bookFromJson(old)!!
        assertTrue(b.readingLog.isEmpty())
        assertNull(b.prefs)
    }

    @Test
    fun logRowsAreCleanedOnRead() {
        val arr = JSONArray()
            .put(JSONObject().put("day", 20260930).put("seconds", 100).put("pages", 2).put("chars", 900))
            .put(JSONObject().put("day", 20260930).put("seconds", 50).put("pages", 5).put("chars", 100))
            .put(JSONObject().put("day", 20260901).put("seconds", 999_999).put("pages", -3).put("chars", -1))
            .put(JSONObject().put("day", 20261341).put("seconds", 60))
            .put(JSONObject().put("day", "20260915").put("seconds", "60"))
            .put(JSONObject().put("day", 20260920))
            .put("not an object")
            .put(JSONObject.NULL)
        assertEquals(
            listOf(
                BackupLogDay(20260901, 24L * 3600, 0, 0),
                BackupLogDay(20260915, 60, 0, 0),
                // A day listed twice: the larger value of each column, like the restore itself.
                BackupLogDay(20260930, 100, 5, 900),
            ),
            BackupJson.logFromJson(arr),
        )
        assertTrue(BackupJson.logFromJson(null).isEmpty())
    }

    @Test
    fun prefsAreCleanedOnRead() {
        val asText = JSONObject().put("txtOverride", "{\"stripIndent\":true}").put("finishedAt", -5)
        assertEquals(BackupPrefs(TxtOverride(stripIndent = true), 0, null), BackupJson.prefsFromJson(asText))
        val label = JSONObject().put("episodeLabel", "  12/40화 \n").put("txtOverride", "not json")
        assertEquals(BackupPrefs(null, 0, "12/40화"), BackupJson.prefsFromJson(label))
        assertNull(BackupJson.prefsFromJson(JSONObject()))
        assertNull(BackupJson.prefsFromJson(JSONObject().put("txtOverride", JSONObject()).put("episodeLabel", " ")))
        val huge = JSONObject().put("txtOverride", JSONObject().put("replaceRules", "x".repeat(BookPrefs.MAX_OVERRIDE_CHARS)))
        assertNull(BackupJson.prefsFromJson(huge))
    }

    @Test
    fun mergeRules() {
        val cur = PrefsRow("{\"stripIndent\":false}", 1000, "3/10화")
        val backupOverride = TxtOverride(stripIndent = true)
        // The backup's values win where it has them.
        assertEquals(
            PrefsRow(backupOverride.toJson(), 2000, "5/10화"),
            BackupJson.mergePrefs(cur, BackupPrefs(backupOverride, 2000, "5/10화"), haveRead = true),
        )
        // Missing in the backup: the device's stay.
        assertEquals(cur, BackupJson.mergePrefs(cur, BackupPrefs(), haveRead = true))
        assertEquals(cur, BackupJson.mergePrefs(cur, null, haveRead = true))
        // A book restored as not finished loses its finish time, whatever either side had.
        assertEquals(cur.copy(finishedAt = 0), BackupJson.mergePrefs(cur, BackupPrefs(finishedAt = 5), haveRead = false))
        // Nothing left: no row.
        assertNull(BackupJson.mergePrefs(PrefsRow(null, 1000, null), null, haveRead = false))
        assertNull(BackupJson.mergePrefs(null, null, haveRead = true))
        assertEquals(PrefsRow(null, 5, null), BackupJson.mergePrefs(null, BackupPrefs(finishedAt = 5), haveRead = true))
    }
}
