package com.ggumtak.readeraplus.ui.settings

import android.app.Dialog
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.render.DeviceCleanInfo
import com.ggumtak.readeraplus.render.Eink
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.sectionHeader
import com.ggumtak.readeraplus.ui.kit.stepperRow
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "e-ink 화면" (T1-3): 새로고침 (the full refresh cadence by day and on dark pages, chapter and picture refreshes, the
 * device's own ghost clearing and the double-flash warning) and 화면 모드 (the reader page's e-ink mode, and a folded
 * "고급" group with the refresh method, the flash length, the refresh test and the diagnostics). The device probe runs
 * once on IO when the page is built; its result fills the mode row, its note and the device line in one pass.
 */
internal class EinkPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_EINK, "e-ink 화면") {
    /** The refresh test while open (dismissed with the page). */
    private var testDialog: Dialog? = null
    private lateinit var cleanText: TextView
    private lateinit var cleanWarning: TextView
    private lateinit var diagText: TextView
    private var modeRow: View? = null
    private var methodRow: View? = null
    /** Read on IO when the page is built: the device's ghost clearing, and whether the xrz refresh exists. */
    private var cleanInfo: DeviceCleanInfo? = null
    private var hasXrz: Boolean? = null

    override fun build(): View {
        val app = Settings.app
        val body = ctx.pageBody()
        addRefresh(body, app)
        addMode(body, app)
        return ctx.pageScroll(body)
    }

    override fun onDestroy() {
        runCatching { testDialog?.dismiss() }
        testDialog = null
    }

    // ---------------------------------------------------------------- 새로고침

    private fun addRefresh(body: LinearLayout, app: AppSettings) {
        body.section("새로고침")
        body.addView(ctx.stepperRow("전체 새로고침", app.einkRefreshEvery.toFloat(), 0f, 20f, 1f, { SettingsFormat.refreshEvery(it.toInt()) }) { v ->
            editApp { it.copy(einkRefreshEvery = v.toInt()) }
            updateCleanWarning()
        })
        var nightRow: View? = null
        nightRow = ctx.valueRow(EinkChoices.NIGHT_TITLE, EinkChoices.night(app.einkRefreshEveryNight)) {
            val opts = EinkChoices.NIGHT
            ctx.chooser(EinkChoices.NIGHT_CHOOSER_TITLE, opts.map { EinkChoices.night(it) }, opts.indexOf(Settings.app.einkRefreshEveryNight)) { i ->
                editApp { it.copy(einkRefreshEveryNight = opts[i]) }
                nightRow?.setSummary(EinkChoices.night(opts[i]))
                updateCleanWarning()
            }
        }.also(body::addView)
        body.addView(ctx.toggleRow("새 챕터에서 새로고침", null, app.einkRefreshOnChapter) { v -> editApp { it.copy(einkRefreshOnChapter = v) } })
        body.addView(ctx.toggleRow("그림 페이지에서 새로고침", "들어갈 때 · 나올 때 한 번씩", app.einkFlashImages) { v ->
            editApp { it.copy(einkFlashImages = v) }
        })
        cleanText = ctx.note("").apply { visibility = View.GONE }.also(body::addView)
        cleanWarning = ctx.warning(EinkChoices.DOUBLE_FLASH).apply { visibility = View.GONE }.also(body::addView)
    }

    /** The double-flash warning shows while the device clears ghosts itself and an app cadence is on too. */
    private fun updateCleanWarning() {
        if (!::cleanWarning.isInitialized) return
        cleanWarning.setShown(EinkChoices.doubleFlash(cleanInfo, Settings.app))
    }

    // ---------------------------------------------------------------- 화면 모드 · 고급

