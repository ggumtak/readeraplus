package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ScrollMathTest {
    private class Source(val layouts: List<SectionLayout?>, val gaps: FloatArray = FloatArray(layouts.size)) : StripSource {
        override val sectionCount get() = layouts.size
        override fun layoutOf(section: Int) = layouts.getOrNull(section)
        override fun unitGap(section: Int) = gaps[section]
    }
    private fun section(pages: Int, lines: Int = 4, lineH: Float = 30f, lead: Float = 10f): SectionLayout {
        val text = "가".repeat(pages * lines * 10)
        val content = SectionContent(text, listOf(ParagraphBlock(0, text.length)))
        val list = ArrayList<PageInfo>()
        var start = 0
        repeat(pages) { p ->
            val ls = ArrayList<LineInfo>()
            repeat(lines) { i ->
                ls.add(LineInfo(start, start + 10, 0f, i * lineH, i * lineH + 20f,
                    (i + 1) * lineH, 0f, LineInfo.EXPAND_NONE))
                start += 10
            }
            list.add(PageInfo(p * lines * 10, start, ls, lead))
        }
        return SectionLayout(content, LayoutConfig(200, 500), list, FloatArray(text.length))
    }
    private fun empty() = SectionLayout(SectionContent.EMPTY, LayoutConfig(200, 500),
        listOf(PageInfo(0, 0, emptyList(), 99f)), FloatArray(0))
    private fun copy(p: ScrollPos) = ScrollPos().also { it.set(p) }
    private data class Flat(val s: Int, val p: Int, val top: Float, val h: Float, val gap: Float)
    private fun flat(src: Source): List<Flat> {
        val out = ArrayList<Flat>()
        var top = 0f
        for ((s, l) in src.layouts.withIndex()) {
            if (l == null) break
            for ((p, page) in l.pages.withIndex()) {
                val gap = if (page.lines.isEmpty() || s == 0 && p == 0) 0f
                    else page.lead + if (p == 0) src.gaps[s] else 0f
                val h = if (page.lines.isEmpty()) 0f else gap + page.lines.last().bottom
                out.add(Flat(s, p, top, h, gap)); top += h
            }
        }
        return out
    }
    private fun y(src: Source, pos: ScrollPos) = flat(src).first { it.s == pos.section && it.p == pos.page }.top + pos.dy

    @Test fun pageLeadAndChapterGapApplyOnlyToNonemptyStrips() {
        val l = section(2)
        val src = Source(listOf(l, l, empty()), floatArrayOf(40f, 40f, 40f))
        assertEquals(0f, ScrollMath.gapAbove(src, 0, l, 0), 0f)
        assertEquals(10f, ScrollMath.gapAbove(src, 0, l, 1), 0f)
        assertEquals(50f, ScrollMath.gapAbove(src, 1, l, 0), 0f)
        assertEquals(120f, ScrollMath.body(l, 0), 0f)
        assertEquals(170f, ScrollMath.height(src, 1, l, 0), 0f)
        assertEquals(0f, ScrollMath.height(src, 2, src.layouts[2]!!, 0), 0f)
    }

    @Test fun smoothMovesMatchAnIndependentFlatBookAndNeverExposeBlankSpacePastTheEnd() {
        val r = Random(8051)
        repeat(100) {
            val src = Source(List(r.nextInt(1, 9)) { section(r.nextInt(1, 5), r.nextInt(1, 9),
                r.nextInt(18, 60).toFloat(), r.nextInt(0, 25).toFloat()) }, FloatArray(8) { 40f })
            val f = flat(src); val total = f.last().top + f.last().h
            for (viewH in floatArrayOf(100f, 300f, 517f)) {
                val pos = ScrollPos()
                var expected = 0f
                repeat(300) {
                    val delta = r.nextInt(-700, 701).toFloat()
                    val next = (expected + delta).coerceIn(0f, maxOf(0f, total - viewH))
                    val moved = ScrollMath.scrollBy(src, pos, delta, viewH)
                    assertEquals("distance", next - expected, moved, 0.01f)
                    assertEquals("position", next, y(src, pos), 0.01f)
                    assertTrue(pos.dy >= 0f)
                    assertTrue(pos.dy < ScrollMath.height(src, pos.section, src.layouts[pos.section]!!, pos.page))
                    assertEquals(next + viewH >= total - 0.5f, ScrollMath.atBookEnd(src, pos, viewH))
                    expected = next
                }
            }
        }
    }

    @Test fun unknownSectionIsATemporaryEndAndIsReportedForLoading() {
        val src = Source(listOf(section(2), null, section(2)))
        val pos = ScrollPos()
        assertEquals(150f, ScrollMath.scrollBy(src, pos, 1000f, 100f), 0f)
        assertEquals(1, pos.blockedAt)
        assertFalse(ScrollMath.atBookEnd(src, pos, 100f))
        val next = ScrollPos()
        assertEquals(Step.NEED_SECTION, ScrollMath.stepDown(src, pos, 100f, next))
        assertEquals(1, next.blockedAt)
        assertEquals(y(src, pos), y(src, next), 0f)
    }

    @Test fun repeatedStepsCrossTwoHundredEmptySectionsWithoutAFalseBookEdge() {
        val src = Source(listOf(section(1, 1)) + List(200) { empty() } + section(1, 1))
        val pos = ScrollPos(); val next = ScrollPos()
        var reached = false
        repeat(10) {
            val step = ScrollMath.stepDown(src, pos, 20f, next)
            if (step == Step.MOVED) pos.set(next)
            if (pos.section == 201) reached = true
        }
        assertTrue("the work cap must not strand the reader on empty content", reached)
        assertTrue(ScrollMath.atBookEnd(src, pos, 20f))
    }

    @Test fun screenStepsNeverSkipAWholeTextLineAcrossPagesAndSections() {
        val src = Source(List(5) { section(4) }, FloatArray(5) { 40f })
        val seen = HashSet<Long>()
        val pos = ScrollPos(); val next = ScrollPos()
        repeat(100) {
            ScrollMath.forEachVisible(src, pos, 100f) { s, l, p, top, gap ->
                for (ln in l.pages[p].lines) if (top + gap + ln.top >= -0.5f && top + gap + ln.bottom <= 100.5f)
                    seen.add((s.toLong() shl 32) or ln.start.toLong())
            }
            when (ScrollMath.stepDown(src, pos, 100f, next)) {
                Step.MOVED -> pos.set(next)
                Step.NEED_SECTION -> fail("all layouts are present")
                Step.EDGE -> return@repeat
            }
        }
        assertTrue(ScrollMath.atBookEnd(src, pos, 100f))
        for (s in src.layouts.indices) for (p in src.layouts[s]!!.pages) for (ln in p.lines)
            assertTrue("skipped section=$s offset=${ln.start}", seen.contains((s.toLong() shl 32) or ln.start.toLong()))
    }

    @Test fun aLongEmptyTailEndsAtTheLastRealViewportAndAnEmptyBookTerminates() {
        for (hasBody in listOf(false, true)) {
            val src = Source((if (hasBody) listOf(section(1)) else emptyList()) + List(200) { empty() })
            val pos = ScrollPos(); val next = ScrollPos()
            var ended = false
            repeat(30) {
                if (ScrollMath.stepDown(src, pos, 100f, next) == Step.MOVED) pos.set(next)
                if (ScrollMath.atBookEnd(src, pos, 100f)) ended = true
            }
            assertTrue("empty tail hasBody=$hasBody", ended)
            if (hasBody) {
                assertEquals(0, pos.section)
                assertTrue(ScrollMath.lastFullyVisibleBottom(src, pos, 100f) > 0f)
            }
        }
    }

    @Test fun anOversizedLineIsShownBeforeTheNextStepContinuesAtTheFollowingLine() {
        val src = Source(listOf(section(1, 3, 150f, 0f)))
        val pos = ScrollPos(); val next = ScrollPos()
        assertEquals(100f, ScrollMath.lastFullyVisibleBottom(src, pos, 100f), 0f)
        assertEquals(Step.MOVED, ScrollMath.stepDown(src, pos, 100f, next))
        assertEquals(150f, next.dy, 0f)
    }

    @Test fun previousScreensReturnToTheBookStartOnWholeLines() {
        val src = Source(List(3) { section(3) }, FloatArray(3) { 40f })
        val pos = ScrollPos()
        ScrollMath.place(src, 2, src.layouts[2]!!, 110, Placement.TOP, 100f, true, pos)
        val next = ScrollPos()
        repeat(30) {
            val before = y(src, pos)
            val step = ScrollMath.stepUp(src, pos, 100f, next)
            if (step == Step.MOVED) {
                assertTrue(y(src, next) < before)
                pos.set(next)
            }
        }
        assertEquals(0f, y(src, pos), 0f)
        assertEquals(Step.EDGE, ScrollMath.stepUp(src, pos, 100f, next))
    }

    @Test fun placementUsesContextOnlyForAMidLineTargetAndKeepsItInsideItsSection() {
        val src = Source(List(2) { section(4) }, floatArrayOf(0f, 40f))
        val l = src.layouts[1]!!
        val pos = ScrollPos()
        ScrollMath.place(src, 1, l, 40, Placement.CONTEXT, 100f, true, pos)
        assertEquals(1, pos.page); assertEquals(10f, pos.dy, 0f)
        ScrollMath.place(src, 1, l, 45, Placement.CONTEXT, 100f, true, pos)
        assertEquals(1, pos.section)
        assertEquals(30f, ScrollMath.anchor(src, pos, 100f).toInt().toFloat(), 0f)
        ScrollMath.place(src, 1, l, 1, Placement.CONTEXT, 500f, true, pos)
        assertEquals(1, pos.section)
    }

    @Test fun halfVisibleLineSelectsTheReadingAnchorAndRealPage() {
        val src = Source(listOf(section(2)))
        val pos = ScrollPos().apply { dy = 14f }
        assertEquals(0, ScrollMath.anchor(src, pos, 100f).toInt())
        pos.dy = 16f
        assertEquals(10, ScrollMath.anchor(src, pos, 100f).toInt())
        assertEquals(0, ScrollMath.topPage(src, pos, 100f).toInt())
        pos.page = 1; pos.dy = 10f
        assertEquals(40, ScrollMath.anchor(src, pos, 100f).toInt())
        assertEquals(1, ScrollMath.topPage(src, pos, 100f).toInt())
    }

    @Test fun stepReleaseKeepsTapsAndSlowDragsDistinctFromShortFlings() {
        assertEquals(1, ScrollMath.releaseStep(20f, 1000f, 50f, 300f))
        assertEquals(-1, ScrollMath.releaseStep(-20f, -1000f, 50f, 300f))
        assertEquals(0, ScrollMath.releaseStep(101f, 1000f, 50f, 300f))
        assertEquals(0, ScrollMath.releaseStep(20f, 99f, 50f, 300f))
        assertTrue(ScrollMath.isVertical(5f, -6f)); assertFalse(ScrollMath.isVertical(6f, 5f))
    }

    @Test fun placementsKeepTheStickyAnchorAndUserMotionsRecomputeIt() {
        for (kind in SettleKind.entries) assertEquals(
            if (kind in listOf(SettleKind.OPEN, SettleKind.RELAYOUT, SettleKind.SWITCH)) 17L else 9L,
            ScrollMath.anchorAfter(kind, 17L, 9L))
    }

    @Test fun screenCounterAccumulatesBothDirectionsAndResets() {
        val counter = ScreenCounter()
        assertEquals(0, counter.add(30f, 100f))
        assertEquals(0, counter.add(-30f, 100f))
        assertEquals(2, counter.add(210f, 100f))
        assertEquals(1, counter.add(30f, 100f))
        counter.reset()
        assertEquals(0, counter.add(99f, 100f))
        assertEquals(0, counter.add(Float.NaN, 100f))
        assertEquals(0, counter.add(10f, 0f))
    }

    @Test fun distanceAndVisibleStripsAgreeWithTheFlatReference() {
        val src = Source(List(3) { section(3) }, FloatArray(3) { 40f })
        val from = ScrollPos().apply { dy = 25f }
        val to = ScrollPos().apply { section = 2; page = 1; dy = 10f }
        assertEquals(y(src, to) - y(src, from), ScrollMath.distance(src, from, to, 10_000f), 0f)
        assertEquals(y(src, from) - y(src, to), ScrollMath.distance(src, to, from, 10_000f), 0f)
        val seen = ArrayList<Flat>()
        ScrollMath.forEachVisible(src, to, 100f) { s, l, p, top, gap ->
            seen.add(Flat(s, p, top, gap + l.pages[p].lines.last().bottom, gap))
        }
        val origin = y(src, to)
        assertEquals(flat(src).filter { it.top < origin + 100f && it.top + it.h > origin }
            .map { it.copy(top = it.top - origin) }, seen)
    }

    @Test fun engineLayoutsKeepEveryLineVisibleDuringForwardAndBackwardScreenSteps() {
        val rnd = Random(9951)
        repeat(60) { seed ->
            val src = Source(List(3) {
                val builder = SectionBuilder()
                repeat(20) { i ->
                    when (rnd.nextInt(8)) {
                        0 -> builder.para("")
                        1 -> builder.heading("새 장면 $i", pageBreak = rnd.nextBoolean())
                        2 -> builder.rule()
                        else -> builder.para(SampleText.koreanParagraph(rnd, rnd.nextInt(15, 350)))
                    }
                }
                val content = builder.build()
                Typesetter(FakeMeasurer(), LayoutConfig(300, 400, lineHeightEm = 1.5f,
                    paragraphSpacingEm = 0.5f, widowOrphanControl = seed % 3 != 0,
                    pageBreak = if (seed % 2 == 0) PageBreakMode.LINE else PageBreakMode.PARAGRAPH))
                    .layout(content, if (seed % 4 == 0) content.length / 3 else -1)
            }, FloatArray(3) { 40f })
            val pos = ScrollPos(); val next = ScrollPos()
            val seen = HashSet<Long>()
            repeat(200) {
                ScrollMath.forEachVisible(src, pos, 300f) { s, l, p, top, gap ->
                    for (ln in l.pages[p].lines) if (top + gap + ln.top >= -0.5f && top + gap + ln.bottom <= 300.5f)
                        seen.add((s.toLong() shl 32) or ln.start.toLong())
                }
                if (ScrollMath.stepDown(src, pos, 300f, next) == Step.MOVED) {
                    assertTrue("seed=$seed must progress", y(src, next) > y(src, pos))
                    pos.set(next)
                }
            }
            assertTrue(ScrollMath.atBookEnd(src, pos, 300f))
            for ((s, l) in src.layouts.withIndex()) for (page in l!!.pages) for (ln in page.lines)
                if (ln.end > ln.start) assertTrue("seed=$seed section=$s line=${ln.start}",
                    seen.contains((s.toLong() shl 32) or ln.start.toLong()))
            repeat(200) {
                val old = y(src, pos)
                var first = Float.POSITIVE_INFINITY
                ScrollMath.forEachVisible(src, pos, 300f) { _, l, p, top, gap ->
                    for (ln in l.pages[p].lines) if (top + gap + ln.top >= -0.5f && top + gap + ln.bottom <= 300.5f)
                        first = minOf(first, old + top + gap + ln.top)
                }
                if (ScrollMath.stepUp(src, pos, 300f, next) == Step.MOVED) {
                    val new = y(src, next)
                    assertTrue(new < old)
                    for (strip in flat(src)) for (ln in src.layouts[strip.s]!!.pages[strip.p].lines) {
                        val top = strip.top + strip.gap + ln.top
                        if (top >= new - 0.5f && top < first - 0.5f)
                            assertTrue("stepUp seed=$seed cut line", strip.top + strip.gap + ln.bottom - new <= 300.5f)
                    }
                    pos.set(next)
                }
            }
            assertEquals(0f, y(src, pos), 0.01f)
        }
    }

    private fun frameMath(src: Source, pos: ScrollPos): Float {
        var sum = ScrollMath.lastFullyVisibleBottom(src, pos, 100f)
        sum += ScrollMath.anchor(src, pos, 100f).toInt()
        sum += ScrollMath.topPage(src, pos, 100f).toInt()
        ScrollMath.forEachVisible(src, pos, 100f) { _, _, _, top, gap -> sum += top + gap }
        return sum
    }

    @Test fun contextPlacementAndNearestLineSnapUseTheExpectedWholeLine() {
        val src = Source(listOf(section(10)))
        val pos = ScrollPos()
        val l = src.layouts[0]!!
        ScrollMath.place(src, 0, l, 181, Placement.CONTEXT, 300f, true, pos)
        val target = flat(src).first { it.p == 4 }.let { it.top + it.gap + l.pages[4].lines[2].top }
        val relative = target - y(src, pos)
        assertTrue(relative >= 300f * 0.2f && relative <= 300f * 0.3f)
        pos.section = 0; pos.page = 0; pos.dy = 14f
        ScrollMath.snapToLine(src, pos, 100f)
        assertEquals(0f, pos.dy, 0f)
        pos.dy = 16f
        ScrollMath.snapToLine(src, pos, 100f)
        assertEquals(30f, pos.dy, 0f)
    }

    @Test fun steadyFrameMathHasNoPerFrameAllocations() {
        val bean: Any
        val get: java.lang.reflect.Method
        try {
            bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            val type = Class.forName("com.sun.management.ThreadMXBean")
            if (!type.isInstance(bean) || type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) return
            get = type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        } catch (_: Throwable) { return }
        val id = Thread.currentThread().id
        val src = Source(listOf(section(3)))
        val pos = ScrollPos().apply { dy = 14f }
        var sum = 0.0
        repeat(2_000) { sum += frameMath(src, pos); get.invoke(bean, id) }
        val before = get.invoke(bean, id) as Long
        repeat(20_000) { sum += frameMath(src, pos) }
        val bytes = (get.invoke(bean, id) as Long) - before
        assertTrue(sum > 0.0)
        assertTrue("frame math allocated $bytes bytes", bytes < 4096L)
    }
}
