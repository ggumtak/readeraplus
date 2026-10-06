package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.render.QuoteStyles

/**
 * Pure logic of the contents dialog's 인용문 tab (N §7.2): style chips and the in-memory filter, the tab label, the
 * "· 위치 바뀜" rule, the swatch column's tap zone and the tagged share-all text. Unit-tested.
 */
internal object QuoteRows {
    /** No filter: every quote is listed. */
    const val ALL = -1

    /** Width of the swatch column at the row's right edge (its whole height is the touch target). */
    const val SWATCH_COLUMN_DP = 48

    const val STALE_SUFFIX = " · 위치 바뀜"

    /** Quotes per displayed style ([QuoteStyles.of]: unknown stored ids count as yellow, as they are drawn). */
    fun styleCounts(quotes: List<Quote>): IntArray {
        val counts = IntArray(QuoteStyles.COUNT)
        for (q in quotes) counts[QuoteStyles.of(q.style)]++
        return counts
    }

    /** Distinct displayed styles among [quotes]. */
    fun distinctStyles(quotes: List<Quote>): Int = styleCounts(quotes).count { it > 0 }

    /** The chip row shows only when the book's quotes use two or more styles. */
    fun showChips(counts: IntArray): Boolean = counts.count { it > 0 } >= 2

    /** Chips in palette order after [전체 N]: the styles in use, as (style, count). */
    fun chips(counts: IntArray): List<Pair<Int, Int>> =
        counts.indices.filter { counts[it] > 0 }.map { it to counts[it] }

    /** "전체 12". */
    fun allChip(total: Int): String = "전체 $total"

    /** The filter after a reload: one that no longer matches any quote (or has no chips) falls back to [ALL]. */
    fun keepFilter(filter: Int, counts: IntArray): Int =
        if (filter == ALL || !showChips(counts) || counts.getOrElse(filter) { 0 } == 0) ALL else filter

    fun filter(quotes: List<Quote>, style: Int): List<Quote> =
        if (style == ALL) quotes else quotes.filter { QuoteStyles.of(it.style) == style }

    /** "인용문", "인용문 12", or "인용문 5" while a chip filters (the chip row names the style and [전체 12]). */
    fun tabLabel(shown: Int, total: Int, filtered: Boolean): String = when {
        total == 0 -> "인용문"
        filtered -> "인용문 $shown"
        else -> "인용문 $total"
    }

    /**
     * The quote's place no longer matches the open text (PLAN K2, the reader's own rule for drawing it): its stored
     * text signature differs from the session's [sessionSig] (TXT options, encoding or file changed) and, when the
     * quote's section text is at hand, its text is not found at its offset ([anchorMatch], `JumpAnchor.matches`).
     * Without the anchor result the sig decides: legacy rows (`''`) and EPUB (session sig `''`) never move. A host
     * without places ([sessionSig] null) keeps every quote.
     */
    fun placeChanged(quoteSig: String, sessionSig: String?, anchorMatch: Boolean?): Boolean = when {
        sessionSig == null || quoteSig == sessionSig -> false
        anchorMatch != null -> !anchorMatch
        else -> quoteSig.isNotEmpty() && sessionSig.isNotEmpty()
    }

    /** A row tap at [x] (row coordinates) of a row [rowWidth] wide hits the swatch column ([columnPx] wide). */
    fun inSwatchColumn(x: Float, rowWidth: Int, columnPx: Int): Boolean =
        x >= 0f && rowWidth > 0 && x >= rowWidth - columnPx

    /** Two or more distinct styles: share-all entries carry "[초록]" (highlights.md §7). */
    fun tagged(quotes: List<Quote>): Boolean = QuoteExport.tagged(quotes)

    /**
     * Text of 모두 공유 for [quotes] (already filtered), capped at [maxChars]: cut after the last whole entry that fits,
     * then "\n…" (a first entry longer than the cap is cut inside, never between a surrogate pair). One style: exactly
     * the untagged format; two or more: each entry starts with its tag.
     */
    fun shareAll(title: String, author: String, quotes: List<Quote>, pageOf: (Quote) -> String, maxChars: Int): String {
        val tag = tagged(quotes)
        val sb = StringBuilder()
        sb.append("《").append(title).append("》")
        if (author.isNotBlank()) sb.append(" — ").append(author)
        sb.append("\n인용문 ").append(quotes.size).append("개\n")
        val header = sb.length
        var fits = header
        for (q in quotes) {
            sb.append('\n')
            if (tag) sb.append(QuoteExport.prefix(q, true))
            sb.append('“').append(q.text.trim()).append("”\n")
            sb.append(PageLabel.shareLine(pageOf(q)))
            if (q.note.isNotBlank()) sb.append("  메모: ").append(q.note.trim()).append('\n')
            if (sb.length > maxChars) break
            fits = sb.length
        }
        if (sb.length <= maxChars) return sb.toString()
        var cut = if (fits > header) fits else maxChars
        if (cut in 1 until sb.length && Character.isHighSurrogate(sb[cut - 1])) cut--
        return sb.substring(0, cut) + "\n…"
    }

}
