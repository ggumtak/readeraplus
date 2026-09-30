package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.vertical

/*
 * Compact building blocks of the reading-settings popup, sized for the ~6" 360×720 dp e-ink screen: plain rows
 * (min 36 dp) split by 1px lines, 14 sp labels, 15 sp values, 36 dp stepper buttons on the label's own row, 12 sp
 * bold section headers, inverted toggle buttons, and a small drop-down list. No card boxes, no animations.
 */
internal object Compact {
    /**
     * Row height: the popup's main section ([PopupGeometry.MAIN_ROWS] rows) is 360 dp, half the Comet's height, so it
     * fits under the 55% cap without scrolling and leaves the lower half of the page in view for the preview.
     */
    const val ROW_DP = 36
    const val LABEL_SP = 14f
    const val VALUE_SP = 15f
    const val SUMMARY_SP = 12f
    const val HEADER_SP = 12f
    const val TOGGLE_SP = 13f
    const val STEP_DP = 36
    const val PAD_DP = 12
    const val LIST_ROW_DP = 40
    const val LIST_SP = 15f
}

/** Row background: white (gray while pressed when [pressable]) with a 1px line along the top when [topLine]. */
internal fun Context.compactRowBackground(pressable: Boolean, topLine: Boolean): Drawable {
    val base: Drawable = if (pressable) pressableBackground(Ink.WHITE) else ColorDrawable(Ink.WHITE)
    if (!topLine) return base
    return LayerDrawable(arrayOf(base, ColorDrawable(Ink.LINE))).apply {
        setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
        setLayerHeight(1, 1)
    }
}

/**
 * A horizontal settings row (min [Compact.ROW_DP], 12 dp side padding, no vertical padding: a 36 dp stepper button
 * makes a row of exactly 36 dp); clickable (pressed = gray) when [onClick] is set.
 */
internal fun Context.compactRow(topLine: Boolean = true, onClick: ((View) -> Unit)? = null): LinearLayout = horizontal {
    minimumHeight = dp(Compact.ROW_DP)
    setPadding(dp(Compact.PAD_DP), 0, dp(Compact.PAD_DP - 4), 0)
    background = compactRowBackground(onClick != null, topLine)
    if (onClick != null) setOnClickListener(onClick)
}

/** 12 sp bold section header ("글자", "페이지", "TXT 파일" …). */
internal fun Context.compactHeader(text: String): TextView = label(text, Compact.HEADER_SP, bold = true, color = Ink.GRAY).apply {
    setPadding(dp(Compact.PAD_DP), dp(10), dp(Compact.PAD_DP), dp(4))
}

/** The 14 sp row label (left side), optionally with a 12 sp gray summary under it. */
internal fun Context.compactLabelBlock(title: String, summary: String? = null): View {
    val t = label(title, Compact.LABEL_SP, maxLines = 2)
    if (summary == null) return t
    return vertical {
        setPadding(0, dp(4), 0, dp(4))
        addView(t)
        addView(label(summary, Compact.SUMMARY_SP, color = Ink.GRAY, maxLines = 2).apply { setPadding(0, dp(1), 0, 0) })
    }
}

/** 36 dp flat icon button (stepper − / +). */
internal fun Context.compactIcon(iconRes: Int, description: String, onClick: (View) -> Unit): ImageButton =
    flatIcon(iconRes, description, sizeDp = Compact.STEP_DP, onClick = onClick)

/**
 * A small bordered text toggle (style presets, 정렬, 줄바꿈): selected = black background with white text.
 * Update with [setCompactToggle].
 */
internal fun Context.compactToggle(text: String, selected: Boolean, onClick: (View) -> Unit): TextView =
    label(text, Compact.TOGGLE_SP, maxLines = 1).apply {
        gravity = Gravity.CENTER
        minHeight = dp(28)
        minWidth = dp(40)
        setPadding(dp(7), 0, dp(7), 0)
        // Reserve the BOLD (selected) width up front: selecting a toggle never widens it and shifts its neighbours.
        val boldWidth = android.text.TextPaint(paint).apply { typeface = Typeface.DEFAULT_BOLD }.measureText(text)
        minWidth = maxOf(dp(40), Math.ceil(boldWidth.toDouble()).toInt() + paddingLeft + paddingRight)
        setOnClickListener(onClick)
        setCompactToggle(this, selected)
    }

