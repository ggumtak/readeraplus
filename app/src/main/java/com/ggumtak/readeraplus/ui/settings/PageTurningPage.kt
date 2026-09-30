package com.ggumtak.readeraplus.ui.settings

import android.app.AlertDialog
import android.content.DialogInterface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.render.Eink
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.settings.TapZoneMode
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.iconButton
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
 * "페이지 넘김 및 페이지 표시": tap-zone mode with a visual preview (3×3 editor in CUSTOM mode), swipes, volume
 * keys, learned page keys + key test, e-ink page mode and full refresh, auto page turn, and the page status line.
 */
internal class PageTurningPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_PAGE_TURNING, "페이지 넘김 및 페이지 표시") {
    private val modeRows = LinkedHashMap<TapZoneMode, View>()
    private lateinit var preview: TapZoneView
    private lateinit var customTools: LinearLayout
    private lateinit var customNote: TextView
    private lateinit var keysBox: LinearLayout
    private var keyTestRow: View? = null
    private var liveDialog: AlertDialog? = null
    /** The "키 지정" dialog while open (dismissed with the page). */
    private var keyDialog: AlertDialog? = null

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
        body.addView(ctx.toggleRow("메뉴 고정", "읽는 동안 위아래 메뉴를 계속 표시합니다 (터치로 페이지는 넘어감)", app.pinChrome) { v -> editApp { it.copy(pinChrome = v) } })
        updateModeUi()

        // ---- gestures
        body.section("스와이프 · 길게 누르기")
        body.addView(ctx.toggleRow("스와이프로 넘김", "좌우로 밀어서 페이지 넘김 (오른쪽→왼쪽 = 다음)", app.swipeToTurn) { v -> editApp { it.copy(swipeToTurn = v) } })
        body.addView(ctx.toggleRow("세로 스와이프", "위로 밀면 다음 페이지, 아래로 밀면 이전 페이지", app.verticalSwipe) { v -> editApp { it.copy(verticalSwipe = v) } })
        body.addView(ctx.toggleRow("길게 눌러 텍스트 선택", "단어를 길게 누르면 선택 → 복사 · 인용 · 사전 · 검색", app.longPressSelect) { v -> editApp { it.copy(longPressSelect = v) } })

        // ---- keys
        body.section("버튼 · 키")
        body.addView(ctx.toggleRow("볼륨 키로 넘김", "볼륨 아래 = 다음, 볼륨 위 = 이전 (끄면 볼륨 조절)", app.volumeKeysTurn) { v -> editApp { it.copy(volumeKeysTurn = v) } })
        body.addView(ctx.toggleRow("볼륨 키 반대로", "볼륨 위 = 다음, 볼륨 아래 = 이전", app.invertVolumeKeys) { v -> editApp { it.copy(invertVolumeKeys = v) } })
        body.addView(ctx.row("다음 페이지 키 지정", "기기 버튼 · 리모컨 · 키보드의 키를 눌러 다음 페이지로 지정") { learnKey(next = true) })
        body.addView(ctx.row("이전 페이지 키 지정", "누른 키를 이전 페이지로 지정") { learnKey(next = false) })
        keysBox = ctx.vertical().also(body::addView)
        fillKeys()
        keyTestRow = ctx.row("키 테스트", keyTestSummary()) { showKeyTest() }.also(body::addView)
        body.addView(ctx.note("Page Up/Down, 방향키, 스페이스, 미디어 다음/이전 키는 기본으로 페이지를 넘깁니다. 코멧의 사용자 키가 인식되지 않으면 기기 설정(KeyPack)에서 그 키를 '다음 페이지' 또는 볼륨 키로 지정한 뒤 여기서 확인하세요."))

