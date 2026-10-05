package com.ggumtak.readeraplus.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.MotionEvent
import android.widget.LinearLayout
import com.ggumtak.readeraplus.render.ChromePalette
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF

/**
 * One of the reader's two bars (U §2.1): a vertical LinearLayout that paints its own panel and the edge where the
 * panel meets the page, so neither needs a view of its own. The panel is filled with the surface colour up to the
 * screen edge (the top bar through its inset padding, the bottom bar through the gesture strip). The edge is a band
 * of [edgeArea] px drawn over the page side after the children: a short shadow fading into the page
 * ([ChromePalette.shadow], phones) or a solid 1 px line ([ChromePalette.edge]: e-ink, and 흑백 반전, where a shadow
 * would not show). The band
 * lies in the bar's own padding on the page side (the owner pads it by [edgeArea]); the bottom bar's starts at its
 * first panel child ([panelFrom]): the history row above it stays on the page colour, starts over that padding
 * ([fitLead]) and the band covers its foot, as in ReadEra. Positions come from the children at draw time: no layout
 * listener, nothing measured twice.
 */
internal class ChromeBar(ctx: Context, private val edgeAtTop: Boolean) : LinearLayout(ctx) {
    /**
     * While the bar fades out, a new touch passes through to the page under it. A gesture that started on the bar
     * before still reaches its end there (a slider drag gets its lift, as when the bar went GONE at once).
     */
    var inert = false

    /** Index of the first child that is on the panel (the bottom bar's history row is above it, on the page colour). */
    var panelFrom = 0
        set(value) {
            field = value
            fitLead()
        }

    /** Height of the edge band: [ChromePalette.SHADOW_DP] with a shadow, 1 px with a line, else 0. */
    var edgeArea = 1
        private set

    private var surface = 0
    private var shadow = 0
    private var edge = 0
    private val fill = Paint()
    private val band = Paint()
    // The shader is built for one band position and colour, and rebuilt only when either changes.
    private var shaderY = Int.MIN_VALUE
    private var shaderColor = 0

    init {
        orientation = VERTICAL
        isClickable = true
        setWillNotDraw(false)
        setLook(ChromePalette.DEFAULT)
    }

    /** The bar's colours; true when [edgeArea] changed (the owner then pads the bar again). */
    fun setLook(look: ChromePalette): Boolean {
        val area = when {
            look.shadow != 0 -> context.dp(ChromePalette.SHADOW_DP)
            look.edge != 0 -> 1
            else -> 0
        }
        if (look.surface == surface && look.shadow == shadow && look.edge == edge && area == edgeArea) return false
        surface = look.surface
        shadow = look.shadow
        edge = look.edge
        fill.color = surface
        band.shader = null
        // A shader is drawn with the paint's alpha: opaque for the shadow, the line's own colour otherwise.
        band.color = if (shadow != 0) Color.BLACK else edge
        shaderY = Int.MIN_VALUE
        val changed = area != edgeArea
        edgeArea = area
        if (changed) fitLead()
        invalidate()
        return changed
    }

    /**
     * The row above the panel (child 0 when [panelFrom] > 0) starts [edgeArea] higher, over the bar's edge padding: no
     * strip of bare page is left above it (the clickable bar would swallow its taps there), and the band covers the
     * row's foot. Without the row, the band lies in that padding. Set once per [edgeArea], never in a layout pass.
     */
    private fun fitLead() {
        if (!edgeAtTop || panelFrom <= 0 || childCount == 0) return
        val c = getChildAt(0)
        val lp = c.layoutParams as? LayoutParams ?: return
        if (lp.topMargin == -edgeArea) return
        lp.topMargin = -edgeArea
        c.layoutParams = lp
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        if (inert && ev.actionMasked == MotionEvent.ACTION_DOWN) false else super.dispatchTouchEvent(ev)

    override fun onDraw(canvas: Canvas) {
        val from = if (edgeAtTop) panelTop() else 0
        val to = if (edgeAtTop) height else height - edgeArea
        if (to > from) canvas.drawRect(0f, from.toFloat(), width.toFloat(), to.toFloat(), fill)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (edgeArea <= 0) return
        val y = if (edgeAtTop) panelTop() - edgeArea else height - edgeArea
        if (shadow != 0 && (y != shaderY || shadow != shaderColor)) {
            // Strongest at the panel, gone at the far side of the band.
            val clear = shadow and 0x00FFFFFF
            val atTop = if (edgeAtTop) clear else shadow
            val atBottom = if (edgeAtTop) shadow else clear
            val y1 = (y + edgeArea).toFloat()
            band.shader = LinearGradient(0f, y.toFloat(), 0f, y1, atTop, atBottom, Shader.TileMode.CLAMP)
            shaderY = y
            shaderColor = shadow
        }
        canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + edgeArea).toFloat(), band)
    }

    /** Top of the first shown panel child: the panel starts there (the bar's own end when none is shown). */
    private fun panelTop(): Int {
        for (i in panelFrom until childCount) {
            val c = getChildAt(i)
            if (c.visibility != GONE) return c.top
        }
        return height - paddingBottom
    }
}

/**
 * The system's animator duration scale (0 = animations off: 개발자 옵션, 접근성 "애니메이션 제거"). The scale itself is
 * public from API 33; before that only whether animators run at all (API 26).
 */
internal fun animatorScale(): Float = when {
    Build.VERSION.SDK_INT >= 33 -> ValueAnimator.getDurationScale()
    ValueAnimator.areAnimatorsEnabled() -> 1f
    else -> 0f
}

// ------------------------------------------------------------------ pressed and active looks (U §2.1 State)

/** A pressed overlay fades this long after the finger lifts (phones; at once with the system's animations off). */
private const val PRESS_FADE_MS = 120

/**
 * An icon button's background in [look]: on a phone a 40 dp circle of the pressed colour while pressed, over the
 * accent's circle while [active] (a state that stays: bookmark, pin, rotation lock); on e-ink none, so a press is never
 * a second screen update.
 */
internal fun Context.chromeIconBackground(look: ChromePalette, active: Boolean): Drawable? {
    val on = if (active && look.active != 0) circle(look.active) else null
    if (look.pressed == 0) return on
    val press = PressedList(circle(look.pressed))
    // Stacked, not nested: the pressed circle is the active one's size, and the padding stays 4 dp either way (a
    // toggle never changes the button's padding, so it never lays the bar out again).
    if (on == null) return press
    return LayerDrawable(arrayOf(on, press)).apply { paddingMode = LayerDrawable.PADDING_MODE_STACK }
}

/** A text cell's or a row's pressed overlay in [look], with corners of [radiusDp]; null on e-ink. */
internal fun Context.chromePressed(look: ChromePalette, radiusDp: Float): Drawable? {
    if (look.pressed == 0) return null
    return PressedList(GradientDrawable().apply {
        setColor(look.pressed)
        cornerRadius = dpF(radiusDp)
    })
}

/** [d] while pressed, else nothing; fading out in [PRESS_FADE_MS], or at once while the system's animations are off. */
private class PressedList(d: Drawable) : StateListDrawable() {
    init {
        addState(intArrayOf(android.R.attr.state_pressed), d)
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    override fun onStateChange(stateSet: IntArray): Boolean {
        // Read at each change: 애니메이션 제거 may be switched while a book is open.
        setExitFadeDuration(if (ChromeMath.animates(true, animatorScale())) PRESS_FADE_MS else 0)
        return super.onStateChange(stateSet)
    }
}

/** A 40 dp circle centred in a 48 dp button. */
private fun Context.circle(color: Int): Drawable =
    InsetDrawable(GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }, dp(4))
