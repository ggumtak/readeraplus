package com.ggumtak.readeraplus.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.TimeZone

object AutoBackup {
    const val RELATIVE_DIR = "Download/ReaderaPlus/backup/"
    const val AUTO_PREFIX = "readeraplus-auto-"
    const val MANUAL_PREFIX = "readeraplus-backup-"        // SettingsFormat.backupFileName
    const val MIN_INTERVAL_MS = 20L * 3_600_000             // "daily", tolerant of reading at slightly earlier hours
    const val KEEP_OWN = 2
    // Raw prefs, device-local (transient substring "backupauto"):
    const val PREF_CHECKED_AT = "backupAuto.checkedAt"
    const val PREF_WRITTEN_AT = "backupAuto.writtenAt"
    const val PREF_HASH = "backupAuto.hash"
    const val PREF_SUMMARY = "backupAuto.summary"           // "books,read,bookmarks,quotes" of the last write
    /** [Δ] installId that wrote the four values above; values with another owner (copied by Android Auto Backup
     *  from an earlier install) are treated as absent. */
    const val PREF_OWNER = "backupAuto.owner"

    enum class Outcome { WROTE, UNCHANGED, NOT_DUE, DISABLED, NO_LOCATION, BUSY, SKIPPED_EMPTY, FAILED }
    class Summary(val books: Int, val read: Int, val bookmarks: Int, val quotes: Int)
    /** [Δ] Header only (read with android.util.JsonReader, `books` skipped); [uri] for MediaStore/SAF sources. */
    class Candidate(val file: File?, val uri: Uri?, val createdAt: Long, val auto: Boolean, val installId8: String?,
                    val summary: Summary)

    /** Pure: never checked, ≥ MIN_INTERVAL since the last check, or the clock went back more than 1 h. */
    fun isDue(now: Long, checkedAt: Long): Boolean = TODO("owner: DA-C")
    /** Pure: the new snapshot is empty (0 read, 0 bookmarks, 0 quotes) while the last write was not. */
    fun wouldEmpty(last: Summary?, now: Summary): Boolean = TODO("owner: DA-C")
    /** [Δ] Pure: nothing worth keeping (0 read, 0 bookmarks, 0 quotes and settings equal to the defaults). */
    fun isBlank(now: Summary, settingsAreDefault: Boolean): Boolean = TODO("owner: DA-C")
    /** [Δ] Pure: the default offer among up to 5 headers: the newest whose score (read + bookmarks + quotes) is at
     *  least half the best score among them; null when none has content. */
    fun pickDefault(cands: List<Candidate>): Candidate? = null // R3 stub (owner: DA-C)
    /** Pure: own files to delete (newest [KEEP_OWN] kept), by name. */
    fun toRotate(ownNames: List<String>): List<String> = emptyList() // R3 stub (owner: DA-C)
    /** Pure: "readeraplus-auto-<id8>-<yyyyMMdd-HHmm>.json" and its parse (id8, time) or null. */
    fun autoName(id8: String, millis: Long, tz: TimeZone = TimeZone.getDefault()): String = TODO("owner: DA-C")
    fun parseAutoName(name: String): Pair<String, Long>? = null // R3 stub (owner: DA-C)

    /** Main thread; returns at once. Runs [runNow] after [delayMs] on the backup thread (latest request wins). */
    fun schedule(context: Context, delayMs: Long, busy: () -> Boolean) {} // R3 stub (owner: DA-C)
    fun cancelScheduled() {} // R3 stub (owner: DA-C)
    /** Blocking (backup thread). [force] (지금 백업) skips isDue, the hash and the empty guard, keeps the other gates. */
    fun runNow(context: Context, force: Boolean, busy: () -> Boolean): Outcome = Outcome.DISABLED // R3 stub (owner: DA-C)
    /** Blocking (IO). [Δ] Headers of up to 5 newest backups of OTHER installs (and manual exports), newest first;
     *  empty without read access or when none. The offer uses pickDefault(); "다른 백업 보기" lists them all. */
    fun findCandidates(context: Context): List<Candidate> = emptyList() // R3 stub (owner: DA-C)
    /** Blocking (IO). Backup.import(candidate) + InstallState.settleOffer; returns restored book count. */
    fun restore(context: Context, c: Candidate): Int = 0 // R3 stub (owner: DA-C)
    /** Blocking (IO). Deletes this install's auto files, and other installs' too when [others]. Returns the count. */
    fun deleteFiles(context: Context, others: Boolean): Int = 0 // R3 stub (owner: DA-C)
    fun lastWrittenAt(context: Context): Long = 0L // R3 stub (owner: DA-C)
    /** "다운로드/ReaderaPlus/backup". */
    fun locationLabel(): String = TODO("owner: DA-C")
}
