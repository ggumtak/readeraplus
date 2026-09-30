package com.ggumtak.readeraplus.ui.kit

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.ggumtak.readeraplus.R

/**
 * Tiny programmatic UI kit. Everything is black on white (e-ink): no ripples, no animations,
 * no elevation shadows. Screens are built in code (fast to create, no XML inflation).
 */
object Ink {
    const val BLACK = Color.BLACK
    const val WHITE = Color.WHITE
    /** Secondary text. Dark enough to stay legible on e-ink. */
    const val GRAY = 0xFF555555.toInt()
    /** Hairlines / dividers. */
    const val LINE = 0xFF000000.toInt()
    const val PRESSED = 0xFFCCCCCC.toInt()
    const val DISABLED = 0xFF999999.toInt()
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.dpF(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/**
 * Short status message. The system toast always fades in and out (an e-ink smear), so inside an activity that
 * has the window focus the message is a static bordered box drawn in the activity's own window and hidden after
 * [InkMessage.SHOW_MS] without animation. Falls back to a system toast when there is no activity, the activity is
 * finishing (e.g. `toast(...); finish()`), or a dialog/popup has the focus and would cover the box.
 */
fun Context.toast(msg: CharSequence) {
    val activity = activityOrNull()
    val content = activity?.window?.peekDecorView()?.findViewById<View>(android.R.id.content) as? FrameLayout
    if (activity == null || content == null) {
        systemToast(msg)
        return
    }
    // Judged one message later, on the main thread, so a toast right before finish() is still seen.
    content.post {
        if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus() || !content.isAttachedToWindow) {
            systemToast(msg)
        } else {
            InkMessage.show(content, msg)
        }
    }
}

private fun Context.systemToast(msg: CharSequence) {
    if (Looper.myLooper() == Looper.getMainLooper()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    else android.os.Handler(Looper.getMainLooper()).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}

/** The activity behind this context (through ContextWrapper layers), or null. */
private fun Context.activityOrNull(): Activity? {
    var c: Context? = this
    var depth = 0
    while (c != null && depth++ < 16) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Bottom margin (px) for an in-window message: [base] above whatever part of the bottom system bars / keyboard
 * ([barInsetBottom], from the window bottom) overlaps the content view, whose bottom edge is at
 * [contentBottomInWindow] in a window [windowHeight] tall. Edge-to-edge windows (the reader) get the bar height
 * added; windows that already fit the content above the bars get just [base].
 */
internal fun inkMessageBottomMargin(contentBottomInWindow: Int, windowHeight: Int, barInsetBottom: Int, base: Int): Int {
    val overlap = contentBottomInWindow - (windowHeight - barInsetBottom.coerceAtLeast(0))
    return base + overlap.coerceAtLeast(0)
}

/** The static toast replacement: one reusable box per activity content view. */
private object InkMessage {
    const val SHOW_MS = 2000L

    private class Box(ctx: Context) : TextView(ctx) {
        val hide = Runnable { visibility = View.GONE }
    }

    fun show(content: FrameLayout, msg: CharSequence) {
        val ctx = content.context
        var existing: Box? = null
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v is Box) existing = v
        }
        val b = existing ?: Box(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Ink.BLACK)
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = ctx.borderBox()
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            content.addView(this, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        b.removeCallbacks(b.hide)
        b.text = msg
        if (content.width > 0) b.maxWidth = content.width - 2 * ctx.dp(24)
        val loc = IntArray(2)
        content.getLocationInWindow(loc)
        val lp = b.layoutParams as FrameLayout.LayoutParams
        val margin = inkMessageBottomMargin(loc[1] + content.height, content.rootView.height, bottomInset(content), ctx.dp(48))
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin
            b.layoutParams = lp
        }
        if (content.indexOfChild(b) != content.childCount - 1) b.bringToFront()
        b.visibility = View.VISIBLE
        b.announceForAccessibility(msg)
        b.postDelayed(b.hide, SHOW_MS)
    }

    private fun bottomInset(v: View): Int {
        val insets = v.rootWindowInsets ?: return 0
        return if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
    }
}

/** Background with a flat gray pressed state and no ripple. */
fun pressableBackground(base: Int = Color.TRANSPARENT): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Ink.PRESSED))
    addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(Ink.PRESSED))
    addState(intArrayOf(), ColorDrawable(base))
}

