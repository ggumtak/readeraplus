package com.ggumtak.readeraplus.reader.pdf

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * Settings of one drawing tool as a dark bottom sheet (Flexcil-like): a preview, thickness (slider with − / +),
 * basic colours, recent colours, a custom colour (hue / saturation / brightness) and, for the pen, pressure.
 * Every change is reported at once through [onChange]; [onClose] runs once when the sheet closes.
 */
internal class PenPanel(
    private val activity: Activity,
    private val title: String,
    private val highlighter: Boolean,
    color: Int,
    width: Float,
    private val minWidth: Float,
    private val maxWidth: Float,
    private val pxPerPoint: Float,
    pressure: Boolean?,
    private val recent: IntArray,
    private val onChange: (color: Int, width: Float, pressure: Boolean) -> Unit,
    private val onClose: (() -> Unit)? = null,
) {
    private var color: Int = color or (0xFF shl 24)
    private var width: Float = width.coerceIn(minOf(minWidth, maxWidth), maxOf(minWidth, maxWidth))
    private val hasPressure: Boolean = pressure != null
    private var pressure: Boolean = pressure ?: false

    /** Thickness positions: one per 0.1 point. */
    private val steps = Math.round((maxWidth - minWidth) * 10f).coerceAtLeast(1)
    private val hsv = FloatArray(3)
    private val chips = ArrayList<ColorChip>()
    private lateinit var preview: Preview
    private lateinit var custom: ColorChip
    private lateinit var hueBar: SeekBar
    private lateinit var satBar: SeekBar
    private lateinit var valBar: SeekBar

    fun show() {
        PenColors.argbToHsv(color, hsv)
        PdfSheet.show(activity, null, build(), onDismiss = { onClose?.invoke() })
    }

    private fun emit() = onChange(color, width, pressure)

    private fun build(): View {
        val ctx: Context = activity
        val col = ctx.vertical()
        preview = Preview(ctx, highlighter, pxPerPoint).also { it.set(color, width, pressure) }
        col.addView(preview, lp(h = ctx.dp(84)).apply { setMargins(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(4)) })

        col.addView(PdfSheet.sliderRow(ctx, "$title 두께", steps, Math.round((width - minWidth) * 10f).coerceIn(0, steps), { p ->
            PenColors.formatWidth(minWidth + p / 10f)
        }) { p ->
            width = (minWidth + p / 10f).coerceIn(minWidth, maxWidth)
            preview.set(color, width, pressure)
            emit()
        }, lp())

        col.addView(PdfSheet.section(ctx, "기본 색상"))
        col.addView(chipRows(ctx, if (highlighter) PenColors.HIGHLIGHTER_PALETTE else PenColors.PEN_PALETTE), lp())
        if (recent.isNotEmpty()) {
            col.addView(PdfSheet.section(ctx, "최근 색상"))
            col.addView(chipRows(ctx, recent.copyOf(minOf(recent.size, COLUMNS))), lp())
        }

        col.addView(PdfSheet.section(ctx, "직접 고르기"))
        val head = ctx.horizontal {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
        }
        custom = ColorChip(ctx).also { it.chipColor = color }
        head.addView(ctx.label("지금 색", 15f, color = PdfSheet.SUB), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(custom, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(32)))
        col.addView(head, lp())
        hueBar = hsvRow(ctx, col, "색상", 360, Math.round(hsv[0]).coerceIn(0, 360)) { hsv[0] = it.toFloat() }
        satBar = hsvRow(ctx, col, "채도", 100, Math.round(hsv[1] * 100f)) { hsv[1] = it / 100f }
        valBar = hsvRow(ctx, col, "밝기", 100, Math.round(hsv[2] * 100f)) { hsv[2] = it / 100f }

        if (hasPressure) {
            col.addView(PdfSheet.section(ctx, "펜"))
            col.addView(PdfSheet.switchRow(ctx, "펜 압력 감지", "스타일러스를 누르는 힘에 따라 굵기가 변합니다", pressure) { on ->
                pressure = on
                preview.set(color, width, pressure)
                emit()
            })
        }
        return col
    }

    /** Colour squares, [COLUMNS] per row; the current colour ringed white. */
    private fun chipRows(ctx: Context, colors: IntArray): View {
        val grid = ctx.vertical { setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(4)) }
        var i = 0
        while (i < colors.size) {
            val row = ctx.horizontal()
            for (k in 0 until COLUMNS) {
                val idx = i + k
                if (idx < colors.size) {
                    val c = colors[idx] or (0xFF shl 24)
                    val chip = ColorChip(ctx).apply {
                        chipColor = c
                        ringed = c == color
                        setOnClickListener {
                            color = c
                            colorChanged(fromChip = true)
                        }
                    }
                    chips += chip
                    row.addView(chip, LinearLayout.LayoutParams(0, ctx.dp(40), 1f))
                } else {
                    row.addView(View(ctx), LinearLayout.LayoutParams(0, ctx.dp(40), 1f))
                }
            }
            grid.addView(row, lp())
            i += COLUMNS
        }
        return grid
    }

    private fun hsvRow(ctx: Context, parent: LinearLayout, name: String, max: Int, progress: Int, store: (Int) -> Unit): SeekBar {
        val bar = PdfSheet.darkSeekBar(ctx, max, progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                store(p)
                color = PenColors.hsvToArgb(hsv[0], hsv[1], hsv[2])
                colorChanged(fromChip = false)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        val row = ctx.horizontal {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(20), 0, ctx.dp(12), 0)
        }
        row.addView(ctx.label(name, 14f, color = PdfSheet.SUB).apply { minWidth = ctx.dp(40) })
        row.addView(bar, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, lp())
        return bar
    }

    private fun colorChanged(fromChip: Boolean) {
        if (fromChip) {
            PenColors.argbToHsv(color, hsv)
            hueBar.progress = Math.round(hsv[0]).coerceIn(0, 360)
            satBar.progress = Math.round(hsv[1] * 100f)
            valBar.progress = Math.round(hsv[2] * 100f)
        }
        custom.chipColor = color
        custom.invalidate()
        for (c in chips) {
            val r = c.chipColor == color
            if (c.ringed != r) {
                c.ringed = r
                c.invalidate()
            }
        }
        preview.set(color, width, pressure)
        emit()
    }

    /** A square colour cell (slightly rounded), ringed white when chosen. */
    private class ColorChip(ctx: Context) : View(ctx) {
        var chipColor = Color.BLACK
        var ringed = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = ctx.dpF(3f)
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF555555.toInt()
            strokeWidth = ctx.dpF(1f)
        }
        private val r = RectF()

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            val inset = context.dpF(3f)
            r.set(inset, inset, width - inset, height - inset)
            fill.color = chipColor
            canvas.drawRoundRect(r, context.dpF(3f), context.dpF(3f), fill)
            canvas.drawRoundRect(r, context.dpF(3f), context.dpF(3f), if (ringed) ring else edge)
        }
    }

    /** A sample S-curve on white paper at the true on-screen width (pressure: thin → thick → thin). */
    private class Preview(ctx: Context, private val highlighter: Boolean, private val pxPerPoint: Float) : View(ctx) {
        private var color = Color.BLACK
        private var widthPt = 1f
        private var pressure = false
        private val path = Path()
        private var pts = FloatArray(0)
        private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val textLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF9A9A9A.toInt()
            strokeWidth = ctx.dpF(4f)
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            if (highlighter) xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
        private val r = RectF()

        fun set(color: Int, widthPt: Float, pressure: Boolean) {
            this.color = color
            this.widthPt = widthPt
            this.pressure = pressure
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            path.rewind()
            if (w <= 0 || h <= 0) return
            val cw = minOf(w.toFloat() * 0.8f, context.dpF(240f))
            val x0 = (w - cw) / 2f
            val amp = h * 0.25f
            val cy = h / 2f
            path.moveTo(x0, cy + amp)
            path.cubicTo(x0 + cw * 0.45f, cy + amp, x0 + cw * 0.30f, cy - amp, x0 + cw * 0.5f, cy)
            path.cubicTo(x0 + cw * 0.70f, cy + amp, x0 + cw * 0.55f, cy - amp, x0 + cw, cy - amp)
            val pm = PathMeasure(path, false)
            val len = pm.length
            val p = FloatArray(2)
            pts = FloatArray(2 * (SEGMENTS + 1))
            for (i in 0..SEGMENTS) {
                pm.getPosTan(len * i / SEGMENTS, p, null)
                pts[2 * i] = p[0]
                pts[2 * i + 1] = p[1]
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            r.set(0f, 0f, w, h)
            canvas.drawRoundRect(r, context.dpF(10f), context.dpF(10f), paper)
            if (highlighter) {
                for (k in 1..3) canvas.drawLine(w * 0.1f, h * k / 4f, w * (if (k == 2) 0.75f else 0.9f), h * k / 4f, textLine)
            }
            val px = (widthPt * pxPerPoint).coerceIn(1f, maxOf(1f, h * 0.8f))
            stroke.color = color
            if (pressure && !highlighter && pts.isNotEmpty()) {
                for (i in 0 until SEGMENTS) {
                    val t = (i + 0.5f) / SEGMENTS
                    stroke.strokeWidth = maxOf(1f, px * InkShape.widthAt(1f, Math.sin(Math.PI * t).toFloat()))
                    canvas.drawLine(pts[2 * i], pts[2 * i + 1], pts[2 * i + 2], pts[2 * i + 3], stroke)
                }
            } else {
                stroke.strokeWidth = px
                canvas.drawPath(path, stroke)
            }
        }

        private companion object {
            const val SEGMENTS = 48
        }
    }

    private companion object {
        const val COLUMNS = 8
    }
}
