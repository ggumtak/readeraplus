package com.ggumtak.readeraplus.update

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
            val shown = confirm != null && runCatching {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }.isSuccess
            if (!shown) {
                // The app left the front meanwhile (Android 10+ starts nothing from the background): the session would
                // wait for ever without a word. Dropped; the download stays for the next try.
                val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                context.applicationContext.toast("설치 화면을 열지 못했습니다. 앱을 연 채로 업데이트를 다시 눌러 주세요")
            }
            return
        }
        val message = UpdateInfo.failureMessage(status) ?: return
        // A download that was cut short installs as "invalid" every time: the next try downloads again.
        runCatching { AppUpdater.forgetDownloads(context) }
        context.applicationContext.toast(message)
    }
}
