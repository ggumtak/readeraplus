package com.ggumtak.readeraplus.ui.library

import android.view.KeyEvent
import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.data.ShelfGroup
import java.util.Locale

/**
 * Pure (JVM-testable) helpers of the library screen: display strings, path conversion for SAF picks,
 * file naming for imports, key mapping and back-navigation order.
 */
internal object LibraryText {

    /** Shelves that first list [ShelfGroup]s and only show books after a group is picked. */
    private val GROUPED = setOf(Shelf.AUTHORS, Shelf.SERIES, Shelf.COLLECTIONS, Shelf.FORMATS, Shelf.FOLDERS)

    fun isGrouped(shelf: Shelf): Boolean = shelf in GROUPED

    private val UNITS = arrayOf("KB", "MB", "GB", "TB")

    /**
     * Compact size like ReadEra's card: "820KB", "3.4MB", "15MB" (binary units, one decimal below 10,
     * no trailing ".0").
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)}B"
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < UNITS.lastIndex) {
            v /= 1024
            i++
        }
        if (v < 10) {
            val r = Math.round(v * 10) / 10.0
            if (r < 10) {
                val whole = r.toLong()
                return if (r == whole.toDouble()) "$whole${UNITS[i]}" else String.format(Locale.US, "%.1f%s", r, UNITS[i])
            }
        }
        val rounded = Math.round(v)
        if (rounded >= 1024 && i < UNITS.lastIndex) return "1${UNITS[i + 1]}"
        return "$rounded${UNITS[i]}"
    }

    /** "TXT, 3.4MB". */
    fun metaLine(formatLabel: String, sizeBytes: Long): String = "$formatLabel, ${formatSize(sizeBytes)}"

    /** Integer percent label ("34%"), or "" for a book that was never opened. Floors so 99.7% isn't "100%". */
    fun percent(progress: Float, opened: Boolean): String {
        if (!opened) return ""
        val p = Math.floor(progress.toDouble() * 100.0 + 1e-3).toInt().coerceIn(0, 100)
        return "$p%"
    }

    /** Last path segment of a folder path ("/storage/emulated/0/Books" → "Books"). */
    fun folderName(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return "/"
        return trimmed.substringAfterLast('/').ifEmpty { trimmed }
    }

    /**
     * Title shown for a group: folders use their last path segment (whether the data layer labels them with
     * the full path or already with a short name), everything else its label.
     */
    fun groupTitle(shelf: Shelf, group: ShelfGroup): String {
        if (shelf != Shelf.FOLDERS) return group.label
        val l = group.label
        return if (l.isEmpty() || l.contains('/')) folderName(l.ifEmpty { group.key }) else l
    }

    /** Second line of a group row: the full folder path for FOLDERS (when it adds information), else null. */
    fun groupSubtitle(shelf: Shelf, group: ShelfGroup): String? {
        if (shelf != Shelf.FOLDERS) return null
        val path = when {
            group.key.contains('/') -> group.key
            group.label.contains('/') -> group.label
            else -> group.key.ifEmpty { group.label }
        }
        return path.takeIf { it.isNotEmpty() && it != groupTitle(shelf, group) }
    }

    /** Groups whose label (or folder path) contains [query] (case-insensitive); all when blank. */
    fun filterGroups(groups: List<ShelfGroup>, query: String): List<ShelfGroup> {
        val q = query.trim()
        if (q.isEmpty()) return groups
        return groups.filter { it.label.contains(q, ignoreCase = true) || it.key.contains(q, ignoreCase = true) }
    }

