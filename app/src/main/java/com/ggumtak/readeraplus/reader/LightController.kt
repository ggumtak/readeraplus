package com.ggumtak.readeraplus.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.PowerManager
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import android.provider.Settings as SystemSettings

internal interface LightHost {                        // implemented by ReaderActivity (READER_A)
    val activity: Activity
    val handler: Handler
    val app: AppSettings                              // the live one
    val chromeVisible: Boolean
    fun saveApp(a: AppSettings)                       // ReaderActivity.saveApp
    fun setPageBrightnessSwipe(on: Boolean)           // page.brightnessSwipe = on
    fun showChrome()                                  // setChromeVisible(true)
}

/**
 * The reader's brightness (UI_SPEC §4, brightness.md §4): the brightness row and its options panel, the left-edge
 * swipe, the window path (linear `ReaderWindow.applyBrightness`, phones) or the opt-in device path ([DeviceLight],
 * light = [LightCurve.out]), the one-time verdict question per firmware and the restore-on-leave policy.
 *
 * Rules: [onCreate] does no IO (only the window attribute); every device write, the verdict read, the observer and
 * the warm probe start in [afterFirstPage]. A drag allocates nothing on the main thread. The only writer of
 * `page.brightnessSwipe` (C33). Main thread only; IO through [ReaderIo].
 */
internal class LightController(private val host: LightHost) {
    companion object {
        const val ASK_NONE=0; const val ASK_WINDOW=1; const val ASK_DEVICE=2
        /** The manual position Ⓐ returns to (same key as the old ReaderActivity constant). */
        private const val PREF_LAST_BRIGHTNESS = "reader.lastBrightness"
    }

    private var chrome: ReaderChrome? = null
    /** Read on IO in [afterFirstPage]; UNKNOWN until then. */
    private var verdict = DeviceLight.VERDICT_UNKNOWN
    /** True after the first page: device writes wait for it. */
    private var ready = false
    private var ask = ASK_NONE
    /** The first-drag question was considered this session. */
    private var askChecked = false
    private var awaitingPermission = false
    private var ownLaunch = false
    private var backFromOwn = false
    private var resumed = false
    private var destroyed = false
    private var observed = false
    private var hasWarm = false
    /** The device path may have written this session: leaving it restores. */
    private var deviceUsed = false
    /** Automatic confirmation baseline (brightness.md §4.4) and a generation that drops stale IO results. */
    private var cold0 = -1
    private var keys0: Map<String, String>? = null
    private var probeGen = 0

    private val observer by lazy {
        object : ContentObserver(host.handler) {
            override fun onChange(selfChange: Boolean) = DeviceLight.refresh()
        }
    }
    private val onExternal = Runnable { adoptDeviceLight() }
    private val onPermissionLost = Runnable {
        if (destroyed) return@Runnable
        apply(host.app.brightness)
        updateObserver()
        if (host.chromeVisible) bind()
    }
    private val confirmTask = Runnable { confirm() }

    private val app: AppSettings get() = host.app
    private val appCtx: Context get() = host.activity.applicationContext

    /** Called by ReaderActivity right after `ReaderChrome(...)`; no setter runs here. */
    fun attach(chrome: ReaderChrome) { this.chrome = chrome }

    /** onCreate: the window attribute only (no IO). The device path sets no window override. */
    fun onCreate() {
        ReaderWindow.applyBrightness(host.activity, if (app.brightnessDevice) -1f else app.brightness)
    }

    /** afterOpen, main: the verdict and the warm probe on IO, then the first device write and the observer. */
    fun afterFirstPage() {
        val c = appCtx
        DeviceLight.init(c)
        DeviceLight.onExternal = onExternal
        DeviceLight.onNoPermission = onPermissionLost
        ReaderIo.launch {
            val v = DeviceLight.verdict(c)
            val warm = LightProbe.hasWarm(LightProbe.lightKeys(c))
            host.handler.post {
                if (destroyed) return@post
                verdict = v
                hasWarm = warm
                ready = true
                apply(app.brightness)
                host.setPageBrightnessSwipe(app.brightnessSwipe && swipeUsable)
                updateObserver()
                if (host.chromeVisible) bind()
            }
        }
    }

    /** applyAppSettings(): the brightness through the path in use, and the swipe flag (its only writer). */
    fun onAppSettingsApplied() {
        apply(app.brightness)
        host.setPageBrightnessSwipe(app.brightnessSwipe && swipeUsable)
        updateObserver()
    }

