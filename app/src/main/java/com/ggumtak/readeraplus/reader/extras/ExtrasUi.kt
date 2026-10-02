package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.inkCursor
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import java.net.URLEncoder

/*
 * Small UI building blocks shared by the extras (ReadEra-style cards, overlays over the reader, text actions).
 * Everything black on white, no ripples, no animations.
 */

/** ScrollView that never grows taller than [maxHeightPx] (popup content). */
internal class MaxHeightScrollView(context: Context, var maxHeightPx: Int) : ScrollView(context) {
    init {
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = false
        isScrollbarFadingEnabled = false
        isFillViewport = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}

internal fun Context.einkScroll(child: View): ScrollView = ScrollView(this).apply {
    overScrollMode = View.OVER_SCROLL_NEVER
    isVerticalFadingEdgeEnabled = false
    isScrollbarFadingEnabled = false
    addView(child, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

/** A ReadEra-like settings card: white box, 1px black border. */
internal fun Context.card(): LinearLayout = vertical {
    background = borderBox(radiusDp = 3f)
    setPadding(dp(14), dp(8), dp(10), dp(8))
    layoutParams = lp().apply { setMargins(dp(8), dp(6), dp(8), 0) }
}

internal fun Context.cardTitle(text: String): TextView = label(text, 13f, bold = true, color = Ink.GRAY)

/**
 * On/off toggle drawn statically (the platform Switch animates its thumb, which smears on e-ink).
 * Off = white track with a black border and a black knob on the left; on = black track, white knob on the right.
 * Not clickable itself: the surrounding card/row toggles it (large touch target).
 */
internal class EinkToggle(context: Context, checked: Boolean) : View(context) {
    private val wPx = context.dp(44)
    private val hPx = context.dp(24)
    private val stroke = context.dpF(1.5f)
    private val inset = context.dpF(4f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Ink.BLACK }
    private val rect = RectF()

    var isChecked: Boolean = checked
        set(v) {
            if (field == v) return
            field = v
            contentDescription = if (v) "켜짐" else "꺼짐"
            invalidate()
        }

    init {
        contentDescription = if (checked) "켜짐" else "꺼짐"
        isClickable = false
        isFocusable = false
    }

    fun toggle() {
        isChecked = !isChecked
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(wPx, widthMeasureSpec), resolveSize(hPx, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2f
        rect.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f)
        fill.color = if (isChecked) Ink.BLACK else Ink.WHITE
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, line)
        fill.color = if (isChecked) Ink.WHITE else Ink.BLACK
        canvas.drawCircle(if (isChecked) w - r else r, h / 2f, (r - inset).coerceAtLeast(1f), fill)
    }
}

/**
 * Latest known quotes of the open book, shared by the selection popup and the contents dialog so that a quote
 * deleted or edited in one is not acted on (stale) in the other. Plain data only (no views / contexts).
 */
internal object QuoteCache {
    private class Entry(val bookId: Long, val quotes: List<Quote>)

    @Volatile private var entry: Entry? = null

    fun get(bookId: Long): List<Quote>? = entry?.takeIf { it.bookId == bookId }?.quotes

    fun put(bookId: Long, quotes: List<Quote>) {
        entry = Entry(bookId, quotes)
    }
}

/**
 * Black-on-white SeekBar for e-ink. The platform thumb is an animated selector (grows on press, shrinks on release:
 * several e-ink updates) and the split track redraws a gap around it: use a plain static dot instead.
 */
internal fun SeekBar.einkStyle(): SeekBar = apply {
    progressTintList = ColorStateList.valueOf(Ink.BLACK)
    progressBackgroundTintList = ColorStateList.valueOf(Ink.DISABLED)
    thumb = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Ink.BLACK)
        val d = context.dp(20)
        setSize(d, d)
    }
    thumbOffset = context.dp(10)
    splitTrack = false
    background = null
}

/** Flat square icon button (no ripple) used inside cards and bars. */
internal fun Context.flatIcon(iconRes: Int, description: String, sizeDp: Int = 48, tint: Int = Ink.BLACK, onClick: (View) -> Unit): ImageButton =
    ImageButton(this).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = description
        background = pressableBackground()
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener(onClick)
        setOnLongClickListener { toast(description); true }
    }

/** Round black button with a white icon (TTS play/pause). */
internal fun Context.roundBlackButton(iconRes: Int, description: String, sizeDp: Int, onClick: (View) -> Unit): ImageButton =
    ImageButton(this).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(Ink.WHITE)
        contentDescription = description
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Ink.BLACK)
            setStroke(dp(2), Ink.WHITE)
        }
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener(onClick)
    }

/** Centered gray message for empty lists. */
internal fun Context.emptyMessage(text: String): TextView = label(text, 17f, color = Ink.GRAY).apply {
    gravity = Gravity.CENTER
    setPadding(dp(32), dp(48), dp(32), dp(48))
    setLineSpacing(0f, 1.3f)
}

/** A plain text button with a 1px border (dialog footers). */
internal fun Context.outlineButton(text: String, onClick: (View) -> Unit): TextView = label(text, 16f, bold = true).apply {
    gravity = Gravity.CENTER
    minHeight = dp(48)
    setPadding(dp(12), 0, dp(12), 0)
    background = android.graphics.drawable.LayerDrawable(arrayOf(pressableBackground(Ink.WHITE), borderBox(Color.TRANSPARENT, radiusDp = 3f)))
    setOnClickListener(onClick)
}

