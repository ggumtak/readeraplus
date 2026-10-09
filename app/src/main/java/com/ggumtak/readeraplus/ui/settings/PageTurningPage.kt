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
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.reader.KeyMap
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.KeyHold
import com.ggumtak.readeraplus.settings.ReadMode
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.settings.TapZoneMode
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.stepperRow
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * "넘기기·터치·키" (PLAN §1.6.3): 넘기기 (the read mode, page turning or scroll, and the scroll motion; the book end;
 * the auto-turn interval), 화면 터치 (the tap-zone mode with a visual preview, a 3×3 editor in 직접 지정, and the two
 * corner switches), 스와이프·길게 누르기 (swipes and the long-press time) and 버튼·키 (the volume keys, key hold, key →
 * action bindings with the "이 키로 할 동작" chooser, the key test). Every row is an app setting; the reading settings
 * live on 읽기 설정, the status bar and brightness on 화면·밝기, the refreshes on e-ink 새로고침.
 */
internal class PageTurningPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_PAGE_TURNING, "넘기기·터치·키") {
    private val modeRows = LinkedHashMap<TapZoneMode, View>()
    private lateinit var preview: TapZoneView
    private lateinit var customTools: LinearLayout
    private lateinit var customNote: TextView
    private lateinit var keysBox: LinearLayout
    private var keyTestRow: View? = null
    private var volumeRow: View? = null
    private var readModeRow: View? = null
    private var scrollStyleRow: View? = null
    private var scrollNote: View? = null
    private var swipeRow: View? = null
    private var verticalSwipeRow: View? = null
    private var pressRow: View? = null
    private var liveDialog: AlertDialog? = null
    /** The "키 지정" dialog while open (dismissed with the page). */
    private var keyDialog: AlertDialog? = null

    override fun build(): View {
        val app = Settings.app
        val body = ctx.pageBody()
        addTurning(body, app)
        addTaps(body, app)
        addGestures(body, app)
        addKeys(body, app)
        updateReadModeUi()
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        updateVolumeUi()
        updateReadModeUi()
        keyTestRow?.setSummary(keyTestSummary())
    }

    // ---------------------------------------------------------------- 넘기기 (scroll SPEC §1.2, T1-2)

    private fun addTurning(body: LinearLayout, app: AppSettings) {
        body.section("넘기기")
        readModeRow = ctx.valueRow("넘기는 방식", R3Rows.readMode(app.readMode)) {
            val opts = R3Rows.READ_MODES
            ctx.chooser("넘기는 방식", opts.map { R3Rows.readModeChoice(it) }, opts.indexOf(Settings.app.readMode)) { i -> setReadMode(opts[i]) }
        }.also(body::addView)
        scrollStyleRow = ctx.valueRow("스크롤 움직임", R3Rows.scrollStyle(app.scrollStyle)) {
            val opts = R3Rows.SCROLL_STYLES
            ctx.chooser("스크롤 움직임", opts.map { R3Rows.scrollStyleChoice(it) }, R3Rows.scrollStyleIndex(Settings.app.scrollStyle)) { i ->
                // A stored SMOOTH already reads as the first entry: picking it again changes nothing.
                if (R3Rows.scrollStyleIndex(Settings.app.scrollStyle) != i) editApp { it.copy(scrollStyle = opts[i]) }
                updateReadModeUi()
            }
        }.also(body::addView)
        scrollNote = ctx.note(R3Rows.READ_MODE_NOTE).also(body::addView)
        body.addView(ctx.toggleRow("끝까지 읽으면 ‘다 읽은 책’으로", "끝 화면에서 되돌릴 수 있음", app.autoMarkFinished) { v ->
            editApp { it.copy(autoMarkFinished = v) }
        })
        body.addView(ctx.stepperRow("자동 넘김 간격", app.autoTurnSeconds.coerceIn(5, 300).toFloat(), 5f, 300f, 5f, { SettingsFormat.seconds(it.toInt()) }) { v ->
            editApp { it.copy(autoTurnSeconds = v.toInt()) }
        }.liveStepperValue())
        body.addView(ctx.note("자동 넘김은 읽는 화면의 ⋮ 메뉴에서 시작하고, 화면을 누르면 멈춥니다."))
    }

