package com.ggumtak.readeraplus.format.epub

import java.nio.charset.Charset

/** Zip-path helpers for EPUB hrefs: fragment splitting, percent-decoding, relative resolution, normalisation. */
internal object EpubPaths {
    /** Directory part of a zip path including the trailing '/', or "" at the root. */
    fun dirOf(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i < 0) "" else path.substring(0, i + 1)
    }

    /** True when [href] has a URI scheme (`http:`, `mailto:`, `data:` …) — i.e. it points outside the book. */
    fun hasScheme(href: String): Boolean {
        val h = href.trim()
        for (i in h.indices) {
            val c = h[i]
            if (c == ':') return i > 0
            val ok = c in 'a'..'z' || c in 'A'..'Z' || (i > 0 && (c in '0'..'9' || c == '+' || c == '-' || c == '.'))
            if (!ok) return false
        }
        return false
    }

    /** The fragment of [href] (after '#'), or null when absent/empty. Percent-decoded. */
    fun fragment(href: String): String? {
        val i = href.indexOf('#')
        if (i < 0 || i == href.length - 1) return null
        return percentDecode(href.substring(i + 1))
    }

    /** [href] without fragment and query. */
    fun stripFragment(href: String): String {
        var e = href.length
        val h = href.indexOf('#')
        if (h >= 0) e = h
        val q = href.indexOf('?')
        if (q in 0 until e) e = q
        return href.substring(0, e).trim()
    }

    /**
     * Resolves [href] (fragment/query stripped) against [baseDir] into a normalised zip path.
     * [decode] percent-decodes the href first (the default; hrefs in EPUBs are URLs).
     */
    fun resolve(baseDir: String, href: String, decode: Boolean = true): String {
        var h = stripFragment(href)
        if (decode) h = percentDecode(h)
        h = h.replace('\\', '/')
        val joined = if (h.startsWith("/")) h else baseDir + h
        return normalize(joined)
    }

    /** Normalises `a/./b/../c` → `a/c`, collapses `//`, strips leading '/', converts '\' to '/'. */
    fun normalize(path: String): String {
        var p = path
        if (p.indexOf('\\') >= 0) p = p.replace('\\', '/')
        if (p.indexOf("./") < 0 && p.indexOf("//") < 0 && !p.startsWith("/") && !p.endsWith("/.") && !p.endsWith("/..") &&
            p != "." && p != ".."
        ) return p
        val parts = ArrayList<String>()
        var i = 0
        val n = p.length
        while (i <= n) {
            var j = p.indexOf('/', i)
            if (j < 0) j = n
            val seg = p.substring(i, j)
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
            i = j + 1
        }
        val trailingSlash = p.endsWith("/") && parts.isNotEmpty()
        val out = parts.joinToString("/")
        return if (trailingSlash) "$out/" else out
    }

    /** Percent-decodes UTF-8 escapes; malformed escapes stay literal. '+' is NOT a space (paths, not forms). */
    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        val out = StringBuilder(s.length)
        var i = 0
        fun flush() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
        }
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = hexVal(s[i + 1])
                val l = hexVal(s[i + 2])
                if (h >= 0 && l >= 0) {
                    bytes.write(h * 16 + l)
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** Lowercased extension of a path ("" if none). */
    fun extension(path: String): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        return if (dot > slash && dot < path.length - 1) path.substring(dot + 1).lowercase() else ""
    }

    fun isImagePath(path: String): Boolean = when (extension(path)) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "jpe", "jfif", "avif", "heic", "heif" -> true
        else -> false
    }

    fun isHtmlPath(path: String): Boolean = when (extension(path)) {
        "xhtml", "html", "htm", "xht", "xml" -> true
        else -> false
    }
}

