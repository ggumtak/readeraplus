package com.ggumtak.readeraplus.render.pdftext

/** A line of a [PageGlyphs]: chars [start, end) of its text (no '\n') and their union box [l, t, r, b]. */
class LineBox(val start: Int, val end: Int, val box: FloatArray)

/** Selected [text] and one union box [l, t, r, b] per line touched. */
class Selection(val text: String, val boxes: List<FloatArray>)

/** Selection, hit-testing and search over a [PageGlyphs]. */
object PageGlyphsOps {
    /** Each line as (start, end) char range of g.text (end exclusive, '\n' excluded) with its union box [l,t,r,b]. */
    fun lineBoxes(g: PageGlyphs): List<LineBox> {
        val out = ArrayList<LineBox>(g.lineStarts.size)
        for (i in g.lineStarts.indices) {
            val s = g.lineStarts[i]
            var e = if (i + 1 < g.lineStarts.size) g.lineStarts[i + 1] - 1 else g.text.length
            if (e > s && e <= g.text.length && g.text[e - 1] == '\n') e--
            if (s < 0 || e <= s || e > g.text.length) continue
            out.add(LineBox(s, e, union(g, s, e) ?: continue))
        }
        return out
    }

    private fun union(g: PageGlyphs, from: Int, to: Int): FloatArray? {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var any = false
        for (i in from until to) {
            if (g.text[i] == '\n' || i * 4 + 3 >= g.boxes.size) continue
            val o = i * 4
            if (g.boxes[o] < l) l = g.boxes[o]
            if (g.boxes[o + 1] < t) t = g.boxes[o + 1]
            if (g.boxes[o + 2] > r) r = g.boxes[o + 2]
            if (g.boxes[o + 3] > b) b = g.boxes[o + 3]
            any = true
        }
        return if (any) floatArrayOf(l, t, r, b) else null
    }

    /** Index of the char (not '\n') nearest to point (x, y): inside a box wins, else nearest by distance to the box; -1 if none. */
    fun nearestChar(g: PageGlyphs, x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        var bestInside = false
        val n = minOf(g.text.length, g.boxes.size / 4)
        for (i in 0 until n) {
            if (g.text[i] == '\n') continue
            val o = i * 4
            val l = g.boxes[o]
            val t = g.boxes[o + 1]
            val r = g.boxes[o + 2]
            val b = g.boxes[o + 3]
            val dx = if (x < l) l - x else if (x > r) x - r else 0f
            val dy = if (y < t) t - y else if (y > b) y - b else 0f
            val inside = dx == 0f && dy == 0f
            val d = dx * dx + dy * dy
            if (inside) {
                // among overlapping boxes prefer the one whose centre is closest horizontally
                val c = Math.abs((l + r) / 2f - x)
                if (!bestInside || c < bestD) {
                    best = i
                    bestD = c
                    bestInside = true
                }
            } else if (!bestInside && d < bestD) {
                best = i
                bestD = d
            }
        }
        return best
    }

    /**
     * Text from the char nearest (x0,y0) to the one nearest (x1,y1) in reading order (either order of points), trimmed,
     * plus one union box per line touched. Null when no text.
     */
    fun select(g: PageGlyphs, x0: Float, y0: Float, x1: Float, y1: Float): Selection? {
        var a = nearestChar(g, x0, y0)
        var b = nearestChar(g, x1, y1)
        if (a < 0 || b < 0) return null
        if (a > b) {
            val t = a
            a = b
            b = t
        }
        var s = a
        var e = b + 1
        while (s < e && g.text[s].isWhitespace()) s++
        while (e > s && g.text[e - 1].isWhitespace()) e--
        if (s >= e) return null
        return Selection(g.text.substring(s, e), rangeBoxes(g, s, e))
    }

    /** One union box per line for chars [from, to); whitespace-only line pieces contribute nothing. */
    private fun rangeBoxes(g: PageGlyphs, from: Int, to: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var i = from
        while (i < to) {
            var j = i
            while (j < to && g.text[j] != '\n') j++
            var s = i
            var e = j
            while (s < e && g.text[s].isWhitespace()) s++
            while (e > s && g.text[e - 1].isWhitespace()) e--
            if (e > s) union(g, s, e)?.let { out.add(it) }
            i = j + 1
        }
        return out
    }

    /**
     * Every case-insensitive match of [query]; whitespace in the query matches any run of whitespace incl. '\n'
     * (differences in whitespace runs are ignored). Each match gives one union box per line it spans. Empty for a
     * blank query.
     */
    fun search(g: PageGlyphs, query: String): List<List<FloatArray>> {
        val text = g.text
        if (text.isEmpty()) return emptyList()
        val q = StringBuilder()
        var pendingSpace = false
        for (ch in query) {
            if (ch.isWhitespace()) {
                pendingSpace = q.isNotEmpty()
            } else {
                if (pendingSpace) q.append(' ')
                pendingSpace = false
                q.append(Character.toLowerCase(ch))
            }
        }
        if (q.isEmpty()) return emptyList()

        val norm = StringBuilder(text.length)
        val map = IntArray(text.length + 1)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isWhitespace()) {
                if (norm.isNotEmpty() && norm[norm.length - 1] != ' ') {
                    map[norm.length] = i
                    norm.append(' ')
                }
            } else {
                map[norm.length] = i
                norm.append(Character.toLowerCase(ch))
            }
            i++
        }
        val result = ArrayList<List<FloatArray>>()
        val needle = q.toString()
        var from = 0
        while (true) {
            val at = norm.indexOf(needle, from)
            if (at < 0) break
            val start = map[at]
            val end = map[at + needle.length - 1] + 1
            val boxes = rangeBoxes(g, start, end)
            if (boxes.isNotEmpty()) result.add(boxes)
            from = at + needle.length
        }
        return result
    }
}
