package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.BlockStyle
import com.ggumtak.readeraplus.engine.FakeMeasurer
import com.ggumtak.readeraplus.engine.IntSize
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.engine.RunStyle
import com.ggumtak.readeraplus.engine.SectionBuilder
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.engine.Typesetter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageA11yModelTest {

    private val m = FakeMeasurer(images = mapOf("pic" to IntSize(100, 50)))

    private fun cfg(width: Int = 200, height: Int = 1000) =
        LayoutConfig(width, height, 1.5f, 0.5f, 1f, Align.LEFT, LineBreakMode.CHAR, true, 1f, true)

    private fun layout(width: Int = 200, height: Int = 1000, fill: SectionBuilder.() -> Unit): SectionLayout {
        val b = SectionBuilder()
        b.fill()
        return Typesetter(m, cfg(width, height)).layout(b.build())
    }

    private fun nodes(l: SectionLayout, page: Int = 0, left: Float = 0f, top: Float = 0f, w: Int = 400, h: Int = 1000) =
        A11yFragments.build(l, l.pages[page], left, top, w, h)

    @Test fun oneNodePerParagraphInReadingOrder() {
        val l = layout { para("첫째 문단입니다"); para("둘째 문단"); para("셋째") }
        val n = nodes(l)
        assertEquals(listOf("첫째 문단입니다", "둘째 문단", "셋째"), n.map { it.text })
        assertTrue(n.all { it.role == A11yRole.PARAGRAPH })
        assertEquals(listOf(0, 9, 15), n.map { it.start })
        assertTrue(n[0].bottom <= n[1].top && n[1].bottom <= n[2].top)
    }

    @Test fun wrappedParagraphIsOneNodeCoveringItsLines() {
        val l = layout(width = 100) { para("가나다라마바사아자차카타파하가나다라마바사아자차") }
        val lines = l.pages[0].lines
        assertTrue(lines.size > 2)
        val n = nodes(l, left = 10f, top = 20f)
        assertEquals(1, n.size)
        assertEquals(lines.first().top.toInt() + 20, n[0].top)
        assertEquals(Math.ceil((lines.last().bottom + 20f).toDouble()).toInt(), n[0].bottom)
        assertTrue(n[0].left >= 10 && n[0].right <= 110)
    }

    @Test fun paragraphSplitOverPagesGivesAPartOnEachPage() {
        val l = layout(width = 100, height = 100) {
            para("가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차")
        }
        assertTrue(l.pages.size >= 2)
        val a = nodes(l, 0)
        val b = nodes(l, 1)
        assertEquals(1, a.size)
        assertEquals(1, b.size)
        // Same paragraph start (the stable key), different part of its text.
        assertEquals(a[0].start, b[0].start)
        assertEquals(a[0].textEnd, b[0].textStart)
        assertNotEquals(a[0].text, b[0].text)
        assertTrue(a[0].bottom <= 100)
    }

    @Test fun pictureOnlyPartReadsPictureAndRulesAndBlanksGiveNothing() {
        val l = layout { para("앞"); rule(); para(""); image("pic"); para("뒤") }
        val n = nodes(l)
        assertEquals(listOf("앞", A11yText.PICTURE, "뒤"), n.map { it.text })
    }

    @Test fun layoutMarksAreNotRead() {
        val l = layout { para("하­이픈 ￼다음") }
        assertEquals("하이픈 다음", nodes(l)[0].text)
    }

    @Test fun headingIsMarked() {
        val l = layout { heading("제1장"); para("본문") }
        val n = nodes(l)
        assertTrue(n[0].heading)
        assertFalse(n[1].heading)
    }

    @Test fun linkIsAChildNodeAfterItsParagraphInsideItsBounds() {
        val l = layout {
            paraWithRun("앞 링크글자 뒤", 2, 6, RunStyle(link = "ch2.xhtml#a"))
            para("다음")
        }
        val n = nodes(l)
        assertEquals(listOf(A11yRole.PARAGRAPH, A11yRole.LINK, A11yRole.PARAGRAPH), n.map { it.role })
        val link = n[1]
        assertEquals("링크글자", link.text)
        assertEquals("ch2.xhtml#a", link.href)
        assertEquals(2, link.start)
        assertTrue(link.left >= n[0].left && link.right <= n[0].right)
        assertTrue(link.top >= n[0].top && link.bottom <= n[0].bottom)
        assertTrue(link.right - link.left < n[0].right - n[0].left)
        assertNull(n[0].href)
    }

    @Test fun neighbouringRunsOfOneTargetMergeAndOtherTargetsSplit() {
        val b = SectionBuilder()
        val s = b.para("가나다라마바")
        val c = b.build()
        val runs = listOf(
            com.ggumtak.readeraplus.engine.StyleRun(s, s + 2, RunStyle(link = "x")),
            com.ggumtak.readeraplus.engine.StyleRun(s + 2, s + 3, RunStyle(bold = true, link = "x")),
            com.ggumtak.readeraplus.engine.StyleRun(s + 3, s + 5, RunStyle(link = "y")),
        )
        val content = com.ggumtak.readeraplus.engine.SectionContent(c.text, c.blocks, runs)
        val l = Typesetter(m, cfg()).layout(content)
        val links = nodes(l).filter { it.role == A11yRole.LINK }
        assertEquals(listOf("가나다", "라마"), links.map { it.text })
        assertEquals(listOf("x", "y"), links.map { it.href })
    }

    @Test fun linkOnlyInTheRangeOfThePageShown() {
        val l = layout(width = 100, height = 100) {
            paraWithRun("가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차", 30, 34, RunStyle(link = "z"))
        }
        val all = (0 until l.pages.size).map { nodes(l, it).filter { n -> n.role == A11yRole.LINK } }
        assertEquals(1, all.count { it.isNotEmpty() })
    }

    @Test fun rectangleOutsideTheViewIsDroppedAndOneHalfOutsideIsCut() {
        val l = layout { para("위"); para("아래") }
        val full = nodes(l)
        // The view ends inside the second paragraph: its box is cut there.
        val cutAt = (full[1].top + full[1].bottom) / 2
        val cut = nodes(l, h = cutAt)
        assertEquals(2, cut.size)
        assertEquals(cutAt, cut[1].bottom)
        val only = nodes(l, h = full[0].bottom)
        assertEquals(listOf("위"), only.map { it.text })
        // Shifted left of the view: clipped at 0.
        val shifted = nodes(l, left = -1000f)
        assertTrue(shifted.isEmpty())
        assertTrue(nodes(l, left = -25f).all { it.left == 0 })
    }

    @Test fun emptyViewAndEmptyContentGiveNothing() {
        val l = layout { para("가") }
        assertTrue(nodes(l, w = 0).isEmpty())
        assertTrue(nodes(l, h = 0).isEmpty())
    }

    @Test fun hitTestFindsTheNodeAndALinkWinsOverItsParagraph() {
        val l = layout { paraWithRun("앞 링크글자 뒤", 2, 6, RunStyle(link = "t")) }
        val n = nodes(l)
        val link = n[1]
        val cx = (link.left + link.right) / 2f
        val cy = (link.top + link.bottom) / 2f
        assertEquals(1, A11yFragments.indexAt(n, cx, cy))
        assertEquals(0, A11yFragments.indexAt(n, n[0].left + 1f, cy))
        assertEquals(-1, A11yFragments.indexAt(n, -5f, -5f))
        assertEquals(-1, A11yFragments.indexAt(n, n[0].right.toFloat(), cy))
    }

    @Test fun linkOverSeveralLinesIsOnlyWhereItsLinesAre() {
        // A link that starts late on one line and ends early on the next: the box around it holds plain text too.
        val l = layout(width = 100) { paraWithRun("가나다라마바사아자차카타파하", 4, 11, RunStyle(link = "t")) }
        val n = nodes(l)
        val link = n.first { it.role == A11yRole.LINK }
        val parts = link.parts
        assertNotNull(parts)
        assertTrue(parts!!.size >= 2)
        // The middle of each line rectangle hits; the corner of the union that no line rectangle covers does not.
        for (r in parts) assertTrue(link.contains((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f))
        val last = parts.last()
        val x = last[2] + 1f
        val y = (last[1] + last[3]) / 2f
        assertTrue(x >= link.left && x < link.right && y >= link.top && y < link.bottom)
        assertFalse(link.contains(x, y))
        // The paragraph keeps its whole box; a one-line link has no parts.
        assertNull(n.first { it.role == A11yRole.PARAGRAPH }.parts)
        val one = nodes(layout { paraWithRun("앞 링크글자 뒤", 2, 6, RunStyle(link = "u")) }).first { it.role == A11yRole.LINK }
        assertNull(one.parts)
    }

    // -- several entries ----------------------------------------------------------------------------------------

    @Test fun everyEntryOfAScreenGivesItsNodesInOrderWithItsSection() {
        // The end of section 3 above, the start of section 4 below: a seam of a scroll screen.
        val a = layout { para("앞 구역 끝 문단"); paraWithRun("링크 문단", 0, 2, RunStyle(link = "x")) }
        val b = layout { para("다음 구역 첫 문단"); para("그다음") }
        val ea = A11yEntry(3, a, a.pages[0], 0f, 0f)
        val eb = A11yEntry(4, b, b.pages[0], 0f, 500f)
        val n = A11yFragments.buildAll(listOf(ea, eb), 400, 1000)
        assertEquals(listOf("앞 구역 끝 문단", "링크 문단", "링크", "다음 구역 첫 문단", "그다음"), n.map { it.text })
        assertEquals(listOf(3, 3, 3, 4, 4), n.map { it.section })
        assertTrue(n.filter { it.section == 4 }.all { it.top >= 500 })
        // Both sections start at char 0: only the section tells their ids apart.
        val ids = A11yIds()
        val got = n.map { ids.idFor(A11yKey(it.section, it.start, it.role)) }
        assertEquals(n.size, got.toSet().size)
        assertEquals(1, A11yFragments.buildAll(listOf(ea), 400, 1000).count { it.role == A11yRole.LINK })
        assertTrue(A11yFragments.buildAll(emptyList(), 400, 1000).isEmpty())
    }

    @Test fun hitTestAcrossEntriesFindsTheSectionUnderTheFinger() {
        val a = layout { para("위 구역") }
        val b = layout { para("아래 구역") }
        val n = A11yFragments.buildAll(listOf(A11yEntry(0, a, a.pages[0], 0f, 0f), A11yEntry(1, b, b.pages[0], 0f, 400f)), 400, 1000)
        assertEquals(0, n[A11yFragments.indexAt(n, n[0].left + 1f, n[0].top + 1f)].section)
        assertEquals(1, n[A11yFragments.indexAt(n, n[1].left + 1f, n[1].top + 1f)].section)
    }

    @Test fun sourcesAreTheSameOnlyForTheSamePagesOfTheSameLayouts() {
        val a = layout { para("가") }
        val b = layout { para("가") }
        val owner = Any()
        fun src(l: SectionLayout, left: Float = 0f, vararg more: A11yEntry) =
            A11ySource(owner, 1, listOf(A11yEntry(0, l, l.pages[0], left, 0f)) + more)
        assertTrue(src(a).sameAs(src(a)))
        assertFalse(src(a).sameAs(src(b)))
        assertFalse(src(a).sameAs(src(a, 1f)))
        assertFalse(src(a).sameAs(src(a, 0f, A11yEntry(1, b, b.pages[0], 0f, 300f))))
    }

    @Test fun shownTracksLayoutGenerationAndMode() {
        val l1 = layout { para("가") }
        val l2 = layout { para("가") }
        val s = A11yShown()
        assertTrue(s.changed(l1, 1, false))
        // A redraw of the same layout (a relayout that found the same one) announces nothing.
        assertFalse(s.changed(l1, 1, false))
        assertTrue(s.changed(l2, 1, false))
        assertTrue(s.changed(l2, 2, false))
        // The mode switch builds other nodes from the same layout.
        assertTrue(s.changed(l2, 2, true))
        assertFalse(s.changed(l2, 2, true))
        s.clear()
        assertTrue(s.changed(l2, 2, true))
    }

    // -- ids ------------------------------------------------------------------------------------------------

    @Test fun sameKeyGetsTheSameIdAndKeysNeverCollide() {
        val ids = A11yIds()
        val keys = ArrayList<A11yKey>()
        for (sec in 0..3) for (st in 0..5) for (role in A11yRole.values()) keys += A11yKey(sec, st * 7, role)
        val first = keys.map { ids.idFor(it) }
        assertEquals(keys.size, first.toSet().size)
        assertEquals(first, keys.map { ids.idFor(it) })
        assertTrue(first.all { it >= A11yIds.FIRST })
    }

    @Test fun trimKeepsTheCurrentPageAndASmallLru() {
        val ids = A11yIds(spare = 3)
        val old = (0 until 10).map { ids.idFor(A11yKey(0, it, A11yRole.PARAGRAPH)) }
        val cur = (0 until 4).map { ids.idFor(A11yKey(1, it, A11yRole.PARAGRAPH)) }
        ids.trim(4)
        assertEquals(7, ids.size)
        // The page just asked for keeps its ids; the 3 most recent older ones too, the rest are forgotten.
        assertEquals(cur, (0 until 4).map { ids.idFor(A11yKey(1, it, A11yRole.PARAGRAPH)) })
        assertEquals(old.takeLast(3), (7..9).map { ids.idFor(A11yKey(0, it, A11yRole.PARAGRAPH)) })
        // A forgotten key gets a new id, never a reused one.
        val again = ids.idFor(A11yKey(0, 0, A11yRole.PARAGRAPH))
        assertFalse(again in old || again in cur)
    }

    @Test fun clearForgetsKeysButNeverReusesAnId() {
        val ids = A11yIds()
        val a = ids.idFor(A11yKey(0, 0, A11yRole.PARAGRAPH))
        ids.clear()
        assertEquals(0, ids.size)
        val b = ids.idFor(A11yKey(0, 0, A11yRole.PARAGRAPH))
        val c = ids.idFor(A11yKey(0, 5, A11yRole.LINK))
        assertTrue(b > a && c > b)
    }

    @Test fun idsOfOnePageAreStableAcrossRebuilds() {
        val l = layout { para("하나"); paraWithRun("둘 링크", 2, 4, RunStyle(link = "k")); para("셋") }
        val ids = A11yIds()
        fun pass() = nodes(l).map { ids.idFor(A11yKey(7, it.start, it.role)) }
        val a = pass()
        ids.trim(a.size)
        assertEquals(a, pass())
        assertEquals(a.size, a.toSet().size)
    }
}
