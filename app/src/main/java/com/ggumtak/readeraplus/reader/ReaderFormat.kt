package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.ui.kit.errorDetail
import com.ggumtak.readeraplus.ui.kit.isNoSpace
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipException

/**
 * Pure string formatting for the reader's footer, chrome and chips (unit-tested). Page numbers are shown as plain
 * numbers even while the counts are still estimates (no "~"): the estimate only settles into the exact number.
 */
object ReaderFormat {
    const val SEP = "  ·  "

    /** The error panel's message when nothing in [openError]'s list matches. */
    const val OPEN_FAILED = "책을 열지 못했습니다"

    /** "12 / 3259" (a total below the page, possible while estimating, shows the page as the total). */
    fun pageLabel(page: Int, total: Int): String = "$page / ${total.coerceAtLeast(page)}"

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

    /**
     * "34%  ·  14:05" per enabled item (null when empty). The battery is not text: the renderer draws it after this
     * as a small battery icon with its digits ([com.ggumtak.readeraplus.render.PageDecor.battery]).
     */
    fun footerRight(percent: Int?, clock: String?): String? {
        val parts = ArrayList<String>(2)
        if (percent != null) parts += "$percent%"
        if (clock != null) parts += clock
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
            val space = c == '\n' || c == OBJECT_CHAR || Character.isWhitespace(c) || c == ' '
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
    fun previewLabel(page: Int, chapter: String?): String {
        val p = "p. $page"
        return if (chapter.isNullOrBlank()) p else "$p · ${chapter.trim()}"
    }

    /** Return chip text: "← 돌아가기 (p. 12)". */
    fun returnChip(page: Int): String = "← 돌아가기 (p. $page)"

    fun brightness(value: Float): String =
        if (value < 0f) "밝기 자동" else "밝기 ${Math.round(value.coerceIn(0f, 1f) * 100f)}%"

    fun autoTurnOn(seconds: Int): String = "자동 넘김 켜짐 (${seconds}초)"

    /**
     * Why a book could not be opened or shown, for the error panel and toasts: never an exception message or class
     * name, except [DocumentException]s, whose messages are our own Korean sentences (only their first line: a
     * second one holds a path or URI, see [openErrorDetail]).
     */
    fun openError(t: Throwable): String = when {
        t is DocumentException -> t.message?.lineSequence()?.first()?.trim()?.takeIf { it.isNotEmpty() } ?: OPEN_FAILED
        t is OutOfMemoryError -> "메모리가 부족합니다"
        isNoSpace(t) -> "저장 공간이 부족합니다"
        t is SecurityException -> "파일 접근 권한이 없습니다"
        t is FileNotFoundException -> "파일을 찾을 수 없습니다"
        t is ZipException -> "EPUB 파일이 손상되었습니다"
        t is IOException -> "파일을 읽지 못했습니다"
        else -> OPEN_FAILED
    }

    /**
     * The small grey line under [openError] ("자세히: ZipException"), or null. A [DocumentException] shows the file
     * path its message carries on a second line ("파일을 찾을 수 없습니다.\n/storage/…": which file is missing), else
     * names its cause; a URI there (percent-encoded, unreadable) is left out.
     */
    fun openErrorDetail(t: Throwable): String? {
        if (t !is DocumentException) return errorDetail(t)
        val path = t.message?.lineSequence()?.drop(1)?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        if (path != null && path.startsWith("/")) return path
        return t.cause?.let { errorDetail(it) }
    }

    /**
     * The paragraph shown in place of a section that could not be loaded: "이 부분을 불러오지 못했습니다 (EPUB 파일이
     * 손상되었습니다)", never an exception message (only the class, for a bug report, when nothing better is known).
     */
    fun sectionError(t: Throwable): String {
        if (t is OutOfMemoryError) return "메모리가 부족해 이 부분을 표시하지 못했습니다."
        val why = openError(t)
        return "이 부분을 불러오지 못했습니다 (" + (if (why == OPEN_FAILED) errorDetail(t) else why) + ")"
    }

    /** Label of a TXT encoding ("" = automatic detection): the one wording of the reader and the library. */
    fun encodingLabel(charset: String): String = when (charset.uppercase(Locale.ROOT)) {
        "" -> "자동 감지"
        "UTF-8" -> "UTF-8 (유니코드)"
        "MS949", "CP949", "X-WINDOWS-949", "WINDOWS-949" -> "CP949 (한국어 확장 완성형)"
        "EUC-KR" -> "EUC-KR (한국어 완성형)"
        "UTF-16LE" -> "UTF-16 LE"
        "UTF-16BE" -> "UTF-16 BE"
        else -> charset
    }
}
