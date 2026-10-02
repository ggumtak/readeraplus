package com.ggumtak.readeraplus.reader

import android.view.KeyEvent
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.ui.settings.KeyActions
import com.ggumtak.readeraplus.ui.settings.KeyAssign
import com.ggumtak.readeraplus.ui.settings.KeyNames
import com.ggumtak.readeraplus.ui.settings.SettingsFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeModeTest {
    @Test fun volumeModeRoundTripAndOffKeepsTheRememberedDirection() {
        for (mode in VolumeMode.entries) assertEquals(mode, KeyMap.volumeMode(KeyMap.withVolumeMode(AppSettings(), mode)))
        val off = KeyMap.withVolumeMode(AppSettings(invertVolumeKeys = true), VolumeMode.OFF)
        assertTrue(off.invertVolumeKeys)
        assertFalse(off.volumeKeysTurn)
    }

    @Test fun eachVolumePageAssignmentFoldsIntoBothDirections() {
        for (code in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            for (action in listOf(TapAction.NEXT, TapAction.PREV)) {
                val old = AppSettings(
                    volumeKeysTurn = false,
                    nextPageKeys = setOf(KeyEvent.KEYCODE_VOLUME_UP, 700),
                    prevPageKeys = setOf(KeyEvent.KEYCODE_VOLUME_DOWN, 701),
                    keyBindings = mapOf(KeyEvent.KEYCODE_VOLUME_UP to TapAction.NEXT, KeyEvent.KEYCODE_VOLUME_DOWN to TapAction.PREV, 702 to TapAction.TOC),
                )
                val a = KeyAssign.bind(old, code, action)
                assertEquals(action, KeyMap.action(code, false, a))
                val other = if (code == KeyEvent.KEYCODE_VOLUME_UP) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP
                assertEquals(if (action == TapAction.NEXT) TapAction.PREV else TapAction.NEXT, KeyMap.action(other, false, a))
                assertFalse(KeyMap.volumeBound(a))
                assertNull(KeyAssign.actionOf(a, code))
                assertFalse(KeyAssign.entries(a).any { KeyMap.isVolumeKey(it.first) })
                assertEquals(setOf(700), a.nextPageKeys)
                assertEquals(setOf(701), a.prevPageKeys)
                assertEquals(mapOf(702 to TapAction.TOC), a.keyBindings)
            }
        }
    }

    @Test fun aCustomVolumeActionStaysBoundAndCanBeReplacedByDirection() {
        val a = KeyAssign.bind(AppSettings(), KeyEvent.KEYCODE_VOLUME_DOWN, TapAction.MENU)
        assertTrue(KeyMap.volumeBound(a))
        assertEquals(TapAction.MENU, KeyMap.action(KeyEvent.KEYCODE_VOLUME_DOWN, false, a))
        assertEquals("키 지정에서 볼륨 키 동작을 정했습니다", SettingsFormat.volumeSummary(a))
        val folded = KeyAssign.bind(a, KeyEvent.KEYCODE_VOLUME_DOWN, TapAction.NEXT)
        assertFalse(KeyMap.volumeBound(folded))
        assertEquals(TapAction.NEXT, KeyMap.action(KeyEvent.KEYCODE_VOLUME_DOWN, false, folded))
    }

    @Test fun explicitlyTurningVolumePagingOffRemovesLegacyPageAssignments() {
        val old = AppSettings(nextPageKeys = setOf(KeyEvent.KEYCODE_VOLUME_UP), keyBindings = mapOf(KeyEvent.KEYCODE_VOLUME_DOWN to TapAction.NEXT))
        val off = KeyMap.withVolumeMode(old, VolumeMode.OFF)
        assertEquals(TapAction.NONE, KeyMap.action(KeyEvent.KEYCODE_VOLUME_UP, false, off))
        assertEquals(TapAction.NONE, KeyMap.action(KeyEvent.KEYCODE_VOLUME_DOWN, false, off))
    }

    @Test fun normalizationKeepsOtherCustomActions() {
        val a = AppSettings(keyBindings = mapOf(KeyEvent.KEYCODE_VOLUME_DOWN to TapAction.MENU, KeyEvent.KEYCODE_VOLUME_UP to TapAction.NEXT))
        assertEquals(mapOf(KeyEvent.KEYCODE_VOLUME_DOWN to TapAction.MENU), KeyAssign.normalizeVolume(a).keyBindings)
    }

    @Test fun summariesAndTheKeyTesterDescribeTheLiveMapping() {
        assertEquals("볼륨 아래 = 다음, 볼륨 위 = 이전", SettingsFormat.volumeSummary(AppSettings()))
        assertEquals("볼륨 위 = 다음, 볼륨 아래 = 이전", SettingsFormat.volumeSummary(AppSettings(invertVolumeKeys = true)))
        assertEquals("끄면 볼륨 키는 소리 크기를 조절합니다", SettingsFormat.volumeSummary(AppSettings(volumeKeysTurn = false)))
        val bound = AppSettings(keyBindings = mapOf(KeyEvent.KEYCODE_VOLUME_DOWN to TapAction.PREV))
        assertEquals(KeyNames.Effect.PREV, KeyNames.readerEffect(KeyEvent.KEYCODE_VOLUME_DOWN, false, bound))
        assertEquals("이전 페이지", KeyNames.readerEffectLabel(KeyEvent.KEYCODE_VOLUME_DOWN, false, bound))
        assertEquals("볼륨 위 → 다음 페이지 (볼륨 아래는 이전 페이지)", KeyActions.labelFor(KeyEvent.KEYCODE_VOLUME_UP, TapAction.NEXT))
    }
}
