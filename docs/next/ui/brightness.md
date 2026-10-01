# Brightness on the Comet (item 3): design spec

Status: design only. The repo was not edited. Code references are HEAD symbols. Another workflow is editing the tree, so
lines move; search by symbol.
Owners: READER_A (`reader/*`), SETTINGS (`ui/settings/*`), LIBRARY (one call), plus contract requests for frozen files (§10).
Plugs into the sibling spec `ui/chrome.md` §5 (options panel, `addBrightnessOption`). It is consistent with
`ui/audit.md` A3/A5 and with the scroll designs (§12).

---

## 0. Decision in one paragraph

Keep the **window override** as the default path. It works on phones, is per-app and reverts by itself. Add an opt-in
**"기기 밝기 직접 조절"** path that writes `Settings.System.SCREEN_BRIGHTNESS` (and forces manual mode). It uses the
`WRITE_SETTINGS` special access, runs on one serial background thread, and by default puts the device's own value back
when the reader leaves. No step can be trusted blind on the Comet: the firmware may ignore both paths (see §1).
So the reader asks the user **once per device firmware**, after their first brightness drag, whether the light changed.
It confirms automatically when it can read the hardware back. It records a verdict: `WINDOW`, `DEVICE` or `NONE`.
With `NONE`, the dead slider is replaced by an honest "기기 조명 설정에서 조절 ›" link, and the edge-swipe is turned off.
Warm light is shown only as a link to the device's panel. Apps cannot write any warm-light control on this firmware
family. Everything is behind the first page. A page turn does no work. The app adds no timers and no idle wakeups.

---

## 1. What we know (evidence)

| # | Fact | Confidence | Source |
|---|---|---|---|
| F1 | Our code sets only `WindowManager.LayoutParams.screenBrightness` (`ReaderWindow.applyBrightness`, floor 0.01). The user reports the Comet's front light does not move. | confirmed (user) | `reader/ReaderWindow.kt`, user feedback |
| F2 | The Comet's spec sheet matches the Bigme HiBreak (5.84", 1440×720, A14, 6+128, 3300 mAh, **36-level warm/cool front light**). `Eink.kt` already probes the Bigme `xrz` framework. | strong inference | `research_research_device.md` §2 |
| F3 | On Bigme HiBreak/B6, the front light is a **TI LM3630A** two-string driver (cold + warm). SystemUI drives it through **ioctl on `/dev/lm3630a`** (0x7901 cold, 0x7902 warm). There are also sysfs nodes `…/2-0036/lm3630a_cold_light` and `…/lm3630a_warm_light` (0–255). The user's HiBreak Pro Color has the same chip at `/sys/devices/platform/11d01000.i2c7/i2c-7/7-0036`. | confirmed for Bigme | XDA HiBreak root thread (search snippets), `bigmelight.koplugin` source, `gap/hpc_CLAUDE.md` §3/§7 |
| F4 | Bigme keeps the light state in **custom** `Settings.System` keys: `ColdValue`, `WarmValue`, `LastColdLight`, `LastWarmLight`, `screen_brightness_cold` and `screen_brightness_warm`. The only open-source controller (the KOReader plugin) writes the **sysfs node through root** and then writes these keys "to keep EinkCenter & OS in sync". It never writes `screen_brightness`. | confirmed (code) | `right9code/bigmelight.koplugin` `main.lua` `schedule_global_sync` |
| F5 | On some two-channel e-ink Androids, "`Settings.System screen_brightness` is entirely ignored, which is why any screen brightness control in reader apps does nothing". The light follows vendor keys (`screen_cool_brightness` / `screen_warm_brightness`) instead. | confirmed (other vendors) | MobileRead thread (search snippet) |
| F6 | On Android 14, a normal app can read **any** `Settings.System` key that the framework does not define. That includes the vendor keys above, and a query of `Settings.System.CONTENT_URI` lists them. With `WRITE_SETTINGS` it can write **only** keys in `Settings.System.PUBLIC_SETTINGS`, which include `screen_brightness`, `screen_brightness_mode` and `screen_brightness_float`. Writing a custom key throws `IllegalArgumentException("You cannot keep your settings in the secure settings.")` for targetSdk > 22 (we target 34, and Android 14 refuses installs below 23). | confirmed (AOSP source) | `SettingsProvider.java` android14-release: `checkReadableAnnotation` ("a key string that is not defined in any of the Settings.* classes will still be regarded as readable"), `enforceRestrictedSystemSettingsMutationForCallingPackage`, `warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk`, `getAllSystemSettings`; `Settings.java` `PUBLIC_SETTINGS` |
| F7 | KOReader's Android launcher uses the window override on unknown devices (`GenericController`). It writes `Settings.System.SCREEN_BRIGHTNESS` (with `WRITE_SETTINGS`) on Tolino B300/Nook, where the firmware maps it to the light. It uses root sysfs or vendor services elsewhere. **It has no Bigme controller.** | confirmed (code) | `android-luajit-launcher/…/device/lights/*` (cloned in scratchpad) |
| F8 | `XrzEinkManagerInternal` has static `getScreenBrightnessLevel()` / `setScreenBrightnessLevel(int)`, next to `get/setScreenDarkLevel`. `DisplayPolicy` has `appBrightnessLevel`, and there is `sys.xrz.global_brightness`. The pairing with "dark level" and contrast suggests these are the **image tone** controls of E-Ink Center, not the front light. They are unverified. | weak | `inksdk/docs/bigme-sdk-reverse-engineered.md`, `fd/eink.md` §Bigme |
| F9 | The sysfs light nodes are root-owned. The plugin needs Magisk to `chmod 666` them. SELinux normally stops `untrusted_app` from reading or writing driver sysfs. | confirmed / likely | plugin README, AOSP policy (general) |

**What follows.**
- If the Comet is a Bigme-family build, the **most likely** outcome is that the window override fails (already seen) and
  `screen_brightness` also fails (F4, F5). Only the user can confirm this, on the device (§9).
- The vendor keys that do drive the light are **readable but not writable** for us (F6). The hardware path needs root (F9).
- So the design must (a) try the one public lever that is left (`screen_brightness`), (b) find out quickly whether it
  works, and (c) be honest when nothing works. A slider that silently does nothing is exactly the complaint.

---

## 2. Paths and how each is detected

| Path | Works on | Needs | Detected by | Used when |
|---|---|---|---|---|
| **W. Window override** (`screenBrightness`) | phones; e-ink whose light is the display backlight | nothing | phones: assumed (not e-ink, §4.3). E-ink: user answer or hardware readback | default; `brightnessDevice == false`, or device path unavailable |
| **D. Device setting** (`Settings.System.SCREEN_BRIGHTNESS` 1..255 + `SCREEN_BRIGHTNESS_MODE = MANUAL`) | phones; e-ink whose firmware maps `screen_brightness` to the light (Tolino/Nook-like). Bigme: probably not (F4/F5) | `WRITE_SETTINGS` special access (`Settings.System.canWrite`) | user answer, or automatic readback: sysfs node, or a vendor key moving with our write (§4.4) | `brightnessDevice == true` && can write && verdict ≠ NONE |
| **V. Vendor keys** (`ColdValue`, `screen_brightness_cold`, …) | Bigme firmware | writing is impossible for a normal app (F6) | read-only probe (diagnostics, readback signal, warm-light presence) | **never written.** Read for detection only |
| **S. sysfs node** (`/sys/bus/i2c/devices/*-0036/lm3630a_{cold,warm}_light`) | Bigme HW | root, or a firmware that leaves it writable (unlikely) | `File.canRead()/canWrite()` probe, IO, once | read for automatic verification. Written only if `canWrite()` (rare); warm slider only then (§7) |
| **X. xrz `setScreenBrightnessLevel`** | unknown (probably image tone, F8) | none | getter value shown in diagnostics | **never called** in this round (§11 R6) |
| **R. root (`su`)** | rooted Bigme | Magisk | — | out of scope (Magisk prompt, security). Listed so nobody re-researches it |
| **P. device panel** | all | — | `Settings.ACTION_DISPLAY_SETTINGS` resolves | the honest fallback link |

