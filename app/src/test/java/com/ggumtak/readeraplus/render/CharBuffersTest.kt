package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharBuffersTest {

    @Test
    fun firstBufferIsLargeEnoughToBeNonMovable() {
        // ART allocates primitive arrays of >= 12 KB in the (non-moving) large object space.
        val b = CharBuffers.grow(CharArray(0), 10)
        assertTrue(b.size * 2 >= 12 * 1024)
        assertEquals(CharBuffers.MIN_CHARS, b.size)
    }

    @Test
    fun growsGeometricallyAndAlwaysFits() {
        var b = CharArray(0)
        var allocations = 0
        for (n in intArrayOf(5, 9000, 9001, 20000, 16384, 70000, 1)) {
            if (b.size < n) {
                b = CharBuffers.grow(b, n)
                allocations++
            }
            assertTrue(b.size >= n)
        }
        assertTrue("few reallocations: $allocations", allocations <= 4)
        assertEquals(CharBuffers.MIN_CHARS * 16, CharBuffers.grow(CharArray(CharBuffers.MIN_CHARS), 70000).size)
    }

    @Test
    fun capacityNeverOverflows() {
        val n = Int.MAX_VALUE / 2 + 10
        assertEquals(n, CharBuffers.capacity(CharBuffers.MIN_CHARS, n))
        assertEquals(Int.MAX_VALUE, CharBuffers.capacity(1 shl 30, Int.MAX_VALUE))
        assertEquals(CharBuffers.MIN_CHARS, CharBuffers.capacity(0, 0))
        assertEquals(1 shl 20, CharBuffers.capacity(1 shl 20, 100))
    }

    @Test
    fun zeroesOnlySeparatorsAndObjectChars() {
        val text = "가\n나${OBJECT_CHAR}a b"
        val chars = text.toCharArray()
        val out = FloatArray(text.length + 3) { 5f }
        CharBuffers.zeroInvisible(chars, text.length, out, 3)
        assertArrayEquals(floatArrayOf(5f, 5f, 5f, 5f, 0f, 5f, 0f, 5f, 5f, 5f), out, 0f)
    }
}
