package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.reader.PageProgress
import com.ggumtak.readeraplus.reader.ReaderFormat
import java.net.URLEncoder

/**
 * Pure helpers of the selection's 검색 panel ([WordSearchPanel]): the four tabs, the query cleaned up for the web pages,
 * their addresses, and the texts of the 본문 tab's rows and status. No android.* imports: unit-tested on the JVM.
 */
internal object WordSearchQuery {
    const val TAB_BODY = 0
    const val TAB_KO = 1
    const val TAB_EN = 2
    const val TAB_WIKI = 3
    const val TAB_COUNT = 4

    /** The tab shown when the panel opens: 국어사전. */
    const val DEFAULT_TAB = TAB_KO

    private val TITLES = arrayOf("본문", "국어사전", "영어사전", "백과사전")

    fun title(tab: Int): String = TITLES[tab]

    /** A tab that shows a web page (all but 본문). */
    fun isWeb(tab: Int): Boolean = tab in TAB_KO..TAB_WIKI

    /**
     * What the field starts with: the selection trimmed, every whitespace run one space, cut to [LookupQuery.MAX] chars
     * (the panel is for a word or a short phrase, not a paragraph).
     */
    fun initial(selection: String): String = LookupQuery.word(selection)

    // ------------------------------------------------------------------ the web pages' query

    private const val OPENERS = "([{<（［｛〈《「『【〔"
    private const val CLOSERS = ")]}>）］｝〉》」』】〕"
    private const val QUOTES = "\"'`“”‘’„‟«»‹›＂＇"
    private const val LEADING_PUNCT = "…‥—–~～·・,;:."
    private const val TRAILING_PUNCT = ".,;:!?…‥。、，．！？；：~～·・—–-"

    /** The longest query sent to a web page. */
    const val WEB_MAX = 100

    /**
     * [typed] as the dictionaries and the encyclopedia should get it: whitespace trimmed (runs inside become one
     * space), surrounding quotes and brackets taken off ("「말이다」" → 말이다; a bracket pair inside the text, as in
     * "(주)한국" or "f(x)", stays), and the sentence punctuation around it ("않은가." → 않은가, "“말이다.”" → 말이다). ""
     * when nothing but such marks is left.
     */
    fun webQuery(typed: String): String {
        val s = LookupQuery.word(typed).take(WEB_MAX)
        var start = 0
        var end = s.length
        while (start < end) {
            val first = s[start]
            val last = s[end - 1]
            when {
                last.isWhitespace() -> end--
                first.isWhitespace() -> start++
                wraps(s, start, end) -> {
                    start++
                    end--
                }
                last in TRAILING_PUNCT || last in QUOTES -> end--
                last in CLOSERS && unmatched(s, start, end, last) -> end--
                first in LEADING_PUNCT || first in QUOTES -> start++
                first in OPENERS && unmatched(s, start, end, first) -> start++
                else -> break
            }
        }
        return s.substring(start, end)
    }

    /** s[start, end) opens with a bracket that its last char closes (and not an earlier one: "(주)한국(주)" is no wrap). */
    private fun wraps(s: String, start: Int, end: Int): Boolean {
        if (end - start < 2) return false
        val open = s[start]
        val close = pairOf(open)
        if (close == ' ' || s[end - 1] != close) return false
        var depth = 0
        for (i in start until end) {
            val c = s[i]
            if (c == open) {
                depth++
            } else if (c == close) {
                depth--
                if (depth == 0 && i < end - 1) return false
            }
        }
        return depth == 0
    }

    private fun pairOf(open: Char): Char {
        val i = OPENERS.indexOf(open)
        return if (i < 0) ' ' else CLOSERS[i]
    }

    /** The bracket [c] (an opener or a closer) has no partner of the other kind inside s[start, end). */
    private fun unmatched(s: String, start: Int, end: Int, c: Char): Boolean {
        val oi = OPENERS.indexOf(c)
        val open = if (oi >= 0) c else OPENERS[CLOSERS.indexOf(c)]
        val close = if (oi >= 0) CLOSERS[oi] else c
        var opens = 0
        var closes = 0
        for (i in start until end) {
            if (s[i] == open) opens++ else if (s[i] == close) closes++
        }
        return if (c == open) opens > closes else closes > opens
    }

    private const val KO_DICT = "https://ko.dict.naver.com/#/search?query="
    private const val EN_DICT = "https://en.dict.naver.com/#/search?query="
    private const val WIKI = "https://ko.m.wikipedia.org/w/index.php?search="

    /** The address of web tab [tab] for [webQuery] (spaces as %20); "" for the 본문 tab. */
    fun url(tab: Int, webQuery: String): String {
        val base = when (tab) {
            TAB_KO -> KO_DICT
            TAB_EN -> EN_DICT
            TAB_WIKI -> WIKI
            else -> return ""
        }
        return base + URLEncoder.encode(webQuery, "UTF-8").replace("+", "%20")
    }

