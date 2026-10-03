package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** U6: Typesetter.layout(content, anchorBreak) makes the item holding the anchor open a page. */
class AnchorBreakTest {
    private val m = FakeMeasurer(images = mapOf("pic" to IntSize(300, 200), "tall" to IntSize(200, 900)))

    private fun cfg(w: Int = 300, h: Int = 400, wo: Boolean = true, pb: PageBreakMode = PageBreakMode.LINE,
                    lh: Float = 1.5f, ps: Float = 0.5f) = LayoutConfig(
        width = w, height = h, lineHeightEm = lh, paragraphSpacingEm = ps, indentEm = 1f, align = Align.JUSTIFY,
        lineBreak = LineBreakMode.CHAR, widowOrphanControl = wo, pageBreak = pb,
    )

    private fun book(seed: Long): SectionContent {
        val r = Random(seed)
        val b = SectionBuilder()
        b.heading("제1화 시작")
        repeat(70) {
            when (r.nextInt(14)) {
                0 -> b.para("")
                1 -> b.heading("장면 $it")
                2 -> b.image("pic")
                3 -> b.rule()
                4 -> b.heading("새 장 $it", pageBreak = true)
                5 -> b.para(SampleText.koreanParagraph(r, r.nextInt(5, 30)), BlockStyle(indent = false, softBreak = true))
                else -> b.para(SampleText.koreanParagraph(r, r.nextInt(10, 500)))
            }
        }
        return b.build()
    }

    private val configs = listOf(cfg(), cfg(wo = false), cfg(w = 260, h = 517), cfg(pb = PageBreakMode.PARAGRAPH),
        cfg(w = 340, h = 300, pb = PageBreakMode.PARAGRAPH, wo = false))

    private fun lay(c: SectionContent, k: LayoutConfig, a: Int = -1): SectionLayout {
        val l = Typesetter(m, k).layout(c, a)
        val t = Typesetter(m, k).count(c, a)
        assertEquals("count == layout", l.pageCount, t.pages)
        assertEquals("anchorPage", l.anchorPage, t.anchorPage)
        assertEquals("anchorShifted", l.anchorShifted, t.anchorShifted)
        LayoutChecks.checkPages(l)
        return l
    }

    @Test
    fun withoutAnAnchorNothingChanges() {
        for (seed in 1L..15L) for (k in configs) {
            val c = book(seed)
            val base = Typesetter(m, k).layout(c)
            for (a in intArrayOf(-1, 0, c.length, c.length + 7)) {
                val l = lay(c, k, a)
                assertEquals(LayoutDigest.of(base), LayoutDigest.of(l))
                assertEquals(-1, l.anchorBreak)
                assertEquals(-1, l.anchorPage)
            }
        }
    }

    @Test
    fun anAnchorAtAPageStartChangesNothing() {
        var conservative = 0
        var tried = 0
        for (seed in 1L..15L) for (k in configs) {
            val c = book(seed)
            val base = lay(c, k)
            for (i in 1 until base.pageCount) {
                val a = base.pages[i].start
                if (a <= 0 || a >= c.length) continue
                val l = lay(c, k, a)
                tried++
                assertEquals("seed $seed page $i", LayoutDigest.of(base), LayoutDigest.of(l))
                assertEquals(i, l.anchorPage)
                if (l.anchorShifted) conservative++
            }
        }
        println("natural page starts reported as shifted (conservative): $conservative of $tried")
    }

