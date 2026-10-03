package com.ggumtak.readeraplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** S §3.9: the pure policy of the auto backup. */
class AutoBackupPolicyTest {

    private val hour = 3_600_000L
    private val now = 1_790_000_000_000L

    private fun s(books: Int = 10, read: Int = 0, bookmarks: Int = 0, quotes: Int = 0) =
        AutoBackup.Summary(books, read, bookmarks, quotes)

    private fun cand(createdAt: Long, score: Int, id8: String? = "0badc0de") =
        AutoBackup.Candidate(null, null, createdAt, true, id8, s(read = score))

    @Test
    fun isDue() {
        assertTrue(AutoBackup.isDue(now, 0L)) // never checked
        assertFalse(AutoBackup.isDue(now, now - 19 * hour))
        assertTrue(AutoBackup.isDue(now, now - 20 * hour))
        assertTrue(AutoBackup.isDue(now, now - 72 * hour))
        // The clock went back: more than an hour → due; a little → not.
        assertTrue(AutoBackup.isDue(now, now + 2 * hour))
        assertFalse(AutoBackup.isDue(now, now + hour / 2))
    }

    @Test
    fun wouldEmpty() {
        assertTrue(AutoBackup.wouldEmpty(s(read = 3), s()))
        assertTrue(AutoBackup.wouldEmpty(s(quotes = 1), s(books = 500)))
        assertFalse(AutoBackup.wouldEmpty(s(read = 3), s(bookmarks = 1)))
        assertFalse(AutoBackup.wouldEmpty(null, s()))
        assertFalse(AutoBackup.wouldEmpty(s(), s()))
    }

    @Test
    fun isBlank() {
        assertTrue(AutoBackup.isBlank(s(books = 300), settingsAreDefault = true))
        assertFalse(AutoBackup.isBlank(s(books = 300), settingsAreDefault = false))
        assertFalse(AutoBackup.isBlank(s(read = 1), settingsAreDefault = true))
        assertFalse(AutoBackup.isBlank(s(bookmarks = 1), settingsAreDefault = true))
        assertFalse(AutoBackup.isBlank(s(quotes = 1), settingsAreDefault = true))
    }

    @Test
    fun pickDefault() {
        // A thin newer backup (a day-long reinstall, score 3) loses to a rich older one (score 400).
        val thin = cand(now, 3)
        val rich = cand(now - 90 * 24 * hour, 400)
        assertSame(rich, AutoBackup.pickDefault(listOf(thin, rich)))
        // A rich newer one (at least half the best) wins by recency.
        val richNew = cand(now, 250)
        assertSame(richNew, AutoBackup.pickDefault(listOf(richNew, rich)))
        // Ties → the newest.
        val older = cand(now - hour, 50)
        val newer = cand(now, 50)
        assertSame(newer, AutoBackup.pickDefault(listOf(older, newer)))
        // None with content → null.
        assertNull(AutoBackup.pickDefault(listOf(cand(now, 0), cand(now - 1, 0))))
        assertNull(AutoBackup.pickDefault(emptyList()))
        val bm = AutoBackup.Candidate(null, null, now, false, null, s(bookmarks = 2, quotes = 2))
        assertSame(bm, AutoBackup.pickDefault(listOf(bm, cand(now - hour, 4))))
    }

    @Test
    fun toRotateKeepsTheNewestTwo() {
        val utc = TimeZone.getDefault()
        val names = listOf(
            AutoBackup.autoName("0badc0de", now - 48 * hour, utc),
            AutoBackup.autoName("0badc0de", now, utc),
            AutoBackup.autoName("0badc0de", now - 72 * hour, utc),
            AutoBackup.autoName("0badc0de", now - 24 * hour, utc),
            "notes.txt",
        )
        assertEquals(listOf(names[0], names[2]), AutoBackup.toRotate(names))
        assertTrue(AutoBackup.toRotate(names.take(2)).isEmpty())
        assertTrue(AutoBackup.toRotate(emptyList()).isEmpty())
    }