Verdict per firmware: `UNKNOWN → WINDOW | DEVICE | NONE`. It is stored with `Build.FINGERPRINT`, so an OTA update asks
again. The state machine is in §4.3.

---

## 3. Code: `reader/LightCurve.kt`, `reader/DeviceLight.kt`, `reader/LightProbe.kt` (new, READER_A)

### 3.1 `LightCurve` (pure, unit-tested)

```kotlin
package com.ggumtak.readeraplus.reader

/**
 * Slider position ↔ light. `AppSettings.brightness` stores the slider POSITION p (0..1, < 0 = the device's own).
 * The light is p²: fine steps at the low end, where night reading lives (fd/eink.md §8). Android's own slider is
 * gamma-shaped for the same reason. Both paths (window and device) use it.
 */
internal object LightCurve {
    /** Framework int range of Settings.System.SCREEN_BRIGHTNESS. 0 is "off/invalid" to its int→float mapping: never written. */
    const val LEVEL_MIN = 1
    const val LEVEL_MAX = 255

    fun out(pos: Float): Float { val p = pos.coerceIn(0f, 1f); return p * p }
    fun pos(out: Float): Float = Math.sqrt(out.coerceIn(0f, 1f).toDouble()).toFloat()
    fun level(out: Float, min: Int = LEVEL_MIN, max: Int = LEVEL_MAX): Int =
        (min + Math.round(out.coerceIn(0f, 1f) * (max - min))).coerceIn(min, max)
    fun fraction(level: Int, min: Int = LEVEL_MIN, max: Int = LEVEL_MAX): Float =
        if (max <= min) 1f else ((level - min).toFloat() / (max - min)).coerceIn(0f, 1f)

    /**
     * A change of the setting that is not our write: not our last value, not within [echoMs] of our write (vendors
     * may quantise or mirror it back), and no write of ours is queued.
     */
    fun isExternal(value: Int, ours: Int, sinceOurWriteMs: Long, queued: Boolean, echoMs: Long = 1500L): Boolean =
        ours >= 0 && value >= 0 && value != ours && sinceOurWriteMs >= echoMs && !queued
}
```

`ReaderWindow.applyBrightness(activity, value)` keeps its signature and its 0.01 floor. Callers pass
`LightCurve.out(pos)` (or −1). `ReaderWindow.systemBrightness()` is deleted, and the callers use
`ReaderActivity.systemPos()` (§4.1).
*Behaviour change on phones:* a saved 0.5 now means light 0.25. This is a one-time shift that users re-adjust (§11 R8).

### 3.2 `DeviceLight` (process-wide: the device setting is global and must outlive an activity)

