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
 * [color] is an ARGB int; [width] the stroke width in page points.
 */
internal class InkStroke(val tool: Int, val color: Int, val width: Float, val points: FloatArray) {
    val pointCount: Int get() = points.size / 2
}

/**
 * A book's PDF annotations: ink strokes per page and bookmarked pages, with one undo history for adds and erases.
 * Main thread only. [dirty] turns true on every change (the caller saves and clears it).
 */
internal class PdfNotes {
    /** True after any change since the last time the caller cleared it. */
    var dirty: Boolean = false

    private val byPage = HashMap<Int, ArrayList<InkStroke>>()
    private val marks = TreeSet<Int>()
    private val history = ArrayList<Step>()
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
        push(Step(page, stroke, null, null))
        dirty = true
    }

    /**
     * Removes every stroke on [page] that passes within [radius] (+ half its width) of (x, y): distance from the
     * point to any segment of the stroke (or to the point, for a one-point stroke). All strokes removed by one call
     * are ONE undo step. Returns true when something was removed.
     */
    fun eraseAt(page: Int, x: Float, y: Float, radius: Float): Boolean {
        val list = byPage[page] ?: return false
        var hits = 0
        for (s in list) if (hit(s, x, y, radius)) hits++
        if (hits == 0) return false
        val at = IntArray(hits)
        val gone = ArrayList<InkStroke>(hits)
        val keep = ArrayList<InkStroke>(list.size - hits)
        for (i in list.indices) {
            val s = list[i]
            if (hit(s, x, y, radius)) {
                at[gone.size] = i
                gone.add(s)
            } else {
                keep.add(s)
            }
        }
        if (keep.isEmpty()) byPage.remove(page) else byPage[page] = keep
        push(Step(page, null, at, gone.toTypedArray()))
        dirty = true
        return true
    }

    private fun hit(s: InkStroke, x: Float, y: Float, radius: Float): Boolean {
        val p = s.points
        val reach = radius + s.width * 0.5f
        val n = p.size / 2
        if (n == 0) return false
        if (n == 1) return InkMath.distToSegment(x, y, p[0], p[1], p[0], p[1]) <= reach
        for (i in 0 until n - 1) {
            val o = i * 2
            if (InkMath.distToSegment(x, y, p[o], p[o + 1], p[o + 2], p[o + 3]) <= reach) return true
        }
        return false
    }

    private fun push(step: Step) {
        history.add(if (group != 0) Step(step.page, step.added, step.removedAt, step.removed, group) else step)
        if (history.size > MAX_HISTORY) history.removeAt(0)
    }

    /** True when [undo] has something to revert. */
    val canUndo: Boolean get() = history.isNotEmpty()

    /**
     * Reverts the last add / erase (an erase puts its strokes back in their original order/positions).
     * Returns the page it touched, or -1. History is capped at 100 steps (oldest dropped).
     */
    fun undo(): Int {
        if (history.isEmpty()) return -1
        val last = history.removeAt(history.size - 1)
        undoStep(last)
        // The rest of its group, newest first.
        if (last.group != 0) {
            while (history.isNotEmpty() && history[history.size - 1].group == last.group) {
                undoStep(history.removeAt(history.size - 1))
            }
        }
        dirty = true
        return last.page
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
     * JSON: {"v":1,"bookmarks":[…],"pages":{"<page>":[{"t":tool,"c":color,"w":width,"p":[x,y,…]}, …]}}.
     * Floats are rounded to 2 decimals to keep files small.
     */
    fun toJson(): String {
        val sb = StringBuilder(256)
        sb.append("{\"v\":1,\"bookmarks\":[")
        var first = true
        for (m in marks) {
            if (!first) sb.append(',')
            first = false
            sb.append(m)
        }
        sb.append("],\"pages\":{")
        val pages = pagesWithInk()
        for (pi in pages.indices) {
            if (pi > 0) sb.append(',')
            val page = pages[pi]
            sb.append('"').append(page).append("\":[")
            val list = byPage[page]!!
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
                sb.append("]}")
            }
            sb.append(']')
        }
        sb.append("}}")
        return sb.toString()
    }

    companion object {
        private const val MAX_HISTORY = 100

        /** Appends [v] rounded to 2 decimals with trailing zeros trimmed (never exponent notation). */
        private fun appendFloat(sb: StringBuilder, v: Float) {
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
            return InkStroke(tool, c.toLong().toInt(), width, pts)
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
