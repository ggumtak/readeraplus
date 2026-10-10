package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSearchQueryTest {
    // ---- the cleaned query of the web tabs

    @Test
    fun webQueryDropsTrailingSentencePunctuation() {
        assertEquals("않은가", WordSearchQuery.webQuery("않은가."))
        assertEquals("말이다", WordSearchQuery.webQuery("말이다?!"))
        assertEquals("Hello, World", WordSearchQuery.webQuery("Hello, World!?"))
        assertEquals("그랬다", WordSearchQuery.webQuery("…그랬다…"))
        assertEquals("그랬다", WordSearchQuery.webQuery("...그랬다..."))
        assertEquals("Dr", WordSearchQuery.webQuery("Dr."))
    }

    @Test
    fun webQueryDropsSurroundingQuotesAndBrackets() {
        assertEquals("말이다", WordSearchQuery.webQuery("“말이다.”"))
        assertEquals("단어", WordSearchQuery.webQuery("  '단어'  "))
        assertEquals("hello", WordSearchQuery.webQuery("\"hello,\""))
        assertEquals("안녕", WordSearchQuery.webQuery("「안녕」"))
        assertEquals("안녕", WordSearchQuery.webQuery("『안녕』"))
        assertEquals("말이다", WordSearchQuery.webQuery("(말이다)."))
        assertEquals("말이다", WordSearchQuery.webQuery("‘(말이다)’"))
    }

    @Test
    fun webQueryKeepsBracketsThatBelongToTheText() {
        assertEquals("(주)한국", WordSearchQuery.webQuery("(주)한국"))
        assertEquals("f(x)", WordSearchQuery.webQuery("f(x)"))
        assertEquals("(주)한국(주)", WordSearchQuery.webQuery("(주)한국(주)"))
        assertEquals("C++", WordSearchQuery.webQuery("C++"))
        assertEquals("don't", WordSearchQuery.webQuery("don't"))
    }

    @Test
    fun webQueryRemovesAnUnmatchedBracket() {
        assertEquals("말이다", WordSearchQuery.webQuery("말이다)"))
        assertEquals("말이다", WordSearchQuery.webQuery("(말이다"))
        assertEquals("f(x)", WordSearchQuery.webQuery("f(x))"))
    }

    @Test
    fun webQueryCollapsesWhitespaceAndMayBeEmpty() {
        assertEquals("a b c", WordSearchQuery.webQuery("  a  b\nc  "))
        assertEquals("가 나", WordSearchQuery.webQuery("가　나"))
        assertEquals("", WordSearchQuery.webQuery(""))
        assertEquals("", WordSearchQuery.webQuery("   "))
        assertEquals("", WordSearchQuery.webQuery("..."))
        assertEquals("", WordSearchQuery.webQuery("“”"))
        assertEquals("", WordSearchQuery.webQuery("\""))
        assertEquals("", WordSearchQuery.webQuery("?!"))
    }

    @Test
    fun webQueryIsCappedAndNeverEndsInHalfASurrogatePair() {
        assertEquals(WordSearchQuery.WEB_MAX, WordSearchQuery.webQuery("가".repeat(300)).length)
        val q = WordSearchQuery.webQuery("a" + "😀".repeat(80))
        assertFalse(Character.isHighSurrogate(q.last()))
    }

    @Test
    fun initialIsTheSelectionTrimmedOnOneLine() {
        assertEquals("가 나", WordSearchQuery.initial("  가\n\n나  "))
        assertEquals("", WordSearchQuery.initial(" \n "))
        assertEquals("“말이다.”", WordSearchQuery.initial("“말이다.”"))
    }

    // ---- addresses

    @Test
    fun urlsOfTheWebTabs() {
        val q = "말이다"
        val enc = "%EB%A7%90%EC%9D%B4%EB%8B%A4"
        assertEquals("https://ko.dict.naver.com/#/search?query=$enc", WordSearchQuery.url(WordSearchQuery.TAB_KO, q))
        assertEquals("https://en.dict.naver.com/#/search?query=$enc", WordSearchQuery.url(WordSearchQuery.TAB_EN, q))
        assertEquals("https://ko.m.wikipedia.org/w/index.php?search=$enc", WordSearchQuery.url(WordSearchQuery.TAB_WIKI, q))
        assertEquals("", WordSearchQuery.url(WordSearchQuery.TAB_BODY, q))
    }

    @Test
    fun urlEncodesSpacesAsPercent20AndReservedChars() {
        assertEquals(
            "https://en.dict.naver.com/#/search?query=a%20b%26c%3Dd",
            WordSearchQuery.url(WordSearchQuery.TAB_EN, "a b&c=d"),
        )
    }

    @Test
    fun tabs() {
        assertEquals(WordSearchQuery.TAB_KO, WordSearchQuery.DEFAULT_TAB)
        assertFalse(WordSearchQuery.isWeb(WordSearchQuery.TAB_BODY))
        assertTrue(WordSearchQuery.isWeb(WordSearchQuery.TAB_KO))
        assertTrue(WordSearchQuery.isWeb(WordSearchQuery.TAB_EN))
        assertTrue(WordSearchQuery.isWeb(WordSearchQuery.TAB_WIKI))
        assertFalse(WordSearchQuery.isWeb(-1))
        assertEquals(listOf("본문", "국어사전", "영어사전", "백과사전"), (0 until WordSearchQuery.TAB_COUNT).map { WordSearchQuery.title(it) })
    }

    // ---- 본문 rows

    @Test
    fun snippetMarksTheMatchInsideItsContext() {
        val text = "가".repeat(40) + "등불" + "나".repeat(100)
        val sn = WordSearchQuery.snippet(text, 40, 42)
        assertEquals("등불", sn.text.substring(sn.hitStart, sn.hitEnd))
        assertTrue(sn.text.startsWith("…"))
        assertTrue(sn.text.endsWith("…"))
        // 12 chars before the match, 60 after it, plus the two marks.
        assertEquals(1 + WordSearchQuery.SNIPPET_BEFORE + 2 + WordSearchQuery.SNIPPET_AFTER + 1, sn.text.length)
    }

    @Test
    fun snippetAtTheEdgesHasNoMarks() {
        val sn = WordSearchQuery.snippet("등불이 켜졌다", 0, 2)
        assertEquals("등불이 켜졌다", sn.text)
        assertEquals(0, sn.hitStart)
        assertEquals(2, sn.hitEnd)
        val end = WordSearchQuery.snippet("그는 등불", 3, 5)
        assertEquals("그는 등불", end.text)
        assertEquals("등불", end.text.substring(end.hitStart, end.hitEnd))
    }

    @Test
    fun snippetCollapsesLineBreaksAndKeepsTheHitOffsetsRight() {
        val text = "앞 줄\n\n\n  다음   줄에 등불이 있다\n끝"
        val start = text.indexOf("등불")
        val sn = WordSearchQuery.snippet(text, start, start + 2, before = 100, after = 100)
        assertEquals("앞 줄 다음 줄에 등불이 있다 끝", sn.text)
        assertEquals("등불", sn.text.substring(sn.hitStart, sn.hitEnd))
    }

    @Test
    fun snippetNeverSplitsASurrogatePair() {
        val text = "😀".repeat(30) + "hit" + "😀".repeat(60)
        val start = text.indexOf("hit")
        val sn = WordSearchQuery.snippet(text, start, start + 3, before = 11, after = 11)
        assertEquals("hit", sn.text.substring(sn.hitStart, sn.hitEnd))
        // 11 chars before an odd start would cut a pair: the cut moves one on, the mark and the whole pairs remain.
        for (i in sn.text.indices) {
            val c = sn.text[i]
            if (Character.isHighSurrogate(c)) assertTrue(Character.isLowSurrogate(sn.text[i + 1]))
            if (Character.isLowSurrogate(c)) assertTrue(Character.isHighSurrogate(sn.text[i - 1]))
        }
    }

    @Test
    fun percentFollowsTheFooterOncePagesAreCountedElseTheCharacterShare() {
        // Page 50 of 200: 25% as the footer reads it, whatever the character share says.
        assertEquals(25, WordSearchQuery.percent(50, 200, true, 0.9f))
        // The last page reads 100.
        assertEquals(100, WordSearchQuery.percent(200, 200, true, 0.5f))
        // Pages not counted: the character share, floored.
        assertEquals(34, WordSearchQuery.percent(50, 200, false, 0.349f))
        // A page label that did not parse falls back to the share too.
        assertEquals(12, WordSearchQuery.percent(-1, -1, true, 0.12f))
        assertEquals(0, WordSearchQuery.percent(-1, -1, false, Float.NaN))
        assertEquals(100, WordSearchQuery.percent(0, 0, false, 1.5f))
    }

    @Test
    fun metaLineShowsPercentAndPage() {
        assertEquals("34% | 128 페이지", WordSearchQuery.meta(34, "128"))
        assertEquals("0% | 1 페이지", WordSearchQuery.meta(0, " 1 "))
        // No page while the pages are counted: just the percent, never "- 페이지".
        assertEquals("34%", WordSearchQuery.meta(34, ""))
        assertEquals("34%", WordSearchQuery.meta(34, "-"))
        assertEquals("34%", WordSearchQuery.meta(34, null))
    }

    @Test
    fun bodyMessages() {
        assertEquals("검색어를 입력하세요", WordSearchQuery.bodyMessage(false, true, 0, true))
        assertEquals("책을 여는 중입니다", WordSearchQuery.bodyMessage(true, false, 0, false))
        assertEquals("검색 중…", WordSearchQuery.bodyMessage(true, true, 0, false))
        assertEquals("검색 결과가 없습니다", WordSearchQuery.bodyMessage(true, true, 0, true))
        assertNull(WordSearchQuery.bodyMessage(true, true, 3, false))
        assertNull(WordSearchQuery.bodyMessage(true, true, 3, true))
    }

    @Test
    fun bodyNote() {
        assertEquals("검색 중… · 8개", WordSearchQuery.bodyNote(8, false, false, 1000))
        assertEquals("", WordSearchQuery.bodyNote(0, false, false, 1000))
        assertEquals("", WordSearchQuery.bodyNote(8, true, false, 1000))
        assertEquals("1000개 이상 (앞 1000개만 표시)", WordSearchQuery.bodyNote(1000, true, true, 1000))
    }

    @Test
    fun webMessages() {
        assertEquals("검색어가 없습니다", WordSearchQuery.webMessage("", false))
        assertEquals("웹 창을 열 수 없어 브라우저로 열었습니다", WordSearchQuery.webMessage("말이다", true))
        assertNull(WordSearchQuery.webMessage("말이다", false))
    }

    // ---- which tab shows the current query

    @Test
    fun tabsLoadLazilyAndAgainAfterANewQuery() {
        val t = WordSearchTabs(WordSearchQuery.TAB_COUNT)
        // Nothing shown the first query yet.
        assertTrue(t.needsLoad(WordSearchQuery.TAB_KO))
        t.markLoaded(WordSearchQuery.TAB_KO)
        assertFalse(t.needsLoad(WordSearchQuery.TAB_KO))
        // The other tabs are still waiting for their first show.
        assertTrue(t.needsLoad(WordSearchQuery.TAB_EN))
        assertTrue(t.needsLoad(WordSearchQuery.TAB_BODY))
        t.markLoaded(WordSearchQuery.TAB_EN)
        // A new query: every tab is stale, each loads when it is next shown.
        t.invalidate()
        assertTrue(t.needsLoad(WordSearchQuery.TAB_KO))
        assertTrue(t.needsLoad(WordSearchQuery.TAB_EN))
        t.markLoaded(WordSearchQuery.TAB_KO)
        assertFalse(t.needsLoad(WordSearchQuery.TAB_KO))
        assertTrue(t.needsLoad(WordSearchQuery.TAB_EN))
        // The same query searched again also reloads the shown tab.
        t.invalidate()
        assertTrue(t.needsLoad(WordSearchQuery.TAB_KO))
    }

    @Test
    fun tabsIgnoreOutOfRangeIndexes() {
        val t = WordSearchTabs(2)
        assertFalse(t.needsLoad(-1))
        assertFalse(t.needsLoad(2))
        t.markLoaded(5)
        t.markLoaded(-1)
    }
}
