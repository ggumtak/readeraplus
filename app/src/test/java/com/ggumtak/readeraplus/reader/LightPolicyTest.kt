package com.ggumtak.readeraplus.reader

import org.junit.Assert.*
import org.junit.Test

class LightPolicyTest {
    private val W = LightController.ASK_WINDOW
    private val D = LightController.ASK_DEVICE
    private val N = LightController.ASK_NONE

    /** The five §4.3 edges: phone → WINDOW; 예/아니요 on each question. */
    @Test fun verdictReducer() {
        assertTrue(LightPolicy.silentWindow(deviceOn = false, eink = false))
        assertFalse(LightPolicy.silentWindow(deviceOn = false, eink = true))
        assertFalse(LightPolicy.silentWindow(deviceOn = true, eink = false))
        assertEquals(DeviceLight.VERDICT_WINDOW, LightPolicy.nextVerdict(W, true))
        assertEquals(DeviceLight.VERDICT_UNKNOWN, LightPolicy.nextVerdict(W, false))   // → offer the device path
        assertEquals(DeviceLight.VERDICT_DEVICE, LightPolicy.nextVerdict(D, true))
        assertEquals(DeviceLight.VERDICT_NONE, LightPolicy.nextVerdict(D, false))
        assertEquals(DeviceLight.VERDICT_UNKNOWN, LightPolicy.nextVerdict(N, true))
    }

    @Test fun firstDragAsksOnEinkOrTheDevicePathAtMostThreeSessions() {
        assertEquals(W, LightPolicy.firstDragAsk(deviceOn = false, eink = true, asks = 0))
        assertEquals(W, LightPolicy.firstDragAsk(false, true, LightPolicy.MAX_ASKS - 1))
        assertEquals(N, LightPolicy.firstDragAsk(false, true, LightPolicy.MAX_ASKS))
        assertEquals(D, LightPolicy.firstDragAsk(true, false, 0))
        assertEquals(N, LightPolicy.firstDragAsk(true, true, 5))
        assertEquals(N, LightPolicy.firstDragAsk(false, false, 0))
    }

    @Test fun restorePutsBackOnlyOurOwnLevelAndAlwaysTheAutoMode() {
        val L = LightPolicy.RESTORE_LEVEL
        val M = LightPolicy.RESTORE_MODE
        assertEquals(L, LightPolicy.restoreActions(true, true, 200, 30, 30, false))
        // Fix 1: the user set 120 in the panel after a crash at 30 → 120 stays.
        assertEquals(0, LightPolicy.restoreActions(true, true, 200, 120, 30, false))
        // Fix 2: "나가도 그대로 유지" keeps the level, but automatic mode comes back.
        assertEquals(M, LightPolicy.restoreActions(true, false, 200, 30, 30, true))
        assertEquals(L or M, LightPolicy.restoreActions(true, true, 200, 30, 30, true))
        assertEquals(0, LightPolicy.restoreActions(true, false, 200, 30, 30, false))
        // Reinstall (auto backup restored the file): nothing.
        assertEquals(0, LightPolicy.restoreActions(false, true, 200, 30, 30, true))
        // No original recorded (unreadable): the level is not written.
        assertEquals(0, LightPolicy.restoreActions(true, true, -1, 30, 30, false))
        // Unknown last level: the device may show anything, so it is not ours.
        assertEquals(0, LightPolicy.restoreActions(true, true, 200, 30, -1, false))
    }

    @Test fun autoConfirm() {
        val k0 = mapOf("ColdValue" to "90", "WarmValue" to "40")
        val moved = mapOf("ColdValue" to "60", "WarmValue" to "40")
        assertFalse(LightPolicy.autoConfirm(N, 10, 20, k0, moved))
        assertTrue(LightPolicy.autoConfirm(W, 10, 20, null, null))          // the node changed
        assertFalse(LightPolicy.autoConfirm(W, 10, 10, null, null))         // unchanged is no "아니요"
        assertFalse(LightPolicy.autoConfirm(W, -1, 20, null, null))         // unreadable before
        assertFalse(LightPolicy.autoConfirm(W, -1, -1, k0, moved))          // keys only count on the device path
        assertTrue(LightPolicy.autoConfirm(D, -1, -1, k0, moved))
        assertFalse(LightPolicy.autoConfirm(D, -1, -1, k0, k0))
        assertFalse(LightPolicy.autoConfirm(D, -1, -1, k0, mapOf("Other" to "1")))
    }

    @Test fun vendorKeysExcludeThePublicSetting() {
        val all = linkedMapOf("screen_brightness" to "102", "screen_brightness_mode" to "0", "ColdValue" to "90",
            "screen_brightness_warm" to "40", "LastWarmLight" to "40")
        assertEquals(listOf("ColdValue", "screen_brightness_warm", "LastWarmLight"), LightPolicy.vendorLightKeys(all).keys.toList())
    }

    @Test fun rowTexts() {
        assertEquals("이 기기는 앱이 전면광을 바꿀 수 없습니다", LightPolicy.deviceSubtitle(true, true, false, true, false))
        assertEquals("'시스템 설정 수정' 권한이 필요합니다 · 눌러서 허용", LightPolicy.deviceSubtitle(true, true, true, false, false))
        assertEquals("전면광이 안 바뀔 때 켜세요 · 기기 전체 밝기를 바꿉니다", LightPolicy.deviceSubtitle(false, true, true, false, false))
        assertEquals("기기 전체 밝기를 바꿉니다 · 리더를 나가면 원래대로", LightPolicy.deviceSubtitle(true, true, false, false, true))
        assertEquals("기기 전체 밝기를 바꿉니다 · 나가도 그대로 유지", LightPolicy.deviceSubtitle(true, false, false, false, false))
        assertEquals("기기 전체 밝기를 바꿉니다 · 나가도 그대로 유지 (자동 밝기는 다시 켜짐)", LightPolicy.deviceSubtitle(true, false, false, false, true))
        assertEquals("이 기기에서는 밝기 스와이프를 쓸 수 없습니다", LightPolicy.swipeSubtitle(true))
        assertEquals("화면 왼쪽 가장자리를 위아래로 밀어 밝기를 바꿉니다", LightPolicy.swipeSubtitle(false))
        assertTrue(LightPolicy.panelRowVisible(none = true, warm = false))
        assertTrue(LightPolicy.panelRowVisible(none = false, warm = true))
        assertFalse(LightPolicy.panelRowVisible(none = false, warm = false))
        assertEquals("밝기와 색온도는 기기 조명에서 조절합니다", LightPolicy.panelSubtitle(true))
        assertEquals("색온도(따뜻한 빛)는 기기 조명에서 바꿉니다", LightPolicy.panelSubtitle(false))
    }
}
