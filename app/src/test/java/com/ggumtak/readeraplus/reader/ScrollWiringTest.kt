package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.settings.ScrollStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollWiringTest {
    /** [pages] pages of 4 lines of 10 chars each. */
    private fun section(pages: Int): SectionLayout {
        val text = "가".repeat(pages * 40)
        val content = SectionContent(text, listOf(ParagraphBlock(0, text.length)))
        val list = ArrayList<PageInfo>()
        var start = 0
        repeat(pages) {
            val first = start
            val ls = ArrayList<LineInfo>()
            repeat(4) { i ->
                ls.add(LineInfo(start, start + 10, 0f, i * 30f, i * 30f + 20f, (i + 1) * 30f, 0f, LineInfo.EXPAND_NONE))
                start += 10
            }
            list.add(PageInfo(first, start, ls, 10f))
        }
        return SectionLayout(content, LayoutConfig(200, 500), list, FloatArray(text.length))
    }

    @Test
    fun switchOffsetKeepsTheAnchorLineOnThePageShown() {
        // After a relayout / earlier switch the anchor is the exact line being read.
        assertEquals(57, ScrollWiring.switchOffset(2, 57, 2, 40, 80, lastPage = false))
        // After a paged turn it is the page start itself.
        assertEquals(40, ScrollWiring.switchOffset(2, 40, 2, 40, 80, lastPage = false))
    }

    @Test
    fun switchOffsetFallsBackToThePageStart() {
        assertEquals(40, ScrollWiring.switchOffset(1, 57, 2, 40, 80, lastPage = false))
        assertEquals(40, ScrollWiring.switchOffset(2, 80, 2, 40, 80, lastPage = false))
        assertEquals(40, ScrollWiring.switchOffset(2, 12, 2, 40, 80, lastPage = false))
        // The end of the last page belongs to it.
        assertEquals(80, ScrollWiring.switchOffset(2, 80, 2, 40, 80, lastPage = true))
        // An empty page holds its own start.
        assertEquals(40, ScrollWiring.switchOffset(2, 40, 2, 40, 40, lastPage = false))
    }

    @Test
    fun roundTripsPutTheSameLineBackOnTop() {
        val l = section(3)
        // scroll → paged: the page holding the anchor line, anchor kept; paged → scroll: that line again.
        for (anchor in listOf(0, 10, 40, 50, 70, 110)) {
            val idx = AnchorMath.pageFor(l, anchor)
            val p = l.pages[idx]
            assertEquals(anchor, ScrollWiring.switchOffset(0, anchor, 0, p.start, p.end, idx == l.pageCount - 1))
        }
    }

    @Test
    fun midLineJumpsUseContextPlacement() {
        val l = section(2)
        assertTrue(ScrollWiring.isLineStart(l, 0))
        assertTrue(ScrollWiring.isLineStart(l, 10))
        assertTrue(ScrollWiring.isLineStart(l, 40))
        assertFalse(ScrollWiring.isLineStart(l, 15))
        assertFalse(ScrollWiring.isLineStart(l, 79))
        assertTrue(ScrollWiring.isLineStart(l, 80))
        assertTrue(ScrollWiring.contextPlacement(jump = true, lineStart = false))
        assertFalse(ScrollWiring.contextPlacement(jump = true, lineStart = true))
        assertFalse(ScrollWiring.contextPlacement(jump = false, lineStart = false))
    }

    @Test
    fun motionResolvesAutoByDeviceClass() {
        // AUTO follows the finger everywhere (2026-10-04), e-ink and an unknown device class included.
        assertFalse(ScrollWiring.stepMotion(ScrollStyle.AUTO, true))
        assertFalse(ScrollWiring.stepMotion(ScrollStyle.AUTO, null))
        assertFalse(ScrollWiring.stepMotion(ScrollStyle.AUTO, false))
        assertTrue(ScrollWiring.stepMotion(ScrollStyle.STEP, false))
        assertFalse(ScrollWiring.stepMotion(ScrollStyle.SMOOTH, true))
    }

    @Test
    fun flushIsCappedAtTenSteps() {
        assertEquals(0, ScrollWiring.flushSteps(0))
        assertEquals(3, ScrollWiring.flushSteps(-3))
        assertEquals(10, ScrollWiring.flushSteps(10))
        assertEquals(10, ScrollWiring.flushSteps(-250))
    }

    @Test
    fun flushKeepsTheStepsNotAppliedWhenOneWaitsForItsSection() {
        // Step 3 of 10 waits in the viewport: steps 4..9 go back to the backlog; a dropped step 3 goes back too.
        assertEquals(6, ScrollWiring.flushLeft(10, 3, waiting = true))
        assertEquals(7, ScrollWiring.flushLeft(10, 3, waiting = false))
        assertEquals(0, ScrollWiring.flushLeft(1, 0, waiting = true))
        assertEquals(1, ScrollWiring.flushLeft(1, 0, waiting = false))
        assertEquals(0, ScrollWiring.flushLeft(10, 9, waiting = true))
    }

    @Test
    fun chapterPageIndexTreatsAMidSectionViewportAsInside() {
        assertEquals(0, ScrollWiring.chapterPageIndex(0, 0))
        assertEquals(1, ScrollWiring.chapterPageIndex(15, 0))
        assertEquals(4, ScrollWiring.chapterPageIndex(15, 4))
    }

    @Test
    fun menuTextsAndToasts() {
        assertEquals("스크롤로 보기", ScrollWiring.modeItem(false))
        assertEquals("페이지로 보기", ScrollWiring.modeItem(true))
        assertEquals("자동 넘김 켜기", ScrollWiring.autoItem(scroll = false, on = false))
        assertEquals("자동 넘김 끄기", ScrollWiring.autoItem(scroll = false, on = true))
        assertEquals("자동 스크롤 켜기", ScrollWiring.autoItem(scroll = true, on = false))
        assertEquals("자동 스크롤 끄기", ScrollWiring.autoItem(scroll = true, on = true))
        assertEquals("자동 스크롤 켜짐 · 한 화면에 30초", ScrollWiring.autoScrollOn(30))
        assertEquals("자동 넘김 꺼짐", ScrollWiring.autoOff(false))
        assertEquals("자동 스크롤 꺼짐", ScrollWiring.autoOff(true))
    }

    @Test
    fun packedPositions() {
        val p = (7L shl 32) or 123456L
        assertEquals(7, ScrollWiring.section(p))
        assertEquals(123456, ScrollWiring.offset(p))
        assertEquals(0, ScrollWiring.offset(5L shl 32))
    }
}
