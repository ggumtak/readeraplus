package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.render.QuoteStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteRowsTest {
    private fun q(id: Long, style: Int, text: String = "문장 $id", note: String = "", sig: String = "") =
        Quote(id, 1L, 0, id.toInt() * 10, id.toInt() * 10 + 5, text, note, createdAt = 0L, style = style, sig = sig)

    @Test
    fun chipsOnlyForTwoOrMoreStyles() {
        val one = listOf(q(1, QuoteStyles.GREEN), q(2, QuoteStyles.GREEN))
        assertFalse(QuoteRows.showChips(QuoteRows.styleCounts(one)))
        assertFalse(QuoteRows.showChips(QuoteRows.styleCounts(emptyList())))
        // CI 83: "[전체 2] [● 1] [● 1]".
        val two = listOf(q(1, QuoteStyles.YELLOW), q(2, QuoteStyles.BLUE))
        val counts = QuoteRows.styleCounts(two)
        assertTrue(QuoteRows.showChips(counts))
        assertEquals("전체 2", QuoteRows.allChip(two.size))
        assertEquals(listOf(QuoteStyles.YELLOW to 1, QuoteStyles.BLUE to 1), QuoteRows.chips(counts))
    }

    @Test
    fun unknownStoredStyleCountsAsYellow() {
        val counts = QuoteRows.styleCounts(listOf(q(1, 9), q(2, QuoteStyles.YELLOW), q(3, QuoteStyles.UNDERLINE)))
        assertEquals(2, counts[QuoteStyles.YELLOW])
        assertEquals(1, counts[QuoteStyles.UNDERLINE])
        assertEquals(listOf(QuoteStyles.YELLOW to 2, QuoteStyles.UNDERLINE to 1), QuoteRows.chips(counts))
        assertEquals(listOf(1L, 2L), QuoteRows.filter(listOf(q(1, 9), q(2, 0), q(3, 5)), QuoteStyles.YELLOW).map { it.id })
    }

    @Test
    fun filterAndLabel() {
        val all = listOf(q(1, 0), q(2, 1), q(3, 1), q(4, 5))
        assertEquals(all, QuoteRows.filter(all, QuoteRows.ALL))
        assertEquals(listOf(2L, 3L), QuoteRows.filter(all, 1).map { it.id })
        assertEquals("인용문", QuoteRows.tabLabel(0, 0, false))
        assertEquals("인용문 12", QuoteRows.tabLabel(12, 12, false))
        assertEquals("인용문 5", QuoteRows.tabLabel(5, 12, true))
    }

    @Test
    fun keepFilter() {
        val counts = QuoteRows.styleCounts(listOf(q(1, 0), q(2, 1)))
        assertEquals(1, QuoteRows.keepFilter(1, counts))
        assertEquals(QuoteRows.ALL, QuoteRows.keepFilter(2, counts))
        assertEquals(QuoteRows.ALL, QuoteRows.keepFilter(QuoteRows.ALL, counts))
        // One style left: no chips, so no filter either.
        assertEquals(QuoteRows.ALL, QuoteRows.keepFilter(1, QuoteRows.styleCounts(listOf(q(1, 1)))))
    }

    @Test
    fun placeChanged() {
        // Sig only (no anchor result): legacy rows and EPUB never move, a different sig does.
        assertFalse(QuoteRows.placeChanged("", "abc:10", null))
        assertFalse(QuoteRows.placeChanged("abc:10", "abc:10", null))
        assertFalse(QuoteRows.placeChanged("abc:10", "", null))
        assertFalse(QuoteRows.placeChanged("abc:10", null, null))
        assertTrue(QuoteRows.placeChanged("abc:10", "abd:10", null))
        // The anchor decides when known (K2): a moved sig whose text is still there is drawn, a legacy one that is
        // not found is not.
        assertFalse(QuoteRows.placeChanged("abc:10", "abd:10", true))
        assertTrue(QuoteRows.placeChanged("", "abd:10", false))
        assertFalse(QuoteRows.placeChanged("abd:10", "abd:10", false))
        assertFalse(QuoteRows.placeChanged("abc:10", null, false))
    }

    @Test
    fun swatchColumn() {
        assertTrue(QuoteRows.inSwatchColumn(660f, 700, 48))
        assertTrue(QuoteRows.inSwatchColumn(652f, 700, 48))
        assertFalse(QuoteRows.inSwatchColumn(651f, 700, 48))
        assertFalse(QuoteRows.inSwatchColumn(-1f, 700, 48))
        assertFalse(QuoteRows.inSwatchColumn(10f, 0, 48))
    }

    @Test
    fun shareAllOneStyleIsUntagged() {
        val text = QuoteRows.shareAll("책", "작가", listOf(q(1, 2, "가나", note = "메"), q(2, 2, " 다라 ")), { "3" }, 50_000)
        assertEquals("《책》 — 작가\n인용문 2개\n\n“가나”\n  (3쪽)\n  메모: 메\n\n“다라”\n  (3쪽)\n", text)
        assertFalse(QuoteRows.tagged(listOf(q(1, 2))))
    }

    @Test
    fun shareAllTwoStylesIsTagged() {
        val text = QuoteRows.shareAll("책", "", listOf(q(1, 1, "가나"), q(2, 0, "다라")), { "7" }, 50_000)
        assertEquals("《책》\n인용문 2개\n\n[초록] “가나”\n  (7쪽)\n\n[노랑] “다라”\n  (7쪽)\n", text)
    }

    @Test
    fun shareAllIsCapped() {
        val many = (1L..200L).map { q(it, 0, "가".repeat(100)) }
        val text = QuoteRows.shareAll("책", "", many, { "1" }, 1_000)
        assertTrue(text.length <= 1_002)
        assertTrue(text.endsWith("  (1쪽)\n\n…"))
        // Whole entries only: 8 fit under 1,000 chars (header 13 + 8 × 111).
        assertEquals(8, Regex("“").findAll(text).count())
        // A first entry longer than the cap is cut inside it, never inside a surrogate pair.
        val one = QuoteRows.shareAll("책", "", listOf(q(1, 0, "😀".repeat(100))), { "1" }, 20)
        assertFalse(Character.isHighSurrogate(one[one.length - 3]))
        assertTrue(one.endsWith("\n…"))
    }
}
