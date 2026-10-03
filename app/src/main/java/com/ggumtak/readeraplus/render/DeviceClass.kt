package com.ggumtak.readeraplus.render

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ggumtak.readeraplus.settings.Settings
import java.util.Locale

object DeviceClass {
    const val PREF_KEY = "deviceClass"
    fun einkByBuild(manufacturer: String, brand: String, model: String): Boolean {
        val maker = (manufacturer + " " + brand).lowercase(Locale.ROOT)
        val m = model.lowercase(Locale.ROOT)
        val names = arrayOf("bigme", "boox", "onyx", "innospace", "crema", "meebook", "boyue", "likebook", "pocketbook", "tolino", "kobo", "hyread", "inkpalm", "moaan", "ratta")
        if (names.any { maker.contains(it) || m.contains(it) }) return true
        return maker.contains("hisense") && Regex("(?:^|[^a-z0-9])a[579](?:$|[^0-9])").containsMatchIn(m)
    }
    fun stamp(manufacturer: String, model: String, fingerprint: String): String = "$manufacturer/$model/${fingerprint.hashCode()}"
    fun cached(context: Context): Boolean? = try {
        val mark = stamp(Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT)
        fromCache(Settings.raw().getString(PREF_KEY, ""), mark,
            einkByBuild(Build.MANUFACTURER, Build.BRAND, Build.MODEL))
    } catch (_: RuntimeException) { null }

    internal fun fromCache(stored: String?, mark: String, heuristic: Boolean): Boolean? = when (stored) {
        "eink|$mark" -> true
        "lcd|$mark" -> false
        else -> if (heuristic) true else null
    }
    internal fun detected(vendor: String?, manufacturer: String, brand: String, model: String): Boolean =
        vendor != null || einkByBuild(manufacturer, brand, model)

    /** Blocking vendor discovery belongs to IO, after the first page. */
    fun probe(context: Context): Boolean {
        val isInk = detected(Eink.vendorName(), Build.MANUFACTURER, Build.BRAND, Build.MODEL)
        val mark = stamp(Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT)
        try { Settings.raw().edit().putString(PREF_KEY, (if (isInk) "eink|" else "lcd|") + mark).apply() }
        catch (_: RuntimeException) { /* A diagnostic must not prevent reading. */ }
        return isInk
    }

    private val probeLock = Any()
    private var running = false
    private val callbacks = ArrayList<(Boolean) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Concurrent callers share one discovery; all callbacks are delivered on main. */
    fun probeAsync(context: Context, onDone: (Boolean) -> Unit) {
        val known = cached(context)
        if (known != null) { main.post { onDone(known) }; return }
        synchronized(probeLock) {
            callbacks.add(onDone)
            if (running) return
            running = true
        }
        val app = context.applicationContext
        Thread({
            val value = try { probe(app) } catch (_: Throwable) { cached(app) ?: false }
            val done = synchronized(probeLock) {
                val batch = callbacks.toList()
                callbacks.clear(); running = false
                batch
            }
            main.post { for (callback in done) callback(value) }
        }, "device-class-probe").apply { isDaemon = true }.start()
    }
}
