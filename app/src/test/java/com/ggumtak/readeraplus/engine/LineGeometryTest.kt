package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineGeometryTest {

    private val m = FakeMeasurer(images = mapOf("pic" to IntSize(100, 50)))

    private fun cfg(width: Int = 200, align: Align = Align.JUSTIFY, mode: LineBreakMode = LineBreakMode.WORD) =
        LayoutConfig(width, 1000, 1.5f, 0.5f, 1f, align, mode, true, 1f, true)

    private fun sample(): SectionLayout {
        val b = SectionBuilder()
        b.para("가나 다라마 바사 아자차 카타파 하가나 다라 마바사 아자카 차카타파하 가나다라")
        b.para("hello world, 가나다라마바사아자차카타파하 abc")
        b.image("pic")
        b.para("가나다라마바사아자차카타파하가나다라마바사아자", BlockStyle(indent = false))
        return Typesetter(m, cfg()).layout(b.build())
    }

    @Test
    fun charPositionsArePrefixSumsWithoutJustification() {
        val b = SectionBuilder()
        b.para("ab 가나")
        val l = Typesetter(m, cfg(align = Align.LEFT)).layout(b.build())
        val ln = l.pages[0].lines[0]
        val out = FloatArray(5)
        val right = LineGeometry.charPositions(l, ln, out)
        assertEquals(20f, ln.x, 0.001f) // indent
        val expected = floatArrayOf(20f, 31f, 42f, 48f, 68f)
        for (i in 0 until 5) assertEquals(expected[i], out[i], 0.001f)
        assertEquals(88f, right, 0.001f)
    }

    @Test
    fun charPositionsIncludeSpaceExpansion() {
        val l = sample()
        val ln = l.pages[0].lines[0]
        assertEquals(LineInfo.EXPAND_SPACES, ln.expandMode)
        val out = FloatArray(ln.end - ln.start)
        LineGeometry.charPositions(l, ln, out)
        val t = l.content.text
        // The char after each space is shifted by the space advance + justifyExtra.
        for (i in ln.start + 1 until ln.end) {
            val prev = i - 1
            val expected = out[prev - ln.start] + l.advances[prev] + if (t[prev] == ' ') ln.justifyExtra else 0f
            assertEquals(expected, out[i - ln.start], 0.001f)
        }
    }

    @Test
    fun charPositionsIncludeCharExpansion() {
        val b = SectionBuilder()
        b.para("가나다라마바사아자차카타파하가.", BlockStyle(indent = false))
        val l = Typesetter(m, cfg(width = 110, mode = LineBreakMode.CHAR)).layout(b.build())
        val ln = l.pages[0].lines[0]
        assertEquals(LineInfo.EXPAND_CHARS, ln.expandMode)
        val out = FloatArray(8)
        val right = LineGeometry.charPositions(l, ln, out)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(22.5f, out[1], 0.001f)
        assertEquals(90f, out[4], 0.001f)
        assertEquals(110f, right, 0.001f)
    }

    @Test
    fun hitTestRoundTripsWithCharPositions() {
        val l = sample()
        for (p in l.pages) {
            for (ln in p.lines) {
                val y = (ln.top + ln.bottom) / 2f
                if (ln.imageBlock != null) {
                    assertEquals(ln.imageBlock!!.start, LineGeometry.hitTest(l, p, ln.x + 1f, y))
                    continue
                }
                val out = FloatArray(ln.end - ln.start + 1)
                LineGeometry.charPositions(l, ln, out)
                for (i in ln.start until ln.end) {
                    val a = l.advances[i]
                    if (a <= 0f) continue
                    assertEquals("char $i", i, LineGeometry.hitTest(l, p, out[i - ln.start] + a / 2f, y))
                }
            }
        }
    }

    @Test
    fun hitTestClampsAndHandlesGaps() {
        val l = sample()
        val p = l.pages[0]
        val first = p.lines[0]
        val y = (first.top + first.bottom) / 2f
        assertEquals(first.start, LineGeometry.hitTest(l, p, -50f, y))
        assertEquals(first.end - 1, LineGeometry.hitTest(l, p, 10_000f, y))
        // Just above the first line (within half a line height): snaps to it.
        assertEquals(first.start, LineGeometry.hitTest(l, p, 0f, first.top - 5f))
        // Far below everything: nothing.
        assertEquals(-1, LineGeometry.hitTest(l, p, 10f, 5000f))
        // In the paragraph gap between two lines: nearest line.
        val second = p.lines.first { it.top > first.bottom + 1f }
        val prev = p.lines[p.lines.indexOf(second) - 1]
        val gapY = (prev.bottom + second.top) / 2f
        val hit = LineGeometry.hitTest(l, p, 30f, gapY)
        assertTrue(hit >= prev.start && hit < second.end)
    }

    @Test
    fun hitTestOnEmptyPageOrEmptyLine() {
        val empty = Typesetter(m, cfg()).layout(SectionContent.EMPTY)
        assertEquals(-1, LineGeometry.hitTest(empty, empty.pages[0], 10f, 10f))
        val b = SectionBuilder()
        b.para("가")
        b.para("")
        b.para("나")
        val l = Typesetter(m, cfg()).layout(b.build())
        val blank = l.pages[0].lines[1]
        assertEquals(-1, LineGeometry.hitTest(l, l.pages[0], 10f, (blank.top + blank.bottom) / 2f))
    }

    @Test
    fun rangeRectsCoverSelectedLines() {
        val l = sample()
        val p = l.pages[0]
        val l0 = p.lines[0]
        val l1 = p.lines[1]
        val start = l0.start + 3
        val end = l1.start + 2
        val rects = LineGeometry.rangeRects(l, p, start, end)
        assertEquals(2, rects.size)
        val out0 = FloatArray(l0.end - l0.start)
        val right0 = LineGeometry.charPositions(l, l0, out0)
        assertEquals(out0[3], rects[0].left, 0.001f)
        assertEquals(right0, rects[0].right, 0.001f)
        assertEquals(l0.top, rects[0].top, 0.001f)
        assertEquals(l0.bottom, rects[0].bottom, 0.001f)
        val out1 = FloatArray(l1.end - l1.start)
        LineGeometry.charPositions(l, l1, out1)
        assertEquals(l1.x, rects[1].left, 0.001f)
        assertEquals(out1[2], rects[1].right, 0.001f)
        assertTrue(LineGeometry.rangeRects(l, p, 5, 5).isEmpty())
        assertTrue(LineGeometry.rangeRects(l, p, l.content.length + 5, l.content.length + 9).isEmpty())
    }

    @Test
    fun rangeRectsIncludeImages() {
        val l = sample()
        val p = l.pages[0]
        val img = p.lines.first { it.imageBlock != null }
        val rects = LineGeometry.rangeRects(l, p, img.start, img.start + 1)
        assertEquals(1, rects.size)
        assertEquals(img.x, rects[0].left, 0.001f)
        assertEquals(img.x + img.imageWidth, rects[0].right, 0.001f)
        val out = FloatArray(1)
        assertEquals(img.x + img.imageWidth, LineGeometry.charPositions(l, img, out), 0.001f)
        assertEquals(img.x, out[0], 0.001f)
    }

    private fun word(text: String, offset: Int): String {
        val packed = LineGeometry.wordAt(text, offset)
        return text.substring(LineGeometry.packedStart(packed), LineGeometry.packedEnd(packed))
    }

    @Test
    fun wordAtHangulLatinAndPunctuation() {
        val t = "안녕하세요, world123 세계! \uD83D\uDE00 e\u0301t\u00E9"
        assertEquals("안녕하세요", word(t, 0))
        assertEquals("안녕하세요", word(t, 4))
        assertEquals(",", word(t, 5))
        assertEquals(" ", word(t, 6))
        assertEquals("world123", word(t, 9))
        assertEquals("세계", word(t, 16))
        assertEquals("세계", word(t, 17))
        assertEquals("!", word(t, 18))
        assertEquals("\uD83D\uDE00", word(t, 20))
        assertEquals("\uD83D\uDE00", word(t, 21))
        assertEquals("e\u0301t\u00E9", word(t, 23))
        assertEquals("e\u0301t\u00E9", word(t, t.length + 10)) // clamped to the last char
        assertEquals("안녕하세요", word(t, -4))
        assertEquals(0L, LineGeometry.wordAt("", 3))
        val packed = LineGeometry.wordAt("ab cd", 4)
        assertEquals((3L shl 32) or 5L, packed)
    }
}
