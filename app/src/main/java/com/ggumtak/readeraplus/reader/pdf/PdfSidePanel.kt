package com.ggumtak.readeraplus.reader.pdf

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.einkListView
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import java.util.concurrent.ConcurrentHashMap

/** Dark "Flexcil" palette of the PDF viewer's side panel. */
private object Flex {
    const val PANEL = 0xFF1E1E1E.toInt()
    const val HEADER = 0xFF161616.toInt()
    const val FIELD = 0xFF000000.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val GREY = 0xFF9E9E9E.toInt()
    const val YELLOW = 0xFFF5B82E.toInt()
    const val DIVIDER = 0xFF2C2C2C.toInt()
    const val DANGER = 0xFFE53935.toInt()
    const val SCRIM = 0x66000000
    const val CHIP = 0xFF616161.toInt()
}

/**
 * Right-hand slide-in panel of the PDF viewer, drawn as one overlay (scrim + panel) on top of [root]. Two modes:
 * [showSearch] (text search with a result list) and [showPages] (page jump + large thumbnails, bookmarks, pages with
 * ink). Phone UI: the 200 ms slide is intentional here. Main thread only.
 */
internal class PdfSidePanel(private val activity: Activity, private val root: FrameLayout) {

    private var overlay: FrameLayout? = null
    private var scrim: View? = null
    private var panel: LinearLayout? = null
    private var headerBox: LinearLayout? = null
    private var titleView: TextView? = null
    private var body: FrameLayout? = null
    private var panelW = 0
    private var hiding = false

    /** Bumped whenever the overlay is torn down or starts hiding; stale animation end actions compare against it. */
    private var gen = 0

    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0

    private var search: SearchUi? = null
    private var pages: PagesUi? = null

    /** True while the panel is on screen (also during the slide-out). */
    val isShown: Boolean get() = overlay != null

    /** Slides the panel out and releases everything it holds (thumbnails, callbacks). */
    /** Called when the panel starts to close (the host stops work that only the panel shows). */
    var onHidden: (() -> Unit)? = null

    fun hide() {
        val ov = overlay ?: return
        if (hiding) return
        onHidden?.invoke()
        hideKeyboard()
        releaseModes()
        hiding = true
        val g = ++gen
        panel?.animate()?.translationX(panelW.toFloat())?.setDuration(SLIDE_MS)?.withEndAction {
            if (g == gen && overlay === ov) teardown()
        }?.start()
        scrim?.animate()?.alpha(0f)?.setDuration(SLIDE_MS)?.start()
    }

    /** Back key: closes an open panel. Returns true when it did (or when the panel is already closing). */
    fun onBack(): Boolean {
        if (overlay == null) return false
        hide()
        return true
    }

