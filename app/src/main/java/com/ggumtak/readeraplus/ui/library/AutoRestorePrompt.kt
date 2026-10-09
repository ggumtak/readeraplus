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
import com.ggumtak.readeraplus.ui.settings.R3Rows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The restore offer of a fresh install (scroll SPEC §3.4): "이전 기록을 복원할까요?" over when, what and where.
 *
 * [LibraryActivity] holds its scan while the offer is open (the first scan of a fresh install would otherwise add every
 * book before the backup's flags and positions arrive) and calls [start] from `refreshVisible` when
 * `InstallState.offerPending` and storage access is granted. Candidates are found on IO (headers only); none with
 * content settles the offer silently. The dialog is not cancelable and has no animation:
 * - [새로 시작]: settle the offer, release the scan; the old files stay on disk (BackupPage can still restore them);
 * - [다른 백업] (only with more than one candidate): pick another backup, which shows the same dialog for it;
 * - [복원]: "복원하는 중…" on the status strip, restore on IO, toast "책 N권의 기록을 복원했습니다", start the first
 *   scan and recreate the library (list mode and sort come from the backup). The restore runs in the process, not
 *   the activity: a library recreated or reopened meanwhile ([busy]) holds its scan, shows "복원하는 중…" and takes
 *   the result instead of offering again.
 *
 * [onReleased] runs on the main thread when the offer is over without a restore (the activity starts the held scan).
 */
internal class AutoRestorePrompt(private val activity: LibraryActivity, private val onReleased: () -> Unit) {

    companion object {
        /** Process-wide: a restore runs past the activity that started it. Main thread only. */
        private val process = MainScope()
        private var restoring = false
        /** A finished restore's outcome no library has taken yet. */
        private var finished: Result<Int>? = null
        /** The prompt of the library alive now, which takes [finished]. */
        private var live: AutoRestorePrompt? = null

        /** A restore runs or its outcome waits: the library holds its scan and [start]s a prompt as for an offer. */
        val busy: Boolean get() = restoring || finished != null
    }

    /** Candidates are being read, or the dialog is up: a second `refreshVisible` must not start another round. */
    var active = false
        private set
    private var dialog: AlertDialog? = null

    /** Main thread. Finds the candidates on IO, then asks (or settles the offer when nothing is worth restoring). */
    fun start() {
        if (active) return
        active = true
        live = this
        if (restoring) {
            // Started by an earlier library: its outcome comes here ([deliver]).
            activity.setLocalStatus("복원하는 중…")
            return
        }
        if (finished != null) {
            deliver()
            return
        }
        val app = activity.applicationContext
        activity.scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val f = AutoBackup.search(app, includeOwn = false)
                    val best = AutoBackup.pickDefault(f.candidates)
                    // The late-answer line: this install already has reading history of its own.
                    Triple(f, best, best != null && Library.lastOpened() != null)
                }.getOrNull()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val best = found?.second
            if (found == null || best == null && found.first.unreadable > 0) {
                // Unreadable (IO error, or a listed file whose header failed): the offer stays pending (asked again
                // next visit); this visit scans.
                active = false
                onReleased()
                return@launch
            }
            if (best == null) {
                // None with content: never ask again on this install.
                settle()
                return@launch
            }
            ask(best, found.first.candidates, found.third)
        }
    }

    /** Closes the dialog without answering (the activity is going away; the offer stays pending). */
    fun dismiss() {
        if (live === this) live = null
        active = false
        val d = dialog
        dialog = null
        d?.dismiss()
    }

    private fun ask(c: AutoBackup.Candidate, all: List<AutoBackup.Candidate>, lateAnswer: Boolean) {
        val s = c.summary
        val location = LibraryText.backupLocation(c.auto, c.file?.parentFile?.name, AutoBackup.locationLabel())
        val b = activity.alert()
            .setTitle("이전 기록을 복원할까요?")
            .setMessage(LibraryText.restoreOfferMessage(c.createdAt, s.books, s.bookmarks, s.quotes, location, lateAnswer))
            .setCancelable(false)
            .setPositiveButton("복원") { _, _ -> dialog = null; restore(c) }
            .setNegativeButton("새로 시작") { _, _ -> dialog = null; settle() }
        if (all.size > 1) b.setNeutralButton("다른 백업") { _, _ -> dialog = null; pickOther(all, c, lateAnswer) }
        dialog = b.showNoAnim()
    }

    /**
     * The candidates as a list, in 설정's 복원 wording ([R3Rows.candidate]); Back or [취소] returns to the question for
     * [current] (the offer is never lost).
     */
    private fun pickOther(all: List<AutoBackup.Candidate>, current: AutoBackup.Candidate, lateAnswer: Boolean) {
        val labels = all.map { R3Rows.candidate(it.createdAt, it.summary, it.auto) }.toTypedArray()
        var next = current
        dialog = activity.alert()
            .setTitle("복원할 백업 고르기")
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
        restoring = true
        process.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val n = AutoBackup.restore(app, c)
                    // An empty commit: the restored prefs are on disk before the recreate reads them.
                    Settings.raw().edit().commit()
                    n
                }
            }
            restoring = false
            finished = r
            // This library, or the one recreated meanwhile; none alive: the next one takes it in [start].
            live?.deliver()
        }
    }

    /** Main thread: the finished restore's toast, then the held first scan and a recreate (failed: the scan only). */
    private fun deliver() {
        val r = finished ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        finished = null
        if (live === this) live = null
        val app = activity.applicationContext
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

    private fun settle() {
        val app = activity.applicationContext
        activity.scope.launch {
            withContext(Dispatchers.IO) { runCatching { InstallState.settleOffer(app) } }
            active = false
            onReleased()
        }
    }
}
