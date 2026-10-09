package com.ggumtak.readeraplus.format.txt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingRuleTest {
    private fun matches(stored: String, line: String): Boolean = HeadingRule.compile(stored)!!.matcher(line).find()

    @Test
    fun numberAndSpacing() {
        val r = HeadingRule.simple("< N >")
        for (s in listOf("< 77 >", "<77>", "<  77>", " < 7 > ")) assertTrue(s, matches(r, s))
        assertFalse(matches(r, "< 77 > 끝"))
        assertFalse(matches(r, "< 가 >"))
        assertFalse(matches(r, "< 123456 >"))
    }

    @Test
    fun wildcardAndAlternatives() {
        assertTrue(matches(HeadingRule.simple("< N >*"), "< 77 > 끝"))
        val alts = HeadingRule.simple("N화 *|외전 N")
        assertTrue(matches(alts, "76화 시작"))
        assertTrue(matches(alts, "외전 3"))
        assertFalse(matches(alts, "외전 가"))
        assertTrue(matches(HeadingRule.simple("제N장*"), "제3장 귀환"))
        // blank alternatives are dropped
        assertEquals(HeadingRule.simpleToRegex("a"), HeadingRule.simpleToRegex(" | a | "))
    }

    @Test
    fun otherCharactersAreLiteral() {
        val r = HeadingRule.simple("(N)+[a.b]")
        assertTrue(matches(r, "(5)+[a.b]"))
        assertFalse(matches(r, "(5)+[axb]"))
    }

    @Test
    fun regexShape() {
        assertEquals("^\\s*(?:\\Q<\\E\\s*\\d{1,5}\\s*\\Q>\\E)\\s*$", HeadingRule.simpleToRegex("< N >"))
        assertEquals("", HeadingRule.simpleToRegex("  | "))
    }

    @Test
    fun storedForms() {
        assertEquals("simple:< N >", HeadingRule.simple("  < N > "))
        assertEquals("", HeadingRule.simple("   "))
        assertTrue(HeadingRule.isSimple("simple:N화"))
        assertFalse(HeadingRule.isSimple("^제\\d+화"))
        assertEquals("N화", HeadingRule.simpleText("simple:N화"))
    }

    @Test
    fun compileAndLabel() {
        assertNull(HeadingRule.compile(""))
        assertNull(HeadingRule.compile("   "))
        assertNull(HeadingRule.compile("simple:"))
        assertNull(HeadingRule.compile("([bad"))
        assertNotNull(HeadingRule.compile("^제\\s*\\d+화"))
        assertEquals("기본 규칙만", HeadingRule.label(""))
        assertEquals("< N >", HeadingRule.label("simple:< N >"))
        assertEquals("^제\\d+화", HeadingRule.label("^제\\d+화"))
    }

    @Test
    fun reviewCases() {
        // a stray separator is no rule at all
        assertEquals("", HeadingRule.simple(" | "))
        // runs of '*' and spaces are one "anything": no stacked .* to backtrack through
        assertEquals("^\\s*(?:.*\\Q화\\E)\\s*$", HeadingRule.simpleToRegex("* * * 화"))
        assertTrue(matches("simple:* * * * *화", "어떤 소설 12화"))
        assertFalse(matches("simple:* * * * *화", "가".repeat(59) + "나"))
        assertTrue(HeadingRule.looksLikeRegex("^제\\d+화"))
        assertFalse(HeadingRule.looksLikeRegex("< N > | (N) *"))
        assertFalse(HeadingRule.looksLikeRegex("[N] *"))
        assertFalse(HeadingRule.looksLikeRegex("{N} *"))
        assertFalse(HeadingRule.looksLikeRegex("N.*"))
        assertTrue(HeadingRule.looksLikeRegex("제\\s*N"))
        assertTrue(matches(HeadingRule.simple("[N] *"), "[2] 마법을 만나다 (2)"))
        assertFalse(matches(HeadingRule.simple("[N] *"), "그는 [2]번 말했다"))
    }
}
