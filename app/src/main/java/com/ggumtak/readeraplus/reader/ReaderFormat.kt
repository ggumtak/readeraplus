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
 * Pure string formatting for the reader's chrome, panels and toasts (unit-tested; the page's status slots are
 * formatted without allocation by [StatusText]). Page numbers are shown as plain numbers even while the counts are
 * still estimates (no "~"): the estimate only settles into the exact number.
 */
object ReaderFormat {
    const val SEP = "  ·  "

    /** Shared TTS rate label in the reader and settings. */
    fun ttsRate(v: Float): String = String.format(Locale.US, "%.1f배", v)

    /** Shared TTS pitch label (pitch is a ratio, without the speed suffix). */
    fun ttsPitch(v: Float): String = String.format(Locale.US, "%.1f", v)

    /** Shared chooser labels for volume page direction. */
    fun volumeMode(mode: VolumeMode): String = when (mode) {
        VolumeMode.OFF -> "넘기지 않음 (볼륨 조절)"
        VolumeMode.DOWN_NEXT -> "아래 = 다음 페이지 (기본)"
        VolumeMode.UP_NEXT -> "위 = 다음 페이지 (방향 반전)"
    }

    /** Short value beside the popup row. */
    fun volumeModeShort(mode: VolumeMode): String = when (mode) {
        VolumeMode.OFF -> "끔"
        VolumeMode.DOWN_NEXT -> "아래 = 다음"
        VolumeMode.UP_NEXT -> "위 = 다음"
    }

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

    fun chapterLeft(pages: Int): String = if (pages <= 0) "챕터 마지막 쪽" else "챕터 ${pages}쪽 남음"

    /**
     * The footer's 회차 item (T1-5): "123/540화" — the episode [number] of the current TOC entry over the book's
     * [maxNumber] — when the titles carry numbers ([numbered]) and this entry has one, else "87/612", the entry's
     * place ([index], 0-based) among [count] TOC entries.
     */
    fun episodeLabel(numbered: Boolean, number: Int, maxNumber: Int, index: Int, count: Int): String =
        if (numbered && number > 0) "$number/${maxOf(maxNumber, number)}화" else "${index + 1}/${maxOf(count, index + 1)}"

    /** The footer's 남은 시간 item (T1-7): "이 화 3분" ([bookScope] false) or "책 7시간 20분". */
    fun timeLeft(bookScope: Boolean, minutes: Int): String = (if (bookScope) "책 " else "이 화 ") + duration(minutes)

    /** Minutes needed for [chars] characters at [charsPerMinute] (whole minutes, rounded down: "1분 미만" below one). */
    fun minutesFor(chars: Long, charsPerMinute: Int): Int {
        if (chars <= 0L) return 0
        return (chars / charsPerMinute.coerceAtLeast(1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** The end panel's reading time (T1-2): "읽은 시간 4시간 12분". */
    fun readTime(seconds: Long): String = "읽은 시간 " + durationOfSeconds(seconds)

    /** A TXT at least this big says "목차를 만드는 중…" while its index is built (A5). */
    const val BIG_TXT_BYTES = 4L * 1024 * 1024

    /**
     * The delayed loading text (shown after 300 ms): "목차를 만드는 중…" while a TXT of [sizeBytes] ≥ [BIG_TXT_BYTES]
     * is parsed in full ([buildingIndex]: no usable index, e.g. the first open after an index version change), else
     * "불러오는 중…".
     */
    fun loadingText(buildingIndex: Boolean, sizeBytes: Long): String =
        if (buildingIndex && sizeBytes >= BIG_TXT_BYTES) "목차를 만드는 중…" else "불러오는 중…"

    /**
     * A reading duration, the app's one wording (R2, T1-7): "1분 미만" (under a minute), "n분" (under an hour),
     * "h시간 m분" ("h시간" when m is 0 or h ≥ 10). Shared by the footer's 남은 시간, the end panel, the TOC header,
     * 책 정보 and the statistics page.
     */
    fun duration(minutes: Int): String {
        if (minutes < 1) return "1분 미만"
        if (minutes < 60) return "${minutes}분"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0 || h >= 10) "${h}시간" else "${h}시간 ${m}분"
    }

    /** [duration] of [seconds] (whole minutes, rounded down). */
    fun durationOfSeconds(seconds: Long): String =
        duration((seconds.coerceAtLeast(0) / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

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

    /** Seekbar drag preview: "1234쪽 · 제3장 …" (U polish 17). */
    fun previewLabel(page: Int, chapter: String?): String {
        val p = "${page}쪽"
        return if (chapter.isNullOrBlank()) p else "$p · ${chapter.trim()}"
    }

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
