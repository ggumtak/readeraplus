package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodesTest {

    // ------------------------------------------------------------------ EpisodeNumbers.parse

    @Test
    fun rule1EpisodeSuffix() {
        assertEquals(1, EpisodeNumbers.parse("1화"))
        assertEquals(123, EpisodeNumbers.parse("제123화 새로운 시작"))
        assertEquals(12, EpisodeNumbers.parse("제 12 화"))
        assertEquals(7, EpisodeNumbers.parse("7회"))
        assertEquals(88, EpisodeNumbers.parse("88話"))
        assertEquals(5, EpisodeNumbers.parse("소설 제목 5화"))
        // "1부" is not an episode; the episode number after it is.
        assertEquals(23, EpisodeNumbers.parse("1부 23화"))
        assertEquals(0, EpisodeNumbers.parse("0화 프롤로그"))
    }

    @Test
    fun rule1WinsOverLaterRules() {
        // Rule 1 is tried first even when a leading number (rule 4) comes earlier in the title.
        assertEquals(15, EpisodeNumbers.parse("3. 15화"))
        assertEquals(40, EpisodeNumbers.parse("Chapter 2 - 40화"))
    }

    @Test
    fun rule2Latin() {
        assertEquals(3, EpisodeNumbers.parse("Ep. 3"))
        assertEquals(12, EpisodeNumbers.parse("episode 12"))
        assertEquals(4, EpisodeNumbers.parse("CHAPTER 4: The Road"))
        assertEquals(9, EpisodeNumbers.parse("Ch.9"))
        assertEquals(31, EpisodeNumbers.parse("#31 마지막"))
        // "ep" at the end of a word is not the keyword; the digits then match nothing else.
        assertNull(EpisodeNumbers.parse("deep 3"))
        assertNull(EpisodeNumbers.parse("Epilogue"))
    }

    @Test
    fun rule3ChapterSuffixes() {
        assertEquals(2, EpisodeNumbers.parse("2장 그날"))
        assertEquals(10, EpisodeNumbers.parse("10편"))
        assertEquals(6, EpisodeNumbers.parse("第6章"))
    }

    @Test
    fun rule4LeadingNumber() {
        assertEquals(1, EpisodeNumbers.parse("001. 시작"))
        assertEquals(12, EpisodeNumbers.parse("[12] 비밀"))
        assertEquals(5, EpisodeNumbers.parse("  (5) 여름"))
        assertEquals(7, EpisodeNumbers.parse("【7】"))
        assertEquals(30, EpisodeNumbers.parse("《30》 끝"))
        // Only a leading number.
        assertNull(EpisodeNumbers.parse("그날 3시"))
    }

    @Test
    fun rule4SkipsVolumeAndPartNumbers() {
        assertNull(EpisodeNumbers.parse("2권"))
        assertNull(EpisodeNumbers.parse("1부 시작"))
        assertNull(EpisodeNumbers.parse("3 권"))
    }

    @Test
    fun numbersNeverStartOrEndInsideALongerNumber() {
        assertNull(EpisodeNumbers.parse("123456화"))
        assertNull(EpisodeNumbers.parse("ch123456"))
        assertNull(EpisodeNumbers.parse("123456 제목"))
        assertEquals(99999, EpisodeNumbers.parse("99999화"))
    }

    @Test
    fun noNumber() {
        assertNull(EpisodeNumbers.parse("프롤로그"))
        assertNull(EpisodeNumbers.parse("작가의 말"))
        assertNull(EpisodeNumbers.parse(""))
    }

    @Test
    fun specialTitles() {
        assertTrue(EpisodeNumbers.isSpecial("외전 1화"))
        assertTrue(EpisodeNumbers.isSpecial("번외편"))
        assertTrue(EpisodeNumbers.isSpecial("특별편 2"))
        assertTrue(EpisodeNumbers.isSpecial("완결 후기"))
        assertTrue(EpisodeNumbers.isSpecial("연재 공지"))
        assertFalse(EpisodeNumbers.isSpecial("12화 후회"))
        assertFalse(EpisodeNumbers.isSpecial("프롤로그"))
    }

    // ------------------------------------------------------------------ Episodes

    private fun eps(vararg titles: String) = Episodes.of(titles.toList())

    private fun serial(from: Int, to: Int, except: Set<Int> = emptySet()): List<String> =
        (from..to).filter { it !in except }.map { "${it}화" }

    @Test
    fun countsAndMax() {
        val e = eps("프롤로그", "1화", "2화", "작가의 말", "3화")
        assertEquals(5, e.size)
        assertEquals(3, e.parsedCount)
        assertEquals(3, e.maxNumber)
        assertEquals(1, e.minNumber)
        assertEquals(listOf(-1, 1, 2, -1, 3), e.numbers.toList())
    }

    @Test
    fun maxNumberIgnoresSpecialEntries() {
        val e = eps("1화", "2화", "3화", "2024 공지")
        assertEquals(3, e.maxNumber)
        // Only special entries numbered: they count.
        assertEquals(9, eps("외전 3화", "외전 9화").maxNumber)
        assertEquals(-1, eps("프롤로그", "에필로그").maxNumber)
    }

    @Test
    fun findExactThenNextHigher() {
        val e = Episodes.of(serial(1, 100, except = setOf(57, 58)))
        assertEquals(0, e.find(1))
        assertEquals(55, e.find(56))
        // 57 is missing: the next higher number, 59 (TOC index 56).
        assertEquals(56, e.find(57))
        assertEquals(59, e.numbers[e.find(57)])
        assertEquals(-1, e.find(101))
        assertEquals(-1, e.find(-3))
        // Below the smallest: the smallest.
        assertEquals(0, Episodes.of(serial(10, 20)).find(1))
    }

    @Test
    fun findPrefersTheFirstEntryAndRegularOnes() {
        val e = eps("외전 3화", "1화", "2화", "3화", "3화", "4화")
        assertEquals(3, e.find(3))
        // Only special entries numbered: they are searched.
        val onlySpecial = eps("외전 1화", "외전 2화")
        assertEquals(1, onlySpecial.find(2))
    }

    @Test
    fun gapsAndDupes() {
        val titles = serial(1, 130, except = setOf(57, 120, 121)).toMutableList()
        titles.add(88, "88화")
        titles.add("외전 200화")
        titles.add("작가 후기")
        val e = Episodes.of(titles)
        assertTrue(e.confident)
        assertEquals(listOf(57, 120, 121), e.gaps())
        assertEquals(mapOf(88 to 2), e.dupes())
    }

    @Test
    fun specialEntriesAreNotDupesOrGaps() {
        val e = eps("1화", "2화", "외전 2화", "3화", "번외 10화")
        assertTrue(e.gaps().isEmpty())
        assertTrue(e.dupes().isEmpty())
    }

    @Test
    fun dupesAscendingWithCounts() {
        val e = eps("5화", "3화", "5화", "3화", "4화", "5화")
        assertEquals(listOf(3 to 2, 5 to 3), e.dupes().toList())
    }

    @Test
    fun confidenceNeeds70PercentParsed() {
        // 7 of 10 parse: confident.
        val seven = Episodes.of(serial(1, 7) + listOf("a", "b", "c"))
        assertTrue(seven.confident)
        // 6 of 10: not.
        val six = Episodes.of(serial(1, 6) + listOf("a", "b", "c", "d"))
        assertFalse(six.confident)
    }

    @Test
    fun confidenceNeedsACompactRange() {
        // 10 entries spanning 1..50: max - min = 49 <= 5 × 10.
        assertTrue(Episodes.of((1..10).map { "${it * 5}화" } + "1화").confident)
        // 3 entries spanning 1..1000: garbage.
        assertFalse(eps("1화", "500화", "1000화").confident)
        assertFalse(eps("프롤로그").confident)
        assertFalse(Episodes.of(emptyList()).confident)
    }

    @Test
    fun usableForJump() {
        assertTrue(eps("1화", "2화").usableForJump)
        assertTrue(eps("1화", "작가의 말").usableForJump)
        assertFalse(eps("1화", "작가의 말", "공지").usableForJump)
        assertFalse(eps("1화").usableForJump)
        assertFalse(Episodes.of(emptyList()).usableForJump)
    }

    @Test
    fun numberAtLooksBackToTheLastRegularEpisode() {
        val e = eps("프롤로그", "1화", "2화", "작가의 말", "외전 7화", "3화")
        assertEquals(-1, e.numberAt(0))
        assertEquals(1, e.numberAt(1))
        assertEquals(2, e.numberAt(3))
        assertEquals(2, e.numberAt(4))
        assertEquals(3, e.numberAt(5))
        assertEquals(3, e.numberAt(99))
        assertEquals(-1, e.numberAt(-1))
    }

    @Test
    fun largeTocParsesQuickly() {
        val titles = (1..2000).map { "제${it}화 어느 날의 이야기" }
        val t0 = System.nanoTime()
        val e = Episodes.of(titles)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(2000, e.parsedCount)
        assertTrue(e.confident)
        assertTrue(e.gaps().isEmpty())
        assertTrue("2,000 titles took $ms ms", ms < 500)
    }
}