    fun onResume() {
        resumed = true
        if (destroyed) return
        updateObserver()
        val c = appCtx
        if (awaitingPermission) {
            awaitingPermission = false
            ReaderIo.launch {
                val can = canWrite(c)
                host.handler.post {
                    if (destroyed) return@post
                    if (can) enableDeviceLight() else host.activity.toast("권한이 허용되지 않아 앱 화면 밝기로 조절합니다")
                }
            }
        } else if (ready && app.brightnessDevice && DeviceLight.noPermission) {
            // Granted again in the system settings meanwhile: back to the device path without a question.
            ReaderIo.launch {
                val can = canWrite(c)
                if (can) host.handler.post {
                    if (destroyed || !DeviceLight.noPermission) return@post
                    DeviceLight.noPermission = false
                    apply(app.brightness)
                    updateObserver()
                    if (host.chromeVisible) bind()
                }
            }
        }
        if (backFromOwn && ready) {
            // Our settings page may have reset the verdict ("밝기 방식 다시 확인").
            backFromOwn = false
            ReaderIo.launch {
                val v = DeviceLight.verdict(c)
                host.handler.post { if (!destroyed && v != verdict) onVerdictReloaded(v) }
            }
        }
        // A leave restored the device's own level: the reader's comes back (applyAppSettings may do it too; the
        // second call coalesces in DeviceLight.set).
        if (ready && deviceOn()) apply(app.brightness)
    }

    fun onPause() {
        resumed = false
        unregisterObserver()
        host.handler.removeCallbacks(confirmTask)
        if (ready && deviceUsed && deviceOn() && !ownLaunch && isInteractive()) {
            // Other apps and our library get the device's own value back (the level only with the switch on).
            DeviceLight.restore(app.brightnessRestore)
        }
        if (ownLaunch) backFromOwn = true
        ownLaunch = false
    }

    fun onDestroy(finishing: Boolean) {
        destroyed = true
        unregisterObserver()
        host.handler.removeCallbacks(confirmTask)
        if (DeviceLight.onExternal === onExternal) DeviceLight.onExternal = null
        if (DeviceLight.onNoPermission === onPermissionLost) DeviceLight.onNoPermission = null
        // Already restored by onPause unless the screen was off: then here (a no-op when nothing is pending).
        if (finishing && ready && deviceUsed && deviceOn()) DeviceLight.restore(app.brightnessRestore)
    }

    /** Right before the reader starts our own SettingsActivity: no restore, so the light does not jump. */
    fun markOwnLaunch() { ownLaunch = true }

    /** PageView.brightnessStart and the slider: the saved position, or the device's own one in auto. */
    fun currentPos(): Float = app.brightness.let { if (it >= 0f) it else systemPos() }

    /** Slider and edge swipe. Main thread, any rate; [done] saves and may start the verdict question. */
    fun onDrag(pos: Float, done: Boolean) {
        val v = if (pos.isNaN()) 0f else pos.coerceIn(0f, 1f)
        apply(v)
        if (!done) return
        setManual(v)
        if (ready && deviceOn()) DeviceLight.settle()
        onDragDone()
    }

    /** Ⓐ: auto ↔ manual (the last manual position). */
    fun onAuto() {
        if (app.brightness < 0f) {
            val v = Settings.raw().getFloat(PREF_LAST_BRIGHTNESS, 0.5f).coerceIn(0f, 1f)
            apply(v)
            setManual(v)
        } else {
            host.saveApp(app.copy(brightness = -1f))
            apply(-1f)
            bind()
        }
    }

    fun onSwipeSwitch(on: Boolean) {
        if (on && !swipeUsable) { bind(); return }
        host.saveApp(app.copy(brightnessSwipe = on))
        host.setPageBrightnessSwipe(on && swipeUsable)
        bind()
    }

    /** The question row's [예]/[아니요] (also the automatic confirmation). */
    fun onAnswer(yes: Boolean) {
        val k = ask
        if (k == ASK_NONE) return
        setAsk(ASK_NONE)
        val v = LightPolicy.nextVerdict(k, yes)
        when {
            k == ASK_WINDOW && !yes -> offerDeviceLight()
            v == DeviceLight.VERDICT_NONE -> {
                saveVerdict(v)
                host.saveApp(app.copy(brightnessDevice = false))
                host.setPageBrightnessSwipe(false)   // a swipe that changes nothing is off (the switch says why)
                apply(app.brightness)                // leaves the device path: the original comes back
                updateObserver()
                bind()
                showNoLightDialog()
            }
            else -> { saveVerdict(v); bind() }
        }
    }

