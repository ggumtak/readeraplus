package com.ggumtak.readeraplus.reader.pdf

import java.io.File
import java.util.TreeSet
import org.json.JSONArray
import org.json.JSONObject

/** Annotation tools. */
internal object InkTool {
    const val PEN = 0
    const val HIGHLIGHTER = 1
}

/**
 * One finished stroke on a page, in PDF page points (1/72 in, origin top-left of the page).
 * [points] = x0, y0, x1, y1, … (at least one point = 2 floats). Immutable (never mutate the array after construction).
 * [color] is an ARGB int; [width] the stroke width in page points (the full width, for a pressure stroke).
 * [pressures]: one steadied pen pressure (0..1) per point, or null for a constant-width stroke.
 */
internal class InkStroke(
    val tool: Int,
    val color: Int,
    val width: Float,
    val points: FloatArray,
    val pressures: FloatArray? = null,
) {
    val pointCount: Int get() = points.size / 2

    // Bounding box of the points, computed on first use (the eraser asks on every move); main thread only.
    private var boxReady = false
    private var boxL = 0f
    private var boxT = 0f
    private var boxR = 0f
    private var boxB = 0f

    /**
     * False only when (x, y) is certainly farther than [reach] from every point of the stroke, judged by its bounding
     * box (made once, no allocation afterwards). True still needs the exact distance test.
     */
    fun mayReach(x: Float, y: Float, reach: Float): Boolean {
        if (!boxReady) makeBox()
        val r = reach + BOX_SLACK
        return x >= boxL - r && x <= boxR + r && y >= boxT - r && y <= boxB + r
    }

    private fun makeBox() {
        val p = points
        val n = p.size / 2
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val x = p[i * 2]
            val y = p[i * 2 + 1]
            if (x.isNaN() || y.isNaN()) {
                // Never skipped: the exact test decides, as without a box.
                l = Float.NEGATIVE_INFINITY
                t = Float.NEGATIVE_INFINITY
                r = Float.POSITIVE_INFINITY
                b = Float.POSITIVE_INFINITY
                break
            }
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        boxL = l
        boxT = t
        boxR = r
        boxB = b
        boxReady = true
    }

    private companion object {
        /** Covers the rounding of the exact distance test, so the box never rejects what that test would accept. */
        const val BOX_SLACK = 0.01f
    }
}

/**
 * A book's PDF annotations: ink strokes per page and bookmarked pages, with one undo / redo history for adds and
 * erases. Main thread only. [dirty] turns true on every change (the caller saves and clears it).
 */
internal class PdfNotes {
    /** True after any change since the last time the caller cleared it. */
    var dirty: Boolean = false

    private val byPage = HashMap<Int, ArrayList<InkStroke>>()
    private val marks = TreeSet<Int>()
    private val history = ArrayList<Step>()
    /** Undone steps, the next one to redo last. Any new edit empties it. */
    private val redoSteps = ArrayList<Step>()
    /** Steps recorded while a group is open share its id and undo together (0 = no group). */
    private var group = 0
    private var groupSeq = 0

    /** One undo step: either an added stroke ([added]) or a set of erased strokes with their old list positions. */
    private class Step(
        val page: Int,
        val added: InkStroke?,
        val removedAt: IntArray?,
        val removed: Array<InkStroke>?,
        val group: Int = 0,
    )

    /** Starts one undo step made of several changes (an eraser drag, a highlight over many lines). */
    fun beginGroup() {
        group = ++groupSeq
    }

    /** Ends the group started by [beginGroup]. */
    fun endGroup() {
        group = 0
    }

    /** No strokes and no bookmarks. */
    val isEmpty: Boolean get() = byPage.isEmpty() && marks.isEmpty()

    /** Strokes on [page] in draw order; empty when none. Do not mutate through a cast. */
    fun strokes(page: Int): List<InkStroke> = byPage[page] ?: emptyList()

    /** Pages that have at least one stroke, ascending. */
    fun pagesWithInk(): IntArray {
        val out = IntArray(byPage.size)
        var i = 0
        for (k in byPage.keys) out[i++] = k
        out.sort()
        return out
    }

    /** Appends [stroke] to [page] and records an undo step. Negative pages are ignored. */
    fun add(page: Int, stroke: InkStroke) {
        if (page < 0) return
        byPage.getOrPut(page) { ArrayList() }.add(stroke)
        push(Step(page, stroke, null, null, group))
        dirty = true
    }