/** Byte → String decoding for EPUB text resources (BOM, XML declaration / meta charset, UTF-8 validation). */
internal object EpubText {
    /** Decodes XML/XHTML bytes. Default UTF-8; invalid UTF-8 without a declaration falls back to CP949. */
    fun decode(bytes: ByteArray, len: Int = bytes.size): String {
        if (len <= 0) return ""
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = if (len > 1) bytes[1].toInt() and 0xFF else -1
        val b2 = if (len > 2) bytes[2].toInt() and 0xFF else -1
        if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) return String(bytes, 3, len - 3, Charsets.UTF_8)
        if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, len - 2, Charsets.UTF_16LE)
        if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, len - 2, Charsets.UTF_16BE)
        if (b0 == 0x3C && b1 == 0x00) return String(bytes, 0, len, Charsets.UTF_16LE)
        if (b0 == 0x00 && b1 == 0x3C) return String(bytes, 0, len, Charsets.UTF_16BE)
        var declared = declaredCharset(bytes, len)
        // No BOM and ASCII-compatible bytes (checked above): a UTF-16/32 label is wrong (browsers ignore it too).
        val declaredName = declared?.name() ?: ""
        if (declaredName.startsWith("UTF-16") || declaredName.startsWith("UTF-32")) declared = null
        if (declared != null && declared != Charsets.UTF_8) {
            return String(bytes, 0, len, declared)
        }
        if (!isValidUtf8(bytes, len)) {
            val cp949 = cp949()
            if (cp949 != null) {
                // Undeclared: legacy Korean tools wrote CP949. Declared UTF-8 (every XHTML template says so) but
                // invalid: only switch when the bytes are *strictly* valid CP949 — real UTF-8 Korean text with a
                // corrupt byte never is (0x80 trail bytes aren't CP949 leads), so it keeps UTF-8 + U+FFFD.
                if (declared == null) return String(bytes, 0, len, cp949)
                strictDecode(bytes, len, cp949)?.let { return it }
            }
        }
        return String(bytes, 0, len, Charsets.UTF_8)
    }

    /** Decodes with [cs] reporting (not replacing) malformed/unmappable input; null if the bytes don't fit. */
    private fun strictDecode(bytes: ByteArray, len: Int, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes, 0, len))
            .toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** Decodes a CSS file (BOM / `@charset`, default UTF-8). */
    fun decodeCss(bytes: ByteArray): String {
        val len = bytes.size
        if (len >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, len - 3, Charsets.UTF_8)
        }
        if (len >= 10 && String(bytes, 0, 10, Charsets.ISO_8859_1) == "@charset \"") {
            val end = String(bytes, 0, minOf(len, 64), Charsets.ISO_8859_1).indexOf('"', 10)
            if (end > 10) {
                val cs = charsetFor(String(bytes, 10, end - 10, Charsets.ISO_8859_1))
                if (cs != null) return String(bytes, 0, len, cs)
            }
        }
        return String(bytes, 0, len, Charsets.UTF_8)
    }

    /** Charset named in the XML declaration or an HTML meta tag within the first 1 KB. */
    private fun declaredCharset(bytes: ByteArray, len: Int): Charset? {
        val head = String(bytes, 0, minOf(len, 1024), Charsets.ISO_8859_1)
        var name: String? = null
        if (head.startsWith("<?xml")) {
            val end = head.indexOf("?>")
            val decl = if (end > 0) head.substring(0, end) else head
            name = attrValue(decl, "encoding")
        }
        if (name == null) {
            val i = head.indexOf("charset=", ignoreCase = true)
            if (i >= 0) {
                var j = i + 8
                while (j < head.length && (head[j] == '"' || head[j] == '\'' || head[j] == ' ')) j++
                val st = j
                while (j < head.length && (head[j].isLetterOrDigit() || head[j] == '-' || head[j] == '_')) j++
                if (j > st) name = head.substring(st, j)
            }
        }
        return name?.let { charsetFor(it) }
    }

    private fun attrValue(decl: String, attr: String): String? {
        val i = decl.indexOf(attr)
        if (i < 0) return null
        var j = i + attr.length
        while (j < decl.length && (decl[j] == ' ' || decl[j] == '=')) j++
        if (j >= decl.length) return null
        val q = decl[j]
        if (q != '"' && q != '\'') return null
        val e = decl.indexOf(q, j + 1)
        return if (e > j) decl.substring(j + 1, e).trim() else null
    }

    /** Resolves a declared charset name; Korean legacy names map to CP949 (never plain EUC-KR when avoidable). */
    fun charsetFor(name: String): Charset? {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return null
        return when (n) {
            "utf-8", "utf8" -> Charsets.UTF_8
            "euc-kr", "euckr", "ks_c_5601-1987", "ks_c_5601", "cp949", "ms949", "windows-949", "x-windows-949",
            "uhc", "ksc5601", "korean", "csksc56011987", "iso-ir-149" -> cp949()
            "iso-8859-1", "latin1", "us-ascii", "ascii" -> Charset.forName("windows-1252")
            else -> try {
                Charset.forName(n)
            } catch (_: Exception) {
                null
            }
        }
    }

    @Volatile private var cp949Cache: Charset? = null
    @Volatile private var cp949Resolved = false

    /** First supported of MS949, x-windows-949, windows-949, EUC-KR (the name "CP949" is never used). */
    fun cp949(): Charset? {
        if (cp949Resolved) return cp949Cache
        var found: Charset? = null
        for (n in arrayOf("MS949", "x-windows-949", "windows-949", "EUC-KR")) {
            try {
                if (Charset.isSupported(n)) {
                    found = Charset.forName(n)
                    break
                }
            } catch (_: Exception) {
            }
        }
        cp949Cache = found
        cp949Resolved = true
        return found
    }

    /** Strict UTF-8 validation; a sequence truncated by the end of the buffer is tolerated. */
    fun isValidUtf8(b: ByteArray, len: Int): Boolean {
        var i = 0
        while (i < len) {
            val c = b[i].toInt()
            if (c >= 0) {
                i++
                continue
            }
            val u = c and 0xFF
            val n = when {
                u in 0xC2..0xDF -> 1
                u in 0xE0..0xEF -> 2
                u in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + n >= len) { // sequence truncated by the end of the buffer
                for (k in i + 1 until len) {
                    val t = b[k].toInt() and 0xFF
                    if (t < 0x80 || t > 0xBF) return false
                }
                return true
            }
            for (k in 1..n) {
                val t = b[i + k].toInt() and 0xFF
                if (t < 0x80 || t > 0xBF) return false
            }
            if (n == 2) {
                val t1 = b[i + 1].toInt() and 0xFF
                if (u == 0xE0 && t1 < 0xA0) return false
                if (u == 0xED && t1 > 0x9F) return false
            } else if (n == 3) {
                val t1 = b[i + 1].toInt() and 0xFF
                if (u == 0xF0 && t1 < 0x90) return false
                if (u == 0xF4 && t1 > 0x8F) return false
            }
            i += n + 1
        }
        return true
    }
}
