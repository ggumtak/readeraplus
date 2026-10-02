package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.FakeMeasurer
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SampleText
import com.ggumtak.readeraplus.engine.SectionBuilder
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.Typesetter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AnchorsTest {
    private fun content(t: String) = SectionContent(t, listOf(ParagraphBlock(0, t.length)))

    @Test
    fun snippetTakesVisibleCharsOnly() {
        assertEquals("가나다라마바사아", TextRefind.snippet("  가나 다\n라마바 사아", 0))
        assertNull(TextRefind.snippet("가나다", 0))            // fewer than MIN_NEEDLE
        assertNull(TextRefind.snippet("가나다라마바사", 7))       // at the end
        assertEquals(TextRefind.NEEDLE, TextRefind.snippet("가".repeat(100), 3)!!.length)
    }

    @Test
    fun findIgnoresWhitespaceChangesOfAReparse() {
        val old = "첫 줄입니다.\n\n  그녀는 낡은 우산을 접으며\n말했다. 오늘은 비가 그칠 거야."
        val at = old.indexOf("그녀는")
        val needle = TextRefind.snippet(old, at)!!
        // Blank lines removed, indentation stripped, hard-wrapped lines joined: the same place is found.
        val new = "첫 줄입니다.\n그녀는 낡은 우산을 접으며 말했다. 오늘은 비가 그칠 거야."
        assertEquals(new.indexOf("그녀는"), TextRefind.find(new, at, needle))
        assertEquals(-1, TextRefind.find("전혀 다른 글입니다. 전혀 다른 글입니다.", 5, needle))
    }

    @Test
    fun findPrefersTheOccurrenceNearestTheEstimate() {
        val unit = "반복되는 문장입니다. "
        val t = unit.repeat(50)
        val needle = TextRefind.snippet(t, 0)!!
        val near = unit.length * 30
        assertEquals(near, TextRefind.find(t, near + 3, needle))
        assertEquals(unit.length * 31, TextRefind.find(t, unit.length * 31 - 2, needle))
    }

    @Test
    fun resolveFallsBackToTheClampedEstimate() {
        val c = content("가나다라마바사아자차카타파하".repeat(3))
        assertEquals(5, AnchorSpec(0, 5).resolve(c))
        assertEquals(-1, AnchorSpec(0, 0).resolve(c))
        assertEquals(-1, AnchorSpec(0, c.length).resolve(c))
        assertEquals(-1, AnchorSpec(0, c.length + 50).resolve(c))
        assertEquals(20, AnchorSpec(0, 20, needle = "없는문장입니다없는문장").resolve(c))
        assertEquals(14, AnchorSpec(0, 12, needle = "가나다라마바사아").resolve(c))
        assertEquals(-1, AnchorSpec(0, 3, needle = "가나다라마바사아").resolve(c))   // nearest match is the section start
    }



}
