package com.ggumtak.readeraplus.ui.settings

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.errorDetail
import com.ggumtak.readeraplus.ui.kit.ownMessage
import com.ggumtak.readeraplus.ui.kit.userMessage

/**
 * Error text of the library and settings screens: never a raw exception message or class name. The app's own
 * Korean sentences are kept (Backup, Library and the screens reject input with a reason written for users, e.g.
 * "리더플러스 백업 파일이 아닙니다"); platform errors read as [userMessage], and [detail] gives the class for a
 * small grey second line where there is room.
 */
internal object ErrorLines {
    /** [userMessage]'s catch-all ("작업을 완료하지 못했습니다"): after "스캔 실패" it tells nothing, so [line] leaves it out. */
    private val GENERIC = userMessage(RuntimeException())

    /** "스캔 실패: 저장 공간이 부족합니다", or just [what] when there is nothing useful to add. */
    fun line(what: String, t: Throwable): String = reason(t)?.let { "$what: $it" } ?: what

    /** The reason users can act on: the app's own sentence, else a specific [userMessage]; null for the catch-all. */
    fun reason(t: Throwable): String? = ownReason(t) ?: userMessage(t).takeIf { it != GENERIC }

    /** The app's own Korean sentence carried by [t], or null for platform text ([ownMessage]). */
    fun ownReason(t: Throwable): String? = ownMessage(t)

    /** "자세히: ZipException" for a platform error; null when the reason is the app's own sentence. */
    fun detail(t: Throwable): String? = if (ownReason(t) != null) null else errorDetail(t)

    /** [text] plus [detail] as a smaller gray last line (for message areas in black text). */
    fun withGrayDetail(text: CharSequence, t: Throwable): CharSequence {
        val d = detail(t) ?: return text
        val sb = SpannableStringBuilder(text).append('\n')
        val start = sb.length
        sb.append(d)
        sb.setSpan(ForegroundColorSpan(Ink.GRAY), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.8f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /** [text] plus [detail] as a plain last line (for notes that are gray already). */
    fun withDetail(text: String, t: Throwable): String = detail(t)?.let { "$text\n$it" } ?: text
}
