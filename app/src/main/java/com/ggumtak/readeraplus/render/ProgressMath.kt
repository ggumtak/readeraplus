package com.ggumtak.readeraplus.render

/** Shared pixel geometry for the renderer and status-change detection. */
internal object ProgressMath {
    /**
     * Line row of a [lane] whose bottom is [viewH] (the page height minus [StatusFit.edgePx]): the dot sits at the
     * top of the lane, away from the screen edge (a small lane is the centre, as before).
     */
    fun yc(viewH: Int, lane: Float, density: Float): Int =
        minOf(viewH - Math.round(lane) + Math.round(rDot(lane, density)) + 1, viewH - Math.round(lane / 2f))
    fun rDot(lane: Float, density: Float): Float = minOf(Math.round(3f * density).toFloat(), (lane / 2f - 1f).coerceAtLeast(0f))
    fun rCap(lane: Float, density: Float): Float = minOf(Math.round(1.5f * density).toFloat(), rDot(lane, density) / 2f)
    fun x0(viewW: Int, density: Float): Int = minOf(Math.round(12f * density), viewW / 2)
    fun x1(viewW: Int, density: Float): Int = viewW - x0(viewW, density)
    fun trackPx(viewW: Int, lane: Float, density: Float): Int =
        (x1(viewW, density) - x0(viewW, density) - 2 * Math.round(rCap(lane, density) + rDot(lane, density))).coerceAtLeast(0)
    fun dotX(f: Float, viewW: Int, lane: Float, density: Float): Float =
        Math.round(x0(viewW, density) + rCap(lane, density) + rDot(lane, density) + f.coerceIn(0f, 1f) * trackPx(viewW, lane, density)) + 0.5f
}