        // ---- e-ink
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
        body.addView(ctx.note("ReadEra처럼 잔상이 적게 하려면 기기 e-ink 설정에서 이 앱을 ReadEra와 같은 모드로 지정하세요."))
        body.addView(ctx.stepperRow("전체 새로고침", app.einkRefreshEvery.toFloat(), 0f, 20f, 1f, { SettingsFormat.refreshEvery(it.toInt()) }) { v ->
            editApp { it.copy(einkRefreshEvery = v.toInt()) }
        })
        body.addView(ctx.toggleRow("챕터 시작 시 새로고침", "새 챕터로 넘어갈 때 잔상을 지웁니다", app.einkRefreshOnChapter) { v -> editApp { it.copy(einkRefreshOnChapter = v) } })
        val refreshRow = ctx.row("지금 새로고침 해보기", "e-ink 제어 확인 중…") {
            runCatching { Eink.fullRefresh(activity.window.decorView) }.onFailure { ctx.toast("새로고침 실패") }
        }
        body.addView(refreshRow)
        activity.scope.launch {
            // Vendor detection uses reflection once; keep it off the main thread.
            val vendor = withContext(Dispatchers.IO) { runCatching { Eink.vendorName() }.getOrNull() }
            val viewMode = withContext(Dispatchers.IO) { runCatching { Eink.supportsViewMode() }.getOrDefault(false) }
            refreshRow.setSummary(if (vendor != null) "e-ink 제어: $vendor" else "기기 전용 제어 없음 — 검은 화면을 잠깐 띄워 잔상을 지웁니다")
            modeNote.text = if (viewMode) {
                "기기의 e-ink 제어(${vendor ?: "xrz"})를 찾았습니다. 고른 모드는 읽기 화면에 바로 적용됩니다 " +
                    "('기기 설정 따름'으로 되돌리면 책을 다시 열 때부터 적용)."
            } else {
                "이 기기는 앱에서 e-ink 모드를 바꿀 수 없음 — 기기의 e-ink 설정에서 앱별 모드/잔상 제거 주기를 조정하세요"
            }
        }

        // ---- auto turn
        body.section("자동 넘김")
        body.addView(ctx.stepperRow("넘김 간격", app.autoTurnSeconds.coerceIn(5, 300).toFloat(), 5f, 300f, 5f, { SettingsFormat.seconds(it.toInt()) }) { v ->
            editApp { it.copy(autoTurnSeconds = v.toInt()) }
        })
        body.addView(ctx.note("읽기 화면의 ⋮ 메뉴 → '자동 넘김'으로 켜고 끕니다. 화면을 터치하면 멈춥니다."))

