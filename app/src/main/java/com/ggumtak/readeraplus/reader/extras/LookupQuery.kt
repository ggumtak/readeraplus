package com.ggumtak.readeraplus.reader.extras

import java.net.URLEncoder

/**
 * Pure. What "사전·번역" asks the Naver dictionary window: the selection cleaned up and followed by " 뜻", its search
 * address, the order of the chooser's entries and the window's height limits.
 */
internal object LookupQuery {
    const val SUFFIX = " 뜻"
    const val MAX = 100
    const val NAVER_BASE = "https://m.search.naver.com/search.naver?query="
    const val NAVER_HOST = "m.search.naver.com"
    /** The chooser key (and `extras.lastDictApp` value) of the "네이버 사전" entry; app keys are "package/Activity". */
    const val NAVER_KEY = "naver"

    /** Window height as a share of the screen: first shown at [START_PCT], dragged between [MIN_PCT] and [MAX_PCT]. */
    const val START_PCT = 60
    const val MIN_PCT = 30
    const val MAX_PCT = 90

    /**
     * [text] trimmed, every whitespace run (newlines, NBSP, ideographic space) one space, cut to [MAX] chars (never
     * inside a surrogate pair), then " 뜻". "" when nothing is left to look up.
     */
    fun query(text: String): String {
        val sb = StringBuilder(minOf(text.length, MAX + 2))
        var space = false
        for (ch in text) {
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                space = true
            } else {
                if (space && sb.isNotEmpty()) sb.append(' ')
                space = false
                sb.append(ch)
            }
        }
        if (sb.length > MAX) {
            var end = MAX
            if (Character.isHighSurrogate(sb[end - 1])) end--
            sb.setLength(end)
        }
        val word = sb.toString().trimEnd()
        return if (word.isEmpty()) "" else word + SUFFIX
    }

    /** The Naver search address for [query] (built by [query]); spaces are %20 as in `WebEngines.build`. */
    fun naverUrl(query: String): String = NAVER_BASE + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    /**
     * The keys of the chooser entries before "웹 검색", in order: [NAVER_KEY] first unless [last] (the last used key)
     * is one of [appKeys], which then goes first; the others keep their order.
     */
    fun order(appKeys: List<String>, last: String?): List<String> {
        if (last != null && last != NAVER_KEY && last in appKeys) {
            return listOf(last, NAVER_KEY) + appKeys.filter { it != last }
        }
        return listOf(NAVER_KEY) + appKeys
    }

    /** AnkiDroid ("Anki에 넣기"): it gets the selection as is, in its own full window. */
    fun isAnki(pkg: String): Boolean = pkg.startsWith("com.ichi2.anki")

    /** The text another app's 사전·번역 entry gets: [query] (" 뜻" added) except for Anki, which keeps [text]. */
    fun appText(pkg: String, text: String): String = if (isAnki(pkg)) text else query(text).ifEmpty { text }

    fun startHeight(screen: Int): Int = screen * START_PCT / 100

    /** [height] within [MIN_PCT]..[MAX_PCT] of [screen]. */
    fun clampHeight(height: Int, screen: Int): Int = height.coerceIn(screen * MIN_PCT / 100, screen * MAX_PCT / 100)
}
