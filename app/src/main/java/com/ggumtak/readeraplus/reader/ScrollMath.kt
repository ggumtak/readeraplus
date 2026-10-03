package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.SectionLayout

internal interface StripSource {
    val sectionCount: Int
    /** Laid-out layout of [section] or null (never starts a layout). */
    fun layoutOf(section: Int): SectionLayout?
    /** Extra px above page 0 of [section] (> 0 only at a chapter / spine-item start; never called for section 0). */
    fun unitGap(section: Int): Float
}

internal class ScrollPos {
    @JvmField var section = 0
    @JvmField var page = 0
    @JvmField var dy = 0f
    /** Section a move stopped at because it is not laid out (-1 = none). */
    @JvmField var blockedAt = -1
    /** Cursor hints let capped empty-strip walks finish at the last real body without rescanning the tail. */
    internal var lastSolidSection = -1
    internal var lastSolidPage = -1
    internal var endSection = -1
    internal var endPage = -1
    fun set(o: ScrollPos) {
        section=o.section; page=o.page; dy=o.dy; blockedAt=o.blockedAt
        lastSolidSection=o.lastSolidSection; lastSolidPage=o.lastSolidPage
        endSection=o.endSection; endPage=o.endPage
    }
}

internal enum class Step { MOVED, EDGE, NEED_SECTION }
/** [Δ] Moved here from ScrollReader so the pure settle rule is JVM-testable. */
internal enum class SettleKind { STEP, DRAG, FLING, JUMP, RELAYOUT, OPEN, SWITCH }
internal enum class Placement { TOP, CONTEXT }

internal object ScrollMath {
    const val MAX_STRIPS = 64
    const val CONTEXT_FRACTION = 0.25f
    const val CHAPTER_GAP_EM = 2f
    private const val EPS = 0.5f

    fun gapAbove(src: StripSource, section: Int, l: SectionLayout, page: Int): Float {
        val p = l.pages.getOrNull(page) ?: return 0f
        if (p.lines.isEmpty() || section == 0 && page == 0) return 0f
        return p.lead + if (page == 0 && section > 0) maxOf(0f, src.unitGap(section)) else 0f
    }
    fun body(l: SectionLayout, page: Int): Float = l.pages.getOrNull(page)?.lines?.lastOrNull()?.bottom ?: 0f
    fun height(src: StripSource, section: Int, l: SectionLayout, page: Int): Float =
        if (l.pages.getOrNull(page)?.lines?.isEmpty() != false) 0f else gapAbove(src, section, l, page) + body(l, page)

    /** Raw rebasing is bounded, and even a zero-distance move advances across empty strips. */
    private fun moveRaw(src: StripSource, pos: ScrollPos, delta: Float, stayIn: Int = -1): Float {
        var left = delta
        var moved = 0f
        var work = 0
        while (work < MAX_STRIPS && pos.section in 0 until src.sectionCount) {
            val l = src.layoutOf(pos.section)
            if (l == null) { pos.blockedAt = pos.section; break }
            pos.page = pos.page.coerceIn(0, l.pageCount - 1)
            val h = height(src, pos.section, l, pos.page)
            pos.dy = pos.dy.coerceIn(0f, h)
            if (left >= 0f) {
                if (h > 0f) { pos.lastSolidSection = pos.section; pos.lastSolidPage = pos.page }
                val room = h - pos.dy
                if (room > 0f && left < room) { pos.dy += left; moved += left; break }
                if (left == 0f && h > 0f) break
                val ns = if (pos.page + 1 < l.pageCount) pos.section else pos.section + 1
                val np = if (ns == pos.section) pos.page + 1 else 0
                if (ns >= src.sectionCount || pos.section == pos.endSection && pos.page == pos.endPage) {
                    pos.dy = h; moved += minOf(left, room)
                    if (h == 0f && pos.lastSolidSection >= 0) {
                        pos.section = pos.lastSolidSection; pos.page = pos.lastSolidPage
                        val last = src.layoutOf(pos.section)!!
                        pos.dy = height(src, pos.section, last, pos.page)
                    }
                    pos.endSection = pos.section; pos.endPage = pos.page
                    break
                }
                if (src.layoutOf(ns) == null) {
                    pos.blockedAt = ns; pos.dy = h; moved += minOf(left, room); break
                }
                left -= room; moved += room
                pos.section = ns; pos.page = np; pos.dy = 0f
            } else {
                if (-left <= pos.dy) { pos.dy += left; moved += left; break }
                val room = pos.dy
                val ns = if (pos.page > 0) pos.section else pos.section - 1
                if (ns < 0 || stayIn >= 0 && ns != stayIn) { pos.dy = 0f; moved -= room; break }
                val prev = src.layoutOf(ns)
                if (prev == null) { pos.blockedAt = ns; pos.dy = 0f; moved -= room; break }
                left += room; moved -= room
                pos.page = if (ns == pos.section) pos.page - 1 else prev.pageCount - 1
                pos.section = ns; pos.dy = height(src, ns, prev, pos.page)
            }
            work++
        }
        return moved
    }

