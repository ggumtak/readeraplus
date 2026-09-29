package com.ggumtak.readeraplus.render

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.View
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * E-ink helpers. Vendor hooks are discovered once by reflection (cached) and every call is guarded:
 * nothing here may ever throw into the caller.
 *
 * Order: Bigme "xrz" framework (the Comet is very likely a Bigme ODM design) → Rockchip `EinkManager`
 * → Onyx `View.refreshScreen` → NTX `View.postInvalidateDelayed(…, mode)`. When no hook succeeds the
 * universal fallback flashes one full black frame over the view and then redraws it.
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

    /** NTX / Onyx waveform: GC16 with the "full update" flag. */
    private const val WAVE_GC16_FULL = 2 or 32
    /** How long the black frame stays up (≈ 2 frames + panel latency). */
    private const val FLASH_MS = 100L

    /** Diagnostics: always use the black-frame flash even when a vendor hook exists. */
    @Volatile var flashOnly: Boolean = false

    private val lock = Any()
    @Volatile private var probed = false
    private var vendor: String? = null

    /** XrzEinkManager instance: created lazily on the main thread (its constructor may need a Looper). */
    @Volatile private var xrz: Any? = null
    @Volatile private var xrzCtor: Constructor<*>? = null
    @Volatile private var xrzCtorTried = false
    private var xrzByView: Method? = null
    private var xrzGlobal: Method? = null
    private var rkManager: Any? = null
    private var rkFullFrame: Method? = null
    private var onyxRefresh: Method? = null
    private var ntxInvalidate: Method? = null

    private val flashing: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    /** Forces a full panel refresh on [view] (vendor hook, else a brief black frame then redraw). */
    fun fullRefresh(view: View) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                view.post { fullRefresh(view) }
                return
            }
            probe(view.context)
            if (flashOnly || !hasVendorHook()) {
                flash(view)
                return
            }
            // Let the pending content reach the screen first (two frames), then ask for the full refresh.
            view.invalidate()
            view.postOnAnimation {
                view.postOnAnimation {
                    try {
                        if (!vendorRefresh(view)) flash(view)
                    } catch (t: Throwable) {
                        Log.w(TAG, "vendor refresh failed", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fullRefresh failed", t)
        }
    }

    private fun hasVendorHook(): Boolean =
        xrzGlobal != null || rkFullFrame != null || onyxRefresh != null || ntxInvalidate != null

    /** Asks the vendor e-ink framework for a high-quality refresh mode on the page view (no-op if unsupported). */
    fun prepareReaderView(view: View) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                view.post { prepareReaderView(view) }
                return
            }
            probe(view.context)
            val m = xrzByView ?: return
            val target = if (Modifier.isStatic(m.modifiers)) null else (xrzInstance(view.context) ?: return)
            m.invoke(target, view, XRZ_HD)
        } catch (t: Throwable) {
            Log.w(TAG, "prepareReaderView failed", t)
        }
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

    private fun vendorRefresh(view: View): Boolean {
        xrzGlobal?.let { m ->
            val static = Modifier.isStatic(m.modifiers)
            val target = if (static) null else xrzInstance(view.context)
            if (target != null || static) {
                if (tryInvoke(m, target, XRZ_GC16) || tryInvoke(m, target, XRZ_CLEAN)) return true
            }
        }
        val rk = rkManager
        val rkm = rkFullFrame
        if (rk != null && rkm != null && tryInvoke(rkm, rk)) return true
        val w = view.width
        val h = view.height
        if (w > 0 && h > 0) {
            onyxRefresh?.let { if (tryInvoke(it, view, 0, 0, w, h, WAVE_GC16_FULL)) return true }
            ntxInvalidate?.let { if (tryInvoke(it, view, 0L, 0, 0, w, h, WAVE_GC16_FULL)) return true }
        }
        return false
    }

    private fun tryInvoke(m: Method, target: Any?, vararg args: Any): Boolean = try {
        m.invoke(target, *args)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "vendor call ${m.name} failed", t)
        false
    }

    /** Universal fallback: one full black frame, then the real content again. */
    private fun flash(view: View) {
        if (view.width <= 0 || view.height <= 0 || !view.isAttachedToWindow) {
            view.invalidate()
            return
        }
        if (!flashing.add(view)) return
        val black = ColorDrawable(Color.BLACK)
        black.setBounds(0, 0, view.width, view.height)
        val overlay = view.overlay
        overlay.add(black)
        view.invalidate()
        view.postDelayed({
            try {
                overlay.remove(black)
            } catch (t: Throwable) {
                Log.w(TAG, "flash cleanup failed", t)
            }
            flashing.remove(view)
            view.invalidate()
        }, FLASH_MS)
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
            Class.forName("xrz.framework.manager.XrzEinkManager")
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
