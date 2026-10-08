package com.ggumtak.readeraplus.reader.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.ggumtak.readeraplus.ui.kit.dpF

/**
 * A tool of the floating PDF tool bar, drawn in code (Flexcil-like): a pen standing with its tip in its own
 * colour and its width under it (raised when chosen), a highlighter with a chisel tip, an eraser and a lasso.
 * Paints and paths are made once; [onDraw] allocates nothing.
 */
internal class PdfToolIcon(context: Context, val kind: Int) : View(context) {
    /** Tip colour of a pen / highlighter. */
    var tipColor = 0xFF000000.toInt()
        set(v) {
            field = v
            invalidate()
        }
    /** Width shown under a pen ("0.4"), or null. */
    var caption: String? = null
        set(v) {
            field = v
            invalidate()
        }
    var chosen = false
        set(v) {
            field = v
            invalidate()
        }

    private val d = context.dpF(1f)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * d
        pathEffect = DashPathEffect(floatArrayOf(3f * d, 2.5f * d), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.5f * d
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()

    init {
        contentDescription = when (kind) {
            PEN -> "펜"
            HIGHLIGHTER -> "형광펜"
            ERASER -> "지우개"
            else -> "선택"
        }
        isClickable = true
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (if (kind == PEN || kind == HIGHLIGHTER) 32f else 38f) * d
        setMeasuredDimension(w.toInt(), (PdfChrome.TOOLBAR_DP * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val fg = if (chosen) ACCENT else ICON
        when (kind) {
            PEN -> drawPen(canvas, w, h, fg)
            HIGHLIGHTER -> drawMarker(canvas, w, h, fg)
            ERASER -> drawEraser(canvas, w, h, fg)
            else -> drawLasso(canvas, w, h, fg)
        }
    }

    /** A pen standing upright from the top edge, tip down; lifted 6dp when chosen. */
    private fun drawPen(canvas: Canvas, w: Float, h: Float, fg: Int) {
        val cx = w / 2f
        val lift = if (chosen) 0f else 6f * d
        val top = -2f * d + lift
        val bodyW = 10f * d
        val bodyBottom = top + 14f * d
        val tipBottom = bodyBottom + 11f * d
        body.color = if (chosen) 0xFFF2F2F2.toInt() else 0xFFBDBDBD.toInt()
        rect.set(cx - bodyW / 2f, top, cx + bodyW / 2f, bodyBottom)
        canvas.drawRect(rect, body)
        // The cone, then its coloured point.
        path.rewind()
        path.moveTo(cx - bodyW / 2f, bodyBottom)
        path.lineTo(cx + bodyW / 2f, bodyBottom)
        path.lineTo(cx, tipBottom)
        path.close()
        body.color = 0xFFE0E0E0.toInt()
        canvas.drawPath(path, body)
        path.rewind()
        val pointTop = bodyBottom + 7f * d
        val halfAt = bodyW / 2f * (tipBottom - pointTop) / (tipBottom - bodyBottom)
        path.moveTo(cx - halfAt, pointTop)
        path.lineTo(cx + halfAt, pointTop)
        path.lineTo(cx, tipBottom)
        path.close()
        body.color = tipColor
        canvas.drawPath(path, body)
        // A band of the ink colour on the body, like a pen cap ring.
        rect.set(cx - bodyW / 2f, bodyBottom - 4f * d, cx + bodyW / 2f, bodyBottom - 1.5f * d)
        canvas.drawRect(rect, body)
        caption?.let {
            text.color = fg
            canvas.drawText(it, cx, h - 2f * d, text)
        }
    }

    /** A highlighter: a wider body with a slanted chisel tip in the ink colour. */
    private fun drawMarker(canvas: Canvas, w: Float, h: Float, fg: Int) {
        val cx = w / 2f
        val lift = if (chosen) 0f else 6f * d
        val top = -2f * d + lift
        val bodyW = 14f * d
        val bodyBottom = top + 15f * d
        body.color = if (chosen) 0xFFF2F2F2.toInt() else 0xFFBDBDBD.toInt()
        rect.set(cx - bodyW / 2f, top, cx + bodyW / 2f, bodyBottom)
        canvas.drawRect(rect, body)
        path.rewind()
        path.moveTo(cx - bodyW / 2f, bodyBottom)
        path.lineTo(cx + bodyW / 2f, bodyBottom)
        path.lineTo(cx + bodyW / 4f, bodyBottom + 7f * d)
        path.lineTo(cx - bodyW / 4f, bodyBottom + 7f * d)
        path.close()
        body.color = 0xFFE0E0E0.toInt()
        canvas.drawPath(path, body)
        path.rewind()
        path.moveTo(cx - bodyW / 4f, bodyBottom + 7f * d)
        path.lineTo(cx + bodyW / 4f, bodyBottom + 7f * d)
        path.lineTo(cx + bodyW / 4f, bodyBottom + 10f * d)
        path.lineTo(cx - bodyW / 4f, bodyBottom + 13f * d)
        path.close()
        body.color = tipColor
        canvas.drawPath(path, body)
        caption?.let {
            text.color = fg
            canvas.drawText(it, cx, h - 2f * d, text)
        }
    }

    /** An eraser block, tilted, its lower half filled. */
    private fun drawEraser(canvas: Canvas, w: Float, h: Float, fg: Int) {
        canvas.save()
        canvas.rotate(-40f, w / 2f, h / 2f)
        rect.set(w / 2f - 6f * d, h / 2f - 9f * d, w / 2f + 6f * d, h / 2f + 9f * d)
        line.color = fg
        canvas.drawRoundRect(rect, 3f * d, 3f * d, line)
        body.color = fg
        rect.set(w / 2f - 6f * d, h / 2f + 2f * d, w / 2f + 6f * d, h / 2f + 9f * d)
        canvas.drawRoundRect(rect, 3f * d, 3f * d, body)
        canvas.restore()
    }

    /** A dashed loop with a rope down to a knot: the selection lasso. */
    private fun drawLasso(canvas: Canvas, w: Float, h: Float, fg: Int) {
        dashed.color = fg
        rect.set(w / 2f - 11f * d, h / 2f - 10f * d, w / 2f + 11f * d, h / 2f + 3f * d)
        canvas.drawOval(rect, dashed)
        line.color = fg
        path.rewind()
        path.moveTo(w / 2f - 5f * d, h / 2f + 2f * d)
        path.cubicTo(w / 2f - 1f * d, h / 2f + 6f * d, w / 2f - 8f * d, h / 2f + 8f * d, w / 2f - 4f * d, h / 2f + 11f * d)
        canvas.drawPath(path, line)
        body.color = fg
        canvas.drawCircle(w / 2f - 4f * d, h / 2f + 11f * d, 2f * d, body)
    }

    companion object {
        const val PEN = 0
        const val HIGHLIGHTER = 1
        const val ERASER = 2
        const val LASSO = 3
        /** Icon colour on the dark tool bar, and the chosen tool's accent. */
        const val ICON = 0xFFE0E0E0.toInt()
        const val ACCENT = 0xFFF5B82E.toInt()
    }
}
