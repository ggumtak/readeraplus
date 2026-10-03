package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.*
import org.junit.Assert.*
import org.junit.Test

class ScrollNavigationTest {
    private fun section(lines: Int = 20): SectionLayout {
        val text = "가".repeat(lines * 10)
        val pages = (0 until lines).chunked(4).map { indexes ->
            PageInfo(indexes.first() * 10, (indexes.last() + 1) * 10, indexes.mapIndexed { i, at ->
                LineInfo(at * 10, (at + 1) * 10, 0f, i * 30f, i * 30f + 20f, (i + 1) * 30f, 0f, 0)
            })
        }
        return SectionLayout(SectionContent(text, listOf(ParagraphBlock(0, text.length))), LayoutConfig(200, 100),
            if (pages.isEmpty()) listOf(PageInfo(0, 0, emptyList())) else pages, FloatArray(text.length))
    }
    private class Source(val values: MutableList<SectionLayout?>) : StripSource {
        override val sectionCount get() = values.size
        override fun layoutOf(section: Int) = values.getOrNull(section)
        override fun unitGap(section: Int) = 0f
    }
    private class Events : ScrollNavigation.Events {
        var frames = 0; var later = false; var blocked = -1
        val settled = ArrayList<SettleKind>()
        var onChange: () -> Unit = {}
        override fun changed() { frames++; onChange() }
        override fun settled(kind: SettleKind, distance: Float) { settled.add(kind) }
        override fun blocked(section: Int) { blocked = section }
        override fun later() { later = true }
        fun clear() { frames = 0; later = false; blocked = -1; settled.clear() }
    }
    private fun start(src: Source, e: Events, aligned: Boolean = true): ScrollNavigation {
        val nav = ScrollNavigation(src, e)
        nav.height = 100f; nav.aligned = aligned
        nav.place(0, src.values[0]!!, 0, Placement.TOP, SettleKind.OPEN)
        e.clear()
        return nav
    }
    private fun drain(nav: ScrollNavigation, e: Events) {
        var turns = 0
        while (e.later) { assertTrue("event loop made no progress", turns++ < 1000); e.later = false; nav.continueWork() }
    }
    @Test fun bothModesReplaceOneScreenSynchronouslyWithoutAContinuation() {
        for (aligned in listOf(false, true)) {
            val e = Events(); val nav = start(Source(mutableListOf(section())), e, aligned)
            assertEquals(Step.MOVED, nav.step(true))
            assertEquals(30, nav.anchor.toInt())
            assertEquals(1, e.frames); assertEquals(listOf(SettleKind.STEP), e.settled)
            assertFalse(e.later); assertFalse(nav.moving)
        }
    }
    @Test fun stepsWhileASectionLoadsAreLeftToTheHostAndNeverReplayedFramePerFrame() {
        val src = Source(mutableListOf(section(4), null))
        val e = Events(); val nav = start(src, e)
        nav.step(true)
        val anchor = nav.anchor; val frames = e.frames; val settles = e.settled.size
        assertEquals(Step.NEED_SECTION, nav.step(true))
        assertEquals(1, e.blocked); assertEquals(anchor, nav.anchor); assertEquals(frames, e.frames)
        assertTrue(nav.pending)
        // S §1.10: further steps are the host's to queue (backlog, one flush): the viewport takes none of them.
        for (next in listOf(true, false, true, true, false, true)) assertEquals(Step.NEED_SECTION, nav.step(next))
        assertEquals(frames, e.frames)
        src.values[1] = section(80)
        nav.continueWork(); drain(nav, e)
        assertFalse(nav.moving)
        // Only the step that waited arrives: one frame, one settle.
        assertEquals(frames + 1, e.frames)
        assertEquals(settles + 1, e.settled.size)
        val referenceEvents = Events()
        val referenceSource = Source(mutableListOf(section(4), null))
        val reference = start(referenceSource, referenceEvents)
        reference.step(true)
        referenceSource.values[1] = section(80)
        reference.step(true)
        assertEquals(reference.anchor, nav.anchor)
        assertEquals(reference.pos.dy, nav.pos.dy, 0f)
    }
    @Test fun openAtANoteWithContextPlacementAnchorsTheFirstHalfVisibleLine() {
        val l = section(); val src = Source(mutableListOf(l)); val e = Events(); val nav = start(src, e)
        // Offset 75 is mid-line (lines are 10 chars): CONTEXT puts that line about 25 % down.
        nav.place(0, l, 75, Placement.CONTEXT, SettleKind.OPEN)
        val first = ScrollMath.anchor(src, nav.pos, nav.height)
        assertEquals(first, nav.anchor)
        assertTrue(nav.anchor.toInt() < 70)
        // The anchor line and the top page describe the same page (footer label, chrome, progress agree).
        assertEquals(nav.topPage.toInt(), l.pageForOffset(nav.anchor.toInt()))
        assertEquals(listOf(SettleKind.OPEN), e.settled)
        // TOP placement still keeps its exact offset (restore, relayout, switch).
        nav.place(0, l, 75, Placement.TOP, SettleKind.RELAYOUT)
        assertEquals(75L, nav.anchor)
    }
    @Test fun boundedEmptyWalkNeverPublishesAnEmptyFrameOrSavesItsAnchor() {
        val src = Source((listOf(section(4)) + List(200) { section(0) } + section(8)).toMutableList())
        val e = Events(); val nav = start(src, e)
        e.onChange = {
            assertTrue(src.values[nav.pos.section]!!.pages[nav.pos.page].lines.isNotEmpty())
            var body = false
            ScrollMath.forEachVisible(src, nav.pos, nav.height) { _, l, p, _, _ ->
                if (l.pages[p].lines.isNotEmpty()) body = true
            }
            assertTrue("only a real body may replace the previous frame", body)
        }
        var arrived = false
        repeat(20) {
            nav.step(true); drain(nav, e)
            assertTrue(src.values[(nav.anchor ushr 32).toInt()]!!.pages.any { it.lines.isNotEmpty() })
            if ((nav.anchor ushr 32).toInt() == 201) arrived = true
        }
        assertTrue(arrived)
        assertFalse(nav.moving)
    }
    @Test fun longEmptyTailDoesNotStrandPendingNavigation() {
        val src = Source((listOf(section(4)) + List(200) { section(0) }).toMutableList())
        val e = Events(); val nav = start(src, e)
        repeat(12) { nav.step(true); drain(nav, e) }
        assertFalse(nav.moving)
        assertEquals(0, nav.pos.section)
        assertTrue(ScrollMath.atBookEnd(src, nav.pos, nav.height))
    }
    @Test fun cancelDuringLoadingDiscardsCommandsAndKeepsTheSavedPosition() {
        val src = Source(mutableListOf(section(4), null)); val e = Events(); val nav = start(src, e)
        nav.step(true); nav.step(true); nav.step(true)
        val anchor = nav.anchor
        assertTrue(nav.cancel()); assertFalse(nav.moving)
        src.values[1] = section()
        nav.continueWork(); drain(nav, e)
        assertEquals(anchor, nav.anchor)
    }
    @Test fun smoothUpSettlesOnceAndNeverSchedulesMomentum() {
        val e = Events(); val nav = start(Source(mutableListOf(section())), e, false)
        nav.drag(20f); nav.drag(25f)
        assertTrue(nav.moving); assertTrue(e.settled.isEmpty())
        nav.release(45f, 12000f, 50f)
        assertFalse(nav.moving); assertFalse(e.later)
        assertEquals(45f, nav.pos.dy, 0f)
        assertEquals(listOf(SettleKind.DRAG), e.settled)
        val saved = nav.anchor
        repeat(20) { nav.continueWork() }
        assertEquals(saved, nav.anchor); assertEquals(2, e.frames)
    }
    @Test fun cancelledLiveDragSettlesBeforeAnchorIsRead() {
        val e = Events(); val nav = start(Source(mutableListOf(section())), e, false)
        nav.drag(80f)
        assertEquals(0L, nav.anchor)
        assertTrue(nav.cancel())
        assertEquals(30L, nav.anchor)
        assertEquals(listOf(SettleKind.DRAG), e.settled)
        assertFalse(nav.cancel())
    }
    @Test fun loadingAfterADragDoesNotResumeThatDrag() {
        val src = Source(mutableListOf(section(4), null)); val e = Events(); val nav = start(src, e, false)
        nav.drag(900f); nav.release(900f, 10000f, 50f)
        val anchor = nav.anchor; val dy = nav.pos.dy
        assertEquals(1, e.blocked)
        src.values[1] = section()
        nav.continueWork()
        assertEquals(anchor, nav.anchor); assertEquals(dy, nav.pos.dy, 0f)
        assertFalse(nav.moving)
    }
    @Test fun stepReleaseSnapsImmediatelyAndFlingIsExactlyOneScreen() {
        val e = Events(); val nav = start(Source(mutableListOf(section())), e)
        nav.release(41f, 0f, 50f)
        assertEquals(30f, nav.pos.dy, 0f); assertFalse(nav.moving); assertFalse(e.later)
        e.clear()
        nav.release(10f, 3000f, 50f)
        assertEquals(0f, nav.pos.dy, 0f); assertEquals(1, nav.pos.page)
        assertEquals(listOf(SettleKind.STEP), e.settled)
        assertEquals(1, e.frames); assertFalse(e.later)
    }
    @Test fun restoredPlacementKeepsTheExactOffsetUntilTheFirstUserMovement() {
        val l = section(); val src = Source(mutableListOf(l)); val e = Events(); val nav = start(src, e)
        for (kind in listOf(SettleKind.OPEN, SettleKind.SWITCH, SettleKind.RELAYOUT)) {
            nav.place(0, l, 45, Placement.TOP, kind)
            assertEquals(45L, nav.anchor)
        }
        nav.step(true)
        assertEquals(70L, nav.anchor)
    }
}
