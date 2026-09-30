package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LineGeometry.glyphAt] and the glyph band ([LineGeometry.bandTop] / [LineGeometry.bandBottom]): a long-press selects
 * only with the finger on a glyph, never on margins, leading, paragraph gaps, blank tails, spaces or images.
 * Fake measurer: em = 20 px, ascent 16 / descent 4; at lineHeightEm 2.0 a body line box is 40 px with the baseline
 * 26 px below its top, so the band is top + 7 .. top + 32.
 */
class GlyphAtTest {

    private val m = FakeMeasurer(images = mapOf("pic" to IntSize(100, 50)))
    private val slop = 4f

    private fun cfg() = LayoutConfig(200, 1000, 2.0f, 0.5f, 1f, Align.JUSTIFY, LineBreakMode.CHAR, true, 1f, true)

    /** Three paragraphs: a justified multi-line one, a short one (blank tail), one with Latin and ideographic spaces. */
    private fun threeParagraphs(): SectionLayout {
        val b = SectionBuilder()
        b.para("가나다라마바사아자차카타파하 가나다라마바사아자차카타파하 가나다라마바사")
        b.para("가나 다")
        b.para("ab cd 가　나", BlockStyle(indent = false))
        return Typesetter(m, cfg()).layout(b.build())
    }

    /** A body paragraph, an image, then a heading paragraph at 1.4× size. */
    private fun imageAndHeading(): SectionLayout {
        val b = SectionBuilder()
        b.para("가나다라마")
        b.image("pic")
        b.para("제목", BlockStyle(align = Align.CENTER, indent = false, headingLevel = 1), RunStyle(bold = true, sizeScale = 1.4f))
        return Typesetter(m, cfg()).layout(b.build())
    }

    private fun positions(l: SectionLayout, ln: LineInfo): FloatArray {
        val out = FloatArray(ln.end - ln.start + 1)
        out[ln.end - ln.start] = LineGeometry.charPositions(l, ln, out)
        return out
    }

    private fun textLines(l: SectionLayout): List<LineInfo> =
        LayoutChecks.allLines(l).filter { it.imageBlock == null && !it.isRule && it.end > it.start }

    /** Middle of the glyph band of [ln]. */
    private fun bandMid(l: SectionLayout, ln: LineInfo): Float =
        (LineGeometry.bandTop(l, ln) + LineGeometry.bandBottom(l, ln)) / 2f

    @Test
    fun bandFollowsBaselineAndEm() {
        val l = threeParagraphs()
        for (ln in textLines(l)) {
            assertEquals(40f, ln.bottom - ln.top, 0.001f)
            assertEquals(ln.top + 26f, ln.baseline, 0.001f)
            assertEquals(ln.baseline - 19f, LineGeometry.bandTop(l, ln), 0.001f)
            assertEquals(ln.baseline + 6f, LineGeometry.bandBottom(l, ln), 0.001f)
            // The band lies inside the line box: the rest is half-leading (blank paper).
            assertTrue(LineGeometry.bandTop(l, ln) > ln.top && LineGeometry.bandBottom(l, ln) < ln.bottom)
        }
    }

    @Test
    fun glyphCentresHitTheirChar() {
        for (l in listOf(threeParagraphs(), imageAndHeading())) {
            val t = l.content.text
            var checked = 0
            for (p in l.pages) {
                for (ln in p.lines) {
                    if (ln.imageBlock != null || ln.isRule || ln.end <= ln.start) continue
                    val xs = positions(l, ln)
                    val y = bandMid(l, ln)
                    for (i in ln.start until ln.end) {
                        if (!(l.advances[i] > 0f) || t[i] == ' ' || t[i] == '　') continue
                        val cx = (xs[i - ln.start] + xs[i - ln.start] + l.advances[i]) / 2f
                        assertEquals("char $i '${t[i]}'", i, LineGeometry.glyphAt(l, p, cx, y, slop))
                        assertEquals("char $i '${t[i]}' without slop", i, LineGeometry.glyphAt(l, p, cx, y, 0f))
                        checked++
                    }
                }
            }
            assertTrue(checked >= 7)
        }
    }

