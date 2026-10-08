package com.ggumtak.readeraplus.render.pdftext

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGlyphsOpsTest {
    private val eps = 0.001f

    /** Lines of 10x12 boxes: line i has top 100 + 20 i, char j spans x 10+10j .. 20+10j. */
    private fun glyphs(vararg lines: String): PageGlyphs {
        val sb = StringBuilder()
        val boxes = ArrayList<Float>()
        val starts = ArrayList<Int>()
        for ((i, line) in lines.withIndex()) {
            val top = 100f + 20f * i
            if (i > 0) {
                val x = 10f + 10f * lines[i - 1].length
                sb.append('\n')
                boxes.addAll(listOf(x, top - 20f, x, top - 8f))
            }
            starts.add(sb.length)
            for ((j, ch) in line.withIndex()) {
                sb.append(ch)
                boxes.addAll(listOf(10f + 10f * j, top, 20f + 10f * j, top + 12f))
            }
        }
        return PageGlyphs(sb.toString(), boxes.toFloatArray(), starts.toIntArray())
    }

    @Test
    fun lineBoxesGiveRangesAndUnionBoxes() {
        val g = glyphs("Hello", "World")
        val lines = PageGlyphsOps.lineBoxes(g)
        assertEquals(2, lines.size)
        assertEquals(0, lines[0].start)
        assertEquals(5, lines[0].end)
        assertArrayEquals(floatArrayOf(10f, 100f, 60f, 112f), lines[0].box, eps)
        assertEquals(6, lines[1].start)
        assertEquals(11, lines[1].end)
        assertArrayEquals(floatArrayOf(10f, 120f, 60f, 132f), lines[1].box, eps)
        assertTrue(PageGlyphsOps.lineBoxes(PageGlyphs.EMPTY).isEmpty())
    }

    @Test
    fun nearestCharInsideOutsideAndNeverNewline() {
        val g = glyphs("Hello", "World")
        assertEquals(1, PageGlyphsOps.nearestChar(g, 25f, 105f))
        assertEquals(4, PageGlyphsOps.nearestChar(g, 500f, 105f)) // right of line 1 -> its last char, not '\n'
        assertEquals(8, PageGlyphsOps.nearestChar(g, 35f, 117f)) // between lines, closer to line 2 -> 'r'
        assertEquals(0, PageGlyphsOps.nearestChar(g, 0f, 0f))
        assertEquals(10, PageGlyphsOps.nearestChar(g, 1000f, 1000f))
        assertEquals(-1, PageGlyphsOps.nearestChar(PageGlyphs.EMPTY, 1f, 1f))
        for (y in 90..140 step 3) for (x in 0..80 step 5) {
            val i = PageGlyphsOps.nearestChar(g, x.toFloat(), y.toFloat())
            assertTrue(i >= 0 && g.text[i] != '\n')
        }
    }

    @Test
    fun selectInReadingOrderWithEitherPointOrder() {
        val g = glyphs("Hello", "World")
        val a = PageGlyphsOps.select(g, 25f, 105f, 45f, 105f)!!
        assertEquals("ell", a.text)
        assertEquals(1, a.boxes.size)
        assertArrayEquals(floatArrayOf(20f, 100f, 50f, 112f), a.boxes[0], eps)
        val b = PageGlyphsOps.select(g, 45f, 105f, 25f, 105f)!!
        assertEquals("ell", b.text)
        val c = PageGlyphsOps.select(g, 58f, 125f, 42f, 105f)!!
        assertEquals("lo\nWorld", c.text)
        assertEquals(2, c.boxes.size)
        assertArrayEquals(floatArrayOf(40f, 100f, 60f, 112f), c.boxes[0], eps)
        assertArrayEquals(floatArrayOf(10f, 120f, 60f, 132f), c.boxes[1], eps)
        assertNull(PageGlyphsOps.select(PageGlyphs.EMPTY, 0f, 0f, 1f, 1f))
    }

    @Test
    fun selectTrimsWhitespace() {
        val g = glyphs(" ab ")
        val s = PageGlyphsOps.select(g, 12f, 105f, 38f, 105f)!!
        assertEquals("ab", s.text)
        assertArrayEquals(floatArrayOf(20f, 100f, 40f, 112f), s.boxes[0], eps)
        assertNull(PageGlyphsOps.select(glyphs("   "), 12f, 105f, 18f, 105f))
    }

    @Test
    fun searchIsCaseInsensitiveAndSpansLines() {
        val g = glyphs("Hello", "World")
        val m = PageGlyphsOps.search(g, "hello WORLD")
        assertEquals(1, m.size)
        assertEquals(2, m[0].size)
        assertArrayEquals(floatArrayOf(10f, 100f, 60f, 112f), m[0][0], eps)
        assertArrayEquals(floatArrayOf(10f, 120f, 60f, 132f), m[0][1], eps)

        val o = PageGlyphsOps.search(g, "O")
        assertEquals(2, o.size)
        assertArrayEquals(floatArrayOf(50f, 100f, 60f, 112f), o[0][0], eps)
        assertArrayEquals(floatArrayOf(20f, 120f, 30f, 132f), o[1][0], eps)

        val lo = PageGlyphsOps.search(g, "lo wo")
        assertEquals(1, lo.size)
        assertArrayEquals(floatArrayOf(40f, 100f, 60f, 112f), lo[0][0], eps)
        assertArrayEquals(floatArrayOf(10f, 120f, 30f, 132f), lo[0][1], eps)
    }

    @Test
    fun searchWhitespaceRunsAndBlankQueries() {
        val g = glyphs("a  b", "c")
        assertEquals(1, PageGlyphsOps.search(g, "a b").size)
        assertEquals(1, PageGlyphsOps.search(g, "  a \n\n  b  ").size)
        assertEquals(1, PageGlyphsOps.search(g, "b c").size)
        assertEquals(0, PageGlyphsOps.search(g, "ab").size)
        assertTrue(PageGlyphsOps.search(g, "   ").isEmpty())
        assertTrue(PageGlyphsOps.search(g, "").isEmpty())
        assertTrue(PageGlyphsOps.search(PageGlyphs.EMPTY, "x").isEmpty())
        // non-overlapping repeats
        assertEquals(2, PageGlyphsOps.search(glyphs("aaaa"), "aa").size)
        // Korean and mixed case
        assertEquals(1, PageGlyphsOps.search(glyphs("안녕 Hello"), "녕 hello").size)
        assertNotNull(PageGlyphsOps.search(glyphs("x"), "x").firstOrNull())
    }
}