    private fun setReadMode(m: ReadMode) {
        if (Settings.app.readMode != m) editApp { it.copy(readMode = m) }
        // AUTO resolves by the device class; find it now (IO, once) so the first scroll gesture already knows it.
        if (m == ReadMode.SCROLL && DeviceClass.cached(ctx) == null) {
            DeviceClass.probeAsync(ctx) { if (view != null) updateReadModeUi() }
        }
        updateReadModeUi()
    }

    /**
     * Rows that depend on the read mode: 스크롤 움직임 and its note (SCROLL only), the swipe summaries, 위아래 스와이프로
     * 넘김 (disabled in SCROLL, with the reason).
     */
    private fun updateReadModeUi() {
        val app = Settings.app
        val scroll = app.readMode == ReadMode.SCROLL
        readModeRow?.setSummary(R3Rows.readMode(app.readMode))
        scrollStyleRow?.let { row ->
            row.setShown(scroll)
            if (scroll) row.setSummary(R3Rows.scrollStyle(app.scrollStyle))
        }
        scrollNote?.setShown(scroll)
        swipeRow?.setSummary(if (scroll) R3Rows.SWIPE_TURN_SCROLL else R3Rows.SWIPE_TURN)
        verticalSwipeRow?.let { row ->
            row.setRowEnabled(!scroll)
            row.setSummary(if (scroll) R3Rows.VERTICAL_SWIPE_SCROLL else R3Rows.VERTICAL_SWIPE)
        }
    }

    // ---------------------------------------------------------------- 화면 터치

