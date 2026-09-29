package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.format.ParseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TxtSectionsTest {
    private val T = TxtParser.TARGET_CHARS

    private fun contents(p: TxtParser.Parsed) = TxtTestUtil.sections(p)

    private fun checkAll(p: TxtParser.Parsed, where: String) {
        val cs = contents(p)
        for ((i, c) in cs.withIndex()) {
            TxtTestUtil.assertInvariants(c, "$where section $i")
            assertEquals("$where section $i exact length", p.chars[i], c.length)
        }
        // byte ranges are contiguous, ascending and never overlap
        for (i in 0 until p.sectionCount) {
            assertTrue(p.byteStart[i] <= p.byteEnd[i])
            if (i > 0) assertEquals(p.byteEnd[i - 1], p.byteStart[i])
        }
    }

    @Test
    fun chapterlessTextIsChunked() {
        val r = Random(21)
        val text = TxtTestUtil.body(r, 200_000)
        val p = TxtTestUtil.parse(text)
        checkAll(p, "plain")
        assertTrue(p.sectionCount in 6..9)
        for (i in 0 until p.sectionCount) {
            assertNull(p.titles[i])
            assertEquals(0, p.flags[i] and TxtIndex.CHAPTER)
            if (i < p.sectionCount - 1) assertTrue("chunk $i = ${p.chars[i]}", p.chars[i] in T * 7 / 10..T * 13 / 10 + 500)
        }
        assertTrue(p.chars.last() <= T * 3 / 2 + 500)
        // all text survives
        assertEquals(text.length, contents(p).sumOf { it.length } + p.sectionCount - 1)
    }

    @Test
    fun smallPlainFileIsOneSection() {
        val p = TxtTestUtil.parse(TxtTestUtil.body(Random(22), 40_000))
        assertEquals(1, p.sectionCount)
    }

    @Test
    fun chunksPreferSceneBreaks() {
        val r = Random(23)
        val text = TxtTestUtil.body(r, 26_000) + "\n\n* * *\n\n" + TxtTestUtil.body(r, 60_000)
        val p = TxtTestUtil.parse(text, ParseOptions(txtDetectChapters = false))
        checkAll(p, "scene")
        val first = contents(p)[0]
        assertTrue("first chunk ends with the scene break", first.text.endsWith("* * *"))
        val second = contents(p)[1]
        assertTrue(!second.text.startsWith("* * *"))
    }

    @Test
    fun longChapterIsSplitAndTitledOnce() {
        val r = Random(24)
        val text = "제1화 긴 장\n" + TxtTestUtil.body(r, 150_000) + "\n제2화 짧은 장\n" + TxtTestUtil.body(r, 5_000)
        val p = TxtTestUtil.parse(text)
        checkAll(p, "long")
        assertEquals("제1화 긴 장", p.titles[0])
        var k = 1
        while (p.flags[k] and TxtIndex.CHAPTER == 0) {
            assertNull(p.titles[k])
            assertTrue(p.chars[k] <= T * 3 / 2 + 500)
            k++
        }
        assertTrue("split into ${k} chunks", k in 4..6)
        assertEquals("제2화 짧은 장", p.titles[k])
        assertEquals(k + 1, p.sectionCount)
    }

    @Test
    fun chapterBelowLimitNotSplit() {
        val r = Random(25)
        val text = "1화\n" + TxtTestUtil.body(r, 55_000) + "\n2화\n" + TxtTestUtil.body(r, 1000)
        val p = TxtTestUtil.parse(text)
        assertEquals(2, p.sectionCount)
    }

    @Test
    fun emptyAndBlankFiles() {
        for (text in listOf("", "\n\n\n", "   \n\t\n", "﻿")) {
            val p = TxtTestUtil.parse(text)
            assertEquals(1, p.sectionCount)
            assertEquals("", p.buildSection(0, true).text)
            assertEquals(0, p.chars[0])
        }
        val p = TxtTestUtil.parseBytes(ByteArray(0))
        assertEquals(1, p.sectionCount)
    }

    @Test
    fun giantLineSectionsCarryContinuationFlags() {
        val r = Random(26)
        val giant = TxtTestUtil.body(r, 120_000, " ")
        val p = TxtTestUtil.parse("시작.\n$giant\n끝.", ParseOptions(txtDetectChapters = false))
        checkAll(p, "giant")
        assertTrue(p.sectionCount >= 3)
        for (i in 1 until p.sectionCount) {
            if (p.flags[i] and TxtIndex.STARTS_CONT != 0) {
                assertTrue(p.flags[i - 1] and TxtIndex.ENDS_SEG != 0)
            }
        }
        assertTrue((1 until p.sectionCount).any { p.flags[it] and TxtIndex.STARTS_CONT != 0 })
    }

    @Test
    fun headingOnlySectionsAndAdjacentHeadings() {
        val r = Random(27)
        val text = "제1부\n제1화 시작\n" + TxtTestUtil.body(r, 2000) + "\n제2화 끝\n" + TxtTestUtil.body(r, 2000)
        val p = TxtTestUtil.parse(text)
        checkAll(p, "adjacent")
        assertEquals(listOf("제1부", "제1화 시작", "제2화 끝"), (0 until p.sectionCount).map { p.titles[it] })
        assertEquals("제1부", p.buildSection(0, true).text)
    }
}
