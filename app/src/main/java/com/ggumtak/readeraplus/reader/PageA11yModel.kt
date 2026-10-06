package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.LineGeometry
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionLayout
import kotlin.math.ceil
import kotlin.math.floor

/**
 * What the reader hands the screen-reader tree ([PageA11y]): the page (or the scroll viewport's virtual page) with
 * its section and where its content box sits inside the view. [owner] (the book session) and [generation] (the
 * layout generation) tell the tree when old ids and focus no longer mean anything.
 */
class A11ySource(
    val owner: Any,
    val generation: Int,
    val section: Int,
    val layout: SectionLayout,
    val page: PageInfo,
    /** Content box origin inside the view (px). */
    val left: Float,
    val top: Float,
)

/** Kinds of virtual node. */
internal enum class A11yRole { PARAGRAPH, LINK }

/** Identity of a node across pages: the section, the paragraph's (or link's) first char and the kind. */
internal data class A11yKey(val section: Int, val start: Int, val role: A11yRole)

/**
 * One virtual node of a page, in view pixels already clipped to the view. [start] is the first char of the
 * paragraph (a link: of the link's text on this page); [textStart, textEnd) is the text read.
 */
internal class A11yNode(
    val role: A11yRole,
    val start: Int,
    val textStart: Int,
    val textEnd: Int,
    val text: String,
    /** Link target (raw href) of a [A11yRole.LINK] node. */
    val href: String?,
    val heading: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** Pure: splits a page into the nodes a screen reader walks (paragraph parts, each followed by its links). */
internal object A11yFragments {

    private class Group(var start: Int, var end: Int)

    /**
     * Nodes of [page] of [layout] (section [section]) with the content box at ([left], [top]) in a view of
     * [viewW] x [viewH] px. One paragraph node per paragraph part on the page (a paragraph split over two pages gives
     * a part on each), covering the union of its line rectangles (the geometry drawing and tap hit-testing use); a
     * picture-only part reads [A11yText.PICTURE]. A link inside it follows as a node of its own. Rules, blank lines
     * and parts without readable text give no node; a rectangle outside the view is dropped, one half outside is cut.
     */
    fun build(
        layout: SectionLayout,
        page: PageInfo,
        left: Float,
        top: Float,
        viewW: Int,
        viewH: Int,
    ): List<A11yNode> {
        val content = layout.content
        val blocks = content.blocks
        if (blocks.isEmpty() || viewW <= 0 || viewH <= 0) return emptyList()
        val groups = LinkedHashMap<Int, Group>()
        for (ln: LineInfo in page.lines) {
            val img = ln.imageBlock
            val s: Int
            val e: Int
            if (img != null) {
                s = img.start; e = img.start + 1
            } else {
                if (ln.isRule || ln.end <= ln.start) continue
                s = ln.start; e = ln.end
            }
            val b = content.blockIndexAt(s)
            val g = groups[b]
            if (g == null) groups[b] = Group(s, e) else { g.start = minOf(g.start, s); g.end = maxOf(g.end, e) }
        }
        val out = ArrayList<A11yNode>(groups.size)
        val text = content.text
        for ((index, g) in groups) {
            val block = blocks[index]
            val read = A11yText.page(text, g.start, g.end)
            if (read.isEmpty()) continue
            val heading = (block as? ParagraphBlock)?.style?.headingLevel?.let { it > 0 } == true
            val r = bounds(layout, page, g.start, g.end, left, top, viewW, viewH)
            if (r != null) {
                out += A11yNode(A11yRole.PARAGRAPH, block.start, g.start, g.end, read, null, heading, r[0], r[1], r[2], r[3])
            }
            links(layout, page, g.start, g.end, left, top, viewW, viewH, out)
        }
        return out
    }

    /** The links inside [start, end): contiguous runs of one target are one node. */
    private fun links(
        layout: SectionLayout, page: PageInfo, start: Int, end: Int,
        left: Float, top: Float, viewW: Int, viewH: Int, out: MutableList<A11yNode>,
    ) {
        val runs = layout.content.styleRuns
        if (runs.isEmpty()) return
        // First run that ends after [start].
        var lo = 0
        var hi = runs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (runs[mid].end <= start) lo = mid + 1 else hi = mid
        }
        var href: String? = null
        var a = 0
        var b = 0
        fun flush() {
            val h = href ?: return
            href = null
            val read = A11yText.page(layout.content.text, a, b)
            if (read.isEmpty()) return
            val r = bounds(layout, page, a, b, left, top, viewW, viewH) ?: return
            out += A11yNode(A11yRole.LINK, a, a, b, read, h, false, r[0], r[1], r[2], r[3])
        }
        var i = lo
        while (i < runs.size && runs[i].start < end) {
            val run = runs[i]
            val link = run.style.link
            val ra = maxOf(run.start, start)
            val rb = minOf(run.end, end)
            if (link != null && ra < rb) {
                if (href == link && b == ra) {
                    b = rb
                } else {
                    flush()
                    href = link; a = ra; b = rb
                }
            }
            i++
        }
        flush()
    }

    /** Union of the line rectangles of [start, end) in view px, clipped to the view; null when nothing is left. */
    private fun bounds(
        layout: SectionLayout, page: PageInfo, start: Int, end: Int,
        left: Float, top: Float, viewW: Int, viewH: Int,
    ): IntArray? {
        val rects = LineGeometry.rangeRects(layout, page, start, end)
        if (rects.isEmpty()) return null
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (q in rects) {
            l = minOf(l, q.left); t = minOf(t, q.top); r = maxOf(r, q.right); b = maxOf(b, q.bottom)
        }
        val il = maxOf(0, floor(l + left).toInt())
        val iTop = maxOf(0, floor(t + top).toInt())
        val ir = minOf(viewW, ceil(r + left).toInt())
        val ib = minOf(viewH, ceil(b + top).toInt())
        return if (ir > il && ib > iTop) intArrayOf(il, iTop, ir, ib) else null
    }

    /** Index of the node under ([x], [y]) in view px, or -1. Later nodes win: a link lies over its paragraph. */
    fun indexAt(nodes: List<A11yNode>, x: Float, y: Float): Int {
        for (i in nodes.indices.reversed()) if (nodes[i].contains(x, y)) return i
        return -1
    }
}

