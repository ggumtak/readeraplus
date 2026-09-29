package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypesetterTest {

    private val m = FakeMeasurer()

    private fun cfg(
        width: Int = 400,
        height: Int = 600,
        lineHeightEm: Float = 1.5f,
        paragraphSpacingEm: Float = 0f,
        indentEm: Float = 0f,
        align: Align = Align.LEFT,
        lineBreak: LineBreakMode = LineBreakMode.CHAR,
        publisherStyles: Boolean = true,
        maxImageHeightFraction: Float = 1f,
        widowOrphanControl: Boolean = false,
    ) = LayoutConfig(
        width, height, lineHeightEm, paragraphSpacingEm, indentEm, align, lineBreak, publisherStyles,
        maxImageHeightFraction, widowOrphanControl,
    )

    private fun layout(content: SectionContent, c: LayoutConfig, measurer: TextMeasurer = m): SectionLayout {
        val ts = Typesetter(measurer, c)
        val l = ts.layout(content)
        assertEquals("countPages == layout.pageCount", l.pageCount, Typesetter(measurer, c).countPages(content))
        assertEquals("countPages on a reused typesetter", l.pageCount, ts.countPages(content))
        LayoutChecks.checkAll(l, measurer)
        return l
    }

    private fun textLines(l: SectionLayout) =
        LayoutChecks.allLines(l).filter { it.imageBlock == null && !it.isRule && it.end > it.start }

    private fun lineTexts(l: SectionLayout) = textLines(l).map { l.content.text.substring(it.start, it.end) }

    // --- empty & degenerate sections ------------------------------------------------------------------

    @Test
    fun emptySectionHasOneEmptyPage() {
        val l = layout(SectionContent.EMPTY, cfg())
        assertEquals(1, l.pageCount)
        assertEquals(0, l.pages[0].start)
        assertEquals(0, l.pages[0].end)
        assertTrue(l.pages[0].lines.isEmpty())
        assertEquals(1, Typesetter(m, cfg()).countPages(SectionContent.EMPTY))
    }

    @Test
    fun textWithoutBlocksIsOneEmptyPageCoveringEverything() {
        val content = SectionContent("본문만 있고 블록이 없다", emptyList())
        val l = layout(content, cfg())
        assertEquals(1, l.pageCount)
        assertEquals(content.length, l.pages[0].end)
    }

    @Test
    fun onlyEmptyParagraphsGiveOneEmptyPage() {
        val b = SectionBuilder()
        b.para("")
        b.para("")
        b.para("   ")
        val l = layout(b.build(), cfg())
        assertEquals(1, l.pageCount)
        assertTrue(l.pages[0].lines.isEmpty())
    }

    @Test
    fun zeroSizedViewportStillTerminates() {
        val b = SectionBuilder()
        b.para("가나다 라마바 abc def")
        b.image("img")
        val content = b.build()
        val mm = FakeMeasurer(images = mapOf("img" to IntSize(100, 100)))
        for (c in listOf(cfg(width = 0, height = 0), cfg(width = 1, height = 1), cfg(width = -5, height = -5))) {
            val l = Typesetter(mm, c).layout(content)
            LayoutChecks.checkPages(l)
            assertEquals(l.pageCount, Typesetter(mm, c).countPages(content))
            assertTrue(l.pageCount >= 2)
        }
    }

    // --- vertical metrics & spacing -----------------------------------------------------------------

    @Test
    fun lineHeightIsEmBasedWithHalfLeading() {
        val b = SectionBuilder()
        b.para("가나다")
        val l = layout(b.build(), cfg(lineHeightEm = 1.5f))
        val ln = l.pages[0].lines[0]
        assertEquals(0f, ln.top, 0.001f)
        assertEquals(30f, ln.bottom, 0.001f)
        assertEquals(5f + 16f, ln.baseline, 0.001f) // (30 - 20) / 2 + ascent
    }

    @Test
    fun lineHeightNeverBelowNaturalHeight() {
        val b = SectionBuilder()
        b.para("가나다")
        val ln = layout(b.build(), cfg(lineHeightEm = 0.5f)).pages[0].lines[0]
        assertEquals(20f, ln.bottom - ln.top, 0.001f)
        assertEquals(16f, ln.baseline, 0.001f)
    }

    @Test
    fun lineHeightScalesWithLargestStyleOnTheLine() {
        val b = SectionBuilder()
        b.paraWithRun("가나다라마", 1, 2, RunStyle(sizeScale = 1.5f))
        b.para("가나다")
        val l = layout(b.build(), cfg(lineHeightEm = 1.5f))
        val big = l.pages[0].lines[0]
        assertEquals(45f, big.bottom - big.top, 0.001f)
        assertEquals((45f - 30f) / 2f + 24f, big.baseline - big.top, 0.001f)
        val normal = l.pages[0].lines[1]
        assertEquals(30f, normal.bottom - normal.top, 0.001f)
    }

    @Test
    fun paragraphSpacingBetweenParagraphsButNotAtPageTop() {
        val b = SectionBuilder()
        b.para("첫 문단")
        b.para("둘째 문단")
        b.para("셋째 문단")
        // 30px lines + 10px spacing: page of 75px holds two paragraphs (0-30, 40-70), the third starts page 2.
        val l = layout(b.build(), cfg(height = 75, paragraphSpacingEm = 0.5f))
        assertEquals(2, l.pageCount)
        val p0 = l.pages[0].lines
        assertEquals(0f, p0[0].top, 0.001f)
        assertEquals(40f, p0[1].top, 0.001f)
        assertEquals(0f, l.pages[1].lines[0].top, 0.001f)
    }

    @Test
    fun marginsAddToParagraphSpacing() {
        val b = SectionBuilder()
        b.para("하나", BlockStyle(marginBottomEm = 1f))
        b.para("둘", BlockStyle(marginTopEm = 0.5f))
        val l = layout(b.build(), cfg(paragraphSpacingEm = 0.5f))
        val lines = l.pages[0].lines
        assertEquals(30f + 20f + 10f + 10f, lines[1].top, 0.001f)
    }

    @Test
    fun softBreakHasNoSpacingAndNoIndent() {
        val b = SectionBuilder()
        b.para("시의 첫 줄")
        b.para("시의 둘째 줄", BlockStyle(softBreak = true))
        val l = layout(b.build(), cfg(paragraphSpacingEm = 1f, indentEm = 1f))
        val lines = l.pages[0].lines
        assertEquals(20f, lines[0].x, 0.001f)
        assertEquals(0f, lines[1].x, 0.001f)
        assertEquals(lines[0].bottom, lines[1].top, 0.001f)
    }

    @Test
    fun headingsGetMinimumSpacing() {
        val b = SectionBuilder()
        b.para("본문")
        b.heading("제목")
        b.para("본문")
        val l = layout(b.build(), cfg(paragraphSpacingEm = 0f))
        val lines = l.pages[0].lines
        assertEquals(30f + 20f, lines[1].top, 0.001f) // >= 1 em before
        assertEquals(36f, lines[1].bottom - lines[1].top, 0.001f) // 1.5 em * 1.2
        assertEquals(lines[1].bottom + 12f, lines[2].top, 0.001f) // >= 0.6 em after
    }

    @Test
    fun emptyParagraphIsABlankLineButDroppedAtPageTop() {
        val b = SectionBuilder()
        b.para("가")
        b.para("")
        b.para("나")
        val l = layout(b.build(), cfg())
        val lines = l.pages[0].lines
        assertEquals(3, lines.size)
        assertEquals(60f, lines[2].top, 0.001f)

        val b2 = SectionBuilder()
        b2.para("가")
        b2.para("")
        b2.para("나")
        val l2 = layout(b2.build(), cfg(height = 40))
        assertEquals(2, l2.pageCount)
        // The empty paragraph starts page 2 and is dropped: "나" is at the very top.
        val p1 = l2.pages[1].lines
        assertEquals(1, p1.size)
        assertEquals("나", l2.content.text.substring(p1[0].start, p1[0].end))
        assertEquals(0f, p1[0].top, 0.001f)
    }

    @Test
    fun carriedEmptyParagraphIsDroppedAtPageTop() {
        // body (7 lines, 0..210) + empty paragraph with keepWithNext (210..240) + heading (260..296) + a line
        // that doesn't fit (308..338 > 300): the keep chain carries [empty, heading], and the empty paragraph
        // must not open the next page.
        val b = SectionBuilder()
        b.para("\uAC00".repeat(70))
        val empty = b.para("", BlockStyle(keepWithNext = true))
        val h = b.heading("\uC7A5")
        b.para("\uB098\uB2E4")
        val l = layout(b.build(), cfg(width = 200, height = 300))
        assertEquals(2, l.pageCount)
        assertEquals(7, l.pages[0].lines.size)
        assertEquals(empty, l.pages[1].start)
        val p1 = l.pages[1].lines
        assertEquals(2, p1.size)
        assertEquals(h, p1[0].start)
        assertEquals(0f, p1[0].top, 0.001f)
    }

    @Test
    fun pageBreakBeforeStartsNewPage() {
        val b = SectionBuilder()
        b.para("가")
        b.para("나", BlockStyle(pageBreakBefore = true))
        b.para("다")
        val l = layout(b.build(), cfg())
        assertEquals(2, l.pageCount)
        assertEquals(1, l.pages[0].lines.size)
        assertEquals(2, l.pages[1].start)
        // First block with pageBreakBefore on an empty page does nothing.
        val b2 = SectionBuilder()
        b2.para("가", BlockStyle(pageBreakBefore = true))
        assertEquals(1, layout(b2.build(), cfg()).pageCount)
    }

    // --- horizontal: indent, alignment, insets ------------------------------------------------------

    @Test
    fun firstLineIndent() {
        val b = SectionBuilder()
        b.para("가".repeat(30))
        val l = layout(b.build(), cfg(width = 200, indentEm = 1f))
        val lines = l.pages[0].lines
        assertEquals(20f, lines[0].x, 0.001f)
        assertEquals(9, lines[0].end - lines[0].start) // 180px after the 20px indent
        assertEquals(0f, lines[1].x, 0.001f)
        assertEquals(10, lines[1].end - lines[1].start)
    }

    @Test
    fun centerAndRightAlignmentAndNoIndent() {
        val b = SectionBuilder()
        b.para("가나", BlockStyle(align = Align.CENTER))
        b.para("가나", BlockStyle(align = Align.RIGHT))
        val l = layout(b.build(), cfg(width = 200, indentEm = 1f))
        val lines = l.pages[0].lines
        assertEquals(80f, lines[0].x, 0.001f)
        assertEquals(160f, lines[1].x, 0.001f)
    }

    @Test
    fun publisherAlignmentIgnoredWhenDisabledExceptHeadings() {
        val b = SectionBuilder()
        b.para("가나", BlockStyle(align = Align.CENTER, indent = false))
        b.heading("다라")
        val l = layout(b.build(), cfg(width = 200, publisherStyles = false))
        val lines = l.pages[0].lines
        assertEquals(0f, lines[0].x, 0.001f)
        assertEquals((200f - 48f) / 2f, lines[1].x, 0.001f) // heading 2 * 24px, centred
    }

    @Test
    fun insetsNarrowTheLine() {
        val b = SectionBuilder()
        b.para("가".repeat(20), BlockStyle(insetLeftEm = 1f, insetRightEm = 1f, indent = false))
        val l = layout(b.build(), cfg(width = 200))
        val lines = l.pages[0].lines
        assertEquals(20f, lines[0].x, 0.001f)
        assertEquals(8, lines[0].end - lines[0].start)
    }

    // --- line breaking ------------------------------------------------------------------------------

    @Test
    fun trailingSpacesHangAndLeadingSpacesAreSkipped() {
        val b = SectionBuilder()
        b.para("aaaa   bbbb")
        val l = layout(b.build(), cfg(width = 60))
        val lines = textLines(l)
        assertEquals(2, lines.size)
        assertEquals(0, lines[0].start)
        assertEquals(4, lines[0].end)
        assertEquals(7, lines[1].start)
        assertEquals(11, lines[1].end)
    }

    @Test
    fun spacesMayHangBeyondTheWidth() {
        // "가나다" = 60px exactly; the following spaces hang instead of forcing an extra line.
        val b = SectionBuilder()
        b.para("가나다     라마")
        val l = layout(b.build(), cfg(width = 60))
        assertEquals(listOf("가나다", "라마"), lineTexts(l))
    }

    @Test
    fun charModeBreaksBetweenSyllables() {
        val b = SectionBuilder()
        b.para("가나다 라마바사아자 차카")
        val l = layout(b.build(), cfg(width = 120, lineBreak = LineBreakMode.CHAR))
        assertEquals(listOf("가나다 라마", "바사아자 차", "카"), lineTexts(l))
    }

    @Test
    fun wordModeKeepsEojeolTogether() {
        val b = SectionBuilder()
        b.para("가나다 라마바사아자 차카")
        val l = layout(b.build(), cfg(width = 120, lineBreak = LineBreakMode.WORD))
        assertEquals(listOf("가나다", "라마바사아자", "차카"), lineTexts(l))
    }

    @Test
    fun wordModeFallsBackToCharBreaksForOverlongWords() {
        val b = SectionBuilder()
        b.para("가 나다라마바사아자차카타")
        val l = layout(b.build(), cfg(width = 100, lineBreak = LineBreakMode.WORD))
        assertEquals(listOf("가", "나다라마바", "사아자차카", "타"), lineTexts(l))
    }

    @Test
    fun latinWordsAreNeverSplitUnlessAlone() {
        val b = SectionBuilder()
        b.para("hello world")
        for (mode in LineBreakMode.values()) {
            val l = layout(b.build(), cfg(width = 80, lineBreak = mode))
            assertEquals(listOf("hello", "world"), lineTexts(l))
        }
        val b2 = SectionBuilder()
        b2.para("abcdefghijklmnopqrstuvwxyz")
        val l2 = layout(b2.build(), cfg(width = 100))
        assertEquals(listOf("abcdefghi", "jklmnopqr", "stuvwxyz"), lineTexts(l2))
    }

    @Test
    fun numbersWithSeparatorsStayTogether() {
        val b = SectionBuilder()
        b.para("가격은 1,234.50원")
        val l = layout(b.build(), cfg(width = 120, lineBreak = LineBreakMode.CHAR))
        val lines = lineTexts(l)
        assertTrue(lines.toString(), lines.any { it.contains("1,234.50") })
    }

    @Test
    fun hyphenBreaksBeforeLetters() {
        val b = SectionBuilder()
        b.para("well-known 010-1234")
        val l = layout(b.build(), cfg(width = 90))
        assertEquals(listOf("well-", "known", "010-1234"), lineTexts(l))
    }

    @Test
    fun kinsokuNoClosingPunctuationAtLineStart() {
        for (closing in listOf(".", ",", "!", "?", "\u201D", "\u2019", "\u300D", ")", "~", "\u00B7", "\u3002")) {
            val b = SectionBuilder()
            b.para("가나다라마${closing}바사")
            val l = layout(b.build(), cfg(width = 100, lineBreak = LineBreakMode.CHAR))
            val lines = lineTexts(l)
            assertEquals("closing '$closing'", "가나다라", lines[0])
            assertTrue("closing '$closing': $lines", lines[1].startsWith("마$closing"))
        }
    }

    @Test
    fun kinsokuNoOpeningPunctuationAtLineEnd() {
        for (opening in listOf("\u201C", "\u2018", "\u300C", "(", "[", "\u300A")) {
            val b = SectionBuilder()
            b.para("가나다라${opening}마바")
            val l = layout(b.build(), cfg(width = 100, lineBreak = LineBreakMode.CHAR))
            val lines = lineTexts(l)
            assertEquals("opening '$opening'", "가나다라", lines[0])
            assertEquals("opening '$opening'", "${opening}마바", lines[1])
        }
    }

    @Test
    fun ellipsisAndInterrobangRunsStayTogether() {
        val b = SectionBuilder()
        b.para("가나다라마\u2026\u2026바사")
        val l = layout(b.build(), cfg(width = 100))
        assertEquals(listOf("가나다라", "마\u2026\u2026바사"), lineTexts(l))
        val b2 = SectionBuilder()
        b2.para("가나다라마?!바사")
        assertEquals(listOf("가나다라", "마?!바사"), lineTexts(layout(b2.build(), cfg(width = 100))))
        val b3 = SectionBuilder()
        b3.para("가나다라\u2014\u2014마바")
        val lines3 = lineTexts(layout(b3.build(), cfg(width = 100)))
        assertTrue(lines3.toString(), lines3.none { it.startsWith("\u2014") || it.endsWith("가나다라\u2014") })
    }

    @Test
    fun standaloneEllipsisAfterSpaceMayStartALine() {
        val b = SectionBuilder()
        b.para("\uAC00\uB098\uB2E4\uB77C \u2026\u2026 \uB9C8\uBC14")
        val l = layout(b.build(), cfg(width = 90, lineBreak = LineBreakMode.CHAR))
        assertEquals(listOf("\uAC00\uB098\uB2E4\uB77C", "\u2026\u2026 \uB9C8\uBC14"), lineTexts(l))
        // Attached to a word it still never starts a line.
        val b2 = SectionBuilder()
        b2.para("\uAC00\uB098\uB2E4\uB77C\u2026\u2026 \uB9C8\uBC14")
        assertEquals(listOf("\uAC00\uB098\uB2E4", "\uB77C\u2026\u2026 \uB9C8\uBC14"), lineTexts(layout(b2.build(), cfg(width = 90))))
    }

    @Test
    fun asciiQuotesOpenAfterSpaceAndCloseOtherwise() {
        val b = SectionBuilder()
        b.para("그가 말했다. \"안녕하세요\" 하고")
        val l = layout(b.build(), cfg(width = 150, lineBreak = LineBreakMode.WORD))
        val lines = lineTexts(l)
        assertEquals(listOf("그가 말했다.", "\"안녕하세요\"", "하고"), lines)
    }

    @Test
    fun surrogatePairsAndCombiningMarksAreNeverSplit() {
        val b = SectionBuilder()
        b.para("\uD83D\uDE00".repeat(20) + "e\u0301".repeat(20))
        val l = layout(b.build(), cfg(width = 50))
        val text = l.content.text
        for (ln in textLines(l)) {
            assertFalse(Character.isLowSurrogate(text[ln.start]))
            assertFalse(text[ln.start] == '\u0301')
        }
    }

    @Test
    fun zeroWidthSpaceIsABreakAndNbspIsNot() {
        val b = SectionBuilder()
        b.para("abcd\u200Befgh")
        assertEquals(listOf("abcd\u200B", "efgh"), lineTexts(layout(b.build(), cfg(width = 60))))
        val b2 = SectionBuilder()
        b2.para("ab 가\u00A0나다라")
        // WORD mode: "가 나다라" is glued by the NBSP, so the break happens after "ab ".
        assertEquals(listOf("ab", "가\u00A0나다라"), lineTexts(layout(b2.build(), cfg(width = 100, lineBreak = LineBreakMode.WORD))))
    }

    @Test
    fun cjkIdeographsBreakInWordModeToo() {
        val b = SectionBuilder()
        b.para("\u6F22".repeat(12))
        val lines = lineTexts(layout(b.build(), cfg(width = 100, lineBreak = LineBreakMode.WORD)))
        assertEquals(listOf(5, 5, 2), lines.map { it.length })
    }

    // --- justification --------------------------------------------------------------------------------

    @Test
    fun justifiedLinesFillTheWidthExceptTheLast() {
        val b = SectionBuilder()
        b.para("가나 다라마 바사 아자차 카타파 하가나 다라 마바사 아자카 차카타파하 가나다라")
        val c = cfg(width = 200, align = Align.JUSTIFY, lineBreak = LineBreakMode.WORD, indentEm = 1f)
        val l = layout(b.build(), c)
        val lines = textLines(l)
        assertTrue(lines.size >= 3)
        val pos = FloatArray(64)
        for ((i, ln) in lines.withIndex()) {
            val right = LineGeometry.charPositions(l, ln, pos)
            if (i < lines.size - 1) {
                assertEquals(LineInfo.EXPAND_SPACES, ln.expandMode)
                assertEquals(200f, right, 0.01f)
            } else {
                assertEquals(LineInfo.EXPAND_NONE, ln.expandMode)
            }
        }
        assertEquals(20f, lines[0].x, 0.001f)
    }

    @Test
    fun justifyWithoutSpacesExpandsCharacters() {
        val b = SectionBuilder()
        b.para("가나다라마바사아자차카타파하" + "가.")
        val c = cfg(width = 110, align = Align.JUSTIFY, lineBreak = LineBreakMode.CHAR)
        val l = layout(b.build(), c)
        val first = textLines(l)[0]
        assertEquals(LineInfo.EXPAND_CHARS, first.expandMode)
        assertEquals(110f, LineGeometry.charPositions(l, first, FloatArray(16)), 0.01f)
    }

    @Test
    fun hugeSpaceGapsSwitchToCharExpansionOrNone() {
        // One inner space, 34px of slack > 1.2 em per space -> spread over the 9 char gaps instead.
        val b = SectionBuilder()
        b.para("가나다라마 바사아자 차카타파하")
        val l = layout(b.build(), cfg(width = 220, align = Align.JUSTIFY, lineBreak = LineBreakMode.WORD))
        val first = textLines(l)[0]
        assertEquals("가나다라마 바사아자", l.content.text.substring(first.start, first.end))
        assertEquals(LineInfo.EXPAND_CHARS, first.expandMode)
        assertEquals(220f, LineGeometry.charPositions(l, first, FloatArray(16)), 0.01f)
        // A single short word followed by an unbreakable long one: too much slack -> not justified.
        val b2 = SectionBuilder()
        b2.para("가 abcdefghijklmnopq")
        val l2 = layout(b2.build(), cfg(width = 200, align = Align.JUSTIFY, lineBreak = LineBreakMode.WORD))
        assertEquals(LineInfo.EXPAND_NONE, textLines(l2)[0].expandMode)
    }

    @Test
    fun preformattedIsNeverJustified() {
        val b = SectionBuilder()
        b.para("가나 다라 마바 사아 자차 카타 파하 가나 다라 마바", BlockStyle(preformatted = true))
        val l = layout(b.build(), cfg(width = 100, align = Align.JUSTIFY, lineBreak = LineBreakMode.WORD))
        assertTrue(textLines(l).size > 1)
        assertTrue(textLines(l).all { it.expandMode == LineInfo.EXPAND_NONE })
    }

    // --- pagination: keep-with-next, widows & orphans ----------------------------------------------------

    private fun bodyLines(n: Int) = "가".repeat(10 * n) // 10 syllables per 200px line

    @Test
    fun headingMovesToNextPageWithItsParagraph() {
        // Body 8 lines * 30 = 240; heading at 260..296; the next line would need 296+12+30 = 338 > 300.
        for (keep in listOf(true, false)) {
            val b = SectionBuilder()
            b.para(bodyLines(8))
            val h = b.heading("장 제목", keep = keep)
            b.para(bodyLines(2))
            val l = layout(b.build(), cfg(width = 200, height = 300))
            assertEquals(2, l.pageCount)
            if (keep) {
                assertEquals(h, l.pages[1].start)
                assertEquals(h, l.pages[1].lines[0].start)
                assertEquals(8, l.pages[0].lines.size)
            } else {
                assertEquals(9, l.pages[0].lines.size)
            }
        }
    }

    @Test
    fun headingAloneOnPageIsNotMovedAgain() {
        val mm = FakeMeasurer(images = mapOf("tall" to IntSize(200, 290)))
        val b = SectionBuilder()
        b.para("가")
        b.heading("제목", pageBreak = true)
        b.image("tall")
        val l = layout(b.build(), cfg(width = 200, height = 300), mm)
        assertEquals(3, l.pageCount)
        assertEquals(1, l.pages[1].lines.size)
        assertNotNull(l.pages[2].lines[0].imageBlock)
    }

    @Test
    fun headingChainFillingThePageStaysTogether() {
        // h1 + h2 (both keepWithNext) open a page; the following image doesn't fit after them. Moving the chain
        // would empty the page, so both headings stay and only the image moves.
        val mm = FakeMeasurer(images = mapOf("tall" to IntSize(200, 250)))
        val b = SectionBuilder()
        b.para("\uAC00")
        val h1 = b.heading("\uC81C1\uBD80", level = 1, pageBreak = true)
        val h2 = b.heading("\uC81C1\uC7A5", level = 2)
        val img = b.image("tall")
        val l = layout(b.build(), cfg(width = 200, height = 300), mm)
        assertEquals(3, l.pageCount)
        assertEquals(listOf(h1, h2), l.pages[1].lines.map { it.start })
        assertEquals(img, l.pages[2].start)
    }

    @Test
    fun multiLineHeadingIsNotSplit() {
        val b = SectionBuilder()
        b.para(bodyLines(8))
        val h = b.heading("가".repeat(12)) // 24px glyphs: 8 per 200px line -> 2 lines
        b.para(bodyLines(1))
        val l = layout(b.build(), cfg(width = 200, height = 320))
        assertEquals(h, l.pages[1].start)
    }

    @Test
    fun orphanControlMovesFirstLine() {
        val c = cfg(width = 200, height = 300, widowOrphanControl = true)
        val b = SectionBuilder()
        b.para(bodyLines(9))
        val p2 = b.para(bodyLines(5))
        val l = layout(b.build(), c)
        assertEquals(9, l.pages[0].lines.size)
        assertEquals(p2, l.pages[1].start)
        // Without control the first line stays at the bottom.
        val l2 = layout(b.build(), c.copy(widowOrphanControl = false))
        assertEquals(10, l2.pages[0].lines.size)
    }

    @Test
    fun widowControlMovesOneMoreLine() {
        val c = cfg(width = 200, height = 300, widowOrphanControl = true)
        val b = SectionBuilder()
        b.para(bodyLines(6))
        val p2 = b.para(bodyLines(5))
        val l = layout(b.build(), c)
        // 10 lines per page: p2 would be 4 + 1; widow control makes it 3 + 2.
        assertEquals(9, l.pages[0].lines.size)
        val second = l.pages[1].lines
        assertEquals(2, second.size)
        assertEquals(p2 + 30, second[0].start)
        val l2 = layout(b.build(), c.copy(widowOrphanControl = false))
        assertEquals(10, l2.pages[0].lines.size)
    }

    @Test
    fun threeLineParagraphMovesWhole() {
        val c = cfg(width = 200, height = 300, widowOrphanControl = true)
        val b = SectionBuilder()
        b.para(bodyLines(8))
        val p2 = b.para(bodyLines(3))
        val l = layout(b.build(), c)
        assertEquals(8, l.pages[0].lines.size)
        assertEquals(p2, l.pages[1].start)
    }

    @Test
    fun widowOrphanKeepsAtLeastTwoLines() {
        val c = cfg(width = 200, height = 60, widowOrphanControl = true) // two lines per page
        val b = SectionBuilder()
        b.para(bodyLines(7))
        val l = layout(b.build(), c)
        for (p in l.pages) assertTrue(p.lines.isNotEmpty())
        assertEquals(4, l.pageCount)
    }

    // --- images & rules --------------------------------------------------------------------------------

    private val imgs = FakeMeasurer(
        images = mapOf(
            "big" to IntSize(600, 400),
            "small" to IntSize(100, 50),
            "mid" to IntSize(150, 10),
            "narrowTall" to IntSize(30, 500),
            "upscale" to IntSize(200, 100),
        ),
    )

    private fun imageLine(src: String, c: LayoutConfig): LineInfo {
        val b = SectionBuilder()
        b.image(src)
        val l = layout(b.build(), c, imgs)
        return l.pages[0].lines.single()
    }

    @Test
    fun largeImagesScaleToWidth() {
        val ln = imageLine("big", cfg(width = 400, height = 600))
        assertEquals(400f, ln.imageWidth, 0.01f)
        assertEquals(400f * 400f / 600f, ln.imageHeight, 0.01f)
        assertEquals(0f, ln.x, 0.01f)
        val up = imageLine("upscale", cfg(width = 400, height = 600)) // 200 >= 40% of 400 -> scaled up
        assertEquals(400f, up.imageWidth, 0.01f)
        assertEquals(200f, up.imageHeight, 0.01f)
    }

    @Test
    fun smallImagesAreDoubledAndCentred() {
        val ln = imageLine("small", cfg(width = 400, height = 600))
        assertEquals(200f, ln.imageWidth, 0.01f)
        assertEquals(100f, ln.imageHeight, 0.01f)
        assertEquals(100f, ln.x, 0.01f)
        assertEquals(ln.top + 100f, ln.bottom, 0.01f)
        val mid = imageLine("mid", cfg(width = 400, height = 600))
        assertEquals(300f, mid.imageWidth, 0.01f)
    }

    @Test
    fun imageHeightIsCapped() {
        val ln = imageLine("narrowTall", cfg(width = 400, height = 600))
        assertEquals(600f, ln.imageHeight, 0.01f)
        assertEquals(36f, ln.imageWidth, 0.01f)
        val half = imageLine("narrowTall", cfg(width = 400, height = 600, maxImageHeightFraction = 0.5f))
        assertEquals(300f, half.imageHeight, 0.01f)
        assertEquals(18f, half.imageWidth, 0.01f)
    }

    @Test
    fun unknownImagesAreSkipped() {
        val b = SectionBuilder()
        b.para("가")
        b.image("missing")
        b.para("나")
        val l = layout(b.build(), cfg(), imgs)
        assertEquals(1, l.pageCount)
        assertEquals(2, l.pages[0].lines.size)
        assertTrue(l.pages[0].lines.none { it.imageBlock != null })
    }

    @Test
    fun imageThatDoesNotFitGoesToNextPage() {
        val b = SectionBuilder()
        b.para(bodyLines(5))
        val img = b.image("small")
        val l = layout(b.build(), cfg(width = 400, height = 150), imgs)
        assertEquals(2, l.pageCount)
        assertEquals(img, l.pages[1].start)
        assertNotNull(l.pages[1].lines[0].imageBlock)
        assertEquals(0f, l.pages[1].lines[0].top, 0.001f)
    }

    @Test
    fun rulesAreLinesOfLineHeight() {
        val b = SectionBuilder()
        b.para("가")
        b.rule()
        b.para("나")
        val l = layout(b.build(), cfg())
        val lines = l.pages[0].lines
        assertEquals(3, lines.size)
        assertTrue(lines[1].isRule)
        assertEquals(30f, lines[1].bottom - lines[1].top, 0.001f)
        assertNull(lines[1].imageBlock)
    }

    // --- offsets --------------------------------------------------------------------------------------

    @Test
    fun pageForOffsetFindsThePage() {
        val b = SectionBuilder()
        repeat(40) { b.para(bodyLines(3)) }
        val l = layout(b.build(), cfg(width = 200, height = 300, paragraphSpacingEm = 0.5f))
        assertTrue(l.pageCount > 3)
        for ((i, p) in l.pages.withIndex()) {
            for (o in p.start until p.end) assertEquals(i, l.pageForOffset(o))
        }
        assertEquals(0, l.pageForOffset(-10))
        assertEquals(l.pageCount - 1, l.pageForOffset(l.content.length + 10))
    }

    // --- malformed input ------------------------------------------------------------------------------

    @Test
    fun malformedBlocksAndRunsDoNotCrash() {
        val text = "가나다라\n마바사\n\n아자차카타파하\n" + OBJECT_CHAR
        val blocks = listOf(
            ParagraphBlock(0, 4),
            ParagraphBlock(2, 6), // overlaps
            ParagraphBlock(5, 8),
            ParagraphBlock(12, 9), // reversed
            ImageBlock(17, "x"),
            ImageBlock(50, "y"), // out of range
            RuleBlock(99),
            ParagraphBlock(10, 999), // beyond text
            ParagraphBlock(-3, 2),
        )
        val runs = listOf(
            StyleRun(1, 3, RunStyle(bold = true, sizeScale = Float.NaN)),
            StyleRun(2, 7, RunStyle(sizeScale = -1f)),
            StyleRun(20, 10, RunStyle(italic = true)),
            StyleRun(-5, 1000, RunStyle(sizeScale = 3f)),
        )
        val content = SectionContent(text, blocks, runs)
        val c = cfg(width = 60, height = 50, lineHeightEm = Float.NaN, paragraphSpacingEm = Float.NEGATIVE_INFINITY)
        val l = Typesetter(imgs, c).layout(content)
        LayoutChecks.checkPages(l)
        assertEquals(l.pageCount, Typesetter(imgs, c).countPages(content))
    }

    @Test
    fun newlinesInsideABlockAreHarmless() {
        val content = SectionContent("가나\n다라\n마바", listOf(ParagraphBlock(0, 8)))
        val l = layout(content, cfg(width = 60))
        assertTrue(l.pageCount >= 1)
    }

    @Test
    fun measuresEachBlockOnceAndChunksHugeParagraphs() {
        val mm = FakeMeasurer()
        val b = SectionBuilder()
        b.para("가".repeat(10_000))
        b.para("나")
        Typesetter(mm, cfg()).layout(b.build())
        assertTrue(mm.measureCalls in 4..8)
    }
}
