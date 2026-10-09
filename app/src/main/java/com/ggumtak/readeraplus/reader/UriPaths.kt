package com.ggumtak.readeraplus.reader

/**
 * Pure helpers that turn content:// document ids / paths into candidate real file paths, and pick safe file
 * names for copies (unit-tested). Callers still check that a candidate is readable.
 */
object UriPaths {
    const val PRIMARY_ROOT = "/storage/emulated/0"
    private val VOLUME_ID = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    /**
     * DocumentsContract document id → path:
     * `primary:Books/a.txt` → `/storage/emulated/0/Books/a.txt`, `1A2B-3C4D:x.txt` → `/storage/1A2B-3C4D/x.txt`,
     * `raw:/storage/...` → as is, `home:a.txt` → `…/Documents/a.txt`. Numeric / `msf:` ids → null.
     */
    fun fromDocumentId(docId: String?, primaryRoot: String = PRIMARY_ROOT): String? {
        if (docId.isNullOrEmpty()) return null
        if (docId.startsWith("raw:")) return docId.substring(4).takeIf { it.startsWith("/") }
        if (docId.startsWith("/")) return docId
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rest = docId.substring(colon + 1).trimStart('/')
        return when {
            volume.equals("primary", ignoreCase = true) -> join(primaryRoot, rest)
            volume.equals("home", ignoreCase = true) -> join("$primaryRoot/Documents", rest)
            VOLUME_ID.matches(volume) -> join("/storage/$volume", rest)
            else -> null
        }
    }

    /**
     * Path-shaped content URIs used by many file managers (`/root/storage/emulated/0/…`,
     * `/external_files/Books/a.txt`, `/sdcard/…`) → candidate absolute path, or null.
     */
    fun fromUriPath(path: String?, primaryRoot: String = PRIMARY_ROOT): String? {
        if (path.isNullOrEmpty()) return null
        val storage = path.indexOf("/storage/")
        if (storage >= 0) return path.substring(storage)
        val sdcard = path.indexOf("/sdcard/")
        if (sdcard >= 0) return join(primaryRoot, path.substring(sdcard + "/sdcard/".length))
        for (prefix in EXTERNAL_PREFIXES) {
            if (path.startsWith(prefix)) return join(primaryRoot, path.substring(prefix.length))
        }
        return null
    }

    private val EXTERNAL_PREFIXES = listOf("/external_files/", "/external/", "/external_storage_root/", "/primary/")

    private fun join(root: String, rest: String): String = if (rest.isEmpty()) root else "$root/$rest"

    /**
     * A file name that is safe on disk and ends with a supported extension when the name or [mime] allows it.
     * [fallbackBase] is used when [displayName] is empty.
     */
    fun safeFileName(displayName: String?, mime: String?, fallbackBase: String): String {
        var name = (displayName ?: "").substringAfterLast('/').substringAfterLast('\\')
        val sb = StringBuilder(name.length)
        for (c in name) {
            if (c < ' ' || c in "\\/:*?\"<>|") sb.append('_') else sb.append(c)
        }
        name = sb.toString().trim().trimStart('.')
        if (name.isEmpty()) name = fallbackBase
        if (name.length > 150) {
            val ext = name.substringAfterLast('.', "")
            name = if (ext.length in 1..5) name.substring(0, 140) + "." + ext else name.substring(0, 150)
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "txt" || ext == "epub" || ext == "pdf") return name
        val m = mime?.lowercase() ?: ""
        return when {
            m == "application/epub+zip" || m.endsWith("epub") -> "$name.epub"
            m == "application/pdf" -> "$name.pdf"
            m.startsWith("text/") -> "$name.txt"
            else -> name
        }
    }

    /** "name.txt" with a copy number: 1 → as is, 2 → "name (2).txt" (dot files / no extension handled). */
    fun numberedName(name: String, n: Int): String {
        if (n <= 1) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }
}
