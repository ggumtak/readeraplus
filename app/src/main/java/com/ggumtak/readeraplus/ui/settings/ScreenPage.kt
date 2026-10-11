package com.ggumtak.readeraplus.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import com.ggumtak.readeraplus.reader.DeviceLight
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.ReadMode
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.Settings as SystemSettings

/**
 * "화면·밝기": 화면 (전체 화면, 화면 켜짐 유지, 화면 방향) and 밝기 (UI_SPEC §4.6, brightness.md §5.6: 스와이프로 밝기
 * 조절, 기기 밝기 직접 조절 and its permission flow, 나갈 때 원래 밝기로, the device's own light settings). The status
 * bar moved to [StatusBarPage] and 형광펜 색 표시 to 읽기 설정 (2026-10-11).
 */
internal class ScreenPage(a: SettingsActivity) : SettingsPage(a, SettingsActivity.PAGE_SCREEN, "화면·밝기") {
    private var swipeLightRow: View? = null
    private var deviceRow: View? = null
    private var restoreRow: View? = null
    /** Shown only while the device light was judged unusable (the two rows above are off then): the way back. */
    private var verdictRow: View? = null
    /** "시스템 설정 수정" as last read on IO; null until the first read (the subtitle then assumes it is granted). */
    private var canWrite: Boolean? = null
    /** The device's original brightness mode is automatic ([DeviceLight.readOrigAuto]): the keep subtitle says so. */
    private var origAuto = false
    /** Verdict NONE: the app cannot change the front light here (the light rows are disabled, the reason shown). */
    private var lightNone = false
    /** The device light has a verdict (asked before): "밝기 방식 다시 묻기" can undo it. */
    private var lightJudged = false
    /** The user went to the permission page from "기기 밝기 직접 조절": turn it on when they come back granted. */
    private var pendingDevice = false
    private var lightDialog: android.app.AlertDialog? = null

    override fun build(): View {
        val app = Settings.app
        val body = ctx.pageBody()
        addScreen(body, app)
        addBrightness(body, app)
        return ctx.pageScroll(body)
    }

    override fun onShown() {
        refreshLight()
    }

    override fun onResume() {
        // Back from the system's permission page: the grant is picked up here.
        refreshLight()
    }

    override fun onDestroy() {
        runCatching { lightDialog?.dismiss() }
        lightDialog = null
    }

    // ---------------------------------------------------------------- 화면

    private fun addScreen(body: LinearLayout, app: AppSettings) {
        body.section("화면")
        body.addView(ctx.toggleRow("전체 화면", null, app.fullscreen) { v -> editApp { it.copy(fullscreen = v) } })
        body.addView(ctx.toggleRow("화면 켜짐 유지", null, app.keepScreenOn) { v ->
            editApp { it.copy(keepScreenOn = v) }
        })
        var orientationRow: View? = null
        orientationRow = ctx.valueRow("화면 방향", SettingsFormat.orientation(app.orientationLock)) {
            val opts = SettingsFormat.ORIENTATIONS
            val sel = opts.indexOfFirst { it.second == Settings.app.orientationLock }.coerceAtLeast(0)
            ctx.chooser("화면 방향", opts.map { it.first }, sel) { i ->
                editApp { it.copy(orientationLock = opts[i].second) }
                orientationRow?.setSummary(opts[i].first)
            }
        }.also(body::addView)
    }

    // ---------------------------------------------------------------- 밝기 (UI_SPEC §4.6, brightness.md §5.6)

    private fun addBrightness(body: LinearLayout, app: AppSettings) {
        body.section("밝기")
        swipeLightRow = ctx.toggleRow("스와이프로 밝기 조절", R3Rows.brightnessSwipe(app.readMode == ReadMode.SCROLL, false), app.brightnessSwipe) { v ->
            editApp { it.copy(brightnessSwipe = v) }
        }.also(body::addView)
        deviceRow = ctx.toggleRow(
            "기기 밝기 직접 조절",
            R3Rows.brightnessDevice(app.brightnessDevice, app.brightnessRestore, canWrite = true, none = false),
            app.brightnessDevice,
        ) { on -> onDeviceSwitch(on) }.also(body::addView)
        // Only matters while the switch above is on: hidden otherwise, in the same pass.
        restoreRow = ctx.toggleRow("나갈 때 원래 밝기로", null, app.brightnessRestore) { v ->
            editApp { it.copy(brightnessRestore = v) }
            updateLightUi()
        }.also(body::addView)
        verdictRow = ctx.row("밝기 방식 다시 묻기", null) { resetVerdict() }.also(body::addView)
        body.addView(ctx.navRow("기기 조명 설정", R3Rows.LIGHT_SETTINGS) { openDisplaySettings() })
        updateLightUi()
    }

