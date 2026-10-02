package com.ggumtak.readeraplus.ui.settings

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.render.DeviceCleanInfo
import com.ggumtak.readeraplus.render.Eink
import com.ggumtak.readeraplus.reader.KeyMap
import com.ggumtak.readeraplus.reader.VolumeMode
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.KeyHold
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.settings.TapZoneMode
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkToggle
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.stepperRow
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "넘김·화면 설정": tap-zone mode with a visual preview (3×3 editor in CUSTOM mode), swipes and the long-press time,
 * keys (key → action bindings with the "이 키로 할 동작" chooser, key hold, key test), the e-ink screen (page mode,
 * refresh cadence by day and night, chapter / picture refreshes, the device's own ghost clearing, and a "고급" group
 * with the refresh method, flash length, the refresh test and diagnostics), auto page turn, the book end, and the
 * page status line. The reading-settings popup's "넘김·화면 설정" button opens this page; rows it shares with the
 * popup use its labels and ranges.
 */
internal class PageTurningPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_PAGE_TURNING, "넘김·화면 설정") {
    private val modeRows = LinkedHashMap<TapZoneMode, View>()
    private lateinit var preview: TapZoneView
    private lateinit var customTools: LinearLayout
    private lateinit var customNote: TextView
    private lateinit var keysBox: LinearLayout
    private var keyTestRow: View? = null
    private var volumeRow: LinearLayout? = null
    private var volumeInvertRow: LinearLayout? = null
    private var liveDialog: AlertDialog? = null
    /** The "키 지정" dialog while open (dismissed with the page). */
    private var keyDialog: AlertDialog? = null
    /** The refresh test while open (dismissed with the page). */
    private var testDialog: Dialog? = null

    private lateinit var cleanText: TextView
    private lateinit var cleanWarning: TextView
    private lateinit var diagText: TextView
    private var methodRow: View? = null
    /** Read on IO when the page is built: the device's ghost clearing, and whether the xrz refresh exists. */
    private var cleanInfo: DeviceCleanInfo? = null
    private var hasXrz: Boolean? = null

