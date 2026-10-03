package com.ggumtak.readeraplus.data

import android.Manifest
import android.annotation.TargetApi
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.ggumtak.readeraplus.settings.Settings
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The daily automatic backup (S §3): at most once per [MIN_INTERVAL_MS], only when something changed, into
 * `Download/ReaderaPlus/backup/` (survives an uninstall), each install writing and rotating only its own files
 * (`readeraplus-auto-<id8>-…`), and the candidates a fresh install offers to restore.
 *
 * Never on an open path: [schedule] is called by the library's idle wait and the reader's `onStop`, and the run
 * returns [Outcome.BUSY] as soon as `busy()` turns true — between the snapshot's queries and every 256 KB of hashing
 * or writing (a partial file is deleted). The snapshot is streamed, never built as one JSON tree (K11).
 */
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
    class Summary(val books: Int, val read: Int, val bookmarks: Int, val quotes: Int) {
        /** What a restore would bring back beyond the file list (S §3.4). */
        val score: Int get() = read + bookmarks + quotes
        override fun toString(): String = "$books,$read,$bookmarks,$quotes"
    }
    /** [Δ] Header only (read with android.util.JsonReader, `books` skipped); [uri] for MediaStore/SAF sources. */
    class Candidate(val file: File?, val uri: Uri?, val createdAt: Long, val auto: Boolean, val installId8: String?,
                    val summary: Summary)

    private const val TAG = "AutoBackup"
    /** The clock went back more than this since the last check: due again (S §3.2). */
    private const val CLOCK_BACK_MS = 3_600_000L
    /** Headers read for an offer / list. */
    private const val MAX_CANDIDATES = 5
    /** busy() is checked every this many bytes of hashing and writing. */
    private const val BUSY_CHECK_BYTES = 256 * 1024
    private const val JSON_MIME = "application/json"
    private val AUTO_NAME = Regex("^" + Regex.escape(AUTO_PREFIX) + "([0-9a-fA-F]{8})-(\\d{8}-\\d{4})\\.json$")
    private const val NAME_TIME = "yyyyMMdd-HHmm"

    // ---- pure policy ----

    /** Pure: never checked, ≥ MIN_INTERVAL since the last check, or the clock went back more than 1 h. */
    fun isDue(now: Long, checkedAt: Long): Boolean =
        checkedAt <= 0L || now - checkedAt >= MIN_INTERVAL_MS || checkedAt - now > CLOCK_BACK_MS

    /** Pure: the new snapshot is empty (0 read, 0 bookmarks, 0 quotes) while the last write was not. */
    fun wouldEmpty(last: Summary?, now: Summary): Boolean = last != null && last.score > 0 && now.score == 0

    /** [Δ] Pure: nothing worth keeping (0 read, 0 bookmarks, 0 quotes and settings equal to the defaults). */
    fun isBlank(now: Summary, settingsAreDefault: Boolean): Boolean = now.score == 0 && settingsAreDefault

    /** [Δ] Pure: the default offer among up to 5 headers: the newest whose score (read + bookmarks + quotes) is at
     *  least half the best score among them; null when none has content. */
    fun pickDefault(cands: List<Candidate>): Candidate? {
        val best = cands.maxOfOrNull { it.summary.score } ?: return null
        if (best <= 0) return null
        var pick: Candidate? = null
        for (c in cands) {
            // 2 × score ≥ best, in Long: no overflow for absurd header counts.
            if (2L * c.summary.score < best) continue
            if (pick == null || c.createdAt > pick.createdAt) pick = c // ties: the earlier in the (newest-first) list
        }
        return pick
    }

    /** Pure: own files to delete (newest [KEEP_OWN] kept), by name. Names that are not auto names are never listed. */
    fun toRotate(ownNames: List<String>): List<String> {
        val parsed = ownNames.mapNotNull { n -> parseAutoName(n)?.let { n to it.second } }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenByDescending { it.first })
        return if (parsed.size <= KEEP_OWN) emptyList() else parsed.drop(KEEP_OWN).map { it.first }
    }

    /** Pure: "readeraplus-auto-<id8>-<yyyyMMdd-HHmm>.json" and its parse (id8, time) or null. */
    fun autoName(id8: String, millis: Long, tz: TimeZone = TimeZone.getDefault()): String =
        AUTO_PREFIX + id8.lowercase(Locale.ROOT) + "-" + nameFormat(tz).format(java.util.Date(millis)) + ".json"

    fun parseAutoName(name: String): Pair<String, Long>? = parseAutoName(name, TimeZone.getDefault())

    internal fun parseAutoName(name: String, tz: TimeZone): Pair<String, Long>? {
        val m = AUTO_NAME.matchEntire(name) ?: return null
        val t = try {
            nameFormat(tz).parse(m.groupValues[2])?.time
        } catch (_: Exception) {
            null
        } ?: return null
        return m.groupValues[1].lowercase(Locale.ROOT) to t
    }

    private fun nameFormat(tz: TimeZone) = SimpleDateFormat(NAME_TIME, Locale.US).apply {
        timeZone = tz
        isLenient = false
    }

    /** A file a candidate list may read: [time] = the name's time for auto files, `lastModified` for manual ones. */
    internal class Listed(val name: String, val auto: Boolean, val id8: String?, val time: Long, val inAutoDir: Boolean)

    /**
     * Pure: the files to read headers from, newest first: auto files of the backup folder ([autoDir], name →
     * lastModified) of other installs (and this one's when [includeOwn]), ordered by the time in the name (a copy or
     * a move resets `lastModified`); manual exports (`readeraplus-backup-*.json`; [manual] = full path →
     * lastModified) by `lastModified`. [Listed.name] is the auto file's name or the manual export's path.
     */
    internal fun listed(autoDir: List<Pair<String, Long>>, manual: List<Pair<String, Long>>, myId8: String,
                        includeOwn: Boolean): List<Listed> {
        val out = ArrayList<Listed>()
        for ((name, _) in autoDir) {
            val (id8, t) = parseAutoName(name) ?: continue
            if (!includeOwn && id8 == myId8.lowercase(Locale.ROOT)) continue
            out += Listed(name, true, id8, t, true)
        }
        for ((name, modified) in manual) {
            val base = name.substringAfterLast('/')
            if (!base.startsWith(MANUAL_PREFIX) || !base.endsWith(".json")) continue
            out += Listed(name, false, null, modified, false)
        }
        out.sortWith(compareByDescending<Listed> { it.time }.thenBy { it.name })
        return out
    }

    internal fun encodeSummary(s: Summary): String = s.toString()

    internal fun decodeSummary(text: String?): Summary? {
        val p = text?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        if (p.size != 4 || p.any { it < 0 }) return null
        return Summary(p[0], p[1], p[2], p[3])
    }

    /** The values of the last write, or all absent when [PREF_OWNER] is not this install (S §3.3). */
    internal class State(val checkedAt: Long, val writtenAt: Long, val hash: String?, val summary: Summary?,
                         val owned: Boolean)

    internal fun state(owner: String?, installId: String, checkedAt: Long, writtenAt: Long, hash: String?,
                       summary: String?): State =
        if (installId.isEmpty() || owner != installId) State(0L, 0L, null, null, false)
        else State(checkedAt, writtenAt, hash, decodeSummary(summary), true)

    // ---- triggers ----

    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "AutoBackup").apply { isDaemon = true }
        }
    }
    /** Single flight: a run never overlaps another (scheduled or 지금 백업). */
    private val running = AtomicBoolean(false)
    /** Main thread only. */
    private var pending: Runnable? = null

    /** Main thread; returns at once. Runs [runNow] after [delayMs] on the backup thread (latest request wins). */
    fun schedule(context: Context, delayMs: Long, busy: () -> Boolean) {
        val app = context.applicationContext ?: context
        cancelScheduled()
        val r = object : Runnable {
            override fun run() {
                if (pending === this) pending = null
                executor.execute {
                    val t0 = SystemClock.uptimeMillis()
                    val o = runNow(app, false, busy)
                    Log.i(TAG, "auto backup: $o in ${SystemClock.uptimeMillis() - t0} ms")
                }
            }
        }
        pending = r
        // uptimeMillis: a reader stopped by the screen going off runs it after the next wake; no repeating timer.
        main.postDelayed(r, delayMs.coerceAtLeast(0L))
    }

    fun cancelScheduled() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
    }

    // ---- the run ----

    /** Blocking (backup thread). [force] (지금 백업) skips isDue, the hash and the empty guard, keeps the other gates. */
    fun runNow(context: Context, force: Boolean, busy: () -> Boolean): Outcome {
        val ctx = context.applicationContext ?: context
        if (!running.compareAndSet(false, true)) return Outcome.BUSY
        return try {
            run(ctx, force, busy)
        } catch (_: Backup.BusyException) {
            Outcome.BUSY
        } catch (t: Throwable) {
            Log.w(TAG, "auto backup failed", t)
            Outcome.FAILED
        } finally {
            running.set(false)
        }
    }

    private fun run(ctx: Context, force: Boolean, busy: () -> Boolean): Outcome {
        // 1. Gates.
        Settings.init(ctx)
        Library.init(ctx)
        if (!Settings.app.autoBackup) return Outcome.DISABLED
        InstallState.verify(ctx)
        val installId = InstallState.installId(ctx)
        val id8 = installId.take(8)
        if (id8.length < 8) return Outcome.FAILED
        val prefs = Settings.raw()
        val st = readState(prefs, installId)
        val now = System.currentTimeMillis()
        if (!force && !isDue(now, st.checkedAt)) return Outcome.NOT_DUE
        val loc = location(ctx) ?: return Outcome.NO_LOCATION
        if (busy()) return Outcome.BUSY

        // 2. Snapshot (busy() between its queries).
        val snap = Backup.snapshot(ctx, busy) ?: return Outcome.BUSY
        val summary = snap.data.summary ?: BackupJson.summaryOf(snap.data.books)

        // 3. Hash of the snapshot as written, without createdAt and origin.
        val hash = hashOf(snap.data, busy)
        if (!force) {
            if (hash == st.hash && ownNames(ctx, loc, id8).isNotEmpty()) {
                prefs.edit().putLong(PREF_CHECKED_AT, now).commit()
                return Outcome.UNCHANGED
            }
            val emptied = wouldEmpty(st.summary, summary)
            if (emptied || isBlank(summary, snap.settingsAreDefault)) {
                Log.i(TAG, "nothing written: snapshot empty ($summary, last ${st.summary})")
                val e = prefs.edit()
                // An emptied library is checked again tomorrow; a blank install (a cheap snapshot) on the next
                // trigger, so its first quote or read book is saved the same day.
                if (emptied) e.putLong(PREF_CHECKED_AT, now)
                if (!st.owned) claim(e, installId)
                e.commit()
                return Outcome.SKIPPED_EMPTY
            }
        }
        if (busy()) return Outcome.BUSY

        // 4. Write (origin and summary in the header, before books), atomically.
        val name = autoName(id8, now)
        write(ctx, loc, name, Backup.headed(snap.data, now, Backup.origin(ctx, auto = true)), busy)

        // 5. Rotate (only after a successful write), then record.
        rotate(ctx, loc, id8, name)
        prefs.edit()
            .putLong(PREF_WRITTEN_AT, now)
            .putLong(PREF_CHECKED_AT, now)
            .putString(PREF_HASH, hash)
            .putString(PREF_SUMMARY, encodeSummary(summary))
            .putString(PREF_OWNER, installId)
            .commit()
        return Outcome.WROTE
    }

    private fun readState(p: SharedPreferences, installId: String): State = state(
        p.getString(PREF_OWNER, null), installId,
        safeLong(p, PREF_CHECKED_AT), safeLong(p, PREF_WRITTEN_AT), p.getString(PREF_HASH, null),
        p.getString(PREF_SUMMARY, null),
    )

    private fun safeLong(p: SharedPreferences, key: String): Long = try {
        p.getLong(key, 0L)
    } catch (_: ClassCastException) {
        0L
    }

    /** Makes this install the owner of the auto-backup values (another install's copies are dropped). */
    private fun claim(e: SharedPreferences.Editor, installId: String) {
        e.remove(PREF_HASH).remove(PREF_SUMMARY).remove(PREF_WRITTEN_AT).putString(PREF_OWNER, installId)
    }

    /** SHA-1 (hex) of the streamed snapshot; busy() is checked every 256 KB. */
    private fun hashOf(data: BackupData, busy: () -> Boolean): String {
        val md = MessageDigest.getInstance("SHA-1")
        val sink = BusyCheckedStream(object : OutputStream() {
            override fun write(b: Int) = md.update(b.toByte())
            override fun write(b: ByteArray, off: Int, len: Int) = md.update(b, off, len)
        }, busy)
        BackupJson.write(Backup.headed(data, 0L, null), BufferedWriter(OutputStreamWriter(sink, Charsets.UTF_8), 64 * 1024))
        val sb = StringBuilder(40)
        for (b in md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b))
        return sb.toString()
    }

    /** Counts bytes and throws [Backup.BusyException] when busy() is true at a 256 KB boundary. */
    private class BusyCheckedStream(private val out: OutputStream, private val busy: () -> Boolean) : OutputStream() {
        private var sinceCheck = 0
        private fun count(n: Int) {
            sinceCheck += n
            if (sinceCheck >= BUSY_CHECK_BYTES) {
                sinceCheck = 0
                if (busy()) throw Backup.BusyException()
            }
        }
        override fun write(b: Int) {
            out.write(b)
            count(1)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count(len)
        }
        override fun flush() = out.flush()
    }

    // ---- locations ----

    /** Where this run writes: the shared folder by the File API, or (API 29+, no file access) MediaStore. */
    private sealed class Location {
        class Files(val dir: File) : Location()
        object Store : Location()
    }

    @Suppress("DEPRECATION")
    private fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    private fun backupDir(): File = File(primaryRoot(), RELATIVE_DIR.trimEnd('/'))

    private fun canWriteFiles(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun canReadFiles(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun location(ctx: Context): Location? {
        if (canWriteFiles(ctx)) {
            val dir = backupDir()
            if (dir.isDirectory || dir.mkdirs()) return Location.Files(dir)
        }
        return if (Build.VERSION.SDK_INT >= 29) Location.Store else null
    }

    private fun ownNames(ctx: Context, loc: Location, id8: String): List<String> = when (loc) {
        is Location.Files -> loc.dir.list()?.filter { parseAutoName(it)?.first == id8 }.orEmpty()
        Location.Store -> if (Build.VERSION.SDK_INT >= 29) storeRows(ctx, id8).map { it.second } else emptyList()
    }

    private fun write(ctx: Context, loc: Location, name: String, data: BackupData, busy: () -> Boolean) {
        when (loc) {
            is Location.Files -> {
                val tmp = File(loc.dir, "$name.tmp")
                val dst = File(loc.dir, name)
                try {
                    FileOutputStream(tmp).use { fos ->
                        writeTo(fos, data, busy)
                        fos.fd.sync()
                    }
                    if (!tmp.renameTo(dst)) {
                        dst.delete()
                        if (!tmp.renameTo(dst)) throw IOException("rename failed: $name")
                    }
                } catch (t: Throwable) {
                    tmp.delete()
                    throw t
                }
            }
            Location.Store -> if (Build.VERSION.SDK_INT >= 29) storeWrite(ctx, name, data, busy)
            else throw IOException("no location")
        }
    }

    private fun writeTo(out: OutputStream, data: BackupData, busy: () -> Boolean) {
        BackupJson.write(data, BufferedWriter(OutputStreamWriter(BusyCheckedStream(out, busy), Charsets.UTF_8), 64 * 1024))
    }

    @TargetApi(29)
    private fun storeWrite(ctx: Context, name: String, data: BackupData, busy: () -> Boolean) {
        val r = ctx.contentResolver
        // A second write in the same minute: MediaStore would name the new row "… (1).json", which no rotation or
        // delete recognises. Replace the earlier row of that name instead.
        parseAutoName(name)?.first?.let { id8 ->
            for ((uri, n) in storeRows(ctx, id8)) if (n == name) r.delete(uri, null, null)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, JSON_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("insert failed")
        try {
            (r.openOutputStream(uri, "w") ?: throw IOException("no stream")).use { writeTo(it, data, busy) }
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            r.update(uri, publish, null, null)
        } catch (t: Throwable) {
            try {
                r.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    /** This app's own MediaStore rows in the backup folder named for [id8] (null = any install): (uri, name). */
    @TargetApi(29)
    private fun storeRows(ctx: Context, id8: String?): List<Pair<Uri, String>> {
        val out = ArrayList<Pair<Uri, String>>()
        try {
            val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            ctx.contentResolver.query(
                base,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf(RELATIVE_DIR, AUTO_PREFIX + (id8 ?: "") + "%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val p = parseAutoName(name) ?: continue
                    if (id8 != null && p.first != id8) continue
                    out += ContentUris.withAppendedId(base, c.getLong(0)) to name
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore query failed: $t")
        }
        return out
    }

    /**
     * Pure: own files to delete after writing [written]: it is always kept (its name time may be older than the
     * others' when the clock went back), with the newest [KEEP_OWN] − 1 of the others.
     */
    internal fun toRotateAfter(ownNames: List<String>, written: String): List<String> {
        val others = ownNames.filter { it != written }.mapNotNull { n -> parseAutoName(n)?.let { n to it.second } }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenByDescending { it.first })
        return others.drop(KEEP_OWN - 1).map { it.first }
    }

    private fun rotate(ctx: Context, loc: Location, id8: String, written: String) {
        try {
            when (loc) {
                is Location.Files -> {
                    val names = loc.dir.list().orEmpty()
                    for (n in toRotateAfter(names.filter { parseAutoName(it)?.first == id8 }, written)) {
                        File(loc.dir, n).delete()
                    }
                    // Leftovers of a run killed mid-write.
                    for (n in names) {
                        if (n.endsWith(".json.tmp") && parseAutoName(n.removeSuffix(".tmp"))?.first == id8) {
                            File(loc.dir, n).delete()
                        }
                    }
                }
                Location.Store -> if (Build.VERSION.SDK_INT >= 29) {
                    val rows = storeRows(ctx, id8)
                    val drop = toRotateAfter(rows.map { it.second }, written).toSet()
                    for ((uri, name) in rows) if (name in drop) ctx.contentResolver.delete(uri, null, null)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "rotation failed: $t")
        }
    }

    // ---- restore side ----

    /** Blocking (IO). [Δ] Headers of up to 5 newest backups of OTHER installs (and manual exports), newest first;
     *  empty without read access or when none. The offer uses pickDefault(); "다른 백업 보기" lists them all. */
    fun findCandidates(context: Context): List<Candidate> = findCandidates(context, includeOwn = false)

    /**
     * As [findCandidates]; [includeOwn] also lists this install's own auto files (BackupPage's "자동 백업에서 복원",
     * S §3.8) — without file access then only those, through MediaStore.
     */
    fun findCandidates(context: Context, includeOwn: Boolean): List<Candidate> {
        val ctx = context.applicationContext ?: context
        try {
            InstallState.verify(ctx)
            val mine = InstallState.id8(ctx)
            val out = ArrayList<Candidate>()
            if (canReadFiles(ctx)) {
                val dir = backupDir()
                val autoFiles = dir.listFiles()?.filter { it.isFile }.orEmpty()
                val manualFiles = listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS)
                    .flatMap { File(primaryRoot(), it).listFiles()?.filter { f -> f.isFile }.orEmpty() }
                // Keyed by what [listed] returns: the name in the backup folder, the full path of a manual export
                // (the same name may be in Download/ and Documents/).
                val byKey = HashMap<String, File>()
                autoFiles.forEach { byKey[it.name] = it }
                manualFiles.forEach { byKey[it.absolutePath] = it }
                val list = listed(autoFiles.map { it.name to it.lastModified() },
                    manualFiles.map { it.absolutePath to it.lastModified() }, mine, includeOwn)
                for (l in list) {
                    if (out.size >= MAX_CANDIDATES) break
                    val f = byKey[l.name] ?: continue
                    if (f.length() > Backup.MAX_BYTES) continue
                    val h = try {
                        f.inputStream().use(::readHeader)
                    } catch (t: Throwable) {
                        Log.w(TAG, "unreadable backup ${f.name}: $t")
                        continue
                    }
                    out += candidate(f, null, l, h)
                }
            } else if (includeOwn && Build.VERSION.SDK_INT >= 29) {
                val rows = storeRows(ctx, mine).associateBy({ it.second }, { it.first })
                for (l in listed(rows.keys.map { it to 0L }, emptyList(), mine, includeOwn = true)) {
                    if (out.size >= MAX_CANDIDATES) break
                    val uri = rows[l.name] ?: continue
                    val h = try {
                        (ctx.contentResolver.openInputStream(uri) ?: continue).use(::readHeader)
                    } catch (t: Throwable) {
                        Log.w(TAG, "unreadable backup ${l.name}: $t")
                        continue
                    }
                    out += candidate(null, uri, l, h)
                }
            }
            return out
        } catch (t: Throwable) {
            Log.w(TAG, "candidates failed", t)
            return emptyList()
        }
    }

    private fun candidate(file: File?, uri: Uri?, l: Listed, h: BackupHeader): Candidate = Candidate(
        file = file,
        uri = uri,
        createdAt = if (h.createdAt > 0) h.createdAt else l.time,
        auto = l.auto,
        installId8 = l.id8 ?: h.origin?.installId?.take(8)?.ifEmpty { null },
        summary = h.summary,
    )

    /** The header of one file, under the restore's 64 MB guard. */
    private fun readHeader(input: InputStream): BackupHeader =
        BackupJson.readHeader(BufferedReader(InputStreamReader(LimitedStream(input, Backup.MAX_BYTES), Charsets.UTF_8)))

    private class LimitedStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var total = 0L
        private fun count(n: Int): Int {
            if (n > 0) {
                total += n
                if (total > max) throw IllegalArgumentException("백업 파일이 너무 큽니다")
            }
            return n
        }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
    }

    /** Blocking (IO). Backup.import(candidate) + InstallState.settleOffer; returns restored book count. */
    fun restore(context: Context, c: Candidate): Int {
        val ctx = context.applicationContext ?: context
        val input = c.file?.inputStream() ?: c.uri?.let { ctx.contentResolver.openInputStream(it) }
            ?: throw IOException("백업 파일을 열 수 없습니다")
        val n = input.use { Backup.import(ctx, it) }
        InstallState.settleOffer(ctx)
        // Waits for every pending apply() of the restore: on disk before a recreate or a restart.
        Settings.raw().edit().commit()
        return n
    }

    /** Blocking (IO). Deletes this install's auto files, and other installs' too when [others]. Returns the count. */
    fun deleteFiles(context: Context, others: Boolean): Int {
        val ctx = context.applicationContext ?: context
        Settings.init(ctx)
        // An id copied from an earlier install must not delete that install's files as "own".
        InstallState.verify(ctx)
        val id8 = InstallState.id8(ctx)
        var n = 0
        try {
            if (canWriteFiles(ctx)) {
                val dir = backupDir()
                for (f in dir.listFiles().orEmpty()) {
                    val owner = parseAutoName(f.name.removeSuffix(".tmp"))?.first ?: continue
                    if (!others && owner != id8) continue
                    if (f.delete() && !f.name.endsWith(".tmp")) n++
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                for ((uri, _) in storeRows(ctx, id8)) {
                    if (ctx.contentResolver.delete(uri, null, null) > 0) n++
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "delete failed: $t")
        }
        // The next run must write again instead of finding its hash unchanged.
        Settings.raw().edit().remove(PREF_HASH).remove(PREF_SUMMARY).remove(PREF_WRITTEN_AT).commit()
        return n
    }

    /** Blocking (IO). How many files [deleteFiles] with the same [others] would delete (the confirm's {n}). */
    fun countFiles(context: Context, others: Boolean): Int {
        val ctx = context.applicationContext ?: context
        Settings.init(ctx)
        InstallState.verify(ctx)
        val id8 = InstallState.id8(ctx)
        return try {
            if (canWriteFiles(ctx)) {
                backupDir().list().orEmpty().count { n ->
                    val owner = parseAutoName(n)?.first
                    owner != null && (others || owner == id8)
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                storeRows(ctx, id8).size
            } else {
                0
            }
        } catch (t: Throwable) {
            Log.w(TAG, "count failed: $t")
            0
        }
    }

    fun lastWrittenAt(context: Context): Long {
        Settings.init(context)
        return readState(Settings.raw(), InstallState.installId(context)).writtenAt
    }

    /** "다운로드/ReaderaPlus/backup". */
    fun locationLabel(): String = "다운로드/ReaderaPlus/backup"
}
