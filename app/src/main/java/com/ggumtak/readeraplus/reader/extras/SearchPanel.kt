package com.ggumtak.readeraplus.reader.extras

import android.app.Dialog
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.reader.KeyMap
import com.ggumtak.readeraplus.reader.ReaderHost
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkPager
import com.ggumtak.readeraplus.ui.kit.InkPagerBar
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.einkListView
import com.ggumtak.readeraplus.ui.kit.frameLp
import com.ggumtak.readeraplus.ui.kit.fullScreenDialog
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.InkEditText
import com.ggumtak.readeraplus.ui.kit.inkCursor
import com.ggumtak.readeraplus.ui.kit.inkPagerKeys
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
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference

/**
 * In-book full-text search: streaming results (snippet with the hit in bold + page number) paged a screen at a time
 * ([InkPager]), last query and results kept in memory per open document, and a small bar over the page to step through
 * hits (‹ ›).
 */
internal object SearchPanel {
    const val MAX_RESULTS = 1000
    /** Results and status reach the screen at most this often while scanning (each refresh is an e-ink update). */
    const val FLUSH_MS = 500L

    class Hit(val section: Int, val start: Int, val end: Int, val snippet: CharSequence)

    class State(val bookId: Long, doc: BookDocument, val query: String) {
        val docRef = WeakReference(doc)
        val hits = ArrayList<Hit>()
        var scanned = 0
        var complete = false
        var capped = false
    }

    private var last: State? = null

    /** Soft: section texts of the last searched document, reused by the next query (see [textsFor]). */
    @Volatile private var textCache: SoftReference<SectionTextCache>? = null

    fun show(host: ReaderHost, initialQuery: String) {
        // host.book throws while no book is open (between books): only read it once a document is there.
        val doc = host.document ?: run {
            host.activity.toast("책을 여는 중입니다")
            return
        }
        val prev = last?.takeIf { it.docRef.get() === doc && it.bookId == host.book.id }
        SearchDialog(host, prev, initialQuery.trim()).show()
    }

    /** Section-text cache for [doc], created (replacing another document's) when needed. Main thread. */
    internal fun textsFor(doc: BookDocument): SectionTextCache {
        val n = doc.sections.size
        textCache?.get()?.takeIf { it.isFor(doc, n) }?.let { return it }
        return SectionTextCache(doc, n).also { textCache = SoftReference(it) }
    }

    /** Releases the cached section texts (the book closed). */
    internal fun dropTextCache() {
        textCache = null
    }

    internal fun remember(state: State) {
        last = state
    }

    /** The user cleared the query: reopening search must not bring the old results back. */
    internal fun forget() {
        last = null
    }

