package com.ggumtak.readeraplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {

    private fun sorted(vararg s: String) = s.toList().sortedWith(NaturalOrder)

    @Test
    fun numbersCompareByValue() {
        assertEquals(listOf("1권", "2권", "10권", "100권"), sorted("10권", "100권", "2권", "1권"))
        assertEquals(listOf("제1화", "제9화", "제10화", "제11화"), sorted("제11화", "제1화", "제10화", "제9화"))
        assertEquals(listOf("a1b2", "a1b10", "a2b1"), sorted("a2b1", "a1b10", "a1b2"))
    }

    @Test
    fun leadingZerosTieBreakAfterValue() {
        assertTrue(NaturalOrder.compare("001화", "2화") < 0)
        assertTrue(NaturalOrder.compare("1화", "01화") < 0)
        assertTrue(NaturalOrder.compare("01화", "1화") > 0)
        assertEquals(0, NaturalOrder.compare("01", "01"))
        // Huge numbers don't overflow.
        assertTrue(NaturalOrder.compare("x99999999999999999999", "x100000000000000000000") < 0)
    }

    @Test
    fun caseInsensitiveLatinAndHangulOrder() {
        assertEquals(listOf("apple", "Banana", "cherry"), sorted("cherry", "Banana", "apple"))
        assertEquals(listOf("가나", "나비", "다리"), sorted("다리", "가나", "나비"))
        assertEquals(listOf("Zebra", "가"), sorted("가", "Zebra"))
        assertTrue(NaturalOrder.compare("ABC", "abc") != 0) // total order: stable tie-break
    }

    @Test
    fun prefixesAndEmpty() {
        assertEquals(listOf("", "a", "ab"), sorted("ab", "", "a"))
        assertTrue(NaturalOrder.compare("소설", "소설 1") < 0)
    }

    @Test
    fun consistentWithEqualsSymmetry() {
        val items = listOf("a", "A", "a1", "a01", "a001", "b", "10", "9", "", "가", "가1", "가01", "x y", "x  y")
        for (x in items) for (y in items) {
            val c1 = Integer.signum(NaturalOrder.compare(x, y))
            val c2 = Integer.signum(NaturalOrder.compare(y, x))
            assertEquals("$x vs $y", -c1, c2)
            if (x == y) assertEquals(0, c1)
            if (x != y) assertTrue("$x vs $y", c1 != 0)
        }
    }
}
