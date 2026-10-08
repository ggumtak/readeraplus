package com.ggumtak.readeraplus.reader.pdf

/**
 * Turning a hand-drawn loop (the "lasso", in page points) into a text selection (pure; JVM-tested).
 *
 * The platform selects text as a stream between two character positions, so the loop is reduced to where that
 * stream starts and stops: among the page's text lines (rectangles, in reading order), the first and the last whose
 * middle crosses the loop, at the first / last point of that middle inside the loop. A loop around a word gives
 * that word; a loop around a block gives the block from its first to its last character.
 */
internal object PdfLasso {
    /** A loop smaller than this (page points, both sides) is a tap, not a selection. */
    const val MIN_SIZE = 6f

    /** Distance between the points tried along a line's middle (page points). */
    private const val STEP = 1.5f

    /** Whether the loop of [n] points in [poly] (x, y pairs) is big enough to select something. */
    fun isSelection(poly: FloatArray, n: Int): Boolean {
        if (n < 3) return false
        val b = InkMath.bounds(poly, n)
        return b[2] - b[0] >= MIN_SIZE || b[3] - b[1] >= MIN_SIZE
    }

    /**
     * Start and stop of the selection for the loop [poly] ([n] points) over the text [lines]: [lineCount] rectangles
     * as left, top, right, bottom quadruples, in reading order. Returns [x0, y0, x1, y1] (page points, on the middle
     * of the first / last line crossed), or null when the loop crosses no line.
     */
    fun ends(poly: FloatArray, n: Int, lines: FloatArray, lineCount: Int): FloatArray? {
        if (n < 3 || lineCount <= 0) return null
        var first = -1
        var firstX = 0f
        var last = -1
        var lastX = 0f
        for (i in 0 until lineCount) {
            val l = lines[i * 4]
            val r = lines[i * 4 + 2]
            val cy = (lines[i * 4 + 1] + lines[i * 4 + 3]) / 2f
            if (r <= l) continue
            val inFrom = firstInside(poly, n, l, r, cy)
            if (inFrom.isNaN()) continue
            if (first < 0) {
                first = i
                firstX = inFrom
            }
            last = i
            lastX = lastInside(poly, n, l, r, cy)
        }
        if (first < 0) return null
        val y0 = (lines[first * 4 + 1] + lines[first * 4 + 3]) / 2f
        val y1 = (lines[last * 4 + 1] + lines[last * 4 + 3]) / 2f
        return floatArrayOf(firstX, y0, lastX, y1)
    }

    /** Indices of the [lines] (as in [ends]) whose middle crosses the loop, in order. */
    fun crossed(poly: FloatArray, n: Int, lines: FloatArray, lineCount: Int): IntArray {
        if (n < 3 || lineCount <= 0) return IntArray(0)
        val out = IntArray(lineCount)
        var k = 0
        for (i in 0 until lineCount) {
            val l = lines[i * 4]
            val r = lines[i * 4 + 2]
            val cy = (lines[i * 4 + 1] + lines[i * 4 + 3]) / 2f
            if (r > l && !firstInside(poly, n, l, r, cy).isNaN()) out[k++] = i
        }
        return out.copyOf(k)
    }

    /** The leftmost x in [l, r] on height [y] inside the loop (sampled), or NaN. */
    private fun firstInside(poly: FloatArray, n: Int, l: Float, r: Float, y: Float): Float {
        var x = l + STEP / 2f
        while (x < r) {
            if (InkMath.contains(poly, n, x, y)) return x
            x += STEP
        }
        return Float.NaN
    }

    /** The rightmost x in [l, r] on height [y] inside the loop (sampled), or NaN. */
    private fun lastInside(poly: FloatArray, n: Int, l: Float, r: Float, y: Float): Float {
        var x = r - STEP / 2f
        while (x > l) {
            if (InkMath.contains(poly, n, x, y)) return x
            x -= STEP
        }
        return Float.NaN
    }
}