    @Test
    fun leftMarginIsNothing() {
        val l = threeParagraphs()
        val p = l.pages[0]
        for (ln in textLines(l)) {
            val y = bandMid(l, ln)
            assertEquals(-1, LineGeometry.glyphAt(l, p, -10f, y, slop))
            assertEquals(-1, LineGeometry.glyphAt(l, p, ln.x - slop - 0.5f, y, slop))
        }
        // The first-line indent is blank too.
        val first = p.lines[0]
        assertEquals(20f, first.x, 0.001f)
        assertEquals(-1, LineGeometry.glyphAt(l, p, 10f, bandMid(l, first), slop))
    }

    @Test
    fun glyphEdgePlusMinusSlop() {
        val l = threeParagraphs()
        val p = l.pages[0]
        // Second line of the first paragraph: no indent, first glyph at x = 0.
        val ln = p.lines[1]
        assertEquals(0f, ln.x, 0.001f)
        val y = bandMid(l, ln)
        assertEquals(ln.start, LineGeometry.glyphAt(l, p, -slop + 0.5f, y, slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, -slop - 0.5f, y, slop))
        // Vertical edges of the band: no vertical slop by default, [slopY] when asked for.
        val cx = l.advances[ln.start] / 2f
        val top = LineGeometry.bandTop(l, ln)
        val bottom = LineGeometry.bandBottom(l, ln)
        assertEquals(ln.start, LineGeometry.glyphAt(l, p, cx, top + 0.5f, slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, cx, top - 0.5f, slop))
        assertEquals(ln.start, LineGeometry.glyphAt(l, p, cx, bottom - 0.5f, slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, cx, bottom + 0.5f, slop))
        assertEquals(ln.start, LineGeometry.glyphAt(l, p, cx, top - slop + 0.5f, slop, slopY = slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, cx, top - slop - 0.5f, slop, slopY = slop))
        assertEquals(ln.start, LineGeometry.glyphAt(l, p, cx, bottom + slop - 0.5f, slop, slopY = slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, cx, bottom + slop + 0.5f, slop, slopY = slop))
        // Right edge of the last glyph of the short paragraph "가나 다".
        val short = p.lines.first { l.content.text.substring(it.start, it.end) == "가나 다" }
        val right = positions(l, short)[short.end - short.start]
        val ys = bandMid(l, short)
        assertEquals(short.end - 1, LineGeometry.glyphAt(l, p, right + slop - 0.5f, ys, slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, right + slop + 0.5f, ys, slop))
    }

    @Test
    fun blankTailOfAShortLine() {
        val l = threeParagraphs()
        val p = l.pages[0]
        val short = p.lines.first { l.content.text.substring(it.start, it.end) == "가나 다" }
        val right = positions(l, short)[short.end - short.start]
        assertTrue(right < 100f)
        val y = bandMid(l, short)
        for (x in floatArrayOf(right + 10f, 120f, 199f, 250f)) assertEquals("x=$x", -1, LineGeometry.glyphAt(l, p, x, y, slop))
    }

    @Test
    fun leadingAboveAndBelowTheGlyphsIsNothing() {
        val l = threeParagraphs()
        val p = l.pages[0]
        for (ln in textLines(l)) {
            val h = ln.bottom - ln.top
            val cx = ln.x + l.advances[ln.start] / 2f
            assertEquals(-1, LineGeometry.glyphAt(l, p, cx, ln.top + 0.05f * h, slop))
            assertEquals(-1, LineGeometry.glyphAt(l, p, cx, ln.bottom - 0.05f * h, slop))
        }
        // Between two lines of one paragraph (the boxes touch; the bands do not).
        val a = p.lines[0]
        val b = p.lines[1]
        assertEquals(a.bottom, b.top, 0.001f)
        assertEquals(-1, LineGeometry.glyphAt(l, p, b.x + 5f, b.top, slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, b.x + 5f, a.bottom - 1f, slop))
    }

