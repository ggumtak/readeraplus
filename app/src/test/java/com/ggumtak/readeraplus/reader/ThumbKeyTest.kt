package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThumbKeyTest {
    private val base = ThumbKey(genId = 3, section = 5, pageIndex = 7, wPx = 156, hPx = 312, decorVersion = 2,
        paintVersion = 1, quoteLook = 4)

    @Test
    fun equalKeysHashAlike() {
        val same = ThumbKey(3, 5, 7, 156, 312, 2, 1, 4)
        assertEquals(base, same)
        assertEquals(base.hashCode(), same.hashCode())
        val map = HashMap<ThumbKey, String>()
        map[base] = "x"
        assertEquals("x", map[same])
    }

    @Test
    fun everyFieldChangesTheKey() {
        assertNotEquals(base, base.copy(genId = 4))
        assertNotEquals(base, base.copy(section = 6))
        assertNotEquals(base, base.copy(pageIndex = 8))
        assertNotEquals(base, base.copy(wPx = 181))
        assertNotEquals(base, base.copy(hPx = 393))
        assertNotEquals(base, base.copy(decorVersion = 3))
        assertNotEquals(base, base.copy(paintVersion = 2))
        assertNotEquals(base, base.copy(quoteLook = 5))
    }
}
