package com.ggumtak.readeraplus.reader.extras

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.ggumtak.readeraplus.render.QuoteStyles
import com.ggumtak.readeraplus.ui.kit.Ink

/**
 * One quote style drawn as a static swatch (highlights.md §6.1, NOTES_SPEC §7.1 item 1): no ripple, no animation.
 *
 * - Colour mode: a filled dot of [sizeDp] (20 dp in popups, 12 dp in rows) with a 1 px [Ink.GRAY] ring so pale yellow
 *   still shows on white; 밑줄 is "가" over a 2 px line.
 * - Ink mode ([ink], from `QuoteLook.ink()` when the view is built): a 30 × 20 dp sample (22 × 14 dp for row sizes)
 *   of the style's grey band and line around a black "가" — what the e-ink page shows.
 * - [isChecked] draws a 2 dp black ring 3 dp outside the swatch. The view always reserves that ring's room (unless
 *   [reserveRing] is false) so checking never changes its size.
 *
 * UI swatches always use the day values: popups and lists are black on white even over an inverted page.
 */
@SuppressLint("ViewConstructor")
class QuoteSwatch(context: Context, style: Int, sizeDp: Int, ink: Boolean) : View(context) {
    var style: Int = style
        set(v) {
            if (field == v) return
            field = v
            contentDescription = QuoteStyles.label(v)
            invalidate()
        }

    var isChecked: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    /** False: no room is kept for the [isChecked] ring (the selection popup's 인용 cell, never checked). */
    internal var reserveRing: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            requestLayout()
        }

    private val ink = ink
    private val density = context.resources.displayMetrics.density
    private val small = sizeDp < SMALL_LIMIT_DP
    private val boxW: Float = density * (if (ink) (if (small) 22 else 30) else sizeDp).toFloat()
    private val boxH: Float = density * (if (ink) (if (small) 14 else 20) else sizeDp).toFloat()
    private val ringGap = density * PaletteGeometry.RING_GAP_DP
    private val ringWidth = density * PaletteGeometry.RING_DP
    private val pad: Float get() = if (reserveRing) ringGap + ringWidth else 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeWidth = 1f; color = Ink.GRAY }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; color = Ink.BLACK }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeWidth = ringWidth; color = Ink.BLACK }
    private val dash = DashPathEffect(floatArrayOf(density * 3f, density * 2f), 0f)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ink.BLACK
        textAlign = Paint.Align.CENTER
        textSize = context.resources.displayMetrics.scaledDensity * (if (ink) (if (small) 9f else 13f) else sizeDp * 0.7f)
    }
    private val textCenterOffset: Float = text.fontMetrics.let { -(it.ascent + it.descent) / 2f }
    private val rect = RectF()

    init {
        contentDescription = QuoteStyles.label(style)
        isClickable = false
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = Math.round(boxW + 2 * pad)
        val h = Math.round(boxH + 2 * pad)
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val s = QuoteStyles.of(style)
        val w = minOf(boxW, (width - 2 * pad).coerceAtLeast(0f))
        val h = minOf(boxH, (height - 2 * pad).coerceAtLeast(0f))
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        if (ink) drawInk(canvas, s, cx, cy) else drawColour(canvas, s, cx, cy)
        if (isChecked) {
            val out = ringGap + ringWidth / 2f
            if (ink) {
                rect.inset(-out, -out)
                canvas.drawRect(rect, ring)
            } else {
                canvas.drawCircle(cx, cy, minOf(rect.width(), rect.height()) / 2f + out, ring)
            }
        }
    }

    private fun drawColour(canvas: Canvas, s: Int, cx: Float, cy: Float) {
        if (s == QuoteStyles.UNDERLINE) {
            val stroke = 2f
            canvas.drawText(GLYPH, cx, cy + textCenterOffset - stroke, text)
            line.strokeWidth = stroke
            line.pathEffect = null
            val y = rect.bottom - stroke / 2f
            canvas.drawLine(rect.left + boxW * 0.15f, y, rect.right - boxW * 0.15f, y, line)
            return
        }
        val r = minOf(rect.width(), rect.height()) / 2f
        fill.color = QuoteStyles.colorFill(s, night = false)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r - 0.5f, edge)
    }

    private fun drawInk(canvas: Canvas, s: Int, cx: Float, cy: Float) {
        val g = QuoteStyles.inkGrey(s)
        fill.color = if (g >= 0) Color.rgb(g, g, g) else Ink.WHITE
        canvas.drawRect(rect, fill)
        canvas.drawText(GLYPH, cx, cy + textCenterOffset, text)
        line.pathEffect = null
        when (QuoteStyles.inkLine(s)) {
            QuoteStyles.LINE_THIN -> underline(canvas, density * 1f)
            QuoteStyles.LINE_THICK -> underline(canvas, density * 2f)
            QuoteStyles.LINE_DASHED -> {
                line.pathEffect = dash
                underline(canvas, density * 1.5f)
                line.pathEffect = null
            }
            QuoteStyles.LINE_BOX -> {
                line.strokeWidth = density * 1f
                val h = line.strokeWidth / 2f
                canvas.drawRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, line)
            }
        }
    }

    private fun underline(canvas: Canvas, stroke: Float) {
        line.strokeWidth = stroke
        val y = rect.bottom - stroke / 2f
        canvas.drawLine(rect.left, y, rect.right, y, line)
    }

    private companion object {
        const val GLYPH = "가"
        /** Sizes below this are the list-row swatches (12 dp dot, 22 × 14 dp ink sample). */
        const val SMALL_LIMIT_DP = 16
    }
}
