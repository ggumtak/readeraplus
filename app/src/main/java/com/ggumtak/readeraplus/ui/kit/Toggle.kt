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
 * Not clickable itself: the surrounding row toggles it.
 */
class InkToggle(context: Context) : View(context), Checkable {
    private var checkedState = false
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
        fill.color = if (checkedState) Ink.BLACK else Ink.WHITE
        canvas.drawRoundRect(track, r, r, fill)
        canvas.drawRoundRect(track, r, r, outline)
        val kr = r - context.dpF(4f)
        val cx = if (checkedState) track.right - r else track.left + r
        val cy = track.centerY()
        knob.color = if (checkedState) Ink.WHITE else Ink.BLACK
        canvas.drawCircle(cx, cy, kr, knob)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = checkedState
        info.className = "android.widget.Switch"
    }
}