```kotlin
package com.ggumtak.readeraplus.reader

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ggumtak.readeraplus.render.Eink
import java.util.concurrent.atomic.AtomicBoolean
import android.provider.Settings as SystemSettings

/**
 * The device's own brightness setting (Settings.System.SCREEN_BRIGHTNESS), for front lights that ignore the window
 * override. Opt-in (AppSettings.brightnessDevice), only with the WRITE_SETTINGS special access.
 *
 * Global on purpose: the setting is the device's, as the UI says. The value and mode found before the first write are
 * committed to disk first. [restore] puts them back when the reader leaves (AppSettings.brightnessRestore), and
 * [restoreIfStale] repairs a crash on the next start.
 *
 * Threading: every read and write of the setting runs on ONE serial thread, so a restore is never overtaken by a
 * late drag write. The main thread only stores a float and posts a reused Runnable. No allocation, no binder call, and
 * at most one write per [MIN_GAP_MS] however fast the finger moves.
 */
internal object DeviceLight {
    private const val TAG = "DeviceLight"
    /** Device-local state. Not Settings.raw(), so never in a backup: a restored "value to put back" would be stale. */
    private const val PREFS = "reader_light"
    private const val K_PENDING = "pending"
    private const val K_ORIG = "orig"
    private const val K_ORIG_MODE = "origMode"
    private const val K_STAMP = "stamp"
    private const val K_VERDICT = "verdict"
    private const val K_VERDICT_FP = "verdictFp"
    private const val K_ASKS = "asks"
    private const val MANUAL = SystemSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    private const val KEY = SystemSettings.System.SCREEN_BRIGHTNESS
    private const val KEY_MODE = SystemSettings.System.SCREEN_BRIGHTNESS_MODE

    /** ≤ 10 writes a second while dragging: each is a settings-file write in system_server. */
    const val MIN_GAP_MS = 100L
    const val ECHO_MS = 1500L

    const val VERDICT_UNKNOWN = 0
    const val VERDICT_WINDOW = 1
    const val VERDICT_DEVICE = 2
    const val VERDICT_NONE = 3

    /** The permission went away (revoked in the system settings): the reader falls back to the window path. */
    @Volatile var noPermission = false; private set
    /** The setting as last read or written (0..1 light, NaN = unknown). Written only on the light thread. */
    @Volatile var deviceOut = Float.NaN; private set

    /** Main-thread callbacks, set by the reader (Runnables: no allocation when they fire). */
    @Volatile var onExternal: Runnable? = null
    @Volatile var onNoPermission: Runnable? = null

    @Volatile private var ctx: Context? = null
    @Volatile private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var targetOut = Float.NaN
    private val queued = AtomicBoolean(false)
    @Volatile private var lastWriteAt = 0L
    @Volatile private var lastWritten = -1
    private var modeForced = false // light thread only

    fun init(context: Context) { if (ctx == null) ctx = context.applicationContext }

    private fun h(): Handler = handler ?: synchronized(this) {
        handler ?: Handler(HandlerThread("reader-light").apply { start() }.looper).also { handler = it }
    }

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- writes (main thread posts, light thread works)

    /** Main thread, any rate. [out] = light 0..1 ([LightCurve.out] of the slider position). The latest value wins. */
    fun set(out: Float) {
        targetOut = out
        if (!queued.compareAndSet(false, true)) return
        val wait = (lastWriteAt + MIN_GAP_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        h().postDelayed(writeTask, wait)
    }

    private val writeTask = Runnable {
        queued.set(false)
        val out = targetOut
        val c = ctx
        if (!out.isNaN() && c != null) write(c, out)
        lastWriteAt = SystemClock.uptimeMillis()
    }

    private fun write(c: Context, out: Float) {
        val cr = c.contentResolver
        try {
            val p = prefs(c)
            if (!p.getBoolean(K_PENDING, false)) {
                // What to put back, on disk BEFORE the first write (commit): a crash right after still restores.
                p.edit()
                    .putBoolean(K_PENDING, true)
                    .putInt(K_ORIG, SystemSettings.System.getInt(cr, KEY, -1))
                    .putInt(K_ORIG_MODE, SystemSettings.System.getInt(cr, KEY_MODE, MANUAL))
                    .putLong(K_STAMP, installStamp(c))
                    .commit()
            }
            if (!modeForced) {
                // In automatic mode the value is ignored (phones). The Comet has no light sensor: already manual.
                if (SystemSettings.System.getInt(cr, KEY_MODE, MANUAL) != MANUAL) SystemSettings.System.putInt(cr, KEY_MODE, MANUAL)
                modeForced = true
            }
            val level = LightCurve.level(out)
            if (level != lastWritten) {
                SystemSettings.System.putInt(cr, KEY, level)
                lastWritten = level
            }
            deviceOut = LightCurve.fraction(level)
            noPermission = false
        } catch (e: SecurityException) {
            noPermission = true
            onNoPermission?.let { main.post(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "write failed", t)
        }
    }

    /** Puts back what the reader found (if it changed anything), after any queued write. Any thread. */
    fun restore() {
        val hh = if (handler != null) handler!! else if (ctx != null) h() else return
        hh.removeCallbacks(writeTask)
        queued.set(false)
        targetOut = Float.NaN
        hh.post(restoreTask)
    }

    private val restoreTask = Runnable {
        val c = ctx ?: return@Runnable
        val p = prefs(c)
        if (p.getBoolean(K_PENDING, false)) {
            try {
                // A reinstall (auto backup restored this file) must not put back a months-old value.
                if (p.getLong(K_STAMP, 0L) == installStamp(c)) {
                    val cr = c.contentResolver
                    val orig = p.getInt(K_ORIG, -1)
                    if (orig >= 0) SystemSettings.System.putInt(cr, KEY, orig)
                    val mode = p.getInt(K_ORIG_MODE, MANUAL)
                    if (mode != MANUAL) SystemSettings.System.putInt(cr, KEY_MODE, mode)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "restore failed", t) // revoked meanwhile: the device keeps the reader's value
            }
            p.edit().putBoolean(K_PENDING, false).commit()
        }
        lastWritten = -1
        modeForced = false
        deviceOut = Float.NaN
    }

    /** Library start (IO): a reader that crashed while in front left its value behind. */
    fun restoreIfStale(context: Context) {
        init(context)
        if (com.ggumtak.readeraplus.data.ReaderPresence.inFront) return
        if (prefs(context).getBoolean(K_PENDING, false)) restore()
    }

    // ---------------------------------------------------------------- reads / external changes

    /** Re-reads the setting (after the observer fired, or to position the slider). Any thread. */
    fun refresh() { if (ctx != null) h().post(readTask) }

    private val readTask = Runnable {
        val c = ctx ?: return@Runnable
        val v = try { SystemSettings.System.getInt(c.contentResolver, KEY, -1) } catch (t: Throwable) { -1 }
        if (v < 0) return@Runnable
        deviceOut = LightCurve.fraction(v, LightCurve.LEVEL_MIN, maxOf(LightCurve.LEVEL_MAX, v))
        val since = SystemClock.uptimeMillis() - lastWriteAt
        if (LightCurve.isExternal(v, lastWritten, since, queued.get(), ECHO_MS)) {
            // The user set the light in the device's own panel while reading: keep theirs, put nothing back on exit.
            prefs(c).edit().putBoolean(K_PENDING, false).apply()
            lastWritten = v
            onExternal?.let { main.post(it) }
        }
    }

    // ---------------------------------------------------------------- verdict (IO callers)

    fun verdict(c: Context): Int {
        val p = prefs(c)
        return if (p.getString(K_VERDICT_FP, null) == Build.FINGERPRINT) p.getInt(K_VERDICT, VERDICT_UNKNOWN) else VERDICT_UNKNOWN
    }

    fun setVerdict(c: Context, v: Int) {
        prefs(c).edit().putInt(K_VERDICT, v).putString(K_VERDICT_FP, Build.FINGERPRINT).putInt(K_ASKS, 0).apply()
    }

    /** Sessions in which the question was shown but not answered; the reader stops asking after 3. */
    fun asks(c: Context): Int = prefs(c).getInt(K_ASKS, 0)
    fun countAsk(c: Context) { prefs(c).edit().putInt(K_ASKS, asks(c) + 1).apply() }

    /**
     * True where the front light may ignore the window override, so the reader asks once after a drag. IO thread:
     * it may run the Eink vendor probe (reflection, cached). A false negative only skips the question; the option is
     * always in the brightness panel.
     */
    fun looksEink(): Boolean {
        val s = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} ${Build.DEVICE} ${Build.PRODUCT}".lowercase()
        for (w in EINK_WORDS) if (s.contains(w)) return true
        return try { Eink.vendorName() != null } catch (t: Throwable) { false }
    }

    private val EINK_WORDS = arrayOf(
        "innospace", "comet", "bigme", "hibreak", "xrz", "onyx", "boox", "meebook", "likebook", "boyue",
        "hisense", "tolino", "crema", "pocketbook", "inkpalm", "moaan",
    )

    @Suppress("DEPRECATION")
    private fun installStamp(c: Context): Long = try {
        c.packageManager.getPackageInfo(c.packageName, 0).firstInstallTime
    } catch (t: Throwable) {
        0L
    }
}
```

Notes on the code:
- `set()` allocates nothing. It writes one volatile float, does one CAS and calls `postDelayed` with a reused Runnable
  (the Message comes from the pool). No binder call happens on the main thread.
- `canWrite` is not checked before each write. The `SecurityException` from `putInt` (SettingsProvider
  `checkAndNoteWriteSettingsOperation(..., throwException = true)`) is the signal, and it costs nothing when granted.
- The HandlerThread is created on the first device-path use only. It idles blocked (no wakeups).

### 3.3 `LightProbe` (IO only; diagnostics + automatic verification)

```kotlin
package com.ggumtak.readeraplus.reader

import android.content.Context
import android.provider.Settings as SystemSettings
import java.io.File

/** Read-only look at how this device's light is controlled. IO thread; never on the open path; never writes. */
internal object LightProbe {
    /** Vendor / standard light keys: names we look for in the Settings.System table (a query lists vendor keys too). */
    internal val KEY_RE = Regex("(?i)(cold|warm|front|light|bright|colou?r_?temp)")
    private val SKIP = setOf("notification_light_pulse", "pointer_location")

    /** name → value of every readable Settings.System row whose name looks like a light control. */
    fun lightKeys(c: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            c.contentResolver.query(SystemSettings.System.CONTENT_URI, arrayOf("name", "value"), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val n = cur.getString(0) ?: continue
                    if (n in SKIP || !KEY_RE.containsMatchIn(n)) continue
                    out[n] = cur.getString(1) ?: ""
                }
            }
        } catch (t: Throwable) { /* provider refused: nothing to show */ }
        return out
    }

    /** True when the firmware stores a warm channel (Bigme keys); only then does the UI mention 색온도. */
    fun hasWarm(keys: Map<String, String>): Boolean = keys.keys.any { it.contains("warm", ignoreCase = true) }

    /** LM3630A nodes (Bigme: bus 2 on HiBreak/B6, bus 7 on HiBreak Pro Color) and generic backlight classes. */
    fun coldNode(): File? = node("lm3630a_cold_light")
    fun warmNode(): File? = node("lm3630a_warm_light")

    private fun node(name: String): File? {
        for (bus in 0..9) {
            val f = File("/sys/bus/i2c/devices/$bus-0036/$name")
            try { if (f.exists()) return f } catch (t: Throwable) { return null }
        }
        return null
    }

    /** Current raw value of [f], or -1 when missing / not readable (SELinux usually says no). */
    fun read(f: File?): Int = try { f?.readText()?.trim()?.toInt() ?: -1 } catch (t: Throwable) { -1 }

    /** Lines for 정보 › 조명 진단 (Korean labels, raw values). */
    fun report(c: Context): List<String> = TODO("sketch: the lines of §5.7 — verdict, canWrite, screen_brightness(+mode), lightKeys, nodes, xrz getters")
}
```

