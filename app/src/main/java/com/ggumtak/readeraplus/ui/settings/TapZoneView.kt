package com.ggumtak.readeraplus.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.settings.TapZoneMode
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.sp

/**
 * Miniature page showing where taps go (e-ink: flat greys, 1px lines, no animation). In CUSTOM mode each 3×3
 * cell is outlined and tapping it calls [onCellTap]. Corner hot spots (bookmark / invert) are drawn dashed.
 */
internal class TapZoneView(context: Context) : View(context) {
    var mode: TapZoneMode = TapZoneMode.LEFT_RIGHT
        set(v) { field = v; invalidate() }
    /** Preview with next/previous swapped (AppSettings.invertTaps). */
    var inverted: Boolean = false
        set(v) { field = v; invalidate() }
    var custom: List<TapAction> = emptyList()
        set(v) { field = v; invalidate() }
    var bookmarkCorner: Boolean = false
        set(v) { field = v; invalidate() }
    var invertCorner: Boolean = false
        set(v) { field = v; invalidate() }
    /** Called with the row-major cell index when the user taps a cell in CUSTOM mode. */
    var onCellTap: ((Int) -> Unit)? = null

    /** Page height / width, taken from the display in portrait. */
    private val aspect: Float = run {
        val dm = context.resources.displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val h = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        if (w <= 0f) 1.8f else (h / w).coerceIn(1.3f, 2.2f)
    }
    private val maxWidthPx = context.dp(176)

    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val line = Paint().apply {
        style = Paint.Style.STROKE
        color = Ink.LINE
        strokeWidth = 1f
    }
    private val frame = Paint().apply {
        style = Paint.Style.STROKE
        color = Ink.LINE
        strokeWidth = context.dpF(2f)
    }
    private val dashed = Paint().apply {
        style = Paint.Style.STROKE
        color = Ink.LINE
        strokeWidth = context.dpF(1f).coerceAtLeast(1f)
        pathEffect = DashPathEffect(floatArrayOf(context.dpF(3f), context.dpF(3f)), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ink.BLACK
        textAlign = Paint.Align.CENTER
        textSize = context.sp(14f)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ink.BLACK
        textAlign = Paint.Align.CENTER
        textSize = context.sp(10f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specW = MeasureSpec.getSize(widthMeasureSpec)
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || specW <= 0) maxWidthPx
        else minOf(specW, maxWidthPx)
        setMeasuredDimension(w, Math.round(w * aspect))
    }

    private fun fillColor(a: TapAction): Int = when (a) {
        TapAction.PREV, TapAction.PREV_CHAPTER -> 0xFFE0E0E0.toInt()
        TapAction.MENU -> 0xFFB8B8B8.toInt()
        else -> Ink.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val g = TapZoneModel.grid(mode, custom, inverted)
        val editable = mode == TapZoneMode.CUSTOM

        for (row in 0 until g.rows) {
            for (col in 0 until g.cols) {
                fill.color = fillColor(g.action(col, row))
                canvas.drawRect(g.xs[col] * w, g.ys[row] * h, g.xs[col + 1] * w, g.ys[row + 1] * h, fill)
            }
        }
        // Region borders: between cells with different actions (every cell edge in the editor).
        for (col in 1 until g.cols) {
            val x = Math.round(g.xs[col] * w).toFloat()
            for (row in 0 until g.rows) {
                if (editable || g.action(col - 1, row) != g.action(col, row)) {
                    canvas.drawLine(x, g.ys[row] * h, x, g.ys[row + 1] * h, line)
                }
            }
        }
        for (row in 1 until g.rows) {
            val y = Math.round(g.ys[row] * h).toFloat()
            for (col in 0 until g.cols) {
                if (editable || g.action(col, row - 1) != g.action(col, row)) {
                    canvas.drawLine(g.xs[col] * w, y, g.xs[col + 1] * w, y, line)
                }
            }
        }
        for (l in TapZoneModel.labels(g, perCell = editable)) {
            val left = g.xs[l.col] * w
            val right = g.xs[l.col + 1] * w
            val top = g.ys[l.row] * h
            val bottom = g.ys[l.row + 1] * h
            text.typeface = if (l.action == TapAction.MENU || l.action == TapAction.NEXT || l.action == TapAction.PREV) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.color = if (l.action == TapAction.NONE) Ink.GRAY else Ink.BLACK
            drawLabel(canvas, TapZoneModel.shortLabel(l.action), (left + right) / 2f, (top + bottom) / 2f, right - left, bottom - top)
        }
        if (invertCorner) drawCorner(canvas, 0f, TapZoneModel.CORNER_W * w, TapZoneModel.CORNER_H * h, "반전")
        if (bookmarkCorner) drawCorner(canvas, (1f - TapZoneModel.CORNER_W) * w, w, TapZoneModel.CORNER_H * h, "북마크")
        val half = frame.strokeWidth / 2f
        canvas.drawRect(half, half, w - half, h - half, frame)
    }

    private fun drawCorner(canvas: Canvas, left: Float, right: Float, bottom: Float, label: String) {
        fill.color = Ink.WHITE
        canvas.drawRect(left, 0f, right, bottom, fill)
        canvas.drawRect(left + 1f, 1f, right - 1f, bottom, dashed)
        var size = context.sp(10f)
        small.textSize = size
        while (small.measureText(label) > right - left - 2f && size > context.sp(6f)) {
            size -= 1f
            small.textSize = size
        }
        // Baseline from the final text size so the label is centred vertically.
        val fm = small.fontMetrics
        val base = bottom / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, (left + right) / 2f, base, small)
    }

    /** Centred multi-line label; falls back to one char per line (then smaller text) in narrow cells. */
    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, cellW: Float, cellH: Float) {
        val pad = context.dpF(3f)
        val base = context.sp(14f)
        text.textSize = base
        var lines = label.split('\n')
        if (lines.any { text.measureText(it) > cellW - pad * 2 }) {
            val joined = label.replace("\n", "")
            lines = joined.map { it.toString() }
        }
        val fm = text.fontMetrics
        var lineH = fm.descent - fm.ascent
        while ((lineH * lines.size > cellH - pad * 2 || lines.any { text.measureText(it) > cellW - pad }) && text.textSize > context.sp(7f)) {
            text.textSize = text.textSize - 1f
            val m = text.fontMetrics
            lineH = m.descent - m.ascent
        }
        val m = text.fontMetrics
        val total = lineH * lines.size
        var y = cy - total / 2f - m.ascent
        for (ln in lines) {
            canvas.drawText(ln, cx, y, text)
            y += lineH
        }
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode != TapZoneMode.CUSTOM || onCellTap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                // A tap, not a drag (the parent ScrollView only steals vertical drags).
                val moved = Math.abs(event.x - downX) > touchSlop || Math.abs(event.y - downY) > touchSlop
                if (!moved && width > 0 && height > 0 && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    performClick()
                    onCellTap?.invoke(TapZoneModel.cellAt(event.x / width, event.y / height))
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
