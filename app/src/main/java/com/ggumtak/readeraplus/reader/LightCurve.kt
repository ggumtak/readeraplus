package com.ggumtak.readeraplus.reader

/**
 * Slider position ↔ device light (brightness.md §3.1, UI_SPEC §4.2). **Device path only**: the window path keeps
 * `ReaderWindow.applyBrightness(activity, pos)` linear with its 0.01 floor.
 *
 * The light is p²: fine steps at the low end, where night reading lives. Android's own slider is gamma-shaped for the
 * same reason. Pure: unit-tested by `LightCurveTest`.
 */
internal object LightCurve {
    /** Framework int range of Settings.System.SCREEN_BRIGHTNESS. 0 is "off/invalid" to its int→float mapping: never written. */
    const val LEVEL_MIN=1; const val LEVEL_MAX=255

    /** Light 0..1 for slider position [pos] (NaN counts as 0). */
    fun out(pos: Float): Float { val p = clamp01(pos); return p * p }

    /** Slider position for light [out]; the inverse of [out]. */
    fun pos(out: Float): Float = Math.sqrt(clamp01(out).toDouble()).toFloat()

    /** Settings.System level for light [out], always inside [min]..[max] (NaN counts as 0). */
    fun level(out: Float, min: Int=LEVEL_MIN, max: Int=LEVEL_MAX): Int {
        if (max <= min) return min
        return (min + Math.round(clamp01(out) * (max - min))).coerceIn(min, max)
    }

    /** Light 0..1 of a Settings.System [level]. */
    fun fraction(level: Int, min: Int=LEVEL_MIN, max: Int=LEVEL_MAX): Float =
        if (max <= min) 1f else ((level - min).toFloat() / (max - min)).coerceIn(0f, 1f)

    /**
     * A change of the setting that is not our write: not our last value, not within [echoMs] of our write (vendors
     * may quantise or mirror it back), and no write of ours is queued.
     */
    fun isExternal(value: Int, ours: Int, sinceOurWriteMs: Long, queued: Boolean, echoMs: Long=1500L): Boolean =
        ours >= 0 && value >= 0 && value != ours && sinceOurWriteMs >= echoMs && !queued

    /**
     * UI_SPEC §4.3 fix 1: the device still shows the level we last wrote ([last]), give or take a vendor quantisation
     * step. Only then may a restore put the original back; a level the user set meanwhile (observer off: paused,
     * screen off, crash) wins.
     */
    fun stillOurs(current: Int, last: Int): Boolean =
        current >= 0 && last >= 0 && Math.abs(current - last) <= maxOf(2, last / 32)

    private fun clamp01(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
}
