package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.VerticalMargin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 상하 여백 moves the page's lines up or down only the way the margins push them (user, 2026-10-09). */
class MarginShiftTest {

    private val s = ReaderSettings()
    private val density = 2f
    private val em = 40f
    private val pitch = LayoutKeys.linePitch(s, em)

    /** Lines of 10 chars, one pitch apart; pages of [perPage] lines from [first] on, the lines before it on one page. */
    private fun layout(lines: Int, first: Int, perPage: Int, odd: Set<Int> = emptySet(), blank: Set<Int> = emptySet()): SectionLayout {
        val text = "가".repeat(lines * 10)
        val content = SectionContent(text, listOf(ParagraphBlock(0, text.length)))
        fun line(i: Int, row: Int): LineInfo {
            val y = row * pitch
            return if (i in odd) {
                LineInfo(i * 10, i * 10 + 10, 0f, y, y + 30f, y + pitch, 0f, LineInfo.EXPAND_NONE,
                    imageBlock = ImageBlock(i * 10, "a.png"), imageWidth = 10f, imageHeight = 10f)
            } else {
                LineInfo(i * 10, if (i in blank) i * 10 else i * 10 + 10, 0f, y, y + 30f, y + pitch, 0f, LineInfo.EXPAND_NONE)
            }
        }
        val pages = ArrayList<PageInfo>()
        if (first > 0) pages += PageInfo(0, first * 10, (0 until first).map { line(it, it) })
        var i = first
        while (i < lines) {
            val end = minOf(lines, i + perPage)
            pages += PageInfo(i * 10, end * 10, (i until end).map { line(it, it - i) })
            i = end
        }
        return SectionLayout(content, LayoutConfig(600, 1000), pages, FloatArray(text.length))
    }

    @Test
    fun aLineFewerGivesTheTopLineToThePageBefore() {
        val l = layout(100, 30, 16)
        assertEquals(31 * 10, MarginShift.start(l, 1, 300, 1))
        assertEquals(32 * 10, MarginShift.start(l, 1, 300, 2))
        // the anchor inside the top line counts from that line
        assertEquals(31 * 10, MarginShift.start(l, 1, 305, 1))
        // a line more takes the last one of the page before back
        assertEquals(29 * 10, MarginShift.start(l, 1, 300, -1))
        assertEquals(27 * 10, MarginShift.start(l, 1, 300, -3))
        assertEquals(-1, MarginShift.start(l, 1, 300, 0))
    }

    @Test
    fun theSectionsStartAndPicturesKeepThePage() {
        // nothing before the first line of the section
        val first = layout(100, 0, 16)
        assertEquals(-1, MarginShift.start(first, 0, 0, -1))
        assertEquals(10, MarginShift.start(first, 0, 0, 1))
        // a picture is not a line: it never crosses the top
        val pic = layout(100, 30, 16, odd = setOf(30))
        assertEquals(-1, MarginShift.start(pic, 1, 300, 1))
        val before = layout(100, 30, 16, odd = setOf(29))
        assertEquals(-1, MarginShift.start(before, 1, 300, -1))
        // one line always stays
        val short = layout(32, 30, 16)
        assertEquals(-1, MarginShift.start(short, 1, 300, 2))
        assertEquals(31 * 10, MarginShift.start(short, 1, 300, 1))
    }

    @Test
    fun aBlankLineIsNeverTheNewTop() {
        // Line 29 (before the page) and line 31 (on it) are blank paragraphs: a page drops a blank line on its top.
        val l = layout(100, 30, 16, blank = setOf(29, 31))
        // raising: past the blank line to the next with text
        assertEquals(32 * 10, MarginShift.start(l, 1, 300, 1))
        // lowering: back past the blank line too
        assertEquals(28 * 10, MarginShift.start(l, 1, 300, -1))
        // lowering then raising lands where it started
        val lowered = layout(100, 28, 16, blank = setOf(29, 31))
        assertEquals(30 * 10, MarginShift.start(lowered, 1, 280, 1))
        // only the line just before the page is blank
        val gap = layout(100, 30, 16, blank = setOf(29))
        assertEquals(28 * 10, MarginShift.start(gap, 1, 300, -1))
    }

    @Test
    fun onlyTheVerticalMarginsCount() {
        val t = s.copy(marginTopDp = s.marginTopDp + 2, marginBottomDp = s.marginBottomDp + 2)
        assertTrue(LayoutKeys.verticalMarginsOnly(s, t))
        assertTrue(LayoutKeys.verticalMarginsOnly(s, s.copy(marginBottomDp = 30)))
        assertFalse(LayoutKeys.verticalMarginsOnly(s, s))
        assertFalse(LayoutKeys.verticalMarginsOnly(s, t.copy(lineHeightPct = 150)))
        assertFalse(LayoutKeys.verticalMarginsOnly(s, t.copy(marginLeftDp = s.marginLeftDp + 2)))
        assertFalse(LayoutKeys.verticalMarginsOnly(s, t.copy(pageMargins = !s.pageMargins)))
    }

    @Test
    fun raisingTheMarginsSqueezesTheLinesAndNeverSlidesThemDown() {
        // The Comet's page, 1 em = 40 px, 줄 간격 200 %: the 상하 여백 stepper swept up and back down as the reader
        // applies it (the box and its filled line from the margins, the page start from MarginShift). Raising: the lines
        // from the middle of the box down move up, the upper half down by no more than the top margin did, and a step that drops a line
        // moves no line down. Lowering is the mirror. The page is back at its first line at the end.
        fun g(ui: Int) = LayoutKeys.geometry(
            s.copy(marginTopDp = VerticalMargin.topDp(ui), marginBottomDp = VerticalMargin.bottomDp(ui)), 720, 1440, density, emPx = em,
        )
        fun pitchOf(geo: PageGeometry) = LayoutKeys.config(s, geo).lineHeightEm * em
        val start = 200
        var first = start
        var drops = 0
        fun step(from: Int, to: Int) {
            val a = g(from)
            val b = g(to)
            val pa = pitchOf(a)
            val pb = pitchOf(b)
            val na = LayoutKeys.linesIn(a.contentHeight, pa)
            val nb = LayoutKeys.linesIn(b.contentHeight, pb)
            val l = layout(1000, first, na)
            val off = MarginShift.start(l, 1, first * 10, na - nb)
            val next = if (off < 0) first else off / 10
            if (na != nb) drops++
            val raising = to > from
            val topMoved = (b.contentTop - a.contentTop).toFloat()
            for (j in maxOf(first, next) until minOf(first + na, next + nb)) {
                val before = a.contentTop + (j - first) * pa
                val after = b.contentTop + (j - next) * pb
                val moved = after - before
                val what = "ui $from→$to line $j: $before → $after"
                if (raising) {
                    if (2 * (j - next) >= nb || na != nb) assertTrue(what, moved <= 0.01f)
                    assertTrue(what, moved <= topMoved + 0.01f)
                } else {
                    if (2 * (j - next) >= nb || na != nb) assertTrue(what, moved >= -0.01f)
                    assertTrue(what, moved >= topMoved - 0.01f)
                }
            }
            // the bottom of the text goes the margins' way
            val bottomBefore = a.contentTop + na * pa
            val bottomAfter = b.contentTop + nb * pb
            if (raising) assertTrue(bottomAfter < bottomBefore) else assertTrue(bottomAfter > bottomBefore)
            first = next
        }
        for (ui in -10 until 64 step 2) step(ui, ui + 2)
        assertTrue("the sweep drops lines", drops >= 2)
        for (ui in 64 downTo -8 step 2) step(ui, ui - 2)
        assertEquals(start, first)
    }
}
