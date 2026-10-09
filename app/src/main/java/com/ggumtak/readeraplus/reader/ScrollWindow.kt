package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.render.Highlight
import kotlin.math.round

internal class VirtualPage(val section: Int, val layout: SectionLayout, val page: PageInfo, val pageIndex: Int)

/** Reused frame storage. Drawing never queries the session's boxed-key LRU. */
internal class ScrollWindow {
    val sections = IntArray(ScrollMath.MAX_STRIPS)
    val pages = IntArray(ScrollMath.MAX_STRIPS)
    val layouts = arrayOfNulls<SectionLayout>(ScrollMath.MAX_STRIPS)
    val tops = FloatArray(ScrollMath.MAX_STRIPS)
    val gaps = FloatArray(ScrollMath.MAX_STRIPS)
    val quotes = Array<List<Highlight>>(ScrollMath.MAX_STRIPS) { emptyList() }
    var count = 0
        private set
    var first = -1
        private set
    var last = -1
        private set
    var wholeBottom = 0f
        private set
    var blockedAt = -1
        private set

    fun clear() {
        for (i in layouts.indices) { layouts[i] = null; quotes[i] = emptyList() }
        count = 0; first = -1; last = -1; wholeBottom = 0f; blockedAt = -1
    }
    fun fill(source: StripSource, pos: ScrollPos, height: Float, contentTop: Float = 0f) {
        clear()
        var s = pos.section; var p = pos.page; var top = -pos.dy
        for (work in 0 until ScrollMath.MAX_STRIPS) {
            if (s !in 0 until source.sectionCount || top >= height) break
            val l = source.layoutOf(s)
            if (l == null) { blockedAt = s; break }
            if (p !in l.pages.indices) break
            val gap = ScrollMath.gapAbove(source, s, l, p)
            val h = ScrollMath.height(source, s, l, p)
            if (h > 0f && top + h > 0f) {
                val i = count++
                sections[i] = s; layouts[i] = l; pages[i] = p; tops[i] = top; gaps[i] = gap
                if (first < 0) first = s
                last = s
                val lines = l.pages[p].lines
                val shift = round(contentTop + top + gap) - contentTop
                for (j in lines.indices) {
                    val ln = lines[j]; val t = shift + ln.top; val b = shift + ln.bottom
                    if (t >= -0.5f && b <= height + 0.5f) wholeBottom = maxOf(wholeBottom, minOf(height, b))
                    else if (t <= 0.5f && b > height && ln.bottom - ln.top > height) wholeBottom = height
                }
            }
            top += h
            if (++p >= l.pageCount) { p = 0; s++ }
        }
    }
    fun layoutOf(section: Int): SectionLayout? {
        for (i in 0 until count) if (sections[i] == section) return layouts[i]
        return null
    }
    /** This is also the exact origin passed to drawBody. */
    fun shift(index: Int, contentTop: Float): Float = round(contentTop + tops[index] + gaps[index]) - contentTop
    fun focusAt(y: Float, contentTop: Float, clip: Float): Int {
        if (y < 0f || y >= clip) return -1
        for (i in 0 until count) {
            val l = layouts[i] ?: continue
            val shift = shift(i, contentTop)
            for (ln in l.pages[pages[i]].lines) if (y >= shift + ln.top && y < shift + ln.bottom) return sections[i]
        }
        return -1
    }
    /**
     * The virtual page of every section the screen shows, in reading order (a screen over a seam gives two): what a
     * screen reader walks, where [virtualPage] gives the one section the reader is in.
     */
    fun virtualPages(contentTop: Float, clip: Float): List<VirtualPage> {
        var out: ArrayList<VirtualPage>? = null
        var seen = -1
        for (i in 0 until count) {
            val s = sections[i]
            if (s == seen) continue
            seen = s
            val vp = virtualPage(s, contentTop, clip) ?: continue
            if (out == null) out = ArrayList(2)
            out.add(vp)
        }
        return out ?: emptyList()
    }
    fun virtualPage(section: Int, contentTop: Float, clip: Float): VirtualPage? {
        var layout: SectionLayout? = null
        var pageIndex = -1
        var end = -1
        val lines = ArrayList<LineInfo>()
        for (i in 0 until count) {
            if (sections[i] != section) continue
            val l = layouts[i] ?: continue
            val shift = shift(i, contentTop)
            val ls = l.pages[pages[i]].lines
            for (j in ls.indices) {
                val ln = ls[j]; val top = ln.top + shift; val bottom = ln.bottom + shift
                if (top < -0.5f || bottom > clip + 0.5f) continue
                if (layout == null) { layout = l; pageIndex = pages[i] }
                lines.add(LineInfo(ln.start, ln.end, ln.x, top, ln.baseline + shift, bottom,
                    ln.justifyExtra, ln.expandMode, ln.imageBlock, ln.imageWidth, ln.imageHeight, ln.isRule))
                end = if (j + 1 < ls.size) ls[j + 1].start
                    else l.pages.getOrNull(pages[i] + 1)?.lines?.firstOrNull()?.start ?: l.content.length
            }
        }
        val l = layout ?: return null
        return VirtualPage(section, l, PageInfo(lines[0].start, end, lines), pageIndex)
    }
    fun whollyVisible(section: Int, offset: Int, contentTop: Float, clip: Float): Boolean {
        for (i in 0 until count) {
            if (sections[i] != section) continue
            val l = layouts[i] ?: continue
            val shift = shift(i, contentTop)
            for (ln in l.pages[pages[i]].lines) if (offset >= ln.start && offset < ln.end)
                return shift + ln.top >= -0.5f && shift + ln.bottom <= clip + 0.5f
        }
        return false
    }
}

/** A max-end tree retains long earlier quotes while pruning unrelated ranges. Built once per source list. */
internal class ScrollHighlights(val source: List<Highlight>) {
    private val size: Int
    private val maxEnd: IntArray
    init {
        var n = 1
        while (n < source.size) n = n shl 1
        size = n; maxEnd = IntArray(n * 2)
        for (i in source.indices) maxEnd[n + i] = source[i].end
        for (i in n - 1 downTo 1) maxEnd[i] = maxOf(maxEnd[i * 2], maxEnd[i * 2 + 1])
    }
    fun page(start: Int, end: Int): List<Highlight> {
        if (source.isEmpty() || start >= end) return emptyList()
        var out: ArrayList<Highlight>? = null
        fun visit(node: Int, from: Int, to: Int) {
            if (from >= source.size || source[from].start >= end || maxEnd[node] <= start) return
            if (to - from == 1) {
                if (out == null) out = ArrayList()
                out!!.add(source[from]); return
            }
            val mid = (from + to) ushr 1
            visit(node * 2, from, mid); visit(node * 2 + 1, mid, to)
        }
        visit(1, 0, size)
        return out ?: emptyList()
    }
}
