package com.ggumtak.readeraplus.settings

import com.ggumtak.readeraplus.reader.LightCurve
import org.junit.Assert.assertEquals
import org.junit.Test

/** One meaning of the stored brightness (slider position) and the one-time conversion of values saved before. */
class BrightnessEncodingTest {
    @Test fun windowLegacyValueBecomesItsPosition() {
        assertEquals(0.5f, BrightnessEncoding.migrateLegacy(0.25f, devicePath = false), 1e-6f)
        assertEquals(1f, BrightnessEncoding.migrateLegacy(1f, devicePath = false), 0f)
        assertEquals(0f, BrightnessEncoding.migrateLegacy(0f, devicePath = false), 0f)
        assertEquals(0.1f, BrightnessEncoding.migrateLegacy(0.01f, devicePath = false), 1e-6f)
    }

    @Test fun deviceLegacyValueIsAlreadyAPosition() {
        assertEquals(0.25f, BrightnessEncoding.migrateLegacy(0.25f, devicePath = true), 0f)
        assertEquals(0.8f, BrightnessEncoding.migrateLegacy(0.8f, devicePath = true), 0f)
    }

    @Test fun autoSentinelStaysOnBothPaths() {
        for (device in listOf(false, true)) {
            assertEquals(-1f, BrightnessEncoding.migrateLegacy(-1f, device), 0f)
            assertEquals(-1f, BrightnessEncoding.migrateLegacy(-0.5f, device), 0f)
            assertEquals(-1f, BrightnessEncoding.migrateLegacy(Float.NaN, device), 0f)
            assertEquals(-1f, BrightnessEncoding.fromStored(-1f, false, device), 0f)
            assertEquals(-1f, BrightnessEncoding.fromStored(-1f, true, device), 0f)
        }
    }

    @Test fun versionedValueIsNeverConvertedAgain() {
        for (device in listOf(false, true)) {
            assertEquals(0.25f, BrightnessEncoding.fromStored(0.25f, true, device), 0f)
            assertEquals(0.5f, BrightnessEncoding.fromStored(0.5f, true, device), 0f)
        }
        assertEquals(0.5f, BrightnessEncoding.fromStored(0.25f, false, false), 1e-6f)
        assertEquals(0.25f, BrightnessEncoding.fromStored(0.25f, false, true), 0f)
    }

    @Test fun conversionKeepsTheLegacyWindowLight() {
        // The legacy window light was the stored value; the position's light is its square again.
        for (i in 0..100) {
            val old = i / 100f
            val pos = BrightnessEncoding.migrateLegacy(old, devicePath = false)
            assertEquals(old, LightCurve.out(pos), 1e-5f)
        }
    }

    @Test fun lastManualPrefersThePositionKeyThenConvertsTheLegacyOne() {
        assertEquals(0.3f, BrightnessEncoding.lastManual(0.3f, 0.9f, false), 0f)
        assertEquals(0.5f, BrightnessEncoding.lastManual(null, 0.25f, false), 1e-6f)
        assertEquals(0.25f, BrightnessEncoding.lastManual(null, 0.25f, true), 0f)
        assertEquals(0.5f, BrightnessEncoding.lastManual(null, null, false), 0f)
        assertEquals(0.5f, BrightnessEncoding.lastManual(-1f, null, false), 0f)
        assertEquals(1f, BrightnessEncoding.lastManual(7f, null, false), 0f)
    }
}
