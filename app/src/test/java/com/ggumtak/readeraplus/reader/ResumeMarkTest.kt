package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeMarkTest {
    private val hour = 60 * 60 * 1000L

    @Test
    fun onlyAfterABreakAndNotAtTheBookStart() {
        assertTrue(ResumeMark.wanted(lastReadAt = 10 * hour, now = 11 * hour, section = 3, offset = 0))
        assertFalse(ResumeMark.wanted(lastReadAt = 10 * hour, now = 10 * hour + 60_000, section = 3, offset = 10))
        assertFalse(ResumeMark.wanted(lastReadAt = 0, now = 11 * hour, section = 3, offset = 10))
        assertFalse(ResumeMark.wanted(lastReadAt = 10 * hour, now = 11 * hour, section = 0, offset = 0))
        assertTrue(ResumeMark.wanted(lastReadAt = 10 * hour, now = 11 * hour, section = 0, offset = 5))
    }

    @Test
    fun marksTheLineReadingGoesOnFrom() {
        val text = "앞 문장.\n\n  그는 고개를 들었다.\n다음 줄"
        val r = ResumeMark.range(text, 5)
        assertEquals("그는 고개를 들었다.", text.substring(ResumeMark.start(r), ResumeMark.end(r)))
    }

    @Test
    fun aLongLineIsCutAndAnEmptyTailMarksNothing() {
        val long = "가".repeat(100)
        val r = ResumeMark.range(long, 0)
        assertEquals(0, ResumeMark.start(r))
        assertEquals(ResumeMark.MAX_CHARS, ResumeMark.end(r))
        assertEquals(0L, ResumeMark.range("본문\n   \n", 2))
        assertEquals(0L, ResumeMark.range("", 0))
    }
}
