package com.ggumtak.readeraplus.reader

import android.content.Context
import android.os.Build
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.render.Eink
import java.io.File
import android.provider.Settings as SystemSettings

/**
 * Read-only look at how this device's light is controlled (brightness.md §3.3): vendor/standard light keys in the
 * Settings.System table, the LM3630A sysfs nodes, the xrz getters. IO thread only; never on the open path; never
 * writes anything.
 */
internal object LightProbe {
    /** Vendor / standard light keys: names we look for in the Settings.System table (a query lists vendor keys too). */
    internal val KEY_RE = Regex("(?i)(cold|warm|front|light|bright|colou?r_?temp)")
    private val SKIP = setOf("notification_light_pulse", "pointer_location")
    private const val XRZ_INTERNAL = "xrz.framework.manager.XrzEinkManagerInternal"

    /** name → value of every readable Settings.System row whose name looks like a light control. */
    fun lightKeys(ctx: Context): Map<String,String> {
        val out = LinkedHashMap<String, String>()
        try {
            ctx.contentResolver.query(SystemSettings.System.CONTENT_URI, arrayOf("name", "value"), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val n = cur.getString(0) ?: continue
                    if (!isLightKey(n)) continue
                    out[n] = cur.getString(1) ?: ""
                }
            }
        } catch (t: Throwable) { /* provider refused: nothing to show */ }
        return out
    }

    /** True for a Settings.System name that looks like a light control. */
    internal fun isLightKey(name: String): Boolean = name !in SKIP && KEY_RE.containsMatchIn(name)

    /** True when the firmware stores a warm channel (Bigme keys); only then does the UI mention 색온도. */
    fun hasWarm(keys: Map<String,String>): Boolean = keys.keys.any { it.contains("warm", ignoreCase = true) }

    /** LM3630A nodes (Bigme: bus 2 on HiBreak/B6, bus 7 on HiBreak Pro Color). */
    fun coldNode(): File? = node("lm3630a_cold_light")
    fun warmNode(): File? = node("lm3630a_warm_light")

    private fun node(name: String): File? {
        for (bus in 0..9) {
            val f = File("/sys/bus/i2c/devices/$bus-0036/$name")
            try { if (f.exists()) return f } catch (t: Throwable) { return null }
        }
        return null
    }

    /** Current raw value of [file], or -1 when missing / not readable (SELinux usually says no). */
    fun read(file: File?): Int = try { file?.readText()?.trim()?.toInt() ?: -1 } catch (t: Throwable) { -1 }

    /** Lines for 정보 › 조명 진단 (Korean labels, raw values; UI_SPEC §4.6, brightness.md §5.7). IO thread. */
    fun report(ctx: Context): List<String> {
        val lines = ArrayList<String>()
        val vendor = try { Eink.vendorName() } catch (t: Throwable) { null }
        val eink = vendor ?: if (try { DeviceClass.cached(ctx) == true } catch (t: Throwable) { false }) "예" else "아님"
        lines.add("e-ink: $eink · 제조사 ${Build.MANUFACTURER} · 모델 ${Build.MODEL} · 지문 ${Build.FINGERPRINT}")
        lines.add("밝기 방식: " + verdictLabel(try { DeviceLight.verdict(ctx) } catch (t: Throwable) { DeviceLight.VERDICT_UNKNOWN }))
        val can = try { SystemSettings.System.canWrite(ctx) } catch (t: Throwable) { false }
        lines.add("시스템 설정 수정 권한: " + if (can) "허용" else "없음")
        val cr = ctx.contentResolver
        val level = try { SystemSettings.System.getInt(cr, SystemSettings.System.SCREEN_BRIGHTNESS, -1) } catch (t: Throwable) { -1 }
        val mode = try { SystemSettings.System.getInt(cr, SystemSettings.System.SCREEN_BRIGHTNESS_MODE, -1) } catch (t: Throwable) { -1 }
        lines.add("screen_brightness = " + (if (level >= 0) level.toString() else "없음") + modeLabel(mode))
        lines.add("기기 조명 키: " + keysLine(lightKeys(ctx)))
        val cold = coldNode()
        val warm = warmNode()
        if (cold == null && warm == null) {
            lines.add("조명 노드: 없음")
        } else {
            for (f in arrayOf(cold, warm)) if (f != null) lines.add("조명 노드: " + nodeLine(f.path, read(f)))
        }
        lines.add(xrzLine())
        return lines
    }

    internal fun verdictLabel(v: Int): String = when (v) {
        DeviceLight.VERDICT_DEVICE -> "기기 설정 (확인됨)"
        DeviceLight.VERDICT_WINDOW -> "앱 화면 (확인됨)"
        DeviceLight.VERDICT_NONE -> "없음"
        else -> "확인 안 됨"
    }

    internal fun modeLabel(mode: Int): String = when (mode) {
        SystemSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> " (수동)"
        SystemSettings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> " (자동)"
        else -> ""
    }

    internal fun keysLine(keys: Map<String, String>): String =
        if (keys.isEmpty()) "없음" else keys.entries.joinToString(" · ") { "${it.key}=${it.value}" }

    internal fun nodeLine(path: String, value: Int): String = if (value < 0) "$path 읽기 불가" else "$path = $value"

    /** The xrz image-tone getters, by reflection, getters only (brightness.md F8: never the setters). */
    private fun xrzLine(): String {
        val cls = try { Class.forName(XRZ_INTERNAL) } catch (t: Throwable) { return "xrz: 없음" }
        val b = xrzGet(cls, "getScreenBrightnessLevel")
        val d = xrzGet(cls, "getScreenDarkLevel")
        if (b == null && d == null) return "xrz: 없음"
        return "xrz getScreenBrightnessLevel = ${b ?: "없음"} · getScreenDarkLevel = ${d ?: "없음"}"
    }

    private fun xrzGet(cls: Class<*>, name: String): String? = try {
        cls.getMethod(name).invoke(null)?.toString()
    } catch (t: Throwable) {
        null
    }
}