/** A white box with a 1px black border (cards, popups). */
fun Context.borderBox(fill: Int = Ink.WHITE, strokeDp: Float = 1f, radiusDp: Float = 0f): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(strokeDp).coerceAtLeast(1), Ink.LINE)
        cornerRadius = dpF(radiusDp)
    }

fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight)

fun Context.vertical(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; block() }

fun Context.horizontal(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; block() }

fun Context.label(
    text: CharSequence,
    sizeSp: Float = 16f,
    bold: Boolean = false,
    color: Int = Ink.BLACK,
    maxLines: Int = Int.MAX_VALUE,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    if (maxLines != Int.MAX_VALUE) {
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }
    includeFontPadding = false
}

/** 48dp square icon button tinted black. */
fun Context.iconButton(iconRes: Int, description: String, sizeDp: Int = 48, onClick: (View) -> Unit): ImageButton =
    ImageButton(this).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(Ink.BLACK)
        contentDescription = description
        background = pressableBackground()
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener(onClick)
        setOnLongClickListener { toast(description); true }
    }

fun Context.icon(iconRes: Int, sizeDp: Int = 24, tint: Int = Ink.BLACK): ImageView = ImageView(this).apply {
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.hairline(vertical: Boolean = false): View = View(this).apply {
    setBackgroundColor(Ink.LINE)
    layoutParams = if (vertical) LinearLayout.LayoutParams(1, MATCH_PARENT) else LinearLayout.LayoutParams(MATCH_PARENT, 1)
}

class ToolbarAction(val iconRes: Int, val label: String, val onClick: (View) -> Unit)

/**
 * App bar: [nav] icon, title (and optional subtitle), actions on the right, 1px bottom line.
 * Returns the container; the title TextView is tagged "title" (findViewWithTag) for updates.
 */
fun Context.toolbar(
    title: CharSequence,
    navIcon: Int? = null,
    navLabel: String = "뒤로",
    onNav: ((View) -> Unit)? = null,
    actions: List<ToolbarAction> = emptyList(),
): LinearLayout {
    val bar = vertical { setBackgroundColor(Ink.WHITE) }
    val row = horizontal {
        minimumHeight = dp(56)
        setPadding(dp(4), 0, dp(4), 0)
    }
    if (navIcon != null && onNav != null) row.addView(iconButton(navIcon, navLabel, onClick = onNav))
    val t = label(title, 20f, bold = true, maxLines = 1).apply {
        tag = "title"
        setPadding(dp(12), 0, dp(8), 0)
    }
    row.addView(t, lp(0, WRAP_CONTENT, 1f))
    actions.forEach { a -> row.addView(iconButton(a.iconRes, a.label, onClick = a.onClick)) }
    bar.addView(row, lp())
    bar.addView(hairline())
    return bar
}

// ---------------------------------------------------------------- settings-style rows

fun Context.sectionHeader(text: String): TextView = label(text, 14f, bold = true).apply {
    setPadding(dp(16), dp(20), dp(16), dp(6))
}

/** Title + optional summary on the left, [trailing] view on the right. */
fun Context.row(title: String, summary: String? = null, trailing: View? = null, onClick: ((View) -> Unit)? = null): LinearLayout {
    val r = horizontal {
        minimumHeight = dp(56)
        setPadding(dp(16), dp(10), dp(12), dp(10))
        if (onClick != null) {
            background = pressableBackground()
            setOnClickListener(onClick)
        }
    }
    val texts = vertical()
    texts.addView(label(title, 17f))
    if (summary != null) texts.addView(label(summary, 14f, color = Ink.GRAY).apply { tag = "summary"; setPadding(0, dp(3), 0, 0) })
    r.addView(texts, lp(0, WRAP_CONTENT, 1f))
    if (trailing != null) r.addView(trailing)
    return r
}

fun Context.switchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
    val sw = InkToggle(this).apply { isChecked = checked }
    sw.onChange = onChange
    return row(title, summary, sw) { sw.toggle() }
}

