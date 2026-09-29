package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTextTest {

    private fun assertInvariants(c: SectionContent) {
        var expect = 0
        for (b in c.blocks) {
            assertTrue(b is ParagraphBlock)
            assertEquals(expect, b.start)
            assertTrue(b.end >= b.start)
            for (i in b.start until b.end) assertTrue(c.text[i] != '\n')
            expect = b.end + 1
        }
        assertEquals(c.text.length + 1, expect)
    }

    @Test
    fun oneParagraphPerLine() {
        val c = CoverText.content("첫 줄입니다.\n\n둘째 줄\n셋째", 1000)
        assertEquals(4, c.blocks.size)
        assertEquals(0, c.blocks[1].end - c.blocks[1].start)
        assertInvariants(c)
    }

    @Test
    fun trailingNewlinesDropped() {
        val c = CoverText.content("가나다\n\n\n", 1000)
        assertEquals("가나다", c.text)
        assertEquals(1, c.blocks.size)
        assertInvariants(c)
    }

    @Test
    fun emptyTextGivesOneEmptyParagraph() {
        val c = CoverText.content("", 1000)
        assertEquals(1, c.blocks.size)
        assertInvariants(c)
    }

    @Test
    fun cutsAtLimitWithoutSplittingSurrogates() {
        val c = CoverText.content("ab😀cd", 3)
        assertEquals("ab", c.text)
        assertInvariants(c)
        val long = CoverText.content("가".repeat(5000), 1200)
        assertEquals(1200, long.text.length)
        assertInvariants(long)
    }
}
