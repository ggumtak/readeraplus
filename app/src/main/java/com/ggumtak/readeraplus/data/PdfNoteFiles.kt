package com.ggumtak.readeraplus.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The PDF viewer's per-book notes files, as the rest of the app (library removal, backup) sees them: ink strokes and
 * bookmarks live in `filesDir/pdf_notes/<bookId>.json`, written by `PdfNotesStore` (reader/pdf; same path rule, an
 * empty notes object deletes the file). Everything here is blocking file IO (IO thread) and never throws.
 *
 * The functions come in two forms: with a [Context] (the app's files dir) and with an explicit `dir` (unit tests).
 */
internal object PdfNoteFiles {
    const val DIR = "pdf_notes"

    /** One book's notes text carried by a backup, in chars: a larger file stays out of the backup (it stays on the device). */
    const val MAX_BACKUP_BOOK_CHARS = 8_000_000

    /**
     * All notes texts of one backup file together, in chars: the viewer writes ASCII (about 1 byte a char), and even
     * 3 bytes a char stays below the restore's 64 MB guard ([Backup.MAX_BYTES]).
     */
    const val MAX_BACKUP_TOTAL_CHARS = 20_000_000

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun file(dir: File, bookId: Long): File = File(dir, "$bookId.json")

    fun file(context: Context, bookId: Long): File = file(dir(context), bookId)

    fun exists(context: Context, bookId: Long): Boolean = exists(dir(context), bookId)

    fun exists(dir: File, bookId: Long): Boolean = try {
        file(dir, bookId).isFile
    } catch (e: Exception) {
        false
    }

    /** The ids of the books with a notes file (one directory listing; empty on any failure). */
    fun ids(context: Context): Set<Long> = ids(dir(context))

    fun ids(dir: File): Set<Long> = try {
        val names = dir.list() ?: emptyArray()
        names.mapNotNullTo(HashSet()) { n -> if (n.endsWith(".json")) n.substring(0, n.length - 5).toLongOrNull() else null }
    } catch (e: Exception) {
        emptySet()
    }

    /** Deletes the book's notes file (best effort; a missing file is fine). */
    fun delete(context: Context, bookId: Long) = delete(dir(context), bookId)

    fun delete(dir: File, bookId: Long) {
        try {
            val f = file(dir, bookId)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            // best effort
        }
    }

    /** The notes text; null when there is no file, it is blank, unreadable or longer than [maxChars]. */
    fun readText(context: Context, bookId: Long, maxChars: Int = Int.MAX_VALUE): String? =
        readText(dir(context), bookId, maxChars)

    fun readText(dir: File, bookId: Long, maxChars: Int = Int.MAX_VALUE): String? = try {
        val f = file(dir, bookId)
        // Bytes >= chars: a file longer than the cap in bytes is skipped before it is read into memory.
        if (!f.isFile || f.length() > maxChars) {
            null
        } else {
            f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() && it.length <= maxChars }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Writes [text] as the book's notes file atomically (temp file + rename, creating the folder). The temp name
     * differs from the viewer's own (`<id>.json.tmp`), so a write of the viewer never meets this one's file.
     * Returns false on any IO failure.
     */
    fun writeText(context: Context, bookId: Long, text: String): Boolean = writeText(dir(context), bookId, text)

    fun writeText(dir: File, bookId: Long, text: String): Boolean {
        val tmp = File(dir, "$bookId.json.restore.tmp")
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            val target = file(dir, bookId)
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(target)) {
                true
            } else {
                target.delete()
                val ok = tmp.renameTo(target)
                if (!ok) tmp.delete()
                ok
            }
        } catch (e: Exception) {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
            false
        }
    }

    /**
     * Pure: whether [text] is a notes object holding something — at least one bookmark or one stroke (the shape
     * `PdfNotes.toJson` writes: `{"v":1,"bookmarks":[…],"pages":{"<page>":[strokes]}}`). Not JSON, or an empty
     * object, is nothing: the viewer reads such a file as no notes at all. Scans instead of parsing (a notes file can
     * hold MBs of numbers) and stops at the first bookmark or stroke.
     */
    fun hasContent(text: String?): Boolean {
        if (text == null) return false
        val t: String = text
        var i = ws(t, 0)
        if (i >= t.length || t[i] != '{') return false
        i++
        while (true) {
            i = ws(t, i)
            if (i >= t.length) return false
            when (t[i]) {
                '}' -> return false
                ',' -> {
                    i++
                    continue
                }
                '"' -> {}
                else -> return false
            }
            val keyEnd = stringEnd(t, i)
            if (keyEnd < 0) return false
            val marks = keyIs(t, i, keyEnd, "bookmarks")
            val pages = !marks && keyIs(t, i, keyEnd, "pages")
            i = ws(t, keyEnd + 1)
            if (i >= t.length || t[i] != ':') return false
            i = ws(t, i + 1)
            if (i >= t.length) return false
            if (marks && t[i] == '[') {
                val j = ws(t, i + 1)
                if (j < t.length && t[j] in '0'..'9') return true
            } else if (pages && t[i] == '{' && anyStroke(t, i)) {
                return true
            }
            i = skipValue(t, i)
            if (i < 0) return false
        }
    }

    /**
     * Pure: what a restore writes for the backup's notes [backup] where the device's file holds [local] (null = none);
     * null when it writes nothing. A restore never deletes: the backup fills in only where the device has nothing — the
     * whole file when the device has no notes (a corrupt or empty file included: the viewer reads those as none), else
     * the pages without ink here, plus the backup's bookmarks. A page with ink on both keeps the device's strokes (the
     * newer ones in the cases that matter: same device, or a device the user kept drawing on). Parses both only in
     * that last case.
     */
    fun merged(local: String?, backup: String?): String? {
        if (!hasContent(backup)) return null
        if (!hasContent(local)) return backup
        val dev = try {
            JSONObject(local!!)
        } catch (e: Exception) {
            return backup
        }
        val bak = try {
            JSONObject(backup!!)
        } catch (e: Exception) {
            return null
        }
        var added = false
        val marks = java.util.TreeSet<Int>()
        fun addMarks(o: JSONObject, from: Boolean) {
            val a = o.optJSONArray("bookmarks") ?: return
            for (k in 0 until a.length()) {
                val n = a.opt(k) as? Number ?: continue
                val p = n.toInt()
                if (p >= 0 && n.toDouble() == p.toDouble() && marks.add(p) && from) added = true
            }
        }
        addMarks(dev, false)
        addMarks(bak, true)
        val pages = java.util.TreeMap<Int, JSONArray>()
        fun addPages(o: JSONObject, from: Boolean) {
            val ps = o.optJSONObject("pages") ?: return
            val keys = ps.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val page = key.toIntOrNull() ?: continue
                val strokes = ps.optJSONArray(key) ?: continue
                if (page < 0 || strokes.length() == 0 || pages.containsKey(page)) continue
                pages[page] = strokes
                if (from) added = true
            }
        }
        addPages(dev, false)
        addPages(bak, true)
        if (!added) return null
        val sb = StringBuilder((local?.length ?: 0) + (backup?.length ?: 0) + 64)
        sb.append("{\"v\":1,\"bookmarks\":[")
        marks.forEachIndexed { k, p -> if (k > 0) sb.append(','); sb.append(p) }
        sb.append("],\"pages\":{")
        var first = true
        for ((page, strokes) in pages) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(page).append("\":").append(strokes.toString())
        }
        sb.append("}}")
        return sb.toString()
    }

    /**
     * Restores the backup's notes [text] of the book whose id on this device is [bookId] ([merged]); returns whether
     * the file was written.
     */
    fun restore(context: Context, bookId: Long, text: String): Boolean = restore(dir(context), bookId, text)

    fun restore(dir: File, bookId: Long, text: String): Boolean {
        if (!hasContent(text)) return false
        val out = merged(readText(dir, bookId), text) ?: return false
        return writeText(dir, bookId, out)
    }

    private fun ws(t: String, from: Int): Int {
        var i = from
        while (i < t.length && (t[i] == ' ' || t[i] == '\n' || t[i] == '\r' || t[i] == '\t')) i++
        return i
    }

    /** The index of the quote closing the string that opens at [from], or -1. */
    private fun stringEnd(t: String, from: Int): Int {
        var i = from + 1
        while (i < t.length) {
            when (t[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun keyIs(t: String, open: Int, close: Int, name: String): Boolean =
        close - open - 1 == name.length && t.regionMatches(open + 1, name, 0, name.length)

    /** The index just after the JSON value starting at [from], or -1 when it doesn't end. */
    private fun skipValue(t: String, from: Int): Int {
        var i = from
        val c = t[i]
        if (c == '"') return stringEnd(t, i).let { if (it < 0) -1 else it + 1 }
        if (c != '{' && c != '[') {
            while (i < t.length && t[i] != ',' && t[i] != '}' && t[i] != ']' && t[i] > ' ') i++
            return if (i == from) -1 else i
        }
        var depth = 0
        while (i < t.length) {
            when (t[i]) {
                '"' -> {
                    i = stringEnd(t, i)
                    if (i < 0) return -1
                }
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return -1
    }

    /** Whether the "pages" object opening at [from] has a page whose stroke list starts with a stroke. */
    private fun anyStroke(t: String, from: Int): Boolean {
        var i = from + 1
        while (true) {
            i = ws(t, i)
            if (i >= t.length) return false
            when (t[i]) {
                '}' -> return false
                ',' -> {
                    i++
                    continue
                }
                '"' -> {}
                else -> return false
            }
            val keyEnd = stringEnd(t, i)
            if (keyEnd < 0) return false
            i = ws(t, keyEnd + 1)
            if (i >= t.length || t[i] != ':') return false
            i = ws(t, i + 1)
            if (i >= t.length) return false
            if (t[i] == '[') {
                val j = ws(t, i + 1)
                if (j < t.length && t[j] == '{') return true
            }
            i = skipValue(t, i)
            if (i < 0) return false
        }
    }
}
