package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.Entry
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.SENTENCES
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.checkInvariants
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.container
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.png
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.text
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.writeEpub
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/** Splitting of oversized spine items into sections (review finding: whole-book XHTML laid out in one piece). */
class EpubSplitTest {
    // ================================================================ helpers

    private fun para(rnd: Random, sentences: Int): String {
        val sb = StringBuilder()
        repeat(sentences) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(SENTENCES[rnd.nextInt(SENTENCES.size)])
        }
        return sb.toString()
    }

    /** A converter-style whole book: [chapters] chapters of roughly [charsPerChapter] chars, anchored `chN`. */
    private fun bookBody(chapters: Int, charsPerChapter: Int, seed: Long = 1): String {
        val rnd = Random(seed)
        val sb = StringBuilder()
        for (c in 1..chapters) {
            sb.append("<h2 id=\"ch$c\">제${c}화 바닷길</h2>\n")
            var n = 0
            val want = charsPerChapter / 2 + rnd.nextInt(charsPerChapter)
            while (n < want) {
                val p = para(rnd, 1 + rnd.nextInt(4))
                sb.append("<p>").append(p).append("</p>\n")
                n += p.length + 1
            }
            if (c % 7 == 0) sb.append("<p>주석 참조 <a href=\"#note$c\">[$c]</a></p>\n<p id=\"note$c\">주석 $c 내용.</p>\n")
        }
        return sb.toString()
    }

    private fun anchorsFor(c: SectionContent, parts: Int, scan: EpubSplit.Scan): Pair<IntArray, IntArray> {
        val assigned = EpubSplit.assign(scan, parts)
        val offs = ArrayList<Int>()
        val ps = ArrayList<Int>()
        for ((id, p) in assigned) {
            val v = c.anchors[id] ?: continue
            offs.add(v)
            ps.add(p)
        }
        return offs.toIntArray() to ps.toIntArray()
    }

    /** Structural checks of a cut plus the slices it produces; returns the slices. */
    private fun checkCuts(c: SectionContent, cuts: EpubSplit.Cuts, parts: Int): List<SectionContent> {
        assertEquals(parts, cuts.size)
        assertEquals(0, cuts.starts[0])
        assertEquals(c.length, cuts.ends[parts - 1])
        for (k in 0 until parts) {
            assertTrue("part $k range", cuts.ends[k] >= cuts.starts[k])
            if (k > 0) {
                val gap = cuts.starts[k] - cuts.ends[k - 1]
                assertTrue("gap before part $k: $gap", gap == 0 || gap == 1)
                if (gap == 1) {
                    val ch = c.text[cuts.ends[k - 1]]
                    assertTrue("dropped char before part $k is a separator: '$ch'", ch == '\n' || ch == ' ')
                }
            }
        }
        val slices = (0 until parts).map { EpubSplit.slice(c, cuts, it, null) }
        val sb = StringBuilder()
        for ((k, s) in slices.withIndex()) {
            checkInvariants(s)
            if (k > 0) sb.append(c.text, cuts.ends[k - 1], cuts.starts[k])
            sb.append(s.text)
        }
        assertEquals("parts + dropped chars rebuild the item", c.text, sb.toString())
        // every anchor is found in the part `locate` names, at the same place
        for ((id, v) in c.anchors) {
            val k = cuts.locate(v)
            val local = cuts.local(k, v)
            assertEquals("anchor $id", local, slices[k].anchors[id])
            assertEquals(v, cuts.starts[k] + local)
        }
        // styles survive the cut
        for ((k, s) in slices.withIndex()) {
            var i = 0
            while (i < s.length) {
                assertEquals(c.styleAt(cuts.starts[k] + i), s.styleAt(i))
                i += 97
            }
        }
        return slices
    }

    // ================================================================ pure logic

    @Test
    fun scanEstimatesTextAndFindsAnchors() {
        val body = bookBody(12, 3000) + "<p><a name=\"old\"></a>옛 앵커 &amp; 엔티티 &nbsp; 끝</p>"
        val xhtml = EpubTestUtil.html(body, "<style>p { margin: 0 }</style><script>var x = '<p>no</p>';</script>")
        val c = EpubTestUtil.convert(xhtml)
        val scan = EpubSplit.scan(xhtml, setOf("ch1", "ch5", "ch12", "old", "absent"))
        val ratio = scan.chars.toDouble() / c.length
        assertTrue("estimate $ratio", ratio in 0.95..1.05)
        assertEquals(setOf("ch1", "ch5", "ch12", "old"), scan.anchors.keys)
        assertTrue(scan.anchors["ch1"]!! < scan.anchors["ch5"]!!)
        assertTrue(scan.anchors["ch5"]!! < scan.anchors["ch12"]!!)
        assertTrue(scan.anchors["ch12"]!! < scan.anchors["old"]!!)
        // proportional positions track the converted offsets
        for (id in listOf("ch5", "ch12")) {
            val est = scan.anchors[id]!!.toDouble() / scan.chars
            val real = c.anchors[id]!!.toDouble() / c.length
            assertTrue("$id $est vs $real", kotlin.math.abs(est - real) < 0.02)
        }
        assertEquals(0, EpubSplit.scan(EpubTestUtil.html(""), emptySet()).chars)
    }

    @Test
    fun partCounts() {
        assertEquals(1, EpubSplit.partsFor(0))
        assertEquals(1, EpubSplit.partsFor(EpubSplit.SPLIT_MIN_CHARS))
        assertEquals(3, EpubSplit.partsFor(EpubSplit.SPLIT_MIN_CHARS + 1))
        assertEquals(25, EpubSplit.partsFor(1_000_000))
        assertEquals(EpubSplit.MAX_PARTS, EpubSplit.partsFor(Int.MAX_VALUE))
        val a = EpubSplit.assign(EpubSplit.Scan(1000, mapOf("a" to 0, "b" to 499, "c" to 500, "d" to 999, "e" to 5000)), 2)
        assertEquals(mapOf("a" to 0, "b" to 0, "c" to 1, "d" to 1, "e" to 1), a)
    }

    @Test
    fun chaptersStartTheirParts() {
        val xhtml = EpubTestUtil.html(bookBody(40, 6000))
        val c = EpubTestUtil.convert(xhtml)
        val ids = (1..40).map { "ch$it" }.toSet()
        val scan = EpubSplit.scan(xhtml, ids)
        val parts = EpubSplit.partsFor(scan.chars)
        assertTrue("parts $parts", parts in 4..12)
        val (offs, ps) = anchorsFor(c, parts, scan)
        val cuts = EpubSplit.cut(c, parts, offs, ps)
        val slices = checkCuts(c, cuts, parts)
        val assigned = EpubSplit.assign(scan, parts)
        for (id in ids) {
            val p = assigned[id]!!
            assertEquals("$id stays in its part", p, cuts.locate(c.anchors[id]!!))
        }
        for (k in 1 until parts) {
            // a part that holds chapters starts at its first chapter heading
            val first = assigned.filterValues { it == k }.keys.minByOrNull { c.anchors[it]!! } ?: continue
            assertEquals(0, slices[k].anchors[first])
            assertTrue(slices[k].text.startsWith("제${first.removePrefix("ch")}화"))
            assertTrue((slices[k].blocks[0] as ParagraphBlock).style.headingLevel > 0)
        }
        for (s in slices) assertTrue("part size ${s.length}", s.length < EpubSplit.PART_TARGET_CHARS * 2)
    }

    @Test
    fun headingRunsAboveAChapterStayTogether() {
        val rnd = Random(3)
        val sb = StringBuilder()
        for (part in 1..4) {
            sb.append("<h1>제${part}부</h1>\n")
            for (c in 1..5) {
                sb.append("<h2 id=\"p${part}c$c\">제${c}장</h2>\n")
                repeat(60) { sb.append("<p>").append(para(rnd, 2)).append("</p>\n") }
            }
        }
        val xhtml = EpubTestUtil.html(sb.toString())
        val c = EpubTestUtil.convert(xhtml)
        // only the first chapter of each part is in the TOC, assigned one part each
        val ids = (1..4).map { "p${it}c1" }
        val cuts = EpubSplit.cut(c, 4, IntArray(4) { c.anchors[ids[it]]!! }, IntArray(4) { it })
        val slices = checkCuts(c, cuts, 4)
        for (k in 1 until 4) {
            assertTrue(slices[k].text, slices[k].text.startsWith("제${k + 1}부\n제1장"))
            assertEquals(slices[k].text.indexOf("제1장"), slices[k].anchors[ids[k]])
        }
    }

    @Test
    fun cutsWithoutAnchorsPreferHeadingsAndStayBalanced() {
        val xhtml = EpubTestUtil.html(bookBody(60, 4000, seed = 7))
        val c = EpubTestUtil.convert(xhtml)
        val parts = 8
        val cuts = EpubSplit.cut(c, parts, IntArray(0), IntArray(0))
        val slices = checkCuts(c, cuts, parts)
        val even = c.length / parts
        for ((k, s) in slices.withIndex()) {
            assertTrue("part $k size ${s.length} vs $even", s.length in even / 2..even * 2)
            if (k > 0) assertTrue("part $k starts at a heading", (s.blocks[0] as ParagraphBlock).style.headingLevel > 0)
        }
    }

    @Test
    fun giantParagraphsAreCutInside() {
        // one paragraph with spaces, one without (CJK run with surrogate pairs), and <br> lines
        val words = StringBuilder()
        repeat(30000) { words.append("낱말").append(it % 10).append(' ') }
        val solid = StringBuilder()
        repeat(40000) { solid.append(if (it % 5 == 0) "🌊" else "바") }
        val lines = StringBuilder("<p>")
        repeat(3000) { lines.append("줄 $it 입니다<br/>") }
        lines.append("</p>")
        for (body in listOf("<p>$words</p>", "<p>$solid</p>", lines.toString())) {
            val c = EpubTestUtil.convert(EpubTestUtil.html(body))
            for (parts in listOf(2, 5, 9)) {
                val cuts = EpubSplit.cut(c, parts, IntArray(0), IntArray(0))
                val slices = checkCuts(c, cuts, parts)
                for ((k, s) in slices.withIndex()) {
                    assertTrue("part $k of $parts empty", s.length > 0)
                    if (k > 0) assertTrue((s.blocks[0] as ParagraphBlock).style.softBreak)
                    if (s.length > 0) {
                        assertTrue(!Character.isLowSurrogate(s.text[0]))
                        assertTrue(!Character.isHighSurrogate(s.text[s.length - 1]))
                    }
                }
            }
        }
    }

    @Test
    fun cutFuzz() {
        val rnd = Random(42)
        repeat(150) { iter ->
            val sb = StringBuilder()
            var anchors = 0
            repeat(1 + rnd.nextInt(120)) {
                when (rnd.nextInt(10)) {
                    0 -> {
                        val h = 1 + rnd.nextInt(3)
                        sb.append("<h$h id=\"a${anchors++}\">제목 ${rnd.nextInt(100)}</h$h>")
                    }
                    1 -> sb.append("<hr/>")
                    2 -> sb.append("<p><img src=\"i${rnd.nextInt(5)}.png\"/></p>")
                    3 -> sb.append("<p>").append(para(rnd, 1 + rnd.nextInt(3))).append("<br/>")
                        .append(para(rnd, 1)).append("</p>")
                    4 -> sb.append("<p id=\"a${anchors++}\">").append(para(rnd, rnd.nextInt(40))).append("</p>")
                    5 -> sb.append("<p>&nbsp;</p>")
                    6 -> sb.append("<p>앞 <a id=\"a${anchors++}\"></a>뒤 <b>굵게 ${para(rnd, 1)}</b> 끝</p>")
                    else -> sb.append("<p>").append(para(rnd, 1 + rnd.nextInt(8))).append("</p>")
                }
            }
            val xhtml = EpubTestUtil.html(sb.toString())
            val c = EpubTestUtil.convert(xhtml)
            val parts = 1 + rnd.nextInt(12)
            val ids = (0 until anchors).map { "a$it" }.toSet()
            val scan = EpubSplit.scan(xhtml, ids)
            val (offs, ps) = if (rnd.nextBoolean()) anchorsFor(c, parts, scan) else IntArray(0) to IntArray(0)
            val cuts = try {
                EpubSplit.cut(c, parts, offs, ps)
            } catch (e: Throwable) {
                throw AssertionError("iteration $iter", e)
            }
            checkCuts(c, cuts, parts)
            // conflicting / arbitrary constraints never break the structure
            val bad = IntArray(offs.size) { rnd.nextInt(parts) }
            checkCuts(c, EpubSplit.cut(c, parts, offs, bad), parts)
        }
        // degenerate contents
        checkCuts(SectionContent.EMPTY, EpubSplit.cut(SectionContent.EMPTY, 3, IntArray(0), IntArray(0)), 3)
        val img = EpubTestUtil.convert(EpubTestUtil.html("<p><img src=\"a.png\"/></p>"))
        checkCuts(img, EpubSplit.cut(img, 4, IntArray(0), IntArray(0)), 4)
        checkCuts(img, EpubSplit.trivial(img.length, 3), 3)
    }

    // ================================================================ EpubBook

    private fun ncxPoint(i: Int, label: String, src: String) =
        "<navPoint id=\"n$i\" playOrder=\"$i\"><navLabel><text>$label</text></navLabel><content src=\"$src\"/></navPoint>"

    /** Front matter, one whole-book XHTML with [chapters] anchored chapters, and an afterword. */
    private fun wholeBookEpub(chapters: Int, charsPerChapter: Int, extraToc: String = ""): Pair<File, String> {
        val bookXhtml = EpubTestUtil.html(bookBody(chapters, charsPerChapter))
        val points = StringBuilder()
        points.append(ncxPoint(0, "표지", "Text/front.xhtml"))
        for (c in 1..chapters) points.append(ncxPoint(c, "제${c}화", "Text/book.xhtml#ch$c"))
        points.append(extraToc)
        points.append(ncxPoint(chapters + 1, "후기", "Text/end.xhtml"))
        val opf = """<package version="2.0"><metadata><dc:title>통짜 소설</dc:title><dc:language>ko</dc:language></metadata>
<manifest>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="front" href="Text/front.xhtml" media-type="application/xhtml+xml"/>
<item id="book" href="Text/book.xhtml" media-type="application/xhtml+xml"/>
<item id="end" href="Text/end.xhtml" media-type="application/xhtml+xml"/>
<item id="cover" href="Images/cover.png" media-type="image/png" properties="cover-image"/>
</manifest><spine toc="ncx"><itemref idref="front"/><itemref idref="book"/><itemref idref="end"/></spine></package>"""
        val f = writeEpub(
            listOf(
                container("OEBPS/content.opf"),
                text("OEBPS/content.opf", opf),
                text("OEBPS/toc.ncx", "<ncx><navMap>$points</navMap></ncx>"),
                text("OEBPS/Text/front.xhtml", EpubTestUtil.html("<h1>통짜 소설</h1><p>표지 글</p>")),
                text("OEBPS/Text/book.xhtml", bookXhtml),
                text("OEBPS/Text/end.xhtml", EpubTestUtil.html("<h1>후기</h1><p><a href=\"book.xhtml#ch9\">9화로</a></p>")),
                Entry("OEBPS/Images/cover.png", png(5)),
            ),
        )
        return f to bookXhtml
    }

    @Test
    fun hugeSpineItemIsSplitIntoSections() {
        val chapters = 60
        val (f, bookXhtml) = wholeBookEpub(chapters, 5000, ncxPoint(900, "없는 앵커", "Text/book.xhtml#missing"))
        assertTrue(bookXhtml.toByteArray().size > EpubSplit.SCAN_MIN_BYTES)
        val whole = EpubTestUtil.convert(bookXhtml, path = "OEBPS/Text/book.xhtml")
        val doc = EpubBook.open(f, ParseOptions())
        doc.use {
            val parts = doc.partCounts[1]
            assertEquals(listOf(1, parts, 1), doc.partCounts)
            assertTrue("parts $parts", parts >= 3)
            assertEquals(parts + 2, doc.sections.size)
            val approx = (1..parts).sumOf { doc.sections[it].approxChars }
            assertTrue("approx $approx vs ${whole.length}", kotlin.math.abs(approx - whole.length) < whole.length / 10)
            assertEquals(0, doc.conversions) // nothing converted while opening

            // TOC: sections known at open, in order, each anchor inside its own section
            val toc = doc.toc
            assertEquals(chapters + 3, toc.size)
            assertEquals(0, toc[0].section)
            assertEquals(parts + 1, toc.last().section)
            for (i in 1..chapters) {
                val e = toc[i]
                assertTrue(e.section in 1..parts)
                if (i > 1) assertTrue(e.section >= toc[i - 1].section)
                val s = doc.loadSection(e.section)
                val off = s.anchors[e.anchor]
                assertNotNull("${e.anchor} in section ${e.section}", off)
                assertTrue(s.text.startsWith("제${i}화", off!!))
                assertEquals(DocPosition(e.section, off), doc.resolveToc(e))
            }
            // an anchor missing from the file stays next to the entry before it
            val missing = toc[chapters + 1]
            assertEquals("missing", missing.anchor)
            assertEquals(toc[chapters].section, missing.section)
            assertEquals(DocPosition(missing.section, 0), doc.resolveToc(missing))

            // the parts rebuild the item; one conversion for all of them
            val sb = StringBuilder()
            for (s in 1..parts) {
                val c = doc.loadSection(s)
                checkInvariants(c)
                assertTrue("part size ${c.length}", c.length in 1 until EpubSplit.PART_TARGET_CHARS * 2)
                if (s > 1) sb.append('\n')
                sb.append(c.text)
            }
            assertEquals(whole.text, sb.toString())
            assertEquals(1, doc.conversions)

            // links across parts and from other items
            val note = "note${(chapters / 7) * 7}"
            val target = doc.resolveLink(1, "#$note")!!
            assertTrue(doc.loadSection(target.section).text.startsWith("주석", target.offset))
            val ch9 = doc.resolveLink(parts + 1, "book.xhtml#ch9")!!
            assertEquals(doc.resolveToc(toc[9]), ch9)
            assertEquals(DocPosition(1, 0), doc.resolveLink(parts + 1, "book.xhtml"))
            assertNull(doc.resolveLink(2, "#nowhere"))
            assertArrayEquals(png(5), doc.coverImage())
        }
        // deterministic across opens: same sections and TOC
        EpubBook.open(f, ParseOptions()).use { again ->
            assertEquals(doc.partCounts, again.partCounts)
            assertEquals(doc.toc.map { it.section }, again.toc.map { it.section })
            assertEquals(doc.sections.map { it.approxChars }, again.sections.map { it.approxChars })
        }
    }

    @Test
    fun partsAreResolvedFromTheItemCacheAfterEviction() {
        val (f, _) = wholeBookEpub(40, 5000)
        EpubBook.open(f, ParseOptions()).use { doc ->
            val parts = doc.partCounts[1]
            assertTrue(parts > EpubBook.CACHE_SIZE)
            val first = doc.loadSection(1)
            for (s in 1..parts) doc.loadSection(s) // evicts part 1 from the section LRU
            assertEquals(first.text, doc.loadSection(1).text)
            doc.loadSection(0)
            doc.loadSection(parts + 1)
            assertEquals(3, doc.conversions) // the split item itself was converted once
            // positions resolve without converting again
            for (e in doc.toc) doc.resolveToc(e)
            assertEquals(3, doc.conversions)
        }
    }

    @Test
    fun ordinaryItemsAreNotSplitOrScanned() {
        // 150 KB of text in one file: under the scan threshold, one section as before
        val body = bookBody(10, 5000)
        assertTrue(EpubTestUtil.html(body).toByteArray().size <= EpubSplit.SCAN_MIN_BYTES)
        val opf = """<package><metadata/><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="a"/></spine></package>"""
        val f = writeEpub(listOf(container("content.opf"), text("content.opf", opf), text("a.xhtml", EpubTestUtil.html(body))))
        EpubBook.open(f, ParseOptions()).use { doc ->
            assertEquals(listOf(1), doc.partCounts)
            assertEquals(1, doc.sections.size)
        }
        // a large file that is mostly markup (little text) is scanned but stays whole
        val markup = "<div class=\"x\"><span style=\"color: black\"></span></div>\n".repeat(6000) + "<p>본문</p>"
        val g = writeEpub(listOf(container("content.opf"), text("content.opf", opf), text("a.xhtml", EpubTestUtil.html(markup))))
        EpubBook.open(g, ParseOptions()).use { doc ->
            assertEquals(listOf(1), doc.partCounts)
            assertEquals("본문", doc.loadSection(0).text)
        }
    }

    @Test
    fun fallbackTocFindsHeadingsBeyondTheShortPrefix() {
        val filler = "<p>${SENTENCES[0]}</p>".repeat(60) // ~6 KB of UTF-8 before the heading
        val opf = """<package version="2.0"><metadata><dc:title>제목</dc:title></metadata>
<manifest>
<item id="a" href="a.html" media-type="application/xhtml+xml"/>
<item id="b" href="b.html" media-type="application/xhtml+xml"/>
<item id="c" href="c.html" media-type="application/xhtml+xml"/>
</manifest><spine><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/></spine></package>"""
        val f = writeEpub(
            listOf(
                container("content.opf"),
                text("content.opf", opf),
                text("a.html", EpubTestUtil.html("<h1>앞 장</h1>$filler")),
                text("b.html", EpubTestUtil.html("$filler$filler<h2>늦은 제목</h2>$filler")),
                text("c.html", EpubTestUtil.html(filler + filler + filler + filler + "<h2>너무 늦은 제목</h2>").replace("<title>T</title>", "<title>셋째</title>")),
            ),
        )
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            assertEquals(listOf("앞 장", "늦은 제목", "셋째"), doc.toc.map { it.title })
            assertEquals(listOf(0, 1, 2), doc.toc.map { it.section })
        }
        val html = EpubTestUtil.html("<h1>머리</h1><p>본문</p>")
        assertEquals("머리", EpubTocParser.scanTitle(html.substring(0, html.indexOf("<p>")), null, truncated = true))
        assertTrue(EpubTocParser.scanTitle(html.substring(0, html.indexOf("</h1>")), null, truncated = true) === EpubTocParser.UNDECIDED)
    }

    @Test
    fun coverImageWithoutOpeningTheBook() {
        val (f, _) = wholeBookEpub(30, 5000)
        assertArrayEquals(png(5), EpubDocuments.coverImage(f))
        val opf = """<package><metadata/><manifest><item id="x" href="ch.xhtml" media-type="application/xhtml+xml"/>
<item id="i" href="pic.png" media-type="image/png"/></manifest><spine><itemref idref="x"/></spine></package>"""
        val g = writeEpub(
            listOf(container("content.opf"), text("content.opf", opf), text("ch.xhtml", EpubTestUtil.html("<p><img src=\"pic.png\"/></p>")), Entry("pic.png", png(9))),
        )
        assertArrayEquals(png(9), EpubDocuments.coverImage(g))
        EpubDocuments.open(g, ParseOptions()).use { assertArrayEquals(it.coverImage(), EpubDocuments.coverImage(g)) }
        val none = writeEpub(listOf(container("content.opf"), text("content.opf", opf.replace("<item id=\"i\" href=\"pic.png\" media-type=\"image/png\"/>", "")), text("ch.xhtml", EpubTestUtil.html("<p>글</p>"))))
        assertNull(EpubDocuments.coverImage(none))
    }
}
