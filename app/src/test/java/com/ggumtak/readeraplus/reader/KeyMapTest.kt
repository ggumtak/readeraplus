package com.ggumtak.readeraplus.reader

import android.view.KeyEvent
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.EINK_MODE_FAST
import com.ggumtak.readeraplus.settings.EINK_MODE_HD
import com.ggumtak.readeraplus.settings.EINK_MODE_SYSTEM
import com.ggumtak.readeraplus.settings.KeyHold
import com.ggumtak.readeraplus.settings.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMapTest {
    private val d = AppSettings()

    @Test
    fun builtInNextAndPrev() {
        for (k in listOf(
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_NEXT,
        )) assertEquals("key $k", KeyAction.NEXT, KeyMap.resolve(k, false, d))
        for (k in listOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )) assertEquals("key $k", KeyAction.PREV, KeyMap.resolve(k, false, d))
        assertEquals(KeyAction.PREV, KeyMap.resolve(KeyEvent.KEYCODE_SPACE, true, d))
    }

    @Test
    fun menuKeys() {
        assertEquals(KeyAction.MENU, KeyMap.resolve(KeyEvent.KEYCODE_MENU, false, d))
        assertEquals(KeyAction.MENU, KeyMap.resolve(KeyEvent.KEYCODE_ENTER, false, d))
        assertEquals(KeyAction.MENU, KeyMap.resolve(KeyEvent.KEYCODE_DPAD_CENTER, false, d))
        assertEquals(KeyAction.NONE, KeyMap.resolve(KeyEvent.KEYCODE_A, false, d))
        assertEquals(KeyAction.NONE, KeyMap.resolve(KeyEvent.KEYCODE_BACK, false, d))
    }

    @Test
    fun volumeSwitches() {
        val off = d.copy(volumeKeysTurn = false)
        assertEquals(KeyAction.NONE, KeyMap.resolve(KeyEvent.KEYCODE_VOLUME_DOWN, false, off))
        assertEquals(KeyAction.NONE, KeyMap.resolve(KeyEvent.KEYCODE_VOLUME_UP, false, off))
        val inv = d.copy(invertVolumeKeys = true)
        assertEquals(KeyAction.PREV, KeyMap.resolve(KeyEvent.KEYCODE_VOLUME_DOWN, false, inv))
        assertEquals(KeyAction.NEXT, KeyMap.resolve(KeyEvent.KEYCODE_VOLUME_UP, false, inv))
        // Page keys are not affected by the volume inversion.
        assertEquals(KeyAction.NEXT, KeyMap.resolve(KeyEvent.KEYCODE_PAGE_DOWN, false, inv))
    }

    @Test
    fun learnedKeysWin() {
        val learned = d.copy(nextPageKeys = setOf(KeyEvent.KEYCODE_VOLUME_UP, 290), prevPageKeys = setOf(KeyEvent.KEYCODE_F5))
        assertEquals(KeyAction.NEXT, KeyMap.resolve(KeyEvent.KEYCODE_VOLUME_UP, false, learned))
        assertEquals(KeyAction.NEXT, KeyMap.resolve(290, false, learned))
        assertEquals(KeyAction.PREV, KeyMap.resolve(KeyEvent.KEYCODE_F5, false, learned))
        // Learned keys work even when volume paging is off.
        val offLearned = learned.copy(volumeKeysTurn = false)
        assertEquals(KeyAction.NEXT, KeyMap.resolve(KeyEvent.KEYCODE_VOLUME_UP, false, offLearned))
        assertEquals(KeyAction.NONE, KeyMap.resolve(KeyEvent.KEYCODE_VOLUME_DOWN, false, offLearned))
    }

    @Test
    fun focusKeys() {
        assertTrue(KeyMap.isFocusKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(KeyMap.isFocusKey(KeyEvent.KEYCODE_ENTER))
        assertFalse(KeyMap.isFocusKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(KeyMap.isFocusKey(KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test
    fun repeatFilter() {
        val f = RepeatFilter(150)
        assertTrue(f.accept(0, 1000))
        assertFalse(f.accept(1, 1050))
        assertFalse(f.accept(2, 1149))
        assertTrue(f.accept(3, 1150))
        assertFalse(f.accept(4, 1200))
        // A fresh press is always accepted, even right after another one.
        assertTrue(f.accept(0, 1210))
        assertTrue(f.accept(0, 1220))
    }

    @Test
    fun volumeKeys() {
        assertTrue(KeyMap.isVolumeKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(KeyMap.isVolumeKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(KeyMap.isVolumeKey(KeyEvent.KEYCODE_PAGE_DOWN))
        assertFalse(KeyMap.isVolumeKey(KeyEvent.KEYCODE_VOLUME_MUTE))
    }

    @Test
    fun bindingsComeFirstThenLearnedThenBuiltIn() {
        val a = d.copy(
            keyBindings = mapOf(KeyEvent.KEYCODE_VOLUME_UP to TapAction.TOC, 290 to TapAction.NEXT_CHAPTER),
            nextPageKeys = setOf(290, KeyEvent.KEYCODE_F5),
        )
        assertEquals(TapAction.TOC, KeyMap.action(KeyEvent.KEYCODE_VOLUME_UP, false, a))
        assertEquals(TapAction.NEXT_CHAPTER, KeyMap.action(290, false, a))
        assertEquals(TapAction.NEXT, KeyMap.action(KeyEvent.KEYCODE_F5, false, a))
        assertEquals(TapAction.NEXT, KeyMap.action(KeyEvent.KEYCODE_VOLUME_DOWN, false, a))
        assertEquals(TapAction.PREV, KeyMap.action(KeyEvent.KEYCODE_SPACE, true, a))
        assertEquals(TapAction.MENU, KeyMap.action(KeyEvent.KEYCODE_MENU, false, a))
        assertEquals(TapAction.NONE, KeyMap.action(KeyEvent.KEYCODE_A, false, a))
    }

    @Test
    fun aVolumeBindingOverridesVolumeKeysTurnAndNoneLeavesTheKeyToTheSystem() {
        val a = d.copy(
            volumeKeysTurn = false,
            keyBindings = mapOf(KeyEvent.KEYCODE_VOLUME_DOWN to TapAction.NEXT, KeyEvent.KEYCODE_PAGE_DOWN to TapAction.NONE),
        )
        assertEquals(TapAction.NEXT, KeyMap.action(KeyEvent.KEYCODE_VOLUME_DOWN, false, a))
        assertEquals(TapAction.NONE, KeyMap.action(KeyEvent.KEYCODE_VOLUME_UP, false, a))
        assertEquals(TapAction.NONE, KeyMap.action(KeyEvent.KEYCODE_PAGE_DOWN, false, a))
        assertTrue(KeyMap.isAssigned(KeyEvent.KEYCODE_VOLUME_DOWN, a))
        assertFalse(KeyMap.isAssigned(KeyEvent.KEYCODE_VOLUME_UP, a))
        assertTrue(KeyMap.isAssigned(KeyEvent.KEYCODE_F5, d.copy(prevPageKeys = setOf(KeyEvent.KEYCODE_F5))))
    }

    @Test
    fun builtInKeysAreKnownWhateverTheSettings() {
        assertTrue(KeyMap.isBuiltIn(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(KeyMap.isBuiltIn(KeyEvent.KEYCODE_PAGE_DOWN))
        assertTrue(KeyMap.isBuiltIn(KeyEvent.KEYCODE_ENTER))
        assertFalse(KeyMap.isBuiltIn(KeyEvent.KEYCODE_F5))
        assertFalse(KeyMap.isBuiltIn(290))
    }

    @Test
    fun repeatPaceFollowsTheEinkMode() {
        assertEquals(250L, KeyMap.repeatMs(EINK_MODE_SYSTEM))
        assertEquals(250L, KeyMap.repeatMs(EINK_MODE_HD))
        assertEquals(150L, KeyMap.repeatMs(EINK_MODE_FAST))
    }

    @Test
    fun heldKeyRepeatsAtTheGivenPace() {
        val h = HeldKey()
        val k = KeyEvent.KEYCODE_VOLUME_DOWN
        assertEquals(HeldKey.Step.TURN, h.down(k, 0, 1000, KeyHold.REPEAT, 250))
        assertEquals(HeldKey.Step.TURN, h.down(k, 1, 1500, KeyHold.REPEAT, 250))
        assertEquals(HeldKey.Step.NONE, h.down(k, 2, 1550, KeyHold.REPEAT, 250))
        assertEquals(HeldKey.Step.NONE, h.down(k, 5, 1749, KeyHold.REPEAT, 250))
        assertEquals(HeldKey.Step.TURN, h.down(k, 6, 1750, KeyHold.REPEAT, 250))
        // A fresh press is never paced.
        h.up(k)
        assertEquals(HeldKey.Step.TURN, h.down(k, 0, 1760, KeyHold.REPEAT, 250))
    }

    @Test
    fun heldKeyHoldActsOnceAtTheFirstRepeat() {
        for (hold in listOf(KeyHold.CHAPTER, KeyHold.TEN)) {
            val h = HeldKey()
            assertEquals(HeldKey.Step.TURN, h.down(24, 0, 0, hold, 250))
            assertEquals(HeldKey.Step.HOLD, h.down(24, 1, 500, hold, 250))
            for (r in 2..20) assertEquals(HeldKey.Step.NONE, h.down(24, r, 500L + r * 50, hold, 250))
            h.up(24)
            assertEquals(HeldKey.Step.TURN, h.down(24, 0, 3000, hold, 250))
            assertEquals(HeldKey.Step.HOLD, h.down(24, 1, 3500, hold, 250))
        }
        val single = HeldKey()
        assertEquals(HeldKey.Step.TURN, single.down(24, 0, 0, KeyHold.SINGLE, 250))
        for (r in 1..10) assertEquals(HeldKey.Step.NONE, single.down(24, r, 450L + r * 50, KeyHold.SINGLE, 250))
    }

    @Test
    fun repeatsOfAnotherOrADroppedPressDoNothing() {
        val h = HeldKey()
        assertEquals(HeldKey.Step.TURN, h.down(24, 0, 0, KeyHold.REPEAT, 250))
        assertEquals(HeldKey.Step.NONE, h.down(25, 3, 900, KeyHold.REPEAT, 250))
        h.cancel()
        assertEquals(HeldKey.Step.NONE, h.down(24, 1, 1000, KeyHold.REPEAT, 250))
        assertEquals(HeldKey.Step.NONE, h.down(24, 1, 1000, KeyHold.CHAPTER, 250))
    }
}
