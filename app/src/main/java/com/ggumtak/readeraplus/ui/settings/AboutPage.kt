package com.ggumtak.readeraplus.ui.settings

import android.app.Dialog
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.BuildConfig
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.reader.LightProbe
import com.ggumtak.readeraplus.render.Eink
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.fullScreenDialog
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.keepAll
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.sectionHeader
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.toolbar
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.Settings as SystemSettings

/**
 * "정보": the app's name, version, what it is and what it sends (nothing), the font / icon licenses
 * (assets/fonts/licenses/…), and 문제 해결: one folded row that opens in place on the device info (with 최근 종료) and
 * 조명 진단 (how this device's front light answers), both for the user's report. Their readouts run on IO the first time
 * the row is opened, not when the page opens.
 */
internal class AboutPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_ABOUT, "정보") {
    private lateinit var licensesBox: LinearLayout
    /** License viewer while open (dismissed with the page so its window never leaks). */
    private var textDialog: Dialog? = null
    private var watchButton: TextView? = null
    private var watchText: TextView? = null
    /** The change watch while running: its observer (registered after the snapshot) and its 30 s stop. */
    private var watchObserver: ContentObserver? = null
    private var watchStop: Runnable? = null
    /** Posts and cancels the watch's 30 s stop (the same handler must do both). */
    private val main = Handler(Looper.getMainLooper())

    override fun build(): View {
        val body = ctx.pageBody()
        val head = ctx.vertical { setPadding(ctx.dp(16), ctx.dp(20), ctx.dp(16), ctx.dp(8)) }
        head.addView(ctx.label(ctx.getString(R.string.app_name), 24f, bold = true))
        head.addView(ctx.label("버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", 15f, color = Ink.GRAY).apply {
            setPadding(0, ctx.dp(6), 0, 0)
        })
        head.addView(ctx.label("e-ink 리더기를 위한 TXT·EPUB 리더", 15f).apply { setPadding(0, ctx.dp(10), 0, 0) })
        head.addView(ctx.label(
            keepAll("인터넷으로 아무것도 보내지 않습니다. ‘Wi-Fi로 책 받기’ 화면이 열려 있을 때만 같은 Wi-Fi에서 파일을 받습니다."),
            14f,
            color = Ink.GRAY,
        ).apply { setPadding(0, ctx.dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
        body.addView(head)

        body.section("라이선스")
        licensesBox = ctx.vertical().also(body::addView)
        body.addView(ctx.note("아이콘: Material Symbols (Google, Apache License 2.0)\n글꼴: 모두 SIL 오픈 폰트 라이선스 1.1 (앱 안에서만 사용, 수정 없음)"))
        loadLicenses()

        body.section("문제 해결")
        val details = ctx.vertical { visibility = View.GONE }
        val chevron: ImageView = ctx.icon(R.drawable.ic_expand_more, 24)
        body.addView(ctx.row("기기 정보·조명 진단", "문제를 알릴 때 복사해 보내세요", chevron) {
            val open = details.visibility != View.VISIBLE
            if (open && details.childCount == 0) {
                addDeviceInfo(details)
                addLightDiagnostics(details)
            }
            details.visibility = if (open) View.VISIBLE else View.GONE
            chevron.setImageResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        })
        body.addView(details, lp())
        return ctx.pageScroll(body)
    }

    // ---------------------------------------------------------------- 기기 정보 · 조명 진단 (UI_SPEC §4.6, brightness.md §5.7)

    /** The device lines (read on IO) and [기기 정보 복사]. */
    private fun addDeviceInfo(box: LinearLayout) {
        val infoBox = ctx.vertical().also(box::addView)
        infoBox.addView(ctx.note("불러오는 중…"))
        var info: List<Pair<String, String>> = emptyList()
        box.addView(ctx.buttonBar(ctx.textButton("기기 정보 복사") {
            val text = info.joinToString("\n") { "${it.first}: ${it.second}" }
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("기기 정보", text))
            }
            ctx.toast("복사했습니다")
        }))
        activity.scope.launch {
            val (vendor, exits) = withContext(Dispatchers.IO) {
                runCatching { Eink.vendorName() }.getOrNull() to recentExits()
            }
            info = deviceInfo(vendor) + if (exits != null) listOf("최근 종료" to exits) else emptyList()
            infoBox.removeAllViews()
            for ((k, v) in info) infoBox.addView(ctx.infoRow(k, v))
        }
    }

    /** [LightProbe.report] lines, read on IO; tapping a line copies it. Plus the 30 s change watch. */
    private fun addLightDiagnostics(body: LinearLayout) {
        // A heading inside the folded group: no line above it (it is not a section of the page).
        body.addView(ctx.sectionHeader("조명 진단"))
        val box = ctx.vertical().also(body::addView)
        box.addView(ctx.note("불러오는 중…"))
        watchButton = ctx.textButton(WATCH_LABEL) { startWatch() }
        body.addView(ctx.buttonBar(watchButton!!))
        watchText = ctx.note("기기의 조명 막대를 움직이는 동안 바뀌는 시스템 설정을 30초 동안 기록합니다.").also(body::addView)
        val appCtx = activity.applicationContext
        activity.scope.launch {
            val lines = withContext(Dispatchers.IO) {
                runCatching { LightProbe.report(appCtx) }.getOrElse { listOf(ErrorLines.line("조명 정보를 읽지 못했습니다", it)) }
            }
            box.removeAllViews()
            if (lines.isEmpty()) box.addView(ctx.note("조명 정보 없음"))
            for (line in lines) box.addView(ctx.row(line, null) { copy("조명 진단", line) })
        }
    }

    private fun copy(label: String, text: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        }
        ctx.toast("복사했습니다")
    }

