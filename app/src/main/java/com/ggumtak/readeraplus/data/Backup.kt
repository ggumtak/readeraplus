package com.ggumtak.readeraplus.data

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * JSON export/import of library state (flags, positions, bookmarks, quotes, collections, reviews, reading log,
 * per-book prefs, lookups, PDF notes) + settings. File format: see [BackupJson] / [SettingsJson]; restore rules: [BackupMerge].
 * The auto backup ([AutoBackup]) writes the same file from [snapshot].
 */
object Backup {
    private const val TAG = "Backup"
    /** Backups larger than this are rejected (a real one is a few MB at most). */
    internal const val MAX_BYTES = 64L * 1024 * 1024

    /**
     * The reader's per-book parse records ("b<id>" → `TextPositions.encode`), which ReaderActivity keeps in the
     * SharedPreferences of this name. A restore marks positions of another TXT parse there ([markTextPositions]).
     */
    internal const val TEXT_POSITIONS_PREFS = "reader_text_positions"

    /** Writes a backup JSON to [out] (a manual export: `origin.auto = false`). Streams book by book. */
    fun export(context: Context, out: OutputStream) {
        val snap = snapshot(context) { false } ?: return
        val data = headed(snap.data, System.currentTimeMillis(), origin(context, auto = false))
        BackupJson.write(data, BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024))
    }

    /** Thrown inside a snapshot or a write when the caller's busy() turned true (S §3.2). */
    internal class BusyException : RuntimeException("busy")

    /** A library + settings snapshot; [settingsAreDefault] feeds [AutoBackup.isBlank]. */
    internal class Snapshot(val data: BackupData, val settingsAreDefault: Boolean)

    /**
     * The whole backup as model objects ([BackupData], `createdAt = 0`, no origin, the summary set), read query by
     * query with [busy] checked after each (books, bookmarks, quotes, memberships, collections, reading log, book
     * prefs, lookups, settings); null when it turned true. Raw prefs go in sorted key order, so an unchanged state
     * always serialises — and hashes — the same. Blocking (IO thread). This is S §3.2's `snapshotJson`, kept as
     * models: the 10–20 MB JSON tree is never built; [BackupJson.write] streams it (K11). A PDF book that has a
     * notes file ([PdfNoteFiles]) is only marked ([BackupBook.sourceId]); the write reads the file when it reaches
     * the book.
     */
    internal fun snapshot(context: Context, busy: () -> Boolean): Snapshot? {
        Library.init(context)
        Settings.init(context)
        val db = Library.db()
        val extra = BackupSql.BOOK_EXTRA
        val books = db.queryList(BackupSql.SELECT_BOOKS, null) { c ->
            BookRow(BookRows.book(c).copy(missingAt = c.getLong(extra + 2)), c.getInt(extra) != 0, c.getLong(extra + 1))
        }
        if (busy()) return null
        val bookmarks = db.queryList(BackupSql.SELECT_ALL_BOOKMARKS, null, ::bookmark).groupBy { it.bookId }
        if (busy()) return null
        val quotes = db.queryList(BackupSql.SELECT_ALL_QUOTES, null, ::quote).groupBy { it.bookId }
        if (busy()) return null
        val memberships = db.queryList(LibrarySql.SELECT_ALL_MEMBERSHIPS, null) { c ->
            c.getLong(0) to (c.getString(1) ?: "")
        }.groupBy({ it.first }, { it.second })
        val collectionNames = db.queryList(LibrarySql.SELECT_COLLECTION_NAMES, null) { it.getString(0) ?: "" }
            .filter { it.isNotBlank() }
        if (busy()) return null
        val logs = db.queryList(LibrarySql.SELECT_ALL_LOG, null) { c ->
            c.getLong(0) to BackupLogDay(c.getInt(1), c.getLong(2), c.getInt(3), c.getLong(4))
        }.groupBy({ it.first }, { it.second })
        if (busy()) return null
        val prefs = HashMap<Long, BackupPrefs>()
        db.queryList(BackupSql.SELECT_ALL_BOOK_PREFS, null) { c ->
            c.getLong(0) to BackupPrefs(
                txtOverride = if (c.isNull(1)) null else TxtOverride.fromJson(c.getString(1)),
                finishedAt = c.getLong(2),
                episodeLabel = if (c.isNull(3)) null else c.getString(3),
                returnMark = if (c.isNull(4)) null else c.getString(4)?.ifEmpty { null },
            )
        }.forEach { (id, p) -> if (!p.isEmpty) prefs[id] = p }
        if (busy()) return null
        val lookups = db.queryList(BackupSql.SELECT_ALL_LOOKUPS, null, ::lookup).groupBy { it.bookId }
        if (busy()) return null

        val reader = Settings.reader
        val app = Settings.app
        val styles = Settings.userStyles
        val settings = try {
            SettingsJson.settingsToJson(reader, app, java.util.TreeMap(Settings.raw().all), styles)
        } catch (t: Throwable) {
            Log.w(TAG, "settings export failed", t)
            null
        }
        val settingsAreDefault = reader == ReaderSettings() && app == AppSettings() && styles.isEmpty()
        if (busy()) return null
        val appContext = context.applicationContext ?: context
        val backupBooks = books.map { r ->
            val b = r.book
            val entry = BackupJson.fromBook(
                b, r.metaLocked,
                memberships[b.id].orEmpty(),
                bookmarks[b.id].orEmpty(),
                quotes[b.id].orEmpty(),
                logs[b.id].orEmpty(),
                prefs[b.id],
                r.reviewAt,
                lookups[b.id].orEmpty(),
            )
            if (b.format == BookFormat.PDF && PdfNoteFiles.exists(appContext, b.id)) entry.copy(sourceId = b.id) else entry
        }
        val data = BackupData(
            version = BackupJson.VERSION,
            createdAt = 0L,
            books = backupBooks,
            collections = collectionNames,
            settings = settings,
            txtParseVersion = TxtDocuments.PARSE_VERSION,
            summary = BackupJson.summaryOf(backupBooks),
            pdfNotes = { entry -> PdfNoteFiles.readText(appContext, entry.sourceId, PdfNoteFiles.MAX_BACKUP_BOOK_CHARS) },
        )
        return Snapshot(data, settingsAreDefault)
    }

    /** [d] with its header set: [createdAt] and [origin] (the summary counted when missing). */
    internal fun headed(d: BackupData, createdAt: Long, origin: BackupOrigin?): BackupData =
        BackupData(d.version, createdAt, d.books, d.collections, d.settings, d.txtParseVersion, origin,
            d.summary ?: BackupJson.summaryOf(d.books), d.pdfNotes)

    /** The `origin` header of a file this install writes (one PackageManager call; IO thread). */
    internal fun origin(context: Context, auto: Boolean): BackupOrigin {
        val app = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
        val device = listOf(Build.MANUFACTURER ?: "", Build.MODEL ?: "").filter { it.isNotBlank() }.joinToString(" ")
        return BackupOrigin(InstallState.installId(context), auto, app, device)
    }

    private class BookRow(val book: Book, val metaLocked: Boolean, val reviewAt: Long)

    private fun bookmark(c: Cursor): Bookmark = Bookmark(
        id = c.getLong(0), bookId = c.getLong(1), section = c.getInt(2), offset = c.getInt(3),
        snippet = c.getString(4) ?: "", createdAt = c.getLong(5), note = c.getString(6) ?: "",
        chapter = c.getString(7) ?: "", frac = c.getFloat(8), sig = c.getString(9) ?: "",
    )

    private fun quote(c: Cursor): Quote = Quote(
        id = c.getLong(0), bookId = c.getLong(1), section = c.getInt(2), start = c.getInt(3), end = c.getInt(4),
        text = c.getString(5) ?: "", note = c.getString(6) ?: "", createdAt = c.getLong(7),
        style = c.getInt(8), chapter = c.getString(9) ?: "", frac = c.getFloat(10), sig = c.getString(11) ?: "",
    )

    private fun lookup(c: Cursor): Lookup = Lookup(
        id = 0, bookId = c.getLong(0), word = c.getString(1) ?: "", section = c.getInt(2), start = c.getInt(3),
        end = c.getInt(4), context = c.getString(5) ?: "", chapter = c.getString(6) ?: "", frac = c.getFloat(7),
        sig = c.getString(8) ?: "", via = c.getInt(9), app = c.getString(10) ?: "", note = c.getString(11) ?: "",
        createdAt = c.getLong(12),
    )

    /**
     * Restores from JSON (N §5.6 merge: a union, nothing is deleted); books are matched by path, then by file name +
     * size; a book with notes whose file is not here becomes a placeholder in 휴지통. Settles the restore offer
     * (S §3.4). Returns the number of books matched on this device.
     */
    fun import(context: Context, input: InputStream): Int {
        Library.init(context)
        Settings.init(context)
        val bytes = readLimited(input)
        val data = BackupJson.parse(String(bytes, Charsets.UTF_8))
        val matches = resolveBooks(data.books)
        val pdfNotes = ArrayList<Pair<Long, String>>()
        val remap = applyLibrary(data, matches, pdfNotes)
        Library.notesChanged() // after applyLibrary's commit (N §5.1 / §5.6)
        restorePdfNotes(context, pdfNotes)
        markTextPositions(context, remap)
        data.settings?.let { applySettings(it) }
        InstallState.settleOffer(context)
        return matches.count { !it.placeholder }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IllegalArgumentException("백업 파일이 너무 큽니다")
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /** A backup entry and its library row; [placeholder] = no file here, [id] is made by [applyLibrary]. */
    private class Match(
        val id: Long,
        val backup: BackupBook,
        val fileFound: Boolean,
        val placeholder: Boolean = false,
    )

    private class Row(val id: Long, val path: String, val size: Long, val fileName: String, val lastReadAt: Long)

    /**
     * Library ids for backup entries: path → file name + size → the file itself if it exists (added). An entry that
     * resolves to nothing but holds notes becomes a placeholder ([BackupMerge.needsPlaceholder]).
     */
    private fun resolveBooks(books: List<BackupBook>): List<Match> {
        val db = Library.db()
        val rows = db.queryList(LibrarySql.SELECT_SCAN_STATE, null) { c ->
            Row(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getString(5) ?: "", c.getLong(6))
        }
        val byPath = HashMap<String, Row>(rows.size * 2)
        val byNameSize = HashMap<String, MutableList<Row>>(rows.size * 2)
        for (r in rows) {
            byPath[r.path] = r
            byNameSize.getOrPut(nameSizeKey(r.fileName, r.size)) { ArrayList(1) } += r
        }
        val used = HashSet<Long>()
        // Paths a placeholder must not take: every row's, the files added below, earlier placeholders.
        val taken = HashSet<String>(byPath.keys)
        val paths = books.map { if (it.path.isNotEmpty()) Library.normalizePath(it.path) else "" }
        val out = arrayOfNulls<Match>(books.size)
        // Pass 1: exact paths, so a name + size fallback of another entry can't take a row whose own entry follows.
        for ((i, b) in books.withIndex()) {
            val row = byPath[paths[i]]?.takeIf { paths[i].isNotEmpty() && it.id !in used } ?: continue
            used += row.id
            out[i] = Match(row.id, b, b.missingAt > 0 && fileExists(row.path))
        }
        // Pass 2: file name + size, then the file itself if it exists (added), else a placeholder.
        for ((i, b) in books.withIndex()) {
            if (out[i] != null) continue
            val path = paths[i]
            val row = if (b.fileName.isNotEmpty() && b.size >= 0) {
                byNameSize[nameSizeKey(b.fileName, b.size)]?.firstOrNull { it.id !in used }
            } else {
                null
            }
            var id = row?.id
            var found = row != null && b.missingAt > 0 && fileExists(row.path)
            if (id == null && path.isNotEmpty()) {
                val added = try {
                    val f = File(path)
                    if (f.isFile) Library.addOrUpdate(f, explicit = true) else null
                } catch (t: Throwable) {
                    Log.w(TAG, "could not add ${b.fileName}: $t")
                    null
                }
                if (added != null) taken += added.path
                if (added != null && added.id !in used) {
                    id = added.id
                    found = true
                }
            }
            if (id == null) {
                // Its notes would be lost silently: keep them under a placeholder (N §5.6). A path already taken by a
                // row or by an earlier placeholder can't hold another one.
                if (BackupMerge.needsPlaceholder(b) && path.isNotEmpty() && taken.add(path)) {
                    out[i] = Match(-1, b.copy(path = path), fileFound = false, placeholder = true)
                }
                continue
            }
            used += id
            out[i] = Match(id, b, found)
        }
        return out.filterNotNull()
    }

    private fun fileExists(path: String): Boolean = try {
        path.isNotEmpty() && File(path).isFile
    } catch (t: Throwable) {
        false
    }

    private fun nameSizeKey(fileName: String, size: Long): String = "$fileName\u0000$size"

    /** A new placeholder row for [b] (title, author, format from the backup); its id, or null when the path is taken. */
    private fun SQLiteDatabase.insertPlaceholder(b: BackupBook, now: Long): Long? {
        val format = BookFormat.entries.firstOrNull { it.name == b.format } ?: BookFormat.forFile(b.fileName)
            ?: BookFormat.TXT
        val fileName = b.fileName.ifEmpty { b.path.substringAfterLast('/') }
        val series = b.series?.let { MetaInfo.clean(it, 500) }?.ifEmpty { null }
        val id = insertRow(
            BackupSql.INSERT_PLACEHOLDER,
            b.path, fileName, b.path.substringBeforeLast('/', ""),
            MetaInfo.clean(b.title, DataLimits.TITLE), MetaInfo.clean(b.author, DataLimits.AUTHOR),
            series, if (series == null) null else b.seriesIndex,
            format.name, b.size.coerceAtLeast(0L), b.mtime, if (b.addedAt > 0) b.addedAt else now, b.language,
        )
        // -1: the path is in the library after all (added since the resolve); never trash that real book.
        return id.takeIf { it > 0 }
    }

    /**
     * Restores [matches]; returns (book id, progress) of the restored positions to find again by fraction. The PDF
     * notes of the entries are added to [pdfNotes] as (book id, notes text): files are written after the commit
     * ([restorePdfNotes]), never inside the transaction.
     */
    private fun applyLibrary(
        data: BackupData,
        matches: List<Match>,
        pdfNotes: MutableList<Pair<Long, String>>,
    ): List<Pair<Long, Float>> {
        val db = Library.db()
        val now = System.currentTimeMillis()
        val remap = ArrayList<Pair<Long, Float>>()
        db.inTransaction {
            val collectionIds = HashMap<String, Long>()
            fun collection(name: String): Long {
                // Same folding as the NOCASE unique name: "Ä" and "ä" are different collections to SQLite.
                val key = Library.collectionKey(name)
                if (key.isEmpty()) return -1
                return collectionIds.getOrPut(key) { Library.ensureCollection(this, name, now) }
            }
            for (n in data.collections) collection(n)
            for (m in matches) {
                val b = m.backup
                // A placeholder whose path turned up in the library meanwhile merges into that row like a match.
                val placeholderId = if (m.placeholder) insertPlaceholder(b, now) else null
                val id = when {
                    !m.placeholder -> m.id
                    placeholderId != null -> placeholderId
                    else -> queryFirst(LibrarySql.SELECT_ID_BY_PATH, args(b.path)) { it.getLong(0) } ?: continue
                }
                val cur = queryFirst(BackupSql.SELECT_BOOK_STATE, args(id)) { c ->
                    BackupMerge.BookState(
                        lastReadAt = c.getLong(0), favorite = c.getInt(1) != 0, toRead = c.getInt(2) != 0,
                        haveRead = c.getInt(3) != 0, trashed = c.getInt(4) != 0, review = c.getString(5) ?: "",
                        reviewAt = c.getLong(6), encoding = c.getString(7) ?: "", missingAt = c.getLong(8),
                    )
                } ?: continue
                val r = BackupMerge.book(cur, b, m.fileFound, if (placeholderId != null) now else 0L)
                if (b.metaLocked && b.title.isNotBlank()) {
                    val series = b.series?.let { MetaInfo.clean(it, 500) }?.ifEmpty { null }
                    exec(
                        LibrarySql.UPDATE_BOOK_META_USER,
                        MetaInfo.clean(b.title, 500), MetaInfo.clean(b.author, 300), series,
                        if (series == null) null else b.seriesIndex, id,
                    )
                }
                exec(
                    BackupSql.RESTORE_BOOK,
                    r.favorite, r.toRead, r.haveRead, r.trashed, r.review, r.reviewAt, r.encoding, r.missingAt,
                    b.readingSeconds, if (b.addedAt > 0) b.addedAt else Long.MAX_VALUE, id,
                )
                // Newer wins: an older backup doesn't move a position read later on this device.
                if (r.applyPosition) {
                    // The backup's own read time is the guard too: a row already read later keeps its place. Never
                    // later than now (a backup from a device with a fast clock must not stamp the row in the future).
                    val at = minOf(b.lastReadAt, now)
                    exec(
                        LibrarySql.UPDATE_POSITION,
                        b.posSection, b.posOffset, b.progress, at, id, at, at + LibrarySql.FUTURE_STAMP_MS,
                    )
                    if (BackupJson.remapsTextPosition(data.txtParseVersion, b)) remap += id to b.progress
                }
                for (name in b.collections) {
                    val cid = collection(name)
                    if (cid > 0) insertRow(LibrarySql.INSERT_MEMBERSHIP, id, cid)
                }
                if (b.bookmarks.isNotEmpty()) {
                    val existing = queryList(BackupSql.SELECT_BOOKMARKS_OF_BOOK, args(id), ::bookmark)
                    val plan = BackupMerge.bookmarks(existing, b.bookmarks)
                    for (bm in plan.inserts) {
                        insertRow(
                            BackupSql.INSERT_BOOKMARK,
                            id, bm.section, bm.offset, bm.snippet, bm.note, if (bm.createdAt > 0) bm.createdAt else now,
                            bm.chapter, bm.frac, bm.sig,
                        )
                    }
                    for (f in plan.fills) {
                        f.note?.let { exec(LibrarySql.UPDATE_BOOKMARK_NOTE, it, f.id) }
                        f.place?.let { exec(BackupSql.FILL_BOOKMARK_PLACE, it.chapter, it.frac, it.sig, f.id) }
                    }
                }
                for (d in b.readingLog) {
                    // The larger value of each column wins: restoring the same backup twice adds nothing.
                    if (exec(LibrarySql.LOG_RESTORE, d.seconds, d.pages, d.chars, d.day, id) == 0) {
                        insertRow(LibrarySql.LOG_INSERT, d.day, d.seconds, d.pages, d.chars, id)
                    }
                }
                restorePrefs(this, id, b, r)
                b.pdfNotes?.let { pdfNotes += id to it }
                if (b.quotes.isNotEmpty()) {
                    val existing = queryList(BackupSql.SELECT_QUOTES_OF_BOOK, args(id), ::quote)
                    val plan = BackupMerge.quotes(existing, b.quotes)
                    for (q in plan.inserts) {
                        insertRow(
                            BackupSql.INSERT_QUOTE,
                            id, q.section, q.start, q.end, q.text, q.note, if (q.createdAt > 0) q.createdAt else now,
                            q.style, q.chapter, q.frac, q.sig,
                        )
                    }
                    for (f in plan.fills) {
                        f.style?.let { exec(BackupSql.FILL_QUOTE_STYLE, it, f.id) }
                        f.place?.let { exec(BackupSql.FILL_QUOTE_PLACE, it.chapter, it.frac, it.sig, f.id) }
                        f.note?.let { exec(LibrarySql.UPDATE_QUOTE_NOTE, it, f.id) }
                    }
                }
                if (b.lookups.isNotEmpty()) {
                    val existing = HashSet<String>()
                    queryList(BackupSql.SELECT_LOOKUP_KEYS_OF_BOOK, args(id)) { c ->
                        BackupMerge.lookupKey(c.getString(0) ?: "", c.getInt(1), c.getInt(2), c.getLong(3))
                    }.forEach { existing += it }
                    for ((l, key) in BackupMerge.lookups(existing, b.lookups, LookupWords::key)) {
                        insertRow(
                            BackupSql.INSERT_LOOKUP,
                            id, l.word, key, l.section, l.start, l.end, l.context, l.chapter, l.frac, l.sig, l.via,
                            l.app, l.note, if (l.createdAt > 0) l.createdAt else now,
                        )
                    }
                }
            }
        }
        return remap
    }

    /**
     * Writes the PDF notes of restored books to the files the PDF viewer reads, under the book's id on this device
     * (a new one for an added file or a placeholder). The device's own notes stay ([PdfNoteFiles.shouldRestore]);
     * one failing book never stops the rest.
     */
    private fun restorePdfNotes(context: Context, notes: List<Pair<Long, String>>) {
        for ((id, text) in notes) {
            try {
                PdfNoteFiles.restore(context, id, text)
            } catch (t: Throwable) {
                Log.w(TAG, "pdf notes restore failed for book $id", t)
            }
        }
    }

    /**
     * Restored TXT positions of another parse ([BackupJson.remapsTextPosition]) get a parse record no open matches,
     * so the next open finds each place by its fraction instead of at (section, offset) of the old split. Written
     * after the library's transaction has committed, with `commit()` so an app restart right after keeps them.
     */
    private fun markTextPositions(context: Context, remap: List<Pair<Long, Float>>) {
        if (remap.isEmpty()) return
        try {
            val e = context.getSharedPreferences(TEXT_POSITIONS_PREFS, Context.MODE_PRIVATE).edit()
            for ((id, progress) in remap) e.putString("b$id", BackupJson.staleTextPosition(progress))
            e.commit()
        } catch (t: Throwable) {
            Log.w(TAG, "text position marks failed", t)
        }
    }

    /** [b]'s prefs over the book's current row ([BackupJson.mergePrefs]); must run inside a transaction. */
    private fun restorePrefs(db: SQLiteDatabase, id: Long, b: BackupBook, r: BackupMerge.BookResult) {
        val current = db.queryFirst(BackupSql.SELECT_BOOK_PREFS, args(id)) { c ->
            PrefsRow(
                if (c.isNull(0)) null else c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getString(2),
                if (c.isNull(3)) null else c.getString(3),
            )
        }
        val merged = BackupJson.mergePrefs(current, b.prefs, r.haveRead, r.deviceNewer)
        if (merged == current) return
        // Deleted only when nothing is left, return_mark included (PrefsRow.isEmpty covers it): no column is lost.
        if (merged == null) {
            db.exec(LibrarySql.DELETE_BOOK_PREFS_OF_BOOK, id)
        } else if (db.exec(BackupSql.SET_PREFS_ROW, merged.txtOverride, merged.finishedAt, merged.episodeLabel,
                merged.returnMark, id) == 0) {
            db.insertRow(BackupSql.INSERT_PREFS_ROW, merged.txtOverride, merged.finishedAt, merged.episodeLabel,
                merged.returnMark, id)
        }
    }

    private fun applySettings(s: org.json.JSONObject) {
        try {
            s.optJSONObject("reader")?.let { Settings.saveReader(SettingsJson.readerFromJson(it, Settings.reader)) }
        } catch (t: Throwable) {
            Log.w(TAG, "reader settings restore failed", t)
        }
        try {
            s.optJSONObject("app")?.let { Settings.saveApp(SettingsJson.appFromJson(it, Settings.app)) }
        } catch (t: Throwable) {
            Log.w(TAG, "app settings restore failed", t)
        }
        try {
            // Typed like reader/app (the in-memory list must change too); absent in older backups = keep the device's.
            SettingsJson.userStylesFromJson(s)?.let { Settings.saveUserStyles(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "user styles restore failed", t)
        }
        try {
            val prefs = Settings.raw()
            // Read after saveReader/saveApp: every field of this build's settings classes is stored now.
            val current = prefs.all
            val editor = prefs.edit()
            // Settings fields this mapper doesn't know by name yet (typed like this device stores them). They
            // reach Settings' in-memory cache with the restart the restore screen offers.
            val unmapped = SettingsJson.unmappedFromJson(s.optJSONObject("reader"), SettingsJson.READER_PREFIX, current) +
                SettingsJson.unmappedFromJson(s.optJSONObject("app"), SettingsJson.APP_PREFIX, current)
            for (p in unmapped) put(editor, p)
            for (p in SettingsJson.otherFromJson(s.optJSONObject("other"), s.optJSONObject("otherTypes"))) {
                // Never change the stored type of an existing key (a mismatched get* would throw at runtime).
                val existing = current[p.key]
                if (existing != null && !sameType(existing, p.type)) continue
                put(editor, p)
            }
            // commit() also waits for the apply()s of saveReader/saveApp: the restore is on disk before an
            // app restart kills the process.
            editor.commit()
        } catch (t: Throwable) {
            Log.w(TAG, "raw settings restore failed", t)
        }
    }

    private fun sameType(v: Any, type: String): Boolean = when (type) {
        SettingsJson.TYPE_STRING -> v is String
        SettingsJson.TYPE_BOOL -> v is Boolean
        SettingsJson.TYPE_INT -> v is Int
        SettingsJson.TYPE_LONG -> v is Long
        SettingsJson.TYPE_FLOAT -> v is Float
        SettingsJson.TYPE_SET -> v is Set<*>
        else -> false
    }

    @Suppress("UNCHECKED_CAST")
    private fun put(e: SharedPreferences.Editor, p: RawPref) {
        when (p.type) {
            SettingsJson.TYPE_STRING -> e.putString(p.key, p.value as String)
            SettingsJson.TYPE_BOOL -> e.putBoolean(p.key, p.value as Boolean)
            SettingsJson.TYPE_INT -> e.putInt(p.key, p.value as Int)
            SettingsJson.TYPE_LONG -> e.putLong(p.key, p.value as Long)
            SettingsJson.TYPE_FLOAT -> e.putFloat(p.key, p.value as Float)
            SettingsJson.TYPE_SET -> e.putStringSet(p.key, p.value as Set<String>)
        }
    }
}
