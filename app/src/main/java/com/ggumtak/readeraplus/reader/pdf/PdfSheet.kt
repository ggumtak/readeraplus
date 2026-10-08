package com.ggumtak.readeraplus.reader.pdf

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Checkable
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * The PDF viewer's dark bottom sheets (Flexcil-like): a rounded dark card with a title and scrolling rows, and a
 * separate "닫기" bar under it. Rows: section headers, switches with a yellow knob, segmented choices, actions and a
 * labelled slider with − / + buttons. For phones: it slides up, the screen behind dims.
 */
internal object PdfSheet {
    const val SHEET = 0xFF2B2B2B.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SUB = 0xFF9E9E9E.toInt()
    const val ACCENT = 0xFFF5B82E.toInt()
    const val DIVIDER = 0xFF3A3A3A.toInt()
    const val DANGER = 0xFFE53935.toInt()

    /** Shows [content] under [title] (null: no title) as a bottom sheet; returns the dialog. */
    fun show(activity: Activity, title: String?, content: View, onDismiss: (() -> Unit)? = null): Dialog {
        val ctx: Context = activity
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = ctx.vertical {
            background = rounded(ctx, SHEET, 16f)
            setPadding(0, ctx.dp(if (title == null) 8 else 4), 0, ctx.dp(8))
        }
        if (title != null) {
            card.addView(ctx.label(title, 18f, bold = true, color = TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(16), 0, ctx.dp(8))
            }, lp())
        }
        // Room for the title, the 닫기 bar and margins stays on screen, landscape included.
        val maxScroll = (ctx.resources.displayMetrics.heightPixels - ctx.dp(if (title == null) 120 else 170)).coerceAtLeast(ctx.dp(160))
        val scroll = MaxHeightScroll(ctx, maxScroll).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(scroll, lp())
        val close = ctx.label("닫기", 17f, bold = true, color = ACCENT).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dp(56)
            background = rounded(ctx, SHEET, 16f)
            setOnClickListener { dialog.dismiss() }
        }
        val box = ctx.vertical { setPadding(ctx.dp(12), 0, ctx.dp(12), ctx.dp(12)) }
        box.addView(card, lp())
        box.addView(close, lp().apply { topMargin = ctx.dp(10) })
        dialog.setContentView(box)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.45f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        keepBars(dialog, activity)
        return dialog
    }

    /**
     * A full-screen reader keeps its bars hidden while a sheet is open: the sheet's window hides the same system bars
     * as the activity's (else they would show, change the page area and re-render the page).
     */
    private fun keepBars(dialog: Dialog, activity: Activity) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val owner = activity.window ?: return
        val ownerDecor = owner.peekDecorView() ?: return
        val w = dialog.window ?: return
        if (w.peekDecorView() == null) return
        val c = w.insetsController ?: return
        val insets = ownerDecor.rootWindowInsets ?: return
        var hide = 0
        if (!insets.isVisible(android.view.WindowInsets.Type.statusBars())) hide = hide or android.view.WindowInsets.Type.statusBars()
        if (!insets.isVisible(android.view.WindowInsets.Type.navigationBars())) hide = hide or android.view.WindowInsets.Type.navigationBars()
        if (hide != 0) {
            c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(hide)
        }
    }

    fun rounded(ctx: Context, color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = ctx.dpF(radiusDp)
        setColor(color)
    }

    /** A grey section title ("펜 도구"), with a divider above unless [first]. */
    fun section(ctx: Context, text: String, first: Boolean = false): View {
        val v = ctx.vertical()
        if (!first) v.addView(View(ctx).apply { setBackgroundColor(DIVIDER) }, lp(h = 1).apply { topMargin = ctx.dp(8) })
        v.addView(ctx.label(text, 13f, color = SUB).apply { setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(4)) })
        return v
    }

    /** Title (and grey summary) with a [DarkSwitch]; the whole row toggles it. */
    fun switchRow(ctx: Context, title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = DarkSwitch(ctx).apply { isChecked = checked }
        sw.onChange = onChange
        return row(ctx, title, summary, sw) { sw.toggle() }
    }

    /** Title with [options] as segments on the right; the chosen one yellow. */
    fun choiceRow(ctx: Context, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val seg = ctx.horizontal { gravity = Gravity.CENTER_VERTICAL }
        val cells = ArrayList<TextView>()
        fun paint(sel: Int) {
            cells.forEachIndexed { i, c ->
                val on = i == sel
                c.setTextColor(if (on) 0xFF1A1A1A.toInt() else TEXT)
                c.background = rounded(ctx, if (on) ACCENT else 0xFF3C3C3C.toInt(), 8f)
            }
        }
        options.forEachIndexed { i, o ->
            val c = ctx.label(o, 14f, bold = true).apply {
                gravity = Gravity.CENTER
                minWidth = ctx.dp(52)
                minHeight = ctx.dp(36)
                setPadding(ctx.dp(10), 0, ctx.dp(10), 0)
                setOnClickListener {
                    paint(i)
                    onPick(i)
                }
            }
            cells += c
            seg.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(36)).apply { leftMargin = ctx.dp(6) })
        }
        paint(selected)
        return row(ctx, title, null, seg, null)
    }

    /** A tappable row ([danger]: red title). */
    fun actionRow(ctx: Context, title: String, summary: String?, danger: Boolean = false, onClick: () -> Unit): View =
        row(ctx, title, summary, null) { onClick() }.also {
            if (danger) (it.findViewWithTag<TextView>("title"))?.setTextColor(DANGER)
        }

    private fun row(ctx: Context, title: String, summary: String?, trailing: View?, onClick: (() -> Unit)?): LinearLayout {
        val r = ctx.horizontal {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(56)
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8))
            if (onClick != null) setOnClickListener { onClick() }
        }
        val texts = ctx.vertical()
        texts.addView(ctx.label(title, 17f, color = TEXT).apply { tag = "title" })
        if (summary != null) texts.addView(ctx.label(summary, 13f, color = SUB).apply { setPadding(0, ctx.dp(3), 0, 0) })
        r.addView(texts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) r.addView(trailing)
        return r
    }

    /**
     * "펜 두께   −  0.4  +" over a slider ([steps] positions). [format] shows a position's value; [onChange] gets the
     * new position (slider drags and the − / + buttons).
     */
    fun sliderRow(ctx: Context, title: String, steps: Int, position: Int, format: (Int) -> String, onChange: (Int) -> Unit): View {
        val box = ctx.vertical { setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(4)) }
        val head = ctx.horizontal { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ctx.label(title, 17f, color = TEXT), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = ctx.label(format(position), 16f, color = TEXT).apply {
            gravity = Gravity.CENTER
            minWidth = ctx.dp(64)
        }
        val bar = darkSeekBar(ctx, steps, position)
        fun step(by: Int) {
            val p = (bar.progress + by).coerceIn(0, steps)
            if (p == bar.progress) return
            bar.progress = p
            value.text = format(p)
            onChange(p)
        }
        head.addView(ctx.label("−", 24f, color = TEXT).apply {
            gravity = Gravity.CENTER
            minWidth = ctx.dp(40)
            minHeight = ctx.dp(40)
            setOnClickListener { step(-1) }
        })
        head.addView(value)
        head.addView(ctx.label("+", 24f, color = TEXT).apply {
            gravity = Gravity.CENTER
            minWidth = ctx.dp(40)
            minHeight = ctx.dp(40)
            setOnClickListener { step(1) }
        })
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                value.text = format(p)
                onChange(p)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        box.addView(head, lp())
        box.addView(bar, lp())
        return box
    }

    fun darkSeekBar(ctx: Context, max: Int, progress: Int): SeekBar = SeekBar(ctx).apply {
        this.max = max
        this.progress = progress.coerceIn(0, max)
        progressTintList = ColorStateList.valueOf(0xFFBDBDBD.toInt())
        progressBackgroundTintList = ColorStateList.valueOf(0xFF555555.toInt())
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            val d = ctx.dp(22)
            setSize(d, d)
        }
        splitTrack = false
        setPadding(ctx.dp(11), ctx.dp(12), ctx.dp(11), ctx.dp(12))
    }

    /** A ScrollView that never grows past [maxHeight] px (the sheet stays on screen with its 닫기 bar). */
    private class MaxHeightScroll(ctx: Context, private val maxHeight: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }
}

