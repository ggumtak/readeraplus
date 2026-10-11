package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterTicksTest {
    private fun place(starts: IntArray, max: Int, width: Int = 300, gap: Int = 6): FloatArray {
        val out = FloatArray(starts.size)
        val n = ChapterTicks.place(starts, max, width, gap, out)
        return out.copyOf(n)
    }

    @Test
    fun marksSitWhereTheThumbStandsOnThatPage() {
        // 101 pages: progress 0..100; chapters start on pages 25 and 50 (0-based)
        val xs = place(intArrayOf(0, 25, 50), max = 100)
        assertEquals(2, xs.size)
        assertEquals(75f, xs[0], 0.001f)
        assertEquals(150f, xs[1], 0.001f)
    }

    @Test
    fun bookStartDuplicatesAndOutOfRangeGetNoMark() {
        val xs = place(intArrayOf(50, 0, 50, 200, -3, 10), max = 100)
        assertEquals(listOf(30f, 150f), xs.toList())
    }

    @Test
    fun lastPageCanStartAChapter() {
        assertEquals(listOf(300f), place(intArrayOf(100), max = 100).toList())
    }

    @Test
    fun tooManyChaptersDrawNothing() {
        // 300 px / 6 px = 50 marks at most; 60 evenly spread chapters would make a ruler
        val starts = IntArray(60) { (it + 1) * 10 }
        assertEquals(0, place(starts, max = 1000).size)
        // 50 fit
        assertEquals(50, place(IntArray(50) { (it + 1) * 10 }, max = 1000).size)
    }

    @Test
    fun marksTooCloseToThePreviousAreDropped() {
        // 1 page apart on a 1000-page book is 0.3 px: below a third of the 6 px gap
        val xs = place(intArrayOf(500, 501, 700), max = 1000)
        assertEquals(listOf(150f, 210f), xs.toList())
    }

    @Test
    fun degenerateInputs() {
        assertEquals(0, place(intArrayOf(), max = 100).size)
        assertEquals(0, place(intArrayOf(1), max = 0).size)
        assertEquals(0, place(intArrayOf(1), max = 100, width = 0).size)
        assertEquals(0, place(intArrayOf(0), max = 100).size)
    }

    @Test
    fun moreThanAHundredChaptersDrawNothingEvenOnAWideTrack() {
        // 3000 px / 6 px would take 500 marks; a web novel's 101 episodes still get none.
        assertEquals(0, place(IntArray(101) { (it + 1) * 10 }, max = 2000, width = 3000).size)
        assertEquals(100, place(IntArray(100) { (it + 1) * 10 }, max = 2000, width = 3000).size)
        assertEquals(100, ChapterTicks.MAX_CHAPTERS)
    }

    @Test
    fun aChapterInsideASectionStartsByItsShareOfTheText() {
        // A 40-page section from page 100 with 80 000 chars: an episode at char 20 000 starts on its 11th page.
        assertEquals(110, ChapterTicks.startPage(pagesBefore = 100, pages = 40, offset = 20_000, chars = 80_000))
        // The section's own start, and a one-page or empty section, stay on its first page.
        assertEquals(100, ChapterTicks.startPage(100, 40, 0, 80_000))
        assertEquals(100, ChapterTicks.startPage(100, 1, 500, 800))
        assertEquals(100, ChapterTicks.startPage(100, 40, 500, 0))
        // Never past the section's last page (an offset at or past the end).
        assertEquals(139, ChapterTicks.startPage(100, 40, 80_000, 80_000))
        assertEquals(139, ChapterTicks.startPage(100, 40, 99_999, 80_000))
        // No overflow on a huge section.
        assertEquals(1_999, ChapterTicks.startPage(0, 2_000, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
