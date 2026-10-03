package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** U4: PageBreakMode.PARAGRAPH never splits a block that fits on one page. */
class ParagraphModeTest {
    private val m = FakeMeasurer(images = mapOf("pic" to IntSize(300, 200)))

    private fun cfg(w: Int = 300, h: Int = 400, wo: Boolean = true, pb: PageBreakMode = PageBreakMode.PARAGRAPH) =
        LayoutConfig(width = w, height = h, lineHeightEm = 1.5f, paragraphSpacingEm = 0.5f, indentEm = 1f,
            align = Align.JUSTIFY, lineBreak = LineBreakMode.CHAR, widowOrphanControl = wo, pageBreak = pb)

    private fun book(seed: Long): SectionContent {
        val r = Random(seed)
        val b = SectionBuilder()
        repeat(80) {
            when (r.nextInt(12)) {
                0 -> b.para("")
                1 -> b.heading("장면 $it")
                2 -> b.image("pic")
                3 -> b.para(SampleText.koreanParagraph(r, r.nextInt(600, 1500)))   // taller than a page
                else -> b.para(SampleText.koreanParagraph(r, r.nextInt(10, 400)))
            }
        }
        return b.build()
    }

    private fun lay(c: SectionContent, k: LayoutConfig): SectionLayout {
        val l = Typesetter(m, k).layout(c)
        assertEquals(l.pageCount, Typesetter(m, k).countPages(c))
        LayoutChecks.checkPages(l)
        LayoutChecks.checkAll(l, m)
        return l
    }

    /** (page index, line) of every text line of [block], in order. */
    private fun linesOf(l: SectionLayout, block: ParagraphBlock): List<Pair<Int, LineInfo>> {
        val out = ArrayList<Pair<Int, LineInfo>>()
        for ((i, p) in l.pages.withIndex()) for (ln in p.lines) {
            if (ln.imageBlock == null && !ln.isRule && ln.end > ln.start && ln.start >= block.start && ln.start < maxOf(block.end, block.start + 1)) out += i to ln
        }
        return out
    }

    @Test
    fun aBlockThatFitsIsNeverSplit() {
        var moved = 0
        for (seed in 1L..30L) for (k in listOf(cfg(), cfg(wo = false), cfg(w = 260, h = 517))) {
            val c = book(seed)
            val l = lay(c, k)
            val line = Typesetter(m, k.copy(pageBreak = PageBreakMode.LINE)).layout(c)
            if (l.pageCount > line.pageCount) moved++
            for (b in c.blocks) {
                if (b !is ParagraphBlock || b.end <= b.start) continue
                val ls = linesOf(l, b)
                if (ls.isEmpty()) continue
                val h = ls.sumOf { (it.second.bottom - it.second.top).toDouble() }
                val pages = ls.map { it.first }.toSet()
                if (h <= k.height + 0.01) assertEquals("block ${b.start} (h=$h) split: $pages", 1, pages.size)
            }
            // Same lines as LINE mode: only the pagination differs.
            assertEquals(LayoutDigest.flow(line), LayoutDigest.flow(l))
        }
        assertTrue(moved > 0)
    }

    @Test
    fun aHeadingStaysWithTheBlockItIntroduces() {
        for (seed in 1L..30L) {
            val c = book(seed)
            val k = cfg()
            val l = lay(c, k)
            for ((i, p) in l.pages.withIndex()) {
                if (i == l.pageCount - 1) continue
                val last = p.lines.lastOrNull { it.end > it.start } ?: continue
                val bi = c.blockIndexAt(last.start)
                val b = c.blocks[bi] as? ParagraphBlock ?: continue
                if (b.style.headingLevel == 0) continue
                // A heading ends this page: its next block did not fit together with it (or is taller than a page).
                // Only a heading directly followed by a body block (no blank, image or second heading between).
                val next = c.blocks.getOrNull(bi + 1) as? ParagraphBlock ?: continue
                if (next.end <= next.start || next.style.headingLevel > 0 || next.style.pageBreakBefore) continue
                val nl = linesOf(l, next)
                val h = nl.sumOf { (it.second.bottom - it.second.top).toDouble() }
                val hh = linesOf(l, b).sumOf { (it.second.bottom - it.second.top).toDouble() }
                assertTrue("seed $seed page $i: heading alone at the bottom though h=$h hh=$hh", h > k.height || hh + h + 60 > k.height)
            }
        }
    }

    @Test
    fun lineModeIsTheDefault() {
        assertEquals(PageBreakMode.LINE, LayoutConfig(100, 100).pageBreak)
    }
}
