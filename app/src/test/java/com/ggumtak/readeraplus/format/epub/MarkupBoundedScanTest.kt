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
 * must be exactly what it was: checked against a copy of the old decoder, and against a reader that searches
 * afresh for every processing instruction as the old one did.
 */
class MarkupBoundedScanTest {
    // ================================================================ the old decoder (c184879), reference only

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

    private val piFrom by lazy { MarkupReader::class.java.getDeclaredField("piFrom").apply { isAccessible = true } }

    /** Token trace with source positions; [fresh] forgets the last "?>" search before every token, like the old reader. */
    private fun trace(src: String, fresh: Boolean = false): List<String> {
        val r = MarkupReader(src)
        val out = ArrayList<String>()
        while (true) {
            if (fresh) piFrom.setInt(r, Int.MAX_VALUE)
            when (r.next()) {
                MarkupReader.EOF -> return out
                MarkupReader.TEXT -> out.add("\"" + r.text() + "\"@" + r.textStart + ".." + r.textEnd)
                MarkupReader.START -> {
                    val sb = StringBuilder("<").append(r.name)
                    for (i in 0 until r.attributeCount) sb.append(' ').append(r.attrName(i)).append('=').append(r.attrValue(i))
                    if (r.selfClosing) sb.append('/')
                    out.add(sb.append('>').toString())
                }
                MarkupReader.END -> out.add("</" + r.name + ">")
            }
        }
    }

    @Test
    fun processingInstructionsStillRunToTheNextQuestionMarkGreaterThan() {
        // "<?" without its own "?>" runs to the next "?>" anywhere after it, else to its first '>'
        assertEquals(listOf("\"y\"@10..11"), trace("<?a>x<?b?>y"))
        assertEquals(listOf("\"x\"@4..5", "\"y\"@9..10"), trace("<?a>x<?b>y"))
        assertEquals(listOf("\"x\"@5..6", "\"z\"@16..17"), trace("<?a?>x<?b>y<?c?>z"))
        // Word's namespace declaration ends at its own '>' when no "?>" follows
        val word = "<p><?xml:namespace prefix = o ns=\"urn:x\" /><o:p></o:p>t</p><?xml:namespace prefix = v />"
        val t = word.indexOf("t</p>")
        assertEquals(listOf("<p>", "<p>", "</p>", "\"t\"@$t..${t + 1}", "</p>"), trace(word))
    }

    @Test
    fun processingInstructionsTokenizeAsWithAFreshSearchEachTime() {
        val pieces = listOf(
            "<?a>", "<?b?>", "<?", "?>", "?", ">", "<?xml:namespace prefix = o ns=\"urn:x\" />", "<p class=\"c\">",
            "</p>", "<o:p></o:p>", "글", " ", "<!-- ?> -->", "<![CDATA[?>]]>", "<style>?></style>", "<a title='?>'>",
        )
        val rnd = Random(17)
        repeat(4000) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(1, 40)) { sb.append(pieces[rnd.nextInt(pieces.size)]) }
            val doc = sb.toString()
            assertEquals(doc, trace(doc, fresh = true), trace(doc))
        }
    }
}