    /**
     * Removes every stroke on [page] that passes within [radius] (+ half its width) of (x, y): distance from the
     * point to any segment of the stroke (or to the point, for a one-point stroke). All strokes removed by one call
     * are ONE undo step. Returns true when something was removed. A call that hits nothing allocates nothing.
     */
    fun eraseAt(page: Int, x: Float, y: Float, radius: Float): Boolean {
        val list = byPage[page] ?: return false
        var keep: ArrayList<InkStroke>? = null
        var gone: ArrayList<InkStroke>? = null
        var at = EMPTY_INTS
        for (i in list.indices) {
            val s = list[i]
            if (hit(s, x, y, radius)) {
                if (keep == null) {
                    keep = ArrayList(list.size)
                    for (j in 0 until i) keep.add(list[j])
                    gone = ArrayList(4)
                    at = IntArray(4)
                }
                if (gone!!.size == at.size) at = at.copyOf(at.size * 2)
                at[gone.size] = i
                gone.add(s)
            } else {
                keep?.add(s)
            }
        }
        if (keep == null || gone == null) return false
        if (keep.isEmpty()) byPage.remove(page) else byPage[page] = keep
        push(Step(page, null, at.copyOf(gone.size), gone.toTypedArray(), group))
        dirty = true
        return true
    }

    private fun hit(s: InkStroke, x: Float, y: Float, radius: Float): Boolean {
        val p = s.points
        val reach = radius + s.width * 0.5f
        val n = p.size / 2
        if (n == 0) return false
        // Most strokes are nowhere near the eraser: the cached bounding box answers without touching the points.
        if (!s.mayReach(x, y, reach)) return false
        if (n == 1) return InkMath.distToSegment(x, y, p[0], p[1], p[0], p[1]) <= reach
        for (i in 0 until n - 1) {
            val o = i * 2
            if (InkMath.distToSegment(x, y, p[o], p[o + 1], p[o + 2], p[o + 3]) <= reach) return true
        }
        return false
    }

    /** Records a new edit: it ends any redo and keeps the history within [MAX_HISTORY] undo units. */
    private fun push(step: Step) {
        redoSteps.clear()
        history.add(step)
        trimHistory()
    }

    /**
     * Drops the oldest undo units while there are more than [MAX_HISTORY] of them. A unit is a whole group (an eraser
     * drag, a highlight over many lines) or one ungrouped step, so a long drag counts once and is never cut in half;
     * the group still being built stays.
     */
    private fun trimHistory() {
        // Units never outnumber steps: nothing to count below the cap.
        if (history.size <= MAX_HISTORY) return
        var units = 0
        for (i in history.indices) if (startsUnit(i)) units++
        while (units > MAX_HISTORY) {
            val g = history[0].group
            if (g != 0 && g == group) return
            var n = 1
            if (g != 0) while (n < history.size && history[n].group == g) n++
            history.subList(0, n).clear()
            units--
        }
    }

    /** Whether history[i] begins an undo unit: it is ungrouped, or its group differs from the step before. */
    private fun startsUnit(i: Int): Boolean {
        val g = history[i].group
        return g == 0 || i == 0 || history[i - 1].group != g
    }

    /** Removes every stroke of [page] as one undo step; false when it had none. */
    fun clearPage(page: Int): Boolean {
        val list = byPage[page] ?: return false
        if (list.isEmpty()) return false
        val removed = list.toTypedArray()
        val at = IntArray(removed.size) { it }
        byPage.remove(page)
        push(Step(page, null, at, removed, group))
        dirty = true
        return true
    }

    /** Removes every stroke of every page (bookmarks stay) and the undo / redo history; returns how many were removed. */
    fun clearAllInk(): Int {
        var n = 0
        for (list in byPage.values) n += list.size
        if (n == 0) return 0
        byPage.clear()
        history.clear()
        redoSteps.clear()
        dirty = true
        return n
    }

    /** True when [undo] has something to revert. */
    val canUndo: Boolean get() = history.isNotEmpty()

    /** True when [redo] has something to put back (after an [undo], until the next edit). */
    val canRedo: Boolean get() = redoSteps.isNotEmpty()

