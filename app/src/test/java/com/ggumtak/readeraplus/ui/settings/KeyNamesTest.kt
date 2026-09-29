package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.settings.AppSettings
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
        var a = AppSettings()
        a = KeyAssign.assign(a, KeyNames.VOLUME_UP, next = true)
        assertEquals(KeyNames.Effect.NEXT, KeyNames.readerEffect(KeyNames.VOLUME_UP, false, a))
        a = KeyAssign.assign(a, 131, next = false)
        assertEquals(KeyNames.Effect.PREV, KeyNames.readerEffect(131, false, a))
    }

    @Test
    fun assignMovesBetweenSets() {
        var a = KeyAssign.assign(AppSettings(), 131, next = true)
        assertTrue(131 in a.nextPageKeys)
        a = KeyAssign.assign(a, 131, next = false)
        assertFalse(131 in a.nextPageKeys)
        assertTrue(131 in a.prevPageKeys)
        a = KeyAssign.assign(a, 132, next = true)
        assertEquals(listOf(132 to true, 131 to false), KeyAssign.list(a))
        a = KeyAssign.remove(a, 131)
        assertEquals(setOf<Int>(), a.prevPageKeys)
        a = KeyAssign.clearAll(a)
        assertTrue(a.nextPageKeys.isEmpty() && a.prevPageKeys.isEmpty())
    }
}
