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
        when (Settings.raw().getString(PREF_KEY, "")) {
            "eink|$mark" -> true; "lcd|$mark" -> false
            else -> if (einkByBuild(Build.MANUFACTURER, Build.BRAND, Build.MODEL)) true else null
        }
    } catch (_: RuntimeException) { null }
    fun probe(context: Context): Boolean = einkByBuild(Build.MANUFACTURER, Build.BRAND, Build.MODEL) // R3 stub (owner: E2)
    fun probeAsync(context: Context, onDone: (Boolean) -> Unit) { // R3 stub (owner: E2)
        val done = Runnable { onDone(cached(context) ?: false) }
        if (Looper.myLooper() == Looper.getMainLooper()) done.run() else Handler(Looper.getMainLooper()).post(done)
    }
}
