package com.ggumtak.readeraplus.update

import android.app.ActivityManager
import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInfoTest {
    private val base = "https://github.com/ggumtak/readeraplus/releases/download/latest/"

    private fun asset(
        name: String,
        url: String = base + name,
        size: Long = 24_508_766L,
        state: String = "uploaded",
        digest: String = "null",
    ) = """{"name":"$name","browser_download_url":"$url","size":$size,"state":"$state","digest":$digest}"""

    private fun release(vararg assets: String, tag: String = "latest", draft: Boolean = false) =
        """{"tag_name":"$tag","prerelease":true,"draft":$draft,"assets":[${assets.joinToString(",")}]}"""

    @Test
    fun patchMustMatchBothBuildsAndHaveTrustedSizesUrlsAndDigests() {
        val digest = "\"sha256:${"ab".repeat(32)}\""
        val apk = asset("ReaderaPlus-189.apk", size = 400, digest = digest)
        val name = "ReaderaPlus-181-189.rpd"
        val patch = asset(name, size = 100, digest = digest)
        val info = UpdateInfo.parse(release(patch, apk), 181)!!
        assertEquals(base + name, info.patch?.url)
        assertNull(UpdateInfo.parse(release(patch, apk), 182)!!.patch)
        for (bad in listOf(asset(name, size = 100), asset(name, size = 400, digest = digest),
            asset(name, url = base + "other", size = 20, digest = digest),
            asset("ReaderaPlus-181-188.rpd", size = 10, digest = digest))) {
            assertNull(UpdateInfo.parse(release(apk, bad), 181)!!.patch)
        }
        assertNull(UpdateInfo.parse(release(asset("ReaderaPlus-189.apk"), patch), 181)!!.patch)
    }

    @Test
    fun picksTheNewestUploadedApkWithItsSizeAndDigest() {
        val sha = "9f83c54a5a5503aed916a2f2420198dc71a772ca75806ace7f3759ed0facbdac"
        val latest = UpdateInfo.parse(
            release(asset("ReaderaPlus-188.apk"), asset("ReaderaPlus-189.apk", digest = "\"sha256:$sha\""), asset("notes.txt")),
        )!!
        assertEquals(189, latest.build)
        assertEquals(base + "ReaderaPlus-189.apk", latest.url)
        assertEquals(24_508_766L, latest.size)
        assertEquals(sha, latest.sha256)
        assertTrue(UpdateInfo.isNewer(latest, 188))
        assertFalse(UpdateInfo.isNewer(latest, 189))
        assertFalse(UpdateInfo.isNewer(latest, 190))
        assertNull(UpdateInfo.parse(release(asset("ReaderaPlus-189.apk")))!!.sha256)
    }

    @Test
    fun skipsIncompleteOrInvalidAssets() {
        val valid = asset("ReaderaPlus-189.apk")
        val result = UpdateInfo.parse(release(
            valid,
            asset("ReaderaPlus-190.apk", url = "https://github.com/other/repo/releases/download/latest/ReaderaPlus-190.apk"),
            asset("ReaderaPlus-191.apk", url = base + "ReaderaPlus-192.apk"),
            asset("ReaderaPlus-192.apk", state = "starter"),
            asset("ReaderaPlus-193.apk", size = 0),
            asset("ReaderaPlus-194.apk", size = UpdateInfo.MAX_APK_BYTES + 1),
            asset("ReaderaPlus-195.apk", digest = "\"sha256:invalid\""),
            asset("Hanja-196.apk"),
        ))
        assertEquals(189, result?.build)
        assertNull(UpdateInfo.parse(release(asset("ReaderaPlus-0.apk"))))
        assertNull(UpdateInfo.parse(release(valid, draft = true)))
        assertNull(UpdateInfo.parse(release(valid, tag = "other")))
        assertNull(UpdateInfo.parse("""{"message":"rate limited"}"""))
        assertNull(UpdateInfo.parse(""))
    }

    @Test
    fun automaticChecksAreSpacedOut() {
        val h12 = 12L * 3600_000
        assertTrue(UpdateInfo.due(1_000_000, 0, h12))
        assertFalse(UpdateInfo.due(h12, 1, h12))
        assertTrue(UpdateInfo.due(h12 + 1, 1, h12))
        assertTrue(UpdateInfo.due(5, 1_000, h12)) // the clock went back
    }

    @Test
    fun aCheckWithoutAnAnswerIsDueAgainAfterTheRetryTime() {
        val h12 = 12L * 60 * 60 * 1000
        val m30 = 30L * 60 * 1000
        val now = 100L * h12
        val stamp = UpdateInfo.retryStamp(now, h12, m30)
        assertFalse(UpdateInfo.due(now, stamp, h12))
        assertFalse(UpdateInfo.due(now + m30 - 1, stamp, h12))
        assertTrue(UpdateInfo.due(now + m30, stamp, h12))
        // A clock near zero (a device without a set time): the stamp stays a valid "checked" mark, never in the future.
        val early = UpdateInfo.retryStamp(1_000, h12, m30)
        assertTrue(early in 1..1_000)
    }

    @Test
    fun aWaitingUpdateIsInstalledAskedForOnceOrLeftToTheUsualCheck() {
        // Nothing waits: the usual check, whatever the permission.
        assertEquals(UpdateInfo.ResumeStep.CHECK, UpdateInfo.resumeStep(pending = false, permitted = true, asked = false))
        assertEquals(UpdateInfo.ResumeStep.CHECK, UpdateInfo.resumeStep(pending = false, permitted = false, asked = true))
        // Permitted: installs, also after the prompt (the user allowed it on the system page).
        assertEquals(UpdateInfo.ResumeStep.INSTALL, UpdateInfo.resumeStep(pending = true, permitted = true, asked = false))
        assertEquals(UpdateInfo.ResumeStep.INSTALL, UpdateInfo.resumeStep(pending = true, permitted = true, asked = true))
        // Not permitted: the prompt once, then the automatic check is no longer skipped.
        assertEquals(UpdateInfo.ResumeStep.ASK, UpdateInfo.resumeStep(pending = true, permitted = false, asked = false))
        assertEquals(UpdateInfo.ResumeStep.CHECK, UpdateInfo.resumeStep(pending = true, permitted = false, asked = true))
    }

    @Test
    fun theConfirmScreenIsShownOnlyInFront() {
        assertEquals(UpdateInfo.ConfirmStep.SHOW, UpdateInfo.confirmStep(foreground = true, hasIntent = true))
        // No screen: kept pending, installed on the next return.
        assertEquals(UpdateInfo.ConfirmStep.FOLD, UpdateInfo.confirmStep(foreground = false, hasIntent = true))
        assertEquals(UpdateInfo.ConfirmStep.FOLD, UpdateInfo.confirmStep(foreground = false, hasIntent = false))
        // In front without an intent: dropped with a word, not retried on every return.
        assertEquals(UpdateInfo.ConfirmStep.DROP, UpdateInfo.confirmStep(foreground = true, hasIntent = false))
    }

    @Test
    fun onlyAShownScreenCountsAsInFront() {
        assertEquals(100, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        assertEquals(125, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
        assertEquals(200, ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
        assertTrue(UpdateInfo.showsScreen(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(UpdateInfo.showsScreen(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE))
        // TTS reading in the background: its foreground service shows no screen.
        assertFalse(UpdateInfo.showsScreen(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertFalse(UpdateInfo.showsScreen(ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE))
        assertFalse(UpdateInfo.showsScreen(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE))
        assertFalse(UpdateInfo.showsScreen(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED))
        assertFalse(UpdateInfo.showsScreen(ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE))
        assertFalse(UpdateInfo.showsScreen(0))
    }

    @Test
    fun apkHeaderAndCachedNames() {
        assertTrue(UpdateInfo.looksLikeApk(byteArrayOf(0x50, 0x4B, 3, 4)))
        assertFalse(UpdateInfo.looksLikeApk("<htm".toByteArray()))
        assertFalse(UpdateInfo.looksLikeApk(byteArrayOf(0x50, 0x4B)))
        assertFalse(UpdateInfo.looksLikeApk(byteArrayOf(0x50, 0x4B, 5, 6)))
        assertEquals("ReaderaPlus-185.apk", UpdateInfo.fileName(185))
        assertEquals(185, UpdateInfo.buildOf("ReaderaPlus-185.apk"))
        assertEquals(0, UpdateInfo.buildOf("ReaderaPlus-185.apk.part"))
        assertEquals(0, UpdateInfo.buildOf("other.apk"))
    }

    @Test
    fun messages() {
        assertEquals(
            "새 버전이 있습니다 (빌드 185 · 현재 184).\n지금 업데이트할까요?\n설치가 끝나면 앱이 닫히므로 다시 열어 주세요.",
            UpdateInfo.offerMessage(185, 184),
        )
        assertEquals("내려받는 중… 40%", UpdateInfo.progressMessage(40))
        assertEquals("내려받는 중…", UpdateInfo.progressMessage(-1))
        assertNull(UpdateInfo.failureMessage(PackageInstaller.STATUS_SUCCESS))
        assertNull(UpdateInfo.failureMessage(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertTrue(UpdateInfo.failureMessage(PackageInstaller.STATUS_FAILURE_CONFLICT)!!.contains("서명"))
        assertEquals("업데이트하지 못했습니다", UpdateInfo.failureMessage(PackageInstaller.STATUS_FAILURE))
    }
}