    /** Insets of the system bars / cutout (px) the panel content keeps clear of. */
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        insetL = left
        insetT = top
        insetR = right
        insetB = bottom
        applyInsets()
    }

    // ------------------------------------------------------------------------------------------ search

    fun showSearch(query: String, onSearch: (String) -> Unit, onPick: (page: Int) -> Unit) {
        val b = beginMode("검색")
        val ui = SearchUi(query, onSearch, onPick)
        search = ui
        b.addView(ui.rootView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (query.isEmpty()) ui.focusKeyboard()
    }

    /** While a search runs ("찾는 중… 12 / 300쪽"). */
    fun setSearchProgress(done: Int, total: Int) {
        search?.setStatus("찾는 중… $done / ${total}쪽")
    }

    /** Results as (page, count) pairs, page-ordered (0-based pages); empty = nothing found for [query]. */
    fun setSearchResults(query: String, results: List<Pair<Int, Int>>) {
        val ui = search ?: return
        val n = results.size
        val total = showResults(ui, results)
        ui.setStatus(if (n == 0) "‘$query’ 찾지 못했습니다" else "‘$query’ 총 ${total}곳 · ${n}쪽")
    }

    /**
     * The matches found so far while the search is still running ([done] of [total] pages scanned): the list grows
     * where it stands (its scroll position is kept) and the status keeps the progress text.
     */
    fun setSearchPartial(results: List<Pair<Int, Int>>, done: Int, total: Int) {
        val ui = search ?: return
        val found = showResults(ui, results)
        ui.setStatus(if (results.isEmpty()) "찾는 중… $done / ${total}쪽" else "찾는 중… $done / ${total}쪽 · ${found}곳")
    }

    /** Puts [results] in the list; returns the number of matches in them. */
    private fun showResults(ui: SearchUi, results: List<Pair<Int, Int>>): Int {
        val n = results.size
        val p = IntArray(n)
        val c = IntArray(n)
        var total = 0
        for (i in 0 until n) {
            p[i] = results[i].first
            c[i] = results[i].second
            total += c[i]
        }
        ui.adapter.set(p, c)
        return total
    }

    // ------------------------------------------------------------------------------------------ pages

    fun showPages(
        tab: Int,
        pageCount: Int,
        current: Int,
        bookmarks: () -> IntArray,
        inkPages: () -> IntArray,
        requestThumb: (page: Int, w: Int, h: Int, wanted: () -> Boolean, done: (Bitmap?) -> Unit) -> Unit,
        onPick: (page: Int) -> Unit,
        onClearAllInk: () -> Unit,
    ) {
        val b = beginMode("페이지 탐색")
        val ui = PagesUi(tab, pageCount, current, bookmarks, inkPages, requestThumb, onPick, onClearAllInk)
        pages = ui
        b.addView(ui.rootView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** Re-reads bookmarks / ink pages of the shown pages tab (after the host changed them). */
    fun refresh() {
        pages?.refresh()
    }

    // ------------------------------------------------------------------------------------------ overlay

    /** Makes sure the overlay exists, drops the previous mode, sets the title and returns the empty body. */
    private fun beginMode(title: String): FrameLayout {
        hideKeyboard()
        releaseModes()
        if (overlay == null || hiding) {
            teardown()
            createOverlay()
        }
        titleView?.text = title
        val b = body!!
        b.removeAllViews()
        return b
    }

    private fun releaseModes() {
        search = null
        pages?.close()
        pages = null
    }

    private fun createOverlay() {
        val ctx = activity
        val rootW = if (root.width > 0) root.width else ctx.resources.displayMetrics.widthPixels
        panelW = minOf((rootW * 0.85f).toInt(), ctx.dp(420))

        val ov = FrameLayout(ctx).apply { isClickable = true }
        val sc = View(ctx).apply {
            setBackgroundColor(Flex.SCRIM)
            alpha = 0f
            setOnClickListener { hide() }
        }
        ov.addView(sc, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val pn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flex.PANEL)
            isClickable = true
        }
        val title = ctx.label("", 18f, bold = true, color = Flex.TEXT, maxLines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(56), 0, ctx.dp(56), 0)
        }
        val close = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(Flex.TEXT)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "닫기"
            background = pressBg()
            setOnClickListener { hide() }
        }
        val headerRow = FrameLayout(ctx).apply {
            addView(title, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(close, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        }
        val hb = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Flex.HEADER)
            addView(headerRow, lp(MATCH_PARENT, ctx.dp(56)))
        }
        pn.addView(hb, lp())
        val bd = FrameLayout(ctx)
        pn.addView(bd, lp(MATCH_PARENT, 0, 1f))
        ov.addView(pn, FrameLayout.LayoutParams(panelW, MATCH_PARENT, Gravity.END))

        overlay = ov
        scrim = sc
        panel = pn
        headerBox = hb
        titleView = title
        body = bd
        hiding = false
        applyInsets()

        root.addView(ov, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pn.translationX = panelW.toFloat()
        pn.animate().translationX(0f).setDuration(SLIDE_MS).start()
        sc.animate().alpha(1f).setDuration(SLIDE_MS).start()
    }

    /** Removes the overlay immediately (no animation) and forgets its views. */
    private fun teardown() {
        gen++
        val ov = overlay
        if (ov != null) {
            panel?.animate()?.cancel()
            scrim?.animate()?.cancel()
            root.removeView(ov)
        }
        overlay = null
        scrim = null
        panel = null
        headerBox = null
        titleView = null
        body = null
        hiding = false
    }

    private fun applyInsets() {
        headerBox?.setPadding(0, insetT, insetR, 0)
        body?.setPadding(0, 0, insetR, insetB)
    }

    private fun hideKeyboard() {
        val token = (overlay ?: root).windowToken ?: return
        val imm = activity.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun showKeyboard(v: View) {
        v.requestFocus()
        v.postDelayed({
            if (v.isAttachedToWindow) {
                activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 120L)
    }

    private fun pickAndHide(onPick: (Int) -> Unit, page: Int) {
        hide()
        onPick(page)
    }

    // ------------------------------------------------------------------------------------------ view helpers

    private fun pressBg(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Flex.DIVIDER))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = activity.dpF(radiusDp)
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(Flex.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, activity.dp(0.5f)))
    }

    private fun emptyText(text: String): TextView = activity.label(text, 16f, color = Flex.GREY).apply {
        gravity = Gravity.CENTER
        setPadding(activity.dp(32), activity.dp(32), activity.dp(32), activity.dp(32))
    }

    private fun darkEdit(hint: String): EditText = EditText(activity).apply {
        this.hint = hint
        setHintTextColor(Flex.GREY)
        setTextColor(Flex.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        includeFontPadding = false
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER_VERTICAL
        highlightColor = 0x55F5B82E
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = GradientDrawable().apply {
                setColor(Flex.YELLOW)
                setSize(activity.dp(2), activity.dp(22))
            }
        }
    }

    private fun listView(): ListView = activity.einkListView().apply { isScrollbarFadingEnabled = true }

    // ------------------------------------------------------------------------------------------ search mode

    private inner class SearchUi(query: String, private val onSearch: (String) -> Unit, private val onPick: (Int) -> Unit) {
        val rootView: LinearLayout = activity.vertical()
        val adapter = ResultAdapter()
        private val edit = darkEdit("검색")
        private val clear = ImageView(activity)
        private val status = activity.label("", 14f, color = Flex.GREY)

        init {
            val ctx = activity
            edit.imeOptions = EditorInfo.IME_ACTION_SEARCH
            edit.setText(query)
            edit.setSelection(edit.text.length)
            clear.apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(Flex.GREY)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "지우기"
                visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                setOnClickListener {
                    edit.setText("")
                    showKeyboard(edit)
                }
            }
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
            })
            edit.setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    submit()
                    true
                } else {
                    false
                }
            }

            val field = ctx.horizontal {
                background = rounded(Flex.FIELD, 8f)
                setPadding(ctx.dp(12), 0, ctx.dp(4), 0)
            }
            field.addView(ctx.icon(R.drawable.ic_search, 20, Flex.GREY))
            field.addView(edit, lp(0, MATCH_PARENT, 1f).apply { leftMargin = ctx.dp(8) })
            field.addView(clear, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
            rootView.addView(field, lp(MATCH_PARENT, ctx.dp(44)).apply { setMargins(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(8)) })

            status.setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(8))
            status.visibility = View.GONE
            rootView.addView(status, lp())
            rootView.addView(divider())

            val list = listView().apply {
                adapter = this@SearchUi.adapter
                setOnItemClickListener { _, _, position, _ ->
                    val page = this@SearchUi.adapter.pageAt(position)
                    pickAndHide(onPick, page)
                }
            }
            rootView.addView(list, lp(MATCH_PARENT, 0, 1f))
        }

        fun focusKeyboard() = showKeyboard(edit)

        fun setStatus(text: String) {
            status.text = text
            status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun submit() {
            val q = edit.text.toString().trim()
            if (q.isEmpty()) return
            hideKeyboard()
            adapter.set(IntArray(0), IntArray(0))
            setStatus("찾는 중…")
            onSearch(q)
        }
    }

    private class ResultHolder(val page: TextView, val count: TextView)

    private inner class ResultAdapter : BaseAdapter() {
        private var pages = IntArray(0)
        private var counts = IntArray(0)

        fun set(p: IntArray, c: IntArray) {
            // Progress updates mostly repeat the list: no redraw, the list stays as it is.
            if (p.contentEquals(pages) && c.contentEquals(counts)) return
            pages = p
            counts = c
            notifyDataSetChanged()
        }

        fun pageAt(position: Int): Int = pages[position]

        override fun getCount(): Int = pages.size
        override fun getItem(position: Int): Any = pages[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val h: ResultHolder
            val row: View
            if (convertView == null) {
                val ctx = activity
                val pageLabel = ctx.label("", 16f, color = Flex.TEXT)
                val countLabel = ctx.label("", 14f, color = Flex.GREY)
                val line = ctx.horizontal {
                    minimumHeight = ctx.dp(52)
                    setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
                    addView(pageLabel, lp(0, WRAP_CONTENT, 1f))
                    addView(countLabel, lp(WRAP_CONTENT, WRAP_CONTENT))
                }
                row = ctx.vertical {
                    background = pressBg()
                    layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    addView(line, lp())
                    addView(divider())
                }
                h = ResultHolder(pageLabel, countLabel)
                row.tag = h
            } else {
                row = convertView
                h = row.tag as ResultHolder
            }
            h.page.text = "${pages[position] + 1}쪽"
            h.count.text = "${counts[position]}곳"
            return row
        }
    }

    // ------------------------------------------------------------------------------------------ pages mode

    private inner class PagesUi(
        initialTab: Int,
        private val pageCount: Int,
        private val current: Int,
        private val bookmarks: () -> IntArray,
        private val inkPages: () -> IntArray,
        private val requestThumb: (page: Int, w: Int, h: Int, wanted: () -> Boolean, done: (Bitmap?) -> Unit) -> Unit,
        private val onPick: (Int) -> Unit,
        private val onClearAllInk: () -> Unit,
    ) {
        val rootView: LinearLayout = activity.vertical()
        private val cache = object : LruCache<Int, Bitmap>(THUMB_CACHE_KB) {
            override fun sizeOf(key: Int, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
        private val content = FrameLayout(activity)
        private val tabIcons = arrayOfNulls<ImageView>(3)
        private val tabLines = arrayOfNulls<View>(3)
        private var tab = -1

        private var thumbs: PageThumbList? = null
        private var marksAdapter: PageRowAdapter? = null
        private var marksEmpty: View? = null
        private var inkAdapter: InkAdapter? = null
        private var inkEmpty: View? = null
        private var inkDelete: View? = null

        init {
            val ctx = activity
            val icons = intArrayOf(R.drawable.ic_grid_view, R.drawable.ic_bookmark, R.drawable.ic_edit)
            val names = arrayOf("쪽", "책갈피", "필기")
            val bar = ctx.horizontal { setBackgroundColor(Flex.HEADER) }
            for (i in 0 until 3) {
                val ic = ImageView(ctx).apply { setImageResource(icons[i]) }
                val line = View(ctx)
                val cell = FrameLayout(ctx).apply {
                    contentDescription = names[i]
                    background = pressBg()
                    setOnClickListener { selectTab(i) }
                    addView(ic, FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
                    addView(line, FrameLayout.LayoutParams(MATCH_PARENT, ctx.dp(3), Gravity.BOTTOM))
                }
                tabIcons[i] = ic
                tabLines[i] = line
                bar.addView(cell, lp(0, ctx.dp(48), 1f))
            }
            rootView.addView(bar, lp())
            rootView.addView(divider())
            rootView.addView(content, lp(MATCH_PARENT, 0, 1f))
            selectTab(initialTab.coerceIn(0, 2))
        }

        fun close() {
            thumbs?.close()
            thumbs = null
            cache.evictAll()
            marksAdapter = null
            inkAdapter = null
        }

        fun refresh() {
            when (tab) {
                TAB_BOOKMARKS -> {
                    val m = bookmarks()
                    marksAdapter?.set(m)
                    marksEmpty?.visibility = if (m.isEmpty()) View.VISIBLE else View.GONE
                }
                TAB_INK -> {
                    val k = inkPages()
                    inkAdapter?.set(k)
                    inkEmpty?.visibility = if (k.isEmpty()) View.VISIBLE else View.GONE
                    inkDelete?.visibility = if (k.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        private fun selectTab(i: Int) {
            if (i == tab) return
            hideKeyboard()
            thumbs?.close()
            thumbs = null
            marksAdapter = null
            inkAdapter = null
            tab = i
            for (t in 0 until 3) {
                val on = t == i
                tabIcons[t]?.imageTintList = ColorStateList.valueOf(if (on) Flex.YELLOW else Flex.GREY)
                tabLines[t]?.setBackgroundColor(if (on) Flex.YELLOW else Color.TRANSPARENT)
            }
            content.removeAllViews()
            val v = when (i) {
                TAB_PAGES -> buildPagesTab()
                TAB_BOOKMARKS -> buildBookmarksTab()
                else -> buildInkTab()
            }
            content.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        private fun buildPagesTab(): View {
            val ctx = activity
            val col = ctx.vertical()
            val edit = darkEdit("1 - $pageCount").apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = EditorInfo.IME_ACTION_GO
                background = rounded(Flex.FIELD, 8f)
                setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            }
            fun go() {
                val n = edit.text.toString().trim().toIntOrNull()
                if (n == null || n < 1 || n > pageCount) {
                    ctx.toast("1–$pageCount 사이로 입력하세요")
                    return
                }
                hideKeyboard()
                pickAndHide(onPick, n - 1)
            }
            edit.setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    go()
                    true
                } else {
                    false
                }
            }
            val button = ctx.label("이동", 16f, bold = true, color = Color.BLACK).apply {
                gravity = Gravity.CENTER
                background = rounded(Flex.YELLOW, 8f)
                setOnClickListener { go() }
            }
            val jump = ctx.horizontal { setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(8)) }
            jump.addView(edit, lp(0, ctx.dp(44), 1f))
            jump.addView(button, lp(ctx.dp(72), ctx.dp(44)).apply { leftMargin = ctx.dp(8) })
            col.addView(jump, lp())

            val contentW = (panelW - insetR).coerceAtLeast(ctx.dp(120))
            val tl = PageThumbList(
                ctx, pageCount, current, cache, (contentW * 0.6f).toInt(), requestThumb,
            ) { page -> pickAndHide(onPick, page) }
            thumbs = tl
            col.addView(tl.list, lp(MATCH_PARENT, 0, 1f))
            return col
        }

        private fun buildBookmarksTab(): View {
            val m = bookmarks()
            val adapter = PageRowAdapter(m)
            marksAdapter = adapter
            val frame = FrameLayout(activity)
            val list = listView().apply {
                this.adapter = adapter
                setOnItemClickListener { _, _, position, _ -> pickAndHide(onPick, adapter.pageAt(position)) }
            }
            frame.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val empty = emptyText("책갈피한 쪽이 없습니다")
            empty.visibility = if (m.isEmpty()) View.VISIBLE else View.GONE
            marksEmpty = empty
            frame.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return frame
        }

        private fun buildInkTab(): View {
            val ctx = activity
            val k = inkPages()
            val adapter = InkAdapter(k)
            inkAdapter = adapter
            val col = ctx.vertical()
            val frame = FrameLayout(ctx)
            val list = listView().apply {
                this.adapter = adapter
                setOnItemClickListener { _, _, position, _ -> pickAndHide(onPick, adapter.pageAt(position)) }
            }
            frame.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val empty = emptyText("필기한 쪽이 없습니다")
            empty.visibility = if (k.isEmpty()) View.VISIBLE else View.GONE
            inkEmpty = empty
            frame.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            col.addView(frame, lp(MATCH_PARENT, 0, 1f))

            val del = ctx.vertical()
            del.addView(divider())
            val btn = ctx.horizontal {
                gravity = Gravity.CENTER
                background = pressBg()
                setOnClickListener { onClearAllInk() }
            }
            btn.addView(ctx.icon(R.drawable.ic_delete, 20, Flex.DANGER))
            btn.addView(ctx.label("전체 필기 삭제", 16f, bold = true, color = Flex.DANGER).apply { setPadding(ctx.dp(8), 0, 0, 0) })
            del.addView(btn, lp(MATCH_PARENT, ctx.dp(52)))
            del.visibility = if (k.isEmpty()) View.GONE else View.VISIBLE
            inkDelete = del
            col.addView(del, lp())
            return col
        }
    }

    private class RowHolder(val label: TextView)

    /** Bookmark rows: yellow bookmark icon + "N쪽". */
    private inner class PageRowAdapter(private var pages: IntArray) : BaseAdapter() {
        fun set(p: IntArray) {
            pages = p
            notifyDataSetChanged()
        }

        fun pageAt(position: Int): Int = pages[position]

        override fun getCount(): Int = pages.size
        override fun getItem(position: Int): Any = pages[position]
        override fun getItemId(position: Int): Long = pages[position].toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val h: RowHolder
            val row: View
            if (convertView == null) {
                val ctx = activity
                val text = ctx.label("", 16f, color = Flex.TEXT)
                val line = ctx.horizontal {
                    minimumHeight = ctx.dp(56)
                    setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
                    addView(ctx.icon(R.drawable.ic_bookmark_fill, 22, Flex.YELLOW))
                    addView(text, lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(16) })
                }
                row = ctx.vertical {
                    background = pressBg()
                    layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    addView(line, lp())
                    addView(divider())
                }
                h = RowHolder(text)
                row.tag = h
            } else {
                row = convertView
                h = row.tag as RowHolder
            }
            h.label.text = "${pages[position] + 1}쪽"
            return row
        }
    }

    /** Ink rows: grey "페이지 N" header, then a dashed 72x96dp box with the pencil icon and "필기". */
    private inner class InkAdapter(private var pages: IntArray) : BaseAdapter() {
        fun set(p: IntArray) {
            pages = p
            notifyDataSetChanged()
        }

        fun pageAt(position: Int): Int = pages[position]

        override fun getCount(): Int = pages.size
        override fun getItem(position: Int): Any = pages[position]
        override fun getItemId(position: Int): Long = pages[position].toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val h: RowHolder
            val row: View
            if (convertView == null) {
                val ctx = activity
                val header = ctx.label("", 14f, color = Flex.GREY).apply { setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(6)) }
                val box = FrameLayout(ctx).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = ctx.dpF(6f)
                        setStroke(ctx.dp(1).coerceAtLeast(1), Flex.GREY, ctx.dpF(4f), ctx.dpF(3f))
                    }
                    addView(ctx.icon(R.drawable.ic_edit, 28, Flex.GREY), FrameLayout.LayoutParams(ctx.dp(28), ctx.dp(28), Gravity.CENTER))
                }
                val line = ctx.horizontal {
                    setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(14))
                    addView(box, LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(96)))
                    addView(ctx.label("필기", 16f, color = Flex.TEXT).apply { setPadding(ctx.dp(16), 0, 0, 0) })
                }
                row = ctx.vertical {
                    background = pressBg()
                    layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    addView(header, lp())
                    addView(line, lp())
                    addView(divider())
                }
                h = RowHolder(header)
                row.tag = h
            } else {
                row = convertView
                h = row.tag as RowHolder
            }
            h.label.text = "페이지 ${pages[position] + 1}"
            return row
        }
    }

    companion object {
        const val TAB_PAGES = 0
        const val TAB_BOOKMARKS = 1
        const val TAB_INK = 2

        private const val SLIDE_MS = 200L
        private const val THUMB_CACHE_KB = 16 * 1024
    }
}

