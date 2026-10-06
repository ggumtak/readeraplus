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

    /**
     * A [stored] value written before the encoding marker. A window-path value was linear light: its position is the
     * square root, so the perceived level stays. A device-path value was already a position. Auto (< 0, or not a
     * number) stays the sentinel.
     */
    fun migrateLegacy(stored: Float, devicePath: Boolean): Float =
        if (stored.isNaN() || stored < 0f) -1f
        else if (devicePath) stored
        else LightCurve.pos(stored)

    /**
     * The position for a [stored] value read together with its encoding: [hasVersion] = the marker was present (the
     * value is used as is, never converted twice); otherwise [migrateLegacy] by [devicePath] (the device-control
     * setting stored next to it; false when there is none).
     */
    fun fromStored(stored: Float, hasVersion: Boolean, devicePath: Boolean): Float =
        if (hasVersion) stored else migrateLegacy(stored, devicePath)

    /**
     * The manual position the reader's Ⓐ button returns to: the stored position [pos] (the key written since the
     * marker), else the legacy value [legacy] converted once like [migrateLegacy], else the middle. Auto never counts.
     */
    fun lastManual(pos: Float?, legacy: Float?, devicePath: Boolean): Float {
        val v = pos ?: legacy?.let { migrateLegacy(it, devicePath) }
        return if (v == null || v.isNaN() || v < 0f) 0.5f else v.coerceIn(0f, 1f)
    }
}
