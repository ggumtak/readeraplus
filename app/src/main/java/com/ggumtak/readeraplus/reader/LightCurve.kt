package com.ggumtak.readeraplus.reader

/**
 * Slider position ↔ light (brightness.md §3.1, UI_SPEC §4.2). One meaning on both paths: `AppSettings.brightness` is
 * the position and the light is [out] of it, whether it goes to the device ([DeviceLight.set]) or to the window
 * ([windowLevel] in `ReaderWindow.applyBrightness`).
 *
 * The device light is p²: fine steps at the low end, where night reading lives (the window one is that, lifted onto
 * a floor). Android's own slider is gamma-shaped for the same reason. Pure: unit-tested by `LightCurveTest`.
 */
internal object LightCurve {
    /** Framework int range of Settings.System.SCREEN_BRIGHTNESS. 0 is "off/invalid" to its int→float mapping: never written. */
    const val LEVEL_MIN=1; const val LEVEL_MAX=255

    /** Lowest window brightness ([windowLevel]): the screen never goes fully dark. */
    const val WINDOW_FLOOR=0.01f

    /** Light 0..1 for slider position [pos] (NaN counts as 0). */
    fun out(pos: Float): Float { val p = clamp01(pos); return p * p }

    /**
     * Window screenBrightness for slider position [pos]: [out] lifted onto [floor]..1 (`floor + (1 - floor) * pos²`),
     * so every position is a distinct light and the screen never goes fully dark.
     */
    fun windowLevel(pos: Float, floor: Float=WINDOW_FLOOR): Float = floor + (1f - floor) * out(pos)

    /** Slider position for a window light [out]; the inverse of [windowLevel] (a light at or under [floor] is position 0). */
    fun windowPos(out: Float, floor: Float=WINDOW_FLOOR): Float {
        if (floor >= 1f) return 0f
        return Math.sqrt(Math.max(0f, (clamp01(out) - floor) / (1f - floor)).toDouble()).toFloat()
    }

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
