package com.ggumtak.readeraplus.reader

/** The status slots' clock without a Calendar (U §5.3): pure, allocation-free, unit-tested. */
internal object StatusClock {
    private const val MINUTE_MS = 60_000L
    private const val DAY_MINUTES = 1440L

    /** Minutes since local midnight (0..1439) at [nowMs] (epoch ms) in a zone [offsetMs] ahead of UTC. */
    fun minuteOfDay(nowMs: Long, offsetMs: Int): Int =
        Math.floorMod(Math.floorDiv(nowMs + offsetMs, MINUTE_MS), DAY_MINUTES).toInt()
}
