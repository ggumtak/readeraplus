package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.LineGeometry
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionLayout
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One page of one section on screen: [page] of [layout] with the content box's origin ([left], [top]) inside the
 * view (px). Paged mode: the page shown. Scroll mode: the wholly visible lines of one section (lines already
 * shifted to where they are drawn, see [ScrollWindow.virtualPages]).
 */
class A11yEntry(
    val section: Int,
    val layout: SectionLayout,
    val page: PageInfo,
    val left: Float,
    val top: Float,
) {
    fun sameAs(o: A11yEntry): Boolean =
        section == o.section && page === o.page && layout === o.layout && left == o.left && top == o.top
}

/**
 * What the reader hands the screen-reader tree ([PageA11y]): everything on screen as entries in reading order (paged:
 * one; scroll: one per section a screen shows, so a seam between two sections exposes both). [owner] (the book
 * session) and [generation] (the layout generation) tell the tree when old ids and focus no longer mean anything.
 */
class A11ySource(
    val owner: Any,
    val generation: Int,
    val entries: List<A11yEntry>,
) {
    /** The same pages of the same layouts at the same place (a cached tree built from [o] still holds). */
    fun sameAs(o: A11ySource): Boolean {
        if (entries.size != o.entries.size) return false
        for (i in entries.indices) if (!entries[i].sameAs(o.entries[i])) return false
        return true
    }
}

/**
 * What the screen-reader tree was last announced for. A relayout or a mode switch that shows the same layout object
 * again sends nothing; a new layout (a re-parse, a font size) or the other mode (the tree has other nodes) does.
 */
internal class A11yShown {
    private var layout: Any? = null
    private var generation = -1
    private var scroll = false

    /** True when ([layout], [generation], [scroll]) differ from the last call's (the first call always); remembers them. */
    fun changed(layout: Any, generation: Int, scroll: Boolean): Boolean {
        val same = this.layout === layout && this.generation == generation && this.scroll == scroll
        this.layout = layout
        this.generation = generation
        this.scroll = scroll
        return !same
    }

    /** A book closed: the next call counts as changed (and no layout is held). */
    fun clear() {
        layout = null
        generation = -1
        scroll = false
    }
}

/** Kinds of virtual node. */
internal enum class A11yRole { PARAGRAPH, LINK }

/** Identity of a node across pages: the section, the paragraph's (or link's) first char and the kind. */
internal data class A11yKey(val section: Int, val start: Int, val role: A11yRole)

/**
 * One virtual node of a page, in view pixels already clipped to the view. [start] is the first char of the
 * paragraph (a link: of the link's text on this page); [textStart, textEnd) is the text read.
 */
internal class A11yNode(
    val section: Int,
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
    /**
     * Hit-test rectangles ([left, top, right, bottom] each, clipped to the view) when the bounds above are not the
     * shape: a link over several lines is only where its lines are, not the box around them. Null = the bounds.
     */
    val parts: List<IntArray>? = null,
) {
    fun contains(x: Float, y: Float): Boolean {
        if (!(x >= left && x < right && y >= top && y < bottom)) return false
        val p = parts ?: return true
        for (r in p) if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return true
        return false
    }
}

/** Pure: splits a page into the nodes a screen reader walks (paragraph parts, each followed by its links). */
internal object A11yFragments {

    private class Group(var start: Int, var end: Int)

    /** Nodes of every entry in order ([build] each; ids carry the section, so entries never collide). */
    fun buildAll(entries: List<A11yEntry>, viewW: Int, viewH: Int): List<A11yNode> {
        if (entries.size == 1) return entries[0].let { build(it.layout, it.page, it.left, it.top, viewW, viewH, it.section) }
        val out = ArrayList<A11yNode>()
        for (e in entries) out += build(e.layout, e.page, e.left, e.top, viewW, viewH, e.section)
        return out
    }

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
        section: Int = 0,
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
                out += A11yNode(section, A11yRole.PARAGRAPH, block.start, g.start, g.end, read, null, heading, r.l, r.t, r.r, r.b)
            }
            links(section, layout, page, g.start, g.end, left, top, viewW, viewH, out)
        }
        return out
    }

    /** The links inside [start, end): contiguous runs of one target are one node. */
    private fun links(
        section: Int, layout: SectionLayout, page: PageInfo, start: Int, end: Int,
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
            out += A11yNode(section, A11yRole.LINK, a, a, b, read, h, false, r.l, r.t, r.r, r.b, if (r.parts.size > 1) r.parts else null)
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

    /** A range's box in view px: the union of its line rectangles and the rectangles themselves (all clipped). */
    private class Box(val l: Int, val t: Int, val r: Int, val b: Int, val parts: List<IntArray>)

    /** The line rectangles of [start, end) in view px, clipped to the view; null when nothing is left. */
    private fun bounds(
        layout: SectionLayout, page: PageInfo, start: Int, end: Int,
        left: Float, top: Float, viewW: Int, viewH: Int,
    ): Box? {
        val rects = LineGeometry.rangeRects(layout, page, start, end)
        if (rects.isEmpty()) return null
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        val parts = ArrayList<IntArray>(rects.size)
        for (q in rects) {
            val il = maxOf(0, floor(q.left + left).toInt())
            val iTop = maxOf(0, floor(q.top + top).toInt())
            val ir = minOf(viewW, ceil(q.right + left).toInt())
            val ib = minOf(viewH, ceil(q.bottom + top).toInt())
            if (ir <= il || ib <= iTop) continue
            parts += intArrayOf(il, iTop, ir, ib)
            l = minOf(l, il); t = minOf(t, iTop); r = maxOf(r, ir); b = maxOf(b, ib)
        }
        return if (parts.isEmpty()) null else Box(l, t, r, b, parts)
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