    /**
     * Reverts the last add / erase (an erase puts its strokes back in their original order/positions); a group goes
     * back as a whole. The reverted steps wait for [redo]. Returns the page it touched, or -1. History is capped at
     * 100 undo units (a group counts as one); the oldest units are dropped first.
     */
    fun undo(): Int {
        if (history.isEmpty()) return -1
        val last = history.removeAt(history.size - 1)
        undoStep(last)
        redoSteps.add(last)
        // The rest of its group, newest first.
        if (last.group != 0) {
            while (history.isNotEmpty() && history[history.size - 1].group == last.group) {
                val s = history.removeAt(history.size - 1)
                undoStep(s)
                redoSteps.add(s)
            }
        }
        dirty = true
        return last.page
    }

    /**
     * Puts back what [undo] reverted last (a whole group at once). Returns the page it touched, or -1 when there is
     * nothing to redo. Any new edit (add, erase, clear) ends the redo.
     */
    fun redo(): Int {
        if (redoSteps.isEmpty()) return -1
        var step = redoSteps.removeAt(redoSteps.size - 1)
        val g = step.group
        redoStep(step)
        history.add(step)
        var page = step.page
        if (g != 0) {
            while (redoSteps.isNotEmpty() && redoSteps[redoSteps.size - 1].group == g) {
                step = redoSteps.removeAt(redoSteps.size - 1)
                redoStep(step)
                history.add(step)
                page = step.page
            }
        }
        dirty = true
        return page
    }

    private fun redoStep(step: Step) {
        val added = step.added
        if (added != null) {
            byPage.getOrPut(step.page) { ArrayList() }.add(added)
            return
        }
        val list = byPage[step.page] ?: return
        val at = step.removedAt!!
        val gone = step.removed!!
        // Highest position first, so the lower ones stay valid; the stroke itself is checked, not just the slot.
        for (k in gone.indices.reversed()) {
            var idx = at[k]
            if (idx >= list.size || list[idx] !== gone[k]) {
                idx = -1
                for (i in list.indices) {
                    if (list[i] === gone[k]) {
                        idx = i
                        break
                    }
                }
            }
            if (idx >= 0) list.removeAt(idx)
        }
        if (list.isEmpty()) byPage.remove(step.page)
    }

    private fun undoStep(step: Step) {
        val added = step.added
        if (added != null) {
            val list = byPage[step.page]
            if (list != null) {
                var idx = -1
                for (i in list.indices.reversed()) {
                    if (list[i] === added) {
                        idx = i
                        break
                    }
                }
                if (idx >= 0) list.removeAt(idx)
                if (list.isEmpty()) byPage.remove(step.page)
            }
        } else {
            val at = step.removedAt!!
            val back = step.removed!!
            val list = byPage.getOrPut(step.page) { ArrayList() }
            for (k in back.indices) list.add(minOf(at[k], list.size), back[k])
        }
    }

    /** Whether [page] is bookmarked. */
    fun isBookmarked(page: Int): Boolean = marks.contains(page)

    /** Flips the bookmark of [page] and returns the new state. Not an undo step. */
    fun toggleBookmark(page: Int): Boolean {
        if (page < 0) return false
        val now = if (marks.remove(page)) false else marks.add(page)
        dirty = true
        return now
    }

    /** Bookmarked pages, ascending. */
    fun bookmarks(): IntArray {
        val out = IntArray(marks.size)
        var i = 0
        for (m in marks) out[i++] = m
        return out
    }

    /**
     * A copy of the notes for serialising and writing elsewhere. Cheap, so it can be taken on the main thread:
     * strokes are immutable, only the per-page lists and the bookmarks are copied.
     */
    fun snapshot(): NotesSnapshot {
        val keys = pagesWithInk()
        val lists = Array(keys.size) { byPage[keys[it]]!!.toTypedArray() }
        return NotesSnapshot(bookmarks(), keys, lists)
    }

    /** The notes as JSON (see [NotesSnapshot.toJson]). */
    fun toJson(): String = snapshot().toJson()

