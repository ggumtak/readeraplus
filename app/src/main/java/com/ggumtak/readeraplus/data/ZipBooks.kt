package com.ggumtak.readeraplus.data

import android.content.Context
import android.util.Log
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.txt.TxtCharsets
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Books inside ZIP files found by the scan (web novels are often shared as a `.zip` of TXT / EPUB files).
 *
 * Each TXT / EPUB entry is copied into the library's copy folder (the same folder as file-picker copies) and added
 * like an imported book; the reading / parsing paths never see a ZIP. A ZIP is handled once per version: its
 * path is recorded with its size and mtime in the private preferences file [PREFS] after all of its entries were
 * processed, and it is opened again only when it changed. The ZIP itself is never modified or deleted.
 *
 * Zip-slip safe by construction: an entry's path is never used as a location. Only its base name, sanitised by
 * [outName], is placed inside the copy folder (and [BookCopies.settle] adds " (2)" … instead of replacing a file).
 */
internal object ZipBooks {
    private const val TAG = "ZipBooks"
    private const val PREFS = "zip_books"

    /** Larger ZIP files are not collected by the scan. */
    const val MAX_ZIP_BYTES = 300L * 1024 * 1024
    /** Entries larger than this (declared or while copying) are skipped. */
    const val MAX_ENTRY_BYTES = 200L * 1024 * 1024
    /** At most this many book entries are taken from one ZIP. */
    const val MAX_ENTRIES = 100
    /** Copying from one ZIP stops once this many bytes were written. */
    const val MAX_TOTAL_BYTES = 500L * 1024 * 1024

    private const val FALLBACK_NAME = "book.txt"
    /** UTF-8 bytes kept of an entry's base name (without extension): room for " (50)" within a 255-byte name. */
    private const val MAX_STEM_BYTES = 180
    private const val BUFFER_BYTES = 128 * 1024

    /** Whether a directory listing name is a ZIP file candidate (not hidden, `*.zip` in any case). */
    fun isZipName(name: String): Boolean =
        name.length > 4 && name[0] != '.' && name.endsWith(".zip", ignoreCase = true)

    /**
     * Whether a ZIP entry name is a book to copy: not a directory, `.txt` / `.epub` (any case), not inside a
     * `__MACOSX` folder and its base name not hidden (AppleDouble `._x.txt`).
     */
    fun isBookEntry(name: String): Boolean {
        if (name.isEmpty() || name.endsWith('/') || name.endsWith('\\')) return false
        val norm = name.replace('\\', '/')
        if (norm.split('/').any { it == "__MACOSX" }) return false
        val base = norm.substringAfterLast('/')
        if (base.isEmpty() || base[0] == '.') return false
        return base.endsWith(".txt", ignoreCase = true) || base.endsWith(".epub", ignoreCase = true)
    }

    /**
     * The file name an entry is copied under: its base name (after the last `/` or `\`) with control characters
     * and `"*:<>?|` replaced by `_`, trimmed, the stem shortened to [MAX_STEM_BYTES] UTF-8 bytes. A name left empty
     * (or hidden) becomes "book.<ext>" for a TXT / EPUB entry, else [FALLBACK_NAME].
     */
    fun outName(entryName: String): String {
        val base = entryName.substring(maxOf(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\')) + 1)
        val sb = StringBuilder(base.length)
        for (c in base) {
            sb.append(if (c < ' ' || c == '\u007f' || c in "\"*:<>?|") '_' else c)
        }
        val clean = sb.toString().trim()
        val dot = clean.lastIndexOf('.')
        val ext = if (dot >= 0) clean.substring(dot + 1) else ""
        val stem = (if (dot >= 0) clean.substring(0, dot) else clean).trim()
        val bookExt = ext.equals("txt", ignoreCase = true) || ext.equals("epub", ignoreCase = true)
        if (stem.isEmpty() || stem[0] == '.' || !bookExt) {
            return if (bookExt) "book.${ext.lowercase()}" else FALLBACK_NAME
        }
        return "${limitUtf8(stem, MAX_STEM_BYTES)}.$ext"
    }

    /** [s] cut to at most [maxBytes] UTF-8 bytes, never inside a surrogate pair. */
    private fun limitUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val pair = Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
            val n = when {
                pair -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + n > maxBytes) break
            bytes += n
            i += if (pair) 2 else 1
        }
        return if (i >= s.length) s else s.substring(0, i).trimEnd()
    }

    /** The recorded value of a ZIP version: its [size] and [mtime] ([path] is the preference key). */
    fun key(path: String, size: Long, mtime: Long): String = "$size:$mtime"

    /**
     * The entries of a ZIP to copy, in order: book entries ([isBookEntry]) whose declared size (unknown = negative)
     * is at most [MAX_ENTRY_BYTES] and, when known, accepted by the scanner's size rule (tiny "readme.txt" files are
     * left out), at most [MAX_ENTRIES] of them.
     */
    fun <T> pick(entries: List<T>, nameOf: (T) -> String, sizeOf: (T) -> Long): List<T> {
        val out = ArrayList<T>()
        for (e in entries) {
            if (out.size >= MAX_ENTRIES) break
            val name = nameOf(e)
            if (!isBookEntry(name)) continue
            val size = sizeOf(e)
            if (size > MAX_ENTRY_BYTES) continue
            if (size >= 0) {
                val format = BookFormat.forFile(name) ?: continue
                if (!DataPaths.acceptSize(format, size)) continue
            }
            out += e
        }
        return out
    }

