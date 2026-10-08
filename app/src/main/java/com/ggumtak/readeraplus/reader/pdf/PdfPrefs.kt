package com.ggumtak.readeraplus.reader.pdf

import android.content.SharedPreferences

/** One drawing tool of the tool bar: pen or highlighter with its own colour, full width (page points) and pressure. */
internal class PenPreset(val tool: Int, val color: Int, val width: Float, val pressure: Boolean) {
    fun with(color: Int = this.color, width: Float = this.width, pressure: Boolean = this.pressure) =
        PenPreset(tool, color, width, pressure)
}

/** Pen presets as text: "tool,color,width,pressure;…" (pure; JVM-tested). */
internal object PenPresets {
    const val PEN_MIN = 0.3f
    const val PEN_MAX = 8f
    const val HIGHLIGHTER_MIN = 4f
    const val HIGHLIGHTER_MAX = 30f
    const val MAX_RECENT = 8

    /** Three pens and a highlighter, like a pencil case. */
    val DEFAULTS: List<PenPreset> = listOf(
        PenPreset(InkTool.PEN, 0xFF000000.toInt(), 1.2f, true),
        PenPreset(InkTool.PEN, 0xFFD32F2F.toInt(), 1.2f, true),
        PenPreset(InkTool.PEN, 0xFF1565C0.toInt(), 1.2f, true),
        PenPreset(InkTool.HIGHLIGHTER, 0xFFFFF176.toInt(), 11f, false),
    )

    fun encode(list: List<PenPreset>): String =
        list.joinToString(";") { "${it.tool},${it.color},${it.width},${if (it.pressure) 1 else 0}" }

    /** Tolerant: bad entries are skipped, widths clamped to their tool's range; nothing usable → [DEFAULTS]. */
    fun decode(text: String?): List<PenPreset> {
        if (text.isNullOrBlank()) return DEFAULTS
        val out = ArrayList<PenPreset>()
        for (part in text.split(';')) {
            val f = part.split(',')
            if (f.size != 4) continue
            val tool = f[0].trim().toIntOrNull() ?: continue
            if (tool != InkTool.PEN && tool != InkTool.HIGHLIGHTER) continue
            val color = f[1].trim().toLongOrNull()?.toInt() ?: continue
            val w = f[2].trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
            val pressure = f[3].trim() == "1"
            out += PenPreset(tool, color or 0xFF000000.toInt(), clampWidth(tool, w), pressure)
        }
        return out.ifEmpty { DEFAULTS }
    }

    fun clampWidth(tool: Int, w: Float): Float =
        if (tool == InkTool.HIGHLIGHTER) w.coerceIn(HIGHLIGHTER_MIN, HIGHLIGHTER_MAX) else w.coerceIn(PEN_MIN, PEN_MAX)

    /** [recent] with [color] moved to the front, at most [MAX_RECENT]. */
    fun pushRecent(recent: IntArray, color: Int): IntArray {
        val out = IntArray(minOf(MAX_RECENT, recent.size + 1))
        out[0] = color
        var k = 1
        for (c in recent) {
            if (k >= out.size) break
            if (c != color) out[k++] = c
        }
        return out.copyOf(k)
    }

    fun encodeColors(colors: IntArray): String = colors.joinToString(",")

    fun decodeColors(text: String?): IntArray =
        text?.split(',')?.mapNotNull { it.trim().toLongOrNull()?.toInt() }?.take(MAX_RECENT)?.toIntArray() ?: IntArray(0)
}

/** The PDF viewer's own settings (the app settings stay shared with the text reader). Main thread. */
internal class PdfPrefs(private val p: SharedPreferences) {
    /** Fingers a page-turning swipe takes: 1 or 2. */
    var swipeFingers: Int
        get() = if (p.getInt("swipeFingers", 1) == 2) 2 else 1
        set(v) = p.edit().putInt("swipeFingers", if (v == 2) 2 else 1).apply()

    /** Page colour: [TONE_NORMAL], [TONE_DARK] (inverted) or [TONE_SEPIA]. */
    var pageTone: Int
        get() = p.getInt("pageTone", TONE_NORMAL).coerceIn(TONE_NORMAL, TONE_SEPIA)
        set(v) = p.edit().putInt("pageTone", v).apply()

    /** The "5 / 120" badge at the bottom right. */
    var pageBadge: Boolean
        get() = p.getBoolean("pageBadge", true)
        set(v) = p.edit().putBoolean("pageBadge", v).apply()

    /** With a tool on, one finger draws; off: only a stylus draws and fingers move and turn the pages. */
    var fingerDraws: Boolean
        get() = p.getBoolean("fingerDraws", true)
        set(v) = p.edit().putBoolean("fingerDraws", v).apply()

    /** Stylus pressure changes the pen width (for presets that use it). */
    var pressure: Boolean
        get() = p.getBoolean("pressure", true)
        set(v) = p.edit().putBoolean("pressure", v).apply()

    var presets: List<PenPreset>
        get() = PenPresets.decode(p.getString("presets", null))
        set(v) = p.edit().putString("presets", PenPresets.encode(v)).apply()

    var selectedPreset: Int
        get() = p.getInt("selectedPreset", 0)
        set(v) = p.edit().putInt("selectedPreset", v).apply()

    var recentColors: IntArray
        get() = PenPresets.decodeColors(p.getString("recentColors", null))
        set(v) = p.edit().putString("recentColors", PenPresets.encodeColors(v)).apply()

    companion object {
        const val TONE_NORMAL = 0
        const val TONE_DARK = 1
        const val TONE_SEPIA = 2
    }
}
