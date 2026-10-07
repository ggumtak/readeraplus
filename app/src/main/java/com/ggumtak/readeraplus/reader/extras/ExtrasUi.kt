package com.ggumtak.readeraplus.reader.extras

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.SearchManager
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
import com.ggumtak.readeraplus.data.Lookups
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
    /** The keys ([LookupList.Entry.key]) of the 사전·번역 entries the user hid; absent until first used (then seeded). */
    private const val PREF_HIDDEN = "extras.lookupHidden"

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

    /**
     * Opens the user's web search ([WebSearchTemplate], `AppSettings.webSearchUrl`) for [text]. [onDone] runs (main
     * thread) only when the browser was started.
     */
    fun webSearch(ctx: Context, text: String, onDone: (() -> Unit)? = null) {
        val url = WebSearchTemplate.url(webSearchTemplate(), text)
        if (start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) onDone?.invoke()
    }

    /** The site host a web lookup is recorded with ("www.google.com"). */
    fun webSearchHost(): String = WebSearchTemplate.host(webSearchTemplate())

    private fun webSearchTemplate(): String? = runCatching { Settings.app.webSearchUrl }.getOrNull()

    /** Installed ACTION_PROCESS_TEXT handlers (dictionaries, translators); the Naver Dictionary app has its own entry. */
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
        return list.filter {
            it.activityInfo != null && it.activityInfo.packageName != own && it.activityInfo.packageName != LookupList.NAVERDIC_PKG
        }
    }

    private fun key(ri: ResolveInfo): String = ri.activityInfo.packageName + "/" + ri.activityInfo.name

    private fun naverInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getLaunchIntentForPackage(LookupList.NAVERDIC_PKG) != null }.getOrDefault(false)

    private fun entries(ctx: Context, apps: List<ResolveInfo>): List<LookupList.Entry> {
        val pm = ctx.packageManager
        val list = apps.map { ri ->
            val label = ri.loadLabel(pm)?.toString().orEmpty()
            val app = runCatching { ri.activityInfo.applicationInfo.loadLabel(pm).toString() }.getOrDefault("")
            LookupList.Entry(key(ri), label, app)
        }.filter { it.label.isNotBlank() }
        val naver = if (naverInstalled(ctx)) LookupList.Entry(LookupList.NAVERDIC_KEY, LookupList.NAVERDIC_LABEL) else null
        return LookupList.order(naver, list)
    }

    /** Every 사전·번역 entry before "웹 검색", in the chooser's order, hidden or not (the settings page lists them). */
    fun lookupEntries(ctx: Context): List<LookupList.Entry> = entries(ctx, processTextApps(ctx))

    /** The hidden entry keys; the first time (nothing stored) the labels of [all] seed it, and that is stored. */
    fun hiddenEntries(all: List<LookupList.Entry>): Set<String> {
        val stored = runCatching { Settings.raw().getStringSet(PREF_HIDDEN, null)?.toSet() }.getOrNull()
        val hidden = LookupList.hidden(stored, all)
        if (stored == null) setHiddenEntries(hidden)
        return hidden
    }

    fun setHiddenEntries(keys: Set<String>) {
        runCatching { Settings.raw().edit().putStringSet(PREF_HIDDEN, HashSet(keys)).apply() }
    }

    /**
     * Dictionary / translate: always a list in a fixed order — the Naver Dictionary app ("네이버 사전", the plain word),
     * the PROCESS_TEXT apps by name (the selection + " 뜻", [LookupQuery.appText]) and "웹 검색" (the floating
     * [LookupPanel]) — without the entries hidden on the 사전·번역·검색 page. [onPicked] (main thread) runs once the
     * pick was started — an app ([Lookups.VIA_APP], its label) or "웹 검색" ([Lookups.VIA_WEB], the site host) — and
     * never when the chooser is cancelled or nothing could be started.
     */
    fun lookUp(activity: Activity, text: String, onPicked: ((via: Int, app: String) -> Unit)? = null) {
        val apps = processTextApps(activity).associateBy { key(it) }
        val all = entries(activity, apps.values.toList())
        val word = LookupQuery.word(text)
        val shown = LookupList.visible(all, hiddenEntries(all)).filter { it.key != LookupList.NAVERDIC_KEY || word.isNotEmpty() }
        val labels = shown.map { it.label } + "웹 검색"
        activity.alert().setTitle("사전·번역")
            .setItems(labels.toTypedArray()) { _, which ->
                val e = shown.getOrNull(which)
                if (e == null) {
                    // The chooser's 웹 검색: the user's search site in the floating window, the query + " 뜻".
                    val q = LookupQuery.query(text).ifEmpty { text }
                    val url = WebSearchTemplate.url(webSearchTemplate(), q)
                    if (LookupPanel.show(activity, q, url)) onPicked?.invoke(Lookups.VIA_WEB, webSearchHost())
                } else if (e.key == LookupList.NAVERDIC_KEY) {
                    if (openNaverDic(activity, word)) onPicked?.invoke(Lookups.VIA_APP, e.label)
                } else if (launchProcessText(activity, apps.getValue(e.key), e.label, text)) {
                    onPicked?.invoke(Lookups.VIA_APP, e.label)
                }
            }
            .setNegativeButton("취소", null)
            .showNoAnim()
            .also { d -> d.listView?.selector = ColorDrawable(Color.TRANSPARENT) }
            .also { d -> PanelRegistry.dialog(activity, d) }
    }

    private fun launchProcessText(ctx: Context, ri: ResolveInfo, label: String, text: String): Boolean {
        val pkg = ri.activityInfo.packageName
        val i = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .setComponent(ComponentName(pkg, ri.activityInfo.name))
            .putExtra(Intent.EXTRA_PROCESS_TEXT, LookupQuery.appText(pkg, label, text))
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        if (LookupQuery.isAnki(pkg) || ctx !is Activity) return start(ctx, i)
        return launchFloating(ctx, i)
    }

    /**
     * Opens the Naver Dictionary app with [word]: the first of PROCESS_TEXT, SEND, SEARCH and WEB_SEARCH the app
     * answers; an app that answers none gets the word on the clipboard and is just opened.
     */
    private fun openNaverDic(activity: Activity, word: String): Boolean {
        val pkg = LookupList.NAVERDIC_PKG
        val pm = activity.packageManager
        val tries = listOf(
            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, word).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, word),
            Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, word),
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, word),
        )
        for (i in tries) {
            i.setPackage(pkg)
            if (i.resolveActivity(pm) != null && launchFloating(activity, i, quiet = true)) return true
        }
        val launch = pm.getLaunchIntentForPackage(pkg) ?: run {
            activity.toast("네이버 사전 앱을 열 수 없습니다")
            return false
        }
        copy(activity, word, confirm = false)
        if (!launchFloating(activity, launch)) return false
        activity.toast("단어를 복사했습니다. 검색창에 붙여 넣으세요")
        return true
    }

    /**
     * Starts [intent] in a floating window over the lower part of the screen, like the 웹 검색 window: a system with
     * pop-up / freeform windows honours the bounds, any other opens the app as before. False when it could not be
     * started; a [quiet] caller tries something else, any other gets a message.
     */
    private fun launchFloating(activity: Activity, intent: Intent, quiet: Boolean = false): Boolean {
        return try {
            val dm = activity.resources.displayMetrics
            val side = (8 * dm.density).toInt()
            val h = LookupQuery.startHeight(dm.heightPixels, LookupPanel.savedPct())
            val bounds = android.graphics.Rect(side, dm.heightPixels - h, dm.widthPixels - side, dm.heightPixels)
            val opts = android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds)
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts.toBundle())
            true
        } catch (_: ActivityNotFoundException) {
            if (!quiet) activity.toast("실행할 앱이 없습니다")
            false
        } catch (_: SecurityException) {
            if (!quiet) activity.toast("앱을 열 수 없습니다")
            false
        } catch (_: RuntimeException) {
            if (quiet) runCatching { activity.startActivity(intent.setFlags(0)) }.isSuccess else start(activity, intent.setFlags(0))
        }
    }

    /** Starts [intent]; false (with a message) when no app takes it or it may not be opened. */
    fun start(ctx: Context, intent: Intent): Boolean {
        return try {
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            ctx.toast("실행할 앱이 없습니다")
            false
        } catch (_: SecurityException) {
            ctx.toast("앱을 열 수 없습니다")
            false
        }
    }
}
