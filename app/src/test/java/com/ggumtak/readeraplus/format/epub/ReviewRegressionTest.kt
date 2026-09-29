package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.blockTexts
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.container
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.convert
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.html
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.text
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.writeEpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for defects found in review (one test per defect; all sample text is original). */
class ReviewRegressionTest {
    private fun para(c: com.ggumtak.readeraplus.engine.SectionContent, i: Int) = c.blocks[i] as ParagraphBlock

    // ---------------------------------------------------------------- anchors

    @Test
    fun blockIdAfterInlineTextPointsAtTheBlockNotThePreviousParagraphEnd() {
        val c = convert(html("<div>앞 문단의 글<p id=\"n1\">각주 내용</p><h2 id=\"h\">제목</h2></div>"))
        assertEquals(listOf("앞 문단의 글", "각주 내용", "제목"), blockTexts(c))
        assertEquals(c.text.indexOf("각주"), c.anchors["n1"])
        assertEquals(c.text.indexOf("제목"), c.anchors["h"])
        // inline anchors still point inside their paragraph
        val d = convert(html("<p>하나 <a id=\"mid\"></a>둘</p>"))
        assertEquals(d.text.indexOf("둘"), d.anchors["mid"])
    }

    @Test
    fun anchorsInsideDroppedOrTrimmedParagraphsDoNotSpillIntoTheNextOne() {
        val c = convert(html("<p>x</p><p>&nbsp;</p><p>&nbsp;<a id=\"z\"></a></p><p>다음</p>"))
        assertEquals(listOf("x", "", "다음"), blockTexts(c))
        assertEquals(c.blocks[2].start, c.anchors["z"])
        val d = convert(html("<p>끝 <a id=\"tail\"></a> </p><p>다음</p>"))
        assertEquals(d.blocks[0].end, d.anchors["tail"]) // not past the trimmed paragraph end
    }

    // ---------------------------------------------------------------- HTML-style markup

    @Test
    fun unclosedParagraphsAutoCloseAndDoNotLeakStyleOrMerge() {
        val sb = StringBuilder("<p class=\"c\">가운데 첫 문단")
        for (i in 0 until 600) sb.append("<p>문단 ").append(i).append(" <b>굵게</b>")
        val c = convert(html(sb.toString(), head = "<style>p.c { text-align: center }</style>"))
        assertEquals(601, c.blocks.size)
        assertEquals(Align.CENTER, para(c, 0).style.align)
        assertEquals(Align.DEFAULT, para(c, 1).style.align) // the first p's class no longer leaks
        assertEquals("문단 599 굵게", blockTexts(c).last())
        assertTrue(c.styleAt(c.text.lastIndexOf("굵게")).bold)
        // a p nested in a block inside a p keeps XHTML nesting (no implied close across the div)
        val d = convert(html("<p class=\"c\">a<div><p>b</p></div></p>", head = "<style>.c { text-align: right }</style>"))
        assertEquals(listOf("a", "b"), blockTexts(d))
        assertEquals(Align.RIGHT, para(d, 1).style.align)
    }

    @Test
    fun headingStartClosesAnOpenHeading() {
        val c = convert(html("<h1>큰 제목<h2>작은 제목</h2><p>본문</p>"))
        assertEquals(listOf("큰 제목", "작은 제목", "본문"), blockTexts(c))
        assertEquals(1.35f, c.styleAt(c.text.indexOf("작은")).sizeScale, 0.001f) // not 1.5 × 1.35
        assertEquals(2, para(c, 1).style.headingLevel)
    }

    @Test
    fun pathologicallyDeepMarkupKeepsParagraphsBreaksAndImages() {
        val sb = StringBuilder()
        for (i in 0 until 450) sb.append("<div>")
        for (i in 0 until 50) sb.append("<div>줄 $i</div>")
        sb.append("<p>가<br>나</p><img src=\"a.png\"/><hr/>끝")
        val c = convert(html(sb.toString()))
        val texts = blockTexts(c)
        assertEquals("줄 0", texts[0])
        assertEquals("줄 49", texts[49])
        assertEquals(listOf("가", "나", "[img:OEBPS/Text/a.png]", "[hr]", "끝"), texts.subList(50, 55))
    }

