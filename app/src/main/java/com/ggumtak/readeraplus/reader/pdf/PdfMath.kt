package com.ggumtak.readeraplus.reader.pdf

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure geometry and paging rules of the PDF viewer (JVM-tested; no android.*). */
internal object PdfMath {
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 5f

    /** Zoom a double tap jumps to from the fitted page. */
    const val DOUBLE_TAP_ZOOM = 2.5f

    /** Share of the view one zoomed "screen step" moves (the rest stays visible as context). */
    const val STEP_FRACTION = 0.9f

    private const val EDGE_EPSILON = 0.5f

    /** Scale (px per PDF point) that fits a pageW×pageH page entirely inside viewW×viewH. 0 when any size <= 0. */
    fun fitScale(pageW: Int, pageH: Int, viewW: Int, viewH: Int): Float {
        if (pageW <= 0 || pageH <= 0 || viewW <= 0 || viewH <= 0) return 0f
        return min(viewW.toFloat() / pageW, viewH.toFloat() / pageH)
    }

    /** [z] clamped to MIN_ZOOM..MAX_ZOOM (NaN → MIN_ZOOM). */
    fun clampZoom(z: Float): Float {
        if (z.isNaN()) return MIN_ZOOM
        return if (z < MIN_ZOOM) MIN_ZOOM else if (z > MAX_ZOOM) MAX_ZOOM else z
    }

    /**
     * Pan offset along one axis: the position of the content's leading edge in view coordinates, for content of
     * [content] px in a view of [view] px. Content not larger than the view is centred ((view - content) / 2);
     * otherwise the offset is clamped to view - content .. 0 (no empty space at either edge).
     */
    fun clampOffset(offset: Float, content: Float, view: Float): Float {
        if (content <= view) return (view - content) / 2f
        val o = if (offset.isNaN()) 0f else offset
        val lo = view - content
        return if (o < lo) lo else if (o > 0f) 0f else o
    }

    /**
     * The offset after zooming from [oldZoom] to [newZoom] around the focus point [focus] (view px) on an axis:
     * the content point under the focus stays under it. [offset] is the current offset; result is NOT clamped.
     * Formula: focus - (focus - offset) * newZoom / oldZoom. oldZoom <= 0 → returns offset.
     */
    fun zoomOffset(offset: Float, focus: Float, oldZoom: Float, newZoom: Float): Float {
        if (oldZoom <= 0f) return offset
        return focus - (focus - offset) * newZoom / oldZoom
    }

    /**
     * One reading step on the vertical axis of a zoomed page: [dir] +1 = forward (down), -1 = back (up).
     * Returns the new clamped offset when the content can still move that way by more than 0.5 px, moving by
     * view * STEP_FRACTION (clamped to the edge), or Float.NaN when the content is already at that edge (or fits
     * the view) — the caller then turns the page.
     */
    fun stepOffset(offset: Float, content: Float, view: Float, dir: Int): Float {
        if (content <= view || dir == 0) return Float.NaN
        val cur = clampOffset(offset, content, view)
        val lowest = view - content
        val step = view * STEP_FRACTION
        return if (dir > 0) {
            if (cur - lowest <= EDGE_EPSILON) Float.NaN else max(cur - step, lowest)
        } else {
            if (0f - cur <= EDGE_EPSILON) Float.NaN else min(cur + step, 0f)
        }
    }

    /** Tap zone by x across a view [width] px wide: left third -1 (previous), right third +1 (next), middle 0 (menu). width <= 0 → 0. */
    fun tapZone(x: Float, width: Int): Int {
        if (width <= 0) return 0
        val x3 = x * 3f
        return if (x3 < width) -1 else if (x3 >= width * 2f) 1 else 0
    }

    /** [page] clamped to 0..count-1 (0 when count <= 0). */
    fun clampPage(page: Int, count: Int): Int {
        if (count <= 0) return 0
        return if (page < 0) 0 else if (page > count - 1) count - 1 else page
    }

    /** Reading progress of 0-based [page] of [count] pages, (page + 1) / count, in 0..1 (0 when count <= 0). */
    fun progress(page: Int, count: Int): Float {
        if (count <= 0) return 0f
        return (clampPage(page, count) + 1).toFloat() / count
    }

    /**
     * Pixel size (width, height) of the bitmap for a page of pageW×pageH points drawn at [scale], each side
     * ceil()'d and at least 1, then shrunk uniformly (keeping aspect) so width*height <= [maxPixels].
     * Returned packed: (width.toLong() shl 32) or height.toLong(). Use [packedW] and [packedH] to unpack.
     */
    fun renderSize(pageW: Int, pageH: Int, scale: Float, maxPixels: Int): Long {
        var w = sideOf(pageW, scale)
        var h = sideOf(pageH, scale)
        if (w * h > maxPixels) {
            val f = sqrt(maxPixels.toDouble() / (w.toDouble() * h.toDouble()))
            w = max(1L, floor(w * f).toLong())
            h = max(1L, floor(h * f).toLong())
            // Guard against floating-point rounding: shave the longer side until the cap holds.
            while (w * h > maxPixels && (w > 1L || h > 1L)) {
                if (w >= h && w > 1L) w-- else h--
            }
        }
        return (w shl 32) or h
    }

    private fun sideOf(points: Int, scale: Float): Long {
        if (points <= 0 || scale.isNaN() || scale <= 0f) return 1L
        val v = ceil(points.toDouble() * scale)
        return if (v < 1.0) 1L else if (v > Int.MAX_VALUE.toDouble()) Int.MAX_VALUE.toLong() else v.toLong()
    }

    /** Width part of a value returned by [renderSize]. */
    fun packedW(p: Long): Int = (p ushr 32).toInt()

    /** Height part of a value returned by [renderSize]. */
    fun packedH(p: Long): Int = (p and 0xFFFFFFFFL).toInt()

    /** "12 / 340" for 0-based [page] (1-based in the text). count <= 0 → "0 / 0". */
    fun pageLabel(page: Int, count: Int): String {
        if (count <= 0) return "0 / 0"
        return "${clampPage(page, count) + 1} / $count"
    }
}
