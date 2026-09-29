package com.ggumtak.readeraplus.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ggumtak.readeraplus.settings.Settings
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * JSON export/import of library state (flags, positions, bookmarks, quotes,
 * collections, reviews) + settings. File format: see [BackupJson] / [SettingsJson].
 */
object Backup {
    private const val TAG = "Backup"
    /** Backups larger than this are rejected (a real one is a few MB at most). */
    private const val MAX_BYTES = 64L * 1024 * 1024

    /** Writes a backup JSON to [out]. */
    fun export(context: Context, out: OutputStream) {
        Library.init(context)
        Settings.init(context)
        val db = Library.db()
        val books = db.queryList(LibrarySql.SELECT_ALL_BOOKS_FOR_BACKUP, null) { c ->
            BookRows.book(c) to (c.getInt(LibrarySql.BOOK_COLUMN_COUNT) != 0)
        }
        val bookmarks = db.queryList(LibrarySql.SELECT_ALL_BOOKMARKS, null, BookRows::bookmark).groupBy { it.bookId }
        val quotes = db.queryList(LibrarySql.SELECT_ALL_QUOTES, null, BookRows::quote).groupBy { it.bookId }
        val memberships = db.queryList(LibrarySql.SELECT_ALL_MEMBERSHIPS, null) { c ->
            c.getLong(0) to (c.getString(1) ?: "")
        }.groupBy({ it.first }, { it.second })
        val collectionNames = db.queryList(LibrarySql.SELECT_COLLECTION_NAMES, null) { it.getString(0) ?: "" }
            .filter { it.isNotBlank() }

        val settings = try {
            SettingsJson.settingsToJson(Settings.reader, Settings.app, Settings.raw().all)
        } catch (t: Throwable) {
            Log.w(TAG, "settings export failed", t)
            null
        }
        val data = BackupData(
            version = BackupJson.VERSION,
            createdAt = System.currentTimeMillis(),
            books = books.map { (b, locked) ->
                BackupJson.fromBook(
                    b, locked,
                    memberships[b.id].orEmpty(),
                    bookmarks[b.id].orEmpty(),
                    quotes[b.id].orEmpty(),
                )
            },
            collections = collectionNames,
            settings = settings,
        )
        val w = OutputStreamWriter(out, Charsets.UTF_8)
        w.write(BackupJson.toJson(data).toString(1))
        w.flush()
    }

    /** Restores from JSON; books are matched by path, then by file name + size. Returns restored book count. */
    fun import(context: Context, input: InputStream): Int {
        Library.init(context)
        Settings.init(context)
        val bytes = readLimited(input)
        val data = BackupJson.parse(String(bytes, Charsets.UTF_8))
        val matches = resolveBooks(data.books)
        applyLibrary(data, matches)
        data.settings?.let { applySettings(it) }
        return matches.size
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

    private class Match(val id: Long, val currentLastReadAt: Long, val backup: BackupBook)

    private class Row(val id: Long, val path: String, val size: Long, val fileName: String, val lastReadAt: Long)

    /** Library ids for backup entries: path → file name + size → the file itself if it exists (added). */
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
        val out = ArrayList<Match>(books.size)
        for (b in books) {
            val path = if (b.path.isNotEmpty()) Library.normalizePath(b.path) else ""
            var row = byPath[path]?.takeIf { it.id !in used }
            if (row == null && b.fileName.isNotEmpty() && b.size >= 0) {
                row = byNameSize[nameSizeKey(b.fileName, b.size)]?.firstOrNull { it.id !in used }
            }
            var id = row?.id
            var lastRead = row?.lastReadAt ?: 0L
            if (id == null && path.isNotEmpty()) {
                val added = try {
                    val f = File(path)
                    if (f.isFile) Library.addOrUpdate(f, explicit = true) else null
                } catch (t: Throwable) {
                    Log.w(TAG, "could not add ${b.fileName}: $t")
                    null
                }
                if (added != null && added.id !in used) {
                    id = added.id
                    lastRead = added.lastReadAt
                }
            }
            if (id == null) continue
            used += id
            out += Match(id, lastRead, b)
        }
        return out
    }

    private fun nameSizeKey(fileName: String, size: Long): String = "$fileName\u0000$size"

    private fun applyLibrary(data: BackupData, matches: List<Match>) {
        val db = Library.db()
        val now = System.currentTimeMillis()
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
                val id = m.id
                if (b.metaLocked && b.title.isNotBlank()) {
                    val series = b.series?.let { MetaInfo.clean(it, 500) }?.ifEmpty { null }
                    exec(
                        LibrarySql.UPDATE_BOOK_META_USER,
                        MetaInfo.clean(b.title, 500), MetaInfo.clean(b.author, 300), series,
                        if (series == null) null else b.seriesIndex, id,
                    )
                }
                exec(
                    LibrarySql.RESTORE_FLAGS,
                    b.favorite, b.toRead && !b.haveRead, b.haveRead, b.trashed, b.review, b.encoding,
                    b.readingSeconds, if (b.addedAt > 0) b.addedAt else Long.MAX_VALUE, id,
                )
                // A backup entry that was never read doesn't wipe progress made on this device.
                if (b.lastReadAt > 0 || m.currentLastReadAt == 0L) {
                    exec(LibrarySql.UPDATE_POSITION, b.posSection, b.posOffset, b.progress, b.lastReadAt, id)
                }
                for (name in b.collections) {
                    val cid = collection(name)
                    if (cid > 0) insertRow(LibrarySql.INSERT_MEMBERSHIP, id, cid)
                }
                if (b.bookmarks.isNotEmpty()) {
                    val existing = queryList(LibrarySql.SELECT_BOOKMARKS, args(id), BookRows::bookmark)
                        .associateBy { it.section.toLong() shl 32 or (it.offset.toLong() and 0xffffffffL) }
                        .toMutableMap()
                    for (bm in b.bookmarks) {
                        val key = bm.section.toLong() shl 32 or (bm.offset.toLong() and 0xffffffffL)
                        val cur = existing[key]
                        if (cur == null) {
                            val newId = insertRow(
                                LibrarySql.INSERT_BOOKMARK,
                                id, bm.section, bm.offset, bm.snippet, bm.note,
                                if (bm.createdAt > 0) bm.createdAt else now,
                            )
                            existing[key] = Bookmark(newId, id, bm.section, bm.offset, bm.snippet, bm.createdAt, bm.note)
                        } else if (cur.note.isEmpty() && bm.note.isNotEmpty()) {
                            exec(LibrarySql.UPDATE_BOOKMARK_NOTE, bm.note, cur.id)
                        }
                    }
                }
                if (b.quotes.isNotEmpty()) {
                    val existing = HashMap<String, Quote>()
                    for (q in queryList(LibrarySql.SELECT_QUOTES, args(id), BookRows::quote)) {
                        existing["${q.section}:${q.start}:${q.end}"] = q
                    }
                    for (q in b.quotes) {
                        val key = "${q.section}:${q.start}:${q.end}"
                        val cur = existing[key]
                        if (cur == null) {
                            val newId = insertRow(
                                LibrarySql.INSERT_QUOTE,
                                id, q.section, q.start, q.end, q.text, q.note,
                                if (q.createdAt > 0) q.createdAt else now,
                            )
                            existing[key] = Quote(newId, id, q.section, q.start, q.end, q.text, q.note, q.createdAt)
                        } else if (cur.note.isEmpty() && q.note.isNotEmpty()) {
                            exec(LibrarySql.UPDATE_QUOTE_NOTE, q.note, cur.id)
                        }
                    }
                }
            }
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
