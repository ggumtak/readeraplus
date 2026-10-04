package com.ggumtak.readeraplus.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkToggle
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.keepAll
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.row
import com.ggumtak.readeraplus.ui.kit.sectionHeader
import com.ggumtak.readeraplus.ui.kit.vertical

/**
 * One screen of the in-activity settings stack. [build] is called once when the page is first shown; the view is
 * kept while the page stays in the stack so going back preserves scroll position. [onShown] runs every time the
 * page becomes the top page (refresh summaries there); [onHidden] when another page is pushed over it. While it is
 * the top page, [onResume] / [onPause] follow the activity's. [onDestroy] runs when it leaves the stack.
 */
internal abstract class SettingsPage(val activity: SettingsActivity, val id: String, val title: String) {
    var view: View? = null

    abstract fun build(): View

    open fun onShown() {}
    open fun onHidden() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean = false
    open fun onKeyPressed(keyCode: Int, scanCode: Int) {}
    open fun onDestroy() {}

    protected val ctx: Context get() = activity
}

// ---------------------------------------------------------------- settings helpers

/** Reads the latest [AppSettings], applies [f] and saves (never works on a stale copy). */
internal inline fun editApp(f: (AppSettings) -> AppSettings) {
    Settings.saveApp(f(Settings.app))
}

internal inline fun editReader(f: (ReaderSettings) -> ReaderSettings) {
    Settings.saveReader(f(Settings.reader))
}

// ---------------------------------------------------------------- view helpers

/**
 * White scroll container for a page body; e-ink friendly (no fading edges, no overscroll). [fling] = false makes the
 * page static: a drag scrolls, a flick stops with the finger (no coasting frames), for pages that are read rather
 * than scanned (읽기 기록).
 */
