package com.ggumtak.readeraplus.ui.library

import android.app.AlertDialog
import com.ggumtak.readeraplus.data.AutoBackup
import com.ggumtak.readeraplus.data.InstallState
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.settings.ErrorLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The restore offer of a fresh install (scroll SPEC §3.4): "이전 설정과 읽기 기록을 복원할까요?".
 *
 * [LibraryActivity] holds its scan while the offer is open (the first scan of a fresh install would otherwise add every
 * book before the backup's flags and positions arrive) and calls [start] from `refreshVisible` when
 * `InstallState.offerPending` and storage access is granted. Candidates are found on IO (headers only); none with
 * content settles the offer silently. The dialog is not cancelable and has no animation:
 * - [새로 시작]: settle the offer, release the scan; the old files stay on disk (BackupPage can still restore them);
 * - [다른 백업 보기] (only with more than one candidate): pick another backup, which shows the same dialog for it;
 * - [복원]: "복원하는 중…" on the status strip, restore on IO, toast "책 N권의 기록을 복원했습니다", start the first
 *   scan and recreate the library (list mode and sort come from the backup).
 *
 * [onReleased] runs on the main thread when the offer is over without a restore (the activity starts the held scan).
 */
internal class AutoRestorePrompt(private val activity: LibraryActivity, private val onReleased: () -> Unit) {

    /** Candidates are being read, or the dialog is up: a second `refreshVisible` must not start another round. */
    var active = false
        private set
    private var dialog: AlertDialog? = null

    /** Main thread. Finds the candidates on IO, then asks (or settles the offer when nothing is worth restoring). */
    fun start() {
        if (active) return
        active = true
        val app = activity.applicationContext
        activity.scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val cands = AutoBackup.findCandidates(app)
                    val best = AutoBackup.pickDefault(cands)
                    // The late-answer line: this install already has reading history of its own.
                    Triple(cands, best, best != null && Library.lastOpened() != null)
                }.getOrNull()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val best = found?.second
            if (found == null || best == null) {
                // None with content (or unreadable): never ask again on this install.
                settle()
                return@launch
            }
            ask(best, found.first, found.third)
        }
    }

    /** Closes the dialog without answering (the activity is going away; the offer stays pending). */
    fun dismiss() {
        active = false
        val d = dialog
        dialog = null
        d?.dismiss()
    }

    private fun ask(c: AutoBackup.Candidate, all: List<AutoBackup.Candidate>, lateAnswer: Boolean) {
        val s = c.summary
        val location = LibraryText.backupLocation(c.auto, c.file?.parentFile?.name, AutoBackup.locationLabel())
        val b = activity.alert()
            .setTitle("이전 기록 복원")
            .setMessage(LibraryText.restoreOfferMessage(c.createdAt, s.books, s.read, s.bookmarks, s.quotes, location, lateAnswer))
            .setCancelable(false)
            .setPositiveButton("복원") { _, _ -> dialog = null; restore(c) }
            .setNegativeButton("새로 시작") { _, _ -> dialog = null; settle() }
        if (all.size > 1) b.setNeutralButton("다른 백업 보기") { _, _ -> dialog = null; pickOther(all, c, lateAnswer) }
        dialog = b.showNoAnim()
    }

    /** The candidates as a list; Back or [취소] returns to the question for [current] (the offer is never lost). */
    private fun pickOther(all: List<AutoBackup.Candidate>, current: AutoBackup.Candidate, lateAnswer: Boolean) {
        val labels = all.map {
            LibraryText.backupChoice(it.createdAt, it.summary.books, it.summary.read, it.summary.bookmarks, it.summary.quotes, it.auto)
        }.toTypedArray()
        var next = current
        dialog = activity.alert()
            .setTitle("다른 백업 보기")
            .setSingleChoiceItems(labels, all.indexOf(current)) { d, which -> next = all[which]; d.dismiss() }
            .setNegativeButton("취소", null)
            .setOnDismissListener {
                dialog = null
                if (active && !activity.isFinishing && !activity.isDestroyed) ask(next, all, lateAnswer)
            }
            .showNoAnim()
    }

    private fun restore(c: AutoBackup.Candidate) {
        val app = activity.applicationContext
        activity.setLocalStatus("복원하는 중…")
        activity.scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val n = AutoBackup.restore(app, c)
                    // An empty commit: the restored prefs are on disk before the recreate reads them.
                    Settings.raw().edit().commit()
                    n
                }
            }
            activity.setLocalStatus(null)
            active = false
            r.onSuccess { n ->
                activity.toast(LibraryText.restoredMessage(n))
                // The first scan of this install, held until now; it runs on in the background over the recreate.
                LibraryJobs.startScan(app, announce = true)
                activity.recreate()
            }.onFailure {
                // The offer stays pending (asked again next visit); this visit scans as usual.
                activity.toast(ErrorLines.line("복원하지 못했습니다", it))
                onReleased()
            }
        }
    }

    private fun settle() {
        val app = activity.applicationContext
        activity.scope.launch {
            withContext(Dispatchers.IO) { runCatching { InstallState.settleOffer(app) } }
            active = false
            onReleased()
        }
    }
}
