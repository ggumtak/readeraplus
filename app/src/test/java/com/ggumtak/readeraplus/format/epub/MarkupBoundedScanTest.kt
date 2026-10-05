package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.format.epub.EpubTestUtil.SENTENCES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * [Entities.decode] searches for '&' only inside the decoded range, and [MarkupReader] remembers its last "?>"
 * search (both scanned to the end of the document per token before: O(n²) on big single-file XHTML). The output
 * must be exactly what it was: checked against verbatim copies of the old decoder and the old reader (c184879,
 * at the end of this file, kept only here).
 */
class MarkupBoundedScanTest {
    private fun check(s: String, a: Int, b: Int) {
        val old = oldDecode(s, a, b)
        val now = Entities.decode(s, a, b)
        if (old != now) fail("decode [$a, $b) of \"$s\": was \"$old\", now \"$now\"")
        val sb = StringBuilder("»")
        Entities.decodeInto(s, a, b, sb)
        if (sb.toString() != "»$old") fail("decodeInto [$a, $b) of \"$s\": was \"$old\", now \"${sb.substring(1)}\"")
    }

    // Entity-shaped pieces, broken ones included: no ';', '&' at the end, numbers that overflow, names cut short.
    private val bits = listOf(
        "&", "&&", "&;", "&amp;", "&amp", "&ampx", "&nbsp", "&nbsp;", "&lt", "&gt;", "&copy", "&copy=", "&reg",
        "&quot", "&eacute;", "&hellip;", "&Omega;", "&unknown;", "&abcdefghijklmn;", "&#", "&#;", "&#x", "&#X",
        "&#x;", "&#97", "&#97;", "&#x1F600;", "&#xd800;", "&#0;", "&#150;", "&#99999999999;", "&#xFFFFFFFFFF",
        ";", "#", "x", "X", "9", "0", "f", "a", "Z", "=", " ", "\n", "가", "\u00A0", "\"", "'", "<", ">", "/",
        "?a=1&b=2", "\uD83D\uDE00",
    )

