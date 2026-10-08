package com.ggumtak.readeraplus.reader.pdf

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
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
 * a floating rounded tool bar under it (reading: 필기 / 선택 / 형광펜; writing: the pens, eraser, lasso and undo)
 * and the "5 / 120 페이지" badge at the bottom right. Views only; the activity decides what the taps do.
 * Main thread.
 */
internal class PdfChrome(private val activity: Activity, root: FrameLayout, private val listener: Listener) {

    interface Listener {
        fun onBack()
        /** The page navigator on [tab] (PdfSidePanel.TAB_*). */
        fun onPages(tab: Int)
        fun onSearch()
        fun onBookmark()
        fun onSettings()
        /** The 필기 button: writing tools on or off. */
        fun onAnnotate(on: Boolean)
        /** A pen preset tapped ([again] = it was already the chosen one: edit it). */
        fun onPreset(index: Int, again: Boolean)
        /** Eraser or lasso ([PdfPageView.MODE_ERASER] / [PdfPageView.MODE_LASSO]). */
        fun onTool(mode: Int)
        fun onUndo()
        fun onBadge()
    }

    private val top: LinearLayout
    private val title: TextView
    private val bookmark: ImageButton
    private val toolbar: LinearLayout
    private val toolRow: LinearLayout
    private val badge: TextView
    private var insetTop = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var insetBottom = 0
    private var shown = true

    /** Height kept free for the bars at the top (inset included), shown or not: hiding them re-renders nothing. */
    val topSpace: Int get() = insetTop + activity.dp(TOP_DP) + activity.dp(TOOLBAR_DP + 2 * TOOLBAR_MARGIN_DP)

    init {
        val ctx = activity
        top = ctx.horizontal {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            minimumHeight = ctx.dp(TOP_DP)
        }
        top.addView(icon(R.drawable.ic_arrow_back, "닫기") { listener.onBack() })
        top.addView(icon(R.drawable.ic_grid_view, "페이지 탐색") { listener.onPages(PdfSidePanel.TAB_PAGES) })
        title = ctx.label("", 16f, color = 0xFFE0E0E0.toInt(), maxLines = 1).apply { setPadding(ctx.dp(8), 0, ctx.dp(8), 0) }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(icon(R.drawable.ic_search, "찾기") { listener.onSearch() })
        bookmark = icon(R.drawable.ic_bookmark, "책갈피") { listener.onBookmark() }
        top.addView(bookmark)
        top.addView(icon(R.drawable.ic_settings, "PDF 설정") { listener.onSettings() })
        root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        toolRow = ctx.horizontal {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
        }
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(toolRow)
        }
        toolbar = ctx.horizontal {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(18).toFloat()
                setColor(TOOLBAR)
            }
            elevation = ctx.dp(6).toFloat()
            minimumHeight = ctx.dp(TOOLBAR_DP)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(TOOLBAR_DP)))
        }
        root.addView(toolbar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, ctx.dp(TOOLBAR_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        badge = ctx.label("", 14f, color = 0xFFDDDDDD.toInt()).apply {
            setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(6).toFloat()
                setColor(0xCC5A5A5A.toInt())
            }
            visibility = View.GONE
            setOnClickListener { listener.onBadge() }
        }
        root.addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        setReading()
        place()
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

    private fun place() {
        top.setPadding(insetLeft + activity.dp(4), insetTop, insetRight + activity.dp(4), 0)
        (toolbar.layoutParams as FrameLayout.LayoutParams).let {
            it.topMargin = insetTop + activity.dp(TOP_DP + TOOLBAR_MARGIN_DP)
            it.leftMargin = insetLeft + activity.dp(8)
            it.rightMargin = insetRight + activity.dp(8)
            toolbar.layoutParams = it
        }
        (badge.layoutParams as FrameLayout.LayoutParams).let {
            it.rightMargin = insetRight + activity.dp(12)
            it.bottomMargin = insetBottom + activity.dp(16)
            badge.layoutParams = it
        }
    }

    /** Reading: 필기 (writing on), 선택 (the lasso) and 형광펜. */
    fun setReading() {
        toolRow.removeAllViews()
        toolRow.addView(icon(R.drawable.ic_edit, "필기") { listener.onAnnotate(true) })
        toolRow.addView(divider())
        toolRow.addView(PdfToolIcon(activity, PdfToolIcon.LASSO).apply { setOnClickListener { listener.onTool(PdfPageView.MODE_LASSO) } })
        toolRow.addView(icon(R.drawable.ic_ink_highlighter, "형광펜") { listener.onTool(PdfPageView.MODE_HIGHLIGHTER) })
        toolRow.addView(icon(R.drawable.ic_bookmark_add, "책갈피 목록") { listener.onPages(PdfSidePanel.TAB_BOOKMARKS) })
    }

    /** Writing: the 필기 button (yellow: off again), the [presets] ([selected] raised when a pen is in use), eraser, lasso, undo. */
    fun setWriting(presets: List<PenPreset>, selected: Int, mode: Int) {
        toolRow.removeAllViews()
        toolRow.addView(icon(R.drawable.ic_edit, "필기 끝내기", tint = PdfToolIcon.ACCENT) { listener.onAnnotate(false) })
        toolRow.addView(divider())
        val drawing = mode == PdfPageView.MODE_PEN || mode == PdfPageView.MODE_HIGHLIGHTER
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
            chosen = mode == PdfPageView.MODE_ERASER
            setOnClickListener { listener.onTool(PdfPageView.MODE_ERASER) }
        })
        toolRow.addView(PdfToolIcon(activity, PdfToolIcon.LASSO).apply {
            chosen = mode == PdfPageView.MODE_LASSO
            setOnClickListener { listener.onTool(PdfPageView.MODE_LASSO) }
        })
        toolRow.addView(divider())
        toolRow.addView(icon(R.drawable.ic_undo, "되돌리기") { listener.onUndo() })
    }

    private fun icon(res: Int, description: String, tint: Int = ICON, onClick: () -> Unit): ImageButton =
        ImageButton(activity).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = description
            background = null
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(activity.dp(46), activity.dp(46))
            setOnClickListener { onClick() }
        }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(0xFF555555.toInt())
        layoutParams = LinearLayout.LayoutParams(activity.dp(1), activity.dp(22)).apply {
            leftMargin = activity.dp(6)
            rightMargin = activity.dp(6)
        }
    }

    companion object {
        const val BAR = 0xFF1F1F1F.toInt()
        const val TOOLBAR = 0xFF2E2E2E.toInt()
        const val ICON = 0xFFE0E0E0.toInt()
        const val TOP_DP = 56
        const val TOOLBAR_DP = 52
        const val TOOLBAR_MARGIN_DP = 8
    }
}
