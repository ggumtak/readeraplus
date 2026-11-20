package com.ggumtak.readeraplus.update

import android.app.ActivityManager
import android.content.pm.PackageInstaller
import org.json.JSONObject

/** Release selection, cache names and messages for the in-app update. */
internal object UpdateInfo {
    const val LATEST_URL = "https://api.github.com/repos/ggumtak/readeraplus/releases/tags/latest"
    const val MAX_APK_BYTES = 300L * 1024 * 1024

    private const val DOWNLOAD_BASE = "https://github.com/ggumtak/readeraplus/releases/download/latest/"
    private val APK_NAME = Regex("""ReaderaPlus-(\d{1,9})\.apk""")
    private val SHA256 = Regex("""sha256:([0-9a-fA-F]{64})""")

    data class Latest(val build: Int, val url: String, val size: Long, val sha256: String?)

    /** Pick the newest uploaded APK from the release CI publishes under the latest tag. */
    fun parse(text: String): Latest? {
        val release = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (release.optString("tag_name") != "latest" || release.optBoolean("draft")) return null
        val assets = release.optJSONArray("assets") ?: return null
        var newest: Latest? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("state") != "uploaded") continue
            val name = asset.optString("name")
            val build = buildOf(name)
            if (build <= (newest?.build ?: 0)) continue
            val url = asset.optString("browser_download_url")
            val size = asset.optLong("size", -1)
            if (url != DOWNLOAD_BASE + name || size !in 1..MAX_APK_BYTES) continue
            val digest = if (asset.isNull("digest")) "" else asset.optString("digest", "")
            val sha256 = SHA256.matchEntire(digest)?.groupValues?.get(1)?.lowercase()
            if (digest.isNotEmpty() && sha256 == null) continue
            newest = Latest(build, url, size, sha256)
        }
        return newest
    }

    fun isNewer(latest: Latest, currentBuild: Int): Boolean = latest.build > currentBuild

    /** An automatic check is due [interval] ms after the last one (or when the clock went back). */
    fun due(now: Long, last: Long, interval: Long): Boolean = last <= 0L || now < last || now - last >= interval

    /**
     * The "last check" to store after a check that got no answer, so that [due] holds again [retry] ms from [now]
     * instead of a whole [interval] later (never in the future: a clock that goes back makes a check due anyway).
     */
    fun retryStamp(now: Long, interval: Long, retry: Long): Long = (now - interval + retry).coerceIn(1L, now.coerceAtLeast(1L))

    /** What a screen coming to the front does about a download that waits to be installed. */
    enum class ResumeStep {
        /** Nothing waits, or its permission prompt was shown already: the usual (due) update check. */
        CHECK,
        /** The install permission is there: install the waiting download now. */
        INSTALL,
        /** The permission is missing and was never asked for this build: show the prompt once. */
        ASK,
    }

    /**
     * [pending]: a verified download waits; [permitted]: the app may install packages; [asked]: the permission prompt
     * was shown for that build already. Asking again on every visit would nag, and returning without checking would
     * skip the automatic check for ever, so a refused prompt falls back to [ResumeStep.CHECK] (the download is kept and
     * installed once the user allows it).
     */
    fun resumeStep(pending: Boolean, permitted: Boolean, asked: Boolean): ResumeStep = when {
        !pending -> ResumeStep.CHECK
        permitted -> ResumeStep.INSTALL
        !asked -> ResumeStep.ASK
        else -> ResumeStep.CHECK
    }

    /** What to do with the installer's confirm screen. */
    enum class ConfirmStep {
        /** Start it: the app is in front. */
        SHOW,
        /**
         * No screen of the app shows: drop the session and keep the download pending (a start from the background is
         * ignored without a word); the install goes on when the library or the settings list is next in front.
         */
        FOLD,
        /**
         * The app is in front but the screen cannot be opened (no intent, or starting it failed): drop the session
         * and say so, without keeping it pending, or every return to the library would fail the same way again.
         */
        DROP,
    }

    /** [foreground]: [showsScreen]; [hasIntent]: the installer sent its confirm intent. */
    fun confirmStep(foreground: Boolean, hasIntent: Boolean): ConfirmStep = when {
        !foreground -> ConfirmStep.FOLD
        hasIntent -> ConfirmStep.SHOW
        else -> ConfirmStep.DROP
    }

    /**
     * Whether the process [importance] (`ActivityManager.RunningAppProcessInfo`) means one of the app's screens shows:
     * in front (100) or visible (200, e.g. the unfocused half of split screen), both of which may start an activity. A
     * foreground service alone (125, TTS reading in the background) shows nothing, and a confirm screen started then
     * is ignored; split screen with that service running also reports 125 and folds (the install goes on on return).
     */
    fun showsScreen(importance: Int): Boolean =
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE

    /** An APK starts with a ZIP local file header. */
    fun looksLikeApk(head: ByteArray): Boolean = head.contentEquals(byteArrayOf(0x50, 0x4b, 3, 4))

    /** The cached download of [build]. */
    fun fileName(build: Int): String = "ReaderaPlus-$build.apk"

    /** The build number of a cached download, or 0. */
    fun buildOf(fileName: String): Int =
        APK_NAME.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun offerMessage(latest: Int, current: Int): String = "새 버전이 있습니다 (빌드 $latest · 지금 $current).\n지금 업데이트할까요?\n설치가 끝나면 앱이 닫힙니다. 다시 열어 주세요."

    fun progressMessage(percent: Int): String = if (percent < 0) "내려받는 중…" else "내려받는 중… $percent%"

    /** A failed install, in words; null when nothing should be said (the user cancelled, or it worked). */
    fun failureMessage(status: Int): String? = when (status) {
        PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> null
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "설치된 앱과 서명이 달라 업데이트할 수 없습니다"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "저장 공간이 부족해 업데이트하지 못했습니다"
        PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "업데이트 파일이 올바르지 않습니다"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "기기에서 설치를 막았습니다"
        else -> "업데이트하지 못했습니다"
    }
}