`report()` also reads `XrzEinkManagerInternal.getScreenBrightnessLevel()` and `getScreenDarkLevel()` by reflection,
**getters only**. The Comet owner can then see whether that value moves with the front light (F8, §9 step 8).

---

## 4. Reader integration (READER_A: `ReaderActivity`, `ReaderChrome`, `PageView` wiring)

### 4.1 State and the single apply point

```kotlin
// ReaderActivity fields
private var lightVerdict = DeviceLight.VERDICT_UNKNOWN   // read on IO in afterOpen
private var lightReady = false                           // true after the first page (device writes wait for it)
private var lightAsk = ASK_NONE                          // ASK_NONE / ASK_WINDOW / ASK_DEVICE (this session only)
private var lightAskChecked = false                      // looksEink() asked once this session
private var awaitingWritePermission = false
private var launchingOwnActivity = false                 // set right before the reader opens our SettingsActivity
private var lightObserved = false
private val lightObserver = object : ContentObserver(handler) {
    override fun onChange(selfChange: Boolean) = DeviceLight.refresh()
}
private val onExternalLight = Runnable { adoptDeviceLight() }
private val onLightPermissionLost = Runnable { applyBrightness(app.brightness); if (chromeVisible) bindChrome() }

private fun deviceLightOn(): Boolean =
    app.brightnessDevice && lightVerdict != DeviceLight.VERDICT_NONE && !DeviceLight.noPermission

/** The reader's brightness [pos] (slider position 0..1; < 0 = the device's own) through the path in use. */
private fun applyBrightness(pos: Float) {
    if (deviceLightOn()) {
        ReaderWindow.applyBrightness(this, -1f)          // one source of truth: no window override on top
        if (!lightReady) return                          // before the first page: afterOpen applies it
        if (pos < 0f) { DeviceLight.restore(); DeviceLight.refresh() } else DeviceLight.set(LightCurve.out(pos))
    } else {
        ReaderWindow.applyBrightness(this, if (pos < 0f) -1f else LightCurve.out(pos))
    }
}

/** Slider position of the device's own brightness (auto look, drag start). */
private fun systemPos(): Float {
    val o = DeviceLight.deviceOut
    if (!o.isNaN()) return LightCurve.pos(o)
    return try {   // Settings.System has a client cache: no binder call while the value is unchanged
        LightCurve.pos(LightCurve.fraction(SystemSettings.System.getInt(contentResolver, SystemSettings.System.SCREEN_BRIGHTNESS, 128)))
    } catch (t: Throwable) { 0.5f }
}
```

Replace every `ReaderWindow.applyBrightness(this, app.brightness)` / `…(this, -1f)` with `applyBrightness(…)`. Also
replace `ReaderWindow.systemBrightness(this)` with `systemPos()`: in `applyAppSettings`, `bindChrome`,
`brightnessStart`, `onBrightnessAuto` and `setBrightness`.

**Gotcha, already true today** (the sibling spec found it too): `saveApp(a)` sets `appliedApp = a` before
`Settings.saveApp`, so `onAppSettingsSaved` returns early and **does not** call `applyAppSettings`. Every handler below
that saves a brightness field must also call `applyBrightness(...)` and update `page.brightnessSwipe` itself. When the
switch is changed from the settings page (another activity), `onResume → applyAppSettings` picks it up. Add
`a.brightnessDevice` to `viewPart()`.

### 4.2 Lifecycle

```kotlin
// afterOpen() — after the first page (spec rule 2)
DeviceLight.init(this)
DeviceLight.onExternal = onExternalLight
DeviceLight.onNoPermission = onLightPermissionLost
ReaderIo.launch {
    val v = DeviceLight.verdict(this@ReaderActivity)
    handler.post {
        if (isDestroyed) return@post
        lightVerdict = v
        lightReady = true
        applyBrightness(app.brightness)       // the device write happens here, never before the first page
        updateLightObserver()
        if (chromeVisible) bindChrome()
    }
}

// onResume(): applyAppSettings() already calls applyBrightness (a no-op write when the level is unchanged)
updateLightObserver()
if (awaitingWritePermission) {
    awaitingWritePermission = false
    ReaderIo.launch {
        val can = SystemSettings.System.canWrite(this@ReaderActivity)
        handler.post { if (can) enableDeviceLight() else toast("권한이 허용되지 않아 앱 화면 밝기로 조절합니다") }
    }
}

// onPause()
unregisterLightObserver()
if (lightReady && deviceLightOn() && app.brightnessRestore && !launchingOwnActivity && isInteractive()) {
    DeviceLight.restore()   // other apps and our library get the device's own value back
}
launchingOwnActivity = false

// onDestroy(): unregisterLightObserver(); DeviceLight.onExternal = null; DeviceLight.onNoPermission = null
// (a finishing reader was already restored by onPause unless the screen was off: then restore here too)
if (isFinishing && lightReady && deviceLightOn() && app.brightnessRestore) DeviceLight.restore()

private fun isInteractive() = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive

private fun updateLightObserver() {
    val want = inFront && lightReady && deviceLightOn()
    if (want == lightObserved) return
    lightObserved = want
    if (want) contentResolver.registerContentObserver(
        SystemSettings.System.getUriFor(SystemSettings.System.SCREEN_BRIGHTNESS), false, lightObserver)
    else contentResolver.unregisterContentObserver(lightObserver)
}
private fun unregisterLightObserver() { if (lightObserved) { lightObserved = false; contentResolver.unregisterContentObserver(lightObserver) } }
```

- **Screen off** (`onPause` with `!isInteractive`): no restore. The light is off anyway, and on wake the reader is in
  front again. This avoids the light jumping twice per wake.
- **Own settings page** opened from the reader menu: set `launchingOwnActivity = true` right before `startActivity`,
  so the light does not jump. Everything else (library, another app, the dictionary app, Home) restores.
- **Crash while in front:** `pending` stays on disk. `LibraryActivity` calls
  `ReaderIo.launch { DeviceLight.restoreIfStale(applicationContext) }` **after its first list is shown**
  (LIBRARY, one line). A reader opened directly keeps the stored original, because `pending` is true, so
  nothing is re-captured.

