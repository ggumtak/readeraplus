package com.ggumtak.readeraplus.render

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import com.ggumtak.readeraplus.engine.FontMetricsPx
import com.ggumtak.readeraplus.engine.IntSize
import com.ggumtak.readeraplus.engine.RunStyle
import com.ggumtak.readeraplus.engine.TextMeasurer
import com.ggumtak.readeraplus.settings.ReaderSettings
import java.util.Locale

/**
 * TextMeasurer backed by TextPaint. One instance per thread. [paintFor] returns the exact paint used for
 * measuring a style, so the renderer draws with identical metrics.
 *
 * Underline / strike-through are NOT paint flags: the renderer draws them as continuous lines across the
 * justified range (per-segment flags would break at every expanded space).
 *
 * Measuring copies the range into a reusable char buffer and uses [TextPaint.getTextRunAdvances] (forced LTR,
 * exactly how the renderer draws). The String overloads hand the whole section String to JNI, which copies all
 * of it on every call when ART stores it Latin-1-compressed or in a movable region: a style-heavy English
 * chapter would copy hundreds of KB per style run.
 */
class AndroidTextMeasurer(
    context: Context,
    val settings: ReaderSettings,
    /** Resolves image intrinsic sizes (decodes bounds only). */
    private val imageSizer: (String) -> IntSize?,
) : TextMeasurer {

    override val emPx: Float = emPxFor(context, settings.fontSizeSp)

    private val fontId = settings.fontId
    private val baseWeight = settings.fontWeight.coerceIn(100, 900)
    private val letterSpacingEm = (settings.letterSpacingPm / 1000f).let { if (it.isFinite()) it.coerceIn(-0.5f, 1f) else 0f }

    private val paints = HashMap<RunStyle, TextPaint>()
    private val metricsCache = HashMap<RunStyle, FontMetricsPx>()
    private var lastStyle: RunStyle? = null
    private var lastPaint: TextPaint? = null
    private var chars = CharArray(0)
    private val fm = Paint.FontMetrics()

    override fun measure(text: String, start: Int, end: Int, style: RunStyle, out: FloatArray, outOffset: Int) {
        val s = maxOf(0, start)
        val e = minOf(end, text.length)
        if (e <= s || outOffset < 0) return
        val n = minOf(e - s, out.size - outOffset)
        if (n <= 0) return
        val p = paintFor(style)
        if (chars.size < n) chars = CharBuffers.grow(chars, n)
        val buf = chars
        text.toCharArray(buf, 0, s, s + n)
        p.getTextRunAdvances(buf, 0, n, 0, n, false, out, outOffset)
        CharBuffers.zeroInvisible(buf, n, out, outOffset)
    }

    override fun metrics(style: RunStyle): FontMetricsPx {
        metricsCache[style]?.let { return it }
        val p = paintFor(style)
        p.getFontMetrics(fm)
        val size = p.textSize
        var asc = -fm.ascent
        var desc = fm.descent
        if (!(asc > 0f) || asc.isInfinite()) asc = size * 0.8f
        if (!(desc >= 0f) || desc.isInfinite()) desc = size * 0.2f
        val m = FontMetricsPx(asc, desc)
        metricsCache[style] = m
        return m
    }

    override fun imageSize(src: String): IntSize? = try {
        imageSizer(src)
    } catch (t: Throwable) {
        null
    }

    fun paintFor(style: RunStyle): TextPaint {
        if (style === lastStyle) return lastPaint!!
        var p = paints[style]
        if (p == null) {
            p = createPaint(style)
            paints[style] = p
        }
        lastStyle = style
        lastPaint = p
        return p
    }

    private fun createPaint(style: RunStyle): TextPaint {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
        p.color = Color.BLACK
        p.textLocale = Locale.KOREAN
        val size = emPx * sanitizeScale(style.sizeScale)
        p.textSize = size
        val weight = FontMath.runWeight(baseWeight, style.bold)
        var stroke = 0f
        val tf: Typeface = if (style.monospace) {
            monospace(style.bold, style.italic)
        } else {
            try {
                stroke = FontManager.syntheticStroke(fontId, weight, size)
                FontManager.typeface(fontId, weight, style.italic)
            } catch (t: Throwable) {
                Typeface.create(Typeface.SERIF, (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0))
            }
        }
        p.typeface = tf
        if (letterSpacingEm != 0f) p.letterSpacing = letterSpacingEm
        if (stroke > 0f) {
            p.style = Paint.Style.FILL_AND_STROKE
            p.strokeWidth = stroke
            p.strokeJoin = Paint.Join.ROUND
        }
        if (style.italic && !tf.isItalic) p.textSkewX = -0.2f
        return p
    }

    private fun monospace(bold: Boolean, italic: Boolean): Typeface {
        val st = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        return if (st == 0) Typeface.MONOSPACE else Typeface.create(Typeface.MONOSPACE, st)
    }

    internal companion object {
        /** 1 em in px for [sp] (settings range is enforced by the UI; only nonsense values are clamped here). */
        fun emPxFor(context: Context, sp: Float): Float {
            val v = if (sp.isFinite()) sp.coerceIn(1f, 400f) else ReaderSettings().fontSizeSp
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)
            return if (px > 0f && px.isFinite()) px else v
        }

        fun sanitizeScale(s: Float): Float = if (s > 0f && s.isFinite()) s.coerceIn(0.3f, 4f) else 1f
    }
}
