package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    private fun sentences(t: String, from: Int = 0, max: Int = SentenceSplitter.MAX_CHUNK): List<String> {
        val r = SentenceSplitter.split(t, from, t.length, max)
        return (r.indices step 2).map { t.substring(r[it], r[it + 1]) }
    }

    @Test
    fun splitsAtTerminalPunctuationFollowedBySpace() {
        val t = "고양이가 창밖을 보았다. 새가 날아갔다! 정말일까? 그렇다…"
        assertEquals(listOf("고양이가 창밖을 보았다.", "새가 날아갔다!", "정말일까?", "그렇다…"), sentences(t))
    }

    @Test
    fun keepsClosingQuotesWithTheSentence() {
        val t = "\"오늘은 비가 오네요.\" 그가 말했다. “정말요?” 나는 되물었다."
        assertEquals(listOf("\"오늘은 비가 오네요.\"", "그가 말했다.", "“정말요?”", "나는 되물었다."), sentences(t))
    }

    @Test
    fun doesNotSplitInsideWordsOrNumbers() {
        val t = "원주율은 3.14이다. 파일명은 note.txt였다. \"뭐?\"라고 물었다."
        assertEquals(listOf("원주율은 3.14이다.", "파일명은 note.txt였다.", "\"뭐?\"라고 물었다."), sentences(t))
    }

    @Test
    fun newlineAlwaysEndsASentence() {
        val t = "첫 문단은 마침표가 없다\n  둘째 문단.\n\n셋째"
        assertEquals(listOf("첫 문단은 마침표가 없다", "둘째 문단.", "셋째"), sentences(t))
    }

    @Test
    fun skipsRangesWithoutLettersOrDigits() {
        val t = "앞 장면.\n* * *\n￼\n뒤 장면."
        assertEquals(listOf("앞 장면.", "뒤 장면."), sentences(t))
    }

    @Test
    fun ellipsisRunsStayTogether() {
        val t = "아... 저... 그게요."
        assertEquals(listOf("아...", "저...", "그게요."), sentences(t))
    }

    @Test
    fun longSentencesAreChunkedAtSpaces() {
        val sb = StringBuilder()
        repeat(120) { sb.append("단어").append(it).append(' ') }
        val t = sb.toString().trim() + "."
        val parts = sentences(t, max = 100)
        assertTrue(parts.size >= 5)
        for (p in parts) {
            assertTrue("chunk too long: ${p.length}", p.length <= 100)
            assertTrue(p == p.trim())
        }
        // Chunks cover every word exactly once, in order.
        assertEquals(t, parts.joinToString(" "))
    }

    @Test
    fun hardCutWhenNoSpaces() {
        val t = "가".repeat(250)
        val parts = sentences(t, max = 100)
        assertEquals(listOf(100, 100, 50), parts.map { it.length })
    }

    @Test
    fun rangesAreOrderedAndInsideBounds() {
        val t = "하나. 둘! 셋?\n넷… 다섯。 여섯"
        val from = 3
        val r = SentenceSplitter.split(t, from)
        var prevEnd = from
        for (i in r.indices step 2) {
            assertTrue(r[i] >= prevEnd)
            assertTrue(r[i + 1] > r[i])
            assertTrue(r[i + 1] <= t.length)
            prevEnd = r[i + 1]
        }
        assertEquals("둘!", t.substring(r[0], r[1]))
    }

    @Test
    fun emptyAndWhitespace() {
        assertEquals(0, SentenceSplitter.split("").size)
        assertEquals(0, SentenceSplitter.split("   \n\n  ").size)
    }

    @Test
    fun utteranceIdRoundTrip() {
        val id = UtteranceId.make(12, 3400, 3456, 77, 5)
        assertEquals("12:3400:3456:77:5", id)
        val p = UtteranceId.parse(id)!!
        assertEquals(listOf(12, 3400, 3456, 77, 5), p.toList())
        assertEquals(null, UtteranceId.parse(null))
        assertEquals(null, UtteranceId.parse("1:2:3:4"))
        assertEquals(null, UtteranceId.parse("1:2:3:4:5:6"))
        assertEquals(null, UtteranceId.parse("a:2:3:4:5"))
        assertEquals(null, UtteranceId.parse("1::3:4:5"))
        assertEquals(null, UtteranceId.parse("other-utterance"))
    }
}
