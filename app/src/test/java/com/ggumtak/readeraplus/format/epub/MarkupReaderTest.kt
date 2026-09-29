package com.ggumtak.readeraplus.format.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupReaderTest {
    /** Flattens tokens into a readable trace: <tag a=v>, </tag>, "text". */
    private fun trace(src: String): List<String> {
        val r = MarkupReader(src)
        val out = ArrayList<String>()
        var guard = 0
        while (true) {
            guard++
            if (guard > 100000) throw AssertionError("tokenizer does not progress")
            when (r.next()) {
                MarkupReader.EOF -> return out
                MarkupReader.TEXT -> out.add("\"" + r.text() + "\"")
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
    fun basicTagsAndAttributes() {
        assertEquals(
            listOf("<p class=a id=x>", "\"hi \"", "<b>", "\"there\"", "</b>", "</p>"),
            trace("<p class=\"a\" id='x'>hi <b>there</b></p>"),
        )
    }

    @Test
    fun unquotedAndValuelessAttributes() {
        assertEquals(listOf("<td nowrap= width=50 align=left>"), trace("<td nowrap width=50 align=left>"))
        assertEquals(listOf("<a href=ch1.xhtml#x>"), trace("<a href=ch1.xhtml#x>"))
        assertEquals(listOf("<img src=a/b.png/>"), trace("<img src=a/b.png/>"))
    }

    @Test
    fun selfClosingUppercaseAndPrefixes() {
        assertEquals(listOf("<br/>", "<br>", "<hr/>"), trace("<br/><BR><hr />"))
        assertEquals(listOf("<title>", "\"x\"", "</title>"), trace("<dc:title>x</dc:title>"))
        val r = MarkupReader("<nav epub:type=\"toc\" xlink:href=\"a.png\">")
        r.next()
        assertEquals("nav", r.name)
        assertEquals("toc", r.attr("epub:type"))
        assertEquals("toc", r.attr("type")) // local-name fallback
        assertEquals("a.png", r.attr("href"))
        assertNull(r.attr("missing"))
        assertTrue(r.hasAttr("EPUB:TYPE"))
    }

    @Test
    fun commentsDoctypeProcessingInstructionsAndCdata() {
        val src = "<?xml version=\"1.0\"?><!DOCTYPE html [ <!ENTITY x \"y\"> ]><!-- c <p> -->" +
            "<p><![CDATA[a < b & c]]></p>"
        assertEquals(listOf("<p>", "\"a < b & c\"", "</p>"), trace(src))
    }

    @Test
    fun rawTextElements() {
        val src = "<style>p > a { color: red } </b></style><script>if (a<b) x();</script><p>t</p>"
        assertEquals(
            listOf("<style>", "\"p > a { color: red } </b>\"", "</style>", "<script>", "\"if (a<b) x();\"", "</script>", "<p>", "\"t\"", "</p>"),
            trace(src),
        )
    }

    @Test
    fun entities() {
        val src = "A&amp;B &lt;x&gt; &nbsp;&hellip; &mdash; &ndash; &lsquo;&rsquo;&ldquo;&rdquo; &middot; &bull; " +
            "&laquo; &raquo; &times; &copy; &reg; &trade; &deg; &prime; &#44032; &#xAC00;"
        assertEquals(
            "A&B <x> \u00A0… — – ‘’“” · • « » × © ® ™ ° ′ 가 가",
            Entities.decode(src, 0, src.length),
        )
    }

    @Test
    fun entityEdgeCases() {
        fun d(s: String) = Entities.decode(s, 0, s.length)
        assertEquals("&unknown; & &", d("&unknown; & &amp")) // legacy name without ';' decodes like browsers
        assertEquals("a\u00A0 b & c < &ltd", d("a&nbsp b &amp c &lt &ltd"))
        assertEquals("x=1&copy=2 &ampx", d("x=1&copy=2 &ampx")) // followed by '=' or alnum: literal
        assertEquals("\uFFFD|\uFFFD;", d("&#xFFFFFFFF;|&#99999999999999;;"))
        assertEquals("�", d("&#0;"))
        assertEquals("�", d("&#xD800;"))
        assertEquals("�", d("&#x110000;"))
        assertEquals("–", d("&#150;")) // windows-1252 remap
        assertEquals("😀", d("&#x1F600;"))
        assertEquals("ÿ÷×é", d("&yuml;&divide;&times;&eacute;"))
        assertEquals("αΩ", d("&alpha;&Omega;"))
        assertEquals("&#;&#x;", d("&#;&#x;"))
        assertEquals("a", d("&#97"))
    }

    @Test
    fun malformedInputNeverThrowsAndProgresses() {
        val bad = listOf(
            "<", "<<", "<p", "<p class=\"unterminated>text", "</", "</>", "<!--", "<!-- x", "<![CDATA[x", "<!DOCTYPE",
            "<?xml", "a < b > c", "<p>x</p></div></span>", "<style>unclosed", "<a href=>", "<a =x>", "<p\"x\">",
            "<p/ >", "&", "&#", "&#x", "<br/", "text<", "<1abc>", "< p>", "<p a='1' b=\"2\" c d=e/>",
        )
        for (s in bad) trace(s)
        assertEquals(listOf("\"a \"", "\"< b > c\""), trace("a < b > c"))
        assertEquals(listOf("<p class=unterminated>", "\"text\""), trace("<p class=\"unterminated>text"))
        // an unclosed tag ends at the next '<'
        assertEquals(listOf("<p x=1>", "<b>", "\"t\""), trace("<p x=1<b>t"))
    }

    @Test
    fun textRangesPointIntoSource() {
        val src = "<p>abc</p>"
        val r = MarkupReader(src)
        r.next()
        assertEquals(MarkupReader.TEXT, r.next())
        assertEquals("abc", src.substring(r.textStart, r.textEnd))
        assertFalse(r.textRaw)
    }

    @Test
    fun collapse() {
        assertEquals("a b c", Entities.collapse("  a \n\t b\u00A0\u3000c  "))
        assertEquals("", Entities.collapse(" \n "))
    }
}
