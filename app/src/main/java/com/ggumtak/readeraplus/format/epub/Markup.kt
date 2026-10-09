package com.ggumtak.readeraplus.format.epub

/**
 * Tolerant pull tokenizer for XML and (X)HTML, used for container.xml, OPF, NCX, nav and content documents.
 *
 * It never throws on malformed input: unclosed tags, stray `<`, attributes without values, unquoted values,
 * unterminated comments/CDATA and truncated files all degrade to text or are skipped. Comments, DOCTYPE and
 * processing instructions are skipped; CDATA sections come back as raw [TEXT] tokens. The content of `<script>`
 * and `<style>` is returned as one raw [TEXT] token (no markup inside).
 *
 * Tag names are lowercased and stripped of their namespace prefix (`dc:title` -> `title`). Attributes are kept
 * as offsets into the source and decoded only on request ([attr]).
 */
internal class MarkupReader(private val s: String, start: Int = 0, end: Int = s.length) {
    private var pos = start.coerceIn(0, s.length)
    private val limit = end.coerceIn(pos, s.length)

    /** Kind of the current token: [EOF], [TEXT], [START] or [END]. */
    var type = EOF
        private set

    /** Lowercased local name of the current START/END tag. */
    var name: String = ""
        private set

    /** The current START tag ended with `/>`. */
    var selfClosing = false
        private set

    /** Source range of the current TEXT token. */
    var textStart = 0
        private set
    var textEnd = 0
        private set

    /** The current TEXT token is CDATA or raw script/style content: no entity decoding applies. */
    var textRaw = false
        private set

    private var attrCount = 0
    // Per attribute: nameStart, nameEnd, valueStart, valueEnd (valueStart = -1 when the attribute has no value).
    private var attrPos = IntArray(64)
    private var rawTextTag: String? = null
    // Last "?>" search ([piClose]): started at piFrom, found at piAt (-1: none up to the end of the source).
    private var piFrom = Int.MAX_VALUE
    private var piAt = -1

    /** Source string (for consumers that decode text ranges themselves). */
    val source: String get() = s

    /** Advances to the next token and returns its type. */
    fun next(): Int {
        val raw = rawTextTag
        if (raw != null) {
            rawTextTag = null
            if (readRawText(raw)) return TEXT
        }
        while (pos < limit) {
            if (s[pos] != '<') return readText(pos)
            if (pos + 1 >= limit) return readText(pos)
            val n = s[pos + 1]
            when {
                n == '/' -> {
                    if (pos + 2 < limit && isNameStart(s[pos + 2])) return readEndTag()
                    // "</>" or "</ junk>": bogus comment, skip to '>'
                    pos = skipPast('>', pos + 2)
                }
                n == '!' -> {
                    if (s.startsWith("<!--", pos)) {
                        val e = s.indexOf("-->", pos + 4)
                        pos = if (e < 0 || e + 3 > limit) limit else e + 3
                    } else if (s.startsWith("<![CDATA[", pos)) {
                        val cs = pos + 9
                        val e = s.indexOf("]]>", cs)
                        val ce = if (e < 0 || e > limit) limit else e
                        pos = if (e < 0 || e + 3 > limit) limit else e + 3
                        if (ce > cs) {
                            textStart = cs
                            textEnd = ce
                            textRaw = true
                            type = TEXT
                            return TEXT
                        }
                    } else {
                        pos = skipDeclaration(pos + 2)
                    }
                }
                n == '?' -> {
                    val e = piClose(pos + 2)
                    pos = if (e < 0 || e + 2 > limit) skipPast('>', pos + 2) else e + 2
                }
                isNameStart(n) -> return readStartTag()
                else -> return readText(pos)
            }
        }
        type = EOF
        return EOF
    }

    private fun readText(from: Int): Int {
        // A '<' at [from] that doesn't start markup is literal text.
        val searchFrom = if (s[from] == '<') from + 1 else from
        val idx = if (searchFrom < limit) s.indexOf('<', searchFrom) else -1
        val end = if (idx < 0 || idx > limit) limit else idx
        textStart = from
        textEnd = end
        textRaw = false
        pos = end
        type = TEXT
        return TEXT
    }

