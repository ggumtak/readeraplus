package com.ggumtak.readeraplus.reader

import android.view.KeyEvent
import com.ggumtak.readeraplus.settings.AppSettings
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
}
