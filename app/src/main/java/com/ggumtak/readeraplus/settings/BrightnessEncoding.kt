package com.ggumtak.readeraplus.settings

import com.ggumtak.readeraplus.reader.LightCurve

/**
 * What [AppSettings.brightness] means in storage. Since [VERSION] 2 it is always the perceptual slider position: both
 * light paths output [LightCurve.out] of it (the window path through [LightCurve.windowLevel]), so toggling the device
 * control no longer changes the light for the same slider position. Before, the window path stored (and applied) the
 * linear output while the device path stored the position.
 *
 * Settings saved before the marker ([KEY_VERSION] absent) are converted once, when loaded (loading never writes: the
 * first `Settings.saveApp` stores the converted value with the marker). The auto sentinel (< 0) is never converted.
 * A backup carries the marker with the value; one without it is legacy (`SettingsJson`). Pure: unit-tested by
 * `BrightnessEncodingTest`.
 */
object BrightnessEncoding {
    /** Prefs / backup key of the encoding version; no setting of its own, stored with `a.brightness`. */
    const val KEY_VERSION = "a.brightnessV"

    /** The version that stores the slider position. */
    const val VERSION = 2

    /** Prefs key of the manual position the reader's Ⓐ button returns to (a slider position). */
    const val KEY_LAST_POS = "reader.lastBrightnessPos"

    /** Before the marker: the window path stored linear light here, the device path a position. */
    const val KEY_LAST_LEGACY = "reader.lastBrightness"

    /**
     * A [stored] value written before the encoding marker. A window-path value was linear light, shown as
     * `max(floor, value)`: its position is [LightCurve.windowPos] of it, so the perceived level stays (values at or
     * under the floor were the floor: position 0). A device-path value was already a position. Auto (< 0, or not a
     * number) stays the sentinel.
     */
    fun migrateLegacy(stored: Float, devicePath: Boolean): Float =
        if (stored.isNaN() || stored < 0f) -1f
        else if (devicePath) stored
        else LightCurve.windowPos(stored)

    /** The marker's [version] (null = absent) is this encoding or a newer one this build does not know. */
    fun isCurrent(version: Int?): Boolean = version != null && version >= VERSION

    /**
     * The position for a [stored] value read together with its encoding [version] (null = no marker). With the
     * marker (this version, or a higher unknown one: read as the current meaning) the value is used as is, never
     * converted twice, only made safe: not a number → auto (-1), above 1 → 1, below 0 → auto. Without it (or a lower
     * version) [migrateLegacy] by [devicePath] (the device-control setting stored next to it).
     */
    fun fromStored(stored: Float, version: Int?, devicePath: Boolean): Float =
        if (!isCurrent(version)) migrateLegacy(stored, devicePath)
        else if (stored.isNaN() || stored < 0f) -1f
        else if (stored > 1f) 1f
        else stored

    /**
     * The value to store under [KEY_LAST_POS] when the legacy key [legacy] is still there: the stored position [pos]
     * wins, else the legacy value converted like [migrateLegacy] by [devicePath] (the flag in effect when it is
     * converted). Null = nothing to store (no usable legacy value, or [pos] already there).
     */
    fun migrateLastManual(pos: Float?, legacy: Float?, devicePath: Boolean): Float? {
        if (pos != null || legacy == null) return null
        val v = migrateLegacy(legacy, devicePath)
        return if (v < 0f) null else v.coerceIn(0f, 1f)
    }

    /** The manual position Ⓐ returns to: the stored position [pos], else the middle. Auto never counts. */
    fun lastManual(pos: Float?): Float =
        if (pos == null || pos.isNaN() || pos < 0f) 0.5f else pos.coerceIn(0f, 1f)
}
