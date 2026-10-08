package com.ggumtak.readeraplus.reader.pdf

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shapes of pen strokes (pure; JVM-tested): how pen pressure becomes width, how raw pressure is steadied, how
 * points are thinned without losing pressure, and the filled outline of a variable-width stroke.
 *
 * A pressure stroke is drawn as one filled outline: both sides offset from the centre line by half the width at each
 * point (normals from the neighbours, so the edge turns smoothly), joined by round caps. Drawing the outline once
 * (instead of a segment per point) has no overlaps, so it stays clean with any colour.
 */
internal object InkShape {
    /** Width at zero pressure, as a share of the full width: a light touch still leaves a line. */
    const val MIN_WIDTH_RATIO = 0.3f
    /** Below 1: the width rises quickly at light pressure and evens out at firm pressure (feels like a pen). */
    private const val PRESSURE_GAMMA = 0.75
    /** How much of each new pressure reading counts: steadies the jittery raw values without lag in the line. */
    const val PRESSURE_SMOOTHING = 0.35f
    /** Points of a round cap (half circle). */
    private const val CAP_POINTS = 6

    /** Stroke width at pressure [p] (0..1, clamped) for a tool of full width [width]. */
    fun widthAt(width: Float, p: Float): Float {
        val q = if (p.isNaN()) 1f else p.coerceIn(0f, 1f)
        return width * (MIN_WIDTH_RATIO + (1f - MIN_WIDTH_RATIO) * q.toDouble().pow(PRESSURE_GAMMA).toFloat())
    }

    /** The next steadied pressure after [previous] given a raw reading [raw] (first point: pass NaN as previous). */
    fun steady(previous: Float, raw: Float): Float {
        val r = if (raw.isNaN()) 1f else raw.coerceIn(0f, 1f)
        return if (previous.isNaN()) r else previous + (r - previous) * PRESSURE_SMOOTHING
    }

    /**
     * The first [count] points of [points] (x, y pairs) and their [pressures] (null = none) without points closer than
     * [minDist] to the last kept one; the first and last are always kept. Returns (points, pressures or null).
     */
    fun simplify(points: FloatArray, pressures: FloatArray?, count: Int, minDist: Float): Pair<FloatArray, FloatArray?> {
        val n = count.coerceAtMost(points.size / 2).coerceAtLeast(0)
        if (n <= 2) {
            return points.copyOf(n * 2) to pressures?.copyOf(n)
        }
        val keep = IntArray(n)
        var k = 0
        keep[k++] = 0
        val min2 = minDist * minDist
        var lx = points[0]
        var ly = points[1]
        for (i in 1 until n - 1) {
            val dx = points[i * 2] - lx
            val dy = points[i * 2 + 1] - ly
            if (dx * dx + dy * dy >= min2) {
                keep[k++] = i
                lx = points[i * 2]
                ly = points[i * 2 + 1]
            }
        }
        keep[k++] = n - 1
        val outP = FloatArray(k * 2)
        val outQ = if (pressures != null) FloatArray(k) else null
        for (j in 0 until k) {
            val i = keep[j]
            outP[j * 2] = points[i * 2]
            outP[j * 2 + 1] = points[i * 2 + 1]
            if (outQ != null) outQ[j] = pressures!![i]
        }
        return outP to outQ
    }

    /**
     * Closed outline (x, y pairs) of a stroke through [points] with full width [width] and per-point [pressures]:
     * left side forward, round end cap, right side back, round start cap. One point gives a circle.
     */
    fun outline(points: FloatArray, pressures: FloatArray, width: Float): FloatArray {
        val n = minOf(points.size / 2, pressures.size)
        if (n <= 0) return FloatArray(0)
        val half = FloatArray(n)
        for (i in 0 until n) {
            // Averaged with the neighbours: no width steps from one reading to the next.
            val a = pressures[maxOf(0, i - 1)]
            val b = pressures[i]
            val c = pressures[minOf(n - 1, i + 1)]
            half[i] = widthAt(width, (a + 2f * b + c) / 4f) / 2f
        }
        if (n == 1 || isDot(points, n)) return circle(points[0], points[1], half.max(), CAP_POINTS * 2)
        val out = FloatArray((n * 2 + CAP_POINTS * 2) * 2)
        var o = 0
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        for (i in 0 until n) {
            // Tangent from the neighbours (smooth sides); the last valid normal carries over duplicate points.
            val i0 = maxOf(0, i - 1)
            val i1 = minOf(n - 1, i + 1)
            var tx = points[i1 * 2] - points[i0 * 2]
            var ty = points[i1 * 2 + 1] - points[i0 * 2 + 1]
            val len = sqrt(tx * tx + ty * ty)
            if (len < 1e-6f) {
                if (i > 0) {
                    nx[i] = nx[i - 1]
                    ny[i] = ny[i - 1]
                } else {
                    nx[i] = 0f
                    ny[i] = -1f
                }
                continue
            }
            tx /= len
            ty /= len
            nx[i] = -ty
            ny[i] = tx
        }
        // Left side, start to end.
        for (i in 0 until n) {
            out[o++] = points[i * 2] + nx[i] * half[i]
            out[o++] = points[i * 2 + 1] + ny[i] * half[i]
        }
        o = cap(out, o, points[(n - 1) * 2], points[(n - 1) * 2 + 1], nx[n - 1], ny[n - 1], half[n - 1])
        // Right side, end to start.
        for (i in n - 1 downTo 0) {
            out[o++] = points[i * 2] - nx[i] * half[i]
            out[o++] = points[i * 2 + 1] - ny[i] * half[i]
        }
        o = cap(out, o, points[0], points[1], -nx[0], -ny[0], half[0])
        return if (o == out.size) out else out.copyOf(o)
    }

    /** Points of a half circle around (cx, cy) from the side (nx, ny) round to the opposite side, ends excluded. */
    private fun cap(out: FloatArray, start: Int, cx: Float, cy: Float, nx: Float, ny: Float, r: Float): Int {
        var o = start
        val a0 = atan2(ny.toDouble(), nx.toDouble())
        for (k in 1..CAP_POINTS) {
            // From +normal clockwise through the forward direction to -normal.
            val a = a0 - PI * k / (CAP_POINTS + 1)
            out[o++] = cx + (cos(a) * r).toFloat()
            out[o++] = cy + (sin(a) * r).toFloat()
        }
        return o
    }

    private fun circle(cx: Float, cy: Float, r: Float, steps: Int): FloatArray {
        val out = FloatArray(steps * 2)
        for (k in 0 until steps) {
            val a = 2.0 * PI * k / steps
            out[k * 2] = cx + (cos(a) * r).toFloat()
            out[k * 2 + 1] = cy + (sin(a) * r).toFloat()
        }
        return out
    }

    /** All points (practically) on the first one. */
    private fun isDot(points: FloatArray, n: Int): Boolean {
        val x = points[0]
        val y = points[1]
        for (i in 1 until n) {
            if (abs(points[i * 2] - x) > 1e-3f || abs(points[i * 2 + 1] - y) > 1e-3f) return false
        }
        return true
    }
}
