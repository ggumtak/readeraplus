package com.ggumtak.readeraplus.reader.extras

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.reader.KeyMap
import com.ggumtak.readeraplus.reader.ReaderActivity
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkEditText
import com.ggumtak.readeraplus.ui.kit.InkPager
import com.ggumtak.readeraplus.ui.kit.InkPagerBar
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.einkListView
import com.ggumtak.readeraplus.ui.kit.fullScreenDialog
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.inkCursor
import com.ggumtak.readeraplus.ui.kit.inkPaging
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * The selection's 검색 (RIDI's): a full-screen panel over the reader with the query on top and six tabs, 본문 (this
 * book's matches), 국어사전, 영어사전, 한자사전, 백과사전 and AI (phone: ChatGPT; e-ink: lightweight Claude dictionary).
 * On a phone the tab row scrolls sideways and AI shows once it is swiped to; on e-ink all six share the width.
 */
object WordSearchPanel {
    /** Opens the panel for [query] (a selection, as selected) over [host]'s reader. Main thread. */
    fun show(host: ReaderHost, query: String) {
        val activity = host.activity
        if (activity.isFinishing || activity.isDestroyed) return
        WordSearchDialog(host, WordSearchQuery.initial(query)).show()
    }
}

/**
 * The panel. The 본문 tab is [SearchPanel]'s scan (its section-text cache, [SearchPanel.State] and [SearchPanel.jumpTo],
 * so a tapped match is highlighted and stepped through like any in-book search), run when the tab is first shown for
 * a query. Each web tab's WebView is created the first time its tab is shown and kept, with its page, until the panel
 * closes; a new query makes every tab load again when it is next shown (the visible one at once).
 */
private class WordSearchDialog(private val host: ReaderHost, initialQuery: String) {
    private val ctx = host.activity
    private val eink = DeviceClass.cached(ctx) != false
    private var aiDeviceEink: Boolean? = DeviceClass.cached(ctx)
    private var aiProbeStarted = false
    private val idleColor = if (eink) Ink.GRAY else PHONE_GRAY
    private val indicatorColor = if (eink) Ink.BLACK else PHONE_GRAY
    private val lineColor = if (eink) Ink.LINE else PHONE_LINE

    private val scope = MainScope()
    private var job: Job? = null
    private var closed = false

    /** The query as typed (trimmed): what the 본문 tab searches. */
    private var typed = initialQuery.trim()
    /** [typed] cleaned for the web pages. */
    private var webQ = WordSearchQuery.webQuery(typed)
    private var tab = WordSearchQuery.DEFAULT_TAB
    private val loads = WordSearchTabs(WordSearchQuery.TAB_COUNT)

    private var state: SearchPanel.State? = null
    private var docMissing = false
    private var chars = IntArray(0)

    private val webs = arrayOfNulls<WebView>(WordSearchQuery.TAB_COUNT)
    private val webLoading = BooleanArray(WordSearchQuery.TAB_COUNT)
    private val webFailed = BooleanArray(WordSearchQuery.TAB_COUNT)
    /** A web tab that loads a new query forgets its old pages once it has loaded (Back then leaves the panel). */
    private val clearHistoryAfter = BooleanArray(WordSearchQuery.TAB_COUNT)

    private lateinit var dialog: Dialog
    private lateinit var root: LinearLayout
    private lateinit var edit: EditText
    private lateinit var clear: View
    private lateinit var loadLine: View
    private lateinit var listPane: View
    private lateinit var list: ListView
    private lateinit var note: TextView
    private lateinit var message: TextView
    private lateinit var webFrame: FrameLayout
    private lateinit var webMessage: TextView
    private var pager: InkPager? = null
    private val tabLabels = arrayOfNulls<TextView>(WordSearchQuery.TAB_COUNT)
    private val tabBars = arrayOfNulls<View>(WordSearchQuery.TAB_COUNT)
    private val tabCells = arrayOfNulls<View>(WordSearchQuery.TAB_COUNT)
    /** The phone's sideways-scrolling tab row (null on e-ink, where the tabs share the width). */
    private var tabScroll: HorizontalScrollView? = null
    private val adapter = ResultAdapter()

    /** The pages were counted: the rows' page numbers and percents are read again, the list stays where it is. */
    private val countsListener: () -> Unit = {
        if (!closed && adapter.count > 0) adapter.notifyDataSetChanged()
    }

    fun show() {
        root = ctx.vertical {
            setBackgroundColor(Ink.WHITE)
            // The root takes the first focus: the field would otherwise be focused and open the keyboard.
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(buildBar(), lp())
        root.addView(buildTabs(), lp())
        root.addView(line())
        loadLine = View(ctx).apply { setBackgroundColor(Ink.BLACK); visibility = View.INVISIBLE }
        root.addView(loadLine, lp(MATCH_PARENT, ctx.dp(2)))
        root.addView(buildBody(), lp(MATCH_PARENT, 0, 1f))

        dialog = ctx.fullScreenDialog(root)
        dialog.setOnDismissListener { cleanup() }
        // The window can go away with its activity without a dismiss: the WebViews must not outlive it.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = cleanup()
        })
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        )
        dialog.setOnKeyListener { _, code, ev -> onKey(code, ev) }
        dialog.show()
        PanelRegistry.dialog(ctx, dialog)
        host.addCountsListener(countsListener)
        for (k in 0 until WordSearchQuery.TAB_COUNT) styleTab(k)
        showContent()
        if (typed.isEmpty()) showKeyboard()
    }

    // ------------------------------------------------------------------ views

    private fun line(): View = View(ctx).apply {
        setBackgroundColor(lineColor)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
    }

    private fun buildBar(): View {
        val bar = ctx.horizontal { minimumHeight = ctx.dp(56); setPadding(ctx.dp(4), 0, ctx.dp(4), 0) }
        bar.addView(ctx.flatIcon(R.drawable.ic_arrow_back, "뒤로") { dialog.dismiss() })
        edit = InkEditText(ctx).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(Ink.BLACK)
            textSize = 19f
            background = null
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            setText(typed)
            setSelection(typed.length)
            inkCursor(singleLine = true)
            // Enter's down starts the search and its up is consumed too (an unconsumed Enter moves the focus down).
            setOnEditorActionListener { _, actionId, ev ->
                val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    if (ev == null || ev.action == KeyEvent.ACTION_DOWN) search(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        bar.addView(edit, lp(0, WRAP_CONTENT, 1f))
        clear = ctx.flatIcon(R.drawable.ic_close, "지우기") { clearQuery() }
        clear.visibility = if (typed.isEmpty()) View.INVISIBLE else View.VISIBLE
        bar.addView(clear)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                if (clear.visibility != v) clear.visibility = v
            }
        })
        return bar
    }

    /**
     * The tabs. A phone's row keeps each label at its own width (RIDI's spacing) and scrolls sideways: the first five fit
     * a 384 dp screen with AI past its edge (user, 2026-10-10: "위의 바를 오른쪽으로 밀면 보일정도로"). E-ink shares the
     * width among all six at a smaller size, so nothing scrolls (no frames of motion).
     */
    private fun buildTabs(): View {
        val row = ctx.horizontal { gravity = Gravity.NO_GRAVITY }
        val sidePad = if (eink) 0 else ctx.dp(TAB_SIDE_DP)
        for (i in 0 until WordSearchQuery.TAB_COUNT) {
            val t = ctx.label(WordSearchQuery.title(i), if (eink) TAB_SP_EINK else TAB_SP, maxLines = 1).apply {
                gravity = Gravity.CENTER
                setPadding(sidePad, ctx.dp(14), sidePad, ctx.dp(12))
            }
            val bar = View(ctx)
            val cell = ctx.vertical {
                if (!eink) background = pressableBackground()
                setOnClickListener { select(i) }
                addView(t, lp())
                addView(bar, lp(MATCH_PARENT, ctx.dp(3)))
            }
            tabLabels[i] = t
            tabBars[i] = bar
            tabCells[i] = cell
            row.addView(cell, if (eink) lp(0, WRAP_CONTENT, 1f) else lp(WRAP_CONTENT, WRAP_CONTENT))
        }
        if (eink) return row
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // A screen wider than the six tabs still spreads the row across it.
            isFillViewport = true
            addView(row, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            tabScroll = this
        }
    }

    /** Scrolls the phone's tab row just far enough to show tab [i] whole. */
    private fun revealTab(i: Int) {
        val sv = tabScroll ?: return
        val cell = tabCells[i] ?: return
        if (sv.width <= 0) return
        val x = sv.scrollX
        when {
            cell.left < x -> sv.scrollTo(cell.left, 0)
            cell.right > x + sv.width -> sv.scrollTo(cell.right - sv.width, 0)
        }
    }

    private fun buildBody(): View {
        val body = FrameLayout(ctx)

        // 본문: the matches.
        val pane = ctx.vertical()
        note = ctx.label("", 13f, color = idleColor).apply {
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8))
            visibility = View.GONE
        }
        pane.addView(note, lp())
        list = ctx.einkListView()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> openHit(position) }
        val listFrame = FrameLayout(ctx)
        listFrame.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        message = ctx.emptyMessage("")
        message.visibility = View.GONE
        listFrame.addView(message, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        pane.addView(listFrame, lp(MATCH_PARENT, 0, 1f))
        if (eink) {
            // A page at a time, as the in-book search list: a fling would paint every scroll frame.
            val pagerBar = InkPagerBar(ctx)
            pane.addView(pagerBar)
            pager = list.inkPaging(pagerBar)
        }
        listPane = pane
        body.addView(pane, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // The web tabs: their WebViews are added here when first needed.
        webFrame = FrameLayout(ctx)
        webFrame.visibility = View.GONE
        webMessage = ctx.emptyMessage("")
        webMessage.visibility = View.GONE
        webFrame.addView(webMessage, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        body.addView(webFrame, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return body
    }

    private fun styleTab(k: Int) {
        val sel = k == tab
        tabLabels[k]?.apply {
            setTextColor(if (sel) Ink.BLACK else idleColor)
            typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        tabBars[k]?.setBackgroundColor(if (sel) indicatorColor else Color.TRANSPARENT)
    }

    private fun setText(tv: TextView, s: CharSequence) {
        if (tv.text.toString() != s.toString()) tv.text = s
    }

    // ------------------------------------------------------------------ query and tabs

    private fun select(i: Int) {
        if (i == tab) return
        val old = tab
        tab = i
        styleTab(old)
        styleTab(i)
        revealTab(i)
        showContent()
    }

    /** The query is searched: the 본문 list and the visible web page follow now, the other web pages when shown. */
    private fun search(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        hideKeyboard()
        typed = q
        webQ = WordSearchQuery.webQuery(q)
        loads.invalidate()
        showContent()
    }

    private fun clearQuery() {
        job?.cancel()
        edit.setText("")
        typed = ""
        // The web tabs drop the old word too: a tab shown next says 검색어가 없습니다 instead of loading it.
        webQ = ""
        state = null
        docMissing = false
        loads.invalidate()
        loads.markLoaded(WordSearchQuery.TAB_BODY)
        adapter.notifyDataSetChanged()
        updateBody()
        if (tab != WordSearchQuery.TAB_BODY) showWeb(tab)
        showKeyboard()
    }

    /** Shows the selected tab and brings it up to date with the query. */
    private fun showContent() {
        if (closed) return
        if (tab == WordSearchQuery.TAB_BODY) {
            listPane.visibility = View.VISIBLE
            webFrame.visibility = View.GONE
            showWeb(-1)
            if (loads.needsLoad(tab)) {
                if (startBodySearch()) loads.markLoaded(tab)
            } else {
                updateBody()
            }
        } else {
            listPane.visibility = View.GONE
            webFrame.visibility = View.VISIBLE
            showWeb(tab)
        }
    }

    private fun onKey(code: Int, ev: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_BACK) {
            val web = if (tab == WordSearchQuery.TAB_BODY) null else webs[tab]
            if (web == null || web.visibility != View.VISIBLE || !web.canGoBack()) return false
            if (ev.action == KeyEvent.ACTION_UP) web.goBack()
            return true
        }
        val p = pager
        // While typing, only the volume keys page the results (a learned key may be an ordinary text key).
        if (p == null || tab != WordSearchQuery.TAB_BODY || (edit.isFocused && !KeyMap.isVolumeKey(code))) return false
        val dir = ListKeys.direction(code, Settings.app)
        if (dir == 0) return false
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) p.page(dir)
        return true
    }

    // ------------------------------------------------------------------ 본문

    /** Starts the scan for [typed]. False while the book is not open (the tab tries again when shown). */
    private fun startBodySearch(): Boolean {
        job?.cancel()
        val q = typed
        if (q.isEmpty()) {
            state = null
            docMissing = false
            adapter.notifyDataSetChanged()
            updateBody()
            return true
        }
        // host.book throws while no book is open: only read it once a document is there.
        val doc = host.document
        if (doc == null) {
            state = null
            docMissing = true
            adapter.notifyDataSetChanged()
            updateBody()
            return false
        }
        docMissing = false
        chars = IntArray(doc.sections.size) { doc.sections[it].approxChars }
        val st = SearchPanel.State(runCatching { host.book.id }.getOrDefault(-1L), doc, q)
        state = st
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        updateBody()
        // Strong while this scan runs; between queries only the (soft) SearchPanel cache holds the texts.
        val texts = SearchPanel.textsFor(doc)
        val total = doc.sections.size
        job = scope.launch {
            withContext(Dispatchers.Default) {
                val batch = ArrayList<SearchPanel.Hit>()
                var count = 0
                var lastFlush = SystemClock.uptimeMillis()
                var s = 0
                while (s < total && count < SearchPanel.MAX_RESULTS) {
                    ensureActive()
                    val text = texts.text(s) { i -> runCatching { doc.loadSection(i).text }.getOrNull() }
                    if (text != null) {
                        TextSearch.scan(text, q) { off ->
                            batch.add(SearchPanel.Hit(s, off, off + q.length, buildSnippet(text, off, off + q.length)))
                            count++
                            count < SearchPanel.MAX_RESULTS
                        }
                    }
                    s++
                    val now = SystemClock.uptimeMillis()
                    val done = s >= total || count >= SearchPanel.MAX_RESULTS
                    if (done || now - lastFlush >= SearchPanel.FLUSH_MS) {
                        lastFlush = now
                        val out = ArrayList(batch)
                        batch.clear()
                        val scannedNow = s
                        val capped = count >= SearchPanel.MAX_RESULTS
                        withContext(Dispatchers.Main) {
                            st.hits.addAll(out)
                            st.scanned = scannedNow
                            if (done) {
                                st.complete = true
                                st.capped = capped
                            }
                            if (state === st) {
                                // Rebinding the list redraws every visible row: only when there is something new.
                                if (out.isNotEmpty() || done) adapter.notifyDataSetChanged()
                                updateBody()
                            }
                        }
                    }
                }
                if (total == 0) {
                    withContext(Dispatchers.Main) {
                        st.complete = true
                        if (state === st) updateBody()
                    }
                }
            }
        }
        return true
    }

    /** The row text: the context with the match in bold. */
    private fun buildSnippet(text: String, start: Int, end: Int): CharSequence {
        val sn = WordSearchQuery.snippet(text, start, end)
        val sp = SpannableString(sn.text)
        if (sn.hitEnd > sn.hitStart) {
            sp.setSpan(StyleSpan(Typeface.BOLD), sn.hitStart, sn.hitEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun updateBody() {
        val st = state
        val hits = st?.hits?.size ?: 0
        val msg = WordSearchQuery.bodyMessage(typed.isNotEmpty(), !docMissing, hits, st?.complete == true)
        setText(message, msg ?: "")
        message.visibility = if (msg == null) View.GONE else View.VISIBLE
        val n = if (st == null) "" else WordSearchQuery.bodyNote(hits, st.complete, st.capped, SearchPanel.MAX_RESULTS)
        setText(note, n)
        note.visibility = if (n.isEmpty()) View.GONE else View.VISIBLE
    }

    /** A match was tapped: the panel closes and the reader goes there (the place it left is remembered for 돌아가기). */
    private fun openHit(position: Int) {
        val st = state ?: return
        SearchPanel.remember(st)
        hideKeyboard()
        dialog.dismiss()
        SearchPanel.jumpTo(host, st, position, remember = true)
    }

    /** "34% | 128 페이지" of a match; the page number is [SearchPanel]'s (empty until the pages are counted). */
    private fun metaOf(h: SearchPanel.Hit): String {
        val pos = DocPosition(h.section, h.start)
        val counted = runCatching { host.pagesPending() }.getOrNull() == null
        val label = if (counted) runCatching { host.pageLabel(pos) }.getOrNull() else null
        val parsed = PageLabel.parse(label)
        val percent = WordSearchQuery.percent(parsed.page, parsed.total, counted, PageLabel.fractionOf(chars, pos))
        return WordSearchQuery.meta(percent, SearchPanel.pageOf(host, h))
    }

    private inner class ResultAdapter : BaseAdapter() {
        override fun getCount() = state?.hits?.size ?: 0
        override fun getItem(position: Int): Any? = state?.hits?.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: buildRow()
            val h = state?.hits?.getOrNull(position) ?: return row
            val cells = row.tag as RowCells
            cells.snippet.text = h.snippet
            setText(cells.meta, metaOf(h))
            return row
        }

        private fun buildRow(): View {
            val snippet = ctx.label("", 16f, maxLines = 2).apply { setLineSpacing(0f, 1.2f) }
            val meta = ctx.label("", 13f, color = idleColor)
            val text = ctx.vertical {
                setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(14))
                addView(snippet, lp())
                addView(meta, lp().apply { topMargin = ctx.dp(6) })
            }
            return ctx.vertical {
                if (!eink) background = pressableBackground()
                addView(text, lp())
                addView(line())
                tag = RowCells(snippet, meta)
            }
        }
    }

    private class RowCells(val snippet: TextView, val meta: TextView)

    // ------------------------------------------------------------------ web tabs

    /**
     * Makes web tab [visible] the visible one (-1: none, the 본문 tab shows) and loads the query in it when it has not
     * shown it yet; the other WebViews are hidden and paused.
     */
    private fun showWeb(visible: Int) {
        val web = WordSearchQuery.isWeb(visible)
        if (web && loads.needsLoad(visible)) {
            loads.markLoaded(visible)
            if (webQ.isNotEmpty()) loadWeb(visible)
        }
        val msg = if (visible == WordSearchQuery.TAB_AI && aiDeviceEink == null && webQ.isNotEmpty())
            "기기 확인 중…" else if (visible == WordSearchQuery.TAB_AI && aiDeviceEink == true && webFailed[visible])
            "AI 창을 열 수 없습니다. Android System WebView를 확인해 주세요."
            else if (web) WordSearchQuery.webMessage(webQ, webFailed[visible]) else null
        setText(webMessage, msg ?: "")
        webMessage.visibility = if (msg == null) View.GONE else View.VISIBLE
        for (k in WordSearchQuery.TAB_KO until WordSearchQuery.TAB_COUNT) {
            val w = webs[k] ?: continue
            if (k == visible && msg == null) {
                w.visibility = View.VISIBLE
                w.onResume()
            } else {
                w.visibility = View.GONE
                // The AI page still loading ahead of its tab ([preloadAi]) is not paused: that would stall it.
                if (!(k == WordSearchQuery.TAB_AI && webLoading[k])) w.onPause()
            }
        }
        updateLine()
    }

    private fun updateLine() {
        val show = WordSearchQuery.isWeb(tab) && webLoading[tab] && webs[tab]?.visibility == View.VISIBLE
        val v = if (show) View.VISIBLE else View.INVISIBLE
        if (loadLine.visibility != v) loadLine.visibility = v
    }

    /**
     * Loads the AI page behind the other tabs once the 국어사전 page has finished (user, 2026-10-10: the AI tab was slow to
     * open on the Comet): only for a query whose AI tab has not loaded yet, never while that tab is the one shown, and
     * silently (no browser fallback, no toast). The tab then shows a page that is ready or nearly.
     */
    private fun preloadAi() {
        aiDeviceEink = aiDeviceEink ?: DeviceClass.cached(ctx)
        // Opening another dictionary must not send a paid Claude question in a hidden tab.
        if (aiDeviceEink != false) return
        val ai = WordSearchQuery.TAB_AI
        if (closed || tab == ai || webQ.isEmpty() || !loads.needsLoad(ai)) return
        loads.markLoaded(ai)
        val existing = webs[ai]
        if (existing != null) {
            webFailed[ai] = false
            clearHistoryAfter[ai] = true
            existing.loadUrl(webUrl(ai))
            return
        }
        val web = createWeb(ai) ?: return
        web.visibility = View.GONE
        webs[ai] = web
        webFrame.addView(web, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        web.loadUrl(webUrl(ai))
    }

    private fun webUrl(i: Int): String = WordSearchQuery.url(i, webQ,
        einkAi = i == WordSearchQuery.TAB_AI && aiDeviceEink == true)

    /** Loads [webQ] in web tab [i], creating its WebView the first time. */
    private fun loadWeb(i: Int) {
        if (i == WordSearchQuery.TAB_AI && aiDeviceEink == null) {
            if (!aiProbeStarted) {
                aiProbeStarted = true
                DeviceClass.probeAsync(ctx) { known ->
                    if (closed) return@probeAsync
                    aiProbeStarted = false
                    aiDeviceEink = known
                    loads.markStale(WordSearchQuery.TAB_AI)
                    if (tab == WordSearchQuery.TAB_AI) showWeb(tab)
                    else if (!known) preloadAi()
                }
            }
            return
        }
        val url = webUrl(i)
        webFailed[i] = false
        val existing = webs[i]
        if (existing != null) {
            clearHistoryAfter[i] = true
            existing.loadUrl(url)
            return
        }
        val web = createWeb(i)
        if (web == null) {
            webFailed[i] = true
            if (i == WordSearchQuery.TAB_AI && aiDeviceEink == true)
                ctx.toast("AI 창을 열 수 없습니다. Android System WebView를 확인해 주세요")
            else {
                ctx.toast("웹 창을 열 수 없어 브라우저로 엽니다")
                TextActions.start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            return
        }
        webs[i] = web
        webFrame.addView(web, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        web.loadUrl(url)
    }

    /** Same settings and link rules as [LookupPanel]'s window; null when no WebView can be created. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWeb(i: Int): WebView? {
        val localAi = i == WordSearchQuery.TAB_AI && aiDeviceEink == true
        val web = try {
            WebView(ctx)
        } catch (_: Throwable) {
            return null
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            if (localAi) mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.setBackgroundColor(Ink.WHITE)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalFadingEdgeEnabled = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!localAi) return null
                val address = request.url.toString()
                if (EinkAiSite.isDocument(address) && request.method == "GET") {
                    // Asset IO happens on WebView's worker thread, with no hosting or sign-in request.
                    return WebResourceResponse("text/html", "UTF-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store", "Referrer-Policy" to "no-referrer",
                            "X-Content-Type-Options" to "nosniff"), ctx.assets.open(EinkAiSite.ASSET))
                }
                if (EinkAiSite.isApiRequest(address) && request.method in arrayOf("POST", "OPTIONS")) return null
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                    emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (localAi) {
                    val address = request.url.toString()
                    if (EinkAiSite.isDocument(address)) return false
                    if (request.isForMainFrame && request.hasGesture() && request.method == "GET" &&
                        EinkAiSite.isSourceLink(address)) {
                        TextActions.start(ctx, Intent(Intent.ACTION_VIEW, request.url))
                    }
                    return true
                }
                val scheme = request.url.scheme?.lowercase()
                // http(s) stays in the panel; intent://, market:// and the like are never followed.
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                webLoading[i] = true
                updateLine()
            }

            override fun onPageFinished(view: WebView, u: String?) {
                webLoading[i] = false
                if (clearHistoryAfter[i]) {
                    clearHistoryAfter[i] = false
                    view.clearHistory()
                }
                updateLine()
                // A page that was loaded ahead is paused until its tab is shown; the dictionary's end starts it.
                if (i == WordSearchQuery.TAB_AI && tab != i) view.onPause()
                if (i == WordSearchQuery.TAB_KO) view.post { preloadAi() }
            }
        }
        if (localAi) web.webChromeClient = object : WebChromeClient() {
            // The page's 사진 button: the picture reaches the page as a downscaled JPEG through readerAttach, so the
            // file input itself gets no file (file and content access stay off in this key-bearing WebView).
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null)
                pickAiImage(view)
                return true
            }
        }
        return web
    }

    private fun pickAiImage(web: WebView) {
        val reader = ctx as? ReaderActivity ?: return
        reader.pickImage { uri ->
            if (uri == null || closed) return@pickImage
            scope.launch {
                val image = withContext(Dispatchers.IO) { runCatching { AiImage.encode(ctx.contentResolver, uri) }.getOrNull() }
                if (closed) return@launch
                if (image == null) ctx.toast("사진을 불러오지 못했습니다")
                else web.evaluateJavascript(AiImage.attachScript(image), null)
            }
        }
    }

    // ------------------------------------------------------------------ closing

    /** The panel closed (or its window went away): stops the scan, drops the listener and takes the WebViews apart. */
    private fun cleanup() {
        if (closed) return
        closed = true
        host.removeCountsListener(countsListener)
        job?.cancel()
        scope.cancel()
        for (k in webs.indices) {
            val web = webs[k] ?: continue
            webs[k] = null
            runCatching {
                web.stopLoading()
                web.loadUrl("about:blank")
                (web.parent as? ViewGroup)?.removeView(web)
                web.removeAllViews()
                web.destroy()
            }
        }
    }

    private fun showKeyboard() {
        edit.requestFocus()
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        edit.post { imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(edit.windowToken, 0)
        root.requestFocus()
    }

    private companion object {
        /** RIDI's grey for the idle tabs and the grey lines of a phone; an e-ink screen takes [Ink.GRAY] and black. */
        const val PHONE_GRAY = 0xFF888888.toInt()
        const val PHONE_LINE = 0xFFE0E0E0.toInt()

        /** A phone's tab labels and the room on each side of one: 본문 … 백과사전 ≈ 370 dp, AI past a 384 dp edge. */
        const val TAB_SP = 15f
        const val TAB_SIDE_DP = 10

        /** E-ink: the six labels share the width (≈ 60 dp each on the Comet's 360 dp; 4 syllables at 14 sp ≈ 56). */
        const val TAB_SP_EINK = 14f
    }
}