    @Test
    fun theAnchorsItemOpensItsPage() {
        val r = Random(7)
        var shifted = 0
        var total = 0
        var minDelta = 0
        var maxDelta = 0
        for (seed in 1L..25L) for (k in configs) {
            val c = book(seed)
            val base = lay(c, k)
            repeat(25) {
                val a = r.nextInt(1, c.length)
                val l = lay(c, k, a)
                total++
                if (l.anchorPage < 0) return@repeat
                if (l.anchorShifted) shifted++ else assertEquals("not shifted => identical", LayoutDigest.of(base), LayoutDigest.of(l))
                val p = l.pages[l.anchorPage]
                assertTrue("page start <= anchor", p.start <= a || p.lines.isEmpty() || p.lines[0].start >= a)
                val first = p.lines.firstOrNull()
                if (first != null) assertTrue("first item holds or follows the anchor", first.end > a || first.start >= a)
                // Everything before the anchor page lies before the anchor.
                for (q in 0 until l.anchorPage) for (ln in l.pages[q].lines) {
                    assertTrue(ln.end <= a || ln.end == ln.start && ln.start < a || ln.imageBlock != null && ln.start < a)
                }
                // Pages before the break are the un-anchored ones; the short page shares their start and a common prefix.
                for (q in 0 until l.anchorPage - 1) assertEquals(LayoutDigest.page(base.pages[q]), LayoutDigest.page(l.pages[q]))
                if (l.anchorPage >= 1) {
                    val s = l.pages[l.anchorPage - 1]
                    val b = base.pages[l.anchorPage - 1]
                    assertEquals(b.start, s.start)
                    val n = minOf(s.lines.size, b.lines.size)
                    for (x in 0 until n) assertEquals(LayoutDigest.line(b.lines[x]), LayoutDigest.line(s.lines[x]))
                }
                // Stitched, the lines are the same lines: only the pagination moved.
                assertEquals(LayoutDigest.flow(base), LayoutDigest.flow(l))
                val d = l.pageCount - base.pageCount
                if (d < minDelta) minDelta = d
                if (d > maxDelta) maxDelta = d
            }
        }
        println("anchors: $total, shifted $shifted, page count delta in [$minDelta, $maxDelta]")
        assertTrue(minDelta >= -1 && maxDelta <= 2)
    }

    @Test
    fun aHeightOnlyChangeKeepsTheExactFirstChar() {
        // Top/bottom margins, line spacing, paragraph spacing, widow control, page-break mode: line starts don't move,
        // so the page the reader was on starts at exactly the same char.
        for (seed in 1L..15L) {
            val c = book(seed)
            val from = lay(c, cfg(h = 400))
            for (to in listOf(cfg(h = 517), cfg(h = 333), cfg(h = 400, lh = 2.0f), cfg(h = 400, ps = 1.2f),
                cfg(h = 400, wo = false), cfg(h = 400, pb = PageBreakMode.PARAGRAPH))) {
                for (i in 1 until from.pageCount) {
                    val a = from.pages[i].start
                    if (a <= 0 || a >= c.length) continue
                    val l = lay(c, to, a)
                    val want = from.pages[i].lines.first { it.end > it.start || it.isRule || it.imageBlock != null }.start
                    val got = l.pages[l.anchorPage].lines.first().start
                    assertEquals("seed $seed page $i", want, got)
                    assertTrue(l.pages[l.anchorPage].start <= a)
                }
            }
        }
    }

    @Test
    fun aWidthChangeKeepsTheAnchorOnTheFirstLineAndComesBackExactly() {
        for (seed in 1L..15L) {
            val c = book(seed)
            val w1 = lay(c, cfg(w = 300))
            for (i in 1 until w1.pageCount) {
                val a = w1.pages[i].start
                if (a <= 0 || a >= c.length) continue
                val w2 = lay(c, cfg(w = 260), a)
                val p = w2.pages[w2.anchorPage]
                assertTrue(p.start <= a)
                val first = p.lines.firstOrNull() ?: continue
                assertTrue(first.end > a || first.start >= a)
                // Font size back / rotation back: the anchor (kept, never replaced by the new page start) is a page
                // start again, so the original pagination returns.
                val back = lay(c, cfg(w = 300), a)
                assertEquals(LayoutDigest.of(w1), LayoutDigest.of(back))
            }
        }
    }

    @Test
    fun theAnchorWinsOverWidowAndKeepRules() {
        val b = SectionBuilder()
        b.para(SampleText.koreanParagraph(Random(1), 300))
        b.heading("소제목")
        val body = b.para(SampleText.koreanParagraph(Random(2), 260))
        val c = b.build()
        val k = cfg(h = 300)
        val base = lay(c, k)
        val lines = base.pages.flatMap { it.lines }.filter { it.start >= body }
        val last = lines.last()
        val l = lay(c, k, last.start)
        assertEquals(last.start, l.pages[l.anchorPage].lines[0].start)
        // The heading's body starts a page even though keep-with-next wants the heading on it.
        val l2 = lay(c, k, body)
        assertEquals(body, l2.pages[l2.anchorPage].lines[0].start)
    }
}
