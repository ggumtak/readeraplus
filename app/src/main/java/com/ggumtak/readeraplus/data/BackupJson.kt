package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class BackupBookmark(
    val section: Int,
    val offset: Int,
    val snippet: String,
    val note: String,
    val createdAt: Long,
    /** v3 place (N §5.6): [frac] < 0 = unknown, then [chapter] and [sig] are empty too. */
    val chapter: String = "",
    val frac: Float = -1f,
    val sig: String = "",
)

internal data class BackupQuote(
    val section: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val note: String,
    val createdAt: Long,
    /** v2 highlight look, 0..[DataLimits.QUOTE_STYLE_MAX]. */
    val style: Int = 0,
    val chapter: String = "",
    val frac: Float = -1f,
    val sig: String = "",
)

/** One dictionary lookup (N §5.4 / §5.6); its dedupe key on restore is (word key, section, start, createdAt). */
internal data class BackupLookup(
    val word: String,
    val section: Int = 0,
    val start: Int = 0,
    val end: Int = 0,
    val context: String = "",
    val chapter: String = "",
    val frac: Float = -1f,
    val sig: String = "",
    val via: Int = 0,
    val app: String = "",
    val note: String = "",
    val createdAt: Long = 0,
)

/** Who wrote a backup (S §3.6); every field optional. */
internal data class BackupOrigin(val installId: String, val auto: Boolean, val app: String, val device: String)

/** One day of a book's reading log (T1-6); [day] = local yyyymmdd. */
internal data class BackupLogDay(val day: Int, val seconds: Long, val pages: Int, val chars: Long)

/**
 * A book's `book_prefs` row (T1-9 / T1-2 / T2-13; v3 [returnMark], U §3.3: the pinned return point as
 * `ReturnMarkCodec` text). [finishedAt] 0 = not finished.
 */
internal data class BackupPrefs(
    val txtOverride: TxtOverride? = null,
    val finishedAt: Long = 0,
    val episodeLabel: String? = null,
    val returnMark: String? = null,
) {
    val isEmpty: Boolean get() = txtOverride == null && finishedAt <= 0 && episodeLabel == null && returnMark == null
}

/** A `book_prefs` row as stored ([txtOverride] = the column's JSON text); see [BackupJson.mergePrefs]. */
internal data class PrefsRow(
    val txtOverride: String?,
    val finishedAt: Long,
    val episodeLabel: String?,
    val returnMark: String? = null,
) {
    val isEmpty: Boolean get() = txtOverride == null && finishedAt <= 0 && episodeLabel == null && returnMark == null
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
    /** v3 (N §5.6): when [review] was last written (0 = unknown, e.g. an older backup). */
    val reviewAt: Long = 0,
    /** v3: > 0 when the scanner trashed the book because its file vanished. */
    val missingAt: Long = 0,
    /** v3: the book's dictionary lookups (단어장). */
    val lookups: List<BackupLookup> = emptyList(),
)

internal class BackupData(
    val version: Int,
    val createdAt: Long,
    val books: List<BackupBook>,
    val collections: List<String>,
    /** `{reader:{…}, app:{…}, other:{…}, otherTypes:{…}}` or null when absent. */
    val settings: JSONObject?,
    /**
     * The TXT parse the books' (section, offset) positions belong to: [TxtDocuments.PARSE_VERSION] of the build that
     * wrote the file, 0 = unknown (a backup from before R2). See [BackupJson.remapsTextPosition].
     */
    val txtParseVersion: Int = 0,
    /** S §3.6: who wrote the file; null in older backups. */
    val origin: BackupOrigin? = null,
    /** S §3.6: the counts of [books] as written; null in older backups (then [BackupJson.summaryOf] counts them). */
    val summary: AutoBackup.Summary? = null,
)

/** The fields a header read returns (S §3.4) without building the books. */
internal class BackupHeader(
    val version: Int,
    val createdAt: Long,
    val origin: BackupOrigin?,
    val summary: AutoBackup.Summary,
)

/**
 * Backup file ⇄ model mapping (org.json; pure). Reading is tolerant: missing / wrong-typed fields fall back to
 * defaults, JSON nulls are treated as missing, non-object array items are skipped.
 *
 * R2 added two optional per-book keys (so the format version stays 1: older builds ignore them, older backups lack
 * them): `readingLog` (`[{day, seconds, pages, chars}]`) and `prefs` (`{txtOverride: {…}, finishedAt,
 * episodeLabel}`), both written only when the book has any; and the top-level `txtParseVersion`.
 *
 * R3 (still version 1; every key optional both ways, written only when it holds something): the header `origin` and
 * `summary`, written right after `createdAt` so [readHeader] stops before `settings` and `books` (S §3.6, C10); per
 * quote `style` (≠ 0) and the place `chapter, frac, sig` (when `frac ≥ 0`), per bookmark the place; per book
 * `reviewAt`, `missingAt`, `lookups`, and `prefs.returnMark` (N §5.6, U §3.3).
 */