private class ThumbHolder(
    val cell: FrameLayout,
    val card: FrameLayout,
    val image: ImageView,
    val chip: TextView,
) {
    /** Page this cell is bound to (-1: none). Main thread only. */
    var page = -1
}

/** One in-flight thumbnail request; [skipped] is set on the render thread when the cell was not wanted anymore. */
private class ThumbReq {
    @Volatile var skipped = false
}

/**
 * Vertical list of large page thumbnails (one per row, [cardW] px wide, portrait 1:1.414) with lazy, cancellable
 * requests, like [PdfThumbs]. [cache] is shared by the owner and outlives this list; the list itself is single use:
 * [close] it before dropping it so late results and `wanted` polls are ignored.
 */
private class PageThumbList(
    private val ctx: Activity,
    private val pageCount: Int,
    private val current: Int,
    private val cache: LruCache<Int, Bitmap>,
    cardW: Int,
    private val requestThumb: (page: Int, w: Int, h: Int, wanted: () -> Boolean, done: (Bitmap?) -> Unit) -> Unit,
    private val onPick: (page: Int) -> Unit,
) {
    private val cardW = cardW.coerceAtLeast(ctx.dp(60))
    private val cardH = (this.cardW * 1.414f).toInt()
    private val inset = ctx.dp(3)
    private val imgW = (this.cardW - 2 * inset).coerceAtLeast(1)
    private val imgH = (cardH - 2 * inset).coerceAtLeast(1)
    private val gap = ctx.dp(12)
    private val chipH = ctx.dp(22)
    private val rowH = gap + cardH + chipH / 2 + gap

    /** Pages whose cell is currently bound (visible); read from the render thread through `wanted`. */
    private val bound = ConcurrentHashMap<Int, Boolean>()
    /** The cell bound to each page (main thread). */
    private val holders = HashMap<Int, ThumbHolder>()

    /** Pages with a request in flight. Main thread only. */
    private val pending = HashSet<Int>()

    @Volatile private var closed = false

    val list: ListView = ctx.einkListView().apply {
        isScrollbarFadingEnabled = true
        setRecyclerListener { v ->
            val h = v.tag as? ThumbHolder ?: return@setRecyclerListener
            if (h.page >= 0) bound.remove(h.page)
            h.page = -1
        }
        setOnItemClickListener { _, _, position, _ -> onPick(position) }
        adapter = ThumbAdapter()
        setSelection(current.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    }

    fun close() {
        closed = true
        bound.clear()
        pending.clear()
    }

    private fun newHolder(): ThumbHolder {
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val card = FrameLayout(ctx).apply {
            setPadding(inset, inset, inset, inset)
            background = GradientDrawable().apply { setColor(Color.WHITE) }
            addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val chip = ctx.label("", 13f, color = Flex.TEXT, maxLines = 1).apply {
            gravity = Gravity.CENTER
            minWidth = ctx.dp(32)
            setPadding(ctx.dp(10), 0, ctx.dp(10), 0)
            background = GradientDrawable().apply {
                setColor(Flex.CHIP)
                cornerRadius = ctx.dpF(11f)
            }
        }
        val cell = FrameLayout(ctx).apply {
            layoutParams = AbsListView.LayoutParams(MATCH_PARENT, rowH)
            addView(card, FrameLayout.LayoutParams(cardW, cardH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = gap })
            addView(
                chip,
                FrameLayout.LayoutParams(WRAP_CONTENT, chipH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                    topMargin = gap + cardH - chipH / 2
                },
            )
        }
        return ThumbHolder(cell, card, image, chip).also { cell.tag = it }
    }

    private fun bind(h: ThumbHolder, page: Int) {
        if (h.page != page) {
            if (h.page >= 0) {
                bound.remove(h.page)
                if (holders[h.page] === h) holders.remove(h.page)
            }
            h.page = page
        }
        bound[page] = true
        holders[page] = h
        val isCurrent = page == current
        (h.card.background as GradientDrawable).setStroke(
            if (isCurrent) ctx.dp(3) else ctx.dp(1).coerceAtLeast(1),
            if (isCurrent) Flex.YELLOW else Flex.GREY,
        )
        h.chip.text = (page + 1).toString()
        h.chip.typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val cached = cache.get(page)
        if (cached != null) {
            h.image.setImageBitmap(cached)
        } else {
            h.image.setImageDrawable(null)
            request(h, page)
        }
    }

    private fun request(h: ThumbHolder, page: Int) {
        if (closed || !pending.add(page)) return
        val req = ThumbReq()
        requestThumb(
            page, imgW, imgH,
            {
                val ok = !closed && bound.containsKey(page)
                if (!ok) req.skipped = true
                ok
            },
            { bmp -> onThumb(h, page, req, bmp) },
        )
    }

    private fun onThumb(h: ThumbHolder, page: Int, req: ThumbReq, bmp: Bitmap?) {
        if (closed) return
        pending.remove(page)
        if (bmp != null) {
            cache.put(page, bmp)
            // The cell that shows the page now (it may have scrolled away and come back in another cell).
            val target = if (h.page == page) h else holders[page]
            if (target != null && target.page == page) target.image.setImageBitmap(bmp)
        } else if (req.skipped && h.page == page && bound.containsKey(page)) {
            // The cell scrolled away and came back before this skipped request was reported: ask again.
            request(h, page)
        }
    }

    private inner class ThumbAdapter : BaseAdapter() {
        override fun getCount(): Int = pageCount
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val h = (convertView?.tag as? ThumbHolder) ?: newHolder()
            bind(h, position)
            return h.cell
        }
    }
}
