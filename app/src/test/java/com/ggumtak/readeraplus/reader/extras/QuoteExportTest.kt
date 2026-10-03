package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteExportTest {
    private fun q(text: String, style: Int, note: String = "") =
        Quote(id = 1, bookId = 1, section = 0, start = 0, end = 1, text = text, note = note, createdAt = 0, style = style)

    @Test
    fun tagged_onlyForTwoOrMoreStyles() {
        assertFalse(QuoteExport.tagged(emptyList()))
        assertFalse(QuoteExport.tagged(listOf(q("a", 1), q("b", 1))))
        assertTrue(QuoteExport.tagged(listOf(q("a", 1), q("b", 5))))
        // Unknown stored ids are drawn (and counted) as 노랑.
        assertFalse(QuoteExport.tagged(listOf(q("a", 0), q("b", 9))))
    }

    @Test
    fun shareAll_oneStyle_isTheUntaggedText() {
        val text = QuoteExport.shareAll("책", "작가", listOf(q(" 첫 ", 2, "메모1"), q("둘", 2))) { "12" }
        assertEquals("《책》 — 작가\n인용문 2개\n\n“첫”\n  (12쪽)\n  메모: 메모1\n\n“둘”\n  (12쪽)\n", text)
    }

    @Test
    fun shareAll_twoStyles_tagsEveryEntry() {
        val text = QuoteExport.shareAll("책", "", listOf(q("첫", 1), q("둘", 5))) { "3" }
        assertEquals("《책》\n인용문 2개\n\n[초록] “첫”\n  (3쪽)\n\n[밑줄] “둘”\n  (3쪽)\n", text)
    }

    @Test
    fun singleShare_neverTagged() {
        assertEquals("“글”\n메모: 노트\n— 책, 작가", QuoteExport.single(q("글", 3, "노트"), "책", "작가"))
        assertEquals("", QuoteExport.prefix(q("x", 3), tagged = false))
        assertEquals("[빨강] ", QuoteExport.prefix(q("x", 3), tagged = true))
    }
}
