package com.ggumtak.readeraplus.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class BackupBookmark(
    val section: Int,
    val offset: Int,
    val snippet: String,
    val note: String,
    val createdAt: Long,
)

internal data class BackupQuote(
    val section: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val note: String,
    val createdAt: Long,
)

/** One day of a book's reading log (T1-6); [day] = local yyyymmdd. */
internal data class BackupLogDay(val day: Int, val seconds: Long, val pages: Int, val chars: Long)

/** A book's `book_prefs` row (T1-9 / T1-2 / T2-13). [finishedAt] 0 = not finished. */
internal data class BackupPrefs(
    val txtOverride: TxtOverride? = null,
    val finishedAt: Long = 0,
    val episodeLabel: String? = null,
) {
    val isEmpty: Boolean get() = txtOverride == null && finishedAt <= 0 && episodeLabel == null
}

/** A `book_prefs` row as stored ([txtOverride] = the column's JSON text); see [BackupJson.mergePrefs]. */
internal data class PrefsRow(val txtOverride: String?, val finishedAt: Long, val episodeLabel: String?) {
    val isEmpty: Boolean get() = txtOverride == null && finishedAt <= 0 && episodeLabel == null
}

/** One library entry in a backup file. */
internal data class BackupBook(
    val path: String,
    val fileName: String,
    val size: Long,
    val mtime: Long = 0,
    val format: String = "",
    val title: String = "",
    val author: String = "",
    val series: String? = null,
    val seriesIndex: Float? = null,
    /** The user edited title/author/series: restore them. */
    val metaLocked: Boolean = false,
    val favorite: Boolean = false,
    val toRead: Boolean = false,
    val haveRead: Boolean = false,
    val trashed: Boolean = false,
    val review: String = "",
    val encoding: String = "",
    val language: String? = null,
    val posSection: Int = 0,
    val posOffset: Int = 0,
    val progress: Float = 0f,
    val lastReadAt: Long = 0,
    val addedAt: Long = 0,
    val readingSeconds: Long = 0,
    val collections: List<String> = emptyList(),
    val bookmarks: List<BackupBookmark> = emptyList(),
    val quotes: List<BackupQuote> = emptyList(),
    /** Reading log rows, ascending by day (R2; absent in older backups). */
    val readingLog: List<BackupLogDay> = emptyList(),
    /** The book's prefs row (R2; null in older backups and for books without one). */
    val prefs: BackupPrefs? = null,
)

internal class BackupData(
    val version: Int,
    val createdAt: Long,
    val books: List<BackupBook>,
    val collections: List<String>,
    /** `{reader:{…}, app:{…}, other:{…}, otherTypes:{…}}` or null when absent. */
    val settings: JSONObject?,
)

/**
 * Backup file ⇄ model mapping (org.json; pure). Reading is tolerant: missing / wrong-typed fields fall back to
 * defaults, JSON nulls are treated as missing, non-object array items are skipped.
 *
 * R2 added two optional per-book keys (so the format version stays 1: older builds ignore them, older backups lack
 * them): `readingLog` (`[{day, seconds, pages, chars}]`) and `prefs` (`{txtOverride: {…}, finishedAt,
 * episodeLabel}`), both written only when the book has any.
 */
internal object BackupJson {
    const val FORMAT = "readeraplus-backup"
    const val VERSION = 1

    /** Log rows kept per book on restore (≈ 27 years of daily reading). */
    private const val MAX_LOG_DAYS = 10_000
    private const val MAX_DAY_SECONDS = 24L * 3600

    fun fromBook(
        b: Book,
        metaLocked: Boolean,
        collections: List<String>,
        bookmarks: List<Bookmark>,
        quotes: List<Quote>,
        readingLog: List<BackupLogDay> = emptyList(),
        prefs: BackupPrefs? = null,
    ): BackupBook = BackupBook(
        path = b.path,
        fileName = b.fileName,
        size = b.sizeBytes,
        mtime = b.modifiedAt,
        format = b.format.name,
        title = b.title,
        author = b.author,
        series = b.series,
        seriesIndex = b.seriesIndex,
        metaLocked = metaLocked,
        favorite = b.favorite,
        toRead = b.toRead,
        haveRead = b.haveRead,
        trashed = b.trashed,
        review = b.review,
        encoding = b.encoding,
        language = b.language,
        posSection = b.posSection,
        posOffset = b.posOffset,
        progress = b.progress,
        lastReadAt = b.lastReadAt,
        addedAt = b.addedAt,
        readingSeconds = b.readingSeconds,
        collections = collections,
        bookmarks = bookmarks.map { BackupBookmark(it.section, it.offset, it.snippet, it.note, it.createdAt) },
        quotes = quotes.map { BackupQuote(it.section, it.start, it.end, it.text, it.note, it.createdAt) },
        readingLog = readingLog.sortedBy { it.day },
        prefs = prefs?.takeUnless { it.isEmpty },
    )

