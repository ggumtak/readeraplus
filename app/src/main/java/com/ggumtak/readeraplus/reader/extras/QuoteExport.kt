package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.render.QuoteStyles

/**
 * Plain-text quote sharing with colour markers (highlights.md §7, NOTES_SPEC §7.1 item 8), pure and unit-tested.
 * Entries are tagged "[초록] “…”" only when the quotes shared use two or more styles; with one style the text is
 * byte-for-byte the untagged form. A single quote's share is never tagged.
 */
internal object QuoteExport {
    /** True when [quotes] use at least two distinct styles (unknown stored ids count as 노랑, as they are drawn). */
    fun tagged(quotes: Collection<Quote>): Boolean {
        var first = -1
        for (q in quotes) {
            val s = QuoteStyles.of(q.style)
            if (first < 0) first = s else if (s != first) return true
        }
        return false
    }

    /** "[초록] " when [tagged], else "". */
    fun prefix(q: Quote, tagged: Boolean): String = if (tagged) QuoteStyles.tag(q.style) + " " else ""

    /**
     * "모두 공유" (ContentsDialog.shareAllQuotes): the book header, then per quote `“…”`, `  (12쪽)` (left out without a page) and
     * an optional `  메모: …` line, each entry prefixed by its tag when [tagged] of the set. [pageOf] gives a quote's page label.
     */
    fun shareAll(title: String, author: String, quotes: List<Quote>, pageOf: (Quote) -> String): String {
        val tag = tagged(quotes)
        val sb = StringBuilder()
        sb.append("《").append(title).append("》")
        if (author.isNotBlank()) sb.append(" — ").append(author)
        sb.append("\n인용문 ").append(quotes.size).append("개\n")
        for (q in quotes) {
            sb.append('\n').append(prefix(q, tag)).append('“').append(q.text.trim()).append("”\n")
            sb.append(PageLabel.shareLine(pageOf(q)))
            if (q.note.isNotBlank()) sb.append("  메모: ").append(q.note.trim()).append('\n')
        }
        return sb.toString()
    }

    /** One quote's share text (the 인용문 row's 공유): never tagged. */
    fun single(q: Quote, title: String, author: String): String {
        val sb = StringBuilder()
        sb.append('“').append(q.text.trim()).append('”')
        if (q.note.isNotBlank()) sb.append("\n메모: ").append(q.note.trim())
        sb.append("\n— ").append(title)
        if (author.isNotBlank()) sb.append(", ").append(author)
        return sb.toString()
    }
}
