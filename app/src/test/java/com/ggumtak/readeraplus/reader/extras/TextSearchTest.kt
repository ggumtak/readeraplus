package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    private val text = "봄비가 내리는 아침이었다. 창가의 화분이 조용히 젖어 갔다.\n" +
        "Hello world, HELLO again. 그녀는 hello라고 속삭였다.\n" +
        "아침 햇살이 다시 비추자 아침 식사가 준비되었다."

    private fun all(t: String, q: String, from: Int = 0): List<Int> {
        val out = ArrayList<Int>()
        TextSearch.scan(t, q, from) { out += it; true }
        return out
    }

    @Test
    fun findsKoreanOccurrences() {
        val hits = all(text, "아침")
        assertEquals(3, hits.size)
        for (h in hits) assertEquals("아침", text.substring(h, h + 2))
    }

    @Test
    fun latinIsCaseInsensitive() {
        val hits = all(text, "hello")
        assertEquals(3, hits.size)
        assertEquals("Hello", text.substring(hits[0], hits[0] + 5))
        assertEquals("HELLO", text.substring(hits[1], hits[1] + 5))
        assertEquals("hello", text.substring(hits[2], hits[2] + 5))
        assertEquals(3, all(text, "HeLLo").size)
    }

    @Test
    fun nonOverlappingAndFrom() {
        assertEquals(listOf(0, 2), all("aaaa", "aa"))
        assertEquals(listOf(1), all("aaaa", "aa", from = 1).take(1))
        assertEquals(emptyList<Int>(), all("abc", ""))
        assertEquals(emptyList<Int>(), all("ab", "abc"))
        assertEquals(listOf(0), all("abc", "abc"))
    }

    @Test
    fun stopsEarly() {
        var n = 0
        val count = TextSearch.scan(text, "아침") { n++; n < 2 }
        assertEquals(2, count)
        assertEquals(2, n)
    }

    @Test
    fun snippetMarksHitAndCleansBreaks() {
        val start = text.indexOf("HELLO")
        val sn = TextSearch.snippet(text, start, start + 5, 10)
        assertEquals("HELLO", sn.text.substring(sn.hitStart, sn.hitEnd))
        assertTrue(sn.text.startsWith("…"))
        assertTrue(sn.text.endsWith("…"))
        assertTrue(!sn.text.contains('\n'))
    }

    @Test
    fun snippetAtEdges() {
        val sn = TextSearch.snippet("짧은 글", 0, 2, 30)
        assertEquals("짧은 글", sn.text)
        assertEquals(0, sn.hitStart)
        assertEquals(2, sn.hitEnd)
        val t = "줄 하나\n두 번째 줄"
        val s2 = TextSearch.snippet(t, t.length - 1, t.length, 30)
        assertEquals("줄 하나 두 번째 줄", s2.text)
        assertEquals("줄", s2.text.substring(s2.hitStart, s2.hitEnd))
    }

    @Test
    fun snippetDoesNotSplitSurrogates() {
        val t = "x😀abcdefgh" // x=0, emoji=1..2, a=3, b=4, c=5
        // radius 3 from offset 5 would start at 2, the low half of the emoji pair
        val sn = TextSearch.snippet(t, 5, 6, 3)
        assertTrue(sn.text.none { Character.isLowSurrogate(it) })
        assertEquals("c", sn.text.substring(sn.hitStart, sn.hitEnd))
        assertTrue(sn.text.startsWith("…a"))
        // ...and keeps the pair whole when the cut is at its end
        val t2 = "abcdefg😀xyz"
        val s2 = TextSearch.snippet(t2, 5, 6, 2)
        assertTrue(s2.text.none { Character.isHighSurrogate(it) })
        assertEquals("f", s2.text.substring(s2.hitStart, s2.hitEnd))
    }

    @Test
    fun scanSpeedSmoke() {
        val sb = StringBuilder()
        repeat(40_000) { sb.append("가나다라마바사 아자차카타파하 ") }
        val big = sb.toString() + "찾을말"
        val t0 = System.nanoTime()
        val hits = all(big, "찾을말")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1, hits.size)
        assertTrue("scan too slow: $ms ms", ms < 1000)
    }
}