    private fun addMode(body: LinearLayout, app: AppSettings) {
        body.section("화면 모드")
        modeRow = ctx.valueRow("e-ink 화면 모드", SettingsFormat.einkMode(app.einkMode)) {
            val opts = SettingsFormat.EINK_MODES
            val sel = opts.indexOfFirst { it.second == Settings.app.einkMode }.coerceAtLeast(0)
            ctx.chooser("e-ink 화면 모드", opts.map { it.first }, sel) { i ->
                editApp { it.copy(einkMode = opts[i].second) }
                modeRow?.setSummary(SettingsFormat.einkMode(opts[i].second))
            }
        }.also(body::addView)
        // Shown once the probe found the device's e-ink control (no "확인 중…" line before it).
        val modeNote = ctx.note(MODE_NOTE).apply { visibility = View.GONE }.also(body::addView)

        // "고급": folded until opened (the method, the flash, the test and the readout are rarely touched).
        val advanced = ctx.vertical { visibility = View.GONE }
        val chevron: ImageView = ctx.icon(R.drawable.ic_expand_more, 24)
        body.addView(ctx.row("고급", "새로고침 방식 · 깜빡임 길이 · 새로고침 시험 · 진단", chevron) {
            val open = advanced.visibility != View.VISIBLE
            advanced.visibility = if (open) View.VISIBLE else View.GONE
            chevron.setImageResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        })
        body.addView(advanced, lp())
        methodRow = ctx.valueRow("새로고침 방식", EinkChoices.method(app.einkRefreshMethod)) { chooseMethod() }.also(advanced::addView)
        var flashRow: View? = null
        flashRow = ctx.valueRow("깜빡임 길이", EinkChoices.flash(app.einkFlashMs).removeSuffix(SettingsFormat.DEFAULT_MARK)) {
            val opts = EinkChoices.FLASH_MS
            ctx.chooser("깜빡임 길이", opts.map { EinkChoices.flash(it) }, opts.indexOf(Settings.app.einkFlashMs)) { i ->
                editApp { it.copy(einkFlashMs = opts[i]) }
                flashRow?.setSummary(EinkChoices.flash(opts[i]).removeSuffix(SettingsFormat.DEFAULT_MARK))
            }
        }.also(advanced::addView)
        advanced.addView(ctx.note(EinkChoices.METHOD_NOTE))
        advanced.addView(ctx.row("새로고침 시험", "잔상이 지워지는지 확인") { startTest() })
        // A heading inside the folded group: no line above it (it is not a section of the page).
        advanced.addView(ctx.sectionHeader("진단"))
        diagText = ctx.note("확인 중…").also(advanced::addView)

        activity.scope.launch {
            // Vendor detection and the device readout use reflection once; keep them off the main thread.
            val d = withContext(Dispatchers.IO) {
                runCatching {
                    Diag(Eink.vendorName(), Eink.supportsViewMode(), Eink.hasXrzRefresh(), Eink.deviceCleanInfo())
                }.getOrDefault(Diag(null, false, false, null))
            }
            hasXrz = d.hasXrz
            cleanInfo = d.clean
            // The mode works only through the device's e-ink control: without it the row says why and is disabled.
            modeNote.setShown(d.viewMode)
            if (!d.viewMode) modeRow?.let { row ->
                row.setSummary(MODE_UNAVAILABLE)
                row.setRowEnabled(false)
            }
            EinkChoices.deviceClean(d.clean)?.let {
                cleanText.text = it
                cleanText.visibility = View.VISIBLE
            }
            updateCleanWarning()
            val dm = ctx.resources.displayMetrics
            diagText.text = EinkChoices.diagnostics(d.vendor, d.hasXrz, d.viewMode, d.clean, dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private class Diag(val vendor: String?, val viewMode: Boolean, val hasXrz: Boolean, val clean: DeviceCleanInfo?)

    /** "새로고침 방식": the device methods are offered only where the xrz refresh exists (probed once, on IO). */
    private fun chooseMethod() {
        activity.scope.launch {
            val xrz = hasXrz ?: withContext(Dispatchers.IO) { runCatching { Eink.hasXrzRefresh() }.getOrDefault(false) }.also { hasXrz = it }
            val opts = EinkChoices.methods(xrz)
            val sel = opts.indexOfFirst { it.second == Settings.app.einkRefreshMethod }
            ctx.chooser("새로고침 방식", opts.map { it.first }, sel) { i -> setMethod(opts[i].second) }
        }
    }

    private fun setMethod(m: Int) {
        if (Settings.app.einkRefreshMethod != m) editApp { it.copy(einkRefreshMethod = m) }
        methodRow?.setSummary(EinkChoices.method(m))
    }

    private fun startTest() {
        if (testDialog?.isShowing == true) return
        activity.scope.launch {
            val xrz = hasXrz ?: withContext(Dispatchers.IO) { runCatching { Eink.hasXrzRefresh() }.getOrDefault(false) }.also { hasXrz = it }
            val app = Settings.app
            // A stored device method this firmware lacks would only test the flash: start from 자동 instead.
            val start = if (!xrz && EinkChoices.isDeviceMethod(app.einkRefreshMethod)) EinkChoices.METHODS[0].second else app.einkRefreshMethod
            val test = EinkTest(ctx, start, app.einkFlashMs, xrz) { m ->
                setMethod(m)
                ctx.toast("새로고침 방식: ${EinkChoices.method(m)}")
            }
            testDialog = test.show()
        }
    }

    private companion object {
        const val MODE_NOTE = "‘기기 설정 따름’은 책을 다시 열 때 적용됩니다."
        const val MODE_UNAVAILABLE = "이 기기에서는 바꿀 수 없습니다 · 기기 e-ink 설정을 쓰세요"
    }
}
