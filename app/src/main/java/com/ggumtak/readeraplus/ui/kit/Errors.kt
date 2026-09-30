package com.ggumtak.readeraplus.ui.kit

import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

/**
 * A short Korean sentence for an error shown to the user (never a raw exception message or class name). Pair it
 * with [errorDetail] when a small grey "자세히" line helps a bug report.
 */
fun userMessage(t: Throwable): String = when {
    t is OutOfMemoryError -> "메모리가 부족합니다"
    isNoSpace(t) -> "저장 공간이 부족합니다"
    t is FileNotFoundException -> "파일을 찾을 수 없습니다"
    t is SecurityException -> "파일 접근 권한이 없습니다"
    t is ZipException -> "파일이 손상되었습니다"
    t is IOException -> "파일을 읽거나 쓰지 못했습니다"
    else -> "작업을 완료하지 못했습니다"
}

/** "자세히: ZipException" — the exception class only (messages can hold paths and are not for users). */
fun errorDetail(t: Throwable): String = "자세히: ${t.javaClass.simpleName}"

/** True when [t] or one of its causes reports a full disk (ENOSPC). */
fun isNoSpace(t: Throwable): Boolean {
    var c: Throwable? = t
    var depth = 0
    while (c != null && depth++ < 8) {
        val m = c.message
        if (m != null && (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true))) return true
        c = c.cause
    }
    return false
}
