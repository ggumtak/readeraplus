package com.ggumtak.readeraplus.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.view.View
import android.widget.LinearLayout
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.FileScanner
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.library.LibraryText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * "책 스캔": 스캔 (the all-files grant while it is missing, and 지금 스캔 with the scan's state as its summary), the
 * folders to scan (SAF tree picker or a typed path; none = the default places) and the folders to skip. A folder row
 * has a summary only to warn (outside every scan folder, not found).
 */
internal class ScanPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_SCAN, "책 스캔") {
    private lateinit var foldersBox: LinearLayout
    private lateinit var excludedBox: LinearLayout
    private var permRow: View? = null
    private var scanRow: View? = null
    private var accessNote: View? = null
    /** Bumped by each [fillFolders]: an older folder check that arrives late draws nothing. */
    private var fillGen = 0
    private val sink: (String) -> Unit = { text -> scanRow?.setSummary(text) }

    override fun build(): View {
        val body = ctx.pageBody()
        body.section("스캔")
        permRow = ctx.row("모든 파일 접근 권한", StorageAccess.summary(ctx)) { StorageAccess.request(activity) }.also(body::addView)
        // A scan started earlier (by another instance of this page, even in a previous activity) keeps running;
        // its latest status shows here and its updates arrive through [sink].
        scanRow = ctx.row("지금 스캔", if (ScanState.running) ScanState.status ?: "스캔 중…" else lastScanText()) { scanNow() }
            .also(body::addView)
        ScanState.sink = sink

        body.section("스캔할 폴더")
        body.addView(ctx.note("추가한 폴더(하위 폴더 포함)만 찾습니다. 없으면 아래 기본 위치를 찾습니다."))
        foldersBox = ctx.vertical().also(body::addView)
        accessNote = ctx.note("모든 파일 접근을 허용해야 이 폴더를 읽을 수 있습니다.").apply { visibility = View.GONE }.also(body::addView)
        body.addView(ctx.buttonBar(
            ctx.textButton("폴더 추가") { pickFolder(SettingsActivity.REQ_ADD_SCAN_FOLDER) },
            ctx.textButton("경로 입력") { typePath(excluded = false) },
        ))

        body.section("제외할 폴더")
        excludedBox = ctx.vertical().also(body::addView)
        body.addView(ctx.buttonBar(
            ctx.textButton("폴더 추가") { pickFolder(SettingsActivity.REQ_ADD_EXCLUDED_FOLDER) },
            ctx.textButton("경로 입력") { typePath(excluded = true) },
        ))
        body.addView(ctx.note("숨김 폴더와 Android/data · obb는 늘 건너뜁니다."))

        updateAccess()
        fillFolders()
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        updateAccess()
        ScanState.sink = sink
    }

    override fun onResume() {
        // Back from the system's permission page.
        updateAccess()
    }

    override fun onDestroy() {
        if (ScanState.sink === sink) ScanState.sink = null
    }

    /** The permission row shows only while all-files access is missing; so does the note under added folders. */
    private fun updateAccess() {
        val granted = StorageAccess.granted(ctx)
        permRow?.setShown(!granted)
        accessNote?.setShown(!granted && Settings.app.scanFolders.isNotEmpty())
    }