/** "−  value  +" stepper. [format] renders the value; returns the row. */
fun Context.stepperRow(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
): LinearLayout {
    var v = value
    val valueText = label(format(v), 17f).apply { gravity = Gravity.CENTER; minWidth = dp(72) }
    fun set(nv: Float) {
        v = (Math.round(nv / step) * step).coerceIn(min, max)
        valueText.text = format(v)
        onChange(v)
    }
    val box = horizontal {
        addView(iconButton(com.ggumtak.readeraplus.R.drawable.ic_do_not_disturb_on, "줄이기") { set(v - step) })
        addView(valueText)
        addView(iconButton(com.ggumtak.readeraplus.R.drawable.ic_add_circle, "늘리기") { set(v + step) })
    }
    return row(title, null, box)
}

fun Context.sliderRow(title: String, value: Int, max: Int, onChange: (Int) -> Unit): LinearLayout {
    val col = vertical { setPadding(dp(16), dp(10), dp(16), dp(10)) }
    col.addView(label(title, 17f))
    val bar = SeekBar(this).apply {
        this.max = max
        progress = value
        progressTintList = ColorStateList.valueOf(Ink.BLACK)
        thumbTintList = ColorStateList.valueOf(Ink.BLACK)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }
    col.addView(bar, lp())
    return col
}

// ---------------------------------------------------------------- dialogs & popups

/**
 * Light alert dialog in the e-ink dialog theme ([R.style.InkDialog]): black accents, no ripple, no dim behind,
 * no elevation shadow and no window animation (the theme's `@null` animation style is what disables it: a
 * `setWindowAnimations(0)` before the decor exists means "use the theme's").
 */
fun Context.alert(): AlertDialog.Builder = AlertDialog.Builder(this, R.style.InkDialog)

fun Dialog.noAnimation(): Dialog = apply { window?.setWindowAnimations(0) }

/** Shows an alert built with [alert] without animation. */
fun AlertDialog.Builder.showNoAnim(): AlertDialog = create().also { it.window?.setWindowAnimations(0); it.show() }

/**
 * A full-screen white dialog hosting [content] (used for TOC, search, settings sub-screens inside the reader).
 * A focused full-screen dialog takes over the system bars, so it asks for the same bar state as the activity
 * below it: otherwise opening/closing it shows/hides a bar, which resizes (re-lays out) the reader behind it.
 */
fun Context.fullScreenDialog(content: View): Dialog {
    val owner = activityOrNull()?.window
    return Dialog(this, R.style.InkScreenDialog).apply {
        setContentView(content)
        window?.let { w ->
            w.setWindowAnimations(0)
            w.setBackgroundDrawable(ColorDrawable(Ink.WHITE))
            if (owner != null) matchSystemBars(w, owner)
        }
    }
}

/** Makes [dialog] (not yet shown, decor installed) request [owner]'s current system-bar visibility and look. */
private fun matchSystemBars(dialog: Window, owner: Window) {
    dialog.statusBarColor = owner.statusBarColor
    dialog.navigationBarColor = owner.navigationBarColor
    val ownerDecor = owner.peekDecorView() ?: return
    if (Build.VERSION.SDK_INT >= 30) {
        val c = dialog.insetsController ?: return // pending controller before attach; replayed on show()
        val oc = owner.insetsController
        if (oc != null) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            c.setSystemBarsAppearance(oc.systemBarsAppearance and mask, mask)
        }
        val insets = ownerDecor.rootWindowInsets ?: return
        var hide = 0
        if (!insets.isVisible(WindowInsets.Type.statusBars())) hide = hide or WindowInsets.Type.statusBars()
        if (!insets.isVisible(WindowInsets.Type.navigationBars())) hide = hide or WindowInsets.Type.navigationBars()
        if (hide != 0) {
            c.systemBarsBehavior = oc?.systemBarsBehavior ?: WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(hide)
        }
    } else {
        @Suppress("DEPRECATION")
        dialog.decorView.systemUiVisibility = ownerDecor.systemUiVisibility
    }
}

