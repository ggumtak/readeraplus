package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.reader.LayoutKeys
import com.ggumtak.readeraplus.reader.extras.SelectionActions.Id
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionActionsTest {

    @Test
    fun newSelection_rowOfFive_thenOverflowInOrder() {
        val (row, more) = SelectionActions.split(SelectionActions.ids(existingQuote = false, readAloud = true, txt = true))
        assertEquals(listOf(Id.COPY, Id.QUOTE, Id.NOTE, Id.LOOKUP, Id.MORE), row)
        assertEquals(
            listOf(Id.PICK_STYLE, Id.SHARE, Id.PARAGRAPH, Id.SEARCH, Id.WEB_SEARCH, Id.READ_ALOUD, Id.DELETE_PHRASE),
            more,
        )
        assertEquals(SelectionActions.CELLS, row.size)
    }

    @Test
    fun existingQuote_rowSwapsQuoteAndNote_noColourPickInMenu() {
        val (row, more) = SelectionActions.split(SelectionActions.ids(existingQuote = true, readAloud = true, txt = false))
        assertEquals(listOf(Id.COPY, Id.EDIT_NOTE, Id.DELETE_QUOTE, Id.LOOKUP, Id.MORE), row)
        assertEquals(listOf(Id.SHARE, Id.PARAGRAPH, Id.SEARCH, Id.WEB_SEARCH, Id.READ_ALOUD), more)
        assertEquals("메모", Id.EDIT_NOTE.label)
        assertEquals("인용 삭제", Id.DELETE_QUOTE.label)
    }

    @Test
    fun deletePhrase_onlyForTxt_readAloudOnlyWithTts() {
        val epub = SelectionActions.ids(existingQuote = false, readAloud = false, txt = false)
        assertFalse(Id.DELETE_PHRASE in epub)
        assertFalse(Id.READ_ALOUD in epub)
        assertTrue(Id.DELETE_PHRASE in SelectionActions.ids(existingQuote = false, readAloud = false, txt = true))
        val (row, more) = SelectionActions.split(epub)
        assertEquals(5, row.size)
        assertEquals(listOf(Id.PICK_STYLE, Id.SHARE, Id.PARAGRAPH, Id.SEARCH, Id.WEB_SEARCH), more)
    }

    @Test
    fun split_noOverflow_noMoreCell_andIgnoresStrayMore() {
        val (row, more) = SelectionActions.split(listOf(Id.COPY, Id.MORE, Id.QUOTE))
        assertEquals(listOf(Id.COPY, Id.QUOTE), row)
        assertTrue(more.isEmpty())
    }

    @Test
    fun labels_areTheSpecStrings() {
        assertEquals(
            listOf("복사", "인용", "메모", "사전·번역", "더보기", "색 골라 인용…", "공유", "문단", "검색", "웹 검색", "여기서 읽기", "문구 지우기"),
            listOf(Id.COPY, Id.QUOTE, Id.NOTE, Id.LOOKUP, Id.MORE, Id.PICK_STYLE, Id.SHARE, Id.PARAGRAPH, Id.SEARCH,
                Id.WEB_SEARCH, Id.READ_ALOUD, Id.DELETE_PHRASE).map { it.label },
        )
    }

    private fun q(id: Long, sec: Int, s: Int, e: Int, style: Int = 0) =
        Quote(id = id, bookId = 1, section = sec, start = s, end = e, text = "t", createdAt = 0, style = style)

    @Test
    fun optimisticQuote_matchesDatabaseOrder() {
        val db = listOf(q(1, 0, 5, 9), q(2, 1, 10, 20), q(3, 1, 10, 30), q(4, 1, 40, 50), q(5, 2, 0, 3))
        val added = q(Long.MAX_VALUE, 1, 10, 20, style = 2)
        val list = QuoteHighlights.withAdded(db, added)
        assertEquals(listOf(1L, 2L, Long.MAX_VALUE, 3L, 4L, 5L), list.map { it.id })
        val hl = QuoteHighlights.forSection(list, 1)
        assertEquals(listOf(10, 10, 10, 40), hl.map { it.start })
        assertEquals(listOf(20, 20, 30, 50), hl.map { it.end })
        assertEquals(2, hl[1].style)
        assertTrue(hl.all { it.kind == HighlightKind.QUOTE })
        assertEquals(listOf(Long.MAX_VALUE), QuoteHighlights.withAdded(emptyList(), added).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 9L), QuoteHighlights.withAdded(db, q(9, 3, 0, 1)).map { it.id })
    }

    @Test
    fun recolour_andEmptySection() {
        val db = listOf(q(1, 0, 5, 9), q(2, 0, 10, 20, style = 1))
        val next = QuoteHighlights.withStyle(db, 2, 4)
        assertEquals(listOf(0, 4), QuoteHighlights.forSection(next, 0).map { it.style })
        assertTrue(QuoteHighlights.forSection(db, 7).isEmpty())
        assertTrue(db[0] === next[0])
    }

    @Test
    fun staleSignature_notDrawn() {
        val db = listOf(q(1, 0, 0, 5).copy(sig = "old"), q(2, 0, 6, 9).copy(sig = "now"), q(3, 0, 10, 12))
        assertEquals(listOf(6, 10), QuoteHighlights.forSection(db, 0, "now").map { it.start })
        assertEquals(listOf(0, 6, 10), QuoteHighlights.forSection(db, 0, null).map { it.start })
    }

    @Test
    fun changedSignature_keepsOnlyMatchingAnchorsAndTheirStyles() {
        val rows = listOf(q(1, 0, 0, 5, 4).copy(sig = "old"), q(2, 0, 6, 9, 2).copy(sig = "old"))
        val found = QuoteHighlights.forSection(rows, 0, "now") { it.id == 1L }
        assertEquals(listOf(0), found.map { it.start })
        assertEquals(listOf(4), found.map { it.style })
        assertTrue(QuoteHighlights.forSection(rows, 0, "now") { null }.isEmpty())
    }

    @Test
    fun epubQuotesRemainVisibleWithoutParseSignatures() {
        val rows = listOf(q(1, 0, 0, 5, 3).copy(sig = "previous"))
        assertEquals(3, QuoteHighlights.forSection(rows, 0, "").single().style)
    }

    @Test
    fun originFallback_isTheContentBox_withoutAnyHeaderTerm() {
        // Comet: 720 × 1440 px, density 2. The status bands sit inside the margins: the header switch changes nothing.
        val d = 2f
        val withHeader = ReaderSettings(pageMargins = true, marginLeftDp = 24, marginTopDp = 30)
        val g = LayoutKeys.geometry(withHeader, 720, 1440, d)
        assertEquals(48f, SelectionOrigin.fallbackX(g, 0), 0f)
        assertEquals(60f, SelectionOrigin.fallbackY(g, 0), 0f)
        assertEquals(63f, SelectionOrigin.fallbackY(g, 3), 0f)
        // No header slot, or a much larger status font: the same box.
        val noHeader = withHeader.copy(headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE)
        assertFalse(noHeader.hasHeader)
        assertEquals(60f, SelectionOrigin.fallbackY(LayoutKeys.geometry(noHeader, 720, 1440, d), 0), 0f)
        val bigStatus = withHeader.copy(headerLeft = StatusItem.CHAPTER, statusFontSizeSp = 20f)
        assertTrue(bigStatus.hasHeader)
        assertEquals(60f, SelectionOrigin.fallbackY(LayoutKeys.geometry(bigStatus, 720, 1440, d), 0), 0f)
        // "페이지 여백" off: the tiny margin.
        val tiny = LayoutKeys.geometry(withHeader.copy(pageMargins = false), 720, 1440, d)
        assertEquals(LayoutKeys.TINY_MARGIN_DP * d, SelectionOrigin.fallbackX(tiny, 0), 0f)
        assertEquals(LayoutKeys.TINY_MARGIN_DP * d, SelectionOrigin.fallbackY(tiny, 0), 0f)
    }
}
