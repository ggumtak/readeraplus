package com.ggumtak.readeraplus.reader.pdf

/** Pure helpers of the pen settings panel: palettes, HSV <-> ARGB and the thickness slider mapping. */
internal object PenColors {
    /** Strong pen colours (opaque ARGB): neutrals first, then the rainbow, then brown. */
    val PEN_PALETTE: IntArray = intArrayOf(
        0xFF000000.toInt(), // black
        0xFF555555.toInt(), // dark grey
        0xFFE53935.toInt(), // red
        0xFFFB8C00.toInt(), // orange
        0xFFC9A400.toInt(), // dark yellow
        0xFF43A047.toInt(), // green
        0xFF00897B.toInt(), // teal
        0xFF1E88E5.toInt(), // blue
        0xFF3949AB.toInt(), // indigo
        0xFF8E24AA.toInt(), // purple
        0xFFD81B60.toInt(), // pink
        0xFF6D4C41.toInt(), // brown
        0xFF9E9E9E.toInt(), // grey
        0xFFB71C1C.toInt(), // dark red
        0xFF1B5E20.toInt(), // dark green
        0xFF0D47A1.toInt(), // dark blue
    )

    /** Light highlighter colours (opaque ARGB); they are multiplied over the page. */
    val HIGHLIGHTER_PALETTE: IntArray = intArrayOf(
        0xFFFFF176.toInt(), // yellow
        0xFFDCE775.toInt(), // lime
        0xFFA5D6A7.toInt(), // green
        0xFF80DEEA.toInt(), // cyan
        0xFF90CAF9.toInt(), // sky blue
        0xFFCE93D8.toInt(), // lavender
        0xFFF48FB1.toInt(), // pink
        0xFFFFAB91.toInt(), // peach
        0xFFFFCC80.toInt(), // light orange
        0xFFE0E0E0.toInt(), // light grey
        0xFFB0BEC5.toInt(), // blue grey
        0xFFBCAAA4.toInt(), // light brown
    )

    /** Opaque ARGB from [h] 0..360, [s] 0..1, [v] 0..1 (values outside the ranges are clamped / wrapped). */
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val sat = s.coerceIn(0f, 1f)
        val value = v.coerceIn(0f, 1f)
        var hue = h % 360f
        if (hue < 0f) hue += 360f
        val sector = hue / 60f
        val i = sector.toInt()
        val f = sector - i
        val p = value * (1f - sat)
        val q = value * (1f - sat * f)
        val t = value * (1f - sat * (1f - f))
        val r: Float
        val g: Float
        val b: Float
        when (i) {
            0 -> { r = value; g = t; b = p }
            1 -> { r = q; g = value; b = p }
            2 -> { r = p; g = value; b = t }
            3 -> { r = p; g = q; b = value }
            4 -> { r = t; g = p; b = value }
            else -> { r = value; g = p; b = q }
        }
        return (0xFF shl 24) or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
    }

    /** Writes hue 0..360, saturation 0..1 and value 0..1 of [argb] (alpha ignored) into [out] (size >= 3). */
    fun argbToHsv(argb: Int, out: FloatArray) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        var h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (h < 0f) h += 360f
        out[0] = h
        out[1] = if (max == 0f) 0f else d / max
        out[2] = max
    }

    /** Thickness for slider [progress] 0..[steps], linear over [minWidth]..[maxWidth], rounded to 0.1. */
    fun progressToWidth(progress: Int, steps: Int, minWidth: Float, maxWidth: Float): Float {
        val t = progress.coerceIn(0, steps).toFloat() / steps
        val w = minWidth + (maxWidth - minWidth) * t
        val rounded = Math.round(w * 10f) / 10f
        return rounded.coerceIn(minOf(minWidth, maxWidth), maxOf(minWidth, maxWidth))
    }

    /** Slider progress 0..[steps] closest to [width]. */
    fun widthToProgress(width: Float, steps: Int, minWidth: Float, maxWidth: Float): Int {
        val span = maxWidth - minWidth
        if (span <= 0f) return 0
        return Math.round((width - minWidth) / span * steps).coerceIn(0, steps)
    }

    /** "1.4" style label (one decimal, locale independent). */
    fun formatWidth(width: Float): String {
        val tenths = Math.round(width * 10f)
        return "${tenths / 10}.${Math.abs(tenths % 10)}"
    }

    private fun to255(x: Float): Int = Math.round(x * 255f).coerceIn(0, 255)
}
