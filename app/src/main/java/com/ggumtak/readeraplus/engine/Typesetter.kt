package com.ggumtak.readeraplus.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns a [SectionContent] into pages of positioned lines (see docs/ARCHITECTURE.md "engine/").
 *
 * Pure Kotlin, allocation-free per character. Like the [TextMeasurer] it wraps, an instance is meant to be
 * used from one thread at a time; internal scratch buffers are reused between calls (a concurrent call
 * falls back to private buffers instead of corrupting them).
 */
class Typesetter(private val measurer: TextMeasurer, private val config: LayoutConfig) {

    private val buffers = TypesetBuffers()
    private val busy = AtomicBoolean(false)

    /** Lays out a whole section into pages. Deterministic for the same (content, config, measurer font state). */
    fun layout(content: SectionContent): SectionLayout {
        val pass = runPass(content, retain = true)
        return SectionLayout(content, config, pass.pages!!, pass.advances)
    }

    /** Same page count as layout(content).pageCount but without retaining lines (background page counting). */
    fun countPages(content: SectionContent): Int = runPass(content, retain = false).pageCount

    private fun runPass(content: SectionContent, retain: Boolean): TypesetPass {
        val own = busy.compareAndSet(false, true)
        try {
            val pass = TypesetPass(measurer, config, content, retain, if (own) buffers else TypesetBuffers())
            pass.run()
            return pass
        } finally {
            if (own) {
                buffers.trim()
                busy.set(false)
            }
        }
    }
}

/** Simple float rectangle (engine stays free of android.graphics). */
class RectPx(@JvmField var left: Float, @JvmField var top: Float, @JvmField var right: Float, @JvmField var bottom: Float)

/** Geometry queries on laid-out lines, shared by rendering, selection, TTS highlight and search highlight. */
object LineGeometry {

    /**
     * Writes the left x (content-box coordinates) of every char of line[start, end) into out[i - line.start]
     * and returns the x of the line's right edge. Includes justification expansion. `out` must hold at least
     * (line.end - line.start) entries.
     */
    fun charPositions(layout: SectionLayout, line: LineInfo, out: FloatArray): Float {
        if (line.imageBlock != null) {
            if (line.end > line.start && out.isNotEmpty()) out[0] = line.x
            return line.x + line.imageWidth
        }
        val s = line.start
        return walk(layout, line) { i, x, _ -> out[i - s] = x; false }
    }

    /** Offset of the char under (x, y) on [page] (content-box coordinates), or -1 if nothing is there. */
    fun hitTest(layout: SectionLayout, page: PageInfo, x: Float, y: Float): Int {
        val lines = page.lines
        var best: LineInfo? = null
        var bestDist = Float.MAX_VALUE
        for (idx in lines.indices) {
            val ln = lines[idx]
            if (y >= ln.top && y < ln.bottom) {
                best = ln
                break
            }
            val d = if (y < ln.top) ln.top - y else y - ln.bottom
            if (d <= (ln.bottom - ln.top) * 0.5f && d < bestDist) {
                best = ln
                bestDist = d
            }
        }
        val ln = best ?: return -1
        val img = ln.imageBlock
        if (img != null) return img.start
        if (ln.isRule || ln.end <= ln.start) return -1
        val adv = layout.advances
        var hit = -1
        var lastVisible = -1
        walk(layout, ln) { i, xi, next ->
            if (adv[i] > 0f) lastVisible = i
            if (x < next && (adv[i] > 0f || i == ln.start)) {
                hit = i
                true
            } else {
                false
            }
        }
        if (hit >= 0) return hit
        return if (lastVisible >= 0) lastVisible else minOf(ln.end, adv.size) - 1
    }

