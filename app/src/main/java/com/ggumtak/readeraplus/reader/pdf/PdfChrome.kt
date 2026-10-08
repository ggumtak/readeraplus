package com.ggumtak.readeraplus.reader.pdf

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.label

/**
 * The PDF viewer's bars, Flexcil-like: a dark top bar (back, page navigator, title, search, bookmark, settings),
 * the pen tool bar and the "5 / 120 페이지" badge at the bottom right. Views only; the activity decides what the
 * taps do. Main thread.
 *
 * The tool bar keeps one shape: the mode button at its left end (pen mode / reading; dragged, it moves a floating
 * tool bar), the pen presets, eraser, lasso, undo, then 위에 붙이기 (pin) and 접기. It is either docked (a flat strip
 * under the top bar, the page below it) or floating (a rounded bar over the page, wherever it was dragged); folded,
 * only the mode button and 펼치기 stay, floating.
 */
internal class PdfChrome(private val activity: Activity, private val root: FrameLayout, private val listener: Listener) {

    interface Listener {
        fun onBack()
        /** The page navigator on [tab] (PdfSidePanel.TAB_*). */
        fun onPages(tab: Int)
        fun onSearch()
        fun onBookmark()
        fun onSettings()
        /** The mode button: pen tools on or off. */
        fun onAnnotate(on: Boolean)
        /** A pen preset tapped ([again] = it was already the chosen one: edit it). */
        fun onPreset(index: Int, again: Boolean)
        /** Eraser or lasso ([PdfPageView.MODE_ERASER] / [PdfPageView.MODE_LASSO]). */
        fun onTool(mode: Int)
        fun onUndo()
        fun onBadge()
        /** The tool bar docked / folded / dragged by the user (to keep for next time). */
        fun onToolbarLayout(docked: Boolean, folded: Boolean, x: Float, y: Float)
    }

    private val top: LinearLayout
    private val title: TextView
    private val bookmark: ImageButton
    private val toolbar: LinearLayout
    private val modeButton: ImageButton
    private val scroll: HorizontalScrollView
    private val toolRow: LinearLayout
    private val pin: ImageButton
    private val fold: ImageButton
    private val badge: TextView
    private val pill = GradientDrawable()
    private var insetTop = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var insetBottom = 0
    private var shown = true

    /** Tool bar layout: docked under the top bar, folded, and the floating position (0..1 of the free room). */
    var docked = true
        private set
    var folded = false
        private set
    private var posX = 0.5f
    private var posY = 0f

    /** Height kept free at the top (inset included), shown or not: hiding the bars re-renders nothing. */
    val topSpace: Int
        get() = insetTop + activity.dp(TOP_DP) + if (docked && !folded) activity.dp(TOOLBAR_DP) else 0

