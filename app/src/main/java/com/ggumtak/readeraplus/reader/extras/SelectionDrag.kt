package com.ggumtak.readeraplus.reader.extras

/** Pure helpers of a selection drag (unit-tested). */
internal object SelectionDrag {
    /**
     * [off] clamped to a char of the span [[start], [end]), or -1 when the span holds no char: a page of an empty
     * section is `PageInfo(0, 0)`, where `coerceIn(start, end - 1)` would throw in the middle of a touch event. An
     * `Int`, not `Int?`, so a drag's MOVE boxes nothing.
     */
    fun clampOffset(off: Int, start: Int, end: Int): Int = if (end <= start) -1 else off.coerceIn(start, end - 1)
}

/**
 * The text area's top and the edge zone's depth (view px) for the edge-dwell check, computed once per drag instead of
 * on every MOVE (the page geometry measures the font). They cannot change under a held finger without a page change or
 * a relayout, and those [invalidate] them; the page view's size is checked as well, as a last guard against a stale
 * value.
 */
internal class DragGeometryMemo {
    private var valid = false
    private var width = 0
    private var height = 0

    var contentTop = 0
        private set
    var zonePx = 0f
        private set

    /** True when the remembered values were computed for a page view of [w] × [h] and not invalidated since. */
    fun matches(w: Int, h: Int): Boolean = valid && w == width && h == height

    fun store(w: Int, h: Int, top: Int, zone: Float) {
        width = w
        height = h
        contentTop = top
        zonePx = zone
        valid = true
    }

    /** A drag starts or ends, or the page / layout changed: the next check computes the geometry again. */
    fun invalidate() {
        valid = false
    }
}
