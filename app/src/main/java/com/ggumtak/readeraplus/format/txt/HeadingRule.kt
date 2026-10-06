package com.ggumtak.readeraplus.format.txt

import java.util.regex.Pattern

/*
 * The user's chapter-heading rule (`ReaderSettings.txtChapterRegex`, one stored String). A rule starting with
 * [SIMPLE_PREFIX] is an easy pattern, anything else is a regex (values stored before the easy syntax keep working).
 *
 * Easy pattern: alternatives separated by `|` (each trimmed, blanks dropped). Inside one: `N` = a number, `*` = any
 * text, a run of whitespace = optional spaces (so `< N >` also matches "<77>"), every other character is literal.
 * An alternative must match the whole (trimmed) heading line: `제N장*` matches "제3장 귀환".
 */
object HeadingRule {
    const val SIMPLE_PREFIX = "simple:"

    fun isSimple(stored: String): Boolean = stored.startsWith(SIMPLE_PREFIX)

    /** The pattern text of a simple rule (after the prefix); a regex is returned as it is. */
    fun simpleText(stored: String): String = if (isSimple(stored)) stored.substring(SIMPLE_PREFIX.length) else stored

    /** The stored form of the easy pattern [text]; "" (no rule) for blank text. */
    fun simple(text: String): String = text.trim().let { if (simpleToRegex(it).isEmpty()) "" else SIMPLE_PREFIX + it }

    /** The regex of the easy pattern [text] (find semantics, anchored on the whole line); "" without any alternative. */
    fun simpleToRegex(text: String): String {
        val alts = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (alts.isEmpty()) return ""
        val sb = StringBuilder("^\\s*(?:")
        for ((i, alt) in alts.withIndex()) {
            if (i > 0) sb.append('|')
            appendAlternative(sb, alt)
        }
        return sb.append(")\\s*$").toString()
    }

    private fun appendAlternative(sb: StringBuilder, alt: String) {
        val lit = StringBuilder()
        fun flush() {
            if (lit.isEmpty()) return
            sb.append(Pattern.quote(lit.toString()))
            lit.setLength(0)
        }
        var k = 0
        while (k < alt.length) {
            val c = alt[k]
            when {
                c == 'N' -> { flush(); sb.append("\\d{1,5}"); k++ }
                c == '*' || isSpace(c) -> {
                    // A run of '*' and spaces is one token: "* * *" stays a single "anything", with no backtracking blow-up.
                    flush()
                    var star = false
                    while (k < alt.length && (alt[k] == '*' || isSpace(alt[k]))) {
                        if (alt[k] == '*') star = true
                        k++
                    }
                    sb.append(if (star) ".*" else "\\s*")
                }
                else -> { lit.append(c); k++ }
            }
        }
        flush()
    }

    private fun isSpace(c: Char): Boolean = c.isWhitespace() || c == '　' || c == ' '

    /** True when easy-pattern [text] looks like a regex (one of ^ $ \ [ ] { } + ?), which it would match only literally. */
    fun looksLikeRegex(text: String): Boolean = text.any { it in "^$\\[]{}+?" }

    /** The pattern [stored] stands for, or null for no rule (blank) and for a regex that does not compile. */
    fun compile(stored: String): Pattern? {
        if (stored.isBlank()) return null
        val regex = if (isSimple(stored)) simpleToRegex(simpleText(stored)) else stored
        if (regex.isEmpty()) return null
        return try {
            Pattern.compile(regex)
        } catch (_: Exception) {
            null
        }
    }

    /** The settings row's value: what applies without a rule, the easy pattern's text, or the regex itself. */
    fun label(stored: String): String = when {
        stored.isBlank() -> "기본 규칙만"
        isSimple(stored) -> simpleText(stored).trim().ifEmpty { "기본 규칙만" }
        else -> stored
    }
}
