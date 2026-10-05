package com.ggumtak.readeraplus.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import android.widget.LinearLayout

/**
 * On/off switch that changes state instantly (no thumb animation, no ripple) for e-ink.
 * Checked = black track with a white knob on the right; unchecked = white track, black knob on the left.
 * [setColors] gives it another set (the reader chrome's theme colours). Not clickable itself: the surrounding row
 * toggles it.
 */
class InkToggle(context: Context) : View(context), Checkable {
    private var checkedState = false
    private var inkColor = Ink.BLACK
    private var paperColor = Ink.WHITE
    private var onColor = Ink.BLACK
    private val stroke = context.dpF(1.5f).coerceAtLeast(1f)
    private val track = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ink.BLACK
        strokeWidth = stroke
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Ink.WHITE }

    /** Called when the state changes through [toggle] / [setChecked]. */
    var onChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        layoutParams = LinearLayout.LayoutParams(context.dp(52), context.dp(32)).apply { leftMargin = context.dp(8) }
    }

    /**
     * [ink] = the outline and the unchecked knob, [paper] = the unchecked track and the checked knob, [on] = the
     * checked track. The defaults are black, white, black.
     */
    fun setColors(ink: Int, paper: Int, on: Int) {
        if (ink == inkColor && paper == paperColor && on == onColor) return
        inkColor = ink
        paperColor = paper
        onColor = on
        outline.color = ink
        invalidate()
    }

    override fun isChecked(): Boolean = checkedState

    override fun setChecked(checked: Boolean) {
        if (checked == checkedState) return
        checkedState = checked
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        onChange?.invoke(checked)
    }

    override fun toggle() {
        isChecked = !checkedState
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val th = minOf(h, context.dpF(24f))
        val half = stroke / 2f
        track.set(half, (h - th) / 2f + half, w - half, (h + th) / 2f - half)
        val r = track.height() / 2f
        fill.color = if (checkedState) onColor else paperColor
        canvas.drawRoundRect(track, r, r, fill)
        canvas.drawRoundRect(track, r, r, outline)
        val kr = r - context.dpF(4f)
        val cx = if (checkedState) track.right - r else track.left + r
        val cy = track.centerY()
        knob.color = if (checkedState) paperColor else inkColor
        canvas.drawCircle(cx, cy, kr, knob)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = checkedState
        info.className = "android.widget.Switch"
    }
}