    /** Both folder lists, built once after one check of every path on IO (and of the default places when none). */
    private fun fillFolders() {
        val app = Settings.app
        val gen = ++fillGen
        val appCtx = activity.applicationContext
        activity.scope.launch {
            val (missing, roots) = withContext(Dispatchers.IO) {
                val all = app.scanFolders + app.excludedFolders
                all.filterNot { StorageAccess.isReadableDir(it) }.toSet() to
                    if (app.scanFolders.isEmpty()) {
                        runCatching { FileScanner.defaultRoots(appCtx).map { it.absolutePath } }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
            }
            if (gen != fillGen) return@launch
            foldersBox.removeAllViews()
            if (app.scanFolders.isEmpty()) {
                foldersBox.addView(ctx.note(
                    if (roots.isEmpty()) "기본 위치: 내부 저장소 전체"
                    else "기본 위치:\n" + roots.joinToString("\n") { "· " + FolderSets.displayName(it, StorageAccess.primaryRoot()) },
                ))
            } else {
                for (p in FolderSets.sorted(app.scanFolders)) foldersBox.addView(folderRow(p, excluded = false, p in missing))
            }
            excludedBox.removeAllViews()
            if (app.excludedFolders.isEmpty()) {
                excludedBox.addView(ctx.note("없음"))
            } else {
                for (p in FolderSets.sorted(app.excludedFolders)) excludedBox.addView(folderRow(p, excluded = true, p in missing))
            }
            updateAccess()
        }
    }

    /** One folder: its friendly name, and a summary only for a warning ([missing]: not found or not readable). */
    private fun folderRow(path: String, excluded: Boolean, missing: Boolean): View {
        val remove = ctx.iconButton(R.drawable.ic_close, "목록에서 빼기") {
            editApp {
                if (excluded) it.copy(excludedFolders = FolderSets.remove(it.excludedFolders, path))
                else it.copy(scanFolders = FolderSets.remove(it.scanFolders, path))
            }
            fillFolders()
        }
        val warnings = listOfNotNull(
            "스캔할 폴더 밖이라 효과 없음".takeIf { excluded && FolderSets.exclusionHasNoEffect(Settings.app.scanFolders, path) },
            "찾을 수 없는 폴더".takeIf { missing },
        )
        return ctx.row(FolderSets.displayName(path, StorageAccess.primaryRoot()), warnings.joinToString(" · ").ifEmpty { null }, remove)
    }

    private fun pickFolder(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, requestCode)
        } catch (_: Exception) {
            ctx.toast("폴더 선택 화면을 열 수 없습니다. ‘경로 입력’을 쓰세요")
        }
    }

    private fun typePath(excluded: Boolean) {
        val initial = StorageAccess.primaryRoot() + "/"
        ctx.prompt(if (excluded) "제외할 폴더 경로" else "스캔할 폴더 경로", initial, "/storage/emulated/0/Books") { text ->
            addPath(FolderSets.normalize(text), excluded, checkExists = true)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != SettingsActivity.REQ_ADD_SCAN_FOLDER && requestCode != SettingsActivity.REQ_ADD_EXCLUDED_FOLDER) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        val excluded = requestCode == SettingsActivity.REQ_ADD_EXCLUDED_FOLDER
        val appCtx = activity.applicationContext
        activity.scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching { appCtx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                treeToPath(appCtx, uri)
            }
            if (path == null) {
                ctx.toast("이 위치는 쓸 수 없습니다. 내부 저장소나 SD 카드의 폴더를 고르세요")
            } else {
                addPath(path, excluded, checkExists = false)
            }
        }
        return true
    }

    /** Tree URI → filesystem path; mounted volumes are resolved through StorageManager (blocking). */
    private fun treeToPath(context: Context, uri: Uri): String? {
        val root = StorageAccess.primaryRoot()
        val volumes = volumeRoots(context)
        val viaId = runCatching { TreePaths.fromDocumentId(DocumentsContract.getTreeDocumentId(uri), root, volumes) }.getOrNull()
        return viaId ?: TreePaths.fromTreeUri(uri.toString(), root, volumes)
    }

    /** Lower-case volume UUID → mount directory for every mounted removable volume (API 30+; else empty). */
    private fun volumeRoots(context: Context): Map<String, String> {
        if (Build.VERSION.SDK_INT < 30) return emptyMap()
        return runCatching {
            val sm = context.getSystemService(StorageManager::class.java) ?: return@runCatching emptyMap()
            val out = HashMap<String, String>()
            for (v in sm.storageVolumes) {
                val uuid = v.uuid ?: continue
                val dir = v.directory?.absolutePath ?: continue
                out[uuid.lowercase(Locale.ROOT)] = dir
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun addPath(path: String, excluded: Boolean, checkExists: Boolean) {
        activity.scope.launch {
            if (checkExists) {
                val ok = withContext(Dispatchers.IO) { StorageAccess.isReadableDir(path) }
                if (!ok) {
                    ctx.toast("폴더를 찾을 수 없거나 읽을 수 없습니다")
                    return@launch
                }
            }
            val app = Settings.app
            val result = FolderSets.add(if (excluded) app.excludedFolders else app.scanFolders, path)
            if (!result.added) {
                ctx.toast(result.message)
                return@launch
            }
            if (excluded && FolderSets.exclusionHidesScanFolder(app.scanFolders, path)) {
                ctx.confirm(
                    "스캔할 폴더를 제외할까요?",
                    "‘${FolderSets.displayName(path, StorageAccess.primaryRoot())}’에는 스캔할 폴더가 들어 있어서, 그 폴더의 책을 모두 찾지 않게 됩니다.",
                    ok = "제외",
                ) { commitAdd(result, excluded) }
                return@launch
            }
            commitAdd(result, excluded)
            if (excluded && FolderSets.exclusionHasNoEffect(app.scanFolders, path)) {
                ctx.toast("추가했지만 스캔할 폴더 밖이라 효과가 없습니다")
            } else {
                ctx.toast(result.message)
            }
        }
    }

    private fun commitAdd(result: FolderSets.AddResult, excluded: Boolean) {
        editApp { if (excluded) it.copy(excludedFolders = result.folders) else it.copy(scanFolders = result.folders) }
        fillFolders()
        if (!excluded) offerScan()
    }

    private fun offerScan() {
        if (ScanState.running) return
        ctx.confirm("지금 스캔할까요?", "추가한 폴더에서 책을 찾아 서재에 넣습니다.", ok = "스캔") { scanNow() }
    }

    private fun scanNow() {
        if (ScanState.running) {
            ctx.toast("이미 스캔 중입니다")
            return
        }
        ScanState.running = true
        ScanState.publish(if (StorageAccess.granted(ctx)) "스캔 중…" else "스캔 중… (권한이 없어 일부 폴더는 못 읽을 수 있음)")
        val appCtx = activity.applicationContext
        // Process-level job: the scan must finish (and clear ScanState.running) even if this activity goes away.
        ScanState.scope.launch {
            var lastPost = 0L
            val result = runCatching {
                val n = FileScanner.scan(appCtx) { found ->
                    // Throttled: every text change is an e-ink refresh.
                    val now = System.currentTimeMillis()
                    if (now - lastPost >= 500) {
                        lastPost = now
                        ScanState.post("스캔 중… 책 파일 ${found}개 찾음")
                    }
                }
                Settings.raw().edit().putLong(SettingsActivity.PREF_LAST_SCAN_AT, System.currentTimeMillis()).apply()
                n
            }
            val done = result.fold(
                onSuccess = { n -> "${LibraryText.scanDoneMessage(n)} · ${SettingsFormat.dateTime(System.currentTimeMillis())}" },
                onFailure = { e -> ErrorLines.withDetail(ErrorLines.line("스캔 실패", e), e) },
            )
            ScanState.finish(appCtx, done, result.getOrNull()?.let { LibraryText.scanDoneMessage(it) })
        }
    }

    private fun lastScanText(): String {
        val at = runCatching { Settings.raw().getLong(SettingsActivity.PREF_LAST_SCAN_AT, 0L) }.getOrDefault(0L)
        return if (at > 0) "마지막 스캔: ${SettingsFormat.dateTime(at)}" else "아직 스캔하지 않음"
    }
}

/**
 * Process-wide state of the "지금 스캔" run: one scan at a time, and its status reaches whichever ScanPage is
 * currently shown (the page that started it may be gone). [sink] and [status] are main-thread only.
 */
internal object ScanState {
    @Volatile var running = false
    /** Latest status line while running / right after finishing; null = show the last-scan time. */
    var status: String? = null
        private set
    var sink: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    /** Scope of the scan job; never cancelled (the blocking scan can't be interrupted anyway). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Main thread. */
    fun publish(text: String) {
        status = text
        sink?.invoke(text)
    }

    /** Any thread. */
    fun post(text: String) {
        main.post { if (running) publish(text) }
    }

    /**
     * Any thread: ends the run and shows [text] on the scan page. [toast] is shown (through [appContext], which
     * outlives pages) only when no scan page is there to show the result.
     */
    fun finish(appContext: Context, text: String, toast: String?) {
        main.post {
            running = false
            val shown = sink != null
            publish(text)
            if (toast != null && !shown) appContext.toast(toast)
        }
    }
}
