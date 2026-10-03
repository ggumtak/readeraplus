package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small fixtures identify the space swallowed by each kind of page boundary. */
class PageLeadTest {
    private val cfg = LayoutConfig(200, 100, lineHeightEm = 1.5f, paragraphSpacingEm = 0.5f,
        indentEm = 0f, align = Align.LEFT, widowOrphanControl = true)
    private val m = FakeMeasurer()

    private fun lay(b: SectionBuilder, c: LayoutConfig = cfg, anchor: Int = -1): SectionLayout {
        val section = b.build()
        val layout = Typesetter(m, c).layout(section, anchor)
        assertEquals(layout.pageCount, Typesetter(m, c).count(section, anchor).pages)
        assertTrue(layout.pages.all { it.lead >= 0f })
        return layout
    }

    @Test fun betweenParagraphsRestoresSpaceBefore() {
        val b = SectionBuilder()
        repeat(5) { b.para("가나다라마") }
        val l = lay(b)
        assertEquals(3, l.pageCount)
        l.pages.forEach { assertEquals(10f, it.lead, 0f) }
    }

    @Test fun aBreakInsideAParagraphHasNoLead() {
        val b = SectionBuilder()
        b.para("가".repeat(100))
        val l = lay(b, cfg.copy(widowOrphanControl = false))
        assertTrue(l.pageCount > 1)
        assertEquals(10f, l.pages.first().lead, 0f)
        l.pages.drop(1).forEach { assertEquals(0f, it.lead, 0f) }
    }

    @Test fun sceneBlankAtThePageTopIsRestored() {
        val b = SectionBuilder()
        b.para("가")
        b.para("나")
        b.para("")
        b.para("다")
        val l = lay(b)
        assertEquals(2, l.pageCount)
        assertEquals(50f, l.pages[1].lead, 0f) // blank sb + blank h + following sb
    }

    @Test fun pageBreakBeforeAddsTheSceneGap() {
        val b = SectionBuilder()
        b.para("가")
        b.heading("새 장면", pageBreak = true)
        val l = lay(b)
        assertEquals(2, l.pageCount)
        assertEquals(20f + 2f * m.emPx, l.pages[1].lead, 0f)
    }

    @Test fun initialBlanksAndInitialMarginAreRecorded() {
        val b = SectionBuilder()
        b.para("")
        b.para("가", BlockStyle(marginTopEm = 0.5f))
        val l = lay(b)
        assertEquals(60f, l.pages[0].lead, 0f)
    }

    @Test fun anOrphanMoveRestoresTheParagraphMargin() {
        val b = SectionBuilder()
        b.para("가")
        b.para("나")
        val start = b.para("다".repeat(30))
        val l = lay(b, cfg.copy(height = 110))
        assertEquals(start, l.pages[1].lines[0].start)
        assertEquals(10f, l.pages[1].lead, 0f)
    }

    @Test fun aMovedHeadingChainRestoresItsMargin() {
        val b = SectionBuilder()
        b.para("가")
        val heading = b.heading("제목")
        b.para("나".repeat(20))
        val l = lay(b, cfg.copy(height = 110))
        assertEquals(heading, l.pages[1].lines[0].start)
        assertEquals(20f, l.pages[1].lead, 0f)
    }

    @Test fun finishPreservesTheLastPagesLeadWhenTrailingBlanksAreDropped() {
        val b = SectionBuilder()
        b.para("가")
        b.heading("다음", pageBreak = true)
        b.para("", BlockStyle(pageBreakBefore = true))
        val l = lay(b)
        assertEquals(2, l.pageCount)
        assertEquals(60f, l.pages.last().lead, 0f)
        assertEquals(l.content.length, l.pages.last().end)
    }

    @Test fun anchoringInsideTheParagraphKeepsItsContinuousLead() {
        val b = SectionBuilder()
        b.para("가".repeat(80))
        val l = lay(b, cfg.copy(height = 500), anchor = 25)
        assertEquals(2, l.pageCount)
        assertEquals(0f, l.pages[1].lead, 0f)
    }
}