    @Test
    fun autoNameRoundTripAndTimeZone() {
        val seoul = TimeZone.getTimeZone("Asia/Seoul")
        val utc = TimeZone.getTimeZone("UTC")
        val t = 1_790_000_000_000L // 2026-09-21 14:13:20 UTC
        assertEquals("readeraplus-auto-0badc0de-20260921-1413.json", AutoBackup.autoName("0BADC0DE", t, utc))
        assertEquals("readeraplus-auto-0badc0de-20260921-2313.json", AutoBackup.autoName("0badc0de", t, seoul))
        val minute = t - t % 60_000
        assertEquals("0badc0de" to minute, AutoBackup.parseAutoName(AutoBackup.autoName("0badc0de", t, utc), utc))
        assertEquals("0badc0de" to minute, AutoBackup.parseAutoName(AutoBackup.autoName("0badc0de", t, seoul), seoul))
        // The default zone both ways.
        assertEquals("0badc0de" to minute, AutoBackup.parseAutoName(AutoBackup.autoName("0badc0de", t)))
        for (bad in listOf("readeraplus-auto-0badc0de-20261341-1200.json", "readeraplus-auto-xyz-20260921-1333.json",
                "readeraplus-auto-0badc0de-20260921-1333.json.tmp", "readeraplus-backup-20260921.json",
                "readeraplus-auto-0badc0de-20260921-1333 (1).json")) {
            assertNull(bad, AutoBackup.parseAutoName(bad))
        }
    }

    @Test
    fun candidatesAreOrderedByTheNameTimeAndExcludeOwnFiles() {
        val tz = TimeZone.getDefault()
        val mine = AutoBackup.autoName("aaaaaaaa", now, tz)
        val older = AutoBackup.autoName("0badc0de", now - 48 * hour, tz)
        val newer = AutoBackup.autoName("12345678", now - 24 * hour, tz)
        // lastModified says the opposite (a copy resets it): the name's time decides.
        val auto = listOf(older to now, newer to 0L, mine to now, "garbage.json" to now)
        val manual = listOf("readeraplus-backup-20260901.json" to now - 36 * hour, "other.json" to now)
        val l = AutoBackup.listed(auto, manual, "aaaaaaaa", includeOwn = false)
        assertEquals(listOf(newer, "readeraplus-backup-20260901.json", older), l.map { it.name })
        assertEquals(listOf(true, false, true), l.map { it.auto })
        assertEquals(listOf("12345678", null, "0badc0de"), l.map { it.id8 })
        // BackupPage's list includes this install's own files.
        assertEquals(mine, AutoBackup.listed(auto, manual, "aaaaaaaa", includeOwn = true).first().name)
    }

    @Test
    fun ownershipOfTheLastWrite() {
        val st = AutoBackup.state("id-1", "id-1", 5, 6, "h", "10,2,3,4")
        assertTrue(st.owned)
        assertEquals(5L, st.checkedAt)
        assertEquals("10,2,3,4", st.summary.toString())
        // Copied from another install by Android Auto Backup: absent.
        val copied = AutoBackup.state("id-0", "id-1", 5, 6, "h", "10,2,3,4")
        assertFalse(copied.owned)
        assertEquals(0L, copied.checkedAt)
        assertEquals(0L, copied.writtenAt)
        assertNull(copied.hash)
        assertNull(copied.summary)
        assertFalse(AutoBackup.state(null, "", 5, 6, "h", null).owned)
        assertNull(AutoBackup.decodeSummary("1,2,3"))
        assertNull(AutoBackup.decodeSummary("1,2,x,4"))
        assertNull(AutoBackup.decodeSummary("1,-2,3,4"))
        assertEquals("1,2,3,4", AutoBackup.encodeSummary(AutoBackup.decodeSummary("1,2,3,4")!!))
    }

    @Test
    fun locationLabel() {
        assertEquals("다운로드/ReaderaPlus/backup", AutoBackup.locationLabel())
    }
}