    /**
     * The reported bug at the presets' 170% line height, with the reader's real slop (4 dp ≈ 0.2 em at 20 sp): the
     * white gap between two lines of one paragraph selects nothing. Only the band itself (top + 4 .. top + 29 of a
     * 34 px box here) is live, whatever the sideways slop.
     */
    @Test
    fun gapBetweenLinesAtOneSeventyPercentIsNothing() {
        val cfg = LayoutConfig(200, 1000, 1.7f, 0.5f, 1f, Align.JUSTIFY, LineBreakMode.CHAR, true, 1f, true)
        val b = SectionBuilder()
        b.para("가나다라마바사아자차카타파하 가나다라마바사아자차카타파하 가나다라마바사", BlockStyle(indent = false))
        val l = Typesetter(m, cfg).layout(b.build())
        val p = l.pages[0]
        val a = p.lines[0]
        val c = p.lines[1]
        assertEquals(34f, a.bottom - a.top, 0.001f)
        assertEquals(a.bottom, c.top, 0.001f)
        val realSlop = 0.2f * 20f
        val x = c.x + 30f
        // From the bottom of line a's band to the top of line c's band: all blank paper.
        val gapTop = LineGeometry.bandBottom(l, a)
        val gapBottom = LineGeometry.bandTop(l, c)
        assertTrue(gapBottom - gapTop > 8f)
        var y = gapTop + 0.5f
        while (y < gapBottom) {
            assertEquals("y=$y", -1, LineGeometry.glyphAt(l, p, x, y, realSlop))
            y += 1f
        }
        assertEquals(-1, LineGeometry.glyphAt(l, p, x, (gapTop + gapBottom) / 2f, realSlop))
        // Just inside either band still selects.
        assertTrue(LineGeometry.glyphAt(l, p, x, gapTop - 0.5f, realSlop) in a.start until a.end)
        assertTrue(LineGeometry.glyphAt(l, p, x, gapBottom + 0.5f, realSlop) in c.start until c.end)
    }

    @Test
    fun paragraphGapIsNothing() {
        val l = threeParagraphs()
        val p = l.pages[0]
        val short = p.lines.indexOfFirst { l.content.text.substring(it.start, it.end) == "가나 다" }
        val prev = p.lines[short - 1]
        val next = p.lines[short]
        assertEquals(10f, next.top - prev.bottom, 0.001f) // paragraph spacing 0.5 em
        for (x in floatArrayOf(5f, 30f, 100f)) {
            assertEquals(-1, LineGeometry.glyphAt(l, p, x, (prev.bottom + next.top) / 2f, slop))
        }
    }

    @Test
    fun belowTheLastLineIsNothing() {
        val l = threeParagraphs()
        assertEquals(1, l.pageCount)
        val p = l.pages[0]
        val last = p.lines.last()
        assertEquals(-1, LineGeometry.glyphAt(l, p, last.x + 5f, last.bottom + 1f, slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, last.x + 5f, last.bottom + 20f, slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, 50f, 999f, slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, 50f, -5f, slop))
    }

    @Test
    fun spacesAreNothing() {
        val l = threeParagraphs()
        val p = l.pages[0]
        val t = l.content.text
        val ln = p.lines.last()
        assertEquals("ab cd 가　나", t.substring(ln.start, ln.end))
        val xs = positions(l, ln)
        val y = bandMid(l, ln)
        // "ab cd": the space is 6 px wide, so a slop of 1 px from either neighbour does not reach its centre.
        val sp = t.indexOf(' ', ln.start)
        val spCentre = xs[sp - ln.start] + l.advances[sp] / 2f
        assertEquals(-1, LineGeometry.glyphAt(l, p, spCentre, y, 1f))
        assertEquals(-1, LineGeometry.glyphAt(l, p, spCentre, y, 0f))
        // An ideographic space (U+3000) is a full em of blank paper.
        val ideo = t.indexOf('　', ln.start)
        val ideoCentre = xs[ideo - ln.start] + l.advances[ideo] / 2f
        assertEquals(20f, l.advances[ideo], 0.001f)
        assertEquals(-1, LineGeometry.glyphAt(l, p, ideoCentre, y, slop))
        // Within slop of a neighbour, the neighbour glyph (never the space) is returned.
        assertEquals(sp - 1, LineGeometry.glyphAt(l, p, xs[sp - ln.start] + 2f, y, slop))
        assertEquals(sp + 1, LineGeometry.glyphAt(l, p, xs[sp + 1 - ln.start] - 2f, y, slop))
        // The reader's slop (0.2 em) is wider than half this space: every point of it gives a neighbour, never the
        // space (a long press there selects that neighbour's word).
        val realSlop = 0.2f * 20f
        var x = xs[sp - ln.start]
        while (x < xs[sp + 1 - ln.start]) {
            val g = LineGeometry.glyphAt(l, p, x, y, realSlop)
            assertTrue("x=$x gave $g", g == sp - 1 || g == sp + 1)
            x += 0.5f
        }
        // An ideographic space is wider than twice that slop: its middle stays blank.
        assertEquals(-1, LineGeometry.glyphAt(l, p, ideoCentre, y, realSlop))
    }

