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
 * Compact building blocks of the reading-settings popup, sized for the ~6" 360×720 dp e-ink screen (U polish 8, 18):
 * plain 44 dp rows split by light 1 px lines inset 12 dp (black only above section headers and 더보기), 15 sp labels,
 * 16 sp values, 44 dp stepper buttons on the label's own row, 13 sp bold section headers, joined segmented controls,
 * and a small drop-down list. No card boxes, no animations.
 */
internal object Compact {
    /**
     * Row height: the popup's main section ([PopupGeometry.MAIN_ROWS] rows) is 396 dp, which fits under the 56% cap
     * of the Comet (403 dp) without scrolling.
     */
    const val ROW_DP = 44
    const val LABEL_SP = 15f
    const val VALUE_SP = 16f
    const val SUMMARY_SP = 12f
    const val HEADER_SP = 13f
    const val TOGGLE_SP = 13f
    /** Toggle / segment height inside a row. */
    const val TOGGLE_DP = 36
    const val STEP_DP = 44
    /** Common width of a stepper's value box, so the − / + buttons of every row line up. */
    const val STEP_VALUE_DP = 60
    const val PAD_DP = 12
    /** Inset of the light group lines from both sides. */
    const val LINE_INSET_DP = 12
    const val LIST_ROW_DP = 40
    const val LIST_SP = 15f
}

/**
 * Row background: white (gray while pressed when [pressable]) with a 1 px line along the top when [topLine]: black
 * and full width (a group starts: section headers, 더보기, other dialogs' bars), or light and inset 12 dp when
 * [light] (an ordinary popup row).
 */
internal fun Context.compactRowBackground(pressable: Boolean, topLine: Boolean, light: Boolean = false): Drawable {
    val base: Drawable = if (pressable) pressableBackground(Ink.WHITE) else ColorDrawable(Ink.WHITE)
    if (!topLine) return base
    return LayerDrawable(arrayOf(base, ColorDrawable(if (light) Ink.LINE_LIGHT else Ink.LINE))).apply {
        setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
        setLayerHeight(1, 1)
        if (light) setLayerInset(1, dp(Compact.LINE_INSET_DP), 0, dp(Compact.LINE_INSET_DP), 0)
    }
}

/**
 * A horizontal settings row (min [Compact.ROW_DP], 12 dp side padding, no vertical padding: a 44 dp stepper button
 * makes a row of exactly 44 dp); clickable (pressed = gray) when [onClick] is set.
 */
internal fun Context.compactRow(topLine: Boolean = true, strongLine: Boolean = false, onClick: ((View) -> Unit)? = null): LinearLayout = horizontal {
    minimumHeight = dp(Compact.ROW_DP)
    setPadding(dp(Compact.PAD_DP), 0, dp(Compact.PAD_DP - 4), 0)
    background = compactRowBackground(onClick != null, topLine, light = !strongLine)
    if (onClick != null) setOnClickListener(onClick)
}

/** 13 sp bold section header ("글자", "페이지", "TXT 파일" …) under a black group line. */
internal fun Context.compactHeader(text: String): TextView = label(text, Compact.HEADER_SP, bold = true, color = Ink.GRAY).apply {
    setPadding(dp(Compact.PAD_DP), dp(10), dp(Compact.PAD_DP), dp(4))
    background = compactRowBackground(pressable = false, topLine = true)
}

/** The 15 sp row label (left side), optionally with a 12 sp gray summary under it. */
internal fun Context.compactLabelBlock(title: String, summary: String? = null): View {
    val t = label(title, Compact.LABEL_SP, maxLines = 2)
    if (summary == null) return t
    return vertical {
        setPadding(0, dp(4), 0, dp(4))
        addView(t)
        addView(label(summary, Compact.SUMMARY_SP, color = Ink.GRAY, maxLines = 2).apply { setPadding(0, dp(1), 0, 0) })
    }
}

/** 44 dp flat icon button (stepper − / +). */
internal fun Context.compactIcon(iconRes: Int, description: String, onClick: (View) -> Unit): ImageButton =
    flatIcon(iconRes, description, sizeDp = Compact.STEP_DP, onClick = onClick)

/**
 * A small square-bordered text toggle (the "내 스타일 ▾" button): selected = black fill with white regular text.
 * Update with [setCompactToggle].
 */
