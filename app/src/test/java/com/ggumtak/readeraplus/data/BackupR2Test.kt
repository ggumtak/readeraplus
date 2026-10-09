package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.reader.TextPositions
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
    fun hugeLogCountsAreCappedLikeOneAdd() {
        // Every log read SUMs these; one saturated row (1e19 → Long.MAX_VALUE) would overflow SQLite's SUM.
        val arr = JSONArray()
            .put(JSONObject().put("day", 20260102).put("seconds", 60).put("pages", Int.MAX_VALUE).put("chars", 1e19))
            .put(JSONObject().put("day", 20260103).put("seconds", 60).put("chars", "1e19"))
            .put(JSONObject().put("day", 20260104).put("seconds", 60).put("chars", Long.MAX_VALUE))
        assertEquals(
            listOf(
                BackupLogDay(20260102, 60, ReadingLog.MAX_ADD_PAGES, ReadingLog.MAX_ADD_CHARS),
                BackupLogDay(20260103, 60, 0, ReadingLog.MAX_ADD_CHARS),
                BackupLogDay(20260104, 60, 0, ReadingLog.MAX_ADD_CHARS),
            ),
            BackupJson.logFromJson(arr),
        )
    }

    @Test
    fun theTxtParseVersionTravelsWithTheBackup() {
        val b = BackupJson.fromBook(book, false, emptyList(), emptyList(), emptyList())
        val text = BackupJson.toJson(BackupData(1, 5, listOf(b), emptyList(), null, TxtDocuments.PARSE_VERSION))
        assertEquals(TxtDocuments.PARSE_VERSION, JSONObject(text.toString()).getInt("txtParseVersion"))
        assertEquals(TxtDocuments.PARSE_VERSION, BackupJson.parse(text.toString()).txtParseVersion)
        // Older backups have none: 0, which no parse is.
        assertFalse(BackupJson.toJson(BackupData(1, 5, listOf(b), emptyList(), null)).has("txtParseVersion"))
        assertEquals(0, BackupJson.parse("{\"books\":[]}").txtParseVersion)
        assertEquals(0, BackupJson.parse("{\"books\":[],\"txtParseVersion\":-3}").txtParseVersion)
    }

    @Test
    fun txtPositionsOfAnotherParseAreFoundAgainByFraction() {
        val v = TxtDocuments.PARSE_VERSION
        val txt = BackupBook(path = book.path, fileName = book.fileName, size = 1, format = "TXT", posSection = 12,
            posOffset = 340, progress = 0.42f)
        // A backup without the key (from before R2) or of another parse version: the (section, offset) is stale.
        assertTrue(BackupJson.remapsTextPosition(0, txt))
        assertTrue(BackupJson.remapsTextPosition(v - 1, txt))
        assertTrue(BackupJson.remapsTextPosition(v + 1, txt))
        // Same parse: the coordinates are exact.
        assertFalse(BackupJson.remapsTextPosition(v, txt))
        // The start is the start of any parse; EPUB spine positions never move.
        assertFalse(BackupJson.remapsTextPosition(0, txt.copy(posSection = 0, posOffset = 0)))
        assertTrue(BackupJson.remapsTextPosition(0, txt.copy(posSection = 0)))
        assertFalse(BackupJson.remapsTextPosition(0, txt.copy(format = "EPUB", fileName = "a.epub")))
        // No (or an unknown) format: the file name decides.
        assertTrue(BackupJson.remapsTextPosition(0, txt.copy(format = "")))
        assertFalse(BackupJson.remapsTextPosition(0, txt.copy(format = "", fileName = "a.epub")))

        // The record the restore writes matches no parse signature (16 hex chars), so the reader's next open goes to
        // the restored progress instead of the stale coordinates.
        val record = BackupJson.staleTextPosition(0.42f)
        assertEquals(0.42f, TextPositions.decode(record)!!.second)
        assertEquals(0.42f, TextPositions.remapFraction(record, "0123456789abcdef", 12, 340, 0.42f))
        assertEquals(0f, TextPositions.decode(BackupJson.staleTextPosition(Float.NaN))!!.second)
        assertEquals(1f, TextPositions.decode(BackupJson.staleTextPosition(7f))!!.second)
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

    @Test
    fun theOverrideFollowsTheNewerReading() {
        // It changes the parse (every quote's offsets), so like the encoding the newer side wins (N §5.6).
        val cur = PrefsRow("{\"stripIndent\":false}", 0, null)
        val older = BackupPrefs(TxtOverride(stripIndent = true))
        assertEquals(cur, BackupJson.mergePrefs(cur, older, haveRead = false, deviceNewer = true))
        // An override cleared on this device after the backup stays cleared.
        assertNull(BackupJson.mergePrefs(null, older, haveRead = false, deviceNewer = true))
        assertNull(BackupJson.mergePrefs(PrefsRow(null, 0, "3화"), older, false, deviceNewer = true)!!.txtOverride)
        // The backup is newer: its override wins, and a backup without one keeps the device's.
        assertEquals(older.txtOverride!!.toJson(), BackupJson.mergePrefs(cur, older, false)!!.txtOverride)
        assertEquals(cur, BackupJson.mergePrefs(cur, BackupPrefs(finishedAt = 5), false))
    }
}