class MenuItem(val label: String, val iconRes: Int? = null, val checked: Boolean? = null, val enabled: Boolean = true, val onClick: () -> Unit)

/** Anchored popup menu (no animation, black border). */
fun Context.popupMenu(anchor: View, items: List<MenuItem>, widthDp: Int = 240): PopupWindow {
    val list = vertical { background = borderBox(); setPadding(0, dp(4), 0, dp(4)) }
    val popup = PopupWindow(list, dp(widthDp), WRAP_CONTENT, true)
    items.forEach { item ->
        val r = horizontal {
            minimumHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
            background = pressableBackground()
            isEnabled = item.enabled
            setOnClickListener { popup.dismiss(); item.onClick() }
        }
        if (item.iconRes != null) r.addView(icon(item.iconRes, 22).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(16) })
        r.addView(label(item.label, 17f, color = if (item.enabled) Ink.BLACK else Ink.DISABLED), lp(0, WRAP_CONTENT, 1f))
        if (item.checked != null) r.addView(icon(if (item.checked) com.ggumtak.readeraplus.R.drawable.ic_radio_button_checked else com.ggumtak.readeraplus.R.drawable.ic_radio_button_unchecked, 22))
        list.addView(r, lp())
    }
    popup.animationStyle = 0
    popup.isOutsideTouchable = true
    popup.elevation = 0f
    popup.showAsDropDown(anchor)
    return popup
}

/** Single-choice list dialog. */
fun Context.chooser(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    alert().setTitle(title)
        .setSingleChoiceItems(options.toTypedArray(), selected) { d, which -> d.dismiss(); onPick(which) }
        .setNegativeButton("취소", null)
        .showNoAnim()
}

/** Text input dialog. */
fun Context.prompt(title: String, initial: String = "", hint: String = "", onOk: (String) -> Unit) {
    val edit = android.widget.EditText(this).apply {
        setText(initial)
        this.hint = hint
        setSelection(initial.length)
        setSingleLine(false)
    }
    val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(edit) }
    alert().setTitle(title).setView(box)
        .setPositiveButton("확인") { _, _ -> onOk(edit.text.toString()) }
        .setNegativeButton("취소", null)
        .showNoAnim()
}

fun Context.confirm(title: String, message: String, ok: String = "확인", onOk: () -> Unit) {
    alert().setTitle(title).setMessage(message)
        .setPositiveButton(ok) { _, _ -> onOk() }
        .setNegativeButton("취소", null)
        .showNoAnim()
}

/**
 * ListView without dividers/overscroll glow/fading edges, suited to e-ink: a static (non-fading) scrollbar and
 * no fast scroller (the auto-hiding one slides in/out on every scroll). A list that wants drag-to-seek sets
 * `isFastScrollEnabled` and `isFastScrollAlwaysVisible` together, like the library.
 */
fun Context.einkListView(): ListView = ListView(this).apply {
    divider = null
    dividerHeight = 0
    overScrollMode = View.OVER_SCROLL_NEVER
    isVerticalFadingEdgeEnabled = false
    selector = ColorDrawable(Color.TRANSPARENT)
    isFastScrollEnabled = false
    isScrollbarFadingEnabled = false
    cacheColorHint = Color.TRANSPARENT
}

fun View.frameLp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, gravity: Int = Gravity.NO_GRAVITY): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(w, h, gravity)

fun ViewGroup.clear() = removeAllViews()
