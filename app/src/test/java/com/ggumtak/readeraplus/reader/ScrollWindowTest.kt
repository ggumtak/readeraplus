package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.*
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ScrollWindowTest {
    private fun layout(): SectionLayout {
        val text = "가".repeat(100)
        return SectionLayout(SectionContent(text, listOf(ParagraphBlock(0, 100))), LayoutConfig(200, 100),
            List(5) { p -> PageInfo(p * 20, (p + 1) * 20, List(2) { i ->
                val start = p * 20 + i * 10
                LineInfo(start, start + 10, 5f, i * 24.5f, i * 24.5f + 20f, (i + 1) * 24.5f, 1.5f, 1)
            }, 3.4f) }, FloatArray(100))
    }
    private class Source(val l: SectionLayout) : StripSource {
        override val sectionCount = 3
        override fun layoutOf(section: Int) = if (section in 0..2) l else null
        override fun unitGap(section: Int) = 11.1f
    }
    @Test fun virtualLinesUseTheIdenticalRoundedDrawingOriginAndKeepAllHitTestFields() {
        val l = layout(); val source = Source(l); val w = ScrollWindow(); val pos = ScrollPos()
        val r = Random(7614)
        repeat(3000) {
            pos.section = r.nextInt(3); pos.page = r.nextInt(5); pos.dy = r.nextFloat() * 40f
            val ct = r.nextInt(15, 90).toFloat(); val h = r.nextInt(80, 150).toFloat()
            w.fill(source, pos, h, ct)
            for (s in 0..2) {
                val virtual = w.virtualPage(s, ct, h) ?: continue
                for (ln in virtual.page.lines) {
                    var index = -1
                    for (i in 0 until w.count) if (w.sections[i] == s && l.pages[w.pages[i]].lines.any { it.start == ln.start }) index = i
                    assertTrue(index >= 0)
                    val original = l.pages[w.pages[index]].lines.first { it.start == ln.start }
                    assertEquals(w.shift(index, ct) + original.top, ln.top, 0f)
                    assertEquals(w.shift(index, ct) + original.baseline, ln.baseline, 0f)
                    assertEquals(original.justifyExtra, ln.justifyExtra, 0f)
                    assertEquals(original.expandMode, ln.expandMode)
                    assertTrue(ln.top >= -0.5f && ln.bottom <= h + 0.5f)
                    assertTrue(w.whollyVisible(s, ln.start, ct, h))
                    assertEquals(s, w.focusAt((ln.top + ln.bottom) / 2f, ct, h))
                }
            }
        }
    }
    @Test fun virtualPagesGiveEverySectionOnScreenInOrderAndAgreeWithTheSingleVirtualPage() {
        val l = layout(); val source = Source(l); val w = ScrollWindow(); val pos = ScrollPos()
        val r = Random(2209)
        var seams = 0
        repeat(2000) {
            pos.section = r.nextInt(3); pos.page = r.nextInt(5); pos.dy = r.nextFloat() * 40f
            val ct = r.nextInt(15, 90).toFloat(); val h = r.nextInt(80, 150).toFloat()
            w.fill(source, pos, h, ct)
            val all = w.virtualPages(ct, h)
            assertEquals(all.map { it.section }.sorted(), all.map { it.section })
            assertEquals(all.size, all.map { it.section }.distinct().size)
            for (s in 0..2) {
                val one = w.virtualPage(s, ct, h)
                val got = all.firstOrNull { it.section == s }
                if (one == null) { assertNull(got); continue }
                assertNotNull(got)
                assertEquals(one.page.lines.map { it.start to it.top }, got!!.page.lines.map { it.start to it.top })
            }
            if (all.size > 1) seams++
        }
        // The screen over a seam (the end of one section, the start of the next) is the case the tree must cover.
        assertTrue(seams > 0)
    }
    @Test fun stepClipEndsAtARoundedWholeLineAndClearedWindowsReleaseLayouts() {
        val l = layout(); val w = ScrollWindow(); val pos = ScrollPos()
        pos.dy = 7.35f
        w.fill(Source(l), pos, 100f, 40f)
        val whole = w.virtualPage(0, 40f, w.wholeBottom)!!
        assertEquals(whole.page.lines.last().bottom, w.wholeBottom, 0f)
        assertEquals(-1, w.focusAt(w.wholeBottom + 1f, 40f, w.wholeBottom))
        w.clear()
        assertEquals(0, w.count); assertTrue(w.layouts.all { it == null }); assertTrue(w.quotes.all { it.isEmpty() })
    }
    @Test fun aLayoutStoredOutsideTheWindowLeavesItsStripsAsTheyWere() {
        // ScrollReader.onSectionStored redraws only when rebuildWindow sees other strips (S §1.8): a neighbour prefetch
        // (s ± 1 after the first page and every settle) is not asked for while the window does not reach it.
        val l = layout(); val have = BooleanArray(3); val asked = ArrayList<Int>()
        val source = object : StripSource {
            override val sectionCount = 3
            override fun layoutOf(section: Int): SectionLayout? { asked += section; return if (have[section]) l else null }
            override fun unitGap(section: Int) = 11.1f
        }
        val w = ScrollWindow(); val pos = ScrollPos()
        have[1] = true; pos.section = 1; pos.page = 1; pos.dy = 7f
        w.fill(source, pos, 100f, 40f)
        assertEquals(-1, w.blockedAt); assertEquals(1, w.first); assertEquals(1, w.last)
        assertEquals(listOf(1), asked.distinct())
        val count = w.count; val pages = w.pages.copyOf(count); val tops = w.tops.copyOf(count)
        val gaps = w.gaps.copyOf(count); val bottom = w.wholeBottom
        have[0] = true; have[2] = true
        w.fill(source, pos, 100f, 40f)
        assertEquals(count, w.count); assertEquals(-1, w.blockedAt); assertEquals(bottom, w.wholeBottom, 0f)
        for (i in 0 until count) {
            assertEquals(1, w.sections[i]); assertSame(l, w.layouts[i]); assertEquals(pages[i], w.pages[i])
            assertEquals(tops[i], w.tops[i], 0f); assertEquals(gaps[i], w.gaps[i], 0f)
        }
        // A window that reaches an unstored section is blocked there; storing it changes the strips.
        have[2] = false; pos.page = 4; pos.dy = 0f
        w.fill(source, pos, 100f, 40f)
        assertEquals(2, w.blockedAt); assertEquals(1, w.last)
        val blockedCount = w.count
        have[2] = true
        w.fill(source, pos, 100f, 40f)
        assertEquals(-1, w.blockedAt); assertEquals(2, w.last); assertTrue(w.count > blockedCount)
    }
    @Test fun longEarlierHighlightsAreNeverLostBehindThousandsOfShorterRanges() {
        val quotes = ArrayList<Highlight>()
        quotes.add(Highlight(0, 100000, HighlightKind.QUOTE, 3))
        repeat(10000) { quotes.add(Highlight(it * 9 + 1, it * 9 + 5, HighlightKind.SEARCH)) }
        val index = ScrollHighlights(quotes)
        val r = Random(129)
        repeat(2000) {
            val start = r.nextInt(100000); val end = start + r.nextInt(1, 300)
            assertEquals(quotes.filter { it.start < end && it.end > start }, index.page(start, end))
        }
    }
    @Test fun nestedHighlightsAndEmptyIntervalsMatchIndependentFiltering() {
        val r = Random(2211)
        repeat(100) {
            val quotes = List(300) { val start = r.nextInt(10000)
                Highlight(start, start + r.nextInt(1, 3000), HighlightKind.QUOTE, r.nextInt(6)) }.sortedBy { it.start }
            val index = ScrollHighlights(quotes)
            repeat(100) {
                val start = r.nextInt(13000); val end = start + r.nextInt(1, 1000)
                assertEquals(quotes.filter { it.start < end && it.end > start }, index.page(start, end))
            }
            assertTrue(index.page(10, 10).isEmpty())
        }
        val empty = ScrollHighlights(emptyList())
        assertSame(emptyList<Highlight>(), empty.page(0, 100))
    }
}