    /** Finite only when the known end lies in this viewport; unknown content is a temporary boundary. */
    private fun endDistance(src: StripSource, pos: ScrollPos, viewH: Float, markBlocked: Boolean): Float {
        var s = pos.section; var p = pos.page; var y = -pos.dy
        for (work in 0 until MAX_STRIPS) {
            val l = src.layoutOf(s) ?: return Float.NaN
            if (p !in l.pages.indices) return Float.NaN
            y += height(src, s, l, p)
            if (y > viewH + EPS) return Float.NaN
            if (s == pos.endSection && p == pos.endPage) return y
            if (++p >= l.pageCount) { p = 0; s++ }
            if (s >= src.sectionCount) return y
            if (src.layoutOf(s) == null) {
                if (markBlocked) pos.blockedAt = s
                return if (markBlocked) y else Float.NaN
            }
        }
        return Float.NaN
    }

    private fun clampBottom(src: StripSource, pos: ScrollPos, viewH: Float): Float {
        val end = endDistance(src, pos, viewH, true)
        if (end == 0f && src.layoutOf(pos.section)?.let { body(it, pos.page) } == 0f) {
            if (pos.lastSolidSection < 0) { pos.endSection = pos.section; pos.endPage = pos.page; return 0f }
            pos.section = pos.lastSolidSection; pos.page = pos.lastSolidPage
            pos.dy = height(src, pos.section, src.layoutOf(pos.section)!!, pos.page)
            pos.endSection = pos.section; pos.endPage = pos.page
        }
        return if (end.isFinite() && end < viewH - EPS) moveRaw(src, pos, end - viewH) else 0f
    }

    fun scrollBy(src: StripSource, pos: ScrollPos, delta: Float, viewH: Float): Float {
        if (!delta.isFinite() || !(viewH > 0f) || !viewH.isFinite()) return 0f
        pos.blockedAt = -1
        val moved = moveRaw(src, pos, delta)
        return moved + clampBottom(src, pos, viewH)
    }

    fun place(src: StripSource, section: Int, l: SectionLayout, offset: Int, placement: Placement,
              viewH: Float, lineAligned: Boolean, pos: ScrollPos) {
        pos.section = section; pos.page = l.pageForOffset(offset.coerceIn(0, l.content.length)); pos.dy = 0f; pos.blockedAt = -1
        pos.lastSolidSection = -1; pos.lastSolidPage = -1; pos.endSection = -1; pos.endPage = -1
        val lines = l.pages[pos.page].lines
        var at = lines.lastIndex
        for (i in lines.indices) if (lines[i].end > offset || lines[i].start >= offset) { at = i; break }
        if (at >= 0) {
            val ln = lines[at]
            pos.dy = gapAbove(src, section, l, pos.page) + ln.top
            if (placement == Placement.CONTEXT && offset != ln.start) {
                moveRaw(src, pos, -viewH * CONTEXT_FRACTION, stayIn = section)
                snapLine(src, pos, ceil = false, stayIn = section)
            }
        } else moveRaw(src, pos, 0f)
        val clamped = clampBottom(src, pos, viewH)
        if (lineAligned && clamped < 0f) snapLine(src, pos, ceil = true)
    }

