package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.render.DeviceCleanInfo
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.EINK_REFRESH_AUTO
import com.ggumtak.readeraplus.settings.EINK_REFRESH_CLEAN
import com.ggumtak.readeraplus.settings.EINK_REFRESH_FLASH
import com.ggumtak.readeraplus.settings.EINK_REFRESH_GC16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkChoicesTest {
    @Test
    fun deviceMethodsOnlyWithTheXrzHook() {
        assertEquals(
            listOf(EINK_REFRESH_AUTO, EINK_REFRESH_GC16, EINK_REFRESH_CLEAN, EINK_REFRESH_FLASH),
            EinkChoices.methods(hasXrz = true).map { it.second },
        )
        assertEquals(listOf(EINK_REFRESH_AUTO, EINK_REFRESH_FLASH), EinkChoices.methods(hasXrz = false).map { it.second })
        assertEquals(
            listOf("자동 (기본)", "기기 GC16", "기기 잔상 제거 (CLEAN)", "검은 화면 깜빡임"),
            EinkChoices.METHODS.map { it.first },
        )
        // The first entry is the default setting.
        assertEquals(AppSettings().einkRefreshMethod, EinkChoices.METHODS[0].second)
        assertEquals("자동 (기본)", EinkChoices.method(42))
        assertTrue(EinkChoices.isDeviceMethod(EINK_REFRESH_GC16) && EinkChoices.isDeviceMethod(EINK_REFRESH_CLEAN))
        assertFalse(EinkChoices.isDeviceMethod(EINK_REFRESH_AUTO) || EinkChoices.isDeviceMethod(EINK_REFRESH_FLASH))
    }

    @Test
    fun nextMethodCyclesThroughWhatTheDeviceOffers() {
        assertEquals(EINK_REFRESH_GC16, EinkChoices.next(EINK_REFRESH_AUTO, hasXrz = true))
        assertEquals(EINK_REFRESH_CLEAN, EinkChoices.next(EINK_REFRESH_GC16, hasXrz = true))
        assertEquals(EINK_REFRESH_FLASH, EinkChoices.next(EINK_REFRESH_CLEAN, hasXrz = true))
        assertEquals(EINK_REFRESH_AUTO, EinkChoices.next(EINK_REFRESH_FLASH, hasXrz = true))
        assertEquals(EINK_REFRESH_FLASH, EinkChoices.next(EINK_REFRESH_AUTO, hasXrz = false))
        assertEquals(EINK_REFRESH_AUTO, EinkChoices.next(EINK_REFRESH_FLASH, hasXrz = false))
        // A method the device lacks (restored from a backup) starts the cycle over.
        assertEquals(EINK_REFRESH_AUTO, EinkChoices.next(EINK_REFRESH_GC16, hasXrz = false))
    }

    @Test
    fun flashAndNightLabels() {
        assertEquals(listOf("100ms (기본)", "200ms", "350ms"), EinkChoices.FLASH_MS.map { EinkChoices.flash(it) })
        assertTrue(AppSettings().einkFlashMs in EinkChoices.FLASH_MS)
        assertEquals(listOf("낮과 같게", "3쪽마다", "5쪽마다", "10쪽마다", "20쪽마다"), EinkChoices.NIGHT.map { EinkChoices.night(it) })
        assertEquals(AppSettings().einkRefreshEveryNight, EinkChoices.NIGHT[0])
    }

    @Test
    fun deviceCleanReadout() {
        assertEquals("기기 자체 잔상 제거: 켜짐 · 10쪽마다", EinkChoices.deviceClean(DeviceCleanInfo(true, 10)))
        assertEquals("기기 자체 잔상 제거: 켜짐", EinkChoices.deviceClean(DeviceCleanInfo(true, -1)))
        assertEquals("기기 자체 잔상 제거: 꺼짐", EinkChoices.deviceClean(DeviceCleanInfo(false, 10)))
        assertNull(EinkChoices.deviceClean(null))
    }

    @Test
    fun doubleFlashWarningNeedsBothCadences() {
        val on = DeviceCleanInfo(true, 10)
        val off = DeviceCleanInfo(false, 10)
        // Defaults: the app has no cadence (decision 3), so nothing to warn about.
        assertFalse(EinkChoices.doubleFlash(on, AppSettings()))
        assertTrue(EinkChoices.doubleFlash(on, AppSettings(einkRefreshEvery = 5)))
        assertTrue(EinkChoices.doubleFlash(on, AppSettings(einkRefreshEveryNight = 3)))
        assertFalse(EinkChoices.doubleFlash(off, AppSettings(einkRefreshEvery = 5)))
        assertFalse(EinkChoices.doubleFlash(null, AppSettings(einkRefreshEvery = 5)))
        // "Same as day" at night is not a cadence of its own.
        assertFalse(EinkChoices.doubleFlash(on, AppSettings(einkRefreshEveryNight = -1)))
    }

    @Test
    fun diagnostics() {
        val text = EinkChoices.diagnostics("Bigme xrz", true, true, DeviceCleanInfo(true, 10), 720, 1440, 320)
        assertEquals(
            "e-ink 제어: Bigme xrz\n" +
                "기기 새로고침 (GC16 · CLEAN): 사용 가능\n" +
                "e-ink 화면 모드 바꾸기: 가능\n" +
                "기기 자체 잔상 제거: 켜짐 · 10쪽마다\n" +
                "화면: 720 × 1440 px · 320 dpi",
            text,
        )
        val none = EinkChoices.diagnostics(null, false, false, null, 1080, 2400, 420).lines()
        assertEquals("e-ink 제어: 없음 (검은 화면을 잠깐 띄워 잔상을 지웁니다)", none[0])
        assertEquals("기기 새로고침 (GC16 · CLEAN): 없음", none[1])
        assertEquals("기기 자체 잔상 제거: 확인할 수 없음", none[3])
    }
}