internal fun Context.pageScroll(body: LinearLayout, fling: Boolean = true): ScrollView =
    (if (fling) ScrollView(this) else StaticScrollView(this)).apply {
        isVerticalFadingEdgeEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isScrollbarFadingEnabled = false
        isSmoothScrollingEnabled = false
        isFillViewport = true
        setBackgroundColor(Ink.WHITE)
        addView(body, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

/** A ScrollView without fling: every frame it draws follows the finger. */
private class StaticScrollView(context: Context) : ScrollView(context) {
    override fun fling(velocityY: Int) {}
}

internal fun Context.pageBody(): LinearLayout = vertical {
    setBackgroundColor(Ink.WHITE)
    setPadding(0, 0, 0, dp(32))
}

/**
 * Starts a section: its bold header (tag "section"), returned so a page can rename it ("받은 파일 (3)") or hide it. A
 * section after another one in this container gets an 8 dp gap and a full-width black 1 px line above its header, so
 * every group reads as its own block when scanning a long page; the first one has none (the toolbar's line is right
 * above it, also after a top note or 정보's header block). This reverses UI_SPEC polish 12 (spacing only) on purpose:
 * the categories were too hard to tell apart (user, 2026-10-04). The gap and the line are the header's own background,
 * so hiding the header hides them too. Static views: no extra e-ink update.
 */
internal fun LinearLayout.section(text: String): TextView {
    val after = (0 until childCount).any { getChildAt(it).tag == SECTION_TAG }
    val header = context.sectionHeader(text).apply { tag = SECTION_TAG }
    if (after) {
        val gap = context.dp(8)
        header.background = LayerDrawable(arrayOf(ColorDrawable(Ink.LINE))).apply {
            setLayerGravity(0, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(0, 1)
            setLayerInsetTop(0, gap)
        }
        header.setPadding(header.paddingLeft, header.paddingTop + gap + 1, header.paddingRight, header.paddingBottom)
    }
    addView(header)
    return header
}

private const val SECTION_TAG = "section"

/** Grey explanatory text block (14 sp, wrapped between words). */
internal fun Context.note(text: CharSequence, sizeSp: Float = 14f): TextView = label(keepAll(text), sizeSp, color = Ink.GRAY).apply {
    setPadding(dp(16), dp(6), dp(16), dp(10))
    setLineSpacing(0f, 1.15f)
}

/**
 * A [note] in black: the one warning a page must not let pass (the status bar hidden by the margins, the double
 * flash, a file the Wi-Fi page could not take).
 */
internal fun Context.warning(text: CharSequence): TextView = note(text).apply { setTextColor(Ink.BLACK) }

/** Row that opens another screen (black chevron on the right). */
internal fun Context.navRow(title: String, summary: String?, onClick: (View) -> Unit): LinearLayout =
    row(title, summary, icon(R.drawable.ic_chevron_right, 24, Ink.BLACK), onClick = onClick)

/** Row whose summary shows the current value; tapping opens a chooser here (grey drop-down mark on the right). */
internal fun Context.valueRow(title: String, value: String, onClick: (View) -> Unit): LinearLayout =
    row(title, value, icon(R.drawable.ic_arrow_drop_down, 24, Ink.GRAY), onClick = onClick)

/** Updates the summary line of a row made with [row] (only when the row was created with a summary). */
internal fun View.setSummary(text: CharSequence) {
    findViewWithTag<TextView>("summary")?.text = keepAll(text)
}

/** Keeps a [row]'s summary on one line, cut with "…" (a regular expression, a path). */
internal fun <T : View> T.oneLineSummary(): T = apply {
    findViewWithTag<TextView>("summary")?.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
}

/**
 * Disables the row and its accessibility controls, with the reason in the summary (set by the caller). A disabled
 * row ignores taps and TalkBack announces it as unavailable ("사용 중지됨"); its title goes light grey, the summary
 * stays #555 so the reason can be read, and a switch fades.
 */
internal fun View.setRowEnabled(enabled: Boolean) {
    isEnabled = enabled
    if (this is TextView) setTextColor(if (tag == "summary") Ink.GRAY else if (enabled) Ink.BLACK else Ink.DISABLED)
    if (this is InkToggle || this is EinkToggle) alpha = if (enabled) 1f else 0.4f
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).setRowEnabled(enabled)
}

/** Sets the [EinkToggle] of a [toggleRow] without calling its change handler (the row's value changed elsewhere). */
internal fun View.setToggleChecked(checked: Boolean) {
    val toggle = (this as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt).firstOrNull { it is EinkToggle } } as? EinkToggle
    toggle?.isChecked = checked
}

/** VISIBLE / GONE, touching the view only when the state changes (no extra layout pass, no extra e-ink update). */
internal fun View.setShown(shown: Boolean) {
    val v = if (shown) View.VISIBLE else View.GONE
    if (visibility != v) visibility = v
}

/**
 * Marks the value of a `stepperRow` as a polite live region, so TalkBack speaks "−10" after a tap on − or + (scroll
 * SPEC §2.4). The row is `row(title, null, box)` with the value between the two buttons.
 */
internal fun LinearLayout.liveStepperValue(): LinearLayout {
    val box = getChildAt(childCount - 1) as? ViewGroup
    (box?.getChildAt(1) as? TextView)?.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    return this
}

/** Radio-style row: radio icon on the left, title + optional summary. */
internal fun Context.radioRow(title: String, summary: String?, checked: Boolean, onClick: (View) -> Unit): LinearLayout {
    val r = horizontal {
        minimumHeight = dp(56)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = pressableBackground()
        setOnClickListener(onClick)
    }
    val radio = icon(if (checked) R.drawable.ic_radio_button_checked else R.drawable.ic_radio_button_unchecked, 24).apply {
        tag = "radio"
        (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
    }
    r.addView(radio)
    val texts = vertical()
    texts.addView(label(title, 17f))
    if (summary != null) texts.addView(label(keepAll(summary), 14f, color = Ink.GRAY).apply { tag = "summary"; setPadding(0, dp(3), 0, 0) })
    r.addView(texts, lp(0, WRAP_CONTENT, 1f))
    return r
}

internal fun View.setRadioChecked(checked: Boolean) {
    findViewWithTag<ImageView>("radio")?.setImageResource(
        if (checked) R.drawable.ic_radio_button_checked else R.drawable.ic_radio_button_unchecked,
    )
}

/** Flat bordered text button (no ripple; grey fill while pressed). */
internal fun Context.textButton(text: String, onClick: (View) -> Unit): TextView = label(text, 15f).apply {
    gravity = Gravity.CENTER
    minHeight = dp(48)
    minWidth = dp(64)
    setPadding(dp(14), dp(8), dp(14), dp(8))
    val stroke = dp(1f).coerceAtLeast(1)
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(Ink.PRESSED); setStroke(stroke, Ink.LINE) })
        addState(intArrayOf(), GradientDrawable().apply { setColor(Ink.WHITE); setStroke(stroke, Ink.LINE) })
    }
    isClickable = true
    setOnClickListener(onClick)
}

/** Horizontal wrap-less row of buttons with 8dp gaps, left-aligned with the page padding. */
internal fun Context.buttonBar(vararg buttons: View): LinearLayout = horizontal {
    setPadding(dp(16), dp(6), dp(16), dp(10))
    buttons.forEachIndexed { i, b ->
        addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(8) })
    }
}

/** Title/value pair for read-only information lists. */
internal fun Context.infoRow(title: String, value: String): LinearLayout = vertical {
    setPadding(dp(16), dp(8), dp(16), dp(8))
    addView(label(title, 14f, color = Ink.GRAY))
    addView(label(value, 16f).apply { tag = "value"; setPadding(0, dp(2), 0, 0); setTextIsSelectable(false) })
}

/** Updates the value of an [infoRow]. */
internal fun View.setInfoValue(text: CharSequence) {
    findViewWithTag<TextView>("value")?.text = text
}