internal object BackupJson {
    const val FORMAT = "readeraplus-backup"
    const val VERSION = 1

    /** Log rows kept per book on restore (≈ 27 years of daily reading). */
    private const val MAX_LOG_DAYS = 10_000

    /** Signature of a restored position's parse record: no parse has it (theirs are hex). */
    private const val STALE_TEXT_SIGNATURE = "restored"

    /** Caps of fields without a [DataLimits] entry (a place signature is "e:<size>" / "<hex>:<size>"). */
    private const val MAX_SIG = 100
    private const val MAX_RETURN_MARK = 1_000
    private const val MAX_ORIGIN_FIELD = 200
    /** Lookups kept per book on restore. */
    private const val MAX_LOOKUPS = 50_000

    fun fromBook(
        b: Book,
        metaLocked: Boolean,
        collections: List<String>,
        bookmarks: List<Bookmark>,
        quotes: List<Quote>,
        readingLog: List<BackupLogDay> = emptyList(),
        prefs: BackupPrefs? = null,
        reviewAt: Long = 0,
        lookups: List<Lookup> = emptyList(),
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
        bookmarks = bookmarks.map {
            val known = it.frac >= 0f
            BackupBookmark(it.section, it.offset, it.snippet, it.note, it.createdAt,
                if (known) it.chapter else "", if (known) it.frac else -1f, if (known) it.sig else "")
        },
        quotes = quotes.map {
            val known = it.frac >= 0f
            BackupQuote(it.section, it.start, it.end, it.text, it.note, it.createdAt, it.style,
                if (known) it.chapter else "", if (known) it.frac else -1f, if (known) it.sig else "")
        },
        readingLog = readingLog.sortedBy { it.day },
        prefs = prefs?.takeUnless { it.isEmpty },
        reviewAt = reviewAt.coerceAtLeast(0L),
        missingAt = b.missingAt.coerceAtLeast(0L),
        lookups = lookups.map {
            BackupLookup(it.word, it.section, it.start, it.end, it.context, it.chapter, it.frac, it.sig, it.via, it.app,
                it.note, it.createdAt)
        },
    )

    /** The header counts of [books] (S §3.6): "read" = opened at least once. */
    fun summaryOf(books: List<BackupBook>): AutoBackup.Summary {
        var read = 0
        var bookmarks = 0
        var quotes = 0
        for (b in books) {
            if (b.lastReadAt > 0) read++
            bookmarks += b.bookmarks.size
            quotes += b.quotes.size
        }
        return AutoBackup.Summary(books.size, read, bookmarks, quotes)
    }

    fun toJson(data: BackupData): JSONObject {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", data.version)
        root.put("createdAt", data.createdAt)
        data.origin?.let { root.put("origin", originToJson(it)) }
        data.summary?.let { root.put("summary", summaryToJson(it)) }
        if (data.txtParseVersion > 0) root.put("txtParseVersion", data.txtParseVersion)
        if (data.settings != null) root.put("settings", data.settings)
        root.put("collections", JSONArray().also { a -> data.collections.forEach { a.put(it) } })
        val books = JSONArray()
        for (b in data.books) books.put(bookToJson(b))
        root.put("books", books)
        return root
    }

    /**
     * Streams [data] to [out] book by book: the same content as `toJson(data)`, with the header keys written in a fixed
     * order (format, version, createdAt, origin, summary, …, books last) that [readHeader] relies on, whatever key order
     * the org.json build keeps; without ever holding the whole tree or the whole text (K11: a 1,000-book snapshot is a
     * 10–20 MB tree). [checkpoint] runs after the header and after every book; it may throw to abort (the busy check).
     */
    fun write(data: BackupData, out: java.io.Writer, checkpoint: () -> Unit = {}) {
        var first = true
        fun field(name: String, value: Any) {
            val one = JSONObject().put(name, value).toString() // {"name":value}: a single key, so its form is fixed
            out.write(if (first) "{" else ",")
            out.write(one, 1, one.length - 2)
            first = false
        }
        field("format", FORMAT)
        field("version", data.version)
        field("createdAt", data.createdAt)
        data.origin?.let { field("origin", originToJson(it)) }
        data.summary?.let { field("summary", summaryToJson(it)) }
        if (data.txtParseVersion > 0) field("txtParseVersion", data.txtParseVersion)
        if (data.settings != null) field("settings", data.settings)
        field("collections", JSONArray().also { a -> data.collections.forEach { a.put(it) } })
        out.write(",\"books\":[")
        checkpoint()
        for ((i, b) in data.books.withIndex()) {
            if (i > 0) out.write(",")
            out.write(bookToJson(b).toString())
            checkpoint()
        }
        out.write("]}")
        out.flush()
    }