/** Dark on/off switch: grey track and white knob when off, yellow knob on a dim yellow track when on. */
internal class DarkSwitch(context: Context) : View(context), Checkable {
    private var on = false
    private val track = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Called when the state changes through [toggle] / [setChecked]. */
    var onChange: ((Boolean) -> Unit)? = null

    init {
        layoutParams = LinearLayout.LayoutParams(context.dp(56), context.dp(36)).apply { leftMargin = context.dp(8) }
    }

    override fun isChecked(): Boolean = on

    override fun setChecked(checked: Boolean) {
        if (checked == on) return
        on = checked
        invalidate()
        onChange?.invoke(checked)
    }

    override fun toggle() {
        isChecked = !on
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val th = context.dpF(16f)
        track.set(context.dpF(6f), (h - th) / 2f, w - context.dpF(6f), (h + th) / 2f)
        trackPaint.color = if (on) 0x80F5B82E.toInt() else 0xFF4A4A4A.toInt()
        canvas.drawRoundRect(track, th / 2f, th / 2f, trackPaint)
        val r = context.dpF(12f)
        val cx = if (on) w - r else r
        knobPaint.color = if (on) PdfSheet.ACCENT else 0xFFEEEEEE.toInt()
        canvas.drawCircle(cx, h / 2f, r, knobPaint)
    }
}