    companion object {
        private const val MAX_HISTORY = 100
        private val EMPTY_INTS = IntArray(0)

        /** Tolerant parse: malformed JSON → empty notes; malformed strokes / negative pages skipped. dirty = false. */
        fun fromJson(json: String): PdfNotes {
            val notes = PdfNotes()
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                return notes
            }
            try {
                val marks = root.optJSONArray("bookmarks")
                if (marks != null) {
                    for (i in 0 until marks.length()) {
                        val n = marks.opt(i)
                        if (n is Number) {
                            val d = n.toDouble()
                            val p = n.toInt()
                            if (p >= 0 && d == p.toDouble()) notes.marks.add(p)
                        }
                    }
                }
                val pages = root.optJSONObject("pages")
                if (pages != null) {
                    val keys = pages.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val page = key.toIntOrNull() ?: continue
                        if (page < 0) continue
                        val arr = pages.optJSONArray(key) ?: continue
                        for (i in 0 until arr.length()) {
                            val stroke = parseStroke(arr.optJSONObject(i)) ?: continue
                            notes.byPage.getOrPut(page) { ArrayList() }.add(stroke)
                        }
                    }
                }
            } catch (e: Exception) {
                // keep whatever parsed before the failure
            }
            notes.dirty = false
            return notes
        }

        private fun parseStroke(o: JSONObject?): InkStroke? {
            if (o == null) return null
            val t = o.opt("t") as? Number ?: return null
            val c = o.opt("c") as? Number ?: return null
            val w = o.opt("w") as? Number ?: return null
            val tool = t.toInt()
            if (tool != InkTool.PEN && tool != InkTool.HIGHLIGHTER) return null
            val width = w.toFloat()
            if (width.isNaN() || width.isInfinite() || width < 0f) return null
            val p: JSONArray = o.optJSONArray("p") ?: return null
            val len = p.length()
            if (len < 2 || len % 2 != 0) return null
            val pts = FloatArray(len)
            for (i in 0 until len) {
                val v = (p.opt(i) as? Number)?.toFloat() ?: return null
                if (v.isNaN() || v.isInfinite()) return null
                pts[i] = v
            }
            // Pressures are optional (older files, finger strokes); a wrong count drops them, not the stroke.
            val qa: JSONArray? = o.optJSONArray("q")
            var q: FloatArray? = null
            if (qa != null && qa.length() == len / 2) {
                val arr = FloatArray(qa.length())
                var ok = true
                for (i in arr.indices) {
                    val v = (qa.opt(i) as? Number)?.toFloat()
                    if (v == null || v.isNaN() || v.isInfinite()) {
                        ok = false
                        break
                    }
                    arr[i] = v.coerceIn(0f, 1f)
                }
                if (ok) q = arr
            }
            return InkStroke(tool, c.toLong().toInt(), width, pts, q)
        }
    }
}

/**
 * An immutable copy of a [PdfNotes] (see [PdfNotes.snapshot]): bookmarks and, for each page with ink, its strokes in
 * draw order. Safe to hand to another thread; strokes are never mutated.
 */
internal class NotesSnapshot(
    private val marks: IntArray,
    /** Pages with ink, ascending. */
    private val pages: IntArray,
    /** The strokes of `pages[i]`. */
    private val strokes: Array<Array<InkStroke>>,
) {
    /** No strokes and no bookmarks. */
    val isEmpty: Boolean get() = pages.isEmpty() && marks.isEmpty()

    /**
     * JSON: {"v":1,"bookmarks":[…],"pages":{"<page>":[{"t":tool,"c":color,"w":width,"p":[x,y,…]}, …]}}.
     * Floats are rounded to 2 decimals to keep files small.
     */
    fun toJson(): String {
        val sb = StringBuilder(256)
        sb.append("{\"v\":1,\"bookmarks\":[")
        for (i in marks.indices) {
            if (i > 0) sb.append(',')
            sb.append(marks[i])
        }
        sb.append("],\"pages\":{")
        for (pi in pages.indices) {
            if (pi > 0) sb.append(',')
            sb.append('"').append(pages[pi]).append("\":[")
            val list = strokes[pi]
            for (si in list.indices) {
                if (si > 0) sb.append(',')
                val s = list[si]
                sb.append("{\"t\":").append(s.tool).append(",\"c\":").append(s.color).append(",\"w\":")
                appendFloat(sb, s.width)
                sb.append(",\"p\":[")
                for (i in s.points.indices) {
                    if (i > 0) sb.append(',')
                    appendFloat(sb, s.points[i])
                }
                sb.append(']')
                val q = s.pressures
                if (q != null) {
                    sb.append(",\"q\":[")
                    for (i in q.indices) {
                        if (i > 0) sb.append(',')
                        appendFloat(sb, q[i])
                    }
                    sb.append(']')
                }
                sb.append('}')
            }
            sb.append(']')
        }
        sb.append("}}")
        return sb.toString()
    }

    private companion object {
        /** Appends [v] rounded to 2 decimals with trailing zeros trimmed (never exponent notation). */
        fun appendFloat(sb: StringBuilder, v: Float) {
            if (v.isNaN() || v.isInfinite()) {
                sb.append('0')
                return
            }
            val scaled = Math.round(v.toDouble() * 100.0)
            if (scaled == 0L) {
                sb.append('0')
                return
            }
            val abs = if (scaled < 0) -scaled else scaled
            if (scaled < 0) sb.append('-')
            sb.append(abs / 100)
            val frac = (abs % 100).toInt()
            if (frac != 0) {
                sb.append('.').append((frac / 10).toChar() + '0'.code)
                if (frac % 10 != 0) sb.append((frac % 10).toChar() + '0'.code)
            }
        }
    }
}

