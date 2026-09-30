package com.ggumtak.readeraplus.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.widget.TextView
import com.ggumtak.readeraplus.data.Backup
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "백업 및 복원": JSON export via ACTION_CREATE_DOCUMENT and import via ACTION_OPEN_DOCUMENT. */
internal class BackupPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_BACKUP, "백업 및 복원") {
    private lateinit var statusText: TextView
    private var busy = false

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
        return ctx.pageScroll(body)
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
                    input.use { Backup.import(appCtx, it) }
                }
            }
            busy = false
            result.onSuccess { n ->
                statusText.text = "책 ${n}권의 기록을 복원했습니다."
                ctx.alert()
                    .setTitle("복원 완료")
                    .setMessage("책 ${n}권의 기록을 복원했습니다.\n열려 있던 책이 복원된 읽기 위치를 덮어쓰지 않도록, 앱을 다시 시작하는 것이 좋습니다.")
                    .setPositiveButton("지금 다시 시작") { _, _ -> restartApp(activity) }
                    .setCancelable(false)
                    .setNegativeButton("나중에", null)
                    .showNoAnim()
            }.onFailure { e ->
                statusText.text = ErrorLines.withDetail(ErrorLines.line("복원 실패", e) + "\n올바른 리더플러스 백업 파일인지 확인하세요.", e)
            }
        }
    }

    private fun lastBackupText(): String {
        val at = runCatching { Settings.raw().getLong(PREF_LAST_BACKUP_AT, 0L) }.getOrDefault(0L)
        return if (at > 0) "마지막 백업: ${SettingsFormat.dateTime(at)}" else "아직 백업한 적이 없습니다"
    }

    companion object {
        /** Raw pref (Long): time of the last successful backup export. */
        const val PREF_LAST_BACKUP_AT = "lastBackupAt"
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
