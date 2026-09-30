package com.ggumtak.readeraplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "다음 권 읽기" (T1-2): the series key, the same-series rule and the folder step, without IO. */
class NextPartTest {

    private fun key(name: String) = NextPart.seriesKey(name)

    private fun same(a: String, b: String) = NextPart.sameSeries(key(a), key(b))

    @Test
    fun specCases() {
        assertEquals("소설a", key("소설A 1-100화.txt"))
        assertEquals("소설a", key("소설A 101-200화 (완).txt"))
        assertTrue(same("소설A 1-100화.txt", "소설A 101-200화 (완).txt"))
        assertTrue(same("소설A 1권.txt", "소설A 2권.txt"))
        assertEquals("소설a", key("[작가] 소설A 3.txt"))
        assertTrue(same("[작가] 소설A 3.txt", "소설A 1-100화.txt"))
        assertEquals("소설b", key("소설B 1-50화.txt"))
        assertFalse(same("소설B 1-50화.txt", "소설A 1-100화.txt"))
    }

    @Test
    fun numberingStyles() {
        for (n in listOf(
            "소설A 1~100화.txt", "소설A 101 ~ 200.txt", "소설A 제1-50화.txt", "소설A 제3권.txt", "소설A 2부.txt",
            "소설A 4 편.txt", "소설A 7장.txt", "소설A_01.txt", "소설A - 02.txt", "소설A.3.txt", "소설A 1부 1권.txt",
            "소설A 1화 ~ 100화.txt", "소설A 【완결】.txt", "소설A 〈1부〉 1-100.txt", "소설A 1-100화 완결.txt",
            "소설A 101-200화 외전 포함.txt", "소설A 101-200화 외전포함 완.txt", "소설A 상.txt", "소설A 하권.txt",
            "소설A（완）.txt", "소설A 001–100.txt",
        )) {
            assertEquals(n, "소설a", key(n))
        }
    }

    @Test
    fun wordsThatMerelyContainTheMarkersStay() {
        // 완 / 하 inside a word are part of the title.
        assertEquals("완벽한 하루", key("완벽한 하루 1권.txt"))
        assertEquals("사랑하", key("사랑하 2.txt"))
        assertEquals("미완성 교향곡", key("미완성 교향곡 3.epub"))
    }

    @Test
    fun keysAreLowercaseAndSpaceCollapsed() {
        assertEquals("harry potter vol", key("Harry  Potter Vol. 2.epub"))
        assertTrue(same("Harry Potter Vol. 1.epub", "harry potter vol 3.EPUB"))
        assertEquals("the end", key("The_End 12.txt"))
    }

    @Test
    fun bracketOnlyNamesKeepTheirBrackets() {
        // Stripping the brackets would leave nothing: the bracketed words are the title.
        assertEquals("[소설a]", key("[소설A] 1.txt"))
        assertTrue(same("[소설A] 1.txt", "[소설A] 2.txt"))
        assertFalse(same("[소설A] 1.txt", "[소설B] 2.txt"))
    }

    @Test
    fun prefixRuleNeedsFourChars() {
        assertTrue(NextPart.sameSeries("나 혼자만 레벨업", "나 혼자만 레벨업 외전"))
        assertTrue(NextPart.sameSeries("나 혼자만 레벨업 외전", "나 혼자만 레벨업"))
        assertTrue(NextPart.sameSeries("abcd", "abcdef"))
        assertFalse(NextPart.sameSeries("소설a", "소설a 외전"))
        assertFalse(NextPart.sameSeries("abc", "abcdef"))
        assertFalse(NextPart.sameSeries("abcd", "abce"))
        assertTrue(NextPart.sameSeries("", ""))
        assertFalse(NextPart.sameSeries("", "소설a"))
    }

    private val folder = listOf(
        "소설A 101-200화.txt",
        "소설A 1-100화.txt",
        "소설A 201-300화 (완).txt",
        "소설B 1-50화.txt",
        "소설B 51-100화.txt",
        "소설A 101-200화.epub",
        "소설A 1-100화.epub",
        "메모.txt",
        ".소설A 150화.txt",
        "소설A 표지.jpg",
    )

    @Test
    fun pickNextWalksThePartsInOrder() {
        assertEquals("소설A 101-200화.txt", NextPart.pickNext("소설A 1-100화.txt", folder))
        assertEquals("소설A 201-300화 (완).txt", NextPart.pickNext("소설A 101-200화.txt", folder))
        assertNull(NextPart.pickNext("소설A 201-300화 (완).txt", folder))
        assertEquals("소설B 51-100화.txt", NextPart.pickNext("소설B 1-50화.txt", folder))
        assertNull(NextPart.pickNext("소설B 51-100화.txt", folder))
        assertNull(NextPart.pickNext("메모.txt", folder))
    }

    @Test
    fun pickNextKeepsTheFormatFamily() {
        assertEquals("소설A 101-200화.epub", NextPart.pickNext("소설A 1-100화.epub", folder))
        assertNull(NextPart.pickNext("소설A 101-200화.epub", folder))
        // Unknown current format: nothing to continue with.
        assertNull(NextPart.pickNext("소설A 1-100화.pdf", folder + "소설A 101-200화.pdf"))
    }

    @Test
    fun pickNextUsesNaturalOrder() {
        val names = listOf("소설 10권.txt", "소설 2권.txt", "소설 1권.txt", "소설 11권.txt")
        assertEquals("소설 2권.txt", NextPart.pickNext("소설 1권.txt", names))
        assertEquals("소설 10권.txt", NextPart.pickNext("소설 2권.txt", names))
        assertEquals("소설 11권.txt", NextPart.pickNext("소설 10권.txt", names))
        // Upper / lower volumes: 상 < 중 < 하 in code point order.
        val halves = listOf("소설 하.txt", "소설 상.txt", "소설 중.txt")
        assertEquals("소설 중.txt", NextPart.pickNext("소설 상.txt", halves))
        assertEquals("소설 하.txt", NextPart.pickNext("소설 중.txt", halves))
    }

    @Test
    fun pickNextInAMixedDownloadFolder() {
        val download = listOf("다른책 2권.txt", "소설A 1권.txt", "소설A 2권.txt", "소설Ab 3권.txt", "영수증.txt")
        assertEquals("소설A 2권.txt", NextPart.pickNext("소설A 1권.txt", download))
        assertNull(NextPart.pickNext("소설A 2권.txt", download))
        // The current file need not be listed (it may have been moved meanwhile).
        assertEquals("소설A 2권.txt", NextPart.pickNext("소설A 1권.txt", download - "소설A 1권.txt"))
        assertNull(NextPart.pickNext("소설A 1권.txt", emptyList()))
    }

    @Test
    fun theSideStoryFollowsTheMainPartsOfALongTitle() {
        val names = listOf("나 혼자만 레벨업 1-100화.txt", "나 혼자만 레벨업 101-270화 (완).txt", "나 혼자만 레벨업 외전.txt")
        assertEquals("나 혼자만 레벨업 101-270화 (완).txt", NextPart.pickNext("나 혼자만 레벨업 1-100화.txt", names))
        assertEquals("나 혼자만 레벨업 외전.txt", NextPart.pickNext("나 혼자만 레벨업 101-270화 (완).txt", names))
        assertNull(NextPart.pickNext("나 혼자만 레벨업 외전.txt", names))
    }
}
