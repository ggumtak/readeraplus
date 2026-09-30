package com.ggumtak.readeraplus.render

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.View
import com.ggumtak.readeraplus.settings.EINK_MODE_SYSTEM
import com.ggumtak.readeraplus.settings.EINK_REFRESH_AUTO
import com.ggumtak.readeraplus.settings.EINK_REFRESH_CLEAN
import com.ggumtak.readeraplus.settings.EINK_REFRESH_FLASH
import com.ggumtak.readeraplus.settings.EINK_REFRESH_GC16
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * The device's own ghost clearing (Bigme `XrzEinkManagerInternal`, T1-3e), read-only: [autoClean] =
 * `isAutoCleanCheckEnable()`, [everyPages] = `getCleanFrequency()` (≤ 0 when unknown). Shown as "기기 자체 잔상 제거:
 * 켜짐 · 10쪽마다"; with an app cadence on too the settings page warns that both would flash.
 */
data class DeviceCleanInfo(val autoClean: Boolean, val everyPages: Int)

/**
 * E-ink helpers. Vendor hooks are discovered once by reflection (cached) and every call is guarded:
 * nothing here may ever throw into the caller.
 *
 * Order: Bigme "xrz" framework (the Comet is very likely a Bigme ODM design) → Rockchip `EinkManager`
 * → Onyx `View.refreshScreen` → NTX `View.postInvalidateDelayed(…, mode)`. When no hook succeeds the
 * universal fallback flashes one full frame over the view and then redraws it.
 *
 * **What a vendor refresh call can tell us** (T1-3): nothing about the panel itself. Bigme's
 * `XrzEinkManager.forceGlobalRefresh(int)` is `static void` (reflection dump of the firmware), and so are the other
 * hooks as far as known, so a call that returns normally only means the request was *sent*: a firmware that ignores
 * the mode leaves the ghosts on screen and nothing reports it. What is detected: whether the class and method exist
 * ([probe]), the firmware's own mode numbers (`EinkRefreshMode.EINK_GC16_MODE` / `EINK_CLEAN_MODE`, else 4 / 176),
 * an exception from the call (a missing service, a native failure: the next step of the chain, or the flash), and a
 * `boolean` result where a firmware declares one (false = failed). Whether the ghosts really went is settled by the
 * user with the settings page's [테스트] and the method they pick ([fullRefresh] with a method, no chain).
 */
object Eink {
    private const val TAG = "Eink"

    /** Bigme xrz refresh modes. */
    const val XRZ_GC16 = 4
    const val XRZ_CLEAN = 176
    const val XRZ_HD = 177
    const val XRZ_DEFAULT = 178
    const val XRZ_FAST = 179
    const val XRZ_REGAL = 180

    private const val XRZ_MANAGER = "xrz.framework.manager.XrzEinkManager"
    private const val XRZ_MODES = "xrz.framework.manager.EinkRefreshMode"
    private const val XRZ_INTERNAL = "xrz.framework.manager.XrzEinkManagerInternal"

    /** NTX / Onyx waveform: GC16 with the "full update" flag. */
    private const val WAVE_GC16_FULL = 2 or 32
    /** How long the flash frame stays up by default (≈ 2 frames + panel latency; AppSettings.einkFlashMs). */
    private const val FLASH_MS = 100

    /** Diagnostics: always use the flash even when a vendor hook exists. */
    @Volatile var flashOnly: Boolean = false

    /**
     * True when the last refresh asked for a device method (기기 GC16 / 기기 잔상 제거) that is missing here or failed
     * detectably (threw, returned false), so the flash stood in; 자동 and 검은 화면 깜빡임 leave it false (the flash is
     * part of those). Set on the main thread once the call ran (two frames after [fullRefresh]). Diagnostics for the
     * settings page's [테스트]: "기기 방식을 쓸 수 없어 화면 깜빡임으로 대신했습니다".
     */
    @Volatile internal var lastRefreshFellBack: Boolean = false
        private set

    private val lock = Any()
    @Volatile private var probed = false
    private var vendor: String? = null

    /** XrzEinkManager instance: created lazily on the main thread (its constructor may need a Looper). */
    @Volatile private var xrz: Any? = null
    @Volatile private var xrzCtor: Constructor<*>? = null
    @Volatile private var xrzCtorTried = false
    private var xrzByView: Method? = null
    private var xrzGlobal: Method? = null
    /** The firmware's own numbers for GC16 / CLEAN (`EinkRefreshMode`), else the known Bigme values. */
    @Volatile private var xrzGc16 = XRZ_GC16
    @Volatile private var xrzClean = XRZ_CLEAN
    private var rkManager: Any? = null
    private var rkFullFrame: Method? = null
    private var onyxRefresh: Method? = null
    private var ntxInvalidate: Method? = null