    /** "기기 밝기 직접 조절": on → the permission flow if needed; off → restore now. */
    fun onDeviceSwitch(on: Boolean) {
        if (on) {
            if (verdict == DeviceLight.VERDICT_NONE) { bind(); return }
            if (app.brightnessDevice && !DeviceLight.noPermission) return
            val c = appCtx
            ReaderIo.launch {
                val can = canWrite(c)
                host.handler.post {
                    if (destroyed) return@post
                    if (can) enableDeviceLight() else { bind(); offerDeviceLight() }
                }
            }
        } else {
            if (!app.brightnessDevice) return
            if (ask == ASK_DEVICE) setAsk(ASK_NONE)
            host.saveApp(app.copy(brightnessDevice = false))
            apply(app.brightness)
            updateObserver()
            bind()
        }
    }

    /** "기기 조명 설정 열기" (and the NONE link): the device's display settings. */
    fun onOpenPanel() {
        val a = host.activity
        try {
            a.startActivity(Intent(SystemSettings.ACTION_DISPLAY_SETTINGS))
        } catch (e: Exception) {
            a.toast("화면 위에서 아래로 내려 기기 조명을 조절하세요")
        }
    }

    /** From bindChrome(): every brightness view; the setters skip unchanged values. Reopens the panel while asking. */
    fun bind() {
        val c = chrome ?: return
        val a = app
        val none = verdict == DeviceLight.VERDICT_NONE
        c.setBrightnessUnavailable(none)
        c.setBrightness(currentPos(), a.brightness < 0f)
        c.setSwipeOption(a.brightnessSwipe && !none, !none, LightPolicy.swipeSubtitle(none))
        val noPerm = DeviceLight.noPermission
        c.setLightDevice(a.brightnessDevice && !noPerm && !none,
            LightPolicy.deviceSubtitle(a.brightnessDevice, a.brightnessRestore, noPerm, none, DeviceLight.origAuto), !none)
        c.setLightPanelRow(LightPolicy.panelRowVisible(none, hasWarm), LightPolicy.panelSubtitle(none))
        c.setLightAsk(ask)
        if (ask != ASK_NONE) c.setBrightnessOptionsOpen(true)
    }

    /** False at verdict NONE: the edge swipe would move nothing. */
    val swipeUsable: Boolean get() = verdict != DeviceLight.VERDICT_NONE

    // ---------------------------------------------------------------- paths

    private fun deviceOn(): Boolean =
        app.brightnessDevice && verdict != DeviceLight.VERDICT_NONE && !DeviceLight.noPermission

    /** The reader's brightness [pos] (0..1; < 0 = the device's own) through the path in use. No allocation. */
    private fun apply(pos: Float) {
        val a = host.activity
        if (deviceOn()) {
            ReaderWindow.applyBrightness(a, -1f)          // one source of truth: no window override on top
            if (!ready) return                            // before the first page: afterFirstPage applies it
            deviceUsed = true
            if (pos < 0f) { DeviceLight.restore(); DeviceLight.refresh() } else DeviceLight.set(LightCurve.out(pos))
        } else {
            if (deviceUsed) { deviceUsed = false; DeviceLight.restore() }   // switch off, NONE, revoked
            ReaderWindow.applyBrightness(a, pos)
        }
    }

    /** Slider position of the device's own brightness (auto look, drag start). */
    private fun systemPos(): Float {
        val sys = ReaderWindow.systemBrightness(host.activity)
        if (!deviceOn()) return sys
        val o = DeviceLight.deviceOut
        return LightCurve.pos(if (o >= 0f) o else sys)
    }

    private fun setManual(v: Float) {
        Settings.raw().edit().putFloat(PREF_LAST_BRIGHTNESS, v).apply()
        host.saveApp(app.copy(brightness = v))
        if (host.chromeVisible) bind()
    }

    /** The user changed the light in the device's panel while reading: the slider adopts it (round trip exact). */
    private fun adoptDeviceLight() {
        if (destroyed) return
        val o = DeviceLight.deviceOut
        if (o < 0f || app.brightness < 0f || !deviceOn()) return
        host.saveApp(app.copy(brightness = LightCurve.pos(o)))
        if (host.chromeVisible) bind()
    }