    init {
        val ctx = activity
        top = ctx.horizontal {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            minimumHeight = ctx.dp(TOP_DP)
        }
        top.addView(icon(R.drawable.ic_arrow_back, "닫기") { listener.onBack() })
        top.addView(icon(R.drawable.ic_grid_view, "페이지 탐색") { listener.onPages(PdfSidePanel.TAB_PAGES) })
        title = ctx.label("", 15f, color = 0xFFE0E0E0.toInt(), maxLines = 1).apply { setPadding(ctx.dp(6), 0, ctx.dp(6), 0) }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(icon(R.drawable.ic_search, "찾기") { listener.onSearch() })
        bookmark = icon(R.drawable.ic_bookmark, "책갈피") { listener.onBookmark() }
        top.addView(bookmark)
        top.addView(icon(R.drawable.ic_settings, "PDF 설정") { listener.onSettings() })
        root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        modeButton = ImageButton(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(ctx.dp(MODE_DP), ctx.dp(MODE_DP)).apply {
                leftMargin = ctx.dp(5)
                rightMargin = ctx.dp(3)
            }
        }
        toolRow = ctx.horizontal { gravity = Gravity.CENTER }
        scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            isFocusable = false
            addView(toolRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        pin = icon(R.drawable.ic_push_pin, "위에 붙이기", size = TOOL_ICON_DP) {
            setLayout(!docked, folded, posX, posY)
            report()
        }
        fold = icon(R.drawable.ic_expand_less, "도구 접기", size = TOOL_ICON_DP) {
            setLayout(docked, !folded, posX, posY)
            report()
        }
        pill.cornerRadius = ctx.dp(TOOLBAR_DP / 2).toFloat()
        pill.setColor(TOOLBAR)
        toolbar = ctx.horizontal {
            gravity = Gravity.CENTER_VERTICAL
            addView(modeButton)
            // Weighted: a tool row wider than the screen shrinks to it and scrolls.
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(pin)
            addView(fold)
            setPadding(0, 0, ctx.dp(4), 0)
        }
        root.addView(toolbar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, ctx.dp(TOOLBAR_DP), Gravity.TOP or Gravity.START))
        dragHandle()
        // The floating position follows the bar's own size (folding, tools) and the screen's (rotation).
        toolbar.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) position()
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) position()
        }

        badge = ctx.label("", 13f, color = 0xFFDDDDDD.toInt()).apply {
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(6).toFloat()
                setColor(0xCC5A5A5A.toInt())
            }
            visibility = View.GONE
            setOnClickListener { listener.onBadge() }
            isFocusable = false
        }
        root.addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        setTools(emptyList(), 0, PdfPageView.MODE_NONE, annotating = false)
        setLayout(docked, folded, posX, posY)
    }

    fun setTitle(text: CharSequence) {
        title.text = text
    }

    fun setBookmarked(on: Boolean) {
        bookmark.setImageResource(if (on) R.drawable.ic_bookmark_fill else R.drawable.ic_bookmark)
        bookmark.imageTintList = ColorStateList.valueOf(if (on) PdfToolIcon.ACCENT else ICON)
    }

    /** The badge text ("5 / 120 페이지"), or null to hide it. */
    fun setBadge(text: String?) {
        badge.visibility = if (text == null || !shown) View.GONE else View.VISIBLE
        if (text != null) badge.text = text
    }

    /** Bars shown (false: full-screen reading, only the page). */
    fun setShown(show: Boolean) {
        shown = show
        val v = if (show) View.VISIBLE else View.GONE
        top.visibility = v
        toolbar.visibility = v
        if (!show) badge.visibility = View.GONE
    }

    val isShown: Boolean get() = shown

    fun setInsets(left: Int, topInset: Int, right: Int, bottom: Int) {
        insetLeft = left
        insetTop = topInset
        insetRight = right
        insetBottom = bottom
        place()
    }

    /**
     * The tool bar [docked] under the top bar or floating at ([x], [y]) (0..1 of the free room), [folded] to the mode
     * button or not. The activity re-fits the page when [topSpace] changes.
     */
    fun setLayout(docked: Boolean, folded: Boolean, x: Float, y: Float) {
        this.docked = docked
        this.folded = folded
        posX = x.coerceIn(0f, 1f)
        posY = y.coerceIn(0f, 1f)
        val strip = docked && !folded
        scroll.visibility = if (folded) View.GONE else View.VISIBLE
        pin.visibility = if (folded) View.GONE else View.VISIBLE
        pin.setImageResource(if (docked) R.drawable.ic_push_pin_fill else R.drawable.ic_push_pin)
        pin.imageTintList = ColorStateList.valueOf(if (docked) PdfToolIcon.ACCENT else ICON)
        pin.contentDescription = if (docked) "띄우기" else "위에 붙이기"
        fold.setImageResource(if (folded) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
        fold.contentDescription = if (folded) "도구 펼치기" else "도구 접기"
        if (strip) {
            toolbar.background = null
            toolbar.setBackgroundColor(STRIP)
            toolbar.elevation = 0f
        } else {
            toolbar.background = pill
            toolbar.elevation = activity.dp(6).toFloat()
        }
        (toolbar.layoutParams as FrameLayout.LayoutParams).width =
            if (strip) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
        place()
    }

    private fun report() = listener.onToolbarLayout(docked, folded, posX, posY)

    private fun place() {
        top.setPadding(insetLeft + activity.dp(2), insetTop, insetRight + activity.dp(2), 0)
        val strip = docked && !folded
        (toolbar.layoutParams as FrameLayout.LayoutParams).let {
            it.topMargin = insetTop + activity.dp(TOP_DP) + if (strip) 0 else activity.dp(FLOAT_GAP_DP)
            it.leftMargin = if (strip) 0 else insetLeft + activity.dp(FLOAT_GAP_DP)
            it.rightMargin = if (strip) 0 else insetRight + activity.dp(FLOAT_GAP_DP)
            it.bottomMargin = if (strip) 0 else insetBottom + activity.dp(FLOAT_GAP_DP)
            toolbar.layoutParams = it
        }
        toolbar.setPadding(if (strip) insetLeft else 0, 0, (if (strip) insetRight else 0) + activity.dp(4), 0)
        (badge.layoutParams as FrameLayout.LayoutParams).let {
            it.rightMargin = insetRight + activity.dp(12)
            it.bottomMargin = insetBottom + activity.dp(14)
            badge.layoutParams = it
        }
        position()
    }

    /** Free room for the floating bar to move in: (width, height) left over around it, never negative. */
    private fun roomX(): Int {
        val lp = toolbar.layoutParams as FrameLayout.LayoutParams
        return maxOf(0, root.width - lp.leftMargin - lp.rightMargin - toolbar.width)
    }

    private fun roomY(): Int {
        val lp = toolbar.layoutParams as FrameLayout.LayoutParams
        return maxOf(0, root.height - lp.topMargin - lp.bottomMargin - toolbar.height)
    }

    /** Puts the floating bar at its position (the docked strip sits still). */
    private fun position() {
        if (docked && !folded) {
            toolbar.translationX = 0f
            toolbar.translationY = 0f
        } else {
            toolbar.translationX = PdfMath.slot(posX, roomX())
            toolbar.translationY = PdfMath.slot(posY, roomY())
        }
    }

    /** The mode button: a tap switches pen mode; a drag moves a floating (or folded) tool bar, like Flexcil's. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragHandle() {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        modeButton.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0f
            var startY = 0f
            var moving = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = toolbar.translationX
                        startY = toolbar.translationY
                        moving = false
                        v.isPressed = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (docked && !folded) return true
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moving && dx * dx + dy * dy > slop * slop) {
                            moving = true
                            v.isPressed = false
                        }
                        if (moving) {
                            toolbar.translationX = (startX + dx).coerceIn(0f, roomX().toFloat())
                            toolbar.translationY = (startY + dy).coerceIn(0f, roomY().toFloat())
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        if (moving) {
                            posX = PdfMath.fractionOf(toolbar.translationX, roomX())
                            posY = PdfMath.fractionOf(toolbar.translationY, roomY())
                            report()
                        } else {
                            v.performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                return true
            }
        })
    }

    /**
     * The tools: the mode button (pen mode when [annotating]), the [presets] ([selected] raised while it is the
     * tool in use), eraser, lasso and undo. Tapping any tool turns pen mode on with it.
     */
    fun setTools(presets: List<PenPreset>, selected: Int, mode: Int, annotating: Boolean) {
        modeButton.setImageResource(if (annotating) R.drawable.ic_edit else R.drawable.ic_touch_app)
        modeButton.imageTintList = ColorStateList.valueOf(if (annotating) 0xFF1F1F1F.toInt() else ICON)
        modeButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (annotating) PdfToolIcon.ACCENT else 0xFF4A4A4A.toInt())
        }
        // Described by what a tap does (also what the emulator check taps).
        modeButton.contentDescription = if (annotating) "필기 끝내기" else "필기"
        modeButton.setOnClickListener { listener.onAnnotate(!annotating) }

        toolRow.removeAllViews()
        val drawing = annotating && (mode == PdfPageView.MODE_PEN || mode == PdfPageView.MODE_HIGHLIGHTER)
        presets.forEachIndexed { i, pr ->
            val kind = if (pr.tool == InkTool.HIGHLIGHTER) PdfToolIcon.HIGHLIGHTER else PdfToolIcon.PEN
            toolRow.addView(PdfToolIcon(activity, kind).apply {
                tipColor = pr.color
                caption = PenColors.formatWidth(pr.width)
                chosen = drawing && i == selected
                setOnClickListener { listener.onPreset(i, drawing && i == selected) }
            })
        }
        toolRow.addView(divider())
        toolRow.addView(PdfToolIcon(activity, PdfToolIcon.ERASER).apply {
            chosen = annotating && mode == PdfPageView.MODE_ERASER
            setOnClickListener { listener.onTool(PdfPageView.MODE_ERASER) }
        })
        toolRow.addView(PdfToolIcon(activity, PdfToolIcon.LASSO).apply {
            chosen = annotating && mode == PdfPageView.MODE_LASSO
            setOnClickListener { listener.onTool(PdfPageView.MODE_LASSO) }
        })
        toolRow.addView(icon(R.drawable.ic_undo, "되돌리기", size = TOOL_ICON_DP) { listener.onUndo() })
        toolRow.addView(divider())
    }

    private fun icon(res: Int, description: String, tint: Int = ICON, size: Int = TOP_ICON_DP, onClick: () -> Unit): ImageButton =
        ImageButton(activity).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = description
            background = null
            scaleType = ImageView.ScaleType.CENTER
            // Never a keyboard focus: Enter / D-pad centre from a Bluetooth keyboard must not "click" 닫기.
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(activity.dp(size), activity.dp(size))
            setOnClickListener { onClick() }
        }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(0xFF555555.toInt())
        layoutParams = LinearLayout.LayoutParams(activity.dp(1), activity.dp(20)).apply {
            leftMargin = activity.dp(4)
            rightMargin = activity.dp(4)
        }
    }

    companion object {
        const val BAR = 0xFF1F1F1F.toInt()
        const val TOOLBAR = 0xFF2E2E2E.toInt()
        /** The docked tool strip, a shade off the top bar. */
        const val STRIP = 0xFF292929.toInt()
        const val ICON = 0xFFE0E0E0.toInt()
        const val TOP_DP = 48
        const val TOP_ICON_DP = 42
        const val TOOLBAR_DP = 44
        const val TOOL_ICON_DP = 38
        const val MODE_DP = 34
        const val FLOAT_GAP_DP = 6
    }
}
