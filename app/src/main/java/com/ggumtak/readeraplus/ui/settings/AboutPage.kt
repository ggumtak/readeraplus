package com.ggumtak.readeraplus.ui.settings

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import com.ggumtak.readeraplus.BuildConfig
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.render.Eink
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.fullScreenDialog
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.toolbar
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "정보": version, font / icon licenses (assets/fonts/licenses/…), device info for troubleshooting. */
internal class AboutPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_ABOUT, "정보") {
    private lateinit var licensesBox: LinearLayout
    /** License viewer while open (dismissed with the page so its window never leaks). */
    private var textDialog: Dialog? = null

    override fun build(): View {
        val body = ctx.pageBody()
        val head = ctx.vertical { setPadding(ctx.dp(16), ctx.dp(20), ctx.dp(16), ctx.dp(8)) }
        head.addView(ctx.label(ctx.getString(R.string.app_name), 24f, bold = true))
        head.addView(ctx.label("버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", 15f, color = Ink.GRAY).apply {
            setPadding(0, ctx.dp(6), 0, 0)
        })
        head.addView(ctx.label(
            "e-ink 전자책 리더기를 위해 만든 가볍고 빠른 TXT·EPUB 리더입니다. 글꼴 · 줄 간격 · 문단 간격 · 여백을 자유롭게 바꿀 수 있습니다.",
            15f,
        ).apply { setPadding(0, ctx.dp(10), 0, 0); setLineSpacing(0f, 1.15f) })
        body.addView(head)

        body.section("라이선스")
        licensesBox = ctx.vertical().also(body::addView)
        body.addView(ctx.note("아이콘: Material Symbols (Google, Apache License 2.0)\n글꼴: 모두 SIL Open Font License 1.1 (앱 안에서만 사용, 수정 없음)"))
        loadLicenses()

        body.section("기기 정보")
        val infoBox = ctx.vertical().also(body::addView)
        infoBox.addView(ctx.note("불러오는 중…"))
        var info: List<Pair<String, String>> = emptyList()
        body.addView(ctx.buttonBar(ctx.textButton("기기 정보 복사") {
            val text = info.joinToString("\n") { "${it.first}: ${it.second}" }
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("기기 정보", text))
            }
            ctx.toast("복사했습니다")
        }))
        activity.scope.launch {
            val vendor = withContext(Dispatchers.IO) { runCatching { Eink.vendorName() }.getOrNull() }
            info = deviceInfo(vendor)
            infoBox.removeAllViews()
            for ((k, v) in info) infoBox.addView(ctx.infoRow(k, v))
        }
        return ctx.pageScroll(body)
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
                val title = when (f) {
                    "FONTS.txt" -> "글꼴 목록과 저작권"
                    else -> f.removeSuffix(".txt")
                }
                licensesBox.addView(ctx.navRow(title, f) { showText(title, "$LICENSE_DIR/$f") })
            }
        }
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

    private fun deviceInfo(einkVendor: String?): List<Pair<String, String>> {
        val dm = ctx.resources.displayMetrics
        val vendor = einkVendor ?: "없음 (일반 새로고침 사용)"
        return listOf(
            "기기" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "하드웨어" to "${Build.HARDWARE} · ${Build.BOARD}",
            "화면" to "${dm.widthPixels} × ${dm.heightPixels} px · ${dm.densityDpi} dpi · 배율 ${dm.density}",
            "e-ink 제어" to vendor,
            "파일 접근" to if (StorageAccess.granted(ctx)) "모든 파일 접근 허용됨" else "허용 안 됨",
        )
    }

    override fun onDestroy() {
        runCatching { textDialog?.dismiss() }
        textDialog = null
    }

    companion object {
        private const val LICENSE_DIR = "fonts/licenses"
    }
}
