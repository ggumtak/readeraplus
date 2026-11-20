package com.ggumtak.readeraplus.update

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.ggumtak.readeraplus.ui.kit.toast

/** The installer's answer to [AppUpdater]'s session: shows its confirm screen, or says why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 34) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            // Decided here, when the installer asks, and nowhere else: a session the user already confirmed is never touched.
            val foreground = isForeground()
            val step = UpdateInfo.confirmStep(foreground, hasIntent = confirm != null)
            val shown = step == UpdateInfo.ConfirmStep.SHOW && confirm != null &&
                runCatching {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }.isSuccess
            if (!shown) {
                // Android 10+ ignores a start from the background without an error, so the session would wait for ever
                // without a word. Dropped (its abort status says nothing and keeps the download); the build waits again
                // and installs when the library or the settings list is next in front.
                val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                runCatching { AppUpdater.keepPending(context, intent.getIntExtra(AppUpdater.EXTRA_BUILD, 0)) }
                if (foreground) context.applicationContext.toast("설치 화면을 열지 못했습니다. 서재를 다시 열면 설치를 이어 갑니다")
            }
            return
        }
        val message = UpdateInfo.failureMessage(status) ?: return
        // A download that was cut short installs as "invalid" every time: the next try downloads again.
        runCatching { AppUpdater.forgetDownloads(context) }
        context.applicationContext.toast(message)
    }

    /**
     * Whether one of the app's screens shows (a running receiver alone does not count). Visible is enough: the system
     * lets an app with a visible window start an activity, and in split screen the unfocused half is only "visible".
     */
    private fun isForeground(): Boolean = runCatching {
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance <=
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }.getOrDefault(false)
}