### 4.3 The one-time question (verdict state machine)

```
                   drag ends (done=true), verdict UNKNOWN, device path off
                                   │
                        looksEink()? (IO, once per session)
                    no ──► verdict WINDOW (phones: never asked)
                    yes ─► ASK_WINDOW  "전면광 밝기가 바뀌었나요?"  [예] [아니요]
                              예 ──► verdict WINDOW
                              아니요 ─► dialog §5.3 ─► [허용하러 가기] ─► system page ─► back: canWrite?
                                                                     no ─► toast, stay WINDOW path (verdict UNKNOWN)
                                                                     yes ─► brightnessDevice = true, apply now
                                                                            ─► ASK_DEVICE "막대를 움직여 보세요 · 전면광이 바뀌나요?"
                                                                                 예 ──► verdict DEVICE
                                                                                 아니요 ─► restore, brightnessDevice = false,
                                                                                          verdict NONE, dialog §5.4
   auto-confirm (§4.4) answers "예" by itself when the hardware or a vendor key visibly followed.
   A question left unanswered this session counts once; after 3 sessions the reader stops asking (the switch stays).
```

```kotlin
private fun onBrightnessDragDone() {
    if (!lightReady || lightVerdict != DeviceLight.VERDICT_UNKNOWN) return
    if (deviceLightOn()) { setLightAsk(ASK_DEVICE); return }
    if (lightAsk != ASK_NONE || lightAskChecked) return
    lightAskChecked = true
    ReaderIo.launch {
        val c = this@ReaderActivity
        val eink = DeviceLight.looksEink()
        if (!eink) DeviceLight.setVerdict(c, DeviceLight.VERDICT_WINDOW)
        val ask = eink && DeviceLight.asks(c) < 3
        if (ask) DeviceLight.countAsk(c)
        handler.post {
            if (!eink) lightVerdict = DeviceLight.VERDICT_WINDOW else if (ask) setLightAsk(ASK_WINDOW)
        }
    }
}

// ReaderChrome.Actions
override fun onLightAnswer(yes: Boolean) {
    val ask = lightAsk
    setLightAsk(ASK_NONE)
    when {
        ask == ASK_WINDOW && yes -> saveVerdict(DeviceLight.VERDICT_WINDOW)
        ask == ASK_WINDOW -> offerDeviceLight()
        ask == ASK_DEVICE && yes -> saveVerdict(DeviceLight.VERDICT_DEVICE)
        else -> {
            DeviceLight.restore()
            saveApp(app.copy(brightnessDevice = false))
            saveVerdict(DeviceLight.VERDICT_NONE)
            page.brightnessSwipe = false            // a swipe that changes nothing is off (the switch shows why)
            applyBrightness(app.brightness)
            bindChrome()
            showNoLightDialog()
        }
    }
}

private fun saveVerdict(v: Int) {
    lightVerdict = v
    ReaderIo.launch { DeviceLight.setVerdict(this@ReaderActivity, v) }
}

private fun requestWriteSettings() = ReaderIo.launch {
    val can = SystemSettings.System.canWrite(this@ReaderActivity)
    handler.post {
        if (can) { enableDeviceLight(); return@post }
        awaitingWritePermission = true
        try {
            startActivity(Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            try { startActivity(Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS)) } catch (e2: Exception) {
                awaitingWritePermission = false
                showNoPermissionScreenDialog()      // §5.5: the adb one-liner
            }
        }
    }
}

private fun enableDeviceLight() {
    saveApp(app.copy(brightnessDevice = true))
    applyBrightness(app.brightness.let { if (it < 0f) systemPos() else it })   // a visible manual value now
    updateLightObserver()
    if (lightVerdict == DeviceLight.VERDICT_UNKNOWN) setLightAsk(ASK_DEVICE)
    if (!chromeVisible) setChromeVisible(true)
    bindChrome()
}

private fun adoptDeviceLight() {       // the user changed the light in the device's panel while reading
    val o = DeviceLight.deviceOut
    if (o.isNaN() || app.brightness < 0f) return
    saveApp(app.copy(brightness = LightCurve.pos(o)))   // pos→out→level round-trips exactly: no write back (§8 test)
    if (chromeVisible) bindChrome()
}
```

`setLightAsk(k)` stores `lightAsk`, calls `chrome.setLightAsk(k)`, and when `k != ASK_NONE` also
`chrome.setBrightnessOptionsOpen(true)`, so the question is visible the next time the chrome shows. An ask made during
an edge-swipe (chrome hidden) waits for the next chrome show. It never pops over the page.

### 4.4 Automatic confirmation (skips the question when the device tells us)

When `ASK_WINDOW` or `ASK_DEVICE` is set, the reader snapshots on IO: `cold0 = LightProbe.read(coldNode())` and
`keys0 = lightKeys()` (only keys whose name contains `cold`/`warm`/`light`). After each finished drag it waits 400 ms
(`handler.postDelayed` + IO read) and takes the same snapshot again:
- The hardware node is readable and changed → answer **예** automatically.
- A vendor light key changed after **our** `screen_brightness` write (the firmware mirrored it into its own light
  state) → answer **예** automatically.
- Anything else → keep asking. A readable node that did **not** change is not taken as "no": the node might not be
  the one that is lit.

This costs at most two small file reads and one provider query per finished drag, and only while a question is open.

### 4.5 `ReaderChrome` additions (on top of `chrome.md` §2.4)

```kotlin
interface Actions {
    // … chrome.md §2.4 …
    fun onLightAnswer(yes: Boolean)
    fun onLightDevice(on: Boolean)     // the "기기 밝기 직접 조절" switch (on → permission flow if needed; off → restore)
    fun onLightPanel()                 // "기기 조명 설정 열기"
}
fun setBrightnessOptionsOpen(open: Boolean)
/** ASK_NONE / ASK_WINDOW / ASK_DEVICE: the question row at the top of the options panel. */
fun setLightAsk(kind: Int)
/** The device switch row: state + subtitle (§5.2), enabled flag; bound only on change. */
fun setLightDevice(on: Boolean, subtitle: String, enabled: Boolean)
/** Verdict NONE: the slider and the auto button give way to one link "기기 조명 설정에서 조절 ›". */
fun setBrightnessUnavailable(unavailable: Boolean)
/** The "기기 조명 설정 열기" row (shown when a warm channel exists or the verdict is NONE). */
fun setLightPanelRow(visible: Boolean, subtitle: String)
```

The rows are built inside `ReaderChrome` with the style from `chrome.md` §2.1: 15 sp title, 13 sp `Ink.GRAY` subtitle,
row ≥ 56 dp, 16 dp sides and `InkToggle`. This replaces the need for `addBrightnessOption(row)`. If the sibling keeps
that hook, build these rows in `reader/LightRows.kt` and add them through it. Every setter compares against the last
bound value (e-ink: an unchanged row is never redrawn).

`bindChrome()` adds:
```kotlin
chrome.setBrightness(app.brightness, systemPos())
chrome.setBrightnessUnavailable(lightVerdict == DeviceLight.VERDICT_NONE)
chrome.setLightDevice(app.brightnessDevice && !DeviceLight.noPermission, deviceSubtitle(), lightVerdict != DeviceLight.VERDICT_NONE)
chrome.setLightPanelRow(lightVerdict == DeviceLight.VERDICT_NONE || hasWarmLight, panelSubtitle())
```
`hasWarmLight` is set once in `afterOpen`'s IO block via `LightProbe.hasWarm(LightProbe.lightKeys(this))`: a single
provider query, after the first page.