    /** Returns true and sets up a raw TEXT token if the element has non-empty content. */
    private fun readRawText(tag: String): Boolean {
        val start = pos
        var i = start
        var end = limit
        while (i < limit) {
            val idx = s.indexOf("</", i)
            if (idx < 0 || idx >= limit) break
            if (s.regionMatches(idx + 2, tag, 0, tag.length, ignoreCase = true)) {
                val after = idx + 2 + tag.length
                if (after >= limit || !isNameChar(s[after])) {
                    end = idx
                    break
                }
            }
            i = idx + 2
        }
        pos = end
        if (end <= start) return false
        textStart = start
        textEnd = end
        textRaw = true
        type = TEXT
        return true
    }

    private fun readStartTag(): Int {
        var i = pos + 1
        val nameStart = i
        while (i < limit && isNameChar(s[i])) i++
        name = localName(nameStart, i)
        attrCount = 0
        selfClosing = false
        while (i < limit) {
            val c = s[i]
            if (c == '>') {
                i++
                break
            }
            if (c == '/') {
                if (i + 1 < limit && s[i + 1] == '>') {
                    selfClosing = true
                    i += 2
                    break
                }
                i++
                continue
            }
            if (c == '<') break // unclosed tag: the '<' starts the next token
            if (c <= ' ') {
                i++
                continue
            }
            val an = i
            while (i < limit) {
                val d = s[i]
                if (d <= ' ' || d == '=' || d == '>' || d == '/' || d == '<') break
                i++
            }
            val ae = i
            if (ae == an) { // e.g. a stray '=' or quote
                i++
                continue
            }
            var j = i
            while (j < limit && s[j] <= ' ') j++
            if (j < limit && s[j] == '=') {
                j++
                while (j < limit && s[j] <= ' ') j++
                if (j < limit && (s[j] == '"' || s[j] == '\'')) {
                    val q = s[j]
                    val vs = j + 1
                    var ve = s.indexOf(q, vs)
                    if (ve < 0 || ve >= limit) {
                        // unterminated quote: value runs to the next '>'
                        val gt = s.indexOf('>', vs)
                        ve = if (gt < 0 || gt > limit) limit else gt
                        addAttr(an, ae, vs, ve)
                        i = ve
                    } else {
                        addAttr(an, ae, vs, ve)
                        i = ve + 1
                    }
                } else {
                    val vs = j
                    while (j < limit) {
                        val d = s[j]
                        if (d <= ' ' || d == '>' || d == '<') break
                        if (d == '/' && j + 1 < limit && s[j + 1] == '>') break
                        j++
                    }
                    addAttr(an, ae, vs, j)
                    i = j
                }
            } else {
                addAttr(an, ae, -1, -1)
            }
        }
        pos = i
        type = START
        if (!selfClosing && (name == "script" || name == "style")) rawTextTag = name
        return START
    }

    private fun readEndTag(): Int {
        var i = pos + 2
        val ns = i
        while (i < limit && isNameChar(s[i])) i++
        name = localName(ns, i)
        pos = skipPast('>', i)
        attrCount = 0
        selfClosing = false
        type = END
        return END
    }

    /**
     * Skips `<!DOCTYPE ...>` (quotes and an internal `[...]` subset respected) or any other `<!...>` bogus
     * declaration (ends at the first '>', like browsers: an apostrophe in `<!it's>` must not swallow the rest
     * of the document). A DOCTYPE whose quotes/brackets never balance also ends at its first '>'.
     */
    private fun skipDeclaration(from: Int): Int {
        if (!s.regionMatches(from, "doctype", 0, 7, ignoreCase = true)) return skipPast('>', from)
        var i = from
        var bracket = 0
        var quote = 0.toChar()
        while (i < limit) {
            val c = s[i]
            if (quote.code != 0) {
                if (c == quote) quote = 0.toChar()
            } else when (c) {
                '"', '\'' -> quote = c
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                '>' -> if (bracket == 0) return i + 1
            }
            i++
        }
        return skipPast('>', from)
    }

