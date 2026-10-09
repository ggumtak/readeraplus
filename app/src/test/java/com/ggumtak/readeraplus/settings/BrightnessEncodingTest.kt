package com.ggumtak.readeraplus.settings

import com.ggumtak.readeraplus.reader.LightCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One meaning of the stored brightness (slider position) and the one-time conversion of values saved before. */
class BrightnessEncodingTest {
    @Test fun windowLegacyValueBecomesItsPosition() {
        // The legacy light was max(0.01, v) linear; the position's window light is the same again.
        assertEquals(1f, BrightnessEncoding.migrateLegacy(1f, devicePath = false), 1e-6f)
        assertEquals(0f, BrightnessEncoding.migrateLegacy(0f, devicePath = false), 0f)
        assertEquals(0f, BrightnessEncoding.migrateLegacy(0.01f, devicePath = false), 0f)
        assertEquals(0f, BrightnessEncoding.migrateLegacy(0.005f, devicePath = false), 0f)
        val p = BrightnessEncoding.migrateLegacy(0.25f, devicePath = false)
        assertEquals(0.25f, LightCurve.windowLevel(p), 1e-5f)
        assertEquals(Math.sqrt((0.25 - 0.01) / 0.99).toFloat(), p, 1e-6f)
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
            assertEquals(-1f, BrightnessEncoding.fromStored(-1f, null, device), 0f)
            assertEquals(-1f, BrightnessEncoding.fromStored(-1f, BrightnessEncoding.VERSION, device), 0f)
        }
    }

    @Test fun versionedValueIsNeverConvertedAgain() {
        for (device in listOf(false, true)) {
            assertEquals(0.25f, BrightnessEncoding.fromStored(0.25f, 2, device), 0f)
            assertEquals(0.5f, BrightnessEncoding.fromStored(0.5f, 2, device), 0f)
        }
        assertEquals(BrightnessEncoding.migrateLegacy(0.25f, false), BrightnessEncoding.fromStored(0.25f, null, false), 0f)
        assertEquals(0.25f, BrightnessEncoding.fromStored(0.25f, null, true), 0f)
    }

    @Test fun markedValueIsSanitised() {
        assertEquals(-1f, BrightnessEncoding.fromStored(Float.NaN, 2, false), 0f)
        assertEquals(1f, BrightnessEncoding.fromStored(7f, 2, false), 0f)
        assertEquals(1f, BrightnessEncoding.fromStored(Float.POSITIVE_INFINITY, 2, true), 0f)
        assertEquals(-1f, BrightnessEncoding.fromStored(Float.NEGATIVE_INFINITY, 2, true), 0f)
        assertEquals(-1f, BrightnessEncoding.fromStored(-0.3f, 2, false), 0f)
        assertEquals(0f, BrightnessEncoding.fromStored(0f, 2, false), 0f)
        assertEquals(1f, BrightnessEncoding.fromStored(1f, 2, false), 0f)
    }

    @Test fun theVersionValueDecidesNotItsPresence() {
        assertFalse(BrightnessEncoding.isCurrent(null))
        assertFalse(BrightnessEncoding.isCurrent(0))
        assertFalse(BrightnessEncoding.isCurrent(1))
        assertTrue(BrightnessEncoding.isCurrent(2))
        // A version below this one is legacy, not "marked".
        assertEquals(BrightnessEncoding.migrateLegacy(0.25f, false), BrightnessEncoding.fromStored(0.25f, 1, false), 0f)
        assertEquals(BrightnessEncoding.migrateLegacy(0.25f, false), BrightnessEncoding.fromStored(0.25f, 0, false), 0f)
        // A newer one this build does not know is read as the current meaning (as is, sanitised).
        assertEquals(0.25f, BrightnessEncoding.fromStored(0.25f, 3, false), 0f)
        assertEquals(1f, BrightnessEncoding.fromStored(9f, 99, false), 0f)
        assertEquals(-1f, BrightnessEncoding.fromStored(Float.NaN, 3, true), 0f)
    }

    @Test fun conversionKeepsTheLegacyWindowLight() {
        for (i in 0..100) {
            val old = i / 100f
            val pos = BrightnessEncoding.migrateLegacy(old, devicePath = false)
            assertEquals(Math.max(0.01f, old), LightCurve.windowLevel(pos), 1e-5f)
        }
    }

    @Test fun lastManualReadsThePositionOrTheMiddle() {
        assertEquals(0.3f, BrightnessEncoding.lastManual(0.3f), 0f)
        assertEquals(0.5f, BrightnessEncoding.lastManual(null), 0f)
        assertEquals(0.5f, BrightnessEncoding.lastManual(-1f), 0f)
        assertEquals(0.5f, BrightnessEncoding.lastManual(Float.NaN), 0f)
        assertEquals(1f, BrightnessEncoding.lastManual(7f), 0f)
    }

    @Test fun legacyLastManualIsConvertedOnceByTheFlagOfThatTime() {
        // Nothing to store without the legacy key, or when the position key is already there.
        assertNull(BrightnessEncoding.migrateLastManual(null, null, false))
        assertNull(BrightnessEncoding.migrateLastManual(0.3f, 0.9f, false))
        assertEquals(BrightnessEncoding.migrateLegacy(0.25f, false), BrightnessEncoding.migrateLastManual(null, 0.25f, false)!!, 0f)
        assertEquals(0.25f, BrightnessEncoding.migrateLastManual(null, 0.25f, true)!!, 0f)
        // Auto or garbage is not a position.
        assertNull(BrightnessEncoding.migrateLastManual(null, -1f, false))
        assertNull(BrightnessEncoding.migrateLastManual(null, Float.NaN, true))
        assertEquals(1f, BrightnessEncoding.migrateLastManual(null, 5f, true)!!, 0f)
    }
}