    private fun updateObserver() {
        val want = resumed && ready && !destroyed && deviceOn()
        if (want == observed) return
        observed = want
        val cr = host.activity.contentResolver
        if (want) {
            cr.registerContentObserver(SystemSettings.System.getUriFor(SystemSettings.System.SCREEN_BRIGHTNESS), false, observer)
        } else {
            cr.unregisterContentObserver(observer)
        }
    }

    private fun unregisterObserver() {
        if (!observed) return
        observed = false
        host.activity.contentResolver.unregisterContentObserver(observer)
    }

    private fun isInteractive(): Boolean = try {
        (host.activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (t: Throwable) {
        true
    }

    private fun canWrite(c: Context): Boolean = try { SystemSettings.System.canWrite(c) } catch (t: Throwable) { false }

    // ---------------------------------------------------------------- the verdict flow (UI_SPEC §4.4)

    private fun onDragDone() {
        if (!ready) return
        if (ask != ASK_NONE) { scheduleConfirm(); return }
        if (verdict != DeviceLight.VERDICT_UNKNOWN || askChecked) return
        askChecked = true
        val c = appCtx
        val dev = deviceOn()
        ReaderIo.launch {
            val eink = dev || DeviceLight.looksEink(c)
            val silent = LightPolicy.silentWindow(dev, eink)
            if (silent) DeviceLight.setVerdict(c, DeviceLight.VERDICT_WINDOW)
            val k = if (silent) ASK_NONE else LightPolicy.firstDragAsk(dev, eink, DeviceLight.asks(c))
            if (k != ASK_NONE) DeviceLight.countAsk(c)
            host.handler.post {
                if (destroyed || verdict != DeviceLight.VERDICT_UNKNOWN) return@post
                if (silent) verdict = DeviceLight.VERDICT_WINDOW
                else if (k != ASK_NONE && ask == ASK_NONE) setAsk(k)
            }
        }
    }

    /**
     * Stores the question and shows it on the options panel's top row. The panel opens now if the chrome is shown,
     * else on its next show ([bind]); a question never pops over the page.
     */
    private fun setAsk(k: Int) {
        ask = k
        probeGen++
        keys0 = null
        cold0 = -1
        host.handler.removeCallbacks(confirmTask)
        val c = chrome
        if (c != null) {
            c.setLightAsk(k)
            if (k != ASK_NONE) c.setBrightnessOptionsOpen(true)
        }
        if (k != ASK_NONE) snapshot()
    }

    private fun onVerdictReloaded(v: Int) {
        verdict = v
        if (v == DeviceLight.VERDICT_UNKNOWN) askChecked = false
        if (ask != ASK_NONE && v != DeviceLight.VERDICT_UNKNOWN) setAsk(ASK_NONE)
        apply(app.brightness)
        host.setPageBrightnessSwipe(app.brightnessSwipe && swipeUsable)
        updateObserver()
        if (host.chromeVisible) bind()
    }

    private fun saveVerdict(v: Int) {
        verdict = v
        val c = appCtx
        ReaderIo.launch { DeviceLight.setVerdict(c, v) }
    }

    /** The automatic-confirmation baseline (brightness.md §4.4), on IO while a question is open. */
    private fun snapshot() {
        val g = probeGen
        val c = appCtx
        ReaderIo.launch {
            val cold = LightProbe.read(LightProbe.coldNode())
            val keys = LightPolicy.vendorLightKeys(LightProbe.lightKeys(c))
            host.handler.post { if (g == probeGen && ask != ASK_NONE) { cold0 = cold; keys0 = keys } }
        }
    }

    private fun scheduleConfirm() {
        host.handler.removeCallbacks(confirmTask)
        host.handler.postDelayed(confirmTask, LightPolicy.CONFIRM_DELAY_MS)
    }

    private fun confirm() {
        val k = ask
        val base = keys0
        if (k == ASK_NONE || base == null || destroyed) return
        val g = probeGen
        val c0 = cold0
        val c = appCtx
        ReaderIo.launch {
            val cold = LightProbe.read(LightProbe.coldNode())
            val keys = LightPolicy.vendorLightKeys(LightProbe.lightKeys(c))
            if (LightPolicy.autoConfirm(k, c0, cold, base, keys)) {
                host.handler.post { if (!destroyed && g == probeGen && ask == k) onAnswer(true) }
            }
        }
    }

    // ---------------------------------------------------------------- permission and dialogs (UI_SPEC §4.5)

    /** Dialog A, then the system's "시스템 설정 수정" page (or straight to the device path when already allowed). */
    private fun offerDeviceLight() {
        val a = host.activity
        if (a.isFinishing || a.isDestroyed) return
        a.alert()
            .setTitle("기기 밝기 직접 조절")
            .setMessage(
                "이 기기의 전면광은 앱 화면 밝기를 따르지 않을 수 있습니다.\n" +
                    "'시스템 설정 수정'을 허용하면 리더가 기기 밝기 설정을 직접 바꿉니다.\n\n" +
                    "· 기기 전체 밝기가 바뀝니다. 다른 앱에서도 같은 밝기가 보일 수 있습니다.\n" +
                    "· 리더를 나가면 원래 밝기로 되돌립니다. (설정에서 바꿀 수 있어요)\n" +
                    "· 다음 화면에서 ReaderaPlus를 찾아 허용을 켠 뒤 돌아오세요."
            )
            .setNegativeButton("취소") { _, _ -> bind() }
            .setPositiveButton("허용하러 가기") { _, _ -> requestWriteSettings() }
            .showNoAnim()
    }

    private fun requestWriteSettings() {
        val c = appCtx
        ReaderIo.launch {
            val can = canWrite(c)
            host.handler.post {
                if (destroyed) return@post
                if (can) enableDeviceLight() else openPermissionPage()
            }
        }
    }

    private fun openPermissionPage() {
        val a = host.activity
        awaitingPermission = true
        try {
            a.startActivity(Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + a.packageName)))
        } catch (e: ActivityNotFoundException) {
            try {
                a.startActivity(Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS))
            } catch (e2: Exception) {
                awaitingPermission = false
                showNoPermissionScreenDialog()
            }
        } catch (e: Exception) {
            awaitingPermission = false
            showNoPermissionScreenDialog()
        }
    }

    /** Permission granted: the device path now, with a visible manual value, then ASK_DEVICE while UNKNOWN. */
    private fun enableDeviceLight() {
        DeviceLight.noPermission = false
        val pos = app.brightness.let { if (it < 0f) systemPosForDevice() else it }
        host.saveApp(app.copy(brightnessDevice = true, brightness = pos))
        apply(pos)
        updateObserver()
        if (verdict == DeviceLight.VERDICT_UNKNOWN) setAsk(ASK_DEVICE)
        if (!host.chromeVisible) host.showChrome()
        bind()
    }

    /** The device's own level as a device-path slider position (before the switch is saved on). */
    private fun systemPosForDevice(): Float {
        val o = DeviceLight.deviceOut
        return LightCurve.pos(if (o >= 0f) o else ReaderWindow.systemBrightness(host.activity))
    }

    /** Dialog B: verdict NONE. */
    private fun showNoLightDialog() {
        val a = host.activity
        if (a.isFinishing || a.isDestroyed) return
        a.alert()
            .setTitle("앱에서 조명을 바꿀 수 없어요")
            .setMessage(
                "이 기기는 다른 앱이 전면광을 바꾸는 방법을 열어 두지 않았습니다. 원래 밝기로 되돌렸습니다.\n" +
                    "밝기와 색온도는 화면 위에서 아래로 내려 기기 조명에서 조절해 주세요."
            )
            .setNegativeButton("확인", null)
            .setPositiveButton("기기 설정 열기") { _, _ -> onOpenPanel() }
            .showNoAnim()
    }

    /** Dialog C: the firmware hides the special-access screen. */
    private fun showNoPermissionScreenDialog() {
        val a = host.activity
        if (a.isFinishing || a.isDestroyed) return
        val cmd = "adb shell appops set ${a.packageName} WRITE_SETTINGS allow"
        a.alert()
            .setTitle("권한 화면을 찾을 수 없어요")
            .setMessage("이 기기에는 '시스템 설정 수정' 화면이 없습니다. PC에 연결해 다음 명령으로 허용할 수 있습니다.\n\n$cmd")
            .setNegativeButton("닫기", null)
            .setPositiveButton("명령 복사") { _, _ ->
                try {
                    (a.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                } catch (t: Throwable) { /* no clipboard: the command stays readable in the dialog */ }
            }
            .showNoAnim()
    }
}
