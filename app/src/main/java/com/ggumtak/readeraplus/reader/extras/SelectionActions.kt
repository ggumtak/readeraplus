package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.reader.PageGeometry
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind

/**
 * The selection popup's actions (UI_SPEC polish 13, NOTES_SPEC §7.1, PLAN C26), pure and unit-tested: one row of 5
 * cells — 복사 · 인용 · 메모 · 사전·번역 · ⋮ (over an existing quote: 복사 · 메모 · 인용 삭제 · 사전·번역 · ⋮) — and the
 * ⋮ menu in the order 색 골라 인용… · 공유 · 문단 선택 · 책에서 검색 · 웹 검색 · 여기부터 듣기 · 문구 지우기 (TXT only).
 */
internal object SelectionActions {
    enum class Id(val label: String) {
        COPY("복사"),
        QUOTE("인용"),
        NOTE("메모"),
        /** "메모" over an existing quote: edits that quote's memo. */
        EDIT_NOTE("메모"),
        DELETE_QUOTE("인용 삭제"),
        LOOKUP("사전·번역"),
        MORE("더보기"),
        PICK_STYLE("색 골라 인용…"),
        SHARE("공유"),
        PARAGRAPH("문단 선택"),
        SEARCH("책에서 검색"),
        WEB_SEARCH("웹 검색"),
        READ_ALOUD("여기부터 듣기"),
        DELETE_PHRASE("문구 지우기"),
    }

    /** Cells of the row, the ⋮ cell included. */
    const val CELLS = 5

    private val PRIMARY = setOf(Id.COPY, Id.QUOTE, Id.NOTE, Id.EDIT_NOTE, Id.DELETE_QUOTE, Id.LOOKUP)

    /**
     * Every action that applies, row actions first, then the ⋮ menu's in its order (⋮ itself not included).
     * [existingQuote]: the selection is a saved quote (its palette row replaces "색 골라 인용…"); [readAloud]: TTS is
     * available; [txt]: a TXT book whose host can take a per-book replace rule ("문구 지우기").
     */
    fun ids(existingQuote: Boolean, readAloud: Boolean, txt: Boolean): List<Id> {
        val list = ArrayList<Id>(13)
        list += Id.COPY
        if (existingQuote) {
            list += Id.EDIT_NOTE
            list += Id.DELETE_QUOTE
        } else {
            list += Id.QUOTE
            list += Id.NOTE
        }
        list += Id.LOOKUP
        if (!existingQuote) list += Id.PICK_STYLE
        list += Id.SHARE
        list += Id.PARAGRAPH
        list += Id.SEARCH
        list += Id.WEB_SEARCH
        if (readAloud) list += Id.READ_ALOUD
        if (txt) list += Id.DELETE_PHRASE
        return list
    }

    /**
     * Splits [list] into the row (the row actions in their order, at most [CELLS] − 1, then ⋮ when anything is left
     * over) and the ⋮ menu (the rest, in order).
     */
    fun split(list: List<Id>): Pair<List<Id>, List<Id>> {
        val primary = ArrayList<Id>(CELLS)
        val overflow = ArrayList<Id>(list.size)
        for (id in list) {
            if (id == Id.MORE) continue
            if (id in PRIMARY && primary.size < CELLS - 1) primary += id else overflow += id
        }
        if (overflow.isNotEmpty()) primary += Id.MORE
        return primary to overflow
    }
}

/**
 * The "quotes" highlight list of one section, in the order the reader builds it from `Library.quotes` (section,
 * start, end, id), so an optimistic list and the reloaded one compare equal and the reload draws nothing (pure).
 */
internal object QuoteHighlights {
    /**
     * Highlights of [section]'s quotes. With the session's parse signature [sig] (null = unknown), a quote saved
     * under another parse (non-empty `sig` ≠ [sig]) is left out like the reader does (NOTES_SPEC §6.5): its offsets
     * point at other text.
     */
    fun forSection(quotes: List<Quote>, section: Int, sig: String? = null, anchorMatch: ((Quote) -> Boolean?)? = null): List<Highlight> {
        var n = 0
        for (q in quotes) if (drawn(q, section, sig, anchorMatch)) n++
        if (n == 0) return emptyList()
        val out = ArrayList<Highlight>(n)
        for (q in quotes) if (drawn(q, section, sig, anchorMatch)) out += Highlight(q.start, q.end, HighlightKind.QUOTE, q.style)
        return out
    }

    /**
     * Whether [q] is drawn on [section] under [sig]: the one K2 rule for the page and for a long press, which must
     * not snap to a quote whose place changed (not drawn: its offsets point at other text).
     */
    fun drawn(q: Quote, section: Int, sig: String?, anchorMatch: ((Quote) -> Boolean?)?): Boolean =
        q.section == section && !QuoteRows.placeChanged(q.sig, sig, if (sig != null && q.sig != sig) anchorMatch?.invoke(q) else null)

    /** [quotes] (database order) with [added] inserted where the database will list it: after every row ≤ it. */
    fun withAdded(quotes: List<Quote>, added: Quote): List<Quote> {
        var at = quotes.size
        for (i in quotes.indices) {
            val q = quotes[i]
            if (q.section > added.section ||
                (q.section == added.section && (q.start > added.start || (q.start == added.start && q.end > added.end)))
            ) {
                at = i
                break
            }
        }
        val out = ArrayList<Quote>(quotes.size + 1)
        out.addAll(quotes.subList(0, at))
        out += added
        out.addAll(quotes.subList(at, quotes.size))
        return out
    }

    /** [quotes] with quote [id]'s style set to [style]. */
    fun withStyle(quotes: List<Quote>, id: Long, style: Int): List<Quote> =
        quotes.map { if (it.id == id && it.style != style) it.copy(style = style) else it }
}

/**
 * Where the page content box sits in the page view when [OriginCalibrator] cannot tell (A §2.5): the reader's own
 * [PageGeometry], which already holds the header's band (from the settings, since 2026-10-05), so there is no separate
 * header term. The same in paged and scroll mode (the virtual page is re-based to the content box).
 */
internal object SelectionOrigin {
    fun fallbackX(g: PageGeometry, paddingLeft: Int): Float = (paddingLeft + g.contentLeft).toFloat()
    fun fallbackY(g: PageGeometry, paddingTop: Int): Float = (paddingTop + g.contentTop).toFloat()
}