    @Test
    fun decodeMatchesTheOldDecoderOnEveryRangeOfRandomStrings() {
        val rnd = Random(20261005)
        var ranges = 0
        repeat(1500) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(0, 24)) { sb.append(bits[rnd.nextInt(bits.size)]) }
            val s = sb.toString()
            // every [a, b): references cut by the end, '&' as the last char, ranges with the next '&' far after b
            for (a in 0..s.length) for (b in a..s.length) {
                check(s, a, b)
                ranges++
            }
        }
        assertTrue(ranges > 500_000)
    }

    @Test
    fun decodeMatchesTheOldDecoderOnRealDocuments() {
        val docs = listOf(
            EpubTestUtil.converterItem(60_000),
            EpubTestUtil.html(
                "<h1 class=\"t&amp;c\" id=\"c1\">Tom &amp; Jerry &mdash; 1화</h1>\n" +
                    "<p class='p1' title=\"&quot;인용&quot; &#44032;&#xAC00;\">${SENTENCES[0]}&nbsp;${SENTENCES[1]}</p>\n" +
                    "<p><a href=\"notes.xhtml?a=1&b=2&copy=3#n1\">[1]</a> &amp &lt &copy &#97 &unknown; & &#;</p>\n" +
                    "<p data-x=\"&\" data-y=\"&amp\" data-z='a&#x' style=\"font-family: &quot;Noto&quot;\">끝&</p>\n" +
                    "<img src=\"../Images/a&amp;b.png\" alt=\"그림 &lt;1&gt;\"/><p class=unquoted&amp;x>x</p>",
                "<style>p { margin: 0 } /* & */</style>",
            ),
        )
        val rnd = Random(3)
        for (doc in docs) {
            // text tokens, as MarkupReader.text() decodes them
            val r = MarkupReader(doc)
            while (true) {
                val t = r.next()
                if (t == MarkupReader.EOF) break
                if (t == MarkupReader.TEXT && !r.textRaw) check(doc, r.textStart, r.textEnd)
            }
            // attribute values, as attrValue decodes them: every quoted run after a '='
            var eq = doc.indexOf('=')
            while (eq >= 0) {
                val q = if (eq + 1 < doc.length) doc[eq + 1] else ' '
                if (q == '"' || q == '\'') {
                    val e = doc.indexOf(q, eq + 2)
                    if (e > 0) check(doc, eq + 2, e)
                }
                eq = doc.indexOf('=', eq + 1)
            }
            // and any range at all
            repeat(20_000) {
                val a = rnd.nextInt(doc.length + 1)
                check(doc, a, minOf(doc.length, a + rnd.nextInt(80)))
            }
        }
    }

    // ================================================================ processing instructions

    private fun textTok(t: String, raw: Boolean, a: Int, b: Int) = (if (raw) "raw" else "") + "\"" + t + "\"@" + a + ".." + b

    private fun startTok(name: String, attrs: List<String>, selfClosing: Boolean) =
        "<" + name + attrs.joinToString("") { " $it" } + (if (selfClosing) "/" else "") + ">"

    /** Token trace with source positions and decoded attributes. */
    private fun trace(r: MarkupReader): List<String> {
        val out = ArrayList<String>()
        while (true) {
            when (r.next()) {
                MarkupReader.EOF -> return out
                MarkupReader.TEXT -> out.add(textTok(r.text(), r.textRaw, r.textStart, r.textEnd))
                MarkupReader.START ->
                    out.add(startTok(r.name, List(r.attributeCount) { r.attrName(it) + "=" + r.attrValue(it) }, r.selfClosing))
                MarkupReader.END -> out.add("</" + r.name + ">")
            }
        }
    }

    /** The same trace from the old reader. */
    private fun trace(r: OldMarkupReader): List<String> {
        val out = ArrayList<String>()
        while (true) {
            when (r.next()) {
                OldMarkupReader.EOF -> return out
                OldMarkupReader.TEXT -> out.add(textTok(r.text(), r.textRaw, r.textStart, r.textEnd))
                OldMarkupReader.START ->
                    out.add(startTok(r.name, List(r.attributeCount) { r.attrName(it) + "=" + r.attrValue(it) }, r.selfClosing))
                OldMarkupReader.END -> out.add("</" + r.name + ">")
            }
        }
    }

    /** Trace of s[a, b) from the new reader, after checking that the old reader gives the same. */
    private fun traceBoth(s: String, a: Int = 0, b: Int = s.length): List<String> {
        val now = trace(MarkupReader(s, a, b))
        assertEquals("[$a, $b) of \"$s\"", trace(OldMarkupReader(s, a, b)), now)
        return now
    }

    @Test
    fun processingInstructionsStillRunToTheNextQuestionMarkGreaterThan() {
        // "<?" without its own "?>" runs to the next "?>" anywhere after it, else to its first '>'
        assertEquals(listOf("\"y\"@10..11"), traceBoth("<?a>x<?b?>y"))
        assertEquals(listOf("\"x\"@4..5", "\"y\"@9..10"), traceBoth("<?a>x<?b>y"))
        assertEquals(listOf("\"x\"@5..6", "\"z\"@16..17"), traceBoth("<?a?>x<?b>y<?c?>z"))
        // several in one next() call: a later "?>" swallows the ones between; with none, each ends at its own '>'
        // (the 2nd reuses the 1st search's miss)
        assertEquals(listOf("\"x\"@13..14"), traceBoth("<?a><?b><?c?>x"))
        assertEquals(listOf("\"x\"@8..9"), traceBoth("<?a><?b>x"))
        // the "?>" lies past the end of a sub-range: each "<?" ends at its own '>' inside the range
        assertEquals(listOf("\"x\"@4..5", "\"y\"@9..10"), traceBoth("<?a>x<?b>y?>z", 0, 10))
        // Word's namespace declaration ends at its own '>' when no "?>" follows
        val word = "<p><?xml:namespace prefix = o ns=\"urn:x\" /><o:p></o:p>t</p><?xml:namespace prefix = v />"
        val t = word.indexOf("t</p>")
        assertEquals(listOf("<p>", "<p>", "</p>", "\"t\"@$t..${t + 1}", "</p>"), traceBoth(word))
    }

    @Test
    fun tokensMatchTheOldReaderOnRandomDocumentsAndSubRanges() {
        val pieces = listOf(
            "<?a>", "<?b?>", "<?", "?>", "?", ">", "<", "<?xml:namespace prefix = o ns=\"urn:x\" />",
            "<?xml version='1.0'?>", "<p class=\"c\">", "<p class=\"a&amp;b\" id=x>", "</p>", "<o:p></o:p>", "글", " ",
            "&amp;", "&", "<!-- ?> -->", "<!--", "-->", "<![CDATA[?>]]>", "<![CDATA[", "]]>", "<!DOCTYPE html>",
            "<!DOCTYPE x [<!ENTITY a 'b'>]>", "<style>?></style>", "<script>", "</script>", "<a title='?>'>", "</", "<br/>",
        )
        val rnd = Random(17)
        repeat(4000) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(1, 40)) { sb.append(pieces[rnd.nextInt(pieces.size)]) }
            val doc = sb.toString()
            traceBoth(doc)
            repeat(4) {
                val a = rnd.nextInt(doc.length + 1)
                traceBoth(doc, a, rnd.nextInt(a, doc.length + 1))
            }
        }
    }
}

// ==================================================================== the old code (c184879), reference only

private fun oldDecode(s: String, start: Int, end: Int): String {
    val amp = s.indexOf('&', start)
    if (amp < 0 || amp >= end) return s.substring(start, end)
    val sb = StringBuilder(end - start)
    oldDecodeInto(s, start, end, sb)
    return sb.toString()
}

private fun oldDecodeInto(s: String, start: Int, end: Int, sb: StringBuilder) {
    var i = start
    while (i < end) {
        val amp = s.indexOf('&', i)
        if (amp < 0 || amp >= end) {
            sb.append(s, i, end)
            return
        }
        sb.append(s, i, amp)
        val r = Entities.parseAt(s, amp, end) // unchanged: always bounded by end
        if (r < 0) {
            sb.append('&')
            i = amp + 1
        } else {
            sb.appendCodePoint((r ushr 32).toInt())
            i = (r and 0xFFFFFFFFL).toInt()
        }
    }
}

/** The old MarkupReader, verbatim except its name and the decoder above: searches "?>" afresh for every "<?". */
private class OldMarkupReader(private val s: String, start: Int = 0, end: Int = s.length) {
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
                    val e = s.indexOf("?>", pos + 2)
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
        return oldDecode(s, vs, attrPos[k + 3])
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
    fun text(): String = if (textRaw) s.substring(textStart, textEnd) else oldDecode(s, textStart, textEnd)

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