internal fun Context.compactToggle(text: String, selected: Boolean, onClick: (View) -> Unit): TextView =
    label(text, Compact.TOGGLE_SP, maxLines = 1).apply {
        gravity = Gravity.CENTER
        minHeight = dp(Compact.TOGGLE_DP)
        minWidth = dp(40)
        setPadding(dp(7), 0, dp(7), 0)
        setOnClickListener(onClick)
        setCompactToggle(this, selected)
    }

/** Selected = black fill + white text, else white + black; the weight never changes, so nothing shifts. */
internal fun setCompactToggle(v: TextView, selected: Boolean) {
    val ctx = v.context
    if (v.isSelected == selected && v.background != null) return
    v.isSelected = selected
    v.background = ctx.borderBox(if (selected) Ink.BLACK else Ink.WHITE)
    v.setTextColor(if (selected) Ink.WHITE else Ink.BLACK)
}

/**
 * A joined segmented control (U polish 18): one 1 px black border around all [options], 1 px black dividers, no
 * radius; the selected segment is black with white regular text. A tap on another segment marks it at once (same UI
 * message as the setting's own change: one e-ink update) and calls [onPick] with its index.
 */
internal class CompactSegments(ctx: Context, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = ctx.borderBox(Color.TRANSPARENT)
        setPadding(1, 1, 1, 1)
    }
    private val cells = ArrayList<TextView>(options.size)
    var selected: Int = -1
        private set

    init {
        options.forEachIndexed { i, text ->
            if (i > 0) view.addView(View(ctx).apply { setBackgroundColor(Ink.LINE) }, LinearLayout.LayoutParams(1, MATCH_PARENT))
            val cell = ctx.label(text, Compact.TOGGLE_SP, maxLines = 1).apply {
                gravity = Gravity.CENTER
                minHeight = ctx.dp(Compact.TOGGLE_DP) - 2
                minWidth = ctx.dp(40)
                setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
                setOnClickListener {
                    if (this@CompactSegments.selected == i) return@setOnClickListener
                    select(i)
                    onPick(i)
                }
            }
            cells += cell
            view.addView(cell, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        select(selected)
    }

    /** Marks segment [index] (−1 = none); no-op when it is already the one. */
    fun select(index: Int) {
        if (index == selected) return
        selected = index
        cells.forEachIndexed { j, c ->
            val on = j == index
            c.isSelected = on
            c.setBackgroundColor(if (on) Ink.BLACK else Ink.WHITE)
            c.setTextColor(if (on) Ink.WHITE else Ink.BLACK)
        }
    }

    /** Segment [index]'s view (content descriptions, long-press hints). */
    fun cell(index: Int): TextView = cells[index]
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
 * [maxHeightFraction] of the screen tall (scrolls; 0.8 for the status slot chooser), its right edge [rightInsetPx] inside [anchor]'s, under the anchor (or above it
 * when there is no room below). The checked row is scrolled into view. Tracked for [ReaderPanels.dismissAll].
 * Returns null when it cannot be shown.
 */
internal object CompactList {
    fun show(
        activity: Activity,
        anchor: View,
        entries: List<ListEntry>,
        widthPx: Int,
        rightInsetPx: Int = 0,
        maxHeightFraction: Float = PopupGeometry.HEIGHT_FRACTION,
    ): PopupWindow? {
        if (entries.isEmpty() || !anchor.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) return null
        val decor = activity.window?.decorView ?: return null
        val dm = activity.resources.displayMetrics
        val screenH = decor.height.takeIf { it > 0 } ?: dm.heightPixels
        val screenW = decor.width.takeIf { it > 0 } ?: dm.widthPixels
        val width = widthPx.coerceIn(1, screenW)

        val noteMaxPx = (width * 0.45f).toInt()
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
            // A live sample (a chapter / book title) is cut at 45% of the row: the item's own name always stays visible.
            if (e.note != null) row.addView(activity.label(e.note, Compact.SUMMARY_SP, color = Ink.GRAY, maxLines = 1).apply {
                setPadding(activity.dp(6), 0, activity.dp(4), 0)
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = noteMaxPx
            })
            if (e.checked != null) {
                row.addView(activity.icon(if (e.checked) R.drawable.ic_radio_button_checked else R.drawable.ic_radio_button_unchecked, 18).apply {
                    (layoutParams as LinearLayout.LayoutParams).leftMargin = activity.dp(6)
                })
            }
            if (e.checked == true) checkedRow = row
            list.addView(row, lp())
        }
        val maxH = PopupGeometry.maxHeight(screenH, maxHeightFraction)
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
        val place = PopupGeometry.dropdown(screenH, top, top + anchor.height, frame.measuredHeight, dm.density, maxHeightFraction)
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
