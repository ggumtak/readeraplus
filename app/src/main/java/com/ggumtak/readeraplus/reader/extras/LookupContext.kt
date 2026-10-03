package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.data.NotePlace
import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import java.net.URI
import java.net.URLEncoder

/**
 * The sentence a looked-up word sits in, for the 단어장 (NOTES_SPEC §7.1 item 7, hub.md §6.4). Pure, unit-tested.
 */
internal object LookupContext {
    const val MAX = 300
    /** At most this many chars of context on each side of the selection. */
    const val SIDE = 150
    private const val ELLIPSIS = '…'

    /**
     * The sentence around [start, end) of [text]: bounds from [SentenceSplitter]'s terminal / closing rules and '\n',
     * at most [SIDE] chars each way (cut at a space, "…" added), OBJECT_CHAR removed, whitespace collapsed, and at
     * most [max] chars in all.
     */
    fun sentence(text: CharSequence, start: Int, end: Int, max: Int = MAX): String {
        val len = text.length
        if (len == 0) return ""
        val s = minOf(start, end).coerceIn(0, len)
        val e = maxOf(start, end).coerceIn(s, len)

        // Left: the first char after a '\n' or after a sentence end (terminal + closing marks + a space).
        var from = -1
        var k = s - 1
        val leftLimit = (s - SIDE).coerceAtLeast(0)
        while (k >= leftLimit) {
            val c = text[k]
            if (c == '\n') {
                from = k + 1
                break
            }
            if (SentenceSplitter.isSpace(c)) {
                var j = k - 1
                while (j >= 0 && SentenceSplitter.isClosing(text[j])) j--
                if (j >= 0 && SentenceSplitter.isTerminal(text[j])) {
                    from = k + 1
                    break
                }
            }
            k--
        }
        var cutLeft = false
        if (from < 0) {
            if (leftLimit == 0) {
                from = 0
            } else {
                // No boundary within SIDE chars: start after the first space there (a whole word), else at the limit.
                from = leftLimit
                var p = leftLimit
                while (p < s && !SentenceSplitter.isSpace(text[p])) p++
                if (p < s) from = p + 1
                cutLeft = true
            }
        }

        // Right: after a terminal run (+ closing marks) followed by a space or the end, or at a '\n'. A terminal run
        // that ends the selection itself counts (the selection is a whole sentence).
        var k0 = e
        while (k0 > s && (SentenceSplitter.isTerminal(text[k0 - 1]) || SentenceSplitter.isClosing(text[k0 - 1]))) k0--
        var to = -1
        val rightLimit = (e + SIDE).coerceAtMost(len)
        var r = k0
        while (r < rightLimit) {
            val c = text[r]
            if (c == '\n') {
                to = r
                break
            }
            if (SentenceSplitter.isTerminal(c)) {
                var j = r + 1
                while (j < len && (SentenceSplitter.isTerminal(text[j]) || SentenceSplitter.isClosing(text[j]))) j++
                if (j >= len || SentenceSplitter.isSpace(text[j])) {
                    to = j
                    break
                }
                r = j
                continue
            }
            r++
        }
        var cutRight = false
        if (to < 0) {
            if (rightLimit == len) {
                to = len
            } else {
                to = rightLimit
                var p = rightLimit
                while (p > e && !SentenceSplitter.isSpace(text[p - 1])) p--
                if (p > e) to = p - 1
                cutRight = true
            }
        }
        if (to < e) to = e

        var body = clean(text, from, to)
        if (body.isEmpty()) return ""
        if (cutLeft) body = ELLIPSIS + body
        if (cutRight) body += ELLIPSIS
        if (max > 0 && body.length > max) {
            var cut = (max - 1).coerceAtLeast(0)
            var p = cut
            while (p > max / 2 && body[p - 1] != ' ') p--
            if (p > max / 2) cut = p - 1
            if (cut > 0 && Character.isHighSurrogate(body[cut - 1])) cut--
            body = body.substring(0, cut).trimEnd() + ELLIPSIS
        }
        return body
    }

    /** text[from, to) without U+FFFC, whitespace runs collapsed to one space, trimmed. */
    private fun clean(text: CharSequence, from: Int, to: Int): String {
        val sb = StringBuilder(to - from)
        var space = false
        for (i in from until to) {
            val c = text[i]
            if (c == OBJECT_CHAR) continue
            if (SentenceSplitter.isSpace(c)) {
                space = sb.isNotEmpty()
                continue
            }
            if (space) sb.append(' ')
            space = false
            sb.append(c)
        }
        return sb.toString()
    }
}

/**
 * What a lookup records (hub.md §6.4), taken before the selection is cleared: the word (≤ 200 chars), where it is,
 * and its sentence ([LookupContext.sentence]).
 */
internal class LookupSnapshot(
    val bookId: Long,
    val word: String,
    val section: Int,
    val start: Int,
    val end: Int,
    val context: String,
    val place: NotePlace?,
) {
    companion object {
        const val MAX_WORD = 200
    }
}

/** The "웹 검색" URL from the user's template (`AppSettings.webSearchUrl`, "%s" = the query), pure. */
internal object WebSearchTemplate {
    const val DEFAULT = "https://www.google.com/search?q=%s"

    /** [template] when it holds "%s", else [DEFAULT]. */
    fun effective(template: String?): String = template?.takeIf { it.contains("%s") } ?: DEFAULT

    fun url(template: String?, query: String): String =
        effective(template).replace("%s", URLEncoder.encode(query.trim(), "UTF-8"))

    /** The site host recorded with a web lookup ("search.naver.com"); "" when the template has none. */
    fun host(template: String?): String {
        val t = effective(template).replace("%s", "x")
        return runCatching { URI(t).host }.getOrNull().orEmpty()
    }
}
