package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemesTest {

    @Test
    fun asciiAndHangulAreOneChar() {
        assertEquals(1, clusterEnd("abc", 0))
        assertEquals(3, clusterEnd("abc", 2))
        assertEquals(2, clusterEnd("가나다", 1))
    }

    @Test
    fun surrogatePairIsOneCluster() {
        val t = "a😀b"
        assertEquals(3, clusterEnd(t, 1))
        // Pointing at the low half still ends after it.
        assertEquals(3, clusterEnd(t, 2))
    }

    @Test
    fun zwjFamilyIsOneCluster() {
        val family = "👨‍👩‍👧"
        val t = "x${family}y"
        assertEquals(1 + family.length, clusterEnd(t, 1))
        assertEquals(t.length, clusterEnd(t, t.length - 1))
    }

    @Test
    fun variationSelectorStaysWithBase() {
        val t = "❤️a"
        assertEquals(2, clusterEnd(t, 0))
    }

    @Test
    fun combiningMarkStaysWithBase() {
        val t = "é̂x"
        assertEquals(3, clusterEnd(t, 0))
        assertEquals(4, clusterEnd(t, 3))
    }

    @Test
    fun skinToneModifierStaysWithBase() {
        val t = "👍🏽z"
        assertEquals(4, clusterEnd(t, 0))
    }

    @Test
    fun flagStaysTogether() {
        val flag = "🇰🇷"
        assertEquals(4, clusterEnd(flag + flag, 0))
    }

    @Test
    fun staysWithinText() {
        assertEquals(0, clusterEnd("", 0))
        assertEquals(3, clusterEnd("abc", 3))
        assertEquals(3, clusterEnd("abc", 9))
        // A lone high surrogate or a trailing ZWJ ends at the text, not beyond.
        assertEquals(1, clusterEnd("\uD83D", 0))
        assertEquals(2, clusterEnd("a‍", 0))
    }
}
