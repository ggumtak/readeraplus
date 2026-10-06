package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupQueryTest {
    @Test
    fun queryTrimsCollapsesAndAppendsMeaning() {
        assertEquals("비명 뜻", LookupQuery.query("  비명 "))
        assertEquals("가 나 다 뜻", LookupQuery.query("가\n\n 나\t 다　"))
        assertEquals("", LookupQuery.query(" \n\t "))
        assertEquals("", LookupQuery.query(""))
    }

    @Test
    fun queryIsCappedAt100Chars() {
        val q = LookupQuery.query("가".repeat(300))
        assertEquals(100 + LookupQuery.SUFFIX.length, q.length)
        assertTrue(q.endsWith(" 뜻"))
        // The cut never leaves a space before the suffix, nor half a surrogate pair.
        assertEquals("가".repeat(99) + " 뜻", LookupQuery.query("가".repeat(99) + " " + "나".repeat(50)))
        val emoji = "😀"
        assertEquals("a" + emoji.repeat(49) + " 뜻", LookupQuery.query("a" + emoji.repeat(60)))
    }

    @Test
    fun urlEncodesSpacesAsPercent20() {
        assertEquals(
            "https://m.search.naver.com/search.naver?query=%EB%B9%84%EB%AA%85%20%EB%9C%BB",
            LookupQuery.naverUrl(LookupQuery.query("비명")),
        )
        assertEquals(
            "https://m.search.naver.com/search.naver?query=a%2Bb%26c%20%EB%9C%BB",
            LookupQuery.naverUrl(LookupQuery.query("a+b&c")),
        )
    }

    @Test
    fun naverFirstUnlessAnotherEntryWasUsedLast() {
        val apps = listOf("a/A", "b/B")
        assertEquals(listOf("naver", "a/A", "b/B"), LookupQuery.order(apps, null))
        assertEquals(listOf("naver", "a/A", "b/B"), LookupQuery.order(apps, "naver"))
        assertEquals(listOf("naver", "a/A", "b/B"), LookupQuery.order(apps, "gone/App"))
        assertEquals(listOf("b/B", "naver", "a/A"), LookupQuery.order(apps, "b/B"))
        assertEquals(listOf("naver"), LookupQuery.order(emptyList(), null))
    }

    @Test
    fun heightIsClampedToThirtyToNinetyPercent() {
        assertEquals(1200, LookupQuery.startHeight(2000))
        assertEquals(600, LookupQuery.clampHeight(100, 2000))
        assertEquals(1800, LookupQuery.clampHeight(5000, 2000))
        assertEquals(1000, LookupQuery.clampHeight(1000, 2000))
    }
}
