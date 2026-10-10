package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.reader.LayoutKeys
import com.ggumtak.readeraplus.reader.extras.SelectionActions.Id
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.render.PagePalette
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionActionsTest {

    @Test
    fun newSelection_ridiRow_thenOverflowInOrder() {
        val (row, more) = SelectionActions.split(SelectionActions.ids(existingQuote = false, readAloud = true, txt = true))
        // 복사 · 형광펜 · (colour dot) · 메모 · 검색 · ⋮, and 공유 heading the ⋮ menu (user, 2026-10-10: "거기에 공유도")
        assertEquals(listOf(Id.COPY, Id.QUOTE, Id.PICK_STYLE, Id.NOTE, Id.WORD_SEARCH, Id.MORE), row)
        assertEquals(
            listOf(Id.SHARE, Id.LOOKUP, Id.PARAGRAPH, Id.SEARCH, Id.WEB_SEARCH, Id.READ_ALOUD, Id.DELETE_PHRASE),
            more,
        )
        assertEquals(SelectionActions.CELLS, row.size)
        assertEquals(6, SelectionActions.CELLS)
    }

    @Test
    fun existingQuote_rowSwapsQuoteAndNote_noColourDot() {
        val (row, more) = SelectionActions.split(SelectionActions.ids(existingQuote = true, readAloud = true, txt = false))
        // The colour row sits above it; the row is 복사 · 메모 · 삭제 · 검색 · ⋮.
        assertEquals(listOf(Id.COPY, Id.EDIT_NOTE, Id.DELETE_QUOTE, Id.WORD_SEARCH, Id.MORE), row)
        assertEquals(listOf(Id.SHARE, Id.LOOKUP, Id.PARAGRAPH, Id.SEARCH, Id.WEB_SEARCH, Id.READ_ALOUD), more)
        assertFalse(Id.PICK_STYLE in row + more)
        assertEquals("메모", Id.EDIT_NOTE.label)
        // The cell says 삭제; the action (and its description) stays 형광펜 삭제.
        assertEquals("삭제", Id.DELETE_QUOTE.short)
        assertEquals("형광펜 삭제", Id.DELETE_QUOTE.label)
    }

    @Test
    fun deletePhrase_onlyForTxt_readAloudOnlyWithTts() {
        val epub = SelectionActions.ids(existingQuote = false, readAloud = false, txt = false)
        assertFalse(Id.DELETE_PHRASE in epub)
        assertFalse(Id.READ_ALOUD in epub)
        assertTrue(Id.DELETE_PHRASE in SelectionActions.ids(existingQuote = false, readAloud = false, txt = true))
        val (row, more) = SelectionActions.split(epub)
        assertEquals(6, row.size)
        assertEquals(listOf(Id.SHARE, Id.LOOKUP, Id.PARAGRAPH, Id.SEARCH, Id.WEB_SEARCH), more)
    }

    @Test
    fun lookupMovesToTheMenu_andBookSearchStaysThereToo() {
        for (existing in listOf(false, true)) {
            val (row, more) = SelectionActions.split(SelectionActions.ids(existing, readAloud = true, txt = true))
            assertFalse(Id.LOOKUP in row)
            assertEquals(Id.LOOKUP, more[1])
            assertTrue(Id.SEARCH in more)
            assertTrue(Id.WEB_SEARCH in more)
            assertTrue(Id.WORD_SEARCH in row)
            assertFalse(Id.SEARCH in row)
            // ⋮ at the far right, 공유 first in its menu.
            assertEquals(Id.MORE, row.last())
            assertEquals(Id.SHARE, more.first())
            assertFalse(Id.SHARE in row)
        }
    }

    @Test
    fun split_noOverflow_noMoreCell_andIgnoresStrayMore() {
        val (row, more) = SelectionActions.split(listOf(Id.COPY, Id.MORE, Id.QUOTE))
        assertEquals(listOf(Id.COPY, Id.QUOTE), row)
        assertTrue(more.isEmpty())
        // 공유 is a menu action: on its own it makes the ⋮ cell.
        assertEquals(listOf(Id.COPY, Id.QUOTE, Id.MORE) to listOf(Id.SHARE), SelectionActions.split(listOf(Id.COPY, Id.SHARE, Id.QUOTE)))
    }

    @Test
    fun split_rowHoldsAtMostFivePrimaryCells_extrasGoToTheMenu() {
        val (row, more) = SelectionActions.split(
            listOf(Id.COPY, Id.QUOTE, Id.PICK_STYLE, Id.NOTE, Id.WORD_SEARCH, Id.DELETE_QUOTE, Id.SHARE),
        )
        assertEquals(listOf(Id.COPY, Id.QUOTE, Id.PICK_STYLE, Id.NOTE, Id.WORD_SEARCH, Id.MORE), row)
        assertEquals(listOf(Id.DELETE_QUOTE, Id.SHARE), more)
    }

    @Test
    fun labels_areTheSpecStrings() {
        assertEquals(
            listOf("복사", "형광펜", "형광펜 색 고르기", "메모", "검색", "더보기", "사전·번역", "공유", "문단 선택", "책에서 검색", "웹 검색",
                "여기부터 듣기", "문구 지우기"),
            listOf(Id.COPY, Id.QUOTE, Id.PICK_STYLE, Id.NOTE, Id.WORD_SEARCH, Id.MORE, Id.LOOKUP, Id.SHARE, Id.PARAGRAPH, Id.SEARCH,
                Id.WEB_SEARCH, Id.READ_ALOUD, Id.DELETE_PHRASE).map { it.label },
        )
        // What the row's text cells show: the label, but 삭제 for 형광펜 삭제.
        assertEquals(listOf("복사", "형광펜", "메모", "검색"), listOf(Id.COPY, Id.QUOTE, Id.NOTE, Id.WORD_SEARCH).map { it.short })
    }

    @Test
    fun handles_areBlueOnAPhone_andPageColouredOnEink() {
        // E-ink (and the one-argument form old callers use): the page's text colour.
        assertEquals(PagePalette.PAPER.text, HandleColors.fill(PagePalette.PAPER, eink = true))
        assertEquals(PagePalette.NIGHT.text, HandleColors.fill(PagePalette.NIGHT, eink = true))
        assertEquals(HandleColors.fill(PagePalette.MARU), HandleColors.fill(PagePalette.MARU, eink = true))
        // A phone: RIDI's #1F8CE6, a lighter blue on a dark page.
        assertEquals(0xFF1F8CE6.toInt(), HandleColors.fill(PagePalette.PAPER, eink = false))
        assertEquals(0xFF4DA3EE.toInt(), HandleColors.fill(PagePalette.NIGHT, eink = false))
        assertEquals(0xFF4DA3EE.toInt(), HandleColors.fill(PagePalette.MARU, eink = false))
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
    fun aLongPressSnapsOnlyToADrawnQuote() {
        // The long press uses the page's rule: a moved quote (other sig, anchor gone) is not drawn and not snapped to.
        val moved = q(1, 0, 0, 5).copy(sig = "old")
        assertFalse(QuoteHighlights.drawn(moved, 0, "now") { false })
        assertFalse(QuoteHighlights.drawn(moved, 0, "now", null))
        assertTrue(QuoteHighlights.drawn(moved, 0, "now") { true })
        // Same sig, a legacy row, or a host without places: drawn; another section never.
        assertTrue(QuoteHighlights.drawn(q(2, 0, 0, 5).copy(sig = "now"), 0, "now") { false })
        assertTrue(QuoteHighlights.drawn(q(3, 0, 0, 5), 0, "now") { null })
        assertTrue(QuoteHighlights.drawn(moved, 0, null, null))
        assertFalse(QuoteHighlights.drawn(q(4, 1, 0, 5), 0, null, null))
    }

    @Test
    fun epubQuotesRemainVisibleWithoutParseSignatures() {
        val rows = listOf(q(1, 0, 0, 5, 3).copy(sig = "previous"))
        assertEquals(3, QuoteHighlights.forSection(rows, 0, "").single().style)
    }

    @Test
    fun originFallback_isTheContentBox_whichHoldsTheHeadersBand() {
        // Comet: 720 × 1440 px, density 2. The margins count from the header's band (2026-10-05), so the geometry's box
        // already has the header in it: no separate header term. Above the text: half of the two margins (30 + 10 dp).
        val d = 2f
        val withHeader = ReaderSettings(pageMargins = true, marginLeftDp = 24, marginTopDp = 30)
        val g = LayoutKeys.geometry(withHeader, 720, 1440, d)
        assertEquals(48f, SelectionOrigin.fallbackX(g, 0), 0f)
        assertEquals((25f + 20f) * d, SelectionOrigin.fallbackY(g, 0), 0f)
        assertEquals((25f + 20f) * d + 3f, SelectionOrigin.fallbackY(g, 3), 0f)
        // No header slot: the margin alone; a larger status font: a taller band (20 sp: 4 + 29 + 2 dp).
        val noHeader = withHeader.copy(headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE)
        assertFalse(noHeader.hasHeader)
        assertEquals(40f, SelectionOrigin.fallbackY(LayoutKeys.geometry(noHeader, 720, 1440, d), 0), 0f)
        val bigStatus = withHeader.copy(headerLeft = StatusItem.CHAPTER, statusFontSizeSp = 20f)
        assertTrue(bigStatus.hasHeader)
        assertEquals((35f + 20f) * d, SelectionOrigin.fallbackY(LayoutKeys.geometry(bigStatus, 720, 1440, d), 0), 0f)
        // "페이지 여백" off: the tiny margin, below the header's band still.
        val tiny = LayoutKeys.geometry(withHeader.copy(pageMargins = false), 720, 1440, d)
        assertEquals(LayoutKeys.TINY_MARGIN_DP * d, SelectionOrigin.fallbackX(tiny, 0), 0f)
        assertEquals((25f + LayoutKeys.TINY_MARGIN_DP) * d, SelectionOrigin.fallbackY(tiny, 0), 0f)
    }
}