---

## 5. UX (Korean text, layout)

### 5.1 Brightness row and options panel (extends `chrome.md` §5)

```
│ Ⓐ  ━━━━━━━━●───────────────────────   ⌃     │  brightness row 48 dp (chrome.md)
├──────────────────────────────────────────────┤  1 px
│ 전면광 밝기가 바뀌었나요?        예     아니요   │  question row 48 dp (only while asked) — 15 sp; buttons 15 sp medium, each ≥ 56×48 dp
├──────────────────────────────────────────────┤  1 px light (#AAAAAA), inset 16 dp
│ 스와이프로 밝기 조절                      (◯ ) │  chrome.md row
│ 화면 왼쪽 가장자리를 위아래로 밀어 밝기를 바꿉니다  │
├──────────────────────────────────────────────┤
│ 기기 밝기 직접 조절                       (◯ ) │  NEW: 56 dp two-line row
│ 전면광이 안 바뀔 때 켜세요 · 기기 전체 밝기를 바꿉니다 │
├──────────────────────────────────────────────┤
│ 기기 조명 설정 열기                          ›  │  only if warm light exists or verdict NONE
│ 색온도(따뜻한 빛)는 기기 조명에서 바꿉니다         │
└──────────────────────────────────────────────┘
```

Verdict NONE, brightness row:
```
│ ☼  기기 조명 설정에서 조절  ›               ⌄    │  ic_brightness_medium (plain glyph, not a button), 15 sp link, weight 1, 48 dp
```

### 5.2 Strings

| Where | Text |
|---|---|
| Question, window | 전면광 밝기가 바뀌었나요? · [예] [아니요] |
| Question, device (after permission) | 막대를 움직여 보세요. 전면광이 바뀌나요? · [예] [아니요] (two lines allowed: 15 sp + 13 sp gray "막대를 움직여 보세요.") |
| Switch title | 기기 밝기 직접 조절 |
| Subtitle: off | 전면광이 안 바뀔 때 켜세요 · 기기 전체 밝기를 바꿉니다 |
| Subtitle: on, restore on | 기기 전체 밝기를 바꿉니다 · 리더를 나가면 원래대로 |
| Subtitle: on, restore off | 기기 전체 밝기를 바꿉니다 · 나가도 그대로 유지 |
| Subtitle: permission missing or revoked | '시스템 설정 수정' 권한이 필요합니다 · 눌러서 허용 |
| Subtitle: verdict NONE (switch disabled) | 이 기기는 앱이 전면광을 바꿀 수 없습니다 |
| Swipe switch subtitle at NONE (disabled) | 이 기기에서는 밝기 스와이프를 쓸 수 없습니다 |
| Panel row title / subtitle | 기기 조명 설정 열기 / 색온도(따뜻한 빛)는 기기 조명에서 바꿉니다 · (NONE) 밝기와 색온도는 기기 조명에서 조절합니다 |
| Brightness row at NONE | 기기 조명 설정에서 조절 › |
| Toast: permission not granted | 권한이 허용되지 않아 앱 화면 밝기로 조절합니다 |
| Toast: no display settings screen | 화면 위에서 아래로 내려 기기 조명을 조절하세요 |
| Overlay (edge drag) | unchanged: "밝기 40%" (the slider position) |

### 5.3 Dialog before the permission page (`ctx.alert()`, InkDialog, no animation)

**기기 밝기 직접 조절**

> 이 기기의 전면광은 앱 화면 밝기를 따르지 않을 수 있습니다.
> '시스템 설정 수정'을 허용하면 리더가 기기 밝기 설정을 직접 바꿉니다.
>
> · 기기 전체 밝기가 바뀝니다. 다른 앱에서도 같은 밝기가 보일 수 있습니다.
> · 리더를 나가면 원래 밝기로 되돌립니다. (설정에서 바꿀 수 있어요)
> · 다음 화면에서 ReaderaPlus를 찾아 허용을 켠 뒤 돌아오세요.

Buttons: **[취소] [허용하러 가기]**

### 5.4 Dialog when the device path also fails (verdict NONE)

**앱에서 조명을 바꿀 수 없어요**

> 이 기기는 다른 앱이 전면광을 바꾸는 방법을 열어 두지 않았습니다. 원래 밝기로 되돌렸습니다.
> 밝기와 색온도는 화면 위에서 아래로 내려 기기 조명에서 조절해 주세요.

Buttons: **[확인] [기기 설정 열기]** (`ACTION_DISPLAY_SETTINGS`)

### 5.5 Dialog when the firmware hides the "시스템 설정 수정" screen

**권한 화면을 찾을 수 없어요**

> 이 기기에는 '시스템 설정 수정' 화면이 없습니다. PC에 연결해 다음 명령으로 허용할 수 있습니다.
> `adb shell appops set com.ggumtak.readeraplus WRITE_SETTINGS allow`

Buttons: **[닫기] [명령 복사]**

### 5.6 Settings page (SETTINGS, `MainPage` "읽기 설정", right after "스와이프로 밝기 조절")

| Row | Kind | Text |
|---|---|---|
| 기기 밝기 직접 조절 | toggle | subtitles as §5.2. On, when `canWrite` is false: the §5.3 dialog, then the permission page. `SettingsActivity.onResume` re-checks on IO |
| 리더를 나가면 원래 밝기로 | toggle (disabled while the one above is off) | "켜 두면 다른 앱과 서재는 원래 밝기를 씁니다. 끄면 리더에서 바꾼 밝기가 기기 밝기로 남습니다." |
| 밝기 방식 다시 확인 | row | "이 기기에서 어떤 밝기 방식이 되는지 다음 조절 때 다시 묻습니다" → `DeviceLight.setVerdict(UNKNOWN)` |
| 기기 조명 설정 열기 | nav row | "밝기 · 색온도를 기기 설정에서 조절" |

### 5.7 정보 › 조명 진단 (SETTINGS `AboutPage`; for the user's report)

Filled on IO when the page opens. Plain lines, each copyable:
```
e-ink: Bigme xrz · 제조사 Innospace · 모델 COMET · 지문 …(Build.FINGERPRINT)
밝기 방식: 기기 설정 (확인됨) | 앱 화면 (확인됨) | 확인 안 됨 | 없음
시스템 설정 수정 권한: 허용 | 없음
screen_brightness = 102 (수동)
기기 조명 키: ColdValue=90 · WarmValue=40 · screen_brightness_cold=90 · …   (none: "없음")
전면광 노드: /sys/bus/i2c/devices/2-0036/lm3630a_cold_light 읽기 불가 | = 90
xrz getScreenBrightnessLevel = 0 · getScreenDarkLevel = 0 | 없음
```
Also a **[변화 감지 30초]** button (P2). It registers a `ContentObserver` on `Settings.System.CONTENT_URI` with
`notifyForDescendants = true` for 30 s and lists every key that changes ("screen_brightness 102 → 60"). The user
moves the device's own light slider meanwhile. This is the in-app version of the adb `settings list` diff (§9).

---

