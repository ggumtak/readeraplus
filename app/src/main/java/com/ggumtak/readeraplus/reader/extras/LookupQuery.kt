package com.ggumtak.readeraplus.reader.extras

import java.net.URLEncoder

/**
 * Pure. What "사전·번역" asks: the selection cleaned up ([word]) and, for most apps, followed by " 뜻" ([query],
 * [appText]), the Naver search address of the lookup window, the apps that keep the plain word (Anki, translators) and
 * the window's height limits.
 */
internal object LookupQuery {
    const val SUFFIX = " 뜻"
    const val MAX = 100
    const val NAVER_BASE = "https://m.search.naver.com/search.naver?query="
    const val NAVER_HOST = "m.search.naver.com"

    /** Window height as a share of the screen: first shown at [START_PCT], dragged between [MIN_PCT] and [MAX_PCT]. */
    const val START_PCT = 60
    const val MIN_PCT = 30
    const val MAX_PCT = 90

    /** Apps that translate: they get the plain word. */
    private val TRANSLATOR_PACKAGES = setOf(
        "com.google.android.apps.translate",
        "com.naver.labs.translator",
        "com.samsung.android.app.interpreter",
        "com.microsoft.translator",
        "com.deepl.mobiletranslator",
    )
    private val TRANSLATOR_LABELS = listOf("번역", "translate", "파파고", "papago", "deepl")

    /**
     * [text] trimmed, every whitespace run (newlines, NBSP, ideographic space) one space and cut to [MAX] chars (never
     * inside a surrogate pair). "" when nothing is left to look up.
     */
    fun word(text: String): String {
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
        return sb.toString().trimEnd()
    }

    /** [word] followed by " 뜻"; "" when nothing is left to look up. */
    fun query(text: String): String {
        val word = word(text)
        return if (word.isEmpty()) "" else word + SUFFIX
    }

    /** The Naver search address for [query] (built by [query]); spaces are %20 as in `WebEngines.build`. */
    fun naverUrl(query: String): String = NAVER_BASE + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    /** AnkiDroid ("Anki에 넣기"): it gets the selection as is, in its own full window. */
    fun isAnki(pkg: String): Boolean = pkg.startsWith("com.ichi2.anki")

    /** A translator app, by package or by its [label] ("번역", "Translate", "파파고", "DeepL"): it gets the plain word. */
    fun isTranslator(pkg: String, label: String): Boolean =
        pkg in TRANSLATOR_PACKAGES || TRANSLATOR_LABELS.any { label.contains(it, ignoreCase = true) }

    /**
     * The text another app's 사전·번역 entry gets: [query] (" 뜻" added), but Anki keeps [text] as is and a translator
     * ([isTranslator]) gets the plain [word].
     */
    fun appText(pkg: String, label: String, text: String): String = when {
        isAnki(pkg) -> text
        isTranslator(pkg, label) -> word(text).ifEmpty { text }
        else -> query(text).ifEmpty { text }
    }

    fun startHeight(screen: Int, pct: Int = START_PCT): Int = screen * pct.coerceIn(MIN_PCT, MAX_PCT) / 100

    /** [height] of [screen] as the percentage kept for the next window (within [MIN_PCT]..[MAX_PCT]). */
    fun heightPct(height: Int, screen: Int): Int =
        if (screen <= 0) START_PCT else (height * 100 / screen).coerceIn(MIN_PCT, MAX_PCT)

    /** [height] within [MIN_PCT]..[MAX_PCT] of [screen]. */
    fun clampHeight(height: Int, screen: Int): Int = height.coerceIn(screen * MIN_PCT / 100, screen * MAX_PCT / 100)
}
