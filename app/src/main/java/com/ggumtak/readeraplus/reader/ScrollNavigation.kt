package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.SectionLayout

/** Main-thread navigation; independent of Android so loading, cancellation and rapid taps are testable. */
internal class ScrollNavigation(private val source: StripSource, private val events: Events) {
    interface Events {
        fun changed()
        fun settled(kind: SettleKind, distance: Float)
        fun blocked(section: Int)
        /** Continue a bounded empty-strip walk on the next event-loop turn, without drawing an empty frame. */
        fun later()
    }
    val pos = ScrollPos()
    val cursor = ScrollPos()
    private val out = ScrollPos()
    private val from = ScrollPos()
    var height = 0f
    var aligned = true
    var anchor = 0L
        private set
    var topPage = 0L
        private set
    private var stepPending = false
    private var stepNext = true
    private var pixelsPending = false
    private var pixels = 0f
    private var snap = false
    private var dragging = false
    private var distance = 0f
    val pending get() = stepPending || pixelsPending
    val moving get() = pending || dragging

    private fun visible(): Boolean {
        val emptyCursor = source.layoutOf(cursor.section)?.pages?.getOrNull(cursor.page)?.lines?.isEmpty() == true
        ScrollMath.forEachVisible(source, cursor, height) { s, l, p, y, gap ->
            val lines = l.pages[p].lines
            for (i in lines.indices) if (y + gap + lines[i].bottom > 0f && y + gap + lines[i].top < height) {
                if (emptyCursor) {
                    cursor.section = s; cursor.page = p; cursor.dy = maxOf(0f, -y)
                    cursor.lastSolidSection = s; cursor.lastSolidPage = p
                }
                return true
            }
        }
        return false
    }
    private fun publish(): Boolean {
        if (!visible()) return false
        pos.set(cursor)
        topPage = ScrollMath.topPage(source, pos, height)
        events.changed()
        return true
    }
    private fun settle(kind: SettleKind, moved: Float, placed: Long = anchor) {
        anchor = ScrollMath.anchorAfter(kind, placed, ScrollMath.anchor(source, pos, height))
        topPage = ScrollMath.topPage(source, pos, height)
        events.settled(kind, moved)
    }
    fun place(section: Int, layout: SectionLayout, offset: Int, placement: Placement, kind: SettleKind) {
        cancel()
        ScrollMath.place(source, section, layout, offset, placement, height, aligned, cursor)
        pos.set(cursor)
        publish()
        // Only a TOP placement keeps its exact offset until the first movement (restore, relayout, switch). CONTEXT
        // (a jump or an open at a note, C16/C17) reads the first half-visible line, like any jump: the same line the
        // top page is counted from, and the line a later relayout keeps at the top.
        val placed = (section.toLong() shl 32) or offset.coerceIn(0, layout.content.length).toLong()
        settle(kind, 0f, if (placement == Placement.CONTEXT) ScrollMath.anchor(source, pos, height) else placed)
        if (cursor.blockedAt >= 0) events.blocked(cursor.blockedAt)
    }
    /** NEED_SECTION without moving while a step waits for its section: the host queues it (S §1.10 turn). */
    fun step(next: Boolean): Step {
        if (pending) return Step.NEED_SECTION
        if (dragging) cancel()
        stepPending = true; stepNext = next; cursor.set(pos); from.set(pos)
        return continueStep()
    }
    private fun continueStep(): Step {
        val s = cursor.section; val p = cursor.page; val dy = cursor.dy
        val result = if (stepNext) ScrollMath.stepDown(source, cursor, height, out)
            else ScrollMath.stepUp(source, cursor, height, out)
        cursor.set(out)
        if (result == Step.NEED_SECTION) {
            if (cursor.blockedAt >= 0) events.blocked(cursor.blockedAt)
            return result
        }
        if (result == Step.MOVED && !publish()) {
            if (s != cursor.section || p != cursor.page || dy != cursor.dy) events.later()
            else { stepPending = false; cursor.set(pos) }
            return Step.NEED_SECTION
        }
        stepPending = false
        if (result == Step.MOVED) {
            val moved = ScrollMath.distance(source, from, pos, height * 3f)
            settle(SettleKind.STEP, if (moved.isFinite()) moved else if (stepNext) height else -height)
        } else {
            // Discovering a long empty tail may prove EOF without changing the last real viewport.
            pos.endSection = cursor.endSection; pos.endPage = cursor.endPage
            cursor.set(pos)
        }
        if (cursor.blockedAt >= 0) events.blocked(cursor.blockedAt)
        return result
    }
    fun continueWork() {
        if (stepPending) continueStep() else if (pixelsPending) continuePixels()
    }
    fun drag(delta: Float) {
        if (!delta.isFinite() || stepPending) return
        if (!dragging) { cursor.set(pos); distance = 0f }
        dragging = true; pixelsPending = true; snap = false; pixels += delta
        continuePixels()
    }
    fun release(total: Float, velocity: Float, flingMin: Float) {
        if (aligned) {
            val screen = ScrollMath.releaseStep(total, velocity, flingMin, height)
            if (screen != 0) { step(screen > 0); return }
            if (!total.isFinite()) return
            cursor.set(pos); distance = 0f; pixels = total; pixelsPending = true; snap = true
            continuePixels()
        } else {
            dragging = false
            // No momentum or animation after UP. An unfinished empty walk can settle only at real content.
            if (pixelsPending) { pixels = 0f; continuePixels() }
            else { settle(SettleKind.DRAG, distance); distance = 0f }
        }
    }
    private fun continuePixels() {
        val s = cursor.section; val p = cursor.page; val dy = cursor.dy
        val moved = ScrollMath.scrollBy(source, cursor, pixels, height)
        pixels -= moved; distance += moved
        val empty = source.layoutOf(cursor.section)?.pages?.getOrNull(cursor.page)?.lines?.isEmpty() == true
        if (empty && cursor.blockedAt < 0 && (s != cursor.section || p != cursor.page || dy != cursor.dy)) {
            events.later(); return
        }
        if (snap) ScrollMath.snapToLine(source, cursor, height)
        val blocked = cursor.blockedAt
        publish()
        pixelsPending = false; pixels = 0f
        // A drag stops at unknown content; loading it never resumes the gesture automatically.
        cursor.set(pos)
        if (!dragging) { settle(SettleKind.DRAG, distance); distance = 0f }
        if (blocked >= 0) events.blocked(blocked)
    }
    fun cancel(): Boolean {
        val wasMoving = moving
        val wasDrag = dragging || pixelsPending
        stepPending = false; pixelsPending = false; dragging = false; pixels = 0f
        cursor.set(pos)
        if (wasDrag) settle(SettleKind.DRAG, distance)
        distance = 0f
        return wasMoving
    }
}