    /**
     * `s.indexOf("?>", from)`, reusing the last search: tokens only move forward, so a miss stays a miss and a hit
     * at or after [from] is still the first. Word-made HTML repeats `<?xml:namespace … />` with no "?>" after it,
     * and searching again for each one scanned to the end of the document every time (O(n²)).
     */
    private fun piClose(from: Int): Int {
        if (from < piFrom || piAt in 0 until from) {
            piFrom = from
            piAt = s.indexOf("?>", from)
        }
        return piAt
    }

    private fun skipPast(ch: Char, from: Int): Int {
        if (from >= limit) return limit
        val e = s.indexOf(ch, from)
        return if (e < 0 || e >= limit) limit else e + 1
    }

    private fun localName(a: Int, b: Int): String {
        var start = a
        for (k in b - 1 downTo a) {
            if (s[k] == ':') {
                start = k + 1
                break
            }
        }
        if (start >= b) return ""
        var hasUpper = false
        for (k in start until b) {
            val c = s[k]
            if (c in 'A'..'Z') {
                hasUpper = true
                break
            }
        }
        val n = s.substring(start, b)
        return if (hasUpper) n.lowercase() else n
    }

    private fun addAttr(ns: Int, ne: Int, vs: Int, ve: Int) {
        val k = attrCount * 4
        if (k + 4 > attrPos.size) attrPos = attrPos.copyOf(attrPos.size * 2)
        attrPos[k] = ns
        attrPos[k + 1] = ne
        attrPos[k + 2] = vs
        attrPos[k + 3] = ve
        attrCount++
    }

    /** Number of attributes of the current START tag. */
    val attributeCount: Int get() = attrCount

    /** Raw (lowercased) name of attribute [i], including its prefix. */
    fun attrName(i: Int): String {
        val k = i * 4
        return s.substring(attrPos[k], attrPos[k + 1]).lowercase()
    }

    /** Decoded value of attribute [i] ("" when it has no value). */
    fun attrValue(i: Int): String {
        val k = i * 4
        val vs = attrPos[k + 2]
        if (vs < 0) return ""
        return Entities.decode(s, vs, attrPos[k + 3])
    }

    /**
     * Decoded value of attribute [name] (case-insensitive). Matches the full name first (`epub:type`), then the
     * local part after a prefix (`href` finds `xlink:href`). Returns "" for a value-less attribute, null if absent.
     */
    fun attr(name: String): String? {
        val i = attrIndex(name)
        return if (i < 0) null else attrValue(i)
    }

    fun hasAttr(name: String): Boolean = attrIndex(name) >= 0

    private fun attrIndex(name: String): Int {
        val n = attrCount
        for (i in 0 until n) {
            val k = i * 4
            val ns = attrPos[k]
            val len = attrPos[k + 1] - ns
            if (len == name.length && s.regionMatches(ns, name, 0, len, ignoreCase = true)) return i
        }
        for (i in 0 until n) {
            val k = i * 4
            val ns = attrPos[k]
            val ne = attrPos[k + 1]
            var colon = -1
            for (q in ne - 1 downTo ns) if (s[q] == ':') { colon = q; break }
            if (colon < 0) continue
            val ls = colon + 1
            val len = ne - ls
            if (len == name.length && s.regionMatches(ls, name, 0, len, ignoreCase = true)) return i
        }
        return -1
    }

    /** Decoded text of the current TEXT token (entities resolved unless raw). */
    fun text(): String = if (textRaw) s.substring(textStart, textEnd) else Entities.decode(s, textStart, textEnd)

    companion object {
        const val EOF = 0
        const val TEXT = 1
        const val START = 2
        const val END = 3

        fun isNameStart(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == ':' || c.code >= 0xC0

        fun isNameChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == ':' || c == '.' ||
                c.code >= 0xB7
    }
}

/** HTML/XML character reference decoding. */
internal object Entities {
    private val named: HashMap<String, Int> = HashMap(256)