    private fun different(a: ScrollPos, s: Int, p: Int, dy: Float): Boolean = a.section != s || a.page != p || a.dy != dy

    fun stepDown(src: StripSource, pos: ScrollPos, viewH: Float, out: ScrollPos): Step {
        val os = pos.section; val op = pos.page; val od = pos.dy
        out.set(pos); out.blockedAt = -1
        if (!(viewH > 0f) || atBookEnd(src, pos, viewH)) return Step.EDGE
        var s = os; var p = op; var y = -od
        for (work in 0 until MAX_STRIPS) {
            val l = src.layoutOf(s) ?: run { out.blockedAt = s; return Step.NEED_SECTION }
            val gap = gapAbove(src, s, l, p)
            val lines = l.pages[p].lines
            for (i in lines.indices) {
                val ln = lines[i]
                val top = y + gap + ln.top; val bottom = y + gap + ln.bottom
                if (bottom <= viewH + EPS) continue
                // A line taller than one screen was already shown from its top: continue with its next line.
                if (top <= EPS && ln.bottom - ln.top > viewH) continue
                out.section = s; out.page = p; out.dy = gap + ln.top
                if (clampBottom(src, out, viewH) < 0f) snapLine(src, out, ceil = true)
                if (different(out, os, op, od)) return Step.MOVED
                return if (out.blockedAt >= 0) Step.NEED_SECTION else Step.EDGE
            }
            y += height(src, s, l, p)
            if (++p >= l.pageCount) { p = 0; s++ }
            if (s >= src.sectionCount) break
        }
        // A work cap is not a book edge: another command continues beyond this batch of empty strips.
        scrollBy(src, out, viewH, viewH)
        if (different(out, os, op, od)) return Step.MOVED
        return if (out.blockedAt >= 0) Step.NEED_SECTION else Step.EDGE
    }

    fun stepUp(src: StripSource, pos: ScrollPos, viewH: Float, out: ScrollPos): Step {
        val os = pos.section; val op = pos.page; val od = pos.dy
        out.set(pos); out.blockedAt = -1
        if (!(viewH > 0f) || os == 0 && op == 0 && od <= 0f) return Step.EDGE
        var fs = -1; var fp = -1; var fi = -1
        forEachVisible(src, pos, viewH) { s, l, p, y, gap ->
            if (fs < 0) for (i in l.pages[p].lines.indices) {
                val ln = l.pages[p].lines[i]
                if (y + gap + ln.top >= -EPS && y + gap + ln.bottom <= viewH + EPS) {
                    fs = s; fp = p; fi = i; break
                }
            }
        }
        if (fs < 0) { fs = os; fp = op; fi = 0 }
        var s = fs; var p = fp; var index = fi - 1
        var found = false
        for (work in 0 until MAX_STRIPS) {
            val l = src.layoutOf(s) ?: run { out.blockedAt = s; return Step.NEED_SECTION }
            if (index >= 0 && index < l.pages[p].lines.size) {
                val ln = l.pages[p].lines[index]
                out.section = s; out.page = p; out.dy = gapAbove(src, s, l, p) + ln.bottom
                moveRaw(src, out, -viewH)
                snapLine(src, out, ceil = true)
                found = true; break
            }
            if (p > 0) p-- else {
                if (s == 0) break
                s--; val prev = src.layoutOf(s) ?: run { out.blockedAt = s; return Step.NEED_SECTION }
                p = prev.pageCount - 1
            }
            index = src.layoutOf(s)!!.pages[p].lines.lastIndex
        }
        if (!found) scrollBy(src, out, -viewH, viewH)
        if (different(out, os, op, od)) return Step.MOVED
        return if (out.blockedAt >= 0) Step.NEED_SECTION else Step.EDGE
    }

