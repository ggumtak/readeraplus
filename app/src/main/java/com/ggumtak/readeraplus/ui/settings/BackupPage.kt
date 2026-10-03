package com.ggumtak.readeraplus.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.data.AutoBackup
import com.ggumtak.readeraplus.data.Backup
import com.ggumtak.readeraplus.data.InstallState
import com.ggumtak.readeraplus.data.ReaderPresence
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "백업 및 복원": JSON export via ACTION_CREATE_DOCUMENT and import via ACTION_OPEN_DOCUMENT, and the daily 자동 백업
 * to shared storage that survives an uninstall (scroll SPEC §3.8): the switch, write now, restore from it, delete it.
 */
internal class BackupPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_BACKUP, "백업 및 복원") {
    private lateinit var statusText: TextView
    private var busy = false
    private var autoRow: View? = null
    private var autoStatus: TextView? = null
    /** The candidate list while open (dismissed with the page). */
    private var listDialog: android.app.Dialog? = null
    /** Left the stack: IO results that arrive later show no dialog. */
    private var destroyed = false

    override fun build(): View {
        val body = ctx.pageBody()
        body.section("백업", first = true)
        body.addView(ctx.row("백업 파일 만들기", "읽던 위치 · 즐겨찾기 등 표시 · 북마크 · 인용문 · 리뷰 · 컬렉션 · 설정을 JSON 파일 하나로 저장") {
            createBackup()
        })
        body.section("복원")
        body.addView(ctx.row("백업에서 복원", "백업 파일을 골라 기록과 설정을 되살립니다. 책은 경로, 없으면 파일 이름 + 크기로 찾습니다") {
            pickRestore()
        })
        statusText = ctx.note(lastBackupText()).also(body::addView)
        body.addView(ctx.note(
            "책 파일 자체는 백업에 들어가지 않습니다. 기기를 바꿨다면 책을 같은 폴더에 옮기고 '파일 스캔'을 한 번 한 뒤 복원하세요.",
        ))
        addAutoBackup(body)
        body.addView(ctx.note(R3Rows.BACKUP_MERGE))
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        refreshAuto()
    }

    override fun onResume() {
        // The all-files grant may have changed on the system screen.
        refreshAuto()
    }

    override fun onDestroy() {
        destroyed = true
        runCatching { listDialog?.dismiss() }
        listDialog = null
    }

    // ---------------------------------------------------------------- 자동 백업 (scroll SPEC §3.8)

    private fun addAutoBackup(body: LinearLayout) {
        body.section("자동 백업")
        val app = Settings.app
        autoRow = ctx.toggleRow(
            "자동 백업 (하루 한 번)",
            R3Rows.autoBackupSummary(StorageAccess.granted(ctx), location(), 0L),
            app.autoBackup,
        ) { on ->
            editApp { it.copy(autoBackup = on) }
            if (!on) runCatching { AutoBackup.cancelScheduled() }
        }.also(body::addView)
        body.addView(ctx.row("지금 자동 백업하기", "자동 백업 파일을 지금 한 번 저장합니다") { runAutoNow() })
        body.addView(ctx.row("자동 백업에서 복원", "자동 백업 파일과 내보낸 백업 파일을 찾아 골라서 되살립니다") { listAutoBackups() })
        body.addView(ctx.row("자동 백업 파일 지우기", "${location()}의 자동 백업 파일을 지웁니다") { deleteAutoFiles() })
        autoStatus = ctx.note("").apply { visibility = View.GONE }.also(body::addView)
        body.addView(ctx.note(R3Rows.BACKUP_PRIVACY))
    }

    /** "다운로드/ReaderaPlus/backup". */
    private fun location(): String = runCatching { AutoBackup.locationLabel() }.getOrDefault(LOCATION)

    private fun showAutoStatus(text: String) {
        autoStatus?.let {
            it.text = text
            it.setShown(true)
        }
    }

    /** The toggle summary needs the last write time (a prefs read) and the access state: read off main. */
    private fun refreshAuto() {
        val appCtx = activity.applicationContext
        activity.scope.launch {
            val (access, last) = withContext(Dispatchers.IO) {
                StorageAccess.granted(appCtx) to runCatching { AutoBackup.lastWrittenAt(appCtx) }.getOrDefault(0L)
            }
            autoRow?.setSummary(R3Rows.autoBackupSummary(access, location(), last))
            autoRow?.setToggleChecked(Settings.app.autoBackup)
        }
    }

