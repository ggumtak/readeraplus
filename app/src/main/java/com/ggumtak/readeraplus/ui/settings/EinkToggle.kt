package com.ggumtak.readeraplus.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import android.widget.LinearLayout
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.row

/**
 * On/off toggle drawn in pure black and white that changes state instantly. The platform `Switch` used by
 * `ui.kit.switchRow` slides its thumb for 250 ms, plays an animated-selector transition and has a borderless
 * ripple background, which leave ghosting on e-ink. Off = outlined track with a hollow knob on the left;
 * on = black track with a white knob on the right. Not clickable itself: the row toggles it.
 */
internal class EinkToggle(context: Context) : View(context), Checkable {
    private var checkedState = false

    private val stroke = context.dpF(1.5f).coerceAtLeast(1f)
    private val track = RectF()
    private val trackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ink.BLACK
        strokeWidth = stroke
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Ink.WHITE }

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
        trackFill.color = if (checkedState) Ink.BLACK else Ink.WHITE
        canvas.drawRoundRect(track, r, r, trackFill)
        canvas.drawRoundRect(track, r, r, outline)
        val kr = r - context.dpF(4f)
        val cx = if (checkedState) track.right - r else track.left + r
        val cy = track.centerY()
        canvas.drawCircle(cx, cy, kr, knob)
        if (!checkedState) canvas.drawCircle(cx, cy, kr, outline)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = checkedState
        info.className = "android.widget.Switch"
    }
}

/**
 * Settings row with an [EinkToggle] on the right (e-ink replacement for `ui.kit.switchRow`, same layout).
 * Tapping anywhere on the row flips the toggle and calls [onChange] with the new value.
 */
internal fun Context.toggleRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
    val t = EinkToggle(this).apply { isChecked = checked }
    return row(title, summary, t) {
        t.toggle()
        onChange(t.isChecked)
    }
}
