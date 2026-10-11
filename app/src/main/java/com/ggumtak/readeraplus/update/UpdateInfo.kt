package com.ggumtak.readeraplus.update

import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The pure half of the in-app update ([AppUpdater]): what the Drive web app (tools/gdrive/upload.gs, `?what=latest`)
 * answers, whether it is newer than this build, where its APK downloads from, and the messages. Unit-tested.
 */
internal object UpdateInfo {
    /** The newest build in the user's Drive folder: its CI run number and its Drive file id. */
    class Latest(val build: Int, val fileId: String)

    private val FILE_ID = Regex("[A-Za-z0-9_-]{10,200}")

    /** The web app's answer `{"build":184,"id":"…"}`; null for anything else (no build yet, an error page). */
    fun parse(text: String): Latest? {
        val o = try {
            JSONObject(text.trim())
        } catch (e: Exception) {
            return null
        }
        val build = o.optInt("build", 0)
        val id = o.optString("id", "")
        if (build <= 0 || !FILE_ID.matches(id)) return null
        return Latest(build, id)
    }

    fun isNewer(latest: Latest, currentBuild: Int): Boolean = latest.build > currentBuild

    /** [base] (the web app's /exec URL) asking for the newest build. */
    fun checkUrl(base: String): String = base + (if ('?' in base) "&" else "?") + "what=latest"

    /** The direct download of a Drive file shared by link (no "can't scan for viruses" page in between). */
    fun downloadUrl(fileId: String): String =
        "https://drive.usercontent.google.com/download?id=" + URLEncoder.encode(fileId, "UTF-8") + "&export=download&confirm=t"

    /** An automatic check is due [interval] ms after the last one (or when the clock went back). */
    fun due(now: Long, last: Long, interval: Long): Boolean = last <= 0L || now < last || now - last >= interval

    /** An APK is a zip: it starts with "PK". A Drive error page (HTML) does not. */
    fun looksLikeApk(head: ByteArray, n: Int): Boolean = n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

    /** The cached download of [build]. */
    fun fileName(build: Int): String = "ReaderaPlus-$build.apk"

    /** The build number of a cached download, or 0. */
    fun buildOf(fileName: String): Int =
        Regex("ReaderaPlus-(\\d{1,9})\\.apk").matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun offerMessage(latest: Int, current: Int): String = "새 버전이 있습니다 (빌드 $latest · 지금 $current).\n지금 업데이트할까요?"

    fun progressMessage(percent: Int): String = if (percent < 0) "내려받는 중…" else "내려받는 중… $percent%"

    /** A failed install, in words; null when nothing should be said (the user cancelled, or it worked). */
    fun failureMessage(status: Int): String? = when (status) {
        PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> null
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "설치된 앱과 서명이 달라 업데이트할 수 없습니다. 앱을 지우고 새로 설치해 주세요"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "저장 공간이 부족해 업데이트하지 못했습니다"
        PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "업데이트 파일이 올바르지 않습니다"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "기기에서 설치를 막았습니다"
        else -> "업데이트하지 못했습니다"
    }
}