    /** Nearest line top, or first line top after the position when ceil=true. No scratch objects. */
    private fun snapLine(src: StripSource, pos: ScrollPos, ceil: Boolean, stayIn: Int = -1) {
        val os = pos.section; val op = pos.page; val od = pos.dy
        var bs = -1; var bp = 0; var bd = 0f; var before = Float.NEGATIVE_INFINITY
        var asct = -1; var ap = 0; var ad = 0f; var after = Float.POSITIVE_INFINITY
        var s = os; var p = op; var y = -od
        for (work in 0 until MAX_STRIPS) {
            val l = src.layoutOf(s) ?: break
            val gap = gapAbove(src, s, l, p)
            val lines = l.pages[p].lines
            for (i in lines.lastIndex downTo 0) {
                val d = gap + lines[i].top; val t = y + d
                if (t <= 0f) { bs = s; bp = p; bd = d; before = t; break }
            }
            if (bs >= 0 || p == 0 && (s == 0 || s == stayIn)) break
            if (p > 0) p-- else { s--; val prev = src.layoutOf(s) ?: break; p = prev.pageCount - 1 }
            val prev = src.layoutOf(s) ?: break
            y -= height(src, s, prev, p)
        }
        s = os; p = op; y = -od
        for (work in 0 until MAX_STRIPS) {
            val l = src.layoutOf(s) ?: break
            val gap = gapAbove(src, s, l, p)
            val lines = l.pages[p].lines
            for (i in lines.indices) {
                val ln = lines[i]
                val d = gap + ln.top; val t = y + d
                if (t >= 0f) { asct = s; ap = p; ad = d; after = t; break }
            }
            if (asct >= 0) break
            y += height(src, s, l, p)
            if (++p >= l.pageCount) { p = 0; s++ }
            if (s >= src.sectionCount || stayIn >= 0 && s != stayIn) break
        }
        if (asct >= 0 && (bs < 0 || ceil || after < -before)) { pos.section = asct; pos.page = ap; pos.dy = ad }
        else if (bs >= 0) { pos.section = bs; pos.page = bp; pos.dy = bd }
    }

    fun snapToLine(src: StripSource, pos: ScrollPos, viewH: Float) {
        moveRaw(src, pos, 0f)
        snapLine(src, pos, ceil = false)
        if (clampBottom(src, pos, viewH) < 0f) snapLine(src, pos, ceil = true)
    }
    fun releaseStep(totalDy: Float, vy: Float, flingMin: Float, viewH: Float): Int =
        if (viewH > 0f && Math.abs(totalDy) < viewH / 3f && Math.abs(vy) >= 2f * maxOf(1f, flingMin))
            if (vy > 0f) 1 else -1 else 0

