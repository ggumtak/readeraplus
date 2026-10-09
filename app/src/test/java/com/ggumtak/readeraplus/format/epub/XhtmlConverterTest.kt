package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.RuleBlock
import com.ggumtak.readeraplus.engine.RunStyle
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.blockTexts
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.convert
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.html
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XhtmlConverterTest {
    private fun para(c: SectionContent, i: Int): ParagraphBlock = c.blocks[i] as ParagraphBlock

    private fun styleOf(c: SectionContent, needle: String): RunStyle = c.styleAt(c.text.indexOf(needle))

    @Test
    fun entitiesAndWhitespace() {
        val c = convert(html("<p>  A&amp;B &lt;tag&gt;   &nbsp;x&hellip;\n\t &#44032;&#xAC01; &unknown; &copy;  </p>"))
        assertEquals(listOf("A&B <tag> \u00A0x… 가각 &unknown; ©"), blockTexts(c))
    }

    @Test
    fun inlineBoundariesKeepSingleSpaces() {
        val c = convert(html("<p>foo <b>bar</b> baz <i> qux </i>end<span>ing</span></p>"))
        assertEquals("foo bar baz qux ending", c.text)
        assertTrue(styleOf(c, "bar").bold)
        assertFalse(styleOf(c, "baz").bold)
        assertTrue(styleOf(c, "qux").italic)
        assertFalse(c.styleAt(c.text.indexOf("baz") + 3).italic) // space before <i> is plain
        assertFalse(styleOf(c, "end").italic)
    }

    @Test
    fun brMakesSoftBreakParagraphs() {
        val c = convert(html("<p>첫째 줄<br/>둘째 줄</p><p>다음 문단</p>"))
        assertEquals(listOf("첫째 줄", "둘째 줄", "다음 문단"), blockTexts(c))
        assertFalse(para(c, 0).style.softBreak)
        assertTrue(para(c, 1).style.softBreak)
        assertFalse(para(c, 2).style.softBreak)
    }

    @Test
    fun doubleBrGivesBlankLineAndTrailingBrIsIgnored() {
        val c = convert(html("<p>a<br/><br/>b<br></p><p>c<br /></p><p><br/></p><p>d</p>"))
        assertEquals(listOf("a", "", "b", "c", "", "d"), blockTexts(c))
        assertTrue(para(c, 1).style.softBreak)
        assertTrue(para(c, 2).style.softBreak)
        assertFalse(para(c, 3).style.softBreak)
    }

    @Test
    fun nestedDivsDoNotNestParagraphs() {
        val c = convert(html("<div>intro <div><p>one</p><p>two</p></div> tail</div><div><div><div>deep</div></div></div>"))
        assertEquals(listOf("intro", "one", "two", "tail", "deep"), blockTexts(c))
    }

    @Test
    fun headings() {
        val c = convert(html("<h1>큰 제목</h1><h2 class=\"x\">작은 <em>제목</em></h2><p>본문</p><h3>셋</h3><h6>여섯</h6>"))
        val h1 = para(c, 0).style
        assertEquals(1, h1.headingLevel)
        assertEquals(Align.CENTER, h1.align)
        assertFalse(h1.indent)
        assertTrue(h1.keepWithNext)
        assertEquals(2, para(c, 1).style.headingLevel)
        assertEquals(0, para(c, 2).style.headingLevel)
        assertTrue(para(c, 2).style.indent)
        val s1 = styleOf(c, "큰")
        assertTrue(s1.bold)
        assertEquals(1.5f, s1.sizeScale, 0.001f)
        assertEquals(1.35f, styleOf(c, "작은").sizeScale, 0.001f)
        assertTrue(styleOf(c, "제목</").let { true })
        val em = c.styleAt(c.text.indexOf("제목", c.text.indexOf("작은")))
        assertTrue(em.italic && em.bold)
        assertEquals(1.2f, styleOf(c, "셋").sizeScale, 0.001f)
        assertEquals(1f, styleOf(c, "여섯").sizeScale, 0.001f)
        assertTrue(styleOf(c, "여섯").bold)
        assertEquals(RunStyle.PLAIN, styleOf(c, "본문"))
    }

    @Test
    fun headingAlignmentFollowsOwnCssOnly() {
        val css = "<style>h2 { text-align: left } body { text-align: justify } .c { text-align: center }</style>"
        val c = convert(html("<h1>A</h1><h2>B</h2><div class=\"c\"><h3>C</h3><p>D</p></div><p>E</p>", css))
        assertEquals(Align.CENTER, para(c, 0).style.align) // body's justify is not inherited by headings
        assertEquals(Align.LEFT, para(c, 1).style.align)
        assertEquals(Align.CENTER, para(c, 2).style.align)
        assertEquals(Align.CENTER, para(c, 3).style.align) // inherited from div.c
        assertEquals(Align.DEFAULT, para(c, 4).style.align) // justify defers to the user
        val off = convert(html("<h1>A</h1><h2>B</h2><div class=\"c\"><p>D</p></div>", css), publisherStyles = false)
        assertEquals(Align.CENTER, para(off, 0).style.align)
        assertEquals(Align.CENTER, para(off, 1).style.align) // publisher styles off: headings stay centred
        assertEquals(Align.DEFAULT, para(off, 2).style.align)
    }

    @Test
    fun lists() {
        val c = convert(
            html(
                "<ul><li>사과</li><li>배<ul><li>작은 배</li></ul></li></ul>" +
                    "<ol start=\"3\"><li>셋</li><li><p>넷</p><p>넷의 둘째 문단</p></li><li value=\"10\">열</li></ol>" +
                    "<ol type=\"a\"><li>x</li><li>y</li></ol>",
            ),
        )
        assertEquals(
            listOf("• 사과", "• 배", "◦ 작은 배", "3. 셋", "4. 넷", "넷의 둘째 문단", "10. 열", "a. x", "b. y"),
            blockTexts(c),
        )
        assertEquals(1.2f, para(c, 0).style.insetLeftEm, 0.001f)
        assertEquals(2.4f, para(c, 2).style.insetLeftEm, 0.001f)
        assertFalse(para(c, 0).style.indent)
        assertFalse(para(c, 5).style.indent)
    }

    @Test
    fun listStyleNoneHidesMarkers() {
        val c = convert(html("<ol style=\"list-style-type: none\"><li>a</li><li>b</li></ol>"))
        assertEquals(listOf("a", "b"), blockTexts(c))
        val off = convert(html("<ol style=\"list-style-type: none\"><li>a</li></ol>"), publisherStyles = false)
        assertEquals(listOf("1. a"), blockTexts(off))
    }

    @Test
    fun blockquoteAndDefinitionLists() {
        val c = convert(html("<blockquote><p>인용</p></blockquote><dl><dt>용어</dt><dd>설명</dd></dl>"))
        assertEquals(1.5f, para(c, 0).style.insetLeftEm, 0.001f)
        assertEquals(1f, para(c, 0).style.insetRightEm, 0.001f)
        assertTrue(styleOf(c, "용어").bold)
        assertEquals(1.5f, para(c, 2).style.insetLeftEm, 0.001f)
        assertFalse(para(c, 2).style.indent)
    }

    @Test
    fun tables() {
        val c = convert(
            html(
                "<table><caption>표 1</caption><thead><tr><th>이름</th><th>값</th></tr></thead>" +
                    "<tbody><tr><td>가</td><td><p>하나</p><p>둘</p></td></tr><tr><td></td><td>빈칸 뒤</td></tr>" +
                    "<tr><td>a<br/>b</td><td>c</td></tr></tbody></table><p>after</p>",
            ),
        )
        assertEquals(listOf("표 1", "이름  ·  값", "가  ·  하나 둘", "빈칸 뒤", "a b  ·  c", "after"), blockTexts(c))
        assertTrue(styleOf(c, "이름").bold)
        assertFalse(para(c, 1).style.indent)
        assertFalse(para(c, 2).style.indent)
    }

    @Test
    fun unclosedTableCellsAndRows() {
        val c = convert("<table><tr><td>a<td>b<tr><td>c<td>d</table><p>e")
        assertEquals(listOf("a  ·  b", "c  ·  d", "e"), blockTexts(c))
    }

    @Test
    fun imagesResolveRelativePaths() {
        val c = convert(
            html(
                "<p>글<img src=\"../Images/a%20b.jpg\" width=\"120\" height=\"80px\" alt=\" 그림 \"/>뒤</p>" +
                    "<div><img src=\"../Images/missing.png\"/></div><p><img src=\"http://x/y.png\"/></p>" +
                    "<svg xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 600 800\"><title>svg title</title>" +
                    "<image width=\"600\" height=\"800\" xlink:href=\"../Images/cover.jpg\"/><text>ignored</text></svg>",
            ),
            missingImages = setOf("OEBPS/Images/missing.png"),
        )
        assertEquals(listOf("글", "[img:OEBPS/Images/a b.jpg]", "뒤", "[img:OEBPS/Images/cover.jpg]"), blockTexts(c))
        val img = c.blocks[1] as ImageBlock
        assertEquals(120, img.intrinsicWidth)
        assertEquals(80, img.intrinsicHeight)
        assertEquals("그림", img.alt)
        assertEquals(Align.CENTER, img.style.align)
        assertTrue(para(c, 2).style.softBreak) // text continuing after an inline image
        assertEquals(600, (c.blocks[3] as ImageBlock).intrinsicWidth)
    }

    @Test
    fun anchors() {
        val c = convert(
            html(
                "<h2 id=\"c1\">장 제목</h2><p>앞 <span id=\"s1\">가운데</span> 뒤</p><a name=\"n1\"></a><p id=\"p3\">셋째</p>" +
                    "<div id=\"hid\" style=\"display:none\"><p id=\"inside\">숨김</p></div><p>끝</p><a id=\"tail\"/>",
            ),
        )
        assertEquals(0, c.anchors["c1"])
        assertEquals(c.text.indexOf("가운데"), c.anchors["s1"])
        assertEquals(c.text.indexOf("셋째"), c.anchors["n1"])
        assertEquals(c.text.indexOf("셋째"), c.anchors["p3"])
        assertEquals(c.text.indexOf("끝"), c.anchors["hid"])
        assertEquals(c.text.indexOf("끝"), c.anchors["inside"])
        assertEquals(c.text.length, c.anchors["tail"])
        assertFalse(c.text.contains("숨김"))
    }

    @Test
    fun cssClassRulesFromStyleAndLink() {
        val head = "<link rel=\"stylesheet\" type=\"text/css\" href=\"../Styles/book.css\"/>" +
            "<style type=\"text/css\">.b { font-weight: bold } p.noind { text-indent: 0 }</style>"
        val css = mapOf(
            "OEBPS/Styles/book.css" to ".center { text-align: center } .right { text-align: right; } .it { font-style: italic }",
        )
        val c = convert(
            html("<p class=\"center\">가운데</p><p class=\"noind right\">오른쪽</p><p><span class=\"b it\">굵은</span> 보통</p>", head),
            css = css,
        )
        assertEquals(Align.CENTER, para(c, 0).style.align)
        assertEquals(Align.RIGHT, para(c, 1).style.align)
        assertFalse(para(c, 1).style.indent)
        assertTrue(styleOf(c, "굵은").bold)
        assertTrue(styleOf(c, "굵은").italic)
        assertEquals(RunStyle.PLAIN, styleOf(c, "보통"))
        // publisher styles off: alignment/indent/weight ignored
        val off = convert(
            html("<p class=\"center\">가운데</p><p class=\"noind\">x</p><p><span class=\"b\">굵은</span></p>", head),
            publisherStyles = false,
            css = css,
        )
        assertEquals(Align.DEFAULT, para(off, 0).style.align)
        assertTrue(para(off, 1).style.indent)
        assertFalse(styleOf(off, "굵은").bold)
    }

    @Test
    fun displayNoneAlwaysApplies() {
        val head = "<style>.hide, aside.note { display: none }</style>"
        val body = "<p>보임</p><p class=\"hide\">숨김1</p><aside class=\"note\">숨김2</aside><p hidden=\"\">숨김3</p>" +
            "<span epub:type=\"pagebreak\" title=\"12\">12</span><p>끝</p>"
        for (ps in listOf(true, false)) {
            val c = convert(html(body, head), publisherStyles = ps)
            assertEquals(listOf("보임", "끝"), blockTexts(c))
        }
    }

    @Test
    fun descendantChildSiblingAndFirstChildSelectors() {
        val head = "<style>.poem p { text-align: center } h2 + p { text-indent: 0 } " +
            "div.box > p { text-align: right } p:first-child { font-style: italic }</style>"
        val c = convert(
            html(
                "<p>첫 문단</p><div class=\"poem\"><section><p>시</p></section></div><h2>제목</h2><p>제목 뒤</p><p>그다음</p>" +
                    "<div class=\"box\"><p>박스</p><div><p>손자</p></div></div>",
                head,
            ),
        )
        val t = blockTexts(c)
        fun p(s: String) = para(c, t.indexOf(s)).style
        assertEquals(Align.DEFAULT, p("첫 문단").align)
        assertEquals(Align.CENTER, p("시").align)
        assertFalse(p("제목 뒤").indent)
        assertTrue(p("그다음").indent)
        assertEquals(Align.RIGHT, p("박스").align)
        assertEquals(Align.DEFAULT, p("손자").align)
        assertTrue(styleOf(c, "첫 문단").italic) // first child of body
        assertTrue(styleOf(c, "시").italic) // first child of section
        assertFalse(styleOf(c, "그다음").italic)
        assertTrue(styleOf(c, "박스").italic)
    }

    @Test
    fun marginsBreaksAndInlineStyle() {
        val head = "<style>.chap { margin-top: 3em; margin-bottom: 2em; page-break-before: always } " +
            "p { margin: 0.5em 0 } .ind { margin-left: 2em }</style>"
        val c = convert(
            html("<div class=\"chap\"><p>하나</p><p>둘</p></div><p>셋</p><p class=\"ind\" style=\"text-align:center\">넷</p>", head),
        )
        val one = para(c, 0).style
        assertEquals(3f, one.marginTopEm, 0.001f)
        assertTrue(one.pageBreakBefore)
        assertEquals(0f, para(c, 1).style.marginTopEm, 0.001f) // 0.5em p margins are paragraph spacing: ignored
        assertEquals(2f, para(c, 1).style.marginBottomEm, 0.001f)
        assertFalse(para(c, 2).style.pageBreakBefore)
        assertEquals(2f, para(c, 3).style.insetLeftEm, 0.001f)
        assertEquals(Align.CENTER, para(c, 3).style.align)
    }

    @Test
    fun inlineTagStyles() {
        val c = convert(
            html(
                "<p>x<sup>2</sup> H<sub>2</sub>O <small>작게</small> <big>크게</big> <u>밑줄</u> <del>취소</del> " +
                    "<code>code</code> <a href=\"ch2.xhtml#n1\">링크</a> <strong>굵게 <em>기울임</em></strong></p>",
            ),
        )
        val sup = styleOf(c, "2")
        assertEquals(1, sup.baselineShift)
        assertEquals(0.75f, sup.sizeScale, 0.001f)
        assertEquals(-1, c.styleAt(c.text.indexOf("2", c.text.indexOf("H"))).baselineShift)
        assertEquals(0.85f, styleOf(c, "작게").sizeScale, 0.001f)
        assertEquals(1.2f, styleOf(c, "크게").sizeScale, 0.001f)
        assertTrue(styleOf(c, "밑줄").underline)
        assertTrue(styleOf(c, "취소").strike)
        assertTrue(styleOf(c, "code").monospace)
        assertEquals("ch2.xhtml#n1", styleOf(c, "링크").link)
        val be = styleOf(c, "기울임")
        assertTrue(be.bold && be.italic)
    }

    @Test
    fun adjacentEqualRunsMerge() {
        val c = convert(html("<p><b>가</b><b>나</b><strong>다</strong></p>"))
        assertEquals(1, c.styleRuns.size)
        assertEquals(0, c.styleRuns[0].start)
        assertEquals(3, c.styleRuns[0].end)
    }

    @Test
    fun rulesAndEmptyParagraphs() {
        val c = convert(
            html(
                "<p>&nbsp;</p><p> </p><p>a</p><p>&nbsp;</p><p>&#160;&#160;</p><p><br/></p><hr/><p></p><p>b</p>" +
                    "<div></div><p>&nbsp;</p><p>\u3000</p>",
            ),
        )
        assertEquals(listOf("a", "", "[hr]", "b"), blockTexts(c))
        assertTrue(c.blocks[2] is RuleBlock)
    }

    @Test
    fun preformatted() {
        val c = convert(html("<pre>\nline1\n  line2\ttab\r\n\nline4\n</pre><p>after</p>"))
        assertEquals(listOf("line1", "  line2    tab", "", "line4", "after"), blockTexts(c))
        val p0 = para(c, 0).style
        assertTrue(p0.preformatted)
        assertFalse(p0.indent)
        assertFalse(p0.softBreak)
        assertTrue(para(c, 1).style.softBreak)
        assertTrue(styleOf(c, "line1").monospace)
        assertFalse(para(c, 4).style.preformatted)
    }

    @Test
    fun whiteSpacePreViaCss() {
        val c = convert(html("<div style=\"white-space: pre-wrap\">a  b\nc</div>"))
        assertEquals(listOf("a  b", "c"), blockTexts(c))
    }

    @Test
    fun leadingIdeographicSpaceIsStripped() {
        val c = convert(html("<p>\u3000\u3000들여쓰기 된 문단\u3000중간</p><p>a<br/>\u3000시 행</p>"))
        assertEquals(listOf("들여쓰기 된 문단\u3000중간", "a", "\u3000시 행"), blockTexts(c))
    }

    @Test
    fun headIsSkippedTitleCaptured() {
        val conv = XhtmlConverter(true, null)
        val c = conv.convert(
            "<html><head><title> 제 1 장 </title><meta charset=\"utf-8\"/><script>var x = '<p>no</p>';</script>" +
                "</head><body><!-- c --><p>본문<![CDATA[ & cdata]]></p><noscript>ns</noscript></body></html>",
            "a.xhtml",
        )
        EpubTestUtil.checkInvariants(c)
        assertEquals("제 1 장", conv.title)
        assertEquals(listOf("본문 & cdata"), blockTexts(c))
    }

    @Test
    fun rubyTextIsDropped() {
        val c = convert(html("<p><ruby>漢<rp>(</rp><rt>한</rt><rp>)</rp></ruby>字</p>"))
        assertEquals("漢字", c.text)
    }

    @Test
    fun malformedMarkup() {
        val docs = listOf(
            "<p>unclosed <b>bold<p>next</i> text",
            "<html><body><div><p>a</div></p></span>b</body>",
            "<p>x</p></p></p><p>y",
            "just text with <no> tags & stuff",
            "<body><p>a<br>b<hr>c<img src=x.png>d",
            "<ul><li>one<li>two<ul><li>n1<li>n2</ul><li>three</ul>",
            "<h1>t<h2>u</h1>v",
            "<p><<<>>></p>",
            "<",
            "",
            "<p class=\"x>broken attr</p><p>ok</p>",
            "<div><table><tr><td><div>cell</div></td></tr></table></div></div></div>",
        )
        for (d in docs) {
            val c = convert(d)
            if (d.contains("unclosed")) assertEquals(listOf("unclosed bold", "next text"), blockTexts(c))
            if (d.startsWith("<ul>")) assertEquals(listOf("• one", "• two", "◦ n1", "◦ n2", "• three"), blockTexts(c))
        }
    }

    @Test
    fun deepNestingIsBounded() {
        val sb = StringBuilder()
        repeat(5000) { sb.append("<div><span>") }
        sb.append("깊다")
        repeat(5000) { sb.append("</span></div>") }
        sb.append("<p>이후</p>")
        val c = convert(sb.toString())
        assertTrue(c.text.contains("깊다"))
        assertTrue(c.text.endsWith("이후"))
    }

    @Test
    fun linksRunCoversOnlyAnchorText() {
        val c = convert(html("<p>보기 <a href=\"#fn1\" epub:type=\"noteref\"><sup>1</sup></a> 끝</p>"))
        val i = c.text.indexOf('1')
        assertEquals("#fn1", c.styleAt(i).link)
        assertNull(styleOf(c, "끝").link)
        assertNull(c.styleAt(i - 1).link)
    }

    @Test
    fun controlCharactersAndObjectCharAreDropped() {
        val c = convert(html("<p>a\u0001b\uFFFCc\u00ADd\uFEFFe</p>"))
        assertEquals("abcde", c.text)
    }

    @Test
    fun emptyDocument() {
        val c = convert(html(""))
        assertEquals("", c.text)
        assertTrue(c.blocks.isEmpty())
    }

    @Test
    fun cssFontSizeOnBodyIgnoredAndScalesQuantized() {
        val head = "<style>body { font-size: 0.8em } p.s { font-size: 0.93em } h1 { font-size: 2.5em }</style>"
        val c = convert(html("<h1>큰</h1><p>본문</p><p class=\"s\">작은</p>", head))
        assertEquals(2f, styleOf(c, "큰").sizeScale, 0.001f)
        assertEquals(RunStyle.PLAIN, styleOf(c, "본문"))
        assertEquals(0.95f, styleOf(c, "작은").sizeScale, 0.001f)
    }

    private val bookSizes = "<style>body { font-size: 0.8em } div { font-size: 1.3em } p.s { font-size: 0.93em } " +
        "span.b { font-size: 140% } h1 { font-size: 1.5em } h2 span { font-size: 0.5em }</style>"

    @Test
    fun ignoreBookSizesKeepsBodyTextAtTheReadersSize() {
        val body = "<div><p>본문</p><p class=\"s\">작은</p><p><span class=\"b\">큰 글씨</span> 끝</p></div>"
        val on = convert(html(body, bookSizes), ignoreBookSizes = true)
        assertEquals(RunStyle.PLAIN, styleOf(on, "본문"))
        assertEquals(RunStyle.PLAIN, styleOf(on, "작은"))
        assertEquals(RunStyle.PLAIN, styleOf(on, "큰 글씨"))
        // Off: the book's scales apply as before (div 1.3, then p.s 0.93 / span 140% on top, quantized).
        val off = convert(html(body, bookSizes))
        assertEquals(1.3f, styleOf(off, "본문").sizeScale, 0.001f)
        assertEquals(1.2f, styleOf(off, "작은").sizeScale, 0.001f)
        assertEquals(1.8f, styleOf(off, "큰 글씨").sizeScale, 0.001f)
    }

    @Test
    fun ignoreBookSizesKeepsHeadingsOnTheirOwnScale() {
        val body = "<div><h1>제목</h1><h2>둘째 <span>안쪽</span></h2><h3>셋</h3><p>본문</p></div>"
        val on = convert(html(body, bookSizes), ignoreBookSizes = true)
        // h1's 1.5em counts relative to the reader's size, not on top of div's ignored 1.3 and body's 0.8.
        assertEquals(1.5f, styleOf(on, "제목").sizeScale, 0.001f)
        // A span inside a heading inherits the heading's scale; its own declaration is ignored.
        assertEquals(1.35f, styleOf(on, "둘째").sizeScale, 0.001f)
        assertEquals(1.35f, styleOf(on, "안쪽").sizeScale, 0.001f)
        // A heading without a declaration of its own keeps the built-in scale.
        assertEquals(1.2f, styleOf(on, "셋").sizeScale, 0.001f)
        assertEquals(RunStyle.PLAIN, styleOf(on, "본문"))
        // Off: h1 compounds with the div's scale (the span's 0.5 counts too, clamped to 0.7: 1.75 × 0.7).
        val off = convert(html(body, bookSizes))
        assertEquals(1.95f, styleOf(off, "제목").sizeScale, 0.001f)
        assertEquals(1.25f, styleOf(off, "안쪽").sizeScale, 0.001f)
    }

    @Test
    fun ignoreBookSizesKeepsBuiltInSmallSubSup() {
        val c = convert(html("<p>a<sup>위</sup>b<sub>아래</sub><small>작게</small><big>크게</big></p>", bookSizes), ignoreBookSizes = true)
        assertEquals(0.75f, styleOf(c, "위").sizeScale, 0.001f)
        assertEquals(0.75f, styleOf(c, "아래").sizeScale, 0.001f)
        assertEquals(0.85f, styleOf(c, "작게").sizeScale, 0.001f)
        assertEquals(1.2f, styleOf(c, "크게").sizeScale, 0.001f)
    }

    @Test
    fun cellSeparatorNotDuplicatedWithSpaces() {
        val c = convert("<table><tr> <td> a </td> <td> b </td> </tr></table>")
        assertEquals(listOf("a  ·  b"), blockTexts(c))
    }
}