    init {
        val basic = arrayOf(
            "amp" to 38, "lt" to 60, "gt" to 62, "quot" to 34, "apos" to 39,
            "hellip" to 0x2026, "mdash" to 0x2014, "ndash" to 0x2013, "lsquo" to 0x2018, "rsquo" to 0x2019,
            "ldquo" to 0x201C, "rdquo" to 0x201D, "sbquo" to 0x201A, "bdquo" to 0x201E, "bull" to 0x2022,
            "lsaquo" to 0x2039, "rsaquo" to 0x203A, "trade" to 0x2122, "prime" to 0x2032, "Prime" to 0x2033,
            "ensp" to 0x2002, "emsp" to 0x2003, "thinsp" to 0x2009, "zwnj" to 0x200C, "zwj" to 0x200D,
            "lrm" to 0x200E, "rlm" to 0x200F, "dagger" to 0x2020, "Dagger" to 0x2021, "permil" to 0x2030,
            "oline" to 0x203E, "frasl" to 0x2044, "euro" to 0x20AC, "larr" to 0x2190, "uarr" to 0x2191,
            "rarr" to 0x2192, "darr" to 0x2193, "harr" to 0x2194, "lArr" to 0x21D0, "rArr" to 0x21D2,
            "hArr" to 0x21D4, "minus" to 0x2212, "lowast" to 0x2217, "infin" to 0x221E, "ne" to 0x2260,
            "le" to 0x2264, "ge" to 0x2265, "asymp" to 0x2248, "equiv" to 0x2261, "sum" to 0x2211,
            "prod" to 0x220F, "radic" to 0x221A, "part" to 0x2202, "nabla" to 0x2207, "isin" to 0x2208,
            "loz" to 0x25CA, "spades" to 0x2660, "clubs" to 0x2663, "hearts" to 0x2665, "diams" to 0x2666,
            "circ" to 0x02C6, "tilde" to 0x02DC, "OElig" to 0x152, "oelig" to 0x153, "Scaron" to 0x160,
            "scaron" to 0x161, "Yuml" to 0x178, "fnof" to 0x192, "sdot" to 0x22C5, "lang" to 0x2329,
            "rang" to 0x232A, "hyphen" to 0x2010, "nbsp" to 0xA0,
        )
        for ((k, v) in basic) named[k] = v
        // Latin-1 supplement 0xA1..0xFF in code point order.
        val latin1 = (
            "iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 " +
                "acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc " +
                "Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde " +
                "Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave " +
                "aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth " +
                "ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
            ).split(' ')
        for ((i, n) in latin1.withIndex()) named[n] = 0xA1 + i
        val greekUpper = "Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi Omicron Pi Rho"
            .split(' ')
        for ((i, n) in greekUpper.withIndex()) named[n] = 0x391 + i
        val greekUpper2 = "Sigma Tau Upsilon Phi Chi Psi Omega".split(' ')
        for ((i, n) in greekUpper2.withIndex()) named[n] = 0x3A3 + i
        val greekLower = ("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi " +
            "rho sigmaf sigma tau upsilon phi chi psi omega").split(' ')
        for ((i, n) in greekLower.withIndex()) named[n] = 0x3B1 + i
    }

    /** Windows-1252 mapping for numeric references 0x80..0x9F, as browsers do. */
    private val cp1252 = intArrayOf(
        0x20AC, 0x81, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152,
        0x8D, 0x017D, 0x8F, 0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122,
        0x0161, 0x203A, 0x0153, 0x9D, 0x017E, 0x0178,
    )