    fun anchor(src: StripSource, pos: ScrollPos, viewH: Float): Long {
        var fallback = -1L
        forEachVisible(src, pos, viewH) { s, l, p, y, gap ->
            val lines = l.pages[p].lines
            for (i in lines.indices) {
                val ln = lines[i]
                val top = y + gap + ln.top; val bottom = y + gap + ln.bottom
                if (bottom <= 0f || top >= viewH) continue
                if (fallback < 0) fallback = (s.toLong() shl 32) or ln.start.toLong()
                if (minOf(bottom, viewH) - maxOf(top, 0f) >= (ln.bottom - ln.top) / 2f)
                    return (s.toLong() shl 32) or ln.start.toLong()
            }
        }
        return if (fallback >= 0) fallback else (pos.section.toLong() shl 32)
    }
    fun topPage(src: StripSource, pos: ScrollPos, viewH: Float): Long {
        var fallback = -1L
        forEachVisible(src, pos, viewH) { s, l, p, y, gap ->
            val lines = l.pages[p].lines
            for (i in lines.indices) {
                val ln = lines[i]
                val top = y + gap + ln.top; val bottom = y + gap + ln.bottom
                if (bottom <= 0f || top >= viewH) continue
                if (fallback < 0) fallback = (s.toLong() shl 32) or p.toLong()
                if (minOf(bottom, viewH) - maxOf(top, 0f) >= (ln.bottom - ln.top) / 2f)
                    return (s.toLong() shl 32) or p.toLong()
            }
        }
        return if (fallback >= 0) fallback else (pos.section.toLong() shl 32) or pos.page.toLong()
    }
    fun atBookEnd(src: StripSource, pos: ScrollPos, viewH: Float): Boolean =
        endDistance(src, pos, viewH, false).let { it.isFinite() && it <= viewH + EPS }
    fun lastFullyVisibleBottom(src: StripSource, pos: ScrollPos, viewH: Float): Float {
        var last = 0f
        forEachVisible(src, pos, viewH) { _, l, p, y, gap ->
            val lines = l.pages[p].lines
            for (i in lines.indices) {
                val ln = lines[i]
                val top = y + gap + ln.top; val b = y + gap + ln.bottom
                if (top >= -EPS && b <= viewH + EPS) last = maxOf(last, minOf(viewH, b))
                else if (top <= EPS && b > viewH && ln.bottom - ln.top > viewH) last = viewH
            }
        }
        return last
    }
    /** Bounded distance for movement accounting; page commands themselves never animate. */
    fun distance(src: StripSource, from: ScrollPos, to: ScrollPos, limit: Float): Float {
        if (from.section > to.section || from.section == to.section && from.page > to.page)
            return -distance(src, to, from, limit)
        if (from.section == to.section && from.page == to.page) return to.dy - from.dy
        var s = from.section; var p = from.page; var y = -from.dy
        for (work in 0 until MAX_STRIPS) {
            val l = src.layoutOf(s) ?: return Float.NaN
            if (s == to.section && p == to.page) return y + to.dy
            y += height(src, s, l, p)
            if (y > limit) return Float.NaN
            if (++p >= l.pageCount) { p = 0; s++ }
            if (s >= src.sectionCount) return Float.NaN
        }
        return Float.NaN
    }
    inline fun forEachVisible(src: StripSource, pos: ScrollPos, viewH: Float,
                              visit: (Int, SectionLayout, Int, Float, Float) -> Unit) {
        var s = pos.section; var p = pos.page; var y = -pos.dy
        for (work in 0 until MAX_STRIPS) {
            if (s !in 0 until src.sectionCount) break
            val l = src.layoutOf(s) ?: break
            if (p !in l.pages.indices) break
            val gap = gapAbove(src, s, l, p)
            val h = height(src, s, l, p)
            if (h > 0f && y + h > 0f && y < viewH) visit(s, l, p, y, gap)
            y += h
            if (y >= viewH) break
            if (++p >= l.pageCount) { p = 0; s++ }
        }
    }
    fun anchorAfter(kind: SettleKind, placed: Long, computed: Long): Long = when (kind) {
        SettleKind.OPEN, SettleKind.RELAYOUT, SettleKind.SWITCH -> placed
        else -> computed
    }
    fun isVertical(dx: Float, dy: Float): Boolean = Math.abs(dy) > Math.abs(dx)
}

internal class ScreenCounter {
    private var remainder = 0.0
    fun reset() { remainder = 0.0 }
    fun add(px: Float, viewH: Float): Int {
        if (!px.isFinite() || !(viewH > 0f) || !viewH.isFinite()) return 0
        val total = remainder + Math.abs(px.toDouble())
        val n = minOf(Int.MAX_VALUE.toDouble(), Math.floor(total / viewH)).toInt()
        remainder = total % viewH
        return n
    }
}
