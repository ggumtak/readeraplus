package com.ggumtak.readeraplus.reader.extras

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A stepper's number typed in instead of stepped (the 톱니 popup, user 2026-10-10: "숫자 클릭해서 입력해서 바꿀 수
 * 있게"): the number is typed in the units the row shows ("160" for 160 %, "−4" for a margin, "+2" steps for 굵기,
 * "17.5" sp), so [toShown] / [fromShown] convert. A typed value is taken as it is (not snapped to the step); − / + then
 * step from the grid again.
 */
internal class StepperEntry(
    val signed: Boolean = false,
    /** Digits after the point the pad takes (0: whole numbers). */
    val decimals: Int = 0,
    /** Digits before the point. */
    val digits: Int = 3,
    val toShown: (Float) -> Double = { it.toDouble() },
    val fromShown: (Double) -> Float = { it.toFloat() },
) {
    /**
     * The value [typed] sets, rounded to [decimals] in shown units, or null when it is outside [lo]..[hi] (the
     * dialog then says so and stays open).
     */
    fun valueFor(typed: Double, lo: Float, hi: Float): Float? {
        if (!typed.isFinite()) return null
        val scale = 10.0.pow(decimals)
        val v = fromShown((typed * scale).roundToLong() / scale)
        if (!v.isFinite()) return null
        val clean = (v * 1000f).let { Math.round(it) } / 1000f
        if (clean < lo - EPS || clean > hi + EPS) return null
        return clean.coerceIn(lo, hi)
    }

    /** [v] as typed: "160", "−4", "17.5" (no unit, no "+"). */
    fun shown(v: Float): String {
        val s = toShown(v)
        val scale = 10.0.pow(decimals)
        val r = (abs(s) * scale).roundToLong()
        val whole = r / scale.toLong()
        val frac = r % scale.toLong()
        val body = if (decimals == 0 || frac == 0L) whole.toString() else "$whole." + frac.toString().padStart(decimals, '0').trimEnd('0')
        return if (s < 0 && r != 0L) "−$body" else body
    }

    /** "100~300": the range in shown units, for the pad's hint and its message. */
    fun range(lo: Float, hi: Float): String = "${shown(lo)}~${shown(hi)}"

    private companion object {
        const val EPS = 1e-3f
    }
}