    fun toJson(data: BackupData): JSONObject {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", data.version)
        root.put("createdAt", data.createdAt)
        if (data.settings != null) root.put("settings", data.settings)
        root.put("collections", JSONArray().also { a -> data.collections.forEach { a.put(it) } })
        val books = JSONArray()
        for (b in data.books) books.put(bookToJson(b))
        root.put("books", books)
        return root
    }

    fun bookToJson(b: BackupBook): JSONObject {
        val o = JSONObject()
        o.put("path", b.path)
        o.put("fileName", b.fileName)
        o.put("size", b.size)
        o.put("mtime", b.mtime)
        o.put("format", b.format)
        o.put("title", b.title)
        o.put("author", b.author)
        o.put("series", b.series ?: JSONObject.NULL)
        o.put("seriesIndex", finiteOrNull(b.seriesIndex))
        o.put("metaLocked", b.metaLocked)
        o.put("favorite", b.favorite)
        o.put("toRead", b.toRead)
        o.put("haveRead", b.haveRead)
        o.put("trashed", b.trashed)
        o.put("review", b.review)
        o.put("encoding", b.encoding)
        o.put("language", b.language ?: JSONObject.NULL)
        o.put("posSection", b.posSection)
        o.put("posOffset", b.posOffset)
        o.put("progress", if (b.progress.isFinite()) b.progress.toDouble() else 0.0)
        o.put("lastReadAt", b.lastReadAt)
        o.put("addedAt", b.addedAt)
        o.put("readingSeconds", b.readingSeconds)
        o.put("collections", JSONArray().also { a -> b.collections.forEach { a.put(it) } })
        val bms = JSONArray()
        for (m in b.bookmarks) {
            bms.put(
                JSONObject()
                    .put("section", m.section)
                    .put("offset", m.offset)
                    .put("snippet", m.snippet)
                    .put("note", m.note)
                    .put("createdAt", m.createdAt),
            )
        }
        o.put("bookmarks", bms)
        val qs = JSONArray()
        for (q in b.quotes) {
            qs.put(
                JSONObject()
                    .put("section", q.section)
                    .put("start", q.start)
                    .put("end", q.end)
                    .put("text", q.text)
                    .put("note", q.note)
                    .put("createdAt", q.createdAt),
            )
        }
        o.put("quotes", qs)
        if (b.readingLog.isNotEmpty()) {
            val log = JSONArray()
            for (d in b.readingLog) {
                log.put(
                    JSONObject()
                        .put("day", d.day)
                        .put("seconds", d.seconds)
                        .put("pages", d.pages)
                        .put("chars", d.chars),
                )
            }
            o.put("readingLog", log)
        }
        b.prefs?.takeUnless { it.isEmpty }?.let { p ->
            val po = JSONObject()
            p.txtOverride?.let { po.put("txtOverride", JSONObject(it.toJson())) }
            if (p.finishedAt > 0) po.put("finishedAt", p.finishedAt)
            p.episodeLabel?.let { po.put("episodeLabel", it) }
            o.put("prefs", po)
        }
        return o
    }