    private val flashing: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    /** [configure]d method / flash length for [fullRefresh] (view). */
    @Volatile private var refreshMethod: Int = EINK_REFRESH_AUTO
    @Volatile private var refreshFlashMs: Int = FLASH_MS

    private val cleanLock = Any()
    @Volatile private var cleanInfoRead = false
    @Volatile private var cleanInfo: DeviceCleanInfo? = null

    /**
     * R2 (T1-3a): the method (AppSettings.einkRefreshMethod, EINK_REFRESH_*) and flash length
     * (AppSettings.einkFlashMs) that [fullRefresh] (view) uses from now on. The reader calls it from
     * applyAppSettings, so no setting is read per refresh. Any thread.
     */
    fun configure(method: Int, flashMs: Int) {
        refreshMethod = method
        refreshFlashMs = flashMs
    }

    /**
     * R2 (T1-3a): one full refresh of [view] by exactly [method], no chain: EINK_REFRESH_AUTO = the vendor hooks in
     * [autoRefresh]'s order with the flash as the last resort (today's behaviour), EINK_REFRESH_GC16 /
     * EINK_REFRESH_CLEAN = Bigme xrz `forceGlobalRefresh(GC16 / CLEAN)`, EINK_REFRESH_FLASH = one flash frame shown
     * for [flashMs] (clamped to 50..1000 ms). A device method that is missing here or fails detectably (see the class
     * KDoc) falls back to the flash, so a stale or restored choice still clears the screen; [lastRefreshFellBack] tells.
     * Used by the reader's cadence (through [fullRefresh] (view)) and by the settings page's [테스트] flow. Main
     * thread (posts itself otherwise); never throws.
     */
    fun fullRefresh(view: View, method: Int, flashMs: Int) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                view.post { fullRefresh(view, method, flashMs) }
                return
            }
            probe(view.context)
            val ms = RefreshCalls.flashLength(flashMs)
            val m = if (flashOnly) EINK_REFRESH_FLASH else method
            val device = m == EINK_REFRESH_GC16 || m == EINK_REFRESH_CLEAN
            if (m == EINK_REFRESH_FLASH || !hasPathFor(m)) {
                lastRefreshFellBack = device
                flash(view, ms)
                return
            }
            // Let the pending content reach the screen first (two frames), then ask for the full refresh.
            view.invalidate()
            view.postOnAnimation {
                view.postOnAnimation {
                    try {
                        val failed = vendorRefresh(view, m) == RefreshCalls.FAILED
                        lastRefreshFellBack = failed && device
                        if (failed) flash(view, ms)
                    } catch (t: Throwable) {
                        Log.w(TAG, "vendor refresh failed", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fullRefresh failed", t)
        }
    }

    /**
     * R2: true when the Bigme xrz global refresh is callable here, i.e. the "기기 GC16" / "기기 잔상 제거 (CLEAN)"
     * choices do something. Probes once by reflection: the first call belongs off the main thread (the settings page
     * reads it on IO with [deviceCleanInfo]).
     */
    fun hasXrzRefresh(): Boolean {
        try {
            probe(RenderContext.app)
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed", t)
        }
        val m = xrzGlobal ?: return false
        return Modifier.isStatic(m.modifiers) || xrzCtor != null
    }

    /**
     * R2 (T1-3e): the firmware's own ghost-clearing state, read once by reflection from `XrzEinkManagerInternal`
     * (`isAutoCleanCheckEnable()`, `getCleanFrequency()`) and cached, a null answer too; null when the device has no
     * such API. Only these two getters are called: the vendor settings (persistent, system-wide) are never written.
     * Blocking the first time (the getters may ask a system service): call on IO. Owner: RENDER.
     */
    fun deviceCleanInfo(): DeviceCleanInfo? {
        if (cleanInfoRead) return cleanInfo
        synchronized(cleanLock) {
            if (!cleanInfoRead) {
                cleanInfo = try {
                    readCleanInfo(Class.forName(XRZ_INTERNAL))
                } catch (t: Throwable) {
                    null // not a Bigme firmware
                }
                cleanInfoRead = true
            }
            return cleanInfo
        }
    }

    /**
     * [DeviceCleanInfo] from the static getters of [cls] (`XrzEinkManagerInternal` or a test double): null without
     * `isAutoCleanCheckEnable()`; `everyPages` -1 when `getCleanFrequency()` is missing or fails.
     */
    internal fun readCleanInfo(cls: Class<*>): DeviceCleanInfo? {
        val auto = staticGetter(cls, "isAutoCleanCheckEnable") as? Boolean ?: return null
        val every = (staticGetter(cls, "getCleanFrequency") as? Number)?.toInt() ?: -1
        return DeviceCleanInfo(auto, every)
    }

    /** Result of the public static no-argument method [name] of [cls], or null (missing, not static, threw). */
    private fun staticGetter(cls: Class<*>, name: String): Any? = try {
        val m = cls.getMethod(name)
        if (Modifier.isStatic(m.modifiers)) m.invoke(null) else null
    } catch (t: Throwable) {
        null
    }

    /**
     * Forces a full panel refresh on [view] by the [configure]d method and flash length (by default the automatic
     * chain with a 100 ms flash, today's behaviour). See [fullRefresh] (view, method, flashMs).
     */
    fun fullRefresh(view: View) {
        fullRefresh(view, refreshMethod, refreshFlashMs)
    }

    private fun hasVendorHook(): Boolean =
        xrzGlobal != null || rkFullFrame != null || onyxRefresh != null || ntxInvalidate != null

    /** A device path exists for [method] (GC16 / CLEAN: the xrz global refresh; anything else: any vendor hook). */
    private fun hasPathFor(method: Int): Boolean = when (method) {
        EINK_REFRESH_GC16, EINK_REFRESH_CLEAN -> xrzGlobal != null
        else -> hasVendorHook()
    }

    /** Old behaviour: always the HD waveform on the page view. Prefer the overload with the user's mode. */
    fun prepareReaderView(view: View) {
        prepareReaderView(view, XRZ_HD)
    }

    /**
     * Applies the vendor refresh [mode] (AppSettings.einkMode) to the page view. [EINK_MODE_SYSTEM] does nothing:
     * the device's own per-app e-ink setting stays in charge, as for any other reader app. Any other value is a
     * Bigme xrz mode (177 HD, 180 REGAL, 179 FAST, 178 NORMAL) set with `setRefreshModeByView` when the firmware has
     * it (no-op elsewhere). A mode set on a view stays on it: going back to 0 takes effect with the next page view
     * (the book opened again). Never throws.
     */
    fun prepareReaderView(view: View, mode: Int) {
        if (mode == EINK_MODE_SYSTEM) return
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                view.post { prepareReaderView(view, mode) }
                return
            }
            probe(view.context)
            val m = xrzByView ?: return
            val target = if (Modifier.isStatic(m.modifiers)) null else (xrzInstance(view.context) ?: return)
            m.invoke(target, view, mode)
        } catch (t: Throwable) {
            Log.w(TAG, "prepareReaderView failed", t)
        }
    }

    /**
     * True when the firmware lets the app pick the page view's refresh mode (Bigme xrz `setRefreshModeByView`), i.e.
     * a non-system AppSettings.einkMode does something on this device. Probes once (reflection): call off the main
     * thread the first time.
     */
    fun supportsViewMode(): Boolean {
        try {
            probe(RenderContext.app)
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed", t)
        }
        val m = xrzByView ?: return false
        return Modifier.isStatic(m.modifiers) || xrzCtor != null
    }

    /** The XrzEinkManager instance, constructing it on first use. Main thread only; null if unavailable. */
    private fun xrzInstance(context: Context?): Any? {
        xrz?.let { return it }
        if (xrzCtorTried || Looper.myLooper() != Looper.getMainLooper()) return null
        val ctor = xrzCtor ?: return null
        val ctx = context?.applicationContext ?: context ?: RenderContext.app ?: return null
        synchronized(lock) {
            if (xrzCtorTried) return xrz
            xrzCtorTried = true
            xrz = try {
                ctor.newInstance(ctx)
            } catch (t: Throwable) {
                Log.w(TAG, "XrzEinkManager(Context) failed", t)
                null
            }
            return xrz
        }
    }

    /** Detected vendor e-ink API ("Bigme xrz", "Rockchip", "Onyx", "NTX") or null. */
    fun vendorName(): String? {
        try {
            probe(RenderContext.app)
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed", t)
        }
        return vendor
    }

    // ---------------------------------------------------------------------------------------------

    /** One device refresh by [method] (GC16 / CLEAN exactly; anything else [autoRefresh]): a [RefreshCalls] outcome. */
    private fun vendorRefresh(view: View, method: Int): Int = when (method) {
        EINK_REFRESH_GC16 -> xrzRefresh(view, xrzGc16)
        EINK_REFRESH_CLEAN -> xrzRefresh(view, xrzClean)
        else -> autoRefresh(view)
    }

    /**
     * 자동: the vendor hooks in this order, stopping at the first that did not fail detectably — Bigme GC16, Bigme
     * CLEAN, Rockchip `sendOneFullFrame`, Onyx, NTX. A call that was only *sent* ends the chain too: a flash after a
     * refresh that did work would flash twice. The Comet's [테스트] result sets this order (spec E, "Refresh test").
     */
    private fun autoRefresh(view: View): Int {
        if (xrzGlobal != null) {
            val gc16 = xrzRefresh(view, xrzGc16)
            if (gc16 != RefreshCalls.FAILED) return gc16
            val clean = xrzRefresh(view, xrzClean)
            if (clean != RefreshCalls.FAILED) return clean
        }
        val rk = rkManager
        val rkm = rkFullFrame
        if (rk != null && rkm != null) {
            val r = tryInvoke(rkm, rk)
            if (r != RefreshCalls.FAILED) return r
        }
        val w = view.width
        val h = view.height
        if (w > 0 && h > 0) {
            onyxRefresh?.let {
                val r = tryInvoke(it, view, 0, 0, w, h, WAVE_GC16_FULL)
                if (r != RefreshCalls.FAILED) return r
            }
            ntxInvalidate?.let {
                val r = tryInvoke(it, view, 0L, 0, 0, w, h, WAVE_GC16_FULL)
                if (r != RefreshCalls.FAILED) return r
            }
        }
        return RefreshCalls.FAILED
    }

    /** Bigme `forceGlobalRefresh([mode])` (static, or on the instance built on the main thread). */
    private fun xrzRefresh(view: View, mode: Int): Int {
        val m = xrzGlobal ?: return RefreshCalls.FAILED
        val static = Modifier.isStatic(m.modifiers)
        val target = if (static) null else (xrzInstance(view.context) ?: return RefreshCalls.FAILED)
        return tryInvoke(m, target, mode)
    }

    private fun tryInvoke(m: Method, target: Any?, vararg args: Any): Int = try {
        RefreshCalls.outcome(m.returnType, m.invoke(target, *args))
    } catch (t: Throwable) {
        Log.w(TAG, "vendor call ${m.name} failed", t)
        RefreshCalls.FAILED
    }

    /**
     * Universal fallback: one full frame for [ms], then the real content again. The frame is black over a light
     * background and white over a dark one (night mode, a dark [ColorDrawable] background such as the reader's
     * root): a partial-update waveform only drives pixels that change, so a black frame over a black page would leave
     * the background's ghosts untouched.
     */
    private fun flash(view: View, ms: Long) {
        if (view.width <= 0 || view.height <= 0 || !view.isAttachedToWindow) {
            view.invalidate()
            return
        }
        if (!flashing.add(view)) return
        val bg = (view.background as? ColorDrawable)?.color
        val frame = ColorDrawable(if (bg != null && RefreshCalls.isDark(bg)) Color.WHITE else Color.BLACK)
        frame.setBounds(0, 0, view.width, view.height)
        val overlay = view.overlay
        overlay.add(frame)
        view.invalidate()
        view.postDelayed({
            try {
                overlay.remove(frame)
            } catch (t: Throwable) {
                Log.w(TAG, "flash cleanup failed", t)
            }
            flashing.remove(view)
            view.invalidate()
        }, ms)
    }

    private fun probe(context: Context?) {
        if (probed) return
        synchronized(lock) {
            if (probed) return
            val ctx = context?.applicationContext ?: context ?: RenderContext.app
            // Without a context the Bigme constructor and the Rockchip service can't be probed yet: retry later.
            if (ctx == null) return
            probeBigme(ctx)
            if (vendor == null) probeRockchip(ctx)
            if (vendor == null) probeOnyx()
            if (vendor == null) probeNtx()
            probed = true
            Log.i(TAG, "e-ink vendor: ${vendor ?: "none"} (${Build.MANUFACTURER} ${Build.MODEL})")
        }
    }

    private fun probeBigme(ctx: Context) {
        val cls = try {
            Class.forName(XRZ_MANAGER)
        } catch (t: Throwable) {
            return
        }
        try {
            // Only look the API up here (any thread); the instance is created on the main thread when first used.
            xrzCtor = try {
                cls.getConstructor(Context::class.java)
            } catch (t: Throwable) {
                null
            }
            for (m in cls.methods) {
                val p = m.parameterTypes
                when (m.name) {
                    "setRefreshModeByView" ->
                        if (p.size == 2 && View::class.java.isAssignableFrom(p[0]) && isInt(p[1])) xrzByView = m
                    "forceGlobalRefresh" ->
                        if (p.size == 1 && isInt(p[0])) xrzGlobal = m
                }
            }
            val usable = { m: Method? -> m != null && (Modifier.isStatic(m.modifiers) || xrzCtor != null) }
            if (usable(xrzGlobal) || usable(xrzByView)) vendor = "Bigme xrz"
            val modes = try {
                Class.forName(XRZ_MODES)
            } catch (t: Throwable) {
                null
            }
            xrzGc16 = RefreshCalls.modeConstant(modes, "EINK_GC16_MODE", XRZ_GC16)
            xrzClean = RefreshCalls.modeConstant(modes, "EINK_CLEAN_MODE", XRZ_CLEAN)
            Log.i(TAG, "xrz forceGlobalRefresh: ${xrzGlobal?.returnType?.name ?: "none"}, GC16 $xrzGc16, CLEAN $xrzClean")
            if (Looper.myLooper() == Looper.getMainLooper()) xrzInstance(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "xrz probe failed", t)
        }
    }

    private fun probeRockchip(ctx: Context) {
        try {
            val mgr = ctx.getSystemService("eink") ?: return
            val m = try {
                mgr.javaClass.getMethod("sendOneFullFrame")
            } catch (e: NoSuchMethodException) {
                mgr.javaClass.getDeclaredMethod("sendOneFullFrame").apply { isAccessible = true }
            }
            rkManager = mgr
            rkFullFrame = m
            vendor = "Rockchip"
        } catch (t: Throwable) {
            // not a Rockchip e-ink firmware
        }
    }

    private fun probeOnyx() {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        if (!brand.contains("onyx")) return
        try {
            val i = Integer.TYPE
            onyxRefresh = View::class.java.getMethod("refreshScreen", i, i, i, i, i)
            vendor = "Onyx"
        } catch (t: Throwable) {
            // older/newer Onyx firmware without the hook
        }
    }

    private fun probeNtx() {
        try {
            val i = Integer.TYPE
            ntxInvalidate = View::class.java.getMethod("postInvalidateDelayed", java.lang.Long.TYPE, i, i, i, i, i)
            vendor = "NTX"
        } catch (t: Throwable) {
            // stock Android: no 6-argument variant
        }
    }

    private fun isInt(c: Class<*>): Boolean = c == Integer.TYPE || c == Integer::class.java
}