    /**
     * [변화 감지 30초]: observes `Settings.System` (with descendants) for 30 s and lists every key that changed with its
     * first and latest value ("screen_brightness 102 → 60"). The values are read on IO; the list is redrawn only when
     * a change arrives (no timer ticks on e-ink).
     */
    private fun startWatch() {
        if (watchObserver != null || watchStop != null) return
        val resolver = ctx.contentResolver
        val appCtx = activity.applicationContext
        val before = HashMap<String, String?>()
        val after = LinkedHashMap<String, String?>()
        watchButton?.text = "감지 중 (30초)…"
        watchText?.text = "기기의 조명 막대를 움직여 보세요."
        activity.scope.launch {
            val snapshot = withContext(Dispatchers.IO) { runCatching { systemValues(appCtx) }.getOrDefault(emptyMap()) }
            before.putAll(snapshot)
            if (watchStop == null) return@launch // stopped (page closed) while reading
            val observer = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    val key = uri?.lastPathSegment ?: return
                    activity.scope.launch {
                        val value = withContext(Dispatchers.IO) { runCatching { SystemSettings.System.getString(appCtx.contentResolver, key) }.getOrNull() }
                        if (watchObserver == null) return@launch
                        after[key] = value
                        watchText?.text = after.entries.joinToString("\n") { (k, v) -> "$k ${before[k] ?: "없음"} → ${v ?: "없음"}" }
                    }
                }
            }
            runCatching { resolver.registerContentObserver(SystemSettings.System.CONTENT_URI, true, observer) }
                .onFailure {
                    stopWatch()
                    watchText?.text = ErrorLines.line("변화를 감지할 수 없습니다", it)
                    return@launch
                }
            watchObserver = observer
        }
        val stop = Runnable {
            stopWatch()
            if (after.isEmpty()) watchText?.text = "30초 동안 바뀐 설정이 없습니다."
            else watchText?.append("\n(30초 감지 끝)")
        }
        watchStop = stop
        main.postDelayed(stop, WATCH_MS)
    }

    private fun stopWatch() {
        watchStop?.let(main::removeCallbacks)
        watchStop = null
        watchObserver?.let { runCatching { ctx.contentResolver.unregisterContentObserver(it) } }
        watchObserver = null
        watchButton?.text = WATCH_LABEL
    }

    /** Blocking (IO): every `Settings.System` key and value readable by apps. */
    private fun systemValues(context: Context): Map<String, String?> {
        val out = HashMap<String, String?>()
        context.contentResolver.query(SystemSettings.System.CONTENT_URI, arrayOf("name", "value"), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { out[it] = c.getString(1) }
        }
        return out
    }

    private fun loadLicenses() {
        licensesBox.addView(ctx.note("불러오는 중…"))
        activity.scope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching { activity.assets.list(LICENSE_DIR)?.toList().orEmpty() }.getOrDefault(emptyList())
                    .filter { it.endsWith(".txt", ignoreCase = true) }
                    .sortedWith(compareBy<String> { it != "FONTS.txt" }.thenBy { it.lowercase() })
            }
            licensesBox.removeAllViews()
            if (files.isEmpty()) {
                licensesBox.addView(ctx.note("라이선스 파일을 찾을 수 없습니다."))
                return@launch
            }
            for (f in files) {
                val title = licenseTitle(f)
                licensesBox.addView(ctx.navRow(title, null) { showText(title, "$LICENSE_DIR/$f") })
            }
        }
    }

    /** A license file's name in Korean (an unknown file keeps its own name). */
    private fun licenseTitle(file: String): String = when (file) {
        "FONTS.txt" -> "글꼴 목록과 저작권"
        "NanumFonts-OFL.txt" -> "나눔 글꼴 라이선스"
        "OFL-1.1.txt" -> "SIL 오픈 폰트 라이선스 1.1"
        "Pretendard-OFL.txt" -> "프리텐다드 라이선스"
        "SUIT-OFL.txt" -> "SUIT 라이선스"
        else -> file.removeSuffix(".txt")
    }

    private fun showText(title: String, assetPath: String) {
        val text = ctx.label("불러오는 중…", 14f).apply {
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(24))
            setLineSpacing(0f, 1.15f)
            // Not selectable: a long-press on a page-long text would start a selection (and its handles) by accident.
            setTextIsSelectable(false)
        }
        val scroll = ctx.pageScroll(ctx.vertical().apply { addView(text, lp()) })
        var dialog: Dialog? = null
        val root = ctx.vertical { setBackgroundColor(Ink.WHITE) }
        root.addView(ctx.toolbar(title, R.drawable.ic_arrow_back, onNav = { dialog?.dismiss() }), lp())
        root.addView(scroll, lp(MATCH_PARENT, 0, 1f))
        dialog = ctx.fullScreenDialog(root)
        dialog.setOnDismissListener { if (textDialog === dialog) textDialog = null }
        textDialog = dialog
        dialog.show()
        activity.scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching { activity.assets.open(assetPath).use { String(it.readBytes(), Charsets.UTF_8) } }
                    .getOrElse { ErrorLines.withGrayDetail("파일을 읽지 못했습니다", it) }
            }
            text.text = content
        }
    }

    /** Only queried while the information page is open; never part of app or book startup. */
    private fun recentExits(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val exits = am?.getHistoricalProcessExitReasons(ctx.packageName, 0, 3).orEmpty()
            exits.joinToString("\n") { exit ->
                val reason = when (exit.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "메모리 부족"
                    ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "강제 종료"
                    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "앱 업데이트"
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> "오류"
                    ApplicationExitInfo.REASON_SIGNALED -> "시스템이 종료"
                    else -> "기타" + exit.description?.let { " · $it" }.orEmpty()
                }
                "${SettingsFormat.dateTime(exit.timestamp)} $reason"
            }.ifEmpty { "기록 없음" }
        }.getOrDefault("기록을 읽지 못했습니다")
    }

    private fun deviceInfo(einkVendor: String?): List<Pair<String, String>> {
        val dm = ctx.resources.displayMetrics
        val vendor = einkVendor ?: "없음 (일반 새로고침 사용)"
        return listOf(
            "기기" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "하드웨어" to "${Build.HARDWARE} · ${Build.BOARD}",
            "화면" to "${dm.widthPixels} × ${dm.heightPixels} px · ${dm.densityDpi} dpi · 배율 ${dm.density}",
            "e-ink 제어" to vendor,
            "파일 접근" to if (StorageAccess.granted(ctx)) "허용됨" else "허용 안 됨",
        )
    }

    override fun onDestroy() {
        runCatching { textDialog?.dismiss() }
        textDialog = null
        stopWatch()
    }

    companion object {
        private const val LICENSE_DIR = "fonts/licenses"
        private const val WATCH_LABEL = "변화 감지 30초"
        private const val WATCH_MS = 30_000L
    }
}
