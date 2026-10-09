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

    @Test
    fun clusterStartPlainText() {
        assertEquals(0, clusterStart("abc", 0))
        assertEquals(2, clusterStart("abc", 2))
        assertEquals(1, clusterStart("가나다", 1))
        assertEquals(0, clusterStart("", 0))
        assertEquals(3, clusterStart("abc", 3))
        assertEquals(3, clusterStart("abc", 9))
        assertEquals(0, clusterStart("abc", -4))
    }

    @Test
    fun clusterStartMovesBackToSurrogatePairStart() {
        val t = "a😀b"
        assertEquals(1, clusterStart(t, 1))
        assertEquals(1, clusterStart(t, 2))
        assertEquals(3, clusterStart(t, 3))
    }

    @Test
    fun clusterStartMovesBackOverMarksAndModifiers() {
        assertEquals(0, clusterStart("e\u0301\u0302x", 1))
        assertEquals(0, clusterStart("e\u0301\u0302x", 2))
        assertEquals(3, clusterStart("e\u0301\u0302x", 3))
        assertEquals(0, clusterStart("\u2764\uFE0Fa", 1))
        assertEquals(1, clusterStart("a👍🏽z", 3))
        assertEquals(1, clusterStart("a👍🏽z", 4))
        assertEquals(5, clusterStart("a👍🏽z", 5))
    }

    @Test
    fun clusterStartMovesBackOverZwjSequences() {
        val family = "👨‍👩‍👧"
        val t = "x${family}y"
        for (o in 1 until 1 + family.length) assertEquals("o=$o", 1, clusterStart(t, o))
        assertEquals(1 + family.length, clusterStart(t, t.length - 1))
    }

    @Test
    fun clusterStartKeepsFlagsInPairs() {
        val flag = "🇰🇷"
        val t = flag + flag + "a"
        assertEquals(0, clusterStart(t, 2))
        assertEquals(0, clusterStart(t, 3))
        assertEquals(4, clusterStart(t, 4))
        assertEquals(4, clusterStart(t, 6))
        assertEquals(8, clusterStart(t, 8))
    }

    @Test
    fun clusterStartAndEndAgree() {
        val t = "a😀e\u0301\u0302👨\u200D👩\u200D👧🇰🇷🇰🇷👍🏽가"
        var i = 0
        while (i < t.length) {
            val e = clusterEnd(t, i)
            for (o in i until e) {
                assertEquals("o=$o", i, clusterStart(t, o))
                assertEquals("o=$o", e, clusterEnd(t, clusterStart(t, o)))
            }
            i = e
        }
    }
}
