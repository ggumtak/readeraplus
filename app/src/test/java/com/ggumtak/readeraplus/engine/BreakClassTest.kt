package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakClassTest {

    @Test
    fun markRangesCoverEveryBmpCombiningMark() {
        for (cp in 0 until 65536) {
            val t = Character.getType(cp)
            val isMark = t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() ||
                t == Character.COMBINING_SPACING_MARK.toInt()
            if (!isMark) continue
            val cls = BreakClass.of(cp.toChar())
            // Marks may be re-classified as GLUE/JOIN/CJK-voiced marks, never as a breakable class.
            assertTrue(
                "U+%04X is a combining mark but class %d".format(cp, cls),
                cls == BreakClass.GLUE || cls == BreakClass.JOIN,
            )
        }
    }

    @Test
    fun classesOfKeyCharacters() {
        assertEquals(BreakClass.HANGUL, BreakClass.of('가'))
        assertEquals(BreakClass.HANGUL, BreakClass.of('\u3131'))
        assertEquals(BreakClass.GLUE, BreakClass.of('\u1161'))
        assertEquals(BreakClass.CJK, BreakClass.of('\u6F22'))
        assertEquals(BreakClass.CJK, BreakClass.of('\u3042'))
        assertEquals(BreakClass.SPACE, BreakClass.of(' '))
        assertEquals(BreakClass.SPACE, BreakClass.of('\u3000'))
        assertEquals(BreakClass.JOIN, BreakClass.of('\u00A0'))
        assertEquals(BreakClass.JOIN, BreakClass.of('\u200D'))
        assertEquals(BreakClass.ZWSP, BreakClass.of('\u200B'))
        assertEquals(BreakClass.GLUE, BreakClass.of('\u0301'))
        assertEquals(BreakClass.GLUE, BreakClass.of('\uDE00'))
        assertEquals(BreakClass.GLUE, BreakClass.of('\uFE0F'))
        assertEquals(BreakClass.QUOTE, BreakClass.of('"'))
        assertEquals(BreakClass.DASH, BreakClass.of('\u2014'))
        for (c in ".,!?:;\u2026\u2025\u201D\u2019\u300D\u300F)]}\u3009\u300B\u3011\u3015\uFF5E~\u00B7\u3001\u3002\uFF0C") {
            assertEquals("closing U+%04X".format(c.code), BreakClass.CLOSE, BreakClass.of(c))
        }
        for (c in "\u201C\u2018\u300C\u300E([{\u3008\u300A\u3010\u3014") {
            assertEquals("opening U+%04X".format(c.code), BreakClass.OPEN, BreakClass.of(c))
        }
        assertEquals(BreakClass.OTHER, BreakClass.of('a'))
        assertEquals(BreakClass.OTHER, BreakClass.of('7'))
    }
}
