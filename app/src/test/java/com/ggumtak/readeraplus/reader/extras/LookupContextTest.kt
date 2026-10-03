package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupContextTest {
    private fun ctx(text: String, word: String, max: Int = LookupContext.MAX): String {
        val s = text.indexOf(word)
        return LookupContext.sentence(text, s, s + word.length, max)
    }

    @Test
    fun boundsAtTerminals() {
        val t = "첫 문장이다. 그는 비명을 질렀다! 왜 그랬을까? 끝…  다음"
        assertEquals("그는 비명을 질렀다!", ctx(t, "비명을"))
        assertEquals("왜 그랬을까?", ctx(t, "그랬을까"))
        assertEquals("첫 문장이다.", ctx(t, "첫"))
        assertEquals("끝…", ctx(t, "끝"))
        assertEquals("다음", ctx(t, "다음"))
        assertEquals("가나。", ctx("앞。 가나。 뒤", "가나"))
    }

    @Test
    fun closingQuotesStayWithTheSentence() {
        val t = "그가 말했다. “정말이야?” 그녀가 웃었다."
        assertEquals("“정말이야?”", ctx(t, "정말이야"))
        assertEquals("그녀가 웃었다.", ctx(t, "그녀가"))
    }

    @Test
    fun newlineIsABound_andNoSplitInsideNumbers() {
        assertEquals("둘째 줄 단어", ctx("첫 줄\n둘째 줄 단어\n셋째", "단어"))
        assertEquals("값은 3.5 정도다.", ctx("앞. 값은 3.5 정도다. 뒤", "정도"))
    }

    @Test
    fun selectionOverTwoSentences() {
        val t = "하나. 둘이다. 셋이다. 넷."
        val s = t.indexOf("둘")
        val e = t.indexOf("셋이다") + 2
        assertEquals("둘이다. 셋이다.", LookupContext.sentence(t, s, e))
        // A selection ending on its own terminal.
        val e2 = t.indexOf("둘이다.") + "둘이다.".length
        assertEquals("둘이다.", LookupContext.sentence(t, s, e2))
    }

    @Test
    fun longSentence_cutAtSpacesWithEllipsis() {
        val left = (1..60).joinToString(" ") { "앞말$it" }
        val right = (1..60).joinToString(" ") { "뒷말$it" }
        val t = "$left 표적 $right"
        val out = ctx(t, "표적")
        assertTrue(out, out.startsWith("…"))
        assertTrue(out, out.endsWith("…"))
        assertTrue(out, out.contains("표적"))
        assertTrue(out.length <= LookupContext.MAX)
        // Whole words only at the cuts.
        val words = out.trim('…').split(' ')
        assertTrue(words.first().startsWith("앞말"))
        assertTrue(words.last().startsWith("뒷말"))
        assertTrue(words.first().length > 2 && words.last().length > 2)
        val s = t.indexOf("표적")
        assertTrue(s - t.indexOf(words.first()) <= LookupContext.SIDE)
    }

    @Test
    fun objectCharRemoved_whitespaceCollapsed() {
        val t = "그림￼ 아래   글자가\t있다. 다음."
        assertEquals("그림 아래 글자가 있다.", ctx(t, "글자가"))
    }

    @Test
    fun clampedAndDegenerateInputs() {
        val t = "짧은 문장."
        assertEquals("짧은 문장.", LookupContext.sentence(t, -5, 100))
        assertEquals("짧은 문장.", LookupContext.sentence(t, 3, 1))
        assertEquals("", LookupContext.sentence("", 0, 0))
        assertEquals("", LookupContext.sentence("   ", 1, 2))
    }

    @Test
    fun maxCapsTheWhole() {
        val t = (1..100).joinToString(" ") { "낱말$it" }
        val s = t.indexOf("낱말50 ")
        val out = LookupContext.sentence(t, s, s + 4, max = 40)
        assertTrue(out, out.length <= 40)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun webSearchTemplate() {
        assertEquals("https://www.google.com/search?q=%EA%B0%80+b", WebSearchTemplate.url(null, " 가 b "))
        assertEquals("https://search.naver.com/search.naver?query=x", WebSearchTemplate.url("https://search.naver.com/search.naver?query=%s", "x"))
        assertEquals(WebSearchTemplate.DEFAULT, WebSearchTemplate.effective("no placeholder"))
        assertEquals("search.naver.com", WebSearchTemplate.host("https://search.naver.com/search.naver?query=%s"))
        assertEquals("www.google.com", WebSearchTemplate.host(""))
    }
}