## 6. Restore policy (decided)

- The device setting is global. The UI says so wherever it can change: the §5.2 subtitle, the §5.3 dialog and the
  §5.6 row.
- **Default: restore on leaving** (`brightnessRestore = true`). This is the same per-app model as phones and ReadEra
  (window override). Other apps and our library keep the user's device brightness. The switch "리더를 나가면 원래
  밝기로" turns it off for users who want one global light.
- No restore on screen-off, and no restore when the reader opens our own settings page (§4.2).
- The user changes the light in the device panel while reading → theirs wins. Nothing is put back, and the slider moves
  to their value (`adoptDeviceLight`).
- Auto (`brightness < 0`, the Ⓐ button) → restore now and stop writing.
- Switch off → restore now.
- The "value to put back" is committed before the first write, repaired after a crash, and ignored after a reinstall
  (`firstInstallTime` stamp).
- `SCREEN_BRIGHTNESS_MODE`: forced to manual only if it was automatic (phones), and put back with the value. With
  restore off, a phone stays in manual mode. That is implied by "나가도 그대로 유지" and needs no extra text (the Comet has
  no light sensor, so it is manual already).

---

## 7. Warm light (색온도)

- The Comet probably has it (F2). On this firmware family it is reachable only through vendor keys (not writable,
  F6), the ioctl (system only) or root sysfs (F3, F9). **No warm slider in this round.**
- It is detected read-only (`LightProbe.hasWarm`, a `warm` key exists). The options panel then shows "기기 조명 설정
  열기 · 색온도(따뜻한 빛)는 기기 조명에서 바꿉니다". Honest and one tap away.
- A warm slider would be enabled only if `LightProbe.warmNode()?.canWrite() == true` (firmware left it 0666 and SELinux
  allows it; not expected). It would be a second 48 dp row "따뜻함" with the same `einkSeekBar`, writing
  `round(p × 255)` on the same light thread, and with the same restore rules. The design is here so nobody redoes it.
  It is not built unless §9 shows a writable node.
- "밤에는 따뜻하게" schedules (fd/eink.md §8) stay parked until a writable warm control exists.

---

## 8. House rules and cost

| Rule | How |
|---|---|
| Nothing before the first page | `DeviceLight.init` only stores a Context. The verdict read, the first device write, the observer and the warm probe all run from `afterOpen()`. In device mode `onCreate` only sets the window override to NONE (a window attribute, as today). |
| Page turn O(1), no allocation | Page turns do no brightness work at all. |
| Drag | Main thread: one volatile float, one CAS, a pooled Message. Writes ≤ 10/s on the light thread. The overlay text keeps its existing 250 ms throttle. |
| No idle redraws / wakeups | The HandlerThread blocks. The observer is registered only while in front with the device path on, and fires only on real changes. No timers. |
| E-ink | Front-light changes do not refresh the panel. The question row, the link and the subtitles are each one partial top-bar update, and only when their value changes. No animation, no ripple, no toast for success. |
| Tap targets | Question buttons ≥ 56×48 dp; rows ≥ 56 dp. |

**Tests (JVM, `test/.../reader/LightCurveTest.kt`):**
- `out`/`pos` are monotonic, with `out(0)=0` and `out(1)=1`.
- `level` stays in 1..255 for any input, including NaN coerced by the caller.
- For all v in 1..255, `level(out(pos(fraction(v)))) == v`. This guarantees `adoptDeviceLight` never writes back.
- `isExternal` returns false for our value, inside the echo window, while queued, or when `ours < 0`, and true
  otherwise.
- The regex in `LightProbe.KEY_RE` matches `ColdValue`, `screen_brightness_warm`, `LastWarmLight` and
  `screen_cool_brightness`, and does not match `font_scale`.
- The verdict reducer (extract `nextVerdict(ask, yes)` as a pure function): all five §4.3 edges.

---

## 9. Device checks for the user (Comet; the HiBreak Pro Color is a useful second Bigme data point)

**Without a PC (new build):**
1. Open a book, show the menu and drag the brightness bar fully left, then fully right. Does the front light change?
   Answer the question row honestly.
2. If not: tap 아니요 → 허용하러 가기 → allow "시스템 설정 수정" → come back and drag again. Does it change now?
3. Open 설정 › 정보 › 조명 진단 and take a screenshot. Tap [변화 감지 30초], pull down the device's own light panel,
   move the **brightness** slider and then the **색온도** slider, come back and take another screenshot.

**With adb** (10 minutes; paste the outputs back):
```sh
adb shell getprop | grep -iE "ro.product|ro.board|ro.hardware|xrz|inno"
adb shell settings get system screen_brightness_mode
adb shell settings get system screen_brightness
# which keys does the device's own light slider change?
adb shell settings list system > s1.txt
#   (move the device's brightness slider clearly, then the 색온도 slider)
adb shell settings list system > s2.txt && diff s1.txt s2.txt
# does the public key drive the light? (what our 기기 밝기 path writes)
adb shell settings put system screen_brightness_mode 0
adb shell settings put system screen_brightness 5      # dimmer?
adb shell settings put system screen_brightness 220    # brighter?
# does a vendor key drive it? (tells us whether an observer applies them — the APP still cannot write these)
adb shell settings put system screen_brightness_cold 10 ; adb shell settings put system ColdValue 10
# hardware nodes and their SELinux labels (can an app read them?)
adb shell 'ls -lZ /sys/bus/i2c/devices/ ; ls -lZ /sys/bus/i2c/devices/*-0036/ 2>/dev/null'
adb shell 'cat /sys/bus/i2c/devices/*-0036/lm3630a_cold_light /sys/bus/i2c/devices/*-0036/lm3630a_warm_light'
adb shell ls -l /dev/lm3630a
# services / lights HAL / what the window override did
adb shell service list | grep -iE "light|xrz|eink|inno"
adb shell dumpsys lights | head -40
adb shell dumpsys display | grep -iE "brightness" | head -20
adb shell dumpsys window | grep -i screenBrightness          # while the reader is open after a drag
adb shell dumpsys xrz_display_policy_service | grep -i ggumtak
# brightness keys (the InnoKey remote changes brightness in e-reader mode)
adb shell input keyevent 220 ; adb shell input keyevent 221  # KEYCODE_BRIGHTNESS_DOWN / UP: does the light step?
# the device light panel's component (so a later build can link straight to it): open that panel, then
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp"
# permission, if the firmware hides the special-access screen
adb shell appops get com.ggumtak.readeraplus WRITE_SETTINGS
adb shell appops set com.ggumtak.readeraplus WRITE_SETTINGS allow
adb logcat -s DeviceLight Eink
```

How the answers change the plan:
| Result | Next step |
|---|---|
| `screen_brightness 5/220` changes the light | Path D works. Ship as designed. Optionally, for the Comet's brand/model string, skip `ASK_WINDOW` (the window is known to fail) and open with the §5.3 offer directly. |
| Only the vendor keys change it | An observer applies them, but only system apps can write them. Look for an exported vendor provider or service (`dumpsys activity providers | grep -i xrz`, the `content://com.xrz.SettingProvider` seen in the plugin) with a light method. Test from adb first (`content call --uri … --method …`). Nothing blind from the app. |
| `lm3630a_*` readable (`-Z` label not `sysfs_*` restricted) | Turn on automatic verification (§4.4). If it is writable too → warm slider (§7). |
| Keyevent 220/221 steps the light | The firmware maps brightness keys to the light. An app cannot inject system keys: only a note for remote/keyboard users. |
| The xrz getter moves with the light | Candidate vendor API (F8). Prototype `setScreenBrightnessLevel` behind 조명 진단 with read-back-and-restore, **never** in the reader first. |
| Nothing moves the light | Verdict NONE is the truth. Ship the honest link. That is still better than a dead slider. |