    /**
     * The switch flipped to [on]. Turning it on needs "시스템 설정 수정": without it the switch stays off and dialog A
     * leads to the permission page; the grant is picked up in [refreshLight] when the user comes back. A tap while it
     * is on but the permission was revoked opens the same flow instead of turning it off. A tap before the first read
     * of the permission finished reads it first (never the dialog for a permission that is granted).
     */
    private fun onDeviceSwitch(on: Boolean) {
        if (canWrite == null) {
            val appCtx = activity.applicationContext
            activity.scope.launch {
                val write = withContext(Dispatchers.IO) { runCatching { SystemSettings.System.canWrite(appCtx) }.getOrDefault(false) }
                if (canWrite == null) canWrite = write
                onDeviceSwitch(on)
            }
            return
        }
        val app = Settings.app
        if (on && canWrite != true) {
            deviceRow?.setToggleChecked(false)
            askPermission()
            return
        }
        if (!on && app.brightnessDevice && canWrite == false) {
            deviceRow?.setToggleChecked(true)
            askPermission()
            return
        }
        if (app.brightnessDevice != on) editApp { it.copy(brightnessDevice = on) }
        updateLightUi()
    }

    /** Re-reads the permission and the verdict on IO (prefs and a system call; never on main), then updates the rows. */
    private fun refreshLight() {
        val appCtx = activity.applicationContext
        activity.scope.launch {
            val (write, verdict, auto) = withContext(Dispatchers.IO) {
                Triple(
                    runCatching { SystemSettings.System.canWrite(appCtx) }.getOrDefault(false),
                    runCatching { DeviceLight.verdict(appCtx) }.getOrDefault(DeviceLight.VERDICT_UNKNOWN),
                    DeviceLight.readOrigAuto(appCtx),
                )
            }
            canWrite = write
            if (auto != null) origAuto = auto
            lightNone = verdict == DeviceLight.VERDICT_NONE
            lightJudged = verdict != DeviceLight.VERDICT_UNKNOWN
            if (pendingDevice) {
                pendingDevice = false
                if (write && !lightNone) {
                    editApp { it.copy(brightnessDevice = true) }
                    deviceRow?.setToggleChecked(true)
                } else if (!write) {
                    ctx.toast("권한이 허용되지 않아 앱 화면 밝기로 조절합니다")
                }
            }
            updateLightUi()
        }
    }

    private fun updateLightUi() {
        val app = Settings.app
        val none = lightNone
        swipeLightRow?.let { row ->
            row.setRowEnabled(!none)
            row.setSummary(R3Rows.brightnessSwipe(app.readMode == ReadMode.SCROLL, none))
            row.setToggleChecked(app.brightnessSwipe)
        }
        deviceRow?.let { row ->
            row.setRowEnabled(!none)
            row.setSummary(R3Rows.brightnessDevice(app.brightnessDevice, app.brightnessRestore, canWrite != false, none, origAuto))
            row.setToggleChecked(app.brightnessDevice)
        }
        restoreRow?.let { row ->
            row.setShown(app.brightnessDevice)
            row.setRowEnabled(!none)
        }
        verdictRow?.setShown(lightJudged)
    }

    /** "밝기 방식 다시 묻기": the reader asks again at the next brightness change. */
    private fun resetVerdict() {
        val appCtx = activity.applicationContext
        activity.scope.launch {
            withContext(Dispatchers.IO) { runCatching { DeviceLight.setVerdict(appCtx, DeviceLight.VERDICT_UNKNOWN) } }
            lightNone = false
            lightJudged = false
            updateLightUi()
            ctx.toast("다음에 밝기를 바꿀 때 다시 묻습니다")
        }
    }

    /** Dialog A (brightness.md §5.3), then the system's "시스템 설정 수정" page for this app. */
    private fun askPermission() {
        if (lightDialog?.isShowing == true) return
        lightDialog = ctx.alert()
            .setTitle("기기 밝기 직접 조절")
            .setMessage(R3Rows.DEVICE_DIALOG)
            .setNegativeButton("취소", null)
            .setPositiveButton("허용하러 가기") { _, _ -> openWritePermission() }
            .showNoAnim()
    }

    private fun openWritePermission() {
        val pkg = activity.packageName
        val withUri = Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$pkg"))
        val plain = Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS)
        for (intent in arrayOf(withUri, plain)) {
            try {
                activity.startActivity(intent)
                pendingDevice = true
                return
            } catch (_: Exception) {
                // Trimmed firmware: try the next form, then explain the adb grant.
            }
        }
        lightDialog = ctx.alert()
            .setTitle("권한 화면을 열 수 없습니다")
            .setMessage(R3Rows.NO_PERMISSION_SCREEN)
            .setNegativeButton("닫기", null)
            .setPositiveButton("명령 복사") { _, _ ->
                runCatching {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("adb", R3Rows.ADB_GRANT))
                }
                ctx.toast("명령을 복사했습니다")
            }
            .showNoAnim()
    }

    private fun openDisplaySettings() {
        try {
            activity.startActivity(Intent(SystemSettings.ACTION_DISPLAY_SETTINGS))
        } catch (_: Exception) {
            ctx.toast("화면 위에서 아래로 내려 기기 조명을 조절하세요")
        }
    }
}
