package com.ggumtak.readeraplus.reader

import org.junit.Assert.*
import org.junit.Test

class LightCurveTest {
    @Test fun outAndPosAreMonotonicWithFixedEnds() {
        assertEquals(0f, LightCurve.out(0f), 0f)
        assertEquals(1f, LightCurve.out(1f), 0f)
        assertEquals(0f, LightCurve.pos(0f), 0f)
        assertEquals(1f, LightCurve.pos(1f), 0f)
        var prevOut = -1f
        var prevPos = -1f
        for (i in 0..1000) {
            val x = i / 1000f
            val o = LightCurve.out(x)
            val p = LightCurve.pos(x)
            assertTrue(o >= prevOut)
            assertTrue(p >= prevPos)
            prevOut = o
            prevPos = p
        }
        assertEquals(0.25f, LightCurve.out(0.5f), 1e-6f)
    }

    @Test fun outsideAndNaNInputsAreClamped() {
        assertEquals(0f, LightCurve.out(-3f), 0f)
        assertEquals(1f, LightCurve.out(7f), 0f)
        assertEquals(0f, LightCurve.out(Float.NaN), 0f)
        assertEquals(0f, LightCurve.pos(Float.NaN), 0f)
    }

    @Test fun levelStaysInsideTheFrameworkRange() {
        val inputs = floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, -1f, 0f, 1e-6f, 0.5f, 0.999f, 1f, 2f, Float.POSITIVE_INFINITY)
        for (x in inputs) {
            val l = LightCurve.level(x)
            assertTrue("level($x) = $l", l in LightCurve.LEVEL_MIN..LightCurve.LEVEL_MAX)
        }
        assertEquals(1, LightCurve.level(0f))
        assertEquals(255, LightCurve.level(1f))
        assertEquals(1, LightCurve.level(Float.NaN))
        assertEquals(5, LightCurve.level(0.5f, 5, 5))
    }

    @Test fun fractionMapsTheRangeAndGuardsAnEmptyOne() {
        assertEquals(0f, LightCurve.fraction(1), 0f)
        assertEquals(1f, LightCurve.fraction(255), 0f)
        assertEquals(0f, LightCurve.fraction(0), 0f)
        assertEquals(1f, LightCurve.fraction(300), 0f)
        assertEquals(1f, LightCurve.fraction(10, 5, 5), 0f)
    }

    /** adoptDeviceLight never writes back: every level survives level → fraction → pos → out → level. */
    @Test fun everyLevelRoundTripsExactly() {
        for (v in LightCurve.LEVEL_MIN..LightCurve.LEVEL_MAX) {
            assertEquals(v, LightCurve.level(LightCurve.out(LightCurve.pos(LightCurve.fraction(v)))))
        }
    }

    @Test fun isExternalOnlyForAForeignSettledValue() {
        assertFalse(LightCurve.isExternal(100, 100, 5000L, false))      // our value
        assertFalse(LightCurve.isExternal(90, 100, 200L, false))        // inside the echo window
        assertFalse(LightCurve.isExternal(90, 100, 5000L, true))        // our write is queued
        assertFalse(LightCurve.isExternal(90, -1, 5000L, false))        // we wrote nothing
        assertFalse(LightCurve.isExternal(-1, 100, 5000L, false))       // unreadable
        assertTrue(LightCurve.isExternal(90, 100, 5000L, false))
        assertTrue(LightCurve.isExternal(90, 100, DeviceLight.ECHO_MS, false))
        assertFalse(LightCurve.isExternal(90, 100, DeviceLight.ECHO_MS - 1, false))
    }

    @Test fun stillOursToleratesVendorQuantisationOnly() {
        assertTrue(LightCurve.stillOurs(30, 30))
        assertTrue(LightCurve.stillOurs(32, 30))
        assertFalse(LightCurve.stillOurs(33, 30))
        assertTrue(LightCurve.stillOurs(255, 248))                      // 248 / 32 = 7
        assertFalse(LightCurve.stillOurs(120, 30))
        assertFalse(LightCurve.stillOurs(30, -1))                       // no last level known
        assertFalse(LightCurve.stillOurs(-1, 30))
    }

    @Test fun keyRegexFindsVendorLightKeysOnly() {
        for (k in listOf("ColdValue", "screen_brightness_warm", "LastWarmLight", "screen_cool_brightness", "WarmValue",
                "screen_brightness", "color_temperature")) {
            assertTrue(k, LightProbe.isLightKey(k))
        }
        assertFalse(LightProbe.KEY_RE.containsMatchIn("font_scale"))
        assertFalse(LightProbe.isLightKey("font_scale"))
        assertFalse(LightProbe.isLightKey("notification_light_pulse"))
        assertTrue(LightProbe.hasWarm(mapOf("ColdValue" to "90", "WarmValue" to "40")))
        assertFalse(LightProbe.hasWarm(mapOf("screen_brightness" to "102")))
    }

    /**
     * C35/K13 crash rule: a reader opened straight after a crash (RESUME/OPEN_LAST: no library list, no
     * restoreIfStale) keeps the pending original and never re-records the crashed reader's level as "the original".
     */
    @Test fun aPendingOriginalIsKeptAcrossACrash() {
        assertTrue(LightPolicy.recordOriginal(pending = false))
        assertFalse(LightPolicy.recordOriginal(pending = true))
        // Original 200, the crashed reader left 30; the next reader writes 30 again and leaves: 200 comes back.
        val act = LightPolicy.restoreActions(stampOk = true, level = true, orig = 200, current = 30, last = 30, origAuto = false)
        assertEquals(LightPolicy.RESTORE_LEVEL, act)
    }

    @Test fun diagnosticLines() {
        assertEquals("없음", LightProbe.keysLine(emptyMap()))
        assertEquals("ColdValue=90 · WarmValue=40", LightProbe.keysLine(linkedMapOf("ColdValue" to "90", "WarmValue" to "40")))
        assertEquals("/x 읽기 불가", LightProbe.nodeLine("/x", -1))
        assertEquals("/x = 90", LightProbe.nodeLine("/x", 90))
        assertEquals("기기 설정 (확인됨)", LightProbe.verdictLabel(DeviceLight.VERDICT_DEVICE))
        assertEquals("앱 화면 (확인됨)", LightProbe.verdictLabel(DeviceLight.VERDICT_WINDOW))
        assertEquals("확인 안 됨", LightProbe.verdictLabel(DeviceLight.VERDICT_UNKNOWN))
        assertEquals("없음", LightProbe.verdictLabel(DeviceLight.VERDICT_NONE))
        assertEquals(" (수동)", LightProbe.modeLabel(0))
        assertEquals(" (자동)", LightProbe.modeLabel(1))
    }

    @Test fun windowLevelIsTheCurveWithAFloor() {
        assertEquals(0.25f, LightCurve.windowLevel(0.5f), 1e-6f)
        assertEquals(1f, LightCurve.windowLevel(1f), 0f)
        assertEquals(LightCurve.WINDOW_FLOOR, LightCurve.windowLevel(0f), 0f)
        assertEquals(LightCurve.WINDOW_FLOOR, LightCurve.windowLevel(0.05f), 0f)   // 0.0025 is under the floor
        assertEquals(LightCurve.WINDOW_FLOOR, LightCurve.windowLevel(Float.NaN), 0f)
        assertEquals(1f, LightCurve.windowLevel(3f), 0f)
        // The same slider position gives the same light on the window and the device path (above the floor).
        for (i in 10..100) { val p = i / 100f; assertEquals(LightCurve.out(p), LightCurve.windowLevel(p), 0f) }
    }

    @Test fun systemLightRoundTripsThroughThePosition() {
        // systemPos on the window path: LightCurve.pos of the system light shows the same light again.
        for (level in 1..255) {
            val sys = level / 255f
            assertEquals(sys, LightCurve.out(LightCurve.pos(sys)), 1e-5f)
        }
    }
}