    private fun addTaps(body: LinearLayout, app: AppSettings) {
        body.section("화면 터치")
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
            ctx.textButton("좌우 바꾸기") { setCustom(TapZoneModel.mirrored(Settings.app.customTapZones)) },
        ))
        customTools.addView(ctx.buttonBar(
            ctx.textButton("기본값") { setCustom(AppSettings().customTapZones) },
        ))
        customTools.addView(customNote)
        body.addView(customTools)
        // The corners are drawn dotted in the preview while their switch is on.
        body.addView(ctx.toggleRow("왼쪽 위 터치로 흑백 반전", null, app.invertByTouch) { v ->
            editApp { it.copy(invertByTouch = v) }
            preview.invertCorner = v
        })
        body.addView(ctx.toggleRow("오른쪽 위 터치로 북마크", null, app.bookmarkByTouch) { v ->
            editApp { it.copy(bookmarkByTouch = v) }
            preview.bookmarkCorner = v
        })
        body.addView(ctx.toggleRow("다음·이전 바꾸기", "터치만 (키는 그대로)", app.invertTaps) { v ->
            editApp { it.copy(invertTaps = v) }
            preview.inverted = v
        })
        updateModeUi()
    }

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
        customTools.setShown(app.tapZoneMode == TapZoneMode.CUSTOM)
        customNote.text = if (TapZoneModel.centreForcedToMenu(app.customTapZones)) "메뉴 칸이 없어서 가운데 칸이 메뉴로 동작합니다." else ""
        customNote.setShown(customNote.text.isNotEmpty())
    }

    /** A cell of the 직접 지정 grid: the tap zones' own names (the preview says the same). */
    private fun pickCellAction(cell: Int) {
        val actions = TapAction.entries
        val current = Settings.app.customTapZones.getOrNull(cell)
        ctx.chooser("${TapZoneModel.cellName(cell)} 칸", actions.map { it.label }, actions.indexOf(current)) { i ->
            setCustom(TapZoneModel.withCell(Settings.app.customTapZones, cell, actions[i]))
        }
    }

    // ---------------------------------------------------------------- 스와이프·길게 누르기

    private fun addGestures(body: LinearLayout, app: AppSettings) {
        body.section("스와이프·길게 누르기")
        swipeRow = ctx.toggleRow("좌우 스와이프로 넘김", R3Rows.SWIPE_TURN, app.swipeToTurn) { v -> editApp { it.copy(swipeToTurn = v) } }
            .also(body::addView)
        verticalSwipeRow = ctx.toggleRow("위아래 스와이프로 넘김", R3Rows.VERTICAL_SWIPE, app.verticalSwipe) { v -> editApp { it.copy(verticalSwipe = v) } }
            .also(body::addView)
        body.addView(ctx.toggleRow("길게 눌러 선택", "복사 · 형광펜 · 사전 · 검색", app.longPressSelect) { v ->
            editApp { it.copy(longPressSelect = v) }
            pressRow?.setShown(v)
        })
        // Only matters while long-press selection is on: hidden otherwise, in the same pass.
        pressRow = ctx.valueRow("길게 누르기 시간", SettingsFormat.longPress(app.longPressMs)) {
            val opts = SettingsFormat.LONG_PRESS_OPTIONS
            ctx.chooser("길게 누르기 시간", opts.map { SettingsFormat.longPressChoice(it) }, opts.indexOf(Settings.app.longPressMs)) { i ->
                editApp { it.copy(longPressMs = opts[i]) }
                pressRow?.setSummary(SettingsFormat.longPress(opts[i]))
            }
        }.also(body::addView)
        pressRow?.setShown(app.longPressSelect)
    }

    // ---------------------------------------------------------------- 버튼·키 (T1-4, P8)

    private fun addKeys(body: LinearLayout, app: AppSettings) {
        body.section("버튼·키")
        // One row for both volume keys (the direction or off); a volume key given an action in 키 지정 wins.
        volumeRow = ctx.valueRow("볼륨 키", SettingsFormat.volumeValue(app)) {
            val opts = SettingsFormat.VOLUME_CHOICES
            val sel = opts.indexOfFirst { it.second == KeyMap.volumeMode(Settings.app) }
            ctx.chooser("볼륨 키", opts.map { it.first }, sel) { i ->
                editApp { KeyMap.withVolumeMode(it, opts[i].second) }
                updateVolumeUi()
            }
        }.also(body::addView)
        var holdRow: View? = null
        holdRow = ctx.valueRow("페이지 키를 길게 누르면", app.keyHold.label) {
            val all = KeyHold.entries
            ctx.chooser("페이지 키를 길게 누르면", all.map { it.label }, all.indexOf(Settings.app.keyHold)) { i ->
                editApp { it.copy(keyHold = all[i]) }
                holdRow?.setSummary(all[i].label)
            }
        }.also(body::addView)
        body.addView(ctx.row("키 지정", "누른 키에 동작 지정", ctx.icon(R.drawable.ic_add, 24)) { learnKey() })
        keysBox = ctx.vertical().also(body::addView)
        fillKeys()
        keyTestRow = ctx.row("키 테스트", keyTestSummary()) { showKeyTest() }.also(body::addView)
    }

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
            keysBox.addView(ctx.row(KeyNames.name(code), KeyActions.label(action), remove) { chooseKeyAction(code) })
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
            .setNegativeButton("취소", null)
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

    /**
     * "이 키로 할 동작": stores the binding (a learned page-key entry of the same key goes). The key list or the 볼륨 키
     * row shows the result, so no toast says it again.
     */
    private fun chooseKeyAction(code: Int) {
        val actions = KeyActions.CHOICES
        val current = KeyAssign.actionOf(Settings.app, code)
        ctx.chooser("이 키로 할 동작 · ${KeyNames.name(code)}", actions.map { KeyActions.labelFor(code, it) }, actions.indexOf(current)) { i ->
            editApp { KeyAssign.bind(it, code, actions[i]) }
            fillKeys()
            keyTestRow?.setSummary(keyTestSummary())
        }
    }

    /** The 볼륨 키 row reflects the latest settings, also after a key binding took a volume key or let it go. */
    private fun updateVolumeUi() {
        val app = Settings.app
        volumeRow?.let { row ->
            row.setRowEnabled(!KeyMap.volumeBound(app))
            row.setSummary(SettingsFormat.volumeValue(app))
        }
    }

    /** Live key tester: shows each key's code and what the reader does with it (consumes all keys but Back). */
    private fun showKeyTest() {
        val out = ctx.label("아무 키나 누르세요.\n(뒤로 키: 닫기)\n\n코멧의 사용자 키가 안 되면 기기 설정(KeyPack)에서 볼륨 키로 지정해 보세요.", 17f).apply {
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
        val (code, scan) = lastTested ?: return "누른 키의 이름과 동작 확인"
        return "마지막 키: ${KeyNames.label(code)} · 스캔 코드 $scan · ${KeyNames.readerEffectLabel(code, false, Settings.app)}"
    }

    override fun onDestroy() {
        runCatching { liveDialog?.dismiss() }
        liveDialog = null
        runCatching { keyDialog?.dismiss() }
        keyDialog = null
    }
}