    // ------------------------------------------------------------------ the 본문 tab

    /** Chars of context shown before a match and after it: a row has two lines, and the match is at its start. */
    const val SNIPPET_BEFORE = 12
    const val SNIPPET_AFTER = 60

    /**
     * A row's text: the context around text[start, end) with every whitespace run (line breaks, object chars) one space
     * and "…" marking cut ends; [TextSearch.Snippet.hitStart] / [TextSearch.Snippet.hitEnd] delimit the match in it.
     * Never splits a surrogate pair.
     */
    fun snippet(
        text: String,
        start: Int,
        end: Int,
        before: Int = SNIPPET_BEFORE,
        after: Int = SNIPPET_AFTER,
    ): TextSearch.Snippet {
        val n = text.length
        val hs = start.coerceIn(0, n)
        val he = end.coerceIn(hs, n)
        var s = (hs - before).coerceAtLeast(0)
        var e = (he + after).coerceAtMost(n)
        if (s in 1 until hs && Character.isLowSurrogate(text[s])) s++
        if (e in (he + 1) until n && Character.isHighSurrogate(text[e - 1])) e--
        val lead = if (s > 0) 1 else 0
        val sb = StringBuilder(e - s + 2)
        if (lead == 1) sb.append('…')
        var hitStart = -1
        var hitEnd = -1
        var space = false
        for (i in s until e) {
            if (i == hs) hitStart = sb.length
            if (i == he) hitEnd = sb.length
            val c = text[i]
            if (c == OBJECT_CHAR || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (!space && sb.length > lead) sb.append(' ')
                space = true
            } else {
                sb.append(c)
                space = false
            }
        }
        if (hitStart < 0) hitStart = sb.length
        if (hitEnd < 0) hitEnd = sb.length
        if (e < n) sb.append('…')
        return TextSearch.Snippet(sb.toString(), hitStart, hitEnd.coerceAtLeast(hitStart))
    }

    /**
     * The percent through the book a row shows: the footer's (by pages, [PageProgress]) once the pages are counted
     * ([pagesKnown]) and [page] / [total] are real, else the character share [fraction] (0..1).
     */
    fun percent(page: Int, total: Int, pagesKnown: Boolean, fraction: Float): Int =
        if (pagesKnown && page > 0 && total > 0) {
            PageProgress.percentOf(page, total)
        } else {
            ReaderFormat.percent(if (fraction.isNaN()) 0f else fraction)
        }

    /** A row's grey line: "34% | 128 페이지"; just "34%" while there is no page number (pages not counted yet). */
    fun meta(percent: Int, page: String?): String =
        if (PageLabel.hasPage(page)) "$percent% | ${page!!.trim()} 페이지" else "$percent%"

    /**
     * The message over an empty 본문 list, or null while there are rows: nothing typed, the book not open, searching
     * (no row yet) and no match.
     */
    fun bodyMessage(hasQuery: Boolean, docOpen: Boolean, hits: Int, complete: Boolean): String? = when {
        !hasQuery -> "검색어를 입력하세요"
        !docOpen -> "책을 여는 중입니다"
        hits > 0 -> null
        complete -> "검색 결과가 없습니다"
        else -> "검색 중…"
    }

    /** The grey line over the rows: the scan is still running (with matches shown) or stopped at [max] matches; else "". */
    fun bodyNote(hits: Int, complete: Boolean, capped: Boolean, max: Int): String = when {
        !complete && hits > 0 -> "검색 중… · ${hits}개"
        capped -> "${max}개 이상 (앞 ${max}개만 표시)"
        else -> ""
    }

    /** What a web tab shows instead of its page, or null when it shows the page. */
    fun webMessage(webQuery: String, failed: Boolean): String? = when {
        webQuery.isEmpty() -> "검색어가 없습니다"
        failed -> "웹 창을 열 수 없어 브라우저로 열었습니다"
        else -> null
    }
}

/**
 * Which tabs show the current query: a new query ([invalidate]) makes every tab load again, but only when it is next
 * shown ([needsLoad]); the web pages are created and the book searched lazily. Pure; main thread.
 */
internal class WordSearchTabs(private val count: Int) {
    private var generation = 1
    private val loadedFor = IntArray(count)

    /** The query changed (or was searched again): every tab is stale. */
    fun invalidate() {
        generation++
    }

    /** [tab] has not shown the current query yet. */
    fun needsLoad(tab: Int): Boolean = tab in 0 until count && loadedFor[tab] != generation

    /** [tab] shows the current query. */
    fun markLoaded(tab: Int) {
        if (tab in 0 until count) loadedFor[tab] = generation
    }
}
