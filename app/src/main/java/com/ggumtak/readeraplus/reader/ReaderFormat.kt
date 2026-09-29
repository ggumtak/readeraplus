package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.OBJECT_CHAR

/** Pure string formatting for the reader's footer, chrome and chips (unit-tested). */
object ReaderFormat {
    const val SEP = "  ·  "

    /** "12 / 3259"; estimated parts get a "~" prefix ("~12 / ~3260"). */
    fun pageLabel(page: Int, total: Int, pageExact: Boolean, totalExact: Boolean): String {
        val t = total.coerceAtLeast(page)
        val p = if (pageExact) "$page" else "~$page"
        val tt = if (totalExact) "$t" else "~$t"
        return "$p / $tt"
    }

    /** Percent 0..100 (floor; the last page shows 100). */
    fun percent(progress: Float): Int = (progress * 100f + 1e-4f).toInt().coerceIn(0, 100)

    fun clock(hour: Int, minute: Int, is24: Boolean): String {
        val h = if (is24) hour else ((hour + 11) % 12) + 1
        val mm = if (minute < 10) "0$minute" else "$minute"
        return if (is24 && h < 10) "0$h:$mm" else "$h:$mm"
    }

    /** "12 / 3259  ·  챕터 5쪽 남음" (null when there is nothing to show). */
    fun footerLeft(pageLabel: String?, chapterPagesLeft: Int?): String? {
        val parts = ArrayList<String>(2)
        if (pageLabel != null) parts += pageLabel
        if (chapterPagesLeft != null) parts += chapterLeft(chapterPagesLeft)
        return if (parts.isEmpty()) null else parts.joinToString(SEP)
    }

    fun chapterLeft(pages: Int): String = if (pages <= 0) "챕터 마지막 쪽" else "챕터 ${pages}쪽 남음"

    /** "34%  ·  14:05  ·  80%" per enabled item (null when empty). */
    fun footerRight(percent: Int?, clock: String?, battery: Int?): String? {
        val parts = ArrayList<String>(3)
        if (percent != null) parts += "$percent%"
        if (clock != null) parts += clock
        if (battery != null && battery >= 0) parts += "$battery%"
        return if (parts.isEmpty()) null else parts.joinToString(SEP)
    }

    /** Bookmark list snippet: page text with newlines/objects as spaces, whitespace collapsed, ≤ [max] chars. */
    fun snippet(text: String, start: Int, end: Int, max: Int = 80): String {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, minOf(text.length, s + max * 3))
        val sb = StringBuilder(minOf(e - s, max + 1))
        var lastSpace = true
        var i = s
        while (i < e && sb.length < max + 1) {
            val c = text[i]
            val space = c == '\n' || c == OBJECT_CHAR || Character.isWhitespace(c) || c == ' '
            if (space) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(c)
                lastSpace = false
            }
            i++
        }
        var out = sb.toString().trim()
        if (out.length > max) out = out.substring(0, max).trimEnd() + "…"
        return out
    }

    /** Seekbar drag preview: "p. 12 · 3화 제목". */
    fun previewLabel(page: Int, exact: Boolean, chapter: String?): String {
        val p = if (exact) "p. $page" else "p. ~$page"
        return if (chapter.isNullOrBlank()) p else "$p · ${chapter.trim()}"
    }

    /** Return chip text: "← 돌아가기 (p. 12)". */
    fun returnChip(page: Int, exact: Boolean): String = "← 돌아가기 (p. ${if (exact) "" else "~"}$page)"

    fun brightness(value: Float): String =
        if (value < 0f) "밝기 자동" else "밝기 ${Math.round(value.coerceIn(0f, 1f) * 100f)}%"

    fun autoTurnOn(seconds: Int): String = "자동 넘김 켜짐 (${seconds}초)"
}