    /** True for names the app can open (.epub / .txt, any case). */
    fun isBookName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".epub") || n.endsWith(".txt")
    }

    /**
     * Converts a DocumentsProvider document/tree id to a filesystem path when the provider is the platform's
     * external storage (`primary:Books/a.txt`, `1234-ABCD:Novels`, `home:x`) or a downloads `raw:` id.
     * [authority] null = assume external storage. Returns null when no path can be derived.
     */
    fun docIdToPath(authority: String?, docId: String, primaryRoot: String): String? {
        if (docId.startsWith("raw:")) return docId.removePrefix("raw:").takeIf { it.startsWith("/") }
        if (authority != null && authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1).trim('/')
        val base = when {
            volume.equals("primary", ignoreCase = true) -> primaryRoot.trimEnd('/')
            volume.equals("home", ignoreCase = true) -> primaryRoot.trimEnd('/') + "/Documents"
            VOLUME_ID.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    private val VOLUME_ID = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|[0-9A-Fa-f]{8,16}")

    private val BAD_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /**
     * File name to store an imported document under: the display name with unsafe characters replaced, and an
     * extension derived from [mime] when the name has none we support. Null when the type is unsupported.
     */
    fun importFileName(displayName: String?, mime: String?): String? {
        val clean = (displayName ?: "").replace(BAD_CHARS, "_").trim().trimStart('.')
        if (clean.isNotEmpty() && isBookName(clean)) return clean
        val ext = when (mime?.lowercase(Locale.ROOT)) {
            "application/epub+zip" -> ".epub"
            "text/plain" -> ".txt"
            else -> return null
        }
        val base = clean.ifEmpty { "문서" }
        return base + ext
    }

    /** "a.txt" → "a (1).txt", "a (2).txt", … until [exists] is false. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    /** Grid columns for a width: as many [cellDp]-wide cells as fit, at least [min]. */
    fun gridColumns(widthPx: Int, density: Float, cellDp: Int = 110, min: Int = 2): Int {
        if (density <= 0f || widthPx <= 0) return min
        return ((widthPx / density) / cellDp).toInt().coerceAtLeast(min)
    }

    /**
     * Library list paging by hardware keys: +1 = scroll a page down, -1 = up, 0 = not ours.
     * Volume keys only when [volumeKeysTurn] (swapped by [invertVolume]); page keys always; learned keys too.
     */
    fun keyDirection(
        keyCode: Int,
        volumeKeysTurn: Boolean,
        invertVolume: Boolean,
        nextKeys: Set<Int>,
        prevKeys: Set<Int>,
    ): Int = when {
        // A learned key must never swallow navigation keys (the user could not leave the library).
        keyCode in RESERVED_KEYS -> 0
        keyCode in nextKeys -> 1
        keyCode in prevKeys -> -1
        keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> 1
        keyCode == KeyEvent.KEYCODE_PAGE_UP -> -1
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && volumeKeysTurn -> if (invertVolume) -1 else 1
        keyCode == KeyEvent.KEYCODE_VOLUME_UP && volumeKeysTurn -> if (invertVolume) 1 else -1
        else -> 0
    }

    private val RESERVED_KEYS = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_APP_SWITCH,
    )

    enum class BackStep { CLOSE_DRAWER, CLOSE_SEARCH, LEAVE_GROUP, FINISH }

    /** Back closes the drawer, then the search row, then the open group, then leaves the app. */
    fun backStep(drawerOpen: Boolean, searchOpen: Boolean, inGroup: Boolean): BackStep = when {
        drawerOpen -> BackStep.CLOSE_DRAWER
        searchOpen -> BackStep.CLOSE_SEARCH
        inGroup -> BackStep.LEAVE_GROUP
        else -> BackStep.FINISH
    }

    /** True when a background rescan is due ([lastScanAt] 0 = never scanned). */
    fun rescanDue(lastScanAt: Long, now: Long, maxAgeMs: Long = 30L * 60 * 1000): Boolean =
        lastScanAt <= 0L || now - lastScanAt > maxAgeMs || now < lastScanAt

    /** Status row text while scanning / importing, or null when idle. */
    fun statusText(scanning: Boolean, scanFound: Int, importing: Boolean, imported: Int, importTotal: Int): String? {
        val parts = ArrayList<String>(2)
        if (scanning) parts += if (scanFound > 0) "스캔 중… $scanFound" else "스캔 중…"
        if (importing) parts += if (importTotal > 0) "가져오는 중… $imported/$importTotal" else "가져오는 중… $imported"
        return if (parts.isEmpty()) null else parts.joinToString("  ·  ")
    }

    /** Empty-state message for a shelf (no search). */
    fun emptyMessage(shelf: Shelf, query: String, inGroup: Boolean): String {
        if (query.isNotBlank()) return "‘${query.trim()}’에 해당하는 항목이 없습니다."
        if (inGroup) return "이 항목에 문서가 없습니다."
        return when (shelf) {
            Shelf.READING_NOW -> "읽고 있는 문서가 없습니다.\n책을 열면 여기에 표시됩니다."
            Shelf.ALL -> "책이 없습니다.\n‘도서 스캔’으로 기기의 EPUB · TXT 파일을 찾거나 ‘파일 열기’로 추가하세요."
            Shelf.FAVORITES -> "즐겨찾기한 문서가 없습니다.\n카드의 별 버튼으로 추가하세요."
            Shelf.TO_READ -> "읽을 문서가 없습니다.\n카드의 시계 버튼으로 추가하세요."
            Shelf.HAVE_READ -> "읽던 문서가 없습니다.\n카드의 체크 버튼으로 표시하세요."
            Shelf.AUTHORS -> "작가 정보가 있는 문서가 없습니다."
            Shelf.SERIES -> "시리즈 정보가 있는 문서가 없습니다."
            Shelf.COLLECTIONS -> "컬렉션이 없습니다.\n오른쪽 위 + 버튼으로 새 컬렉션을 만드세요."
            Shelf.FORMATS -> "문서가 없습니다."
            Shelf.FOLDERS -> "문서가 있는 폴더가 없습니다."
            Shelf.DOWNLOADS -> "다운로드 폴더에 EPUB · TXT 문서가 없습니다."
            Shelf.TRASH -> "휴지통이 비어 있습니다."
        }
    }

    /** Mime type for sharing a book file. */
    fun mimeFor(fileName: String): String {
        val n = fileName.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".epub") -> "application/epub+zip"
            n.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * Adds [path] to the configured scan folders. An empty [existing] set means "scan [defaultRoots]" (the
     * whole storage), so a folder inside one of them changes nothing, and a folder outside all of them (e.g. a
     * USB drive) is added *together with* the default roots — otherwise the non-empty set would narrow the scan
     * to that one folder. Returns null when nothing changes (already covered, or no roots known). Folders
     * inside the new one are dropped (the new root covers them).
     */
    fun addScanFolder(existing: Set<String>, path: String, defaultRoots: List<String> = emptyList()): Set<String>? {
        val p = path.trimEnd('/').ifEmpty { "/" }
        val base: Collection<String> = if (existing.isNotEmpty()) {
            existing
        } else {
            defaultRoots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
        }
        if (base.isEmpty()) return null
        if (base.any { isSameOrInside(p, it) }) return null
        val kept = base.filterNot { isSameOrInside(it, p) }
        return LinkedHashSet(kept).apply { add(p) }
    }

    /** True when [path] equals [folder] or lies below it (path-segment aware). */
    fun isSameOrInside(path: String, folder: String): Boolean {
        val f = folder.trimEnd('/')
        val p = path.trimEnd('/')
        if (f.isEmpty()) return true
        return p == f || p.startsWith("$f/")
    }

    /** Human label for a TXT encoding choice ("" = auto). */
    fun encodingLabel(charset: String): String = when (charset.uppercase(Locale.ROOT)) {
        "" -> "자동 감지"
        "UTF-8" -> "UTF-8 (유니코드)"
        "MS949", "CP949", "X-WINDOWS-949", "WINDOWS-949" -> "CP949 (한국어 확장 완성형)"
        "EUC-KR" -> "EUC-KR (한국어 완성형)"
        "UTF-16LE" -> "UTF-16 LE"
        "UTF-16BE" -> "UTF-16 BE"
        else -> charset
    }

    const val ADB_HINT = "adb shell appops set com.ggumtak.readeraplus MANAGE_EXTERNAL_STORAGE allow"
}
