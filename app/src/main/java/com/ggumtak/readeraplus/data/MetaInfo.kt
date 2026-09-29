package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocMeta

/** Stored-length caps shared by the library API and the backup restore (defensive; the UI passes far less). */
internal object DataLimits {
    const val TITLE = 500
    const val AUTHOR = 300
    const val SNIPPET = 500
    const val NOTE = 20_000
    /** ~300 KB of UTF-8 at most: a row must fit a 2 MB CursorWindow with room to spare. */
    const val QUOTE = 100_000
    const val REVIEW = 100_000
    const val COLLECTION_NAME = 100
}

/** File facts stored for a library entry. */
internal class FileInfo(
    val path: String,
    val fileName: String,
    val format: BookFormat,
    val size: Long,
    val mtime: Long,
) {
    val folder: String get() = DataPaths.folderOf(path)
}

/** Cleaned metadata ready to store (title never blank). */
internal data class MetaInfo(
    val title: String,
    val author: String,
    val series: String?,
    val seriesIndex: Float?,
    val language: String?,
) {
    companion object {
        private const val MAX_TITLE = DataLimits.TITLE
        private const val MAX_AUTHOR = DataLimits.AUTHOR
        private val SPACES = Regex("\\s+")

        /** From parser metadata (null = unreadable file): trims, NFC, file-name title fallback. */
        fun from(meta: DocMeta?, fileName: String): MetaInfo {
            val fallback = titleFromFileName(fileName)
            if (meta == null) return MetaInfo(fallback, "", null, null, null)
            val title = clean(meta.title, MAX_TITLE).ifEmpty { fallback }
            val authors = LinkedHashSet<String>()
            for (a in meta.authors) {
                val c = clean(a, MAX_AUTHOR)
                if (c.isNotEmpty()) authors += c
            }
            val author = clean(authors.joinToString(", "), MAX_AUTHOR)
            val series = meta.series?.let { clean(it, MAX_TITLE) }?.ifEmpty { null }
            val idx = meta.seriesIndex?.takeIf { it.isFinite() }
            val lang = meta.language?.let { clean(it, 35) }?.ifEmpty { null }
            return MetaInfo(title, author, series, if (series == null) null else idx, lang)
        }

        /**
         * Whether the library needs the parser's metadata for [format]. TXT has none beyond the file-name title
         * (its sniffed charset is not stored: `Book.encoding` is the user's forced encoding), so the scanner
         * skips a 64 KB read + charset sniff per TXT file.
         */
        fun needsParser(format: BookFormat?): Boolean = format != null && format != BookFormat.TXT

        /** "소설 1권.txt" → "소설 1권"; names without extension stay as they are. */
        fun titleFromFileName(fileName: String): String {
            val base = fileName.substringAfterLast('/')
            val dot = base.lastIndexOf('.')
            val t = if (dot > 0) base.substring(0, dot) else base
            return clean(t, MAX_TITLE).ifEmpty { base }
        }

        /** Trim, collapse whitespace runs (incl. newlines) to one space, NFC, cap length. */
        fun clean(s: String, max: Int): String {
            var t = s.trim()
            if (t.isEmpty()) return t
            if (t.any { it.isWhitespace() && it != ' ' } || t.contains("  ")) t = t.replace(SPACES, " ")
            t = LibrarySql.nfc(t)
            return if (t.length > max) truncate(t, max).trimEnd() else t
        }

        /** Lower-cases ASCII letters only: the same folding as SQLite's NOCASE collation. */
        fun asciiLower(s: String): String {
            var i = 0
            while (i < s.length && s[i] !in 'A'..'Z') i++
            if (i == s.length) return s
            val c = s.toCharArray()
            while (i < c.size) {
                if (c[i] in 'A'..'Z') c[i] = c[i] + 32
                i++
            }
            return String(c)
        }

        /** First [max] chars of [s] without splitting a surrogate pair. */
        fun truncate(s: String, max: Int): String {
            if (s.length <= max) return s
            if (max <= 0) return ""
            val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
            return s.substring(0, end)
        }
    }
}
