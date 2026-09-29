package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import java.util.Locale

/**
 * Pure path helpers of the data module: canonical library paths, scan-root derivation and the
 * scanner's skip rules. No IO.
 */
internal object DataPaths {

    const val PRIMARY_ROOT = "/storage/emulated/0"

    /** Minimum size of a .txt file picked up by the scanner (tiny files are usually junk / readme). */
    const val MIN_TXT_BYTES = 1024L

    /** Well-known aliases of the primary shared storage. */
    private val PRIMARY_ALIASES = listOf("/sdcard", "/storage/self/primary", "/mnt/sdcard", "/mnt/user/0/primary")

    /**
     * Normalises an absolute path the way the library stores it: `.` / `..` / duplicate `/` resolved,
     * no trailing slash, primary-storage aliases (`/sdcard/…`) mapped to [primaryRoot]. No filesystem access,
     * so symlinks other than the known aliases are kept as given.
     */
    fun normalize(path: String, primaryRoot: String = PRIMARY_ROOT): String {
        if (path.isEmpty()) return path
        val parts = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        var p = if (parts.isEmpty()) "/" else parts.joinToString("/", prefix = "/")
        val root = primaryRoot.trimEnd('/')
        for (alias in PRIMARY_ALIASES) {
            if (p == alias || p.startsWith("$alias/")) {
                p = root + p.substring(alias.length)
                break
            }
        }
        return p
    }

    /** Parent folder of a stored path (same rule as [Book.folder]). */
    fun folderOf(path: String): String = path.substringBeforeLast('/', "")

    /** True when [path] is [dir] itself or lies below it. */
    fun isUnder(path: String, dir: String): Boolean {
        val d = dir.trimEnd('/')
        if (d.isEmpty()) return path.startsWith("/")
        return path == d || (path.length > d.length && path.startsWith(d) && path[d.length] == '/')
    }

    /**
     * Storage volume roots from app-specific dirs (`context.getExternalFilesDirs(null)`):
     * `/storage/1A2B-3C4D/Android/data/<pkg>/files` → `/storage/1A2B-3C4D`. Nulls / unparsable entries skipped.
     */
    fun volumeRootsFromAppDirs(appDirs: List<String?>, primaryRoot: String = PRIMARY_ROOT): List<String> {
        val out = ArrayList<String>()
        for (d in appDirs) {
            if (d.isNullOrEmpty()) continue
            val i = d.indexOf("/Android/")
            if (i <= 0) continue
            val root = normalize(d.substring(0, i), primaryRoot)
            if (root != "/" && root !in out) out += root
        }
        return out
    }

    /** Distinct normalised roots with nested ones removed (a root inside another root is redundant). */
    fun dedupeRoots(roots: Collection<String>, primaryRoot: String = PRIMARY_ROOT): List<String> {
        val norm = roots.asSequence().map { it.trim() }.filter { it.startsWith("/") }.map { normalize(it, primaryRoot) }
            .distinct()
            .sortedBy { it.length }.toList()
        val out = ArrayList<String>()
        for (r in norm) if (out.none { isUnder(r, it) }) out += r
        return out
    }

    /** Book format of a scan candidate by name only (null = not a book). Hidden / AppleDouble files excluded. */
    fun bookFormatOf(name: String): BookFormat? {
        if (name.isEmpty() || name[0] == '.') return null
        return BookFormat.forFile(name)
    }

    /** Whether a found file of [format] and [size] should be added by the scanner. */
    fun acceptSize(format: BookFormat, size: Long): Boolean = when (format) {
        BookFormat.TXT -> size >= MIN_TXT_BYTES
        BookFormat.EPUB -> size > 0
    }

    /**
     * Directory entries the scanner skips without descending: hidden dirs, `Android/data`, `Android/obb`
     * (inaccessible and huge) and `LOST.DIR`.
     */
    fun skipDir(parentPath: String, name: String): Boolean {
        if (name.isEmpty() || name[0] == '.') return true
        if (name == "LOST.DIR") return true
        if ((name == "data" || name == "obb") && parentPath.endsWith("/Android")) return true
        return false
    }

    /**
     * Names that are certainly regular non-book files (by extension), so the scanner can skip them without a
     * stat call — a big saving in photo / music folders on slow e-ink devices.
     */
    fun obviouslyNotBookFile(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot >= name.length - 1 || name.length - dot - 1 > 10) return false
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        return ext in KNOWN_FILE_EXTENSIONS
    }

    private val KNOWN_FILE_EXTENSIONS: Set<String> = hashSetOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff", "dng", "raw", "ico", "svg",
        "psd", "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr", "mid", "midi",
        "mp4", "m4v", "mkv", "avi", "mov", "3gp", "webm", "wmv", "flv", "ts",
        "apk", "apks", "xapk", "obb", "so", "dex", "jar", "odex", "vdex",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "hwpx", "odt", "rtf", "csv",
        "zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz",
        "json", "xml", "html", "htm", "js", "css", "db", "sqlite", "db-wal", "db-shm", "log", "tmp", "dat",
        "bin", "cache", "lrc", "srt", "smi", "ass", "vtt", "ttf", "otf", "ttc", "vcf", "ics", "nomedia",
        "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr", "torrent", "part", "crdownload",
    )
}