---

## 10. Contract requests (frozen files)

1. `AndroidManifest.xml`:
   ```xml
   <manifest xmlns:android="http://schemas.android.com/apk/res/android"
       xmlns:tools="http://schemas.android.com/tools">
       <!-- "기기 밝기 직접 조절" only (opt-in): writes Settings.System screen_brightness for e-ink front lights that
            ignore the window brightness. Special access, granted by the user in "시스템 설정 수정". -->
       <uses-permission android:name="android.permission.WRITE_SETTINGS" tools:ignore="ProtectedPermissions" />
   ```
   The "Modify system settings" screen lists only apps that declare it.
2. `settings/ReaderSettings.kt` `AppSettings`:
   ```kotlin
   /** "기기 밝기 직접 조절": write the device brightness setting (WRITE_SETTINGS) instead of the window override. */
   val brightnessDevice: Boolean = false,
   /** "리더를 나가면 원래 밝기로": put the device brightness back when the reader leaves (only with brightnessDevice). */
   val brightnessRestore: Boolean = true,
   /** Slider POSITION 0..1 (light = position², LightCurve), or -1 = the device's own. */
   val brightness: Float = -1f,
   ```
3. `settings/Settings.kt`: `a.brightnessDevice`, `a.brightnessRestore` (save/load, defaults).
4. `data/SettingsJson.kt`: map both, typed. Backup carries the choice and not the permission, which shows as
   "권한이 필요합니다" after a restore.
5. `docs/ARCHITECTURE.md` reader section: "Brightness: window override, or opt-in device setting (DeviceLight), verdict
   per firmware".
6. If the reinstall-restore work (scroll SPEC) adds Android Auto Backup rules in `res/xml`, exclude
   `sharedpref/reader_light.xml`. The install stamp already guards it, but excluding it is cleaner.
7. `chrome.md` §2.4 API: add `setBrightnessOptionsOpen`, `setLightAsk`, `setLightDevice`, `setBrightnessUnavailable`
   and `setLightPanelRow`, plus `Actions.onLightAnswer/onLightDevice/onLightPanel` (§4.5). All are within READER_A, so
   no cross-owner contract is needed, only the sibling spec's agreement.

---

## 11. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Neither public path drives the Comet's light** (the most likely case if it is a Bigme build: F4/F5). | The question → verdict NONE → honest link, edge-swipe off, device value put back. §9 tells us for sure before more is built. |
| R2 | `screen_brightness` works but maps oddly: a vendor 36-step quantisation, a 0–100 range (Tolino B300 uses 0–100), or a floor that is still lit. | The echo window ignores the vendor echoing back. Top-end saturation is harmless. §9's 5/220 test shows the range. If needed, add a `levelMax` learned from the device's own max value (read `screen_brightness` after the user sets the device slider to max). |
| R3 | Global side effect: other apps see our brightness if restore is off, or after a crash. | Restore on by default. Pending restore is committed before the first write. `restoreIfStale` runs at library start. Install stamp. |
| R4 | The firmware hides the special-access screen. | §5.5 adb one-liner. Also `ACTION_MANAGE_WRITE_SETTINGS` without the package uri. |
| R5 | The user adjusts the light in the device panel while reading, and restore would overwrite it. | Observer → `isExternal` → pending cleared and the value adopted. |
| R6 | A tempting xrz `setScreenBrightnessLevel` / `setBrightnessLevelForPackage` is really image brightness and persists globally. | Never called. Getter-only diagnostics first (§9). |
| R7 | The question annoys phone users. | Asked only when `looksEink()`. Phones get `WINDOW` silently. At most 3 unanswered sessions. It never pops over the page. |
| R8 | The p² curve shifts existing saved values (0.5 → light 0.25). | One-time, and users re-adjust. Alternative: migrate once with `pos = sqrt(old)` (a raw-pref flag), cheap if wanted. |
| R9 | Settings writes during drags wear flash or cost system_server work. | ≤ 10/s. SettingsState batches file writes (~200 ms). No writes when the level is unchanged. |
| R10 | Brightness jumps when leaving to another app (restore) and back (re-apply). | This is the per-app model (phones do the same). No jump on screen-off or when opening our own settings. The user can turn restore off. |
| R11 | Revoked permission mid-session. | `SecurityException` → `noPermission` → window path at once, and the subtitle says "권한이 필요합니다 · 눌러서 허용". |

---

## 12. Consistency with the other specs

- `ui/chrome.md` §5: the brightness row is never hidden, the ⌄/⌃ button opens the options panel, and the swipe row is
  unchanged. This spec adds the question row, the device switch, the panel row and the NONE link state. The options
  panel still closes when the chrome hides, except that `setLightAsk` reopens it on the next show while a question is
  pending.
- `ui/audit.md` A3: same `WRITE_SETTINGS` + manual-mode design, refined. The window override is set to NONE only in
  device mode (not on "e-ink" by guess). Level 0 is never written (the framework treats 0 as off/invalid), so the
  "light off" A3 wanted is not reachable through this path. A4 (pin) is independent.
- Scroll designs (A/B/C): brightness is mode-independent. The left-edge strip rule is unchanged (the direction is
  decided at first movement, and it wins only with `brightnessSwipe`). With verdict NONE the reader sets
  `page.brightnessSwipe = false`, so the strip scrolls and turns pages like the rest of the page.
- The pinned-chrome removal (A4) matters here too: opening the options panel changes the top bar height, which no
  longer relays out the page.

---

## Sources

- AOSP `SettingsProvider.java` and `Settings.java`, android14-release, via the GitHub mirror
  `aosp-mirror/platform_frameworks_base` (read locally: `scratchpad/aosp/SettingsProvider_android14-release.java`).
- KOReader Android launcher light controllers (`scratchpad/android-luajit-launcher/…/device/lights/`):
  https://github.com/koreader/android-luajit-launcher
- Bigme Light Control KOReader plugin (cloned: `scratchpad/bigmelight/`): https://github.com/right9code/bigmelight.koplugin
- XDA "[Bigme Hibreak] Root (Mediatek 6765)" (search snippets only; the site is blocked by the proxy):
  https://xdaforums.com/t/bigme-hibreak-root-mediatek-6765.4697830/ (pages 4, 9)
- MobileRead thread on `screen_cool_brightness` / `screen_warm_brightness` (snippet): https://www.mobileread.com/forums/showthread.php?p=4134854
- Parkablogs HiBreak review (front light has no auto brightness): https://www.parkablogs.com/content/bigme-hibreak-android-e-ink-phone
- Local notes: `research_research_device.md`, `fd/eink.md` §8/§11, `gap/hpc_CLAUDE.md` §3/§7, `inksdk/docs/bigme-sdk-reverse-engineered.md`.
