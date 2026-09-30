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

/**
 * The app's own Korean sentence carried by [t] (code that rejects input throws one written for users, e.g.
 * "TTF/OTF 글꼴 파일이 아닙니다"), or null for platform text: no Korean, too long for a toast, a wrapped exception, or
 * a path or URI (a platform message can hold Korean in a file name: "…/Download/나눔명조.ttf: open failed"). A full
 * disk or memory never counts as such: [userMessage] names those better.
 */
fun ownMessage(t: Throwable): String? {
    if (t is OutOfMemoryError || isNoSpace(t)) return null
    val m = t.message?.trim() ?: return null
    if (m.isEmpty() || m.length > MAX_OWN_MESSAGE || m.none { it in '가'..'힣' }) return null
    if ("Exception" in m || PATH_IN_MESSAGE.containsMatchIn(m)) return null
    return m
}

/** Longer than this is not a sentence the app wrote for a toast. */
private const val MAX_OWN_MESSAGE = 80

/**
 * A path or URI: a '/' that starts one ("/storage/…", "at /x", "content://…"), two in one word ("Books/a/b.txt"), or
 * one before a Korean file or folder name ("primary:Download/소설.txt"). A slash between two ASCII words
 * ("TTF/OTF") is not one.
 */
private val PATH_IN_MESSAGE = Regex("""(^|[\s:=(\[])/|/\S*/|/[^\x00-\x7F]""")

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