    /** Rectangles (one per line) covering offsets [start, end) intersected with [page]. */
    fun rangeRects(layout: SectionLayout, page: PageInfo, start: Int, end: Int): List<RectPx> {
        if (end <= start) return emptyList()
        var out: ArrayList<RectPx>? = null
        val lines = page.lines
        for (idx in lines.indices) {
            val ln = lines[idx]
            val img = ln.imageBlock
            if (img != null) {
                if (img.start < end && img.start + 1 > start) {
                    if (out == null) out = ArrayList()
                    out.add(RectPx(ln.x, ln.top, ln.x + ln.imageWidth, ln.bottom))
                }
                continue
            }
            if (ln.isRule || ln.end <= ln.start) continue
            val a = maxOf(start, ln.start)
            val b = minOf(end, ln.end)
            if (a >= b) continue
            var xa = ln.x
            var xb = Float.NaN
            val right = walk(layout, ln) { i, xi, _ ->
                if (i == a) xa = xi
                if (i == b) {
                    xb = xi
                    true
                } else {
                    false
                }
            }
            if (xb.isNaN()) xb = right
            if (out == null) out = ArrayList()
            out.add(RectPx(xa, ln.top, xb, ln.bottom))
        }
        return out ?: emptyList()
    }

    /** Word boundaries around [offset] for long-press selection: returns packed (start shl 32 | end). */
    fun wordAt(text: String, offset: Int): Long {
        val len = text.length
        if (len == 0) return 0L
        val o = offset.coerceIn(0, len - 1)
        if (!isWordChar(text, o)) {
            var s = o
            var e = o + 1
            val c = text[o]
            if (Character.isHighSurrogate(c) && e < len && Character.isLowSurrogate(text[e])) e++
            else if (Character.isLowSurrogate(c) && s > 0 && Character.isHighSurrogate(text[s - 1])) s--
            return pack(s, e)
        }
        var s = o
        while (s > 0 && isWordChar(text, s - 1)) s--
        var e = o + 1
        while (e < len && isWordChar(text, e)) e++
        return pack(s, e)
    }

    /** Unpacks the start of a [wordAt] result. */
    fun packedStart(packed: Long): Int = (packed ushr 32).toInt()

    /** Unpacks the end of a [wordAt] result. */
    fun packedEnd(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()

    private fun pack(s: Int, e: Int): Long = (s.toLong() shl 32) or e.toLong()

    private fun isWordChar(text: String, i: Int): Boolean {
        val c = text[i]
        if (Character.isLetterOrDigit(c)) return true
        if (Character.isHighSurrogate(c)) {
            return i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) &&
                Character.isLetterOrDigit(Character.toCodePoint(c, text[i + 1]))
        }
        if (Character.isLowSurrogate(c)) {
            return i > 0 && Character.isHighSurrogate(text[i - 1]) &&
                Character.isLetterOrDigit(Character.toCodePoint(text[i - 1], c))
        }
        return when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }

    /**
     * Walks the chars of a text line in order, calling [visit] with (index, left x, x of the next char incl.
     * expansion); stops early when [visit] returns true. Returns the line's right edge (or the x reached).
     * Single source of truth for justification geometry (must mirror TypesetPass.justify).
     */
    private inline fun walk(layout: SectionLayout, line: LineInfo, visit: (Int, Float, Float) -> Boolean): Float {
        val adv = layout.advances
        val t = layout.content.text
        val s = line.start
        val e = minOf(line.end, adv.size, t.length)
        var x = line.x
        if (s >= e) return x
        val ex = line.justifyExtra
        when (line.expandMode) {
            LineInfo.EXPAND_SPACES -> {
                var i = s
                while (i < e && BreakClass.isExpandSpace(t[i])) {
                    val nx = x + adv[i]
                    if (visit(i, x, nx)) return nx
                    x = nx
                    i++
                }
                while (i < e) {
                    var nx = x + adv[i]
                    if (BreakClass.isExpandSpace(t[i])) nx += ex
                    if (visit(i, x, nx)) return nx
                    x = nx
                    i++
                }
            }
            LineInfo.EXPAND_CHARS -> {
                var last = e - 1
                while (last >= s && !(adv[last] > 0f)) last--
                for (i in s until e) {
                    var nx = x + adv[i]
                    if (i < last && adv[i] > 0f) nx += ex
                    if (visit(i, x, nx)) return nx
                    x = nx
                }
            }
            else -> {
                for (i in s until e) {
                    val nx = x + adv[i]
                    if (visit(i, x, nx)) return nx
                    x = nx
                }
            }
        }
        return x
    }
}
