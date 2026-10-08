package com.ggumtak.readeraplus.reader.pdf

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import com.ggumtak.readeraplus.ui.kit.InkToggle
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.keepAll
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * Settings of one drawing tool: thickness, colour (palette + custom HSV) and, for the pen, pressure.
 * Every change is reported at once through [onChange] (the caller stores and applies it); the panel closes with 닫기.
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
) {
    private var color: Int = color or (0xFF shl 24)
    private var width: Float = width.coerceIn(minOf(minWidth, maxWidth), maxOf(minWidth, maxWidth))
    private val hasPressure: Boolean = pressure != null
    private var pressure: Boolean = pressure ?: false

    private val hsv = FloatArray(3)
    private val swatches = ArrayList<SwatchView>()
    private lateinit var preview: PreviewView
    private lateinit var widthLabel: android.widget.TextView
    private lateinit var customSwatch: SwatchView
    private lateinit var hueBar: SeekBar
    private lateinit var satBar: SeekBar
    private lateinit var valBar: SeekBar

    /** Builds the panel and shows it as a dialog without animation. */
    fun show() {
        PenColors.argbToHsv(color, hsv)
        activity.alert()
            .setTitle(title)
            .setView(buildContent())
            .setPositiveButton("닫기", null)
            .showNoAnim()
    }

    private fun emit() {
        onChange(color, width, pressure)
    }

    private fun buildContent(): View {
        val ctx: Context = activity
        val col = ctx.vertical { setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8)) }

        preview = PreviewView(ctx, highlighter, pxPerPoint).also { it.set(color, width, pressure) }
        col.addView(preview, lp(h = ctx.dp(96)))

        // Thickness
        widthLabel = ctx.label("굵기 ${PenColors.formatWidth(width)}", 16f, bold = true).apply { setPadding(0, ctx.dp(16), 0, 0) }
        col.addView(widthLabel)
        val widthBar = styledSeekBar(ctx, WIDTH_STEPS, PenColors.widthToProgress(width, WIDTH_STEPS, minWidth, maxWidth)) { p ->
            val w = PenColors.progressToWidth(p, WIDTH_STEPS, minWidth, maxWidth)
            if (w != width) {
                width = w
                widthLabel.text = "굵기 ${PenColors.formatWidth(w)}"
                preview.set(color, width, pressure)
                emit()
            }
        }
        col.addView(widthBar, lp())

        // Colour
        col.addView(ctx.label("색상", 16f, bold = true).apply { setPadding(0, ctx.dp(16), 0, ctx.dp(8)) })
        if (recent.isNotEmpty()) {
            col.addView(ctx.label("최근 사용", 13f, color = Ink.GRAY).apply { setPadding(0, 0, 0, ctx.dp(2)) })
            col.addView(swatchGrid(ctx, recent.copyOf(minOf(recent.size, GRID_COLUMNS))), lp())
            col.addView(View(ctx), lp(h = ctx.dp(8)))
        }
        col.addView(swatchGrid(ctx, if (highlighter) PenColors.HIGHLIGHTER_PALETTE else PenColors.PEN_PALETTE), lp())

        // Custom colour
        val customHead = ctx.horizontal { setPadding(0, ctx.dp(16), 0, ctx.dp(4)) }
        customHead.addView(ctx.label("직접 고르기", 16f, bold = true), lp(0, WRAP_CONTENT, 1f))
        customSwatch = SwatchView(ctx).also { it.swatchColor = color }
        customHead.addView(customSwatch, lp(ctx.dp(36), ctx.dp(36)))
        col.addView(customHead, lp())
        hueBar = hsvRow(ctx, col, "색상", 360, Math.round(hsv[0]).coerceIn(0, 360)) { p -> hsv[0] = p.toFloat() }
        satBar = hsvRow(ctx, col, "채도", 100, Math.round(hsv[1] * 100f)) { p -> hsv[1] = p / 100f }
        valBar = hsvRow(ctx, col, "밝기", 100, Math.round(hsv[2] * 100f)) { p -> hsv[2] = p / 100f }

        if (hasPressure) col.addView(pressureRow(ctx), lp())

        return ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            addView(col, lp())
        }
    }

    private fun pressureRow(ctx: Context): View {
        val toggle = InkToggle(ctx).apply { isChecked = pressure }
        toggle.onChange = { on ->
            pressure = on
            preview.set(color, width, pressure)
            emit()
        }
        val texts = ctx.vertical()
        texts.addView(ctx.label("펜 압력 감지", 16f, bold = true))
        texts.addView(
            ctx.label(keepAll("스타일러스 펜만, 누르는 힘에 따라 굵기가 변합니다"), 14f, color = Ink.GRAY).apply { setPadding(0, ctx.dp(3), 0, 0) },
        )
        return ctx.horizontal {
            minimumHeight = ctx.dp(56)
            setPadding(0, ctx.dp(16), 0, ctx.dp(8))
            background = pressableBackground()
            setOnClickListener { toggle.toggle() }
            addView(texts, lp(0, WRAP_CONTENT, 1f))
            addView(toggle)
        }
    }

    /** Adds "label + slider" to [parent]; [store] writes the user's value into [hsv], then the colour is rebuilt. */
    private fun hsvRow(ctx: Context, parent: LinearLayout, name: String, max: Int, progress: Int, store: (Int) -> Unit): SeekBar {
        val bar = styledSeekBar(ctx, max, progress.coerceIn(0, max)) { p ->
            store(p)
            color = PenColors.hsvToArgb(hsv[0], hsv[1], hsv[2])
            colorChanged(fromPalette = false)
        }
        val row = ctx.horizontal()
        row.addView(ctx.label(name, 15f, color = Ink.GRAY).apply { minWidth = ctx.dp(44) })
        row.addView(bar, lp(0, WRAP_CONTENT, 1f))
        parent.addView(row, lp())
        return bar
    }

    private fun colorChanged(fromPalette: Boolean) {
        if (fromPalette) {
            PenColors.argbToHsv(color, hsv)
            hueBar.progress = Math.round(hsv[0]).coerceIn(0, 360)
            satBar.progress = Math.round(hsv[1] * 100f)
            valBar.progress = Math.round(hsv[2] * 100f)
        }
        customSwatch.swatchColor = color
        customSwatch.invalidate()
        for (s in swatches) {
            val ringed = s.swatchColor == color
            if (s.ringed != ringed) {
                s.ringed = ringed
                s.invalidate()
            }
        }
        preview.set(color, width, pressure)
        emit()
    }

    private fun swatchGrid(ctx: Context, colors: IntArray): View {
        val grid = ctx.vertical()
        var i = 0
        while (i < colors.size) {
            val row = ctx.horizontal()
            for (k in 0 until GRID_COLUMNS) {
                val idx = i + k
                if (idx < colors.size) {
                    val c = colors[idx] or (0xFF shl 24)
                    val sw = SwatchView(ctx).apply {
                        swatchColor = c
                        ringed = c == color
                        setOnClickListener {
                            color = c
                            colorChanged(fromPalette = true)
                        }
                    }
                    swatches.add(sw)
                    row.addView(sw, lp(0, WRAP_CONTENT, 1f))
                } else {
                    row.addView(View(ctx), lp(0, 1, 1f))
                }
            }
            grid.addView(row, lp())
            i += GRID_COLUMNS
        }
        return grid
    }

    /** Slider with a plain black dot thumb (the platform thumb animates, which is noise even off e-ink). */
    private fun styledSeekBar(ctx: Context, max: Int, progress: Int, onUser: (Int) -> Unit): SeekBar = SeekBar(ctx).apply {
        this.max = max
        this.progress = progress
        progressTintList = ColorStateList.valueOf(Ink.BLACK)
        progressBackgroundTintList = ColorStateList.valueOf(Ink.GRAY)
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Ink.BLACK)
            val d = ctx.dp(22)
            setSize(d, d)
        }
        thumbOffset = ctx.dp(11)
        splitTrack = false
        setPadding(ctx.dp(11), ctx.dp(10), ctx.dp(11), ctx.dp(10))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onUser(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    /** Round colour chip; square (height follows the measured width), ringed when it is the current colour. */
    private class SwatchView(ctx: Context) : View(ctx) {
        var swatchColor: Int = Color.BLACK
        var ringed: Boolean = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF888888.toInt()
            strokeWidth = ctx.dpF(1f).coerceAtLeast(1f)
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Ink.BLACK
            strokeWidth = ctx.dpF(3f)
        }
        private val inset = ctx.dpF(6f)

        init {
            isClickable = true
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            val mode = MeasureSpec.getMode(widthSpec)
            val size = if (mode == MeasureSpec.UNSPECIFIED) context.dp(40) else w
            setMeasuredDimension(size, size)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            fill.color = swatchColor
            canvas.drawCircle(cx, cy, r - inset, fill)
            canvas.drawCircle(cx, cy, r - inset, edge)
            if (ringed) canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f - 1f, ring)
        }
    }

    /** Sample S-curve drawn at the true on-screen size of the current thickness. */
    private class PreviewView(ctx: Context, private val highlighter: Boolean, private val pxPerPoint: Float) : View(ctx) {
        private var color = Color.BLACK
        private var widthPt = 1f
        private var pressure = false

        private val path = Path()
        private var pts = FloatArray(0)
        private val bg = Paint().apply { color = Ink.WHITE }
        private val border = Paint().apply {
            style = Paint.Style.STROKE
            color = Ink.LINE_LIGHT
            strokeWidth = ctx.dpF(1f).coerceAtLeast(1f)
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF8A8A8A.toInt()
            strokeCap = Paint.Cap.BUTT
            strokeWidth = ctx.dpF(5f)
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = if (highlighter) Paint.Cap.BUTT else Paint.Cap.ROUND
            if (highlighter) xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }

        fun set(color: Int, widthPt: Float, pressure: Boolean) {
            this.color = color
            this.widthPt = widthPt
            this.pressure = pressure
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            path.rewind()
            if (w <= 0 || h <= 0) return
            val curveW = minOf(w.toFloat(), context.dpF(200f))
            val x0 = (w - curveW) / 2f
            val amp = h * 0.28f
            val cy = h / 2f
            path.moveTo(x0, cy + amp)
            path.cubicTo(x0 + curveW * 0.45f, cy + amp, x0 + curveW * 0.30f, cy - amp, x0 + curveW * 0.5f, cy)
            path.cubicTo(x0 + curveW * 0.70f, cy + amp, x0 + curveW * 0.55f, cy - amp, x0 + curveW, cy - amp)
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
            canvas.drawRect(0f, 0f, w, h, bg)
            if (highlighter) {
                for (k in 1..3) {
                    val y = h * k / 4f
                    canvas.drawLine(w * 0.08f, y, w * (if (k == 2) 0.78f else 0.92f), y, text)
                }
            }
            val px = (widthPt * pxPerPoint).coerceIn(1f, maxOf(1f, h * 0.9f))
            stroke.color = color
            if (pressure && !highlighter && pts.isNotEmpty()) {
                for (i in 0 until SEGMENTS) {
                    // thin -> thick -> thin: 40% at both ends, 100% in the middle
                    val t = (i + 0.5f) / SEGMENTS
                    val f = 0.4f + 0.6f * Math.sin(Math.PI * t).toFloat()
                    stroke.strokeWidth = maxOf(1f, px * f)
                    canvas.drawLine(pts[2 * i], pts[2 * i + 1], pts[2 * i + 2], pts[2 * i + 3], stroke)
                }
            } else {
                stroke.strokeWidth = px
                canvas.drawPath(path, stroke)
            }
            canvas.drawRect(0f, 0f, w - 1f, h - 1f, border)
        }

        private companion object {
            const val SEGMENTS = 48
        }
    }

    private companion object {
        const val WIDTH_STEPS = 100
        const val GRID_COLUMNS = 8
    }
}