    /** Parses a backup file; throws [IllegalArgumentException] when it is not a JSON object. */
    fun parse(text: String): BackupData {
        val t = text.removePrefix("﻿").trim()
        val root = try {
            JSONObject(t)
        } catch (e: JSONException) {
            throw IllegalArgumentException("백업 파일을 읽을 수 없습니다 (JSON 형식이 아닙니다)", e)
        }
        if (!root.has("books") && !root.has("settings") && root.optString("format") != FORMAT) {
            throw IllegalArgumentException("리더플러스 백업 파일이 아닙니다")
        }
        val books = ArrayList<BackupBook>()
        root.optJSONArray("books")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                bookFromJson(o)?.let(books::add)
            }
        }
        return BackupData(
            version = int(root, "version", VERSION),
            createdAt = long(root, "createdAt", 0L),
            books = books,
            collections = strings(root.optJSONArray("collections")),
            settings = root.optJSONObject("settings"),
        )
    }

    /** Null when the entry names no file at all. */
    fun bookFromJson(o: JSONObject): BackupBook? {
        val path = str(o, "path", "").trim()
        val fileName = str(o, "fileName", "").trim().ifEmpty { path.substringAfterLast('/') }
        if (path.isEmpty() && fileName.isEmpty()) return null
        val series = strOrNull(o, "series")?.trim()?.ifEmpty { null }
        val bookmarks = ArrayList<BackupBookmark>()
        o.optJSONArray("bookmarks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                bookmarks += BackupBookmark(
                    section = int(m, "section", 0).coerceAtLeast(0),
                    offset = int(m, "offset", 0).coerceAtLeast(0),
                    snippet = capped(m, "snippet", DataLimits.SNIPPET),
                    note = capped(m, "note", DataLimits.NOTE),
                    createdAt = long(m, "createdAt", 0L),
                )
            }
        }
        val quotes = ArrayList<BackupQuote>()
        o.optJSONArray("quotes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val q = arr.optJSONObject(i) ?: continue
                val s = int(q, "start", 0).coerceAtLeast(0)
                val e = int(q, "end", s).coerceAtLeast(0)
                quotes += BackupQuote(
                    section = int(q, "section", 0).coerceAtLeast(0),
                    start = minOf(s, e),
                    end = maxOf(s, e),
                    text = capped(q, "text", DataLimits.QUOTE),
                    note = capped(q, "note", DataLimits.NOTE),
                    createdAt = long(q, "createdAt", 0L),
                )
            }
        }
        val progress = float(o, "progress", 0f)
        return BackupBook(
            path = path,
            fileName = fileName,
            size = long(o, "size", -1L),
            mtime = long(o, "mtime", 0L),
            format = str(o, "format", ""),
            title = str(o, "title", ""),
            author = str(o, "author", ""),
            series = series,
            seriesIndex = if (series == null) null else floatOrNull(o, "seriesIndex"),
            metaLocked = bool(o, "metaLocked", false),
            favorite = bool(o, "favorite", false),
            toRead = bool(o, "toRead", false),
            haveRead = bool(o, "haveRead", false),
            trashed = bool(o, "trashed", false),
            review = capped(o, "review", DataLimits.REVIEW),
            encoding = str(o, "encoding", "").trim(),
            language = strOrNull(o, "language")?.trim()?.ifEmpty { null },
            posSection = int(o, "posSection", 0).coerceAtLeast(0),
            posOffset = int(o, "posOffset", 0).coerceAtLeast(0),
            progress = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f),
            lastReadAt = long(o, "lastReadAt", 0L).coerceAtLeast(0L),
            addedAt = long(o, "addedAt", 0L).coerceAtLeast(0L),
            readingSeconds = long(o, "readingSeconds", 0L).coerceAtLeast(0L),
            collections = strings(o.optJSONArray("collections")),
            bookmarks = bookmarks,
            quotes = quotes,
            readingLog = logFromJson(o.optJSONArray("readingLog")),
            prefs = o.optJSONObject("prefs")?.let(::prefsFromJson),
        )
    }

    /**
     * Log rows of a backup entry: malformed days and empty rows dropped, values clamped (a book can't be read more
     * than 24 h a day), a day listed twice merged (the larger value of each column), ascending, capped.
     */
    fun logFromJson(arr: JSONArray?): List<BackupLogDay> {
        if (arr == null || arr.length() == 0) return emptyList()
        val byDay = java.util.TreeMap<Int, BackupLogDay>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val day = int(o, "day", 0)
            if (!ReadingLog.isDay(day)) continue
            val d = BackupLogDay(
                day = day,
                seconds = long(o, "seconds", 0L).coerceIn(0L, MAX_DAY_SECONDS),
                pages = int(o, "pages", 0).coerceAtLeast(0),
                chars = long(o, "chars", 0L).coerceAtLeast(0L),
            )
            if (d.seconds == 0L && d.pages == 0 && d.chars == 0L) continue
            val prev = byDay[day]
            byDay[day] = if (prev == null) {
                d
            } else {
                BackupLogDay(day, maxOf(prev.seconds, d.seconds), maxOf(prev.pages, d.pages), maxOf(prev.chars, d.chars))
            }
        }
        val out = ArrayList(byDay.values)
        return if (out.size > MAX_LOG_DAYS) out.subList(out.size - MAX_LOG_DAYS, out.size).toList() else out
    }

    /** A backup entry's prefs; null when nothing usable is in it. `txtOverride` may be an object or its JSON text. */
    fun prefsFromJson(o: JSONObject): BackupPrefs? {
        val override = when (val v = o.opt("txtOverride")) {
            is JSONObject -> v.toString()
            is String -> v
            else -> null
        }?.takeIf { it.length <= BookPrefs.MAX_OVERRIDE_CHARS }?.let(TxtOverride::fromJson)
        val p = BackupPrefs(
            txtOverride = override,
            finishedAt = long(o, "finishedAt", 0L).coerceAtLeast(0L),
            episodeLabel = strOrNull(o, "episodeLabel")?.let { MetaInfo.clean(it, BookPrefs.MAX_EPISODE_LABEL) }
                ?.ifEmpty { null },
        )
        return if (p.isEmpty) null else p
    }

    /**
     * The `book_prefs` row after restoring a backup entry over [current] (pure; null = no row now): the backup's
     * override and episode label win when it has them; the finish time is the backup's when it has one, but always
     * 0 when the restored book isn't [haveRead] (a finish time belongs to a finished book). Null when nothing is left.
     */
    fun mergePrefs(current: PrefsRow?, backup: BackupPrefs?, haveRead: Boolean): PrefsRow? {
        val merged = PrefsRow(
            txtOverride = backup?.txtOverride?.let(BookPrefs::overrideJson) ?: current?.txtOverride,
            finishedAt = when {
                !haveRead -> 0L
                backup != null && backup.finishedAt > 0 -> backup.finishedAt
                else -> current?.finishedAt ?: 0L
            },
            episodeLabel = backup?.episodeLabel ?: current?.episodeLabel,
        )
        return if (merged.isEmpty) null else merged
    }

    // ---- tolerant field access (JSON null = missing) ----

    fun strOrNull(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> null
        }
    }

    fun str(o: JSONObject, key: String, def: String): String = strOrNull(o, key) ?: def

    /**
     * A text field cut to the library's cap: an oversized row (hand-edited / corrupt backup) would otherwise
     * overflow the 2 MB CursorWindow and make every later read of that book's quotes or bookmarks throw.
     */
    fun capped(o: JSONObject, key: String, max: Int): String = MetaInfo.truncate(str(o, key, ""), max)

    fun long(o: JSONObject, key: String, def: Long): Long {
        if (!o.has(key) || o.isNull(key)) return def
        return when (val v = o.opt(key)) {
            is Int -> v.toLong()
            is Long -> v
            is Number -> v.toDouble().let { if (it.isFinite()) it.toLong() else def }
            is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong() ?: def
            else -> def
        }
    }

    fun int(o: JSONObject, key: String, def: Int): Int {
        val l = long(o, key, Long.MIN_VALUE)
        if (l == Long.MIN_VALUE) return def
        return l.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    fun floatOrNull(o: JSONObject, key: String): Float? {
        if (!o.has(key) || o.isNull(key)) return null
        val d = when (val v = o.opt(key)) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        val f = d.toFloat()
        return if (f.isFinite()) f else null
    }

    fun float(o: JSONObject, key: String, def: Float): Float = floatOrNull(o, key) ?: def

    fun bool(o: JSONObject, key: String, def: Boolean): Boolean {
        if (!o.has(key) || o.isNull(key)) return def
        return when (val v = o.opt(key)) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> when (v.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no", "" -> false
                else -> def
            }
            else -> def
        }
    }

    /** String items of an array (non-strings skipped, blanks dropped). */
    fun strings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            if (arr.isNull(i)) continue
            val v = arr.opt(i)
            val s = when (v) {
                is String -> v
                is Number -> v.toString()
                else -> null
            } ?: continue
            if (s.isNotBlank()) out += s
        }
        return out
    }

    private fun finiteOrNull(f: Float?): Any = if (f != null && f.isFinite()) f.toDouble() else JSONObject.NULL
}
