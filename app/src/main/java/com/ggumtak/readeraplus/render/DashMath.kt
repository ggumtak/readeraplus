package com.ggumtak.readeraplus.render

/** A shared x=0 dash grid lets adjacent highlight fragments meet without restarting the pattern. */
internal object DashMath {
    fun firstDash(left: Float, period: Float): Float =
        if (period > 0f && period.isFinite() && left.isFinite())
            Math.floor((left / period).toDouble()).toFloat() * period else left
}