    fun buildSnippet(text: String, start: Int, end: Int): CharSequence {
        val sn = TextSearch.snippet(text, start, end, 30)
        val sp = SpannableString(sn.text)
        if (sn.hitEnd > sn.hitStart) {
            sp.setSpan(StyleSpan(android.graphics.Typeface.BOLD), sn.hitStart, sn.hitEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(UnderlineSpan(), sn.hitStart, sn.hitEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    /**
     * A hit's page, read from the current layout every time (the results outlive a relayout, e.g. a font size change;
     * [ReaderHost.pageLabel] is a lookup in the counts); empty while the pages are counted, so it never shows an estimate.
     */
    fun pageOf(host: ReaderHost, h: Hit): String {
        if (runCatching { host.pagesPending() }.getOrNull() != null) return ""
        return PageLabel.pageOnly(runCatching { host.pageLabel(DocPosition(h.section, h.start)) }.getOrNull())
    }

    // ------------------------------------------------------------------ highlight / navigation

    private var highlightedSection = -1

    fun jumpTo(host: ReaderHost, state: State, index: Int, remember: Boolean) {
        val h = state.hits.getOrNull(index) ?: return
        val doc = host.document
        if (doc == null || state.docRef.get() !== doc) {
            // Results of a document that is no longer open (another book, or re-opened with new parse options).
            SearchNavBar.remove()
            return
        }
        if (highlightedSection >= 0 && highlightedSection != h.section) {
            runCatching { host.setHighlights("search", highlightedSection, emptyList()) }
        }
        host.goTo(DocPosition(h.section, h.start), remember)
        // Like ReadEra: every match of the query in this section is marked, not only the tapped one.
        host.setHighlights("search", h.section, sectionHighlights(state, h.section))
        highlightedSection = h.section
        SearchNavBar.show(host, state, index)
    }

    private fun sectionHighlights(state: State, section: Int): List<Highlight> {
        val out = ArrayList<Highlight>()
        for (x in state.hits) if (x.section == section) out.add(Highlight(x.start, x.end, HighlightKind.SEARCH))
        return out
    }

    fun clearHighlight(host: ReaderHost) {
        if (highlightedSection >= 0) runCatching { host.setHighlights("search", highlightedSection, emptyList()) }
        highlightedSection = -1
    }
}

/** The search screen (full-screen dialog). */
private class SearchDialog(private val host: ReaderHost, private var state: SearchPanel.State?, private val initialQuery: String) {
    private val ctx = host.activity
    /** Only constructed while a document is open (SearchPanel.show). */
    private val doc0 = host.document
    private val bookId = runCatching { host.book.id }.getOrDefault(-1L)
    private lateinit var list: ListView
    private lateinit var pager: InkPager
    private val scope = MainScope()
    private var job: Job? = null
    private lateinit var dialog: Dialog
    private lateinit var edit: EditText
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private val adapter = ResultAdapter()
    /** The pages are counted (or counting failed): the rows' page numbers are read again, the list stays where it is. */
    private val countsListener: () -> Unit = {
        if (::dialog.isInitialized && dialog.isShowing && adapter.count > 0) adapter.notifyDataSetChanged()
    }

    fun show() {
        val root = ctx.vertical { setBackgroundColor(Ink.WHITE) }
        val bar = ctx.horizontal { minimumHeight = ctx.dp(56); setPadding(ctx.dp(4), 0, ctx.dp(4), 0) }
        bar.addView(ctx.flatIcon(R.drawable.ic_arrow_back, "뒤로") { dialog.dismiss() })
        edit = InkEditText(ctx).apply {
            hint = "책에서 검색"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(Ink.BLACK)
            setHintTextColor(Ink.GRAY)
            textSize = 19f
            background = null
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            inkCursor(singleLine = true)
            // Enter's down starts the search and its up is consumed too (an unconsumed Enter moves the focus down).
            setOnEditorActionListener { _, actionId, ev ->
                val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    if (ev == null || ev.action == KeyEvent.ACTION_DOWN) startSearch(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        bar.addView(edit, lp(0, WRAP_CONTENT, 1f))
        bar.addView(ctx.flatIcon(R.drawable.ic_search, "검색") { startSearch(edit.text.toString()) })
        bar.addView(ctx.flatIcon(R.drawable.ic_close, "지우기") {
            // Nothing typed and nothing found: × closes search, as it looks like it should.
            if (edit.text.isNullOrEmpty() && state == null) {
                dialog.dismiss()
                return@flatIcon
            }
            job?.cancel()
            edit.setText("")
            state = null
            SearchPanel.forget()
            SearchPanel.clearHighlight(host)
            adapter.notifyDataSetChanged()
            status.text = ""
            updateEmpty()
            showKeyboard()
        })
        root.addView(bar, lp())
        root.addView(ctx.hairline())
        status = ctx.label("", 14f, color = Ink.GRAY).apply { setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8)) }
        root.addView(status, lp())
        root.addView(ctx.hairline())
        val frame = FrameLayout(ctx)
        list = ctx.einkListView()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val st = state ?: return@setOnItemClickListener
            SearchPanel.remember(st)
            hideKeyboard()
            dialog.dismiss()
            SearchPanel.jumpTo(host, st, position, remember = true)
        }
        frame.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        empty = ctx.emptyMessage("")
        frame.addView(empty, frame.frameLp(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(frame, lp(MATCH_PARENT, 0, 1f))
        val pagerBar = InkPagerBar(ctx)
        root.addView(pagerBar)
        pager = list.inkPaging(pagerBar)

        dialog = ctx.fullScreenDialog(root)
        dialog.setOnDismissListener {
            host.removeCountsListener(countsListener)
            job?.cancel()
            scope.cancel()
            state?.let { SearchPanel.remember(it) }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // While typing, only the volume keys page the results (a learned key may be an ordinary text key).
        dialog.inkPagerKeys({ pager }, { code -> ListKeys.direction(code, Settings.app) }) { ev ->
            edit.isFocused && !KeyMap.isVolumeKey(ev.keyCode)
        }

        val st = state
        when {
            initialQuery.isNotEmpty() && (st == null || st.query != initialQuery) -> {
                edit.setText(initialQuery)
                edit.setSelection(initialQuery.length)
                dialog.show()
                startSearch(initialQuery)
            }
            st != null -> {
                edit.setText(st.query)
                edit.setSelection(st.query.length)
                dialog.show()
                if (!st.complete) resume(st) else renderStatus()
                updateEmpty()
            }
            else -> {
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,
                )
                dialog.show()
                edit.requestFocus()
                updateEmpty()
            }
        }
        PanelRegistry.dialog(ctx, dialog)
        host.addCountsListener(countsListener)
    }

    private fun startSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        hideKeyboard()
        val doc = host.document
        if (doc == null || doc !== doc0) {
            // No document, or another one than this dialog was opened for (the book changed underneath).
            status.text = "책을 여는 중입니다 · 잠시 뒤 다시 검색하세요"
            return
        }
        job?.cancel()
        SearchPanel.clearHighlight(host)
        val st = SearchPanel.State(bookId, doc, q)
        state = st
        SearchPanel.remember(st)
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        resume(st)
    }

    /** Scans sections from [SearchPanel.State.scanned] on a background thread, streaming hits every [SearchPanel.FLUSH_MS]. */
    private fun resume(st: SearchPanel.State) {
        val doc = st.docRef.get() ?: return
        val total = doc.sections.size
        val q = st.query
        // Strong while this scan runs; between queries only the (soft) SearchPanel cache holds the texts.
        val texts = SearchPanel.textsFor(doc)
        renderStatus()
        updateEmpty()
        job = scope.launch {
            withContext(Dispatchers.Default) {
                val batch = ArrayList<SearchPanel.Hit>()
                var count = st.hits.size
                var lastFlush = SystemClock.uptimeMillis()
                var s = st.scanned
                while (s < total && count < SearchPanel.MAX_RESULTS) {
                    ensureActive()
                    val text = texts.text(s) { i -> runCatching { doc.loadSection(i).text }.getOrNull() }
                    if (text != null) {
                        TextSearch.scan(text, q) { off ->
                            batch.add(SearchPanel.Hit(s, off, off + q.length, SearchPanel.buildSnippet(text, off, off + q.length)))
                            count++
                            count < SearchPanel.MAX_RESULTS
                        }
                    }
                    s++
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFlush >= SearchPanel.FLUSH_MS || s >= total || count >= SearchPanel.MAX_RESULTS) {
                        lastFlush = now
                        val out = ArrayList(batch)
                        batch.clear()
                        val scannedNow = s
                        val done = s >= total || count >= SearchPanel.MAX_RESULTS
                        withContext(Dispatchers.Main) {
                            st.hits.addAll(out)
                            st.scanned = scannedNow
                            if (done) {
                                st.complete = true
                                st.capped = count >= SearchPanel.MAX_RESULTS
                            }
                            if (state === st) {
                                // Rebinding the list redraws every visible row: only when there is something new.
                                if (out.isNotEmpty() || done) adapter.notifyDataSetChanged()
                                renderStatus()
                                updateEmpty()
                            }
                        }
                    }
                }
                if (total == 0) {
                    withContext(Dispatchers.Main) {
                        st.complete = true
                        renderStatus()
                        updateEmpty()
                    }
                }
            }
        }
    }

    private fun renderStatus() {
        val st = state ?: run { status.text = ""; return }
        val total = st.docRef.get()?.sections?.size ?: 0
        status.text = SearchText.status(st.scanned, total, st.hits.size, st.complete, st.capped, SearchPanel.MAX_RESULTS)
    }

    private fun updateEmpty() {
        val st = state
        empty.visibility = when {
            st == null -> View.VISIBLE.also { empty.text = "찾을 단어나 문장을 입력하세요" }
            st.complete && st.hits.isEmpty() -> View.VISIBLE.also { empty.text = SearchText.noHits(st.query) }
            else -> View.GONE
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
        edit.clearFocus()
    }

    private inner class ResultAdapter : BaseAdapter() {
        override fun getCount() = state?.hits?.size ?: 0
        override fun getItem(position: Int): Any? = state?.hits?.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val h = state?.hits?.getOrNull(position) ?: return row
            row.findViewWithTag<TextView>("snippet").text = h.snippet
            row.findViewWithTag<TextView>("page").text = SearchPanel.pageOf(host, h)
            return row
        }

        private fun buildRow(): LinearLayout = ctx.vertical {
            background = pressableBackground()
            val r = ctx.horizontal { setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(12), ctx.dp(12)) }
            r.addView(ctx.label("", 16f, maxLines = 3).apply { tag = "snippet"; setLineSpacing(0f, 1.2f) }, lp(0, WRAP_CONTENT, 1f))
            // As the 목차's page column: the hit's text is what the eye looks for.
            r.addView(ctx.label("", 15f, color = Ink.GRAY).apply { tag = "page"; gravity = Gravity.END; minWidth = ctx.dp(44) })
            addView(r, lp())
            addView(ctx.hairline())
        }
    }
}

/** Bar over the page after jumping to a hit: [×]  "검색어" 3 / 57  [≡] [‹] [›]. */
internal object SearchNavBar {
    /** Weak: the bar belongs to the reader window; a static strong reference would leak the activity. */
    private var barRef: WeakReference<View>? = null
    /** What the bar shows, to relabel it when the page numbers change (all weak, like [barRef]). */
    private var hostRef: WeakReference<ReaderHost>? = null
    private var stateRef: WeakReference<SearchPanel.State>? = null
    private var labelRef: WeakReference<TextView>? = null
    private var shownIndex = 0
    /** While the bar is shown: the pages are counted, so its page number is read again. */
    private val countsListener: () -> Unit = { refresh() }

    private fun labelText(host: ReaderHost, state: SearchPanel.State, index: Int): String {
        val more = if (state.complete) "" else "+"
        return PageLabel.withPage("${index + 1} / ${state.hits.size}$more", SearchPanel.pageOf(host, state.hits[index]))
    }

    /** Reads the bar's page number again (the pages were counted, or the layout changed); no-op without a bar. */
    fun refresh() {
        val host = hostRef?.get()
        val state = stateRef?.get()
        val label = labelRef?.get()
        if (host == null || state == null || label == null || label.parent == null) return
        if (shownIndex !in state.hits.indices) return
        val text = labelText(host, state, shownIndex)
        if (label.text.toString() != text) label.text = text
    }

    fun show(host: ReaderHost, state: SearchPanel.State, index: Int) {
        val parent = Overlay.parentOf(host) ?: return
        val ctx = host.activity
        remove()
        if (index !in state.hits.indices) return
        shownIndex = index
        val row = Overlay.bar(ctx)
        row.addView(ctx.flatIcon(R.drawable.ic_close, "검색 닫기") {
            SearchPanel.clearHighlight(host)
            remove()
        })
        val texts = ctx.vertical { gravity = Gravity.CENTER_VERTICAL }
        texts.addView(ctx.label("‘${state.query}’", 15f, bold = true, maxLines = 1))
        val label = ctx.label(labelText(host, state, index), 14f, color = Ink.GRAY)
        texts.addView(label)
        row.addView(texts, lp(0, WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(4) })
        row.addView(ctx.flatIcon(R.drawable.ic_view_list, "검색 결과 목록") {
            remove()
            SearchPanel.show(host, "")
        })
        row.addView(ctx.flatIcon(R.drawable.ic_chevron_left, "이전 결과") {
            if (index > 0) SearchPanel.jumpTo(host, state, index - 1, remember = false)
        }.apply { visibility = if (index > 0) View.VISIBLE else View.INVISIBLE })
        row.addView(ctx.flatIcon(R.drawable.ic_chevron_right, "다음 결과") {
            if (index < state.hits.size - 1) SearchPanel.jumpTo(host, state, index + 1, remember = false)
        }.apply { visibility = if (index < state.hits.size - 1) View.VISIBLE else View.INVISIBLE })
        row.setPadding(row.paddingLeft, row.paddingTop, row.paddingRight, Overlay.bottomInset(host.pageView))
        parent.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        barRef = WeakReference(row)
        hostRef = WeakReference(host)
        stateRef = WeakReference(state)
        labelRef = WeakReference(label)
        host.addCountsListener(countsListener)
    }

    fun isShown(): Boolean = barRef?.get()?.parent != null

    /** A bar is shown over [host]'s page. */
    fun isShown(host: ReaderHost): Boolean {
        val bar = barRef?.get() ?: return false
        return bar.parent != null && bar.context === runCatching { host.activity }.getOrNull()
    }

    fun remove() {
        hostRef?.get()?.removeCountsListener(countsListener)
        barRef?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        barRef = null
        hostRef = null
        stateRef = null
        labelRef = null
    }
}
