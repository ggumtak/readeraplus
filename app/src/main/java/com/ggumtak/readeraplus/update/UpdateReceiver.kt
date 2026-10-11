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
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            return
        }
        val message = UpdateInfo.failureMessage(status) ?: return
        // A download that was cut short installs as "invalid" every time: the next try downloads again.
        runCatching { AppUpdater.forgetDownloads(context) }
        context.applicationContext.toast(message)
    }
}
