package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyNamesTest {
    @Test
    fun names() {
        assertEquals("볼륨 아래", KeyNames.name(25))
        assertEquals("볼륨 아래 (25)", KeyNames.label(25))
        assertEquals("Page Down (93)", KeyNames.label(93))
        assertEquals("A 키", KeyNames.name(29))
        assertEquals("Z 키", KeyNames.name(54))
        assertEquals("숫자 0", KeyNames.name(7))
        assertEquals("F12", KeyNames.name(142))
        assertEquals("키 코드 700", KeyNames.label(700))
        assertEquals("알 수 없는 키 (0)", KeyNames.label(0))
    }

    @Test
    fun unassignable() {
        assertNotNull(KeyNames.unassignableReason(KeyNames.BACK))
        assertNotNull(KeyNames.unassignableReason(KeyNames.HOME))
        assertNotNull(KeyNames.unassignableReason(KeyNames.POWER))
        assertNull(KeyNames.unassignableReason(KeyNames.VOLUME_UP))
        assertNull(KeyNames.unassignableReason(0))
    }

    @Test
    fun readerEffectDefaults() {
        val a = AppSettings()
        assertEquals(KeyNames.Effect.NEXT, KeyNames.readerEffect(KeyNames.VOLUME_DOWN, false, a))
        assertEquals(KeyNames.Effect.PREV, KeyNames.readerEffect(KeyNames.VOLUME_UP, false, a))
        assertEquals(KeyNames.Effect.NEXT, KeyNames.readerEffect(KeyNames.PAGE_DOWN, false, a))
        assertEquals(KeyNames.Effect.PREV, KeyNames.readerEffect(KeyNames.PAGE_UP, false, a))
        assertEquals(KeyNames.Effect.NEXT, KeyNames.readerEffect(KeyNames.SPACE, false, a))
        assertEquals(KeyNames.Effect.PREV, KeyNames.readerEffect(KeyNames.SPACE, true, a))
        assertEquals(KeyNames.Effect.MENU, KeyNames.readerEffect(KeyNames.ENTER, false, a))
        assertEquals(KeyNames.Effect.NONE, KeyNames.readerEffect(131, false, a))
    }

    @Test
    fun readerEffectVolumeOptions() {
        val off = AppSettings(volumeKeysTurn = false)
        assertEquals(KeyNames.Effect.VOLUME, KeyNames.readerEffect(KeyNames.VOLUME_DOWN, false, off))
        val inv = AppSettings(invertVolumeKeys = true)
        assertEquals(KeyNames.Effect.PREV, KeyNames.readerEffect(KeyNames.VOLUME_DOWN, false, inv))
        assertEquals(KeyNames.Effect.NEXT, KeyNames.readerEffect(KeyNames.VOLUME_UP, false, inv))
    }

    @Test
    fun learnedKeysWin() {
        val a = AppSettings(nextPageKeys = setOf(KeyNames.VOLUME_UP), prevPageKeys = setOf(131))
        assertEquals(KeyNames.Effect.NEXT, KeyNames.readerEffect(KeyNames.VOLUME_UP, false, a))
        assertEquals(KeyNames.Effect.PREV, KeyNames.readerEffect(131, false, a))
    }

    @Test
    fun bindingsComeFirstInTheKeyTest() {
        var a = AppSettings(prevPageKeys = setOf(131))
        assertEquals("이전 페이지", KeyNames.readerEffectLabel(131, false, a))
        a = KeyAssign.bind(a, 131, TapAction.NEXT_CHAPTER)
        assertEquals("다음 챕터", KeyNames.readerEffectLabel(131, false, a))
        // A volume key bound to NONE goes to the system even with volume paging on.
        a = KeyAssign.bind(a, KeyNames.VOLUME_DOWN, TapAction.NONE)
        assertEquals("시스템에 맡김", KeyNames.readerEffectLabel(KeyNames.VOLUME_DOWN, false, a))
        assertEquals(KeyNames.Effect.PREV.label, KeyNames.readerEffectLabel(KeyNames.VOLUME_UP, false, a))
        assertEquals(KeyNames.Effect.NONE.label, KeyNames.readerEffectLabel(700, false, a))
    }

    @Test
    fun bindDropsTheLearnedEntry() {
        var a = AppSettings(nextPageKeys = setOf(131, 132), prevPageKeys = setOf(133))
        a = KeyAssign.bind(a, 131, TapAction.TOC)
        a = KeyAssign.bind(a, 133, TapAction.NEXT)
        assertEquals(setOf(132), a.nextPageKeys)
        assertTrue(a.prevPageKeys.isEmpty())
        assertEquals(mapOf(131 to TapAction.TOC, 133 to TapAction.NEXT), a.keyBindings)
        assertEquals(TapAction.TOC, KeyAssign.actionOf(a, 131))
        assertEquals(TapAction.NEXT, KeyAssign.actionOf(a, 132))
        assertNull(KeyAssign.actionOf(a, 134))
        // Re-binding replaces the action.
        a = KeyAssign.bind(a, 131, TapAction.REFRESH)
        assertEquals(TapAction.REFRESH, a.keyBindings[131])
    }

    @Test
    fun entriesMergeBindingsAndLearnedKeysByCode() {
        val a = AppSettings(
            nextPageKeys = setOf(140),
            prevPageKeys = setOf(120),
            keyBindings = mapOf(25 to TapAction.NEXT_CHAPTER, 130 to TapAction.NONE),
        )
        assertEquals(
            listOf(25 to TapAction.NEXT_CHAPTER, 120 to TapAction.PREV, 130 to TapAction.NONE, 140 to TapAction.NEXT),
            KeyAssign.entries(a),
        )
        // A stale learned entry under a binding shows once, as the binding.
        val b = AppSettings(nextPageKeys = setOf(25), keyBindings = mapOf(25 to TapAction.MENU))
        assertEquals(listOf(25 to TapAction.MENU), KeyAssign.entries(b))
        assertTrue(KeyAssign.entries(AppSettings()).isEmpty())
    }

    @Test
    fun removeAndClearAll() {
        var a = AppSettings(nextPageKeys = setOf(131), prevPageKeys = setOf(132), keyBindings = mapOf(133 to TapAction.TOC))
        a = KeyAssign.remove(a, 133)
        assertTrue(a.keyBindings.isEmpty())
        a = KeyAssign.remove(a, 131)
        assertTrue(a.nextPageKeys.isEmpty())
        assertEquals(setOf(132), a.prevPageKeys)
        val unchanged = KeyAssign.remove(a, 999)
        assertEquals(a, unchanged)
        a = KeyAssign.clearAll(KeyAssign.bind(a, 25, TapAction.PREV))
        assertTrue(a.nextPageKeys.isEmpty() && a.prevPageKeys.isEmpty() && a.keyBindings.isEmpty())
    }

    @Test
    fun actionChooser() {
        // The spec's 13 choices, in order, ending with the hand-back.
        assertEquals(13, KeyActions.CHOICES.size)
        assertEquals(TapAction.NEXT, KeyActions.CHOICES.first())
        assertEquals(TapAction.NONE, KeyActions.CHOICES.last())
        assertEquals(KeyActions.CHOICES.size, KeyActions.CHOICES.toSet().size)
        assertEquals(
            listOf(
                "다음 페이지", "이전 페이지", "다음 챕터", "이전 챕터", "목차", "메뉴", "북마크", "화면 새로고침", "흑백 반전",
                "듣기", "자동 넘김", "페이지 이동", "없음 (시스템에 맡김)",
            ),
            KeyActions.CHOICES.map { KeyActions.label(it) },
        )
        // Actions outside the chooser (restored from a backup) keep their tap-zone label; the tap zones, the key
        // chooser and the preview name the chapters the same way.
        assertEquals(TapAction.SEARCH.label, KeyActions.label(TapAction.SEARCH))
        assertEquals(TapAction.NEXT_CHAPTER.label, KeyActions.label(TapAction.NEXT_CHAPTER))
        // The key list shows a key's name without its code; a toast keeps the code.
        assertEquals("볼륨 아래", KeyNames.name(KeyNames.VOLUME_DOWN))
        assertEquals("키 코드 290", KeyNames.name(290))
        assertEquals("볼륨 아래 (25)", KeyNames.label(KeyNames.VOLUME_DOWN))
    }
}