/** Pure rules of [Eink]'s refresh calls (unit-tested). */
internal object RefreshCalls {
    /** The call threw, or returned false: try the next step / the flash. */
    const val FAILED = 0
    /** The call returned true: the firmware says it refreshed. */
    const val DONE = 1
    /** The call returned normally without a verdict (void or a non-boolean result): sent, unconfirmed. */
    const val SENT = 2

    const val MIN_FLASH_MS = 50
    const val MAX_FLASH_MS = 1000

    /** What a vendor method returning [returnType] said with [result] (see [Eink]'s KDoc). */
    fun outcome(returnType: Class<*>, result: Any?): Int =
        if (returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java) {
            if (result == true) DONE else FAILED
        } else {
            SENT
        }

    /** Flash length for AppSettings.einkFlashMs (100 / 200 / 350), clamped: too short a frame is merged away. */
    fun flashLength(ms: Int): Long = ms.coerceIn(MIN_FLASH_MS, MAX_FLASH_MS).toLong()

    /** True for a mostly opaque colour darker than mid grey (Rec. 601 luma): the flash frame is then white. */
    fun isDark(color: Int): Boolean {
        if ((color ushr 24) < 0x80) return false
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return 299 * r + 587 * g + 114 * b < 128 * 1000
    }

    /**
     * The positive static int [name] of the firmware's mode table [modes] (`EinkRefreshMode`), else [fallback] (no
     * table, no such constant, not an int, not positive).
     */
    fun modeConstant(modes: Class<*>?, name: String, fallback: Int): Int {
        if (modes == null) return fallback
        return try {
            val f = modes.getField(name)
            if (!Modifier.isStatic(f.modifiers) || f.type != Integer.TYPE) return fallback
            f.getInt(null).takeIf { it > 0 } ?: fallback
        } catch (t: Throwable) {
            fallback
        }
    }
}
