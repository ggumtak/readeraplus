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
 * Building blocks of the quick reading options (⚙) and their drop-down lists, sized for the ~6" 360×720 dp e-ink
 * screen: 48 dp rows split by light 1 px lines inset 12 dp, 16 sp labels, 17 sp values, 48 dp stepper buttons on the
 * label's own row, 48 dp list rows. No card boxes, no animations.
 */
internal object Compact {
    /**
     * Row height: the 48 dp touch target of its stepper buttons. The popup (the [BAR_DP] top bar and
     * [PopupGeometry.QUICK_ROWS] rows, 384 dp) fits under the 56% cap of the Comet (403 dp) without scrolling.
     */
    const val ROW_DP = 48
    /** The top bar ("전체 읽기 설정 ›" · 닫기). */
    const val BAR_DP = 48
    const val LABEL_SP = 16f
    const val VALUE_SP = 17f
    const val SUMMARY_SP = 13f
    /** Stepper − / + and the close button: the 48 dp minimum touch target. */
    const val STEP_DP = 48
    /** Common width of a stepper's value box, so the − / + buttons of every row line up. */
    const val STEP_VALUE_DP = 64
    const val PAD_DP = 12
    /** Inset of the light group lines from both sides. */
    const val LINE_INSET_DP = 12
    const val LIST_ROW_DP = 48
    const val LIST_SP = 16f
}

/**
 * Row background: white (gray while pressed when [pressable]) with a 1 px line along the top when [topLine]: black
 * and full width (a group starts: a list's actions, other dialogs' bars), or light and inset 12 dp when [light] (an
 * ordinary popup row).
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
 * A horizontal settings row (min [Compact.ROW_DP], 12 dp side padding, no vertical padding: the 48 dp stepper
 * buttons fill its height) under a light line; clickable (pressed = gray) when [onClick] is set.
 */
internal fun Context.compactRow(topLine: Boolean = true, onClick: ((View) -> Unit)? = null): LinearLayout = horizontal {
    minimumHeight = dp(Compact.ROW_DP)
    setPadding(dp(Compact.PAD_DP), 0, dp(Compact.PAD_DP - 4), 0)
    background = compactRowBackground(onClick != null, topLine, light = true)
    if (onClick != null) setOnClickListener(onClick)
}

/** The 16 sp row label (left side), optionally with a 13 sp gray summary under it. */
internal fun Context.compactLabelBlock(title: String, summary: String? = null): View {
    val t = label(title, Compact.LABEL_SP, maxLines = 2)
    if (summary == null) return t
    return vertical {
        setPadding(0, dp(4), 0, dp(4))
        addView(t)
        addView(label(summary, Compact.SUMMARY_SP, color = Ink.GRAY, maxLines = 2).apply { setPadding(0, dp(1), 0, 0) })
    }
}

/** 48 dp flat icon button (stepper − / +, 닫기). */
internal fun Context.compactIcon(iconRes: Int, description: String, onClick: (View) -> Unit): ImageButton =
    flatIcon(iconRes, description, sizeDp = Compact.STEP_DP, onClick = onClick)

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
 * Compact drop-down list for the settings popup: 48 dp rows, 16 sp text, [widthPx] wide (≤ the popup), at most
 * [maxHeightFraction] of the screen tall (scrolls; e.g. 0.8 for long lists), its right edge [rightInsetPx] inside [anchor]'s, under the anchor (or above it
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