/** Multi-line text input dialog (replace rules, notes, review); tracked for [ReaderPanels.dismissAll]. */
internal fun Context.multilinePrompt(
    title: String,
    initial: String,
    hint: String,
    minLines: Int = 4,
    message: String? = null,
    neutral: Pair<String, () -> Unit>? = null,
    onOk: (String) -> Unit,
): AlertDialog {
    val edit = EditText(this).apply {
        setText(initial)
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        this.minLines = minLines
        maxLines = 12
        gravity = Gravity.TOP or Gravity.START
        setTextColor(Ink.BLACK)
        setSelection(initial.length)
        inkCursor(singleLine = false)
    }
    val box = vertical { setPadding(dp(20), dp(8), dp(20), 0) }
    if (message != null) box.addView(label(message, 14f, color = Ink.GRAY).apply { setPadding(0, 0, 0, dp(8)); setLineSpacing(0f, 1.2f) })
    box.addView(edit, lp())
    val b = alert().setTitle(title).setView(box)
        .setPositiveButton("저장") { _, _ -> onOk(edit.text.toString()) }
        .setNegativeButton("취소", null)
    if (neutral != null) b.setNeutralButton(neutral.first) { _, _ -> neutral.second() }
    return PanelRegistry.dialog(this, b.showNoAnim())
}

/** Overlay helpers for views laid over the reader page (handles, TTS bar, search bar). */
internal object Overlay {
    fun parentOf(host: ReaderHost): FrameLayout? = host.pageView.parent as? FrameLayout

    /** Bottom system-bar inset currently visible over [view] (0 in immersive mode). */
    fun bottomInset(view: View): Int {
        val insets = view.rootWindowInsets ?: return 0
        return if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
    }

    /** Top system-bar / cutout inset currently over [view] (0 in immersive mode), in its window's coordinates. */
    fun topInset(view: View): Int {
        val insets = view.rootWindowInsets ?: return 0
        return if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).top
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop
        }
    }

    /** A white bar with a 1px black top line. */
    fun bar(ctx: Context): LinearLayout = ctx.horizontal {
        background = android.graphics.drawable.LayerDrawable(arrayOf(ColorDrawable(Ink.WHITE), ColorDrawable(Ink.LINE))).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, 1)
        }
        isClickable = true
        isFocusable = false
        minimumHeight = ctx.dp(52)
        setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), 0)
    }
}

/** Text actions shared by the selection popup and the quotes list. */
internal object TextActions {
    const val SHARE_MAX_CHARS = 50_000
    private const val PREF_LAST_DICT = "extras.lastDictApp"

    /**
     * Copies [text]. Android 13+ confirms a copy itself, so by default the toast is only shown below that; a caller
     * that confirms in its own view passes [confirm] = false (a long-pressed label in a dialog).
     */
    fun copy(ctx: Context, text: String, confirm: Boolean = Build.VERSION.SDK_INT < 33) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("ReaderaPlus", text))
        if (confirm) ctx.toast("복사했습니다")
    }

    fun share(ctx: Context, text: String, subject: String? = null) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        start(ctx, Intent.createChooser(send, "공유"))
    }

    fun webSearch(ctx: Context, text: String) {
        val template = runCatching { Settings.app.webSearchUrl }.getOrNull()?.takeIf { it.contains("%s") }
            ?: "https://www.google.com/search?q=%s"
        val url = template.replace("%s", URLEncoder.encode(text.trim(), "UTF-8"))
        start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Installed ACTION_PROCESS_TEXT handlers (dictionaries, translators), last used first. */
    fun processTextApps(ctx: Context): List<ResolveInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val list = try {
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        val own = ctx.packageName
        val last = runCatching { Settings.raw().getString(PREF_LAST_DICT, null) }.getOrNull()
        return list.filter { it.activityInfo != null && it.activityInfo.packageName != own }
            .sortedWith(compareBy({ key(it) != last }, { it.loadLabel(pm).toString() }))
    }

    private fun key(ri: ResolveInfo): String = ri.activityInfo.packageName + "/" + ri.activityInfo.name

    /** Dictionary / translate: pick a PROCESS_TEXT app (list), or fall back to a web search. */
    fun lookUp(activity: Activity, text: String) {
        val apps = processTextApps(activity)
        if (apps.isEmpty()) {
            activity.toast("사전·번역 앱이 없어 웹에서 검색합니다")
            webSearch(activity, text)
            return
        }
        val pm = activity.packageManager
        val labels = apps.map { it.loadLabel(pm).toString() } + "웹 검색"
        activity.alert().setTitle("사전 · 번역")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= apps.size) webSearch(activity, text) else launchProcessText(activity, apps[which], text)
            }
            .setNegativeButton("취소", null)
            .showNoAnim()
            .also { d -> d.listView?.selector = ColorDrawable(Color.TRANSPARENT) }
            .also { d -> PanelRegistry.dialog(activity, d) }
    }

    private fun launchProcessText(ctx: Context, ri: ResolveInfo, text: String) {
        runCatching { Settings.raw().edit().putString(PREF_LAST_DICT, key(ri)).apply() }
        val i = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .setComponent(ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        start(ctx, i)
    }

    fun start(ctx: Context, intent: Intent) {
        try {
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ctx.toast("실행할 앱이 없습니다")
        } catch (_: SecurityException) {
            ctx.toast("앱을 열 수 없습니다")
        }
    }
}