        // ---- page display (reader settings)
        val r = Settings.reader
        body.section("페이지 표시")
        body.addView(ctx.toggleRow("상단 챕터 제목", "페이지 위에 현재 챕터 제목 표시", r.showHeader) { v -> editReader { it.copy(showHeader = v) } })
        body.addView(ctx.toggleRow("하단 정보", "페이지 아래에 쪽수 · 진행률 · 시계 · 배터리 표시", r.showFooter) { v -> editReader { it.copy(showFooter = v) } })
        body.addView(ctx.toggleRow("쪽수", "12 / 3259", r.footerPage) { v -> editReader { it.copy(footerPage = v) } })
        body.addView(ctx.toggleRow("챕터 남은 쪽", "챕터 끝까지 남은 쪽 수", r.footerChapterLeft) { v -> editReader { it.copy(footerChapterLeft = v) } })
        body.addView(ctx.toggleRow("진행률", "34%", r.footerPercent) { v -> editReader { it.copy(footerPercent = v) } })
        body.addView(ctx.toggleRow("시계", "페이지를 넘길 때만 갱신 (e-ink 절약)", r.footerClock) { v -> editReader { it.copy(footerClock = v) } })
        body.addView(ctx.toggleRow("배터리", "배터리 잔량 %", r.footerBattery) { v -> editReader { it.copy(footerBattery = v) } })
        body.addView(ctx.stepperRow("상태 글자 크기", r.statusFontSizeSp, 8f, 18f, 1f, { SettingsFormat.sp(it) }) { v ->
            editReader { it.copy(statusFontSizeSp = v) }
        })
        body.addView(ctx.toggleRow("흑백 반전", "검은 바탕에 흰 글씨", r.invert) { v -> editReader { it.copy(invert = v) } })
        body.addView(ctx.toggleRow("페이지 여백", "끄면 여백을 최소로 줄입니다", r.pageMargins) { v -> editReader { it.copy(pageMargins = v) } })
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        // Corner switches live on the main page; reflect them when coming back here.
        val app = Settings.app
        preview.bookmarkCorner = app.bookmarkByTouch
        preview.invertCorner = app.invertByTouch
        keyTestRow?.setSummary(keyTestSummary())
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
        ctx.chooser("${TapZoneModel.cellName(cell)} 칸", actions.map { it.label }, actions.indexOf(current)) { i ->
            setCustom(TapZoneModel.withCell(Settings.app.customTapZones, cell, actions[i]))
        }
    }

    // ---------------------------------------------------------------- keys

    private fun fillKeys() {
        keysBox.removeAllViews()
        val list = KeyAssign.list(Settings.app)
        if (list.isEmpty()) {
            keysBox.addView(ctx.note("지정한 키 없음"))
            return
        }
        for ((code, next) in list) {
            val remove = ctx.iconButton(R.drawable.ic_close, "지정 해제") {
                editApp { KeyAssign.remove(it, code) }
                fillKeys()
            }
            keysBox.addView(ctx.row(KeyNames.label(code), if (next) "→ 다음 페이지" else "→ 이전 페이지", remove))
        }
        keysBox.addView(ctx.buttonBar(ctx.textButton("모두 지우기") {
            ctx.confirm("지정한 키 지우기", "지정한 키를 모두 지울까요?", ok = "지우기") {
                editApp { KeyAssign.clearAll(it) }
                fillKeys()
            }
        }))
    }

    /**
     * Dialog that captures the next key press and assigns it to next/previous page. The key is saved on its DOWN
     * and the dialog closes on its UP, so both events stay inside the dialog (a volume key never reaches the
     * system volume panel, and its UP never leaks into the settings window).
     */
    private fun learnKey(next: Boolean) {
        val target = if (next) "다음" else "이전"
        val msg = ctx.label("$target 페이지로 쓸 키를 누르세요.\n\n(뒤로 키: 취소)", 17f).apply {
            setPadding(ctx.dp(24), ctx.dp(16), ctx.dp(24), ctx.dp(8))
            setLineSpacing(0f, 1.15f)
        }
        val capture = KeyCapture()
        val dialog = ctx.alert()
            .setTitle("$target 페이지 키 지정")
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
                        editApp { KeyAssign.assign(it, keyCode, next) }
                        msg.text = "${KeyNames.label(keyCode)} → $target 페이지\n\n키를 떼면 닫힙니다."
                        if (keyCode == KeyNames.UNKNOWN) {
                            msg.append("\n(키 코드 0: 이름 없는 키는 모두 이 동작을 합니다)")
                        }
                    }
                    KeyCapture.Step.CLOSE -> {
                        d.dismiss()
                        ctx.toast("${KeyNames.label(keyCode)} → $target 페이지")
                    }
                    KeyCapture.Step.IGNORE -> Unit
                }
                // Consume DOWN and UP so volume keys never show the system volume panel here.
                return true
            }
        })
        dialog.setOnDismissListener {
            if (keyDialog === dialog) keyDialog = null
            if (capture.captured != -1) fillKeys()
        }
        dialog.window?.setWindowAnimations(0)
        keyDialog = dialog
        dialog.show()
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
                    val effect = KeyNames.readerEffect(keyCode, event.isShiftPressed, Settings.app)
                    out.text = "${KeyNames.name(keyCode)}\n" +
                        "키 코드 $keyCode · 스캔 코드 ${event.scanCode}\n" +
                        "읽기 화면에서: ${effect.label}"
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
        val effect = KeyNames.readerEffect(code, false, Settings.app)
        return "마지막 키: ${KeyNames.label(code)} · 스캔 $scan · ${effect.label}"
    }

    override fun onDestroy() {
        runCatching { liveDialog?.dismiss() }
        liveDialog = null
        runCatching { keyDialog?.dismiss() }
        keyDialog = null
    }
}