    fun originToJson(o: BackupOrigin): JSONObject = JSONObject()
        .put("installId", o.installId)
        .put("auto", o.auto)
        .put("app", o.app)
        .put("device", o.device)

    fun summaryToJson(s: AutoBackup.Summary): JSONObject = JSONObject()
        .put("books", s.books)
        .put("read", s.read)
        .put("bookmarks", s.bookmarks)
        .put("quotes", s.quotes)

    fun originFromJson(o: JSONObject?): BackupOrigin? {
        if (o == null) return null
        return BackupOrigin(
            installId = MetaInfo.truncate(str(o, "installId", "").trim(), MAX_ORIGIN_FIELD),
            auto = bool(o, "auto", false),
            app = MetaInfo.truncate(str(o, "app", "").trim(), MAX_ORIGIN_FIELD),
            device = MetaInfo.truncate(str(o, "device", "").trim(), MAX_ORIGIN_FIELD),
        )
    }

    fun summaryFromJson(o: JSONObject?): AutoBackup.Summary? {
        if (o == null) return null
        return AutoBackup.Summary(
            books = int(o, "books", 0).coerceAtLeast(0),
            read = int(o, "read", 0).coerceAtLeast(0),
            bookmarks = int(o, "bookmarks", 0).coerceAtLeast(0),
            quotes = int(o, "quotes", 0).coerceAtLeast(0),
        )
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
                    .put("createdAt", m.createdAt)
                    .also { putPlace(it, m.chapter, m.frac, m.sig) },
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
                    .put("createdAt", q.createdAt)
                    .also {
                        if (q.style != 0) it.put("style", q.style)
                        putPlace(it, q.chapter, q.frac, q.sig)
                    },
            )
        }
        o.put("quotes", qs)
        if (b.reviewAt > 0) o.put("reviewAt", b.reviewAt)
        if (b.missingAt > 0) o.put("missingAt", b.missingAt)
        if (b.lookups.isNotEmpty()) {
            val ls = JSONArray()
            for (l in b.lookups) {
                ls.put(
                    JSONObject()
                        .put("word", l.word)
                        .put("section", l.section)
                        .put("start", l.start)
                        .put("end", l.end)
                        .put("context", l.context)
                        .put("chapter", l.chapter)
                        .put("frac", finiteOr(l.frac, -1f))
                        .put("sig", l.sig)
                        .put("via", l.via)
                        .put("app", l.app)
                        .put("note", l.note)
                        .put("createdAt", l.createdAt),
                )
            }
            o.put("lookups", ls)
        }
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
            p.returnMark?.let { po.put("returnMark", it) }
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
            txtParseVersion = int(root, "txtParseVersion", 0).coerceAtLeast(0),
            origin = originFromJson(root.optJSONObject("origin")),
            summary = summaryFromJson(root.optJSONObject("summary")),
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
                ).withPlace(placeFromJson(m))
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
                    style = int(q, "style", 0).coerceIn(0, DataLimits.QUOTE_STYLE_MAX),
                ).withPlace(placeFromJson(q))
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
            reviewAt = long(o, "reviewAt", 0L).coerceAtLeast(0L),
            missingAt = long(o, "missingAt", 0L).coerceAtLeast(0L),
            lookups = lookupsFromJson(o.optJSONArray("lookups")),
        )
    }

    /** A note's place: (chapter, frac, sig) when `frac` is a fraction 0..1, else [NotePlace.UNKNOWN]. */
    fun placeFromJson(o: JSONObject): NotePlace {
        val frac = floatOrNull(o, "frac") ?: return NotePlace.UNKNOWN
        if (frac < 0f) return NotePlace.UNKNOWN
        return NotePlace(
            chapter = MetaInfo.clean(str(o, "chapter", ""), DataLimits.CHAPTER),
            frac = frac.coerceAtMost(1f),
            sig = MetaInfo.truncate(str(o, "sig", "").trim(), MAX_SIG),
        )
    }

    private fun BackupBookmark.withPlace(p: NotePlace): BackupBookmark =
        if (p.frac < 0f) this else copy(chapter = p.chapter, frac = p.frac, sig = p.sig)

    private fun BackupQuote.withPlace(p: NotePlace): BackupQuote =
        if (p.frac < 0f) this else copy(chapter = p.chapter, frac = p.frac, sig = p.sig)

    private fun putPlace(o: JSONObject, chapter: String, frac: Float, sig: String) {
        if (!(frac >= 0f) || !frac.isFinite()) return
        o.put("chapter", chapter).put("frac", frac.toDouble()).put("sig", sig)
    }

    /** A backup entry's lookups: entries without a word skipped, text fields capped, offsets ordered, capped count. */
    fun lookupsFromJson(arr: JSONArray?): List<BackupLookup> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<BackupLookup>(minOf(arr.length(), MAX_LOOKUPS))
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_LOOKUPS) break
            val o = arr.optJSONObject(i) ?: continue
            val word = MetaInfo.clean(str(o, "word", ""), DataLimits.WORD)
            if (word.isEmpty()) continue
            val s = int(o, "start", 0).coerceAtLeast(0)
            val e = int(o, "end", s).coerceAtLeast(0)
            val place = placeFromJson(o)
            out += BackupLookup(
                word = word,
                section = int(o, "section", 0).coerceAtLeast(0),
                start = minOf(s, e),
                end = maxOf(s, e),
                context = capped(o, "context", DataLimits.CONTEXT),
                chapter = place.chapter,
                frac = place.frac,
                sig = place.sig,
                via = int(o, "via", 0).coerceAtLeast(0),
                app = MetaInfo.truncate(str(o, "app", "").trim(), DataLimits.APP),
                note = capped(o, "note", DataLimits.NOTE),
                createdAt = long(o, "createdAt", 0L).coerceAtLeast(0L),
            )
        }
        return out
    }

    /**
     * The header of a backup file (S §3.4): `version`, `createdAt`, `origin` and `summary`, read with a streaming
     * reader — `settings` and the rest are skipped, no tree is built. The read stops at `books` once `summary` is
     * known (R3 files write it before `books`); an older file without one is counted while its `books` stream past.
     * Throws on a malformed file or one that is not a ReaderaPlus backup.
     */
    fun readHeader(input: java.io.Reader): BackupHeader {
        val pr = java.io.PushbackReader(input, 1)
        val first = pr.read()
        if (first != -1 && first != 0xFEFF) pr.unread(first)
        val r = android.util.JsonReader(pr)
        r.isLenient = true
        var version = VERSION
        var createdAt = 0L
        var origin: BackupOrigin? = null
        var summary: AutoBackup.Summary? = null
        var counted: AutoBackup.Summary? = null
        var format: String? = null
        var sawBooks = false
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> format = scalar(r)
                "version" -> version = scalar(r)?.trim()?.toDoubleOrNull()?.toInt() ?: VERSION
                "createdAt" -> createdAt = scalar(r)?.trim()?.toDoubleOrNull()?.toLong() ?: 0L
                "origin" -> origin = originFromJson(smallObject(r))
                "summary" -> summary = summaryFromJson(smallObject(r))
                "books" -> {
                    sawBooks = true
                    if (summary != null) break
                    counted = countBooks(r)
                }
                else -> r.skipValue()
            }
        }
        if (format != FORMAT && !sawBooks) throw IllegalArgumentException("리더플러스 백업 파일이 아닙니다")
        return BackupHeader(version, createdAt, origin, summary ?: counted ?: AutoBackup.Summary(0, 0, 0, 0))
    }

    /** A string / number / boolean value as text; null (skipped) for anything else. */
    private fun scalar(r: android.util.JsonReader): String? = when (r.peek()) {
        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> r.nextString()
        android.util.JsonToken.BOOLEAN -> r.nextBoolean().toString()
        else -> { r.skipValue(); null }
    }

    /** A flat object of scalars (origin, summary) as a JSONObject; null when it is not an object. */
    private fun smallObject(r: android.util.JsonReader): JSONObject? {
        if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        val o = JSONObject()
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            scalar(r)?.let { o.put(k, it) }
        }
        r.endObject()
        return o
    }

    /** Counts a `books` array like [summaryOf] would, without building the books. */
    private fun countBooks(r: android.util.JsonReader): AutoBackup.Summary {
        if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) { r.skipValue(); return AutoBackup.Summary(0, 0, 0, 0) }
        var books = 0
        var read = 0
        var bookmarks = 0
        var quotes = 0
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var path = ""
            var fileName = ""
            var lastRead = 0L
            var bms = 0
            var qs = 0
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "path" -> path = scalar(r)?.trim() ?: ""
                    "fileName" -> fileName = scalar(r)?.trim() ?: ""
                    "lastReadAt" -> lastRead = scalar(r)?.trim()?.toDoubleOrNull()?.toLong() ?: 0L
                    "bookmarks" -> bms = countObjects(r)
                    "quotes" -> qs = countObjects(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (path.isEmpty() && fileName.isEmpty()) continue // bookFromJson drops these too
            books++
            if (lastRead > 0) read++
            bookmarks += bms
            quotes += qs
        }
        r.endArray()
        return AutoBackup.Summary(books, read, bookmarks, quotes)
    }

    private fun countObjects(r: android.util.JsonReader): Int {
        if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) { r.skipValue(); return 0 }
        var n = 0
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() == android.util.JsonToken.BEGIN_OBJECT) n++
            r.skipValue()
        }
        r.endArray()
        return n
    }

    /**
     * Log rows of a backup entry: malformed days and empty rows dropped, values clamped to what one ReadingLog.add may
     * write (a book can't be read more than 24 h a day; a huge count would overflow SQLite's SUM in every log read),
     * a day listed twice merged (the larger value of each column), ascending, capped.
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
                seconds = long(o, "seconds", 0L).coerceIn(0L, ReadingLog.MAX_ADD_SECONDS),
                pages = int(o, "pages", 0).coerceIn(0, ReadingLog.MAX_ADD_PAGES),
                chars = long(o, "chars", 0L).coerceIn(0L, ReadingLog.MAX_ADD_CHARS),
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

    /**
     * True when [b]'s restored position must be found again by its fraction on the next open (the restore then
     * writes [staleTextPosition] for it): a TXT position saved under another parse version than this build's
     * ([backupParseVersion] 0 = a backup from before R2) names a place in another section split. The very start is
     * the same place in any parse; EPUB positions (spine items) never move.
     */
    fun remapsTextPosition(backupParseVersion: Int, b: BackupBook): Boolean {
        if (backupParseVersion == TxtDocuments.PARSE_VERSION || (b.posSection <= 0 && b.posOffset <= 0)) return false
        val format = BookFormat.entries.firstOrNull { it.name == b.format } ?: BookFormat.forFile(b.fileName)
        return format == BookFormat.TXT
    }

    /**
     * The reader's parse record (`TextPositions.encode`: "signature|fraction") for a [remapsTextPosition] book: its
     * signature matches no parse, so `TextPositions.remapFraction` reopens at [progress] (the restored library
     * progress) instead of at the stale (section, offset).
     */
    fun staleTextPosition(progress: Float): String =
        STALE_TEXT_SIGNATURE + "|" + (if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f))

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
            returnMark = strOrNull(o, "returnMark")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_RETURN_MARK },
        )
        return if (p.isEmpty) null else p
    }

    /**
     * The `book_prefs` row after restoring a backup entry over [current] (pure; null = no row now): the backup's
     * override and episode label win when it has them; the finish time is the backup's when it has one, but always
     * 0 when the restored book isn't [haveRead] (a finish time belongs to a finished book). The return mark (U §3.3)
     * belongs to a reading position: the backup's wins unless [deviceNewer] (this device read the book later than the
     * backup) and the device has one of its own. Null when nothing is left.
     */
    fun mergePrefs(current: PrefsRow?, backup: BackupPrefs?, haveRead: Boolean, deviceNewer: Boolean = false): PrefsRow? {
        val merged = PrefsRow(
            txtOverride = backup?.txtOverride?.let(BookPrefs::overrideJson) ?: current?.txtOverride,
            finishedAt = when {
                !haveRead -> 0L
                backup != null && backup.finishedAt > 0 -> backup.finishedAt
                else -> current?.finishedAt ?: 0L
            },
            episodeLabel = backup?.episodeLabel ?: current?.episodeLabel,
            returnMark = if (deviceNewer && current?.returnMark != null) current.returnMark
            else backup?.returnMark ?: current?.returnMark,
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

    private fun finiteOr(f: Float, def: Float): Double = (if (f.isFinite()) f else def).toDouble()

    private fun finiteOrNull(f: Float?): Any = if (f != null && f.isFinite()) f.toDouble() else JSONObject.NULL
}
