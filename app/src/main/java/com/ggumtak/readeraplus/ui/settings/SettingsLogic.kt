package com.ggumtak.readeraplus.ui.settings

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Pure helpers of the settings screens (no Android types; unit-tested on the JVM).
 */

/**
 * Converts folders picked with `ACTION_OPEN_DOCUMENT_TREE` into filesystem paths usable by the scanner
 * (the app scans with java.io.File under "모든 파일 접근").
 */
object TreePaths {
    const val PRIMARY_ROOT = "/storage/emulated/0"
    private val VOLUME_ID = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    /**
     * `content://com.android.externalstorage.documents/tree/primary%3ABooks` → `/storage/emulated/0/Books`.
     * Returns null when the URI is not a tree URI or its document id has no path equivalent (cloud providers,
     * media ids, ...).
     */
    fun fromTreeUri(uri: String?, primaryRoot: String = PRIMARY_ROOT, volumeRoots: Map<String, String> = emptyMap()): String? {
        if (uri.isNullOrEmpty()) return null
        val i = uri.indexOf("/tree/")
        if (i < 0) return null
        var seg = uri.substring(i + "/tree/".length)
        for (stop in charArrayOf('/', '?', '#')) {
            val k = seg.indexOf(stop)
            if (k >= 0) seg = seg.substring(0, k)
        }
        if (seg.isEmpty()) return null
        return fromDocumentId(percentDecode(seg), primaryRoot, volumeRoots)
    }

    /**
     * Document id (from `DocumentsContract.getTreeDocumentId`) → path:
     * `primary:Books` → `<primaryRoot>/Books`, `1A2B-3C4D:Novels` → `/storage/1A2B-3C4D/Novels`,
     * `home:x` → `<primaryRoot>/Documents/x`, `raw:/storage/...` → as is, `downloads` → `<primaryRoot>/Download`.
     * [volumeRoots] maps lower-case volume UUIDs to their mount directories (from `StorageManager`); it wins
     * over the `/storage/<id>` guess, which is only made for FAT-style `XXXX-XXXX` ids.
     */
    fun fromDocumentId(docId: String?, primaryRoot: String = PRIMARY_ROOT, volumeRoots: Map<String, String> = emptyMap()): String? {
        if (docId.isNullOrEmpty()) return null
        if (docId.startsWith("raw:")) return docId.substring(4).takeIf { it.startsWith("/") }?.let { FolderSets.normalize(it) }
        if (docId.startsWith("/")) return FolderSets.normalize(docId)
        if (docId == "downloads") return FolderSets.normalize("$primaryRoot/Download")
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rest = docId.substring(colon + 1).trim('/')
        val base = when {
            volume.equals("primary", ignoreCase = true) -> primaryRoot
            volume.equals("home", ignoreCase = true) -> "$primaryRoot/Documents"
            volumeRoots.containsKey(volume.lowercase(Locale.ROOT)) -> volumeRoots.getValue(volume.lowercase(Locale.ROOT))
            VOLUME_ID.matches(volume) -> "/storage/${volume.uppercase(Locale.ROOT)}"
            else -> return null
        }
        return FolderSets.normalize(if (rest.isEmpty()) base else "$base/$rest")
    }

    /** RFC 3986 percent-decoding as UTF-8. Unlike URLDecoder, '+' stays '+'; malformed escapes stay literal. */
    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            // Literal char (a surrogate pair is copied as one code point).
            val n = if (Character.isHighSurrogate(c) && i + 1 < s.length) 2 else 1
            out.write(s.substring(i, i + n).toByteArray(Charsets.UTF_8))
            i += n
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

/** Scan / excluded folder set operations (paths are absolute, normalised, without trailing slash). */
object FolderSets {
    /** Result of [add]: the new set, whether it changed, and a short Korean message for a toast. */
    class AddResult(val folders: Set<String>, val added: Boolean, val message: String)

