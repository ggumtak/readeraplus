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
 * of [edgeArea] px drawn over the page side after the children: a solid 1 px hairline in the bar's own tone
 * ([ChromePalette.topEdge] for the top bar, [ChromePalette.bottomEdge] for the bottom one) or, were a look to set
 * [ChromePalette.shadow], a short shadow fading into the page. Rows outside the panel sit on the page colour. The bottom bar's history row is above
 * its first panel child ([panelFrom]): it starts over the bar's top padding, where the band lies without it
 * ([fitLead]), and the band covers its foot. The top bar's rows from [pageFrom] on (the brightness row and its options)
 * are filled with the page colour ([ChromePalette.page], the very pixels of the page): the band covers their head, and
 * the bar's bottom padding, while the owner gives one (the options are open), holds a second band under them. Without
 * [pageFrom] the whole top bar is the panel and its band lies in that padding. Positions come from the children at draw
 * time: no layout listener, nothing measured twice.
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

    /**
     * Top bar: index of the first child on the page colour; the panel ends at its top. Out of range (the default):
     * every child is on the panel.
     */
    var pageFrom = Int.MAX_VALUE

    /** Height of the edge band: [ChromePalette.SHADOW_DP] with a shadow, 1 px with a line, else 0. */
    var edgeArea = 1
        private set

    private var surface = 0
    private var page = 0
    private var shadow = 0
    private var edge = 0
    private val fill = Paint()
    private val pageFill = Paint()
    /** The band, drawn translated to its place: a shadow's gradient is built once per look, from 0 to [edgeArea]. */
    private val band = Paint()

    init {
        orientation = VERTICAL
        isClickable = true
        setWillNotDraw(false)
        setLook(ChromePalette.DEFAULT)
    }

    /** The bar's colours; true when [edgeArea] changed (the owner then pads the bar again). */
    fun setLook(look: ChromePalette): Boolean {
        // The bottom bar's edge is at its top (edgeAtTop); the top bar's at its foot.
        val line = if (edgeAtTop) look.bottomEdge else look.topEdge
        val area = when {
            look.shadow != 0 -> context.dp(ChromePalette.SHADOW_DP)
            line != 0 -> 1
            else -> 0
        }
        if (look.surface == surface && look.page == page && look.shadow == shadow && line == edge &&
            area == edgeArea) return false
        surface = look.surface
        page = look.page
        shadow = look.shadow
        edge = line
        fill.color = surface
        pageFill.color = page
        // A shader is drawn with the paint's alpha: opaque for the shadow, the line's own colour otherwise.
        band.color = if (shadow != 0) Color.BLACK else edge
        band.shader = if (shadow != 0) {
            // Strongest at the panel, gone at the far side of the band.
            val clear = shadow and 0x00FFFFFF
            val atTop = if (edgeAtTop) clear else shadow
            val atBottom = if (edgeAtTop) shadow else clear
            LinearGradient(0f, 0f, 0f, area.toFloat(), atTop, atBottom, Shader.TileMode.CLAMP)
        } else {
            null
        }
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
        val w = width.toFloat()
        if (edgeAtTop) {
            val from = panelTop()
            if (height > from) canvas.drawRect(0f, from.toFloat(), w, height.toFloat(), fill)
            return
        }
        // The bottom padding only ever holds the band, over the page.
        val end = height - paddingBottom
        val split = pageTop()
        if (split > 0) canvas.drawRect(0f, 0f, w, split.toFloat(), fill)
        if (end > split) canvas.drawRect(0f, split.toFloat(), w, end.toFloat(), pageFill)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (edgeArea <= 0) return
        if (edgeAtTop) {
            drawBand(canvas, panelTop() - edgeArea)
            return
        }
        val end = height - paddingBottom
        val split = pageTop()
        if (split < end) drawBand(canvas, split)
        if (paddingBottom > 0) drawBand(canvas, end)
    }

    private fun drawBand(canvas: Canvas, y: Int) {
        val saved = canvas.save()
        canvas.translate(0f, y.toFloat())
        canvas.drawRect(0f, 0f, width.toFloat(), edgeArea.toFloat(), band)
        canvas.restoreToCount(saved)
    }

    /** Top of the first shown panel child: the panel starts there (the bar's own end when none is shown). */
    private fun panelTop(): Int {
        for (i in panelFrom until childCount) {
            val c = getChildAt(i)
            if (c.visibility != GONE) return c.top
        }
        return height - paddingBottom
    }

    /** Top of the first shown child on the page colour: the panel ends there (at the bottom padding when none is). */
    private fun pageTop(): Int {
        for (i in pageFrom until childCount) {
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
 * active circle while [active] and the look has one (none now: bookmark, pin and rotation lock swap their icons); on
 * e-ink none, so a press is never a second screen update.
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

/**
 * A text cell's or a row's pressed overlay in [look], with corners of [radiusDp], starting [topInset] px under the
 * view's top (a cell whose text sits low in its box: the rect still hugs the words); null on e-ink.
 */
internal fun Context.chromePressed(look: ChromePalette, radiusDp: Float, topInset: Int = 0): Drawable? {
    if (look.pressed == 0) return null
    val rect = GradientDrawable().apply {
        setColor(look.pressed)
        cornerRadius = dpF(radiusDp)
    }
    // A layer inset, not an InsetDrawable: an inset counts as the drawable's padding, which would replace the view's own.
    val inner: Drawable = if (topInset > 0) LayerDrawable(arrayOf(rect)).apply { setLayerInsetTop(0, topInset) } else rect
    return PressedList(inner)
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
