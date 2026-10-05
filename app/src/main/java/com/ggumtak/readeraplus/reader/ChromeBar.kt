package com.ggumtak.readeraplus.reader

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
 * first panel child ([panelFrom]): the history row above it stays on the page colour and the band covers its foot,
 * as in ReadEra. Positions come from the children at draw time: no layout listener, nothing measured twice.
 */
internal class ChromeBar(ctx: Context, private val edgeAtTop: Boolean) : LinearLayout(ctx) {
    /** While the bar fades out, touches pass through to the page under it. */
    var inert = false

    /** Index of the first child that is on the panel (the bottom bar's history row is above it, on the page colour). */
    var panelFrom = 0

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
        invalidate()
        return changed
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = if (inert) false else super.dispatchTouchEvent(ev)

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

// ------------------------------------------------------------------ pressed and active looks (U §2.1 State)

/** A pressed overlay fades this long after the finger lifts (phones only). */
private const val PRESS_FADE_MS = 120

/**
 * An icon button's background in [look]: on a phone a 40 dp circle of the pressed colour while pressed, over the
 * accent's circle while [active] (a state that stays: bookmark, pin, rotation lock); on e-ink none, so a press is never
 * a second screen update.
 */
internal fun Context.chromeIconBackground(look: ChromePalette, active: Boolean): Drawable? {
    val on = if (active && look.active != 0) circle(look.active) else null
    if (look.pressed == 0) return on
    val press = pressedList(circle(look.pressed))
    return if (on == null) press else LayerDrawable(arrayOf(on, press))
}

/** A text cell's or a row's pressed overlay in [look], with corners of [radiusDp]; null on e-ink. */
internal fun Context.chromePressed(look: ChromePalette, radiusDp: Float): Drawable? {
    if (look.pressed == 0) return null
    return pressedList(GradientDrawable().apply {
        setColor(look.pressed)
        cornerRadius = dpF(radiusDp)
    })
}

private fun pressedList(d: Drawable): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), d)
    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    setExitFadeDuration(PRESS_FADE_MS)
}

/** A 40 dp circle centred in a 48 dp button. */
private fun Context.circle(color: Int): Drawable =
    InsetDrawable(GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }, dp(4))