    override fun build(): View {
        val app = Settings.app
        val body = ctx.pageBody()

        // ---- tap zones
        body.section("화면 터치", first = true)
        for (m in TapZoneMode.entries) {
            val r = ctx.radioRow(TapZoneModel.modeName(m), TapZoneModel.modeDescription(m), m == app.tapZoneMode) { setMode(m) }
            modeRows[m] = r
            body.addView(r)
        }
        preview = TapZoneView(ctx).apply {
            mode = app.tapZoneMode
            custom = app.customTapZones
            bookmarkCorner = app.bookmarkByTouch
            invertCorner = app.invertByTouch
            inverted = app.invertTaps
            onCellTap = { cell -> pickCellAction(cell) }
            contentDescription = "터치 영역 미리보기"
        }
        val previewBox = FrameLayout(ctx).apply {
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
            addView(preview, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        body.addView(previewBox, lp())
        customTools = ctx.vertical()
        customNote = ctx.note("")
        customTools.addView(ctx.note("칸을 눌러 동작을 고르세요."))
        customTools.addView(ctx.buttonBar(
            ctx.textButton("모두 다음") { setCustom(TapZoneModel.PRESET_ALL_NEXT) },
            ctx.textButton("위는 이전") { setCustom(TapZoneModel.PRESET_TOP_PREV) },
            ctx.textButton("좌우 반전") { setCustom(TapZoneModel.mirrored(Settings.app.customTapZones)) },
        ))
        customTools.addView(ctx.buttonBar(
            ctx.textButton("기본값") { setCustom(AppSettings().customTapZones) },
        ))
        customTools.addView(customNote)
        body.addView(customTools)
        body.addView(ctx.note("모서리 점선 칸: 왼쪽 위 = 흑백 반전, 오른쪽 위 = 북마크 (메인 설정의 '터치로…' 스위치가 켜져 있을 때)."))
        body.addView(ctx.toggleRow("터치 방향 반대로", "터치 영역의 다음/이전을 서로 바꿉니다 (키는 그대로)", app.invertTaps) { v ->
            editApp { it.copy(invertTaps = v) }
            preview.inverted = v
        })
        updateModeUi()

        // ---- gestures
        body.section("스와이프 · 길게 누르기")
        body.addView(ctx.toggleRow("스와이프로 넘김", "좌우로 밀어서 페이지 넘김 (오른쪽→왼쪽 = 다음)", app.swipeToTurn) { v -> editApp { it.copy(swipeToTurn = v) } })
        body.addView(ctx.toggleRow("세로 스와이프", "위로 밀면 다음 페이지, 아래로 밀면 이전 페이지", app.verticalSwipe) { v -> editApp { it.copy(verticalSwipe = v) } })
        body.addView(ctx.toggleRow("길게 눌러 텍스트 선택", "단어를 길게 누르면 선택 → 복사 · 인용 · 사전 · 검색", app.longPressSelect) { v -> editApp { it.copy(longPressSelect = v) } })
        var pressRow: View? = null
        pressRow = ctx.valueRow("길게 누르기 시간", SettingsFormat.longPress(app.longPressMs)) {
            val opts = SettingsFormat.LONG_PRESS_OPTIONS
            ctx.chooser("길게 누르기 시간", opts.map { SettingsFormat.longPressChoice(it) }, opts.indexOf(Settings.app.longPressMs)) { i ->
                editApp { it.copy(longPressMs = opts[i]) }
                pressRow?.setSummary(SettingsFormat.longPress(opts[i]))
            }
        }.also(body::addView)

        // ---- keys
        body.section("버튼 · 키")
        volumeRow = ctx.toggleRow("볼륨 키로 넘김", SettingsFormat.volumeSummary(app), app.volumeKeysTurn) { on ->
            editApp { a -> KeyMap.withVolumeMode(a, if (!on) VolumeMode.OFF else if (a.invertVolumeKeys) VolumeMode.UP_NEXT else VolumeMode.DOWN_NEXT) }
            updateVolumeUi()
        }.also(body::addView)
        volumeInvertRow = ctx.toggleRow("볼륨 키 방향 반전", "볼륨 위 키로 다음 페이지를 넘깁니다", app.invertVolumeKeys) { inverted ->
            editApp { KeyMap.withVolumeMode(it, if (inverted) VolumeMode.UP_NEXT else VolumeMode.DOWN_NEXT) }
            updateVolumeUi()
        }.also(body::addView)
        updateVolumeUi()
        body.addView(ctx.row("키 지정", "기기 버튼 · 리모컨 · 키보드의 키를 누른 뒤 그 키로 할 동작을 고릅니다", ctx.icon(R.drawable.ic_add, 24)) { learnKey() })
        keysBox = ctx.vertical().also(body::addView)
        fillKeys()
        var holdRow: View? = null
        holdRow = ctx.valueRow("키를 길게 누르면", app.keyHold.label) {
            val all = KeyHold.entries
            ctx.chooser("키를 길게 누르면", all.map { it.label }, all.indexOf(Settings.app.keyHold)) { i ->
                editApp { it.copy(keyHold = all[i]) }
                holdRow?.setSummary(all[i].label)
            }
        }.also(body::addView)
        body.addView(ctx.note("다음·이전 페이지 키를 누르고 있을 때입니다. 누르는 순간 한 쪽은 바로 넘어가고, 0.5초쯤 누르고 있으면 고른 동작을 합니다."))
        keyTestRow = ctx.row("키 테스트", keyTestSummary()) { showKeyTest() }.also(body::addView)
        body.addView(ctx.note("Page Up/Down, 방향키, 스페이스, 미디어 다음/이전 키는 기본으로 페이지를 넘깁니다. 코멧의 사용자 키가 인식되지 않으면 기기 설정(KeyPack)에서 그 키를 '다음 페이지' 또는 볼륨 키로 지정한 뒤 여기서 확인하세요."))

        // ---- e-ink
        addEink(body, app)

        // ---- auto turn
        body.section("자동 넘김")
        body.addView(ctx.stepperRow("넘김 간격", app.autoTurnSeconds.coerceIn(5, 300).toFloat(), 5f, 300f, 5f, { SettingsFormat.seconds(it.toInt()) }) { v ->
            editApp { it.copy(autoTurnSeconds = v.toInt()) }
        })
        body.addView(ctx.note("읽기 화면의 ⋮ 메뉴 → '자동 넘김'으로 켜고 끕니다. 화면을 터치하면 멈춥니다."))

        // ---- book end (T1-2)
        body.section("책 끝")
        body.addView(ctx.toggleRow(
            "끝까지 읽으면 완독 처리",
            "마지막 쪽에서 다음으로 넘기면 '${Shelf.HAVE_READ.label}'으로 표시합니다 (끝 화면에서 되돌릴 수 있음)",
            app.autoMarkFinished,
        ) { v -> editApp { it.copy(autoMarkFinished = v) } })

        // ---- page display (reader settings; the popup's labels and ranges)
        val r = Settings.reader
        body.section("페이지 표시")
        body.addView(ctx.stepperRow("상태 표시 글자 크기", r.statusFontSizeSp, 8f, 16f, 0.5f, { SettingsFormat.sp(it) }) { v ->
            editReader { it.copy(statusFontSizeSp = v) }
        })
        body.addView(ctx.toggleRow("흑백 반전", "검은 바탕에 흰 글씨", r.invert) { v -> editReader { it.copy(invert = v) } })
        body.addView(ctx.toggleRow("페이지 여백", "끄면 여백을 최소로 줄입니다", r.pageMargins) { v -> editReader { it.copy(pageMargins = v) } })
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        updateVolumeUi()
        // Corner switches live on the main page; reflect them when coming back here.
        val app = Settings.app
        preview.bookmarkCorner = app.bookmarkByTouch
        preview.invertCorner = app.invertByTouch
        keyTestRow?.setSummary(keyTestSummary())
    }

    // ---------------------------------------------------------------- e-ink (T1-3)

    private fun addEink(body: LinearLayout, app: AppSettings) {
        body.section("e-ink 화면")
        var modeRow: View? = null
        modeRow = ctx.valueRow("e-ink 화면 모드", SettingsFormat.einkMode(app.einkMode)) {
            val opts = SettingsFormat.EINK_MODES
            val sel = opts.indexOfFirst { it.second == Settings.app.einkMode }.coerceAtLeast(0)
            ctx.chooser("e-ink 화면 모드", opts.map { it.first }, sel) { i ->
                editApp { it.copy(einkMode = opts[i].second) }
                modeRow?.setSummary(opts[i].first)
            }
        }.also(body::addView)
        val modeNote = ctx.note("읽기 화면의 e-ink 갱신 방식입니다. 기기의 e-ink 제어를 찾은 경우에만 적용됩니다 (확인 중…).")
        body.addView(modeNote)
        body.addView(ctx.note("잔상이 거슬리면 기기의 e-ink 설정(앱별 최적화)에서 이 앱의 새로고침 모드를 바꿔 보세요. 위의 'e-ink 화면 모드'에서 '기기 설정 따름' 외의 모드를 고르면 그 값이 우선합니다."))
        body.addView(ctx.stepperRow("전체 새로고침", app.einkRefreshEvery.toFloat(), 0f, 20f, 1f, { SettingsFormat.refreshEvery(it.toInt()) }) { v ->
            editApp { it.copy(einkRefreshEvery = v.toInt()) }
            updateCleanWarning()
        })
        var nightRow: View? = null
        nightRow = ctx.valueRow("밤 모드(반전)에서", EinkChoices.night(app.einkRefreshEveryNight)) {
            val opts = EinkChoices.NIGHT
            ctx.chooser("밤 모드(반전)에서", opts.map { EinkChoices.night(it) }, opts.indexOf(Settings.app.einkRefreshEveryNight)) { i ->
                editApp { it.copy(einkRefreshEveryNight = opts[i]) }
                nightRow?.setSummary(EinkChoices.night(opts[i]))
                updateCleanWarning()
            }
        }.also(body::addView)
        body.addView(ctx.toggleRow("챕터 시작 시 새로고침", "새 챕터로 넘어갈 때 잔상을 지웁니다", app.einkRefreshOnChapter) { v -> editApp { it.copy(einkRefreshOnChapter = v) } })
        body.addView(ctx.toggleRow("그림 있는 쪽에서 새로고침", "그림이 있는 쪽으로 넘어가거나 벗어날 때 잔상을 지웁니다 (한 번 깜빡임)", app.einkFlashImages) { v ->
            editApp { it.copy(einkFlashImages = v) }
        })
        cleanText = ctx.note("").apply { visibility = View.GONE }.also(body::addView)
        cleanWarning = ctx.note(EinkChoices.DOUBLE_FLASH).apply {
            setTextColor(Ink.BLACK)
            visibility = View.GONE
        }.also(body::addView)

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
        flashRow = ctx.valueRow("깜빡임 길이", EinkChoices.flash(app.einkFlashMs)) {
            val opts = EinkChoices.FLASH_MS
            ctx.chooser("깜빡임 길이", opts.map { EinkChoices.flash(it) }, opts.indexOf(Settings.app.einkFlashMs)) { i ->
                editApp { it.copy(einkFlashMs = opts[i]) }
                flashRow?.setSummary(EinkChoices.flash(opts[i]))
            }
        }.also(advanced::addView)
        advanced.addView(ctx.note("'자동'은 기기의 새로고침을 먼저 쓰고, 없거나 실패하면 검은 화면을 잠깐 띄웁니다. 깜빡임 길이는 그 검은 화면이 떠 있는 시간입니다."))
        advanced.addView(ctx.row("새로고침 시험", "줄무늬 뒤에 글자를 띄우고 고른 방식으로 새로고침해 잔상이 지워지는지 봅니다") { startTest() })
        advanced.addView(ctx.label("진단", 15f, bold = true).apply { setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), 0) })
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
            modeNote.text = if (d.viewMode) {
                "기기의 e-ink 제어(${d.vendor ?: "xrz"})를 찾았습니다. 고른 모드는 읽기 화면에 바로 적용됩니다 " +
                    "('기기 설정 따름'으로 되돌리면 책을 다시 열 때부터 적용)."
            } else {
                "이 기기는 앱에서 e-ink 모드를 바꿀 수 없음 — 기기의 e-ink 설정에서 앱별 모드/잔상 제거 주기를 조정하세요"
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

    /** The double-flash warning shows while the device clears ghosts itself and an app cadence is on too. */
    private fun updateCleanWarning() {
        if (!::cleanWarning.isInitialized) return
        val show = EinkChoices.doubleFlash(cleanInfo, Settings.app)
        val v = if (show) View.VISIBLE else View.GONE
        if (cleanWarning.visibility != v) cleanWarning.visibility = v
    }

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

    // ---------------------------------------------------------------- tap zones

    private fun setMode(m: TapZoneMode) {
        editApp { it.copy(tapZoneMode = m) }
        updateModeUi()
    }

    private fun setCustom(list: List<TapAction>) {
        editApp { it.copy(customTapZones = list) }
        updateModeUi()
    }

    private fun updateModeUi() {
        val app = Settings.app
        for ((m, r) in modeRows) r.setRadioChecked(m == app.tapZoneMode)
        preview.mode = app.tapZoneMode
        preview.custom = app.customTapZones
        val custom = app.tapZoneMode == TapZoneMode.CUSTOM
        customTools.visibility = if (custom) View.VISIBLE else View.GONE
        customNote.text = if (TapZoneModel.centreForcedToMenu(app.customTapZones)) {
            "메뉴 칸이 없어서 가운데 칸이 메뉴로 동작합니다."
        } else {
            ""
        }
        customNote.visibility = if (customNote.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun pickCellAction(cell: Int) {
        val actions = TapAction.entries
        val current = Settings.app.customTapZones.getOrNull(cell)
        ctx.chooser("${TapZoneModel.cellName(cell)} 칸", actions.map {
            if (it == TapAction.NEXT_CHAPTER || it == TapAction.PREV_CHAPTER || it == TapAction.NONE) KeyActions.label(it) else it.label
        }, actions.indexOf(current)) { i ->
            setCustom(TapZoneModel.withCell(Settings.app.customTapZones, cell, actions[i]))
        }
    }

    // ---------------------------------------------------------------- keys (T1-4)

    /** The assigned keys, each with its action and [삭제]. */
    private fun fillKeys() {
        updateVolumeUi()
        keysBox.removeAllViews()
        val list = KeyAssign.entries(Settings.app)
        if (list.isEmpty()) {
            keysBox.addView(ctx.note("지정한 키 없음"))
            return
        }
        for ((code, action) in list) {
            val remove = ctx.textButton("삭제") {
                editApp { KeyAssign.remove(it, code) }
                fillKeys()
            }
            keysBox.addView(ctx.row(KeyNames.label(code), "→ ${KeyActions.label(action)}", remove) { chooseKeyAction(code) })
        }
        if (list.size > 1) {
            keysBox.addView(ctx.buttonBar(ctx.textButton("모두 지우기") {
                ctx.confirm("지정한 키 지우기", "지정한 키를 모두 지울까요?", ok = "지우기") {
                    editApp { KeyAssign.clearAll(it) }
                    fillKeys()
                }
            }))
        }
    }

    /**
     * Dialog that captures the next key press, then asks what it should do ([chooseKeyAction]). The key is taken on
     * its DOWN and the chooser opens on its UP, so both events stay inside the dialog (a volume key never reaches the
     * system volume panel, and its UP never leaks into the settings window).
     */
    private fun learnKey() {
        val msg = ctx.label("지정할 키를 누르세요.\n\n(뒤로 키: 취소)", 17f).apply {
            setPadding(ctx.dp(24), ctx.dp(16), ctx.dp(24), ctx.dp(8))
            setLineSpacing(0f, 1.15f)
        }
        val capture = KeyCapture()
        val dialog = ctx.alert()
            .setTitle("키 지정")
            .setView(msg)
            .setNegativeButton("닫기", null)
            .create()
        dialog.setOnKeyListener(object : DialogInterface.OnKeyListener {
            override fun onKey(d: DialogInterface, keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK) return false
                val down = event.action == KeyEvent.ACTION_DOWN
                if (!down && event.action != KeyEvent.ACTION_UP) return true
                when (capture.onKey(down, keyCode, event.repeatCount)) {
                    KeyCapture.Step.REJECT -> msg.text = "${KeyNames.unassignableReason(keyCode)}\n\n다른 키를 누르세요."
                    KeyCapture.Step.ASSIGN -> {
                        msg.text = "${KeyNames.label(keyCode)}\n\n키를 떼면 할 동작을 고릅니다."
                        if (keyCode == KeyNames.UNKNOWN) {
                            msg.append("\n(키 코드 0: 이름 없는 키는 모두 이 동작을 합니다)")
                        }
                    }
                    KeyCapture.Step.CLOSE -> {
                        d.dismiss()
                        chooseKeyAction(keyCode)
                    }
                    KeyCapture.Step.IGNORE -> Unit
                }
                // Consume DOWN and UP so volume keys never show the system volume panel here.
                return true
            }
        })
        dialog.setOnDismissListener {
            if (keyDialog === dialog) keyDialog = null
        }
        dialog.window?.setWindowAnimations(0)
        keyDialog = dialog
        dialog.show()
    }

    /** "이 키로 할 동작": stores the binding (a learned page-key entry of the same key goes). */
    private fun chooseKeyAction(code: Int) {
        val actions = KeyActions.CHOICES
        val current = KeyAssign.actionOf(Settings.app, code)
        ctx.chooser("이 키로 할 동작 · ${KeyNames.name(code)}", actions.map { KeyActions.labelFor(code, it) }, actions.indexOf(current)) { i ->
            editApp { KeyAssign.bind(it, code, actions[i]) }
            fillKeys()
            keyTestRow?.setSummary(keyTestSummary())
            ctx.toast("${KeyNames.label(code)} → ${KeyActions.label(actions[i])}")
        }
    }

    /** Both rows reflect the latest settings, also after editing or removing a custom key action. */
    private fun updateVolumeUi() {
        val app = Settings.app
        val bound = KeyMap.volumeBound(app)
        volumeRow?.let { row ->
            row.setRowEnabled(!bound)
            row.setSummary(SettingsFormat.volumeSummary(app))
            setVolumeChecked(row, app.volumeKeysTurn)
        }
        volumeInvertRow?.let { row ->
            row.setRowEnabled(!bound && app.volumeKeysTurn)
            row.setSummary(when {
                bound -> "키 지정에서 볼륨 키 동작을 정했습니다"
                !app.volumeKeysTurn -> "‘볼륨 키로 넘김’을 켜면 쓸 수 있습니다"
                else -> SettingsFormat.volumeSummary(app)
            })
            setVolumeChecked(row, app.invertVolumeKeys)
        }
    }

    private fun setVolumeChecked(row: LinearLayout, checked: Boolean) {
        val toggle = row.getChildAt(1) as? InkToggle ?: return
        if (toggle.isChecked == checked) return
        val change = toggle.onChange
        toggle.onChange = null
        toggle.isChecked = checked
        toggle.onChange = change
    }

    /** Live key tester: shows each key's code and what the reader does with it (consumes all keys but Back). */
    private fun showKeyTest() {
        val out = ctx.label("아무 키나 누르세요.\n(뒤로 키: 닫기)", 17f).apply {
            setPadding(ctx.dp(24), ctx.dp(16), ctx.dp(24), ctx.dp(8))
            setLineSpacing(0f, 1.2f)
            minLines = 5
        }
        val dialog = ctx.alert()
            .setTitle("키 테스트")
            .setView(out)
            .setPositiveButton("닫기", null)
            .create()
        dialog.setOnKeyListener(object : DialogInterface.OnKeyListener {
            override fun onKey(d: DialogInterface, keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK) return false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val effect = KeyNames.readerEffectLabel(keyCode, event.isShiftPressed, Settings.app)
                    out.text = "${KeyNames.name(keyCode)}\n" +
                        "키 코드 $keyCode · 스캔 코드 ${event.scanCode}\n" +
                        "읽기 화면에서: $effect"
                    lastTested = keyCode to event.scanCode
                }
                return true
            }
        })
        dialog.setOnDismissListener {
            liveDialog = null
            keyTestRow?.setSummary(keyTestSummary())
        }
        dialog.window?.setWindowAnimations(0)
        liveDialog = dialog
        dialog.show()
    }

    /** Last key seen (code to scan code); starts with the last key pressed anywhere in settings. */
    private var lastTested: Pair<Int, Int>? = activity.lastKeyCode.takeIf { it >= 0 }?.let { it to activity.lastScanCode }

    override fun onKeyPressed(keyCode: Int, scanCode: Int) {
        lastTested = keyCode to scanCode
        keyTestRow?.setSummary(keyTestSummary())
    }

    private fun keyTestSummary(): String {
        val (code, scan) = lastTested ?: return "눌러서 키 코드를 확인합니다"
        return "마지막 키: ${KeyNames.label(code)} · 스캔 $scan · ${KeyNames.readerEffectLabel(code, false, Settings.app)}"
    }

    override fun onDestroy() {
        runCatching { liveDialog?.dismiss() }
        liveDialog = null
        runCatching { keyDialog?.dismiss() }
        keyDialog = null
        runCatching { testDialog?.dismiss() }
        testDialog = null
    }

    private companion object {
        /** The "남은 시간" chooser's examples (the footer's wording). */
    }
}