    /**
     * Parses a character reference starting at s[i] == '&' (not beyond [end]).
     * Returns `(codePoint shl 32) or indexAfter`, or -1 if there is no valid reference (the '&' is literal).
     */
    fun parseAt(s: String, i: Int, end: Int): Long {
        var j = i + 1
        if (j >= end) return -1
        if (s[j] == '#') {
            j++
            var hex = false
            if (j < end && (s[j] == 'x' || s[j] == 'X')) {
                hex = true
                j++
            }
            val ds = j
            var v = 0L
            // Consume every digit (browsers do); saturate so huge values can't overflow into valid code points.
            while (j < end) {
                val c = s[j]
                val d = when {
                    c in '0'..'9' -> c - '0'
                    hex && c in 'a'..'f' -> c - 'a' + 10
                    hex && c in 'A'..'F' -> c - 'A' + 10
                    else -> -1
                }
                if (d < 0) break
                if (v <= 0x10FFFF) v = v * (if (hex) 16 else 10) + d
                j++
            }
            if (j == ds) return -1
            if (j < end && s[j] == ';') j++
            var cp = if (v > 0x10FFFF) 0xFFFD else v.toInt()
            if (cp in 0x80..0x9F) cp = cp1252[cp - 0x80]
            if (cp == 0 || cp in 0xD800..0xDFFF) cp = 0xFFFD
            return (cp.toLong() shl 32) or j.toLong()
        }
        val ns = j
        while (j < end && j - ns < 12) {
            val c = s[j]
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') j++ else break
        }
        if (j == ns) return -1
        if (j < end && s[j] == ';') {
            val cp = named[s.substring(ns, j)] ?: return -1
            return (cp.toLong() shl 32) or (j + 1).toLong()
        }
        // Legacy references without ';' (`&nbsp `, `&amp `) as sloppy HTML-made EPUBs contain; never when an
        // alphanumeric or '=' follows (`?a=1&copy=2` in URLs stays literal).
        if (j < end && (s[j] == '=' || s[j].isLetterOrDigit())) return -1
        val cp = legacyNoSemicolon(s, ns, j)
        if (cp < 0) return -1
        return (cp.toLong() shl 32) or j.toLong()
    }

    private fun legacyNoSemicolon(s: String, a: Int, b: Int): Int {
        val len = b - a
        fun eq(w: String) = len == w.length && s.regionMatches(a, w, 0, len)
        return when {
            eq("nbsp") -> 0xA0
            eq("amp") -> 38
            eq("lt") -> 60
            eq("gt") -> 62
            eq("quot") -> 34
            eq("copy") -> 0xA9
            eq("reg") -> 0xAE
            else -> -1
        }
    }

    /** Decodes references in s[start, end). Unknown references stay literal. */
    fun decode(s: String, start: Int, end: Int): String {
        if (ampIn(s, start, end) < 0) return s.substring(start, end)
        val sb = StringBuilder(end - start)
        decodeInto(s, start, end, sb)
        return sb.toString()
    }

    fun decodeInto(s: String, start: Int, end: Int, sb: StringBuilder) {
        var i = start
        while (i < end) {
            val amp = ampIn(s, i, end)
            if (amp < 0) {
                sb.append(s, i, end)
                return
            }
            sb.append(s, i, amp)
            val r = parseAt(s, amp, end)
            if (r < 0) {
                sb.append('&')
                i = amp + 1
            } else {
                sb.appendCodePoint((r ushr 32).toInt())
                i = (r and 0xFFFFFFFFL).toInt()
            }
        }
    }

    /**
     * First '&' in s[from, end), or -1. Bounded on purpose: `s.indexOf('&', from)` runs on to the end of the whole
     * document when the range has none, which made decoding every `class` of a big single-file XHTML O(n²).
     */
    private fun ampIn(s: String, from: Int, end: Int): Int {
        for (k in from until end) if (s[k] == '&') return k
        return -1
    }

    /** Decodes references and collapses whitespace runs (incl. NBSP) to single spaces, trimmed. */
    fun decodeCollapsed(s: String, start: Int = 0, end: Int = s.length): String =
        collapse(decode(s, start, end))

    /** Collapses whitespace (incl. NBSP, U+3000) to single spaces and trims. */
    fun collapse(t: String): String {
        val sb = StringBuilder(t.length)
        var space = false
        for (c in t) {
            if (c <= ' ' || c == '\u00A0' || c == '\u3000' || c == '\u2028' || c == '\u2029') {
                if (sb.isNotEmpty()) space = true
            } else {
                if (space) sb.append(' ')
                space = false
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