    @Test
    fun deepRandomMarkupWithDescendantSelectorsKeepsInvariantsAndStaysFast() {
        val rnd = kotlin.random.Random(424242)
        val opens = listOf("<div>", "<p>", "<span>", "<b>", "<div class=\"x\">", "<blockquote>", "<li>", "<h2>", "<td>")
        val others = listOf("</div>", "</p>", "</span>", "<br>", "<img src=\"i.png\">", "<hr>", "<a id=\"a%d\"></a>",
            "<p id=\"p%d\">", "&nbsp;", " ", "　", "<wbr>")
        val css = "<style>.x div div p { font-weight: bold } div > p + p { text-indent: 0 } " +
            ".x span b { font-style: italic } li:first-child p { text-align: center }</style>"
        val t0 = System.nanoTime()
        repeat(40) { iter ->
            val sb = StringBuilder()
            repeat(rnd.nextInt(300, 1200)) { k ->
                when (rnd.nextInt(4)) {
                    0, 1 -> sb.append(opens[rnd.nextInt(opens.size)])
                    2 -> sb.append(others[rnd.nextInt(others.size)].replace("%d", "$iter-$k"))
                    else -> sb.append(EpubTestUtil.SENTENCES[rnd.nextInt(EpubTestUtil.SENTENCES.size)])
                }
            }
            try {
                convert(html(sb.toString(), head = css), publisherStyles = iter % 2 == 0)
            } catch (t: Throwable) {
                throw AssertionError("iteration $iter failed", t)
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("40 deep documents took $ms ms", ms < 5000)
    }

    // ---------------------------------------------------------------- CSS

    @Test
    fun descendantSelectorsCannotBacktrackExponentially() {
        val sb = StringBuilder()
        for (i in 0 until 390) sb.append("<div>")
        for (i in 0 until 300) sb.append("<div>x</div>")
        val css = ".nomatch div div div div { font-weight: bold } .deep div div p { font-style: italic }"
        val t0 = System.nanoTime()
        val c = convert(html(sb.toString(), head = "<style>$css</style>"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(300, c.blocks.size)
        assertFalse(c.styleAt(0).bold)
        assertTrue("deep descendant matching took $ms ms", ms < 1000)
        // correctness is unchanged for real matches, deep or not
        val deep = StringBuilder("<div class=\"deep\">")
        for (i in 0 until 60) deep.append("<div>")
        deep.append("<p>기울임</p>")
        val d = convert(html(deep.toString(), head = "<style>$css</style>"))
        assertTrue(d.styleAt(0).italic)
    }

    @Test
    fun cdataWrappedStyleElementKeepsItsFirstRule() {
        val c = convert(html("<p class=\"r\">오른쪽</p><p class=\"q\">가운데</p>",
            head = "<style type=\"text/css\"><![CDATA[\np.r { text-align: right }\np.q { text-align: center }\n]]></style>"))
        assertEquals(Align.RIGHT, para(c, 0).style.align)
        assertEquals(Align.CENTER, para(c, 1).style.align)
        val d = convert(html("<p class=\"r\">x</p>", head = "<style>/*<![CDATA[*/ p.r { text-align: right } /*]]>*/</style>"))
        assertEquals(Align.RIGHT, para(d, 0).style.align)
    }

    @Test
    fun importInsideStyleElementLoadsTheImportedSheet() {
        val c = convert(
            html("<p class=\"r\">오른쪽</p>", head = "<style type=\"text/css\">@import url(\"../Styles/book.css\");</style>"),
            css = mapOf("OEBPS/Styles/book.css" to "p.r { text-align: right }"),
        )
        assertEquals(Align.RIGHT, para(c, 0).style.align)
    }

    @Test
    fun legacyOeb1SpineItemsAreContent() {
        assertTrue(ManifestItem("a", "a.htm", "text/x-oeb1-document", "").isHtml)
    }

    @Test
    fun colonlessDeclarationsParseInLinearTime() {
        val junk = ";".repeat(200_000)
        val t0 = System.nanoTime()
        val sheet = CssParser.parse("p { $junk text-align: center }")
        assertNull(CssParser.parseInline("$junk x"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1, sheet.ruleCount)
        assertTrue("took $ms ms", ms < 1000)
    }

    // ---------------------------------------------------------------- tokenizer

    @Test
    fun bogusDeclarationWithApostropheDoesNotSwallowTheDocument() {
        val c = convert(html("<p>하나</p><!bogus it's here><p>둘</p><p>셋</p>"))
        assertEquals(listOf("하나", "둘", "셋"), blockTexts(c))
        // DOCTYPE with an unbalanced quote ends at its first '>'
        val d = convert("<!DOCTYPE html PUBLIC \"-//broken><html><body><p>본문</p></body></html>")
        assertEquals(listOf("본문"), blockTexts(d))
        // a DOCTYPE with an internal subset still works
        val e = convert("<!DOCTYPE html [ <!ENTITY x \"a>b\"> ]><html><body><p>내용</p></body></html>")
        assertEquals(listOf("내용"), blockTexts(e))
    }

    @Test
    fun legacyEntitiesWithoutSemicolonAndHugeNumericReferences() {
        val c = convert(html("<p>a&nbsp b &amp c&#xFFFFFFFF;d&#99999999999;e</p><p><a href=\"x.xhtml?a=1&copy=2\">링크</a></p>"))
        assertEquals("a  b & c�d�e", blockTexts(c)[0])
        assertEquals("x.xhtml?a=1&copy=2", c.styleAt(c.text.indexOf("링크")).link)
    }

    // ---------------------------------------------------------------- TOC / OPF

    @Test
    fun fallbackTitleScanHandlesHtmlVoidElementsInHeadings() {
        assertEquals("제1장 시작", EpubTocParser.scanTitle(html("<h1>제1장<br>시작</h1><p>본문이 이어진다</p><h2>다른</h2>"), null))
        assertEquals("그림 제목", EpubTocParser.scanTitle(html("<h2><img src=\"a.png\">그림 제목</h2><p>본문</p>"), null))
        // </hr> is not a heading closer; an unclosed inner span is ended by the heading's own closer
        assertEquals("장 이름", EpubTocParser.scanTitle(html("<h3><span>장 이름</h3><p>본문 텍스트</p>"), null))
    }

    @Test
    fun opfWithUnclosedMetaElementsKeepsTheRestOfTheMetadata() {
        val opf = """<package><metadata><meta name="cover" content="img1"><dc:title>나무 아래</dc:title>
            <meta property="dcterms:modified">2020-01-01<dc:creator>한결</dc:creator>
            <dc:description>첫 줄<br>둘째 줄</dc:description><dc:publisher>작은 출판</dc:publisher></metadata>
            <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
            <spine><itemref idref="a"/></spine></package>"""
        val p = OpfParser.parse(opf, "OEBPS/content.opf") { it }
        assertEquals("나무 아래", p.title)
        assertEquals(listOf("한결"), p.authors)
        assertEquals("img1", p.coverMeta)
        assertEquals("첫 줄\n둘째 줄", p.description)
        assertEquals("작은 출판", p.publisher)
        assertEquals(1, p.spine.size)
    }

    @Test
    fun navWhoseLinksMissTheSpineFallsBackToNcx() {
        val opf = """<package version="3.0"><metadata><dc:title>두 목차</dc:title></metadata><manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
            </manifest><spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        val nav = html("<nav epub:type=\"toc\"><ol><li><a href=\"gone1.xhtml\">사라진 1</a></li>" +
            "<li><a href=\"gone2.xhtml\">사라진 2</a></li></ol></nav>")
        val ncx = """<ncx><navMap>
            <navPoint id="n1"><navLabel><text>첫 장</text></navLabel><content src="c1.xhtml"/></navPoint>
            <navPoint id="n2"><navLabel><text>둘째 장</text></navLabel><content src="c2.xhtml#s"/></navPoint>
            </navMap></ncx>"""
        val f = writeEpub(listOf(
            container("content.opf"), text("content.opf", opf), text("nav.xhtml", nav), text("toc.ncx", ncx),
            text("c1.xhtml", html("<p>${EpubTestUtil.SENTENCES[0]}</p>")),
            text("c2.xhtml", html("<p>${EpubTestUtil.SENTENCES[1]}</p><p id=\"s\">${EpubTestUtil.SENTENCES[2]}</p>")),
        ))
        EpubDocuments.open(f, ParseOptions()).use { doc ->
            assertEquals(listOf("첫 장", "둘째 장"), doc.toc.map { it.title })
            assertEquals(listOf(0, 1), doc.toc.map { it.section })
            assertEquals("s", doc.toc[1].anchor)
        }
    }

    @Test
    fun tocResolutionReusesAnchorsOfEvictedSections() {
        val entries = ArrayList<EpubTestUtil.Entry>()
        val manifest = StringBuilder()
        val spine = StringBuilder()
        val navLinks = StringBuilder()
        for (i in 0 until 8) {
            entries.add(text("s$i.xhtml", html("<p>${EpubTestUtil.SENTENCES[i]}</p><h2 id=\"h$i\">장 $i</h2><p>본문 $i</p>")))
            manifest.append("<item id=\"s$i\" href=\"s$i.xhtml\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"s$i\"/>")
            navLinks.append("<li><a href=\"s$i.xhtml#h$i\">장 $i</a></li>")
        }
        entries.add(text("nav.xhtml", html("<nav epub:type=\"toc\"><ol>$navLinks</ol></nav>")))
        entries.add(0, text("content.opf", "<package><metadata/><manifest>$manifest" +
            "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
            "</manifest><spine>$spine</spine></package>"))
        entries.add(0, container("content.opf"))
        EpubDocuments.open(writeEpub(entries), ParseOptions()).use { doc ->
            val book = doc as EpubBook
            val first = doc.toc.map { doc.resolveToc(it) } // converts every section once
            assertEquals(8, book.conversions)
            val second = doc.toc.map { doc.resolveToc(it) } // LRU holds 4: anchors come from the anchor cache
            assertEquals(8, book.conversions)
            assertEquals(first, second)
            for ((i, p) in first.withIndex()) assertEquals(doc.loadSection(i).text.indexOf("장 $i"), p.offset)
        }
    }

    // ---------------------------------------------------------------- decoding

    @Test
    fun declaredUtf8ButActuallyCp949IsDecodedAsCp949() {
        val cp949 = EpubText.cp949() ?: return // JVM without CP949 support: nothing to check
        val body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body><p>똠방각하 햏 쐈다</p></body></html>"
        assertEquals(body, EpubText.decode(body.toByteArray(cp949)))
        // real UTF-8 with one corrupt byte stays UTF-8 (one U+FFFD, not mojibake)
        val utf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><p>가나다라마바사</p>".toByteArray(Charsets.UTF_8)
        utf[utf.size - 8] = 0x41
        val s = EpubText.decode(utf)
        assertTrue(s, s.contains("가나다라"))
        assertTrue(s.contains('�'))
    }

    @Test
    fun utf16LabelOnAsciiCompatibleBytesIsIgnored() {
        val src = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><p>한글 문장</p>"
        assertEquals(src, EpubText.decode(src.toByteArray(Charsets.UTF_8)))
        assertNotNull(EpubText.charsetFor("utf-16"))
    }
}