/** Per-book JSON files `<dir>/<bookId>.json`. Blocking IO; thread-safe (synchronized). */
internal object PdfNotesStore {
    /** The notes file of [bookId] inside [dir]. */
    fun file(dir: File, bookId: Long): File = File(dir, "$bookId.json")

    /** Missing / unreadable / corrupt → empty [PdfNotes] (never throws). */
    @Synchronized
    fun load(dir: File, bookId: Long): PdfNotes {
        return try {
            val f = file(dir, bookId)
            if (!f.isFile) PdfNotes() else PdfNotes.fromJson(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            PdfNotes()
        }
    }

    /**
     * Writes [json] atomically (temp file + rename, creating [dir]); null deletes the file.
     * Returns false on IO failure (never throws).
     */
    @Synchronized
    fun save(dir: File, bookId: Long, json: String?): Boolean {
        return try {
            val target = file(dir, bookId)
            if (json == null) {
                !target.exists() || target.delete()
            } else {
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
                val tmp = File(dir, "$bookId.json.tmp")
                tmp.writeText(json, Charsets.UTF_8)
                if (tmp.renameTo(target)) {
                    true
                } else {
                    target.delete()
                    val ok = tmp.renameTo(target)
                    if (!ok) tmp.delete()
                    ok
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}

/** Pure geometry for strokes and lasso selection. */
internal object InkMath {
    /**
     * The first [count] points of [points] (x,y pairs) without points closer than [minDist] to the last kept one;
     * first and last points always kept. Returns a new exact-size array.
     */
    fun simplify(points: FloatArray, count: Int, minDist: Float): FloatArray {
        val n = minOf(count, points.size / 2)
        if (n <= 0) return FloatArray(0)
        if (n == 1) return floatArrayOf(points[0], points[1])
        val out = FloatArray(n * 2)
        out[0] = points[0]
        out[1] = points[1]
        var kept = 1
        var lx = points[0]
        var ly = points[1]
        val min2 = minDist * minDist
        for (i in 1 until n - 1) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            val dx = x - lx
            val dy = y - ly
            if (dx * dx + dy * dy < min2) continue
            out[kept * 2] = x
            out[kept * 2 + 1] = y
            kept++
            lx = x
            ly = y
        }
        out[kept * 2] = points[(n - 1) * 2]
        out[kept * 2 + 1] = points[(n - 1) * 2 + 1]
        kept++
        return if (kept * 2 == out.size) out else out.copyOf(kept * 2)
    }

    /** Distance from point (px, py) to the segment (ax, ay)–(bx, by); a zero-length segment is a point. */
    fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax
        val aby = by - ay
        val len2 = abx * abx + aby * aby
        var cx = ax
        var cy = ay
        if (len2 > 0f) {
            val t = (((px - ax) * abx + (py - ay) * aby) / len2).coerceIn(0f, 1f)
            cx = ax + t * abx
            cy = ay + t * aby
        }
        val dx = px - cx
        val dy = py - cy
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /** Even-odd point-in-polygon for the first [n] points of [poly] (x,y pairs; closed implicitly). n < 3 → false. */
    fun contains(poly: FloatArray, n: Int, x: Float, y: Float): Boolean {
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** [left, top, right, bottom] of the first [n] points (n >= 1; smaller n yields all zeros). */
    fun bounds(points: FloatArray, n: Int): FloatArray {
        if (n < 1) return FloatArray(4)
        var l = points[0]
        var t = points[1]
        var r = l
        var b = t
        for (i in 1 until n) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            if (x < l) l = x else if (x > r) r = x
            if (y < t) t = y else if (y > b) b = y
        }
        return floatArrayOf(l, t, r, b)
    }
}