    @Test
    fun imageLineIsNothing() {
        val l = imageAndHeading()
        val p = l.pages[0]
        val img = p.lines.first { it.imageBlock != null }
        val cx = img.x + img.imageWidth / 2f
        for (y in floatArrayOf(img.top + 1f, (img.top + img.bottom) / 2f, img.bottom - 1f)) {
            assertEquals(-1, LineGeometry.glyphAt(l, p, cx, y, slop))
        }
        // hitTest (tap / selection extension) still finds the image.
        assertEquals(img.imageBlock!!.start, LineGeometry.hitTest(l, p, cx, (img.top + img.bottom) / 2f))
    }

    @Test
    fun headingLineAtOnePointFourScale() {
        val l = imageAndHeading()
        val p = l.pages[0]
        val t = l.content.text
        val head = p.lines.last()
        assertEquals("제목", t.substring(head.start, head.end))
        // Line box 2.0 × 20 × 1.4 = 56 px; natural height 28 px; baseline (56 - 28) / 2 + 22.4 below the top.
        assertEquals(56f, head.bottom - head.top, 0.001f)
        assertEquals(head.top + 36.4f, head.baseline, 0.001f)
        val e = 28f
        assertEquals(head.baseline - 0.95f * e, LineGeometry.bandTop(l, head), 0.001f)
        assertEquals(head.baseline + 0.30f * e, LineGeometry.bandBottom(l, head), 0.001f)
        val xs = positions(l, head)
        val cx = xs[0] + l.advances[head.start] / 2f
        assertEquals(28f, l.advances[head.start], 0.001f)
        // The top of a large glyph is inside the scaled band (an unscaled 20 px em would miss it).
        assertEquals(head.start, LineGeometry.glyphAt(l, p, cx, head.baseline - 25f, 0f))
        assertEquals(head.start, LineGeometry.glyphAt(l, p, cx, head.baseline + 8f, 0f))
        assertEquals(head.start + 1, LineGeometry.glyphAt(l, p, xs[1] + 14f, bandMid(l, head), 0f))
        // Still blank above and below the band, and beside the centred word.
        assertEquals(-1, LineGeometry.glyphAt(l, p, cx, head.top + 0.05f * 56f, slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, cx, head.bottom - 0.05f * 56f, slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, xs[0] - slop - 1f, bandMid(l, head), slop))
        assertEquals(-1, LineGeometry.glyphAt(l, p, xs[2] + slop + 1f, bandMid(l, head), slop))
    }

    @Test
    fun emptyPageAndEmptyLine() {
        val empty = Typesetter(m, cfg()).layout(SectionContent.EMPTY)
        assertEquals(-1, LineGeometry.glyphAt(empty, empty.pages[0], 10f, 10f, slop))
        val b = SectionBuilder()
        b.para("가")
        b.para("")
        b.para("나")
        val l = Typesetter(m, cfg()).layout(b.build())
        val blank = l.pages[0].lines[1]
        assertEquals(blank.start, blank.end)
        assertEquals(-1, LineGeometry.glyphAt(l, l.pages[0], 25f, (blank.top + blank.bottom) / 2f, slop))
    }
}