internal fun setCompactToggle(v: TextView, selected: Boolean) {
    val ctx = v.context
    if (v.isSelected == selected && v.background != null) return
    v.isSelected = selected
    v.background = ctx.borderBox(if (selected) Ink.BLACK else Ink.WHITE, radiusDp = 3f)
    v.setTextColor(if (selected) Ink.WHITE else Ink.BLACK)
    v.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

/** One entry of a [CompactList]. [checked] null = no radio mark; [typeface] renders the label in that face. */
internal class ListEntry(
    val label: String,
    val checked: Boolean? = null,
    val typeface: Typeface? = null,
    val note: String? = null,
    val action: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Compact drop-down list for the settings popup: 40 dp rows, 15 sp text, [widthPx] wide (≤ the popup), at most
 * 55% of the screen tall (scrolls), its right edge [rightInsetPx] inside [anchor]'s, under the anchor (or above it
 * when there is no room below). The checked row is scrolled into view. Tracked for [ReaderPanels.dismissAll].
 * Returns null when it cannot be shown.
 */
internal object CompactList {
    fun show(activity: Activity, anchor: View, entries: List<ListEntry>, widthPx: Int, rightInsetPx: Int = 0): PopupWindow? {
        if (entries.isEmpty() || !anchor.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) return null
        val decor = activity.window?.decorView ?: return null
        val dm = activity.resources.displayMetrics
        val screenH = decor.height.takeIf { it > 0 } ?: dm.heightPixels
        val screenW = decor.width.takeIf { it > 0 } ?: dm.widthPixels
        val width = widthPx.coerceIn(1, screenW)

        lateinit var popup: PopupWindow
        val list = activity.vertical { setBackgroundColor(Ink.WHITE) }
        var checkedRow: View? = null
        entries.forEachIndexed { i, e ->
            val row = activity.horizontal {
                minimumHeight = activity.dp(Compact.LIST_ROW_DP)
                setPadding(activity.dp(Compact.PAD_DP), activity.dp(2), activity.dp(10), activity.dp(2))
                background = activity.compactRowBackground(pressable = true, topLine = i > 0 && (e.action && !entries[i - 1].action))
                setOnClickListener {
                    popup.dismiss()
                    e.onClick()
                }
            }
            val text = activity.label(e.label, Compact.LIST_SP, bold = e.checked == true || e.action).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                if (e.typeface != null) typeface = if (e.checked == true) Typeface.create(e.typeface, Typeface.BOLD) else e.typeface
            }
            row.addView(text, lp(0, WRAP_CONTENT, 1f))
            if (e.note != null) row.addView(activity.label(e.note, Compact.SUMMARY_SP, color = Ink.GRAY, maxLines = 1).apply {
                setPadding(activity.dp(6), 0, activity.dp(4), 0)
            })
            if (e.checked != null) {
                row.addView(activity.icon(if (e.checked) R.drawable.ic_radio_button_checked else R.drawable.ic_radio_button_unchecked, 18).apply {
                    (layoutParams as LinearLayout.LayoutParams).leftMargin = activity.dp(6)
                })
            }
            if (e.checked == true) checkedRow = row
            list.addView(row, lp())
        }
        val maxH = (screenH * PopupGeometry.HEIGHT_FRACTION).toInt()
        val scroll = MaxHeightScrollView(activity, maxH).apply { isVerticalScrollBarEnabled = true }
        scroll.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val frame = FrameLayout(activity).apply {
            background = activity.borderBox()
            setPadding(1, 1, 1, 1)
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST),
        )
        // Anchor rect in the reader window's coordinates (the anchor usually sits in the settings popup's window).
        val a = IntArray(2)
        val d = IntArray(2)
        anchor.getLocationOnScreen(a)
        decor.getLocationOnScreen(d)
        val top = a[1] - d[1]
        val right = a[0] - d[0] + anchor.width - rightInsetPx
        val place = PopupGeometry.dropdown(screenH, top, top + anchor.height, frame.measuredHeight, dm.density)
        val left = PopupGeometry.dropdownLeft(screenW, right, width)

        popup = PopupWindow(frame, width, place.height, true).apply {
            animationStyle = 0
            elevation = 0f
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        return try {
            popup.showAtLocation(decor, Gravity.TOP or Gravity.START, left, place.top)
            checkedRow?.let { r -> scroll.post { if (r.bottom > scroll.height) scroll.scrollTo(0, (r.top - scroll.height / 3).coerceAtLeast(0)) } }
            PanelRegistry.popup(activity, popup)
        } catch (_: RuntimeException) {
            // BadTokenException / IllegalStateException: the reader window is going away.
            null
        }
    }
}
