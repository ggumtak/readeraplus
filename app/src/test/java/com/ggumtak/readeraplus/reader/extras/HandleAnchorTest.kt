package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.BlockStyle
import com.ggumtak.readeraplus.engine.FakeMeasurer
import com.ggumtak.readeraplus.engine.IntSize
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.engine.LineGeometry
import com.ggumtak.readeraplus.engine.RectPx
import com.ggumtak.readeraplus.engine.SectionBuilder
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.engine.Typesetter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selection handles sit under the letters (glyph band), and a dragged handle's hit point stays on its own line. */
class HandleAnchorTest {

    private val m = FakeMeasurer(images = mapOf("pic" to IntSize(100, 50)))

    /** Two wrapped paragraphs, a heading (1.2 scale) and an image, at 200% line height. */
    private fun layout(lineHeightEm: Float = 2f): SectionLayout {
        val b = SectionBuilder()
        b.para("가나다라마바사아자차카타파하 가나다라마바사아자차카타파하 가나다라마바사", BlockStyle(indent = false))
        b.heading("제목 줄")
        b.image("pic")
        b.para("아자차카타파하 가나다라마바사아자차카타파하 가나다", BlockStyle(indent = false))
        val cfg = LayoutConfig(200, 2000, lineHeightEm, 0.5f, 1f, Align.LEFT, LineBreakMode.CHAR, true, 1f, true)
        return Typesetter(m, cfg).layout(b.build())
    }

    @Test
    fun anchorIsTheGlyphBandBottomOfTheRectsOwnLine() {
        val l = layout()
        val page = l.pages[0]
        val text = page.lines.filter { it.imageBlock == null && it.end > it.start }
        assertTrue(text.size >= 4)
        for (ln in text) {
            val rects = LineGeometry.rangeRects(l, page, ln.start, ln.end)
            assertEquals(1, rects.size)
            val r = rects[0]
            assertSame(ln, HandleAnchor.lineOf(page, r))
            val bottom = HandleAnchor.bottom(l, page, r)
            assertEquals(LineGeometry.bandBottom(l, ln), bottom, 0.001f)
            // Under the baseline (descenders), above the line box's blank bottom leading.
            assertTrue(bottom > ln.baseline)
            assertTrue(bottom < ln.bottom)
        }
    }

    @Test
    fun dragLiftStaysOnTheSameLine() {
        for (lh in floatArrayOf(1f, 1.5f, 2f, 3f)) {
            val l = layout(lh)
            val page = l.pages[0]
            for (ln in page.lines) {
                if (ln.imageBlock != null || ln.end <= ln.start) continue
                val r = LineGeometry.rangeRects(l, page, ln.start, ln.end)[0]
                val half = HandleAnchor.halfHeight(l, page, r)
                assertEquals((LineGeometry.bandBottom(l, ln) - LineGeometry.bandTop(l, ln)) / 2f, half, 0.001f)
                val y = HandleAnchor.bottom(l, page, r) - half
                assertTrue("lh=$lh: lifted point inside its line box", y >= ln.top && y < ln.bottom)
                val off = LineGeometry.hitTest(l, page, (r.left + r.right) / 2f, y)
                assertTrue("lh=$lh: hit $off in [${ln.start}, ${ln.end})", off >= ln.start && off < ln.end)
            }
        }
    }

    /**
     * At 100% the line box is only the font's natural height, so the raw band (0.95 / 0.30 em around the baseline)
     * would reach past it. The highlight is drawn inside the box, and the handle tip meets its bottom edge.
     */
    @Test
    fun anchorStaysOnTheHighlightAtTightLineHeights() {
        val l = layout(1f)
        val page = l.pages[0]
        for (ln in page.lines) {
            if (ln.imageBlock != null || ln.end <= ln.start) continue
            assertTrue(ln.baseline + 0.30f * (ln.bottom - ln.top) > ln.bottom)
            val r = LineGeometry.rangeRects(l, page, ln.start, ln.end)[0]
            val bottom = HandleAnchor.bottom(l, page, r)
            assertEquals(ln.bottom, bottom, 0.001f)
            assertEquals(ln.top, LineGeometry.bandTop(l, ln), 0.001f)
            assertEquals((ln.bottom - ln.top) / 2f, HandleAnchor.halfHeight(l, page, r), 0.001f)
        }
    }

    @Test
    fun selectionSpanningLinesAnchorsFirstAndLastLine() {
        val l = layout()
        val page = l.pages[0]
        val first = page.lines[0]
        val second = page.lines[1]
        val rects = LineGeometry.rangeRects(l, page, first.start + 2, second.start + 3)
        assertEquals(2, rects.size)
        assertSame(first, HandleAnchor.lineOf(page, rects.first()))
        assertSame(second, HandleAnchor.lineOf(page, rects.last()))
        assertTrue(HandleAnchor.bottom(l, page, rects.first()) < HandleAnchor.bottom(l, page, rects.last()))
    }

    @Test
    fun headingBandFollowsItsScale() {
        val l = layout()
        val page = l.pages[0]
        val body = page.lines[0]
        val heading = page.lines.first { it.start == l.content.text.indexOf("제목") }
        val rb = LineGeometry.rangeRects(l, page, body.start, body.end)[0]
        val rh = LineGeometry.rangeRects(l, page, heading.start, heading.end)[0]
        val hb = HandleAnchor.halfHeight(l, page, rb)
        val hh = HandleAnchor.halfHeight(l, page, rh)
        assertEquals(1.2f, hh / hb, 0.01f)
    }

    @Test
    fun imageRectKeepsItsBox() {
        val l = layout()
        val page = l.pages[0]
        val img = page.lines.first { it.imageBlock != null }
        val r = LineGeometry.rangeRects(l, page, img.imageBlock!!.start, img.imageBlock!!.start + 1)[0]
        assertNull(HandleAnchor.lineOf(page, r))
        assertEquals(r.bottom, HandleAnchor.bottom(l, page, r), 0f)
        assertEquals((r.bottom - r.top) / 2f, HandleAnchor.halfHeight(l, page, r), 0f)
    }

    @Test
    fun unknownRectFallsBackToItsBox() {
        val l = layout()
        val page = l.pages[0]
        val r = RectPx(0f, -40f, 10f, -20f)
        assertNull(HandleAnchor.lineOf(page, r))
        assertEquals(-20f, HandleAnchor.bottom(l, page, r), 0f)
        assertEquals(10f, HandleAnchor.halfHeight(l, page, r), 0f)
    }
}