    /** "지금 자동 백업하기": [AutoBackup.runNow] with force on IO; the outcome becomes the status line. */
    private fun runAutoNow() {
        if (busy) return
        busy = true
        showAutoStatus("자동 백업을 저장하는 중…")
        val appCtx = activity.applicationContext
        activity.scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { AutoBackup.runNow(appCtx, force = true) { ReaderPresence.inFront } }.getOrDefault(AutoBackup.Outcome.FAILED)
            }
            busy = false
            showAutoStatus(R3Rows.autoBackupOutcome(outcome, location()))
            refreshAuto()
        }
    }

    /** "자동 백업에서 복원": the candidates as a list (date · counts), then the existing confirm, restore and restart. */
    private fun listAutoBackups() {
        if (busy) return
        busy = true
        showAutoStatus("백업 파일을 찾는 중…")
        val appCtx = activity.applicationContext
        activity.scope.launch {
            val cands = withContext(Dispatchers.IO) { runCatching { AutoBackup.findCandidates(appCtx, includeOwn = true) }.getOrDefault(emptyList()) }
            busy = false
            if (destroyed) return@launch
            if (cands.isEmpty()) {
                showAutoStatus(
                    if (StorageAccess.granted(ctx)) "찾은 백업 파일이 없습니다" else "찾은 백업 파일이 없습니다. '모든 파일 접근'을 허용하면 이전 설치의 파일도 찾습니다",
                )
                return@launch
            }
            autoStatus?.setShown(false)
            val labels = cands.map { R3Rows.candidate(it.createdAt, it.summary, it.auto) }
            listDialog = ctx.alert()
                .setTitle("자동 백업에서 복원")
                .setItems(labels.toTypedArray()) { _, i -> confirmAutoRestore(cands[i]) }
                .setNegativeButton("취소", null)
                .showNoAnim()
        }
    }

    private fun confirmAutoRestore(c: AutoBackup.Candidate) {
        ctx.confirm(
            "백업에서 복원",
            "현재 서재의 읽기 기록 · 북마크 · 인용문과 설정을 백업 파일의 내용으로 덮어씁니다. 계속할까요?",
            ok = "복원",
        ) {
            if (busy) return@confirm
            busy = true
            statusText.text = "복원하는 중…"
            val appCtx = activity.applicationContext
            activity.scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { AutoBackup.restore(appCtx, c) } }
                busy = false
                result.onSuccess { n -> restored(n) }.onFailure { e ->
                    statusText.text = ErrorLines.withDetail(ErrorLines.line("복원 실패", e), e)
                }
            }
        }
    }

    /**
     * "자동 백업 파일 지우기". With all-files access (or legacy WRITE on API ≤ 29) every install's files go; without it
     * only this install's own MediaStore rows are visible, so only those are counted and deleted.
     */
    private fun deleteAutoFiles() {
        if (busy) return
        val appCtx = activity.applicationContext
        val others = canDeleteOthers()
        activity.scope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { AutoBackup.countFiles(appCtx, others) }.getOrDefault(0) }
            if (destroyed) return@launch
            if (n <= 0) {
                showAutoStatus("지울 자동 백업 파일이 없습니다")
                return@launch
            }
            ctx.confirm("자동 백업 파일 지우기", R3Rows.deleteAutoFiles(n, others), ok = "지우기") {
                if (busy) return@confirm
                busy = true
                activity.scope.launch {
                    val deleted = withContext(Dispatchers.IO) { runCatching { AutoBackup.deleteFiles(appCtx, others) }.getOrDefault(0) }
                    busy = false
                    showAutoStatus("자동 백업 파일 ${deleted}개를 지웠습니다")
                    refreshAuto()
                }
            }
        }
    }

    private fun canDeleteOthers(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun createBackup() {
        if (busy) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, SettingsFormat.backupFileName(System.currentTimeMillis()))
        start(intent, SettingsActivity.REQ_BACKUP_CREATE)
    }

    private fun pickRestore() {
        if (busy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        start(intent, SettingsActivity.REQ_BACKUP_RESTORE)
    }

    private fun start(intent: Intent, requestCode: Int) {
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, requestCode)
        } catch (_: Exception) {
            ctx.toast("파일 선택 화면을 열 수 없습니다")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != SettingsActivity.REQ_BACKUP_CREATE && requestCode != SettingsActivity.REQ_BACKUP_RESTORE) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        if (requestCode == SettingsActivity.REQ_BACKUP_CREATE) export(uri) else confirmRestore(uri)
        return true
    }

    private fun export(uri: Uri) {
        busy = true
        statusText.text = "백업 파일을 만드는 중…"
        val appCtx = activity.applicationContext
        activity.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = runCatching { appCtx.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: appCtx.contentResolver.openOutputStream(uri)
                        ?: error("파일을 열 수 없습니다")
                    out.use { Backup.export(appCtx, it) }
                    Settings.raw().edit().putLong(PREF_LAST_BACKUP_AT, System.currentTimeMillis()).apply()
                }.onFailure {
                    // Don't leave a truncated backup behind that a later restore would choke on.
                    runCatching { DocumentsContract.deleteDocument(appCtx.contentResolver, uri) }
                }
            }
            busy = false
            result.onSuccess {
                statusText.text = "백업을 저장했습니다.\n" + lastBackupText()
                ctx.toast("백업 완료")
            }.onFailure { e ->
                statusText.text = ErrorLines.withDetail(ErrorLines.line("백업 실패", e), e)
            }
        }
    }

    private fun confirmRestore(uri: Uri) {
        ctx.confirm(
            "백업에서 복원",
            "현재 서재의 읽기 기록 · 북마크 · 인용문과 설정을 백업 파일의 내용으로 덮어씁니다. 계속할까요?",
            ok = "복원",
        ) { restore(uri) }
    }

    private fun restore(uri: Uri) {
        busy = true
        statusText.text = "복원하는 중…"
        val appCtx = activity.applicationContext
        activity.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = appCtx.contentResolver.openInputStream(uri) ?: error("파일을 열 수 없습니다")
                    val n = input.use { Backup.import(appCtx, it) }
                    // A restored backup answers the reinstall offer too (scroll SPEC §3.4 step 5).
                    runCatching { InstallState.settleOffer(appCtx) }
                    n
                }
            }
            busy = false
            result.onSuccess { n -> restored(n) }.onFailure { e ->
                statusText.text = ErrorLines.withDetail(ErrorLines.line("복원 실패", e) + "\n올바른 리더플러스 백업 파일인지 확인하세요.", e)
            }
        }
    }

    private fun restored(n: Int) {
        statusText.text = "책 ${n}권의 기록을 복원했습니다."
        ctx.alert()
            .setTitle("복원 완료")
            .setMessage("책 ${n}권의 기록을 복원했습니다.\n열려 있던 책이 복원된 읽기 위치를 덮어쓰지 않도록, 앱을 다시 시작하는 것이 좋습니다.")
            .setPositiveButton("지금 다시 시작") { _, _ -> restartApp(activity) }
            .setCancelable(false)
            .setNegativeButton("나중에", null)
            .showNoAnim()
    }

    private fun lastBackupText(): String {
        val at = runCatching { Settings.raw().getLong(PREF_LAST_BACKUP_AT, 0L) }.getOrDefault(0L)
        return if (at > 0) "마지막 백업: ${SettingsFormat.dateTime(at)}" else "아직 백업한 적이 없습니다"
    }

    companion object {
        /** Raw pref (Long): time of the last successful backup export. */
        const val PREF_LAST_BACKUP_AT = "lastBackupAt"
        /** [AutoBackup.locationLabel]'s text, used if it cannot be read. */
        private const val LOCATION = "다운로드/ReaderaPlus/backup"
    }
}

/**
 * Restarts the app so in-memory settings caches pick up restored preferences: flushes pending
 * SharedPreferences writes (`apply()` is asynchronous and would be lost by killing the process), relaunches the
 * launcher activity in a fresh task and ends this process (the system starts a new one for the pending launch).
 */
internal fun restartApp(activity: SettingsActivity) {
    activity.scope.launch {
        // An empty commit() waits for every earlier apply() of this file to reach the disk.
        withContext(Dispatchers.IO) { runCatching { Settings.raw().edit().commit() } }
        val component = activity.packageManager.getLaunchIntentForPackage(activity.packageName)?.component
        val started = component != null && runCatching {
            activity.startActivity(Intent.makeRestartActivityTask(component).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.isSuccess
        activity.finishAffinity()
        if (started) Runtime.getRuntime().exit(0)
    }
}