/**
 * Session-local small ids for virtual nodes: the same (section, start, role) always gets the same id while it is
 * remembered; ids are handed out upwards and never reused, so a stale id from a screen reader can never name another
 * node. After a page's keys were asked for, [trim] forgets all but those and a small LRU of earlier ones.
 */
internal class A11yIds(private val spare: Int = SPARE) {
    private val ids = LinkedHashMap<A11yKey, Int>(32, 0.75f, true)
    private var next = FIRST

    val size: Int get() = ids.size

    fun idFor(key: A11yKey): Int {
        ids[key]?.let { return it }
        if (next >= LAST) clear()
        val id = next++
        ids[key] = id
        return id
    }

    /** Keeps the [current] most recently asked keys (the page just built) plus [spare] older ones. */
    fun trim(current: Int) {
        val limit = current + spare
        var drop = ids.size - limit
        if (drop <= 0) return
        val iter = ids.entries.iterator()
        while (drop > 0 && iter.hasNext()) { iter.next(); iter.remove(); drop-- }
    }

    /** A new book or layout generation: every key is forgotten (the counter goes on, so no id is reused). */
    fun clear() {
        ids.clear()
        if (next >= LAST) next = FIRST
    }

    companion object {
        /** Virtual ids start above 0; View.NO_ID (-1) is the host. */
        const val FIRST = 1
        private const val LAST = Int.MAX_VALUE - 1
        const val SPARE = 24
    }
}