    /** Trims, turns '\' into '/', collapses repeated slashes and drops a trailing slash (except for "/"). */
    fun normalize(path: String): String {
        val t = path.trim().replace('\\', '/')
        if (t.isEmpty()) return ""
        val sb = StringBuilder(t.length)
        for (c in t) {
            if (c == '/' && sb.isNotEmpty() && sb[sb.length - 1] == '/') continue
            sb.append(c)
        }
        while (sb.length > 1 && sb[sb.length - 1] == '/') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    /** True when [path] equals [parent] or lies below it. */
    fun isSameOrInside(path: String, parent: String): Boolean {
        if (path == parent) return true
        if (parent == "/") return path.startsWith("/")
        return path.startsWith("$parent/")
    }

    /**
     * Adds [path]: ignored when already covered by an entry; entries below the new folder are merged into it.
     */
    fun add(folders: Set<String>, path: String): AddResult {
        val p = normalize(path)
        if (p.isEmpty() || !p.startsWith("/")) return AddResult(folders, false, "올바른 폴더 경로가 아닙니다")
        folders.firstOrNull { isSameOrInside(p, it) }?.let { cover ->
            val msg = if (cover == p) "이미 추가된 폴더입니다" else "'${displayName(cover)}' 폴더에 이미 포함되어 있습니다"
            return AddResult(folders, false, msg)
        }
        val merged = folders.filter { isSameOrInside(it, p) }
        val next = LinkedHashSet<String>(folders.size + 1)
        folders.filterTo(next) { it !in merged }
        next += p
        val msg = if (merged.isEmpty()) "추가했습니다" else "추가했습니다 (하위 폴더 ${merged.size}개 합침)"
        return AddResult(next, true, msg)
    }

    fun remove(folders: Set<String>, path: String): Set<String> = folders.filterTo(LinkedHashSet()) { it != path }

    fun sorted(folders: Collection<String>): List<String> = folders.sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** True when [path] lies inside one of [roots] (an excluded folder outside every root has no effect). */
    fun isCovered(path: String, roots: Collection<String>): Boolean = roots.any { isSameOrInside(normalize(path), normalize(it)) }

    /**
     * True when excluding [path] can't change anything: explicit scan folders are set ([scanFolders] non-empty)
     * and none of them contains it. (With no scan folders the scanner walks all storage, so every path counts.)
     */
    fun exclusionHasNoEffect(scanFolders: Collection<String>, path: String): Boolean =
        scanFolders.isNotEmpty() && !isCovered(path, scanFolders)

    /** True when excluding [path] would skip a whole scan folder (it equals or contains one of [scanFolders]). */
    fun exclusionHidesScanFolder(scanFolders: Collection<String>, path: String): Boolean {
        val p = normalize(path)
        return p.isNotEmpty() && scanFolders.any { isSameOrInside(normalize(it), p) }
    }

    /**
     * Friendly label: `/storage/emulated/0/Books` → `내부 저장소/Books`, `/storage/1A2B-3C4D/x` → `SD 카드 (1A2B-3C4D)/x`.
     */
    fun displayName(path: String, primaryRoot: String = TreePaths.PRIMARY_ROOT): String {
        val p = normalize(path)
        if (p == primaryRoot) return "내부 저장소"
        if (p.startsWith("$primaryRoot/")) return "내부 저장소/" + p.substring(primaryRoot.length + 1)
        val m = Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(/.*)?$").find(p)
        if (m != null) {
            val rest = m.groupValues[2]
            return "SD 카드 (${m.groupValues[1]})" + rest
        }
        return p
    }
}

/** Formatting of values shown in the settings rows. */
object SettingsFormat {
    /** Sleep timer choices in minutes (0 = off). */
    val SLEEP_OPTIONS: List<Int> = listOf(0, 15, 30, 45, 60, 90)

    /** Screen orientation choices: label to `ActivityInfo.SCREEN_ORIENTATION_*` value. */
    val ORIENTATIONS: List<Pair<String, Int>> = listOf(
        "자동 회전" to -1,
        "세로" to 1,
        "가로" to 0,
        "세로 (뒤집힘)" to 9,
        "가로 (뒤집힘)" to 8,
    )

    fun orientation(value: Int): String = ORIENTATIONS.firstOrNull { it.second == value }?.first ?: "자동 회전"

    fun bytes(n: Long): String {
        if (n < 1024) return "${n.coerceAtLeast(0)} B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n / 1024.0
        var u = 0
        while (v >= 1024 && u < units.size - 1) {
            v /= 1024
            u++
        }
        return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[u]) else String.format(Locale.US, "%.1f %s", v, units[u])
    }

    /** 30 → "30초", 60 → "1분", 90 → "1분 30초". */
    fun seconds(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        if (s < 60) return "${s}초"
        val m = s / 60
        val r = s % 60
        return if (r == 0) "${m}분" else "${m}분 ${r}초"
    }

    /** E-ink full refresh cadence: 0 → "끔", n → "n쪽마다". */
    fun refreshEvery(n: Int): String = if (n <= 0) "끔" else "${n}쪽마다"

    fun sleep(min: Int): String = when {
        min <= 0 -> "끔"
        min % 60 == 0 -> "${min / 60}시간"
        min > 60 -> "${min / 60}시간 ${min % 60}분"
        else -> "${min}분"
    }

    fun rate(v: Float): String = String.format(Locale.US, "%.1f배", v)

    fun pitch(v: Float): String = String.format(Locale.US, "%.1f", v)

    fun sp(v: Float): String = if (v == Math.round(v).toFloat()) "${Math.round(v)}sp" else String.format(Locale.US, "%.1fsp", v)

    /** "readeraplus-backup-20260929.json" (local date). */
    fun backupFileName(millis: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = tz }
        return "readeraplus-backup-${f.format(Date(millis))}.json"
    }

    fun dateTime(millis: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }
        return f.format(Date(millis))
    }
}

/** Web search URL templates offered in "사전 · 번역 · 웹 검색". `%s` = URL-encoded query. */
object WebEngines {
    class Engine(val name: String, val url: String)

    val PRESETS: List<Engine> = listOf(
        Engine("Google", "https://www.google.com/search?q=%s"),
        Engine("네이버", "https://search.naver.com/search.naver?query=%s"),
        Engine("다음", "https://search.daum.net/search?q=%s"),
        Engine("네이버 국어사전", "https://ko.dict.naver.com/#/search?query=%s"),
        Engine("위키백과", "https://ko.wikipedia.org/w/index.php?search=%s"),
        Engine("나무위키", "https://namu.wiki/Search?q=%s"),
    )

    /** Index of the preset with this template, or -1 (custom). */
    fun indexOf(url: String): Int = PRESETS.indexOfFirst { it.url == url.trim() }

    fun nameOf(url: String): String = PRESETS.getOrNull(indexOf(url))?.name ?: "사용자 지정"

    /**
     * Cleans user input: trims, adds "https://" when no scheme is given. Returns null when the template has no
     * `%s`, contains whitespace or is not http(s).
     */
    fun normalizeTemplate(input: String): String? {
        var t = input.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        if (!t.contains("://")) t = "https://$t"
        val lower = t.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        if (!t.contains("%s")) return null
        return t
    }

    fun build(template: String, query: String): String =
        template.replace("%s", URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
}