    /**
     * Copies the books of every ZIP in [zips] that is new or changed since it was last handled and adds them to the
     * library like a scan does (a book the user removed from the library stays removed, a trashed one stays in the
     * trash). Blocking (worker thread). Returns the number of books added (or refreshed). [stopWhen] is checked between
     * ZIPs and entries; a ZIP left half done is tried again by the next scan. A ZIP that cannot be opened at all
     * (malformed, or entry names Android 14 refuses) is remembered as failed for this version and not opened again; any
     * other failure is logged and tried again by the next scan. Temp files are deleted.
     */
    fun extractNew(context: Context, zips: List<File>, stopWhen: () -> Boolean = { false }): Int {
        if (zips.isEmpty()) return 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ignored = runCatching {
            HashSet<String>(Library.db().queryList(LibrarySql.SELECT_IGNORED, null) { it.getString(0) ?: "" })
        }.getOrDefault(HashSet())
        var dir: File? = null
        var added = 0
        for (zip in zips) {
            if (stopWhen()) break
            val path = zip.absolutePath
            val size = zip.length()
            val mtime = zip.lastModified()
            if (size <= 0) continue
            val stamp = key(path, size, mtime)
            val seen = prefs.getString(path, null)
            if (seen == stamp || seen == stamp + FAILED) continue
            try {
                val target = dir ?: (context.getExternalFilesDir("books") ?: File(context.filesDir, "books")).also {
                    if (!it.isDirectory) it.mkdirs()
                    dir = it
                }
                val opened = try {
                    open(zip)
                } catch (e: Exception) {
                    // Not a ZIP we can read in this version (structure or entry names): do not try it on every scan.
                    prefs.edit().putString(path, stamp + FAILED).apply()
                    Log.w(TAG, "zip unreadable: ${zip.name}: $e")
                    continue
                }
                added += extractOne(opened, target, ignored, stopWhen)
                prefs.edit().putString(path, stamp).apply()
            } catch (e: StoppedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "zip skipped: ${zip.name}: $e")
            }
        }
        return added
    }

    private const val FAILED = ":fail"

    /** [stopWhen] said stop in the middle of a ZIP (it is not marked as handled). */
    private class StoppedException : Exception()

    /** An open ZIP and all of its entries; the caller closes [zip]. */
    internal class Opened(val zip: ZipFile, val entries: List<ZipEntry>)

    /**
     * Opens [file] with UTF-8 entry names, else (malformed names: a Korean Windows ZIP) with CP949. Pure java.util.zip
     * (unit-tested); the caller closes [Opened.zip].
     */
    internal fun open(file: File): Opened {
        try {
            return list(ZipFile(file, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
        } catch (e: ZipException) {
        }
        val cp949 = TxtCharsets.cp949Charset ?: throw ZipException("entry names are not UTF-8")
        return list(ZipFile(file, cp949))
    }

    /** All entries, read up front so a malformed name fails before anything is written. */
    private fun list(zip: ZipFile): Opened = try {
        Opened(zip, zip.entries().toList())
    } catch (t: Throwable) {
        zip.close()
        throw t
    }

    private fun extractOne(opened: Opened, dir: File, ignored: Set<String>, stopWhen: () -> Boolean): Int {
        var added = 0
        opened.zip.use { zip ->
            var total = 0L
            for (e in pick(opened.entries, { it.name }, { it.size })) {
                if (stopWhen()) throw StoppedException()
                val remaining = MAX_TOTAL_BYTES - total
                if (remaining <= 0) break
                val cap = minOf(MAX_ENTRY_BYTES, remaining)
                val name = outName(e.name)
                val input = zip.getInputStream(e) ?: continue
                val tmp = File(dir, "${BookCopies.COPY_PREFIX}${System.nanoTime()}${BookCopies.PART_SUFFIX}")
                val placed = try {
                    val written = input.use { copy(it, tmp, cap) }
                    if (written < 0) {
                        tmp.delete()
                        // Over the total budget: stop this ZIP; over the entry limit: next entry.
                        if (cap < MAX_ENTRY_BYTES) break
                        continue
                    }
                    total += written
                    val format = BookFormat.forFile(name)
                    if (format == null || !DataPaths.acceptSize(format, written)) {
                        tmp.delete()
                        continue
                    }
                    // An identical earlier copy (the same ZIP seen before, another ZIP, a picker copy) is reused.
                    BookCopies.settle(tmp, dir, name)
                } catch (t: Throwable) {
                    tmp.delete()
                    throw t
                }
                // Like a scan, not a user's import: a removed (ignored) path stays out, a trashed row stays trashed.
                if (Library.normalizePath(placed.absolutePath) in ignored) continue
                if (Library.addOrUpdate(placed, explicit = false) != null) added++
            }
        }
        return added
    }

    /** Copies [input] to [out]; the byte count, or -1 (copy incomplete) once more than [cap] bytes came. */
    private fun copy(input: InputStream, out: File, cap: Long): Long {
        var total = 0L
        FileOutputStream(out).use { os ->
            val buf = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) return -1
                os.write(buf, 0, n)
            }
        }
        return total
    }
}
