package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.format.epub.EpubTestUtil.SENTENCES
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.checkInvariants
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.convert
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class XhtmlFuzzPerfTest {
    private val pieces = listOf(
        "<p>", "</p>", "<div>", "</div>", "<br/>", "<br>", "</br>", "<hr/>", "<b>", "</b>", "<i>", "</i>",
        "<span class=\"a b\">", "</span>", "<h1>", "</h1>", "<h3 id=\"h\">", "</h3>", "<ul>", "</ul>", "<ol start=\"5\">",
        "</ol>", "<li>", "</li>", "<table>", "<tr>", "<td>", "</td>", "</tr>", "</table>", "<pre>", "</pre>",
        "<img src=\"../i.png\"/>", "<svg><image xlink:href=\"c.jpg\"/></svg>", "<a href=\"#x\">", "</a>",
        "<a name=\"n\"/>", "<blockquote>", "</blockquote>", "<sup>", "</sup>", "<ruby>", "<rt>", "</rt>", "</ruby>",
        "<style>.a{text-align:center} p+p{text-indent:0}</style>", "<p style=\"display:none\">", "<!-- c -->",
        "<![CDATA[ x < y ]]>", "&nbsp;", "&amp;", "&#44032;", "&bogus;", "&", "<", ">", " ", "\n", "\t", "\u3000",
        "\u00A0", "\uFFFC", "가나다", "word", "“인용”", "<p", "<div class=", "\"", "'", "</", "<!DOCTYPE x>", "<?pi?>",
        "<head><title>t</title></head>", "<body>", "</body>", "<script>x<y</script>", "<dl><dt>t</dt><dd>d</dd></dl>",
        "<p hidden>", "<span epub:type=\"pagebreak\">3</span>", "<center>", "</center>", "<wbr/>",
    )

    @Test
    fun randomMarkupNeverThrowsAndKeepsInvariants() {
        val rnd = Random(20260929)
        repeat(600) { iter ->
            val sb = StringBuilder()
            val n = rnd.nextInt(1, 120)
            repeat(n) {
                if (rnd.nextInt(5) == 0) sb.append(SENTENCES[rnd.nextInt(SENTENCES.size)]) else sb.append(pieces[rnd.nextInt(pieces.size)])
            }
            val doc = sb.toString()
            try {
                convert(doc, publisherStyles = iter % 2 == 0)
            } catch (t: Throwable) {
                throw AssertionError("iteration $iter failed on: $doc", t)
            }
        }
    }

    @Test
    fun truncatedAndMutatedDocumentsNeverThrow() {
        val base = sampleXhtml(4000)
        val rnd = Random(7)
        repeat(300) { iter ->
            val cut = rnd.nextInt(base.length)
            val mutated = StringBuilder(base.substring(0, cut))
            repeat(rnd.nextInt(0, 6)) {
                if (mutated.isNotEmpty()) {
                    val at = rnd.nextInt(mutated.length)
                    when (rnd.nextInt(3)) {
                        0 -> mutated.deleteCharAt(at)
                        1 -> mutated.insert(at, "<>&\"'/"[rnd.nextInt(6)])
                        else -> mutated.setCharAt(at, (rnd.nextInt(0x20, 0xD7A3)).toChar())
                    }
                }
            }
            val doc = mutated.toString()
            try {
                convert(doc, publisherStyles = iter % 3 != 0)
            } catch (t: Throwable) {
                throw AssertionError("iteration $iter failed", t)
            }
        }
    }

    @Test
    fun randomCssNeverThrows() {
        val rnd = Random(11)
        val bits = listOf(
            "p", ".a", "#i", "div > p", "h1 + p", "*", "{", "}", ";", ":", "text-align", "center", "display", "none",
            "margin", "1em", "0", "@media", "@import", "url(x)", "\"", "/*", "*/", ",", " ", "!important", "font-size",
            "120%", "(", ")", "[", "]", "::before", ":first-child",
        )
        repeat(500) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(1, 60)) { sb.append(bits[rnd.nextInt(bits.size)]) }
            val css = sb.toString()
            CssParser.parse(css)
            CssParser.parseInline(css)
            convert("<style>$css</style><div class=\"a\"><p id=\"i\">x</p><h1>h</h1><p>y</p></div>")
        }
    }

    /** Builds an XHTML chapter of roughly [targetChars] chars of markup with typical publisher structure. */
    private fun sampleXhtml(targetChars: Int): String {
        val sb = StringBuilder(targetChars + 4096)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n")
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>성능 시험</title>")
        sb.append("<style>p { text-indent: 1em; margin: 0 } .dialog { font-style: normal } .center { text-align: center } ")
        sb.append("h2 + p { text-indent: 0 } .chapter p.first { margin-top: 2em } span.em { font-weight: bold }</style>")
        sb.append("</head><body><div class=\"chapter\">\n<h2 id=\"c1\" class=\"title\">제1장 조용한 항구</h2>\n")
        var i = 0
        var para = 0
        while (sb.length < targetChars) {
            val cls = when (para % 7) {
                0 -> " class=\"first\""
                3 -> " class=\"dialog\""
                else -> ""
            }
            sb.append("<p").append(cls).append(" id=\"p").append(para).append("\">")
            val count = 2 + para % 4
            for (k in 0 until count) {
                val s = SENTENCES[(i++) % SENTENCES.size]
                when ((i + k) % 9) {
                    0 -> sb.append("<span class=\"em\">").append(s).append("</span>")
                    4 -> sb.append(s.substring(0, s.length / 2)).append("<i>").append(s.substring(s.length / 2)).append("</i>")
                    6 -> sb.append(s).append("<a href=\"#fn").append(para).append("\"><sup>").append(para).append("</sup></a>")
                    else -> sb.append(s)
                }
                sb.append(if (k % 3 == 2) "&nbsp;" else " ")
            }
            sb.append("</p>\n")
            if (para % 40 == 39) sb.append("<p class=\"center\">＊ ＊ ＊</p>\n<h2>다음 이야기 ").append(para).append("</h2>\n")
            if (para % 25 == 24) sb.append("<p><br/></p>\n")
            para++
        }
        sb.append("</div></body></html>\n")
        return sb.toString()
    }

    @Test
    fun convert300KbUnder150ms() {
        val doc = sampleXhtml(300 * 1024)
        assertTrue(doc.length >= 300 * 1024)
        // warm-up (JIT)
        repeat(8) { XhtmlConverter(true, null).convert(doc, "OEBPS/Text/ch.xhtml") }
        var best = Long.MAX_VALUE
        repeat(5) {
            val t0 = System.nanoTime()
            val c = XhtmlConverter(true, null).convert(doc, "OEBPS/Text/ch.xhtml")
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms < best) best = ms
            if (it == 0) {
                checkInvariants(c)
                assertTrue(c.blocks.size > 500)
            }
        }
        println("XHTML 300 KB conversion: best $best ms")
        assertTrue("300 KB XHTML took $best ms (limit 150)", best < 150)
    }

    @Test
    fun tokenizerThroughput() {
        val doc = sampleXhtml(1024 * 1024)
        repeat(3) {
            val r = MarkupReader(doc)
            while (r.next() != MarkupReader.EOF) Unit
        }
        val t0 = System.nanoTime()
        val r = MarkupReader(doc)
        var tokens = 0
        while (r.next() != MarkupReader.EOF) tokens++
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("tokenizer: 1 MB, $tokens tokens in $ms ms")
        assertTrue("tokenizer too slow: $ms ms", ms < 200)
    }

    private fun bestMs(runs: Int, block: () -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(runs) {
            val t0 = System.nanoTime()
            block()
            best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
        }
        return best
    }

    /**
     * A whole-book item as converters write it (`<p class=…>` everywhere, no '&'). Decoding each attribute used to
     * search for '&' up to the end of the document: O(n²), 1.5/3 MB took 144/773 ms on the JVM (now 10/18 ms).
     * Doubling the item must about double the time (the floor keeps a fast machine's few ms from failing the ratio).
     */
    @Test
    fun bigConverterItemConvertsInLinearTime() {
        val half = EpubTestUtil.converterItem(1_500_000)
        val full = EpubTestUtil.converterItem(3_000_000)
        fun convert(doc: String) = XhtmlConverter(true, null).convert(doc, "OEBPS/Text/book.xhtml")
        repeat(3) { convert(half) } // warm-up (JIT)
        val tHalf = bestMs(3) { convert(half) }
        val tFull = bestMs(3) { convert(full) }
        println("converter item: 1.5 MB $tHalf ms, 3 MB $tFull ms")
        assertTrue("3 MB item took $tFull ms (limit 2000)", tFull < 2000)
        assertTrue("1.5 MB $tHalf ms, 3 MB $tFull ms: not linear", tFull < 3 * maxOf(tHalf, 20L))
        val c = convert(full)
        checkInvariants(c)
        assertTrue(c.anchors.containsKey("toc_9"))
    }

    /**
     * Word-made HTML repeats `<?xml:namespace … />` with no "?>" after it: each one used to search to the end
     * (1.5/3 MB took 153/619 ms on the JVM, now a few ms).
     */
    @Test
    fun repeatedOpenProcessingInstructionsTokenizeInLinearTime() {
        val half = EpubTestUtil.converterItem(1_500_000, wordPis = true)
        val full = EpubTestUtil.converterItem(3_000_000, wordPis = true)
        fun tokenize(doc: String) {
            val r = MarkupReader(doc)
            while (r.next() != MarkupReader.EOF) Unit
        }
        repeat(3) { tokenize(half) }
        val tHalf = bestMs(3) { tokenize(half) }
        val tFull = bestMs(3) { tokenize(full) }
        println("Word processing instructions: 1.5 MB $tHalf ms, 3 MB $tFull ms")
        assertTrue("3 MB item took $tFull ms (limit 1000)", tFull < 1000)
        assertTrue("1.5 MB $tHalf ms, 3 MB $tFull ms: not linear", tFull < 3 * maxOf(tHalf, 10L))
    }
}
