package com.ggumtak.readeraplus.ui.notes

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.NoteBook
import com.ggumtak.readeraplus.data.NoteKind
import com.ggumtak.readeraplus.data.NoteRef
import com.ggumtak.readeraplus.data.NoteRow
import com.ggumtak.readeraplus.data.Notes
import com.ggumtak.readeraplus.data.NotesCounts
import com.ggumtak.readeraplus.data.NotesExport
import com.ggumtak.readeraplus.data.NotesOrder
import com.ggumtak.readeraplus.data.NotesPage
import com.ggumtak.readeraplus.data.NotesQuery
import com.ggumtak.readeraplus.data.NotesTab
import com.ggumtak.readeraplus.reader.ReaderIo
import com.ggumtak.readeraplus.reader.extras.emptyMessage
import com.ggumtak.readeraplus.reader.extras.outlineButton
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.render.QuoteLook
import com.ggumtak.readeraplus.render.QuoteStyles
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkListView
import com.ggumtak.readeraplus.ui.kit.InkPagerBar
import com.ggumtak.readeraplus.ui.kit.ListPager
import com.ggumtak.readeraplus.ui.kit.ListPaging
import com.ggumtak.readeraplus.ui.kit.PagerMath
import com.ggumtak.readeraplus.ui.kit.ToolbarAction
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.inkCursor
import com.ggumtak.readeraplus.ui.kit.keepAll
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.ownMessage
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.toolbar
import com.ggumtak.readeraplus.ui.kit.userMessage
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.library.LibraryText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.ref.WeakReference

/**
 * 독서 노트 (N §9): every book's quotes, memos, bookmarks, reviews and looked-up words in one list. The hub only
 * queries the database (data/Notes, data/Lookups) and never opens a book file. E-ink first: opening is one screen
 * update (the first draw waits up to 250 ms for the counts and the first page), a page turn is one layout and one
 * draw with no query, tab/chip/order changes are one redraw each, and nothing animates or ticks.
 */
class NotesActivity : Activity() {
    companion object {
        const val EXTRA_TAB="notes_tab"; const val EXTRA_BOOK_ID="notes_book"
        fun open(ctx: Context, tab: NotesTab?=null, bookId: Long=-1L) {
            ctx.startActivity(Intent(ctx,NotesActivity::class.java).apply {
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (tab!=null) putExtra(EXTRA_TAB,tab.name)
                putExtra(EXTRA_BOOK_ID,bookId)
            })
        }

        internal const val PREF_TAB = "notes.tab"
        internal const val PREF_ORDER = "notes.order"
        internal const val PREF_WORDS_ONCE = "notes.wordsOnce"

        private const val STATE_TAB = "notes.s.tab"
        private const val STATE_ORDER = "notes.s.order"
        private const val STATE_BOOK = "notes.s.book"
        private const val STATE_TEXT = "notes.s.text"
        private const val STATE_STYLE = "notes.s.style"
        private const val STATE_FIRST = "notes.s.first"
        private const val STATE_SELECTING = "notes.s.selecting"
        private const val STATE_SELECTION = "notes.s.selection"
        private const val STATE_SEARCH_OPEN = "notes.s.searchOpen"
        private const val STATE_EXPORT_FORMAT = "notes.s.exportFormat"
        private const val STATE_EXPORT_REFS = "notes.s.exportRefs"
        private const val STATE_EXPORT_ALL = "notes.s.exportAll"

        internal const val REQ_EXPORT = 7301
    }

    /** `adb shell setprop log.tag.RANotes DEBUG` (before the app starts) logs the hub's load timings. */
    internal object NotesPerf {
        const val TAG = "RANotes"
        @JvmField val on: Boolean = try { Log.isLoggable(TAG, Log.DEBUG) } catch (_: Throwable) { false }
        fun log(msg: String) { if (on) Log.d(TAG, msg) }
    }

    internal val scope = MainScope()
    internal val handler = Handler(Looper.getMainLooper())
    internal val menus = NotesMenus(this)

    // ---- query and data
    internal var q = NotesQuery()
        private set
    internal var counts = NotesCounts(0, 0, 0, 0, 0)
        private set
    /** Every book with notes under the query without its book filter (meta lines, the chooser, headers). */
    private var bookMap: Map<Long, NoteBook> = emptyMap()
    internal var allBooks: List<NoteBook> = emptyList()
        private set
    /** Books the pages are grouped by (book orders only). */
    private var pageBooks: List<NoteBook>? = null
    /** The query the shown rows belong to: page loads use it (never [q] while a reload of a new query is in flight). */
    private var windowQ = NotesQuery()
    /** Runs in the next [apply], before its one redraw (e.g. leaving selection mode after a delete). */
    private var afterApply: (() -> Unit)? = null
    /** The filtered book (chip title), when [NotesQuery.bookId] is set. */
    internal var filterBook: NoteBook? = null
        private set
    private val rowWindow = NotesWindow<NotesPage>(Notes.PAGE_ROWS)
    /** `Library.notesGen` the shown data was read under. */
    private var loadedGen = Long.MIN_VALUE
    private var reloadToken = 0
    private var loading = false
    private var openedAt = 0L

    // ---- selection (N §9.4): packed NoteRefs; survives paging and tab switches
    internal val selected = HashSet<Long>()
    internal var selecting = false
        private set

    // ---- export in flight across the document picker (N §9.10)
    private var exportFormat: NotesExport.Format? = null
    private var exportRefs: LongArray? = null
    private var exporting = false

    // ---- views
    private lateinit var normalBar: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var selectTitle: TextView
    private lateinit var selectColour: ImageButton
    private val tabViews = ArrayList<Pair<TextView, View>>()
    private lateinit var bookChip: TextView
    private lateinit var orderChip: TextView
    private lateinit var styleChip: TextView
    private lateinit var searchRow: LinearLayout
    private lateinit var searchEdit: EditText
    private lateinit var list: InkListView
    private lateinit var bar: InkPagerBar
    internal lateinit var pager: ListPager
        private set
    private lateinit var emptyBox: ScrollView
    private lateinit var emptyText: TextView
    private lateinit var emptyButton: TextView
    private lateinit var adapter: NotesAdapter
    private var paged = false

    // ---- one e-ink update to open: hold the first draw until the counts and page 0 are in (≤ 250 ms)
    private var holdDraw = false
    private val drawHold = ViewTreeObserver.OnPreDrawListener { !holdDraw }
    private val releaseHold = Runnable { releaseDrawHold() }
    private val moveTimeout = Runnable { val r = rowWindow.takeMove(); if (r >= 0) showRow(r) }
    private val searchRunnable = Runnable { applySearch(searchEdit.text.toString()) }
    private var pendingFirst = 0
    /** The pending export's refs were saved to a cache file that is gone: its result only reports the failure. */
    private var exportLost = false

    // ============================================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedAt = SystemClock.uptimeMillis()
        QuoteLook.update(Settings.app.highlightLook, DeviceClass.cached(this))
        q = initialQuery(savedInstanceState)
        // A failed first load falls back to the requested query (book, tab, order, search), not to NotesQuery();
        // rowWindow is empty until the first apply, so no page load reads this windowQ.
        windowQ = q
        val s = savedInstanceState
        if (s != null) {
            pendingFirst = s.getInt(STATE_FIRST, 0)
            if (s.getBoolean(STATE_SELECTING)) {
                selecting = true
                // A lost file restores no rows: never a wider selection than the user made.
                restoreRefs(s, STATE_SELECTION)?.forEach { selected += it }
            }
            exportFormat = s.getString(STATE_EXPORT_FORMAT)?.let { n -> NotesExport.Format.entries.firstOrNull { it.name == n } }
            if (exportFormat != null && !s.getBoolean(STATE_EXPORT_ALL)) {
                exportRefs = restoreRefs(s, STATE_EXPORT_REFS)
                if (exportRefs == null) {
                    exportFormat = null
                    exportLost = true
                }
            }
        }
        setContentView(buildUi())
        if (s?.getBoolean(STATE_SEARCH_OPEN) == true || q.text.isNotEmpty()) openSearch(focus = false)
        if (selecting) enterSelection(null)
        holdDraw = true
        window.decorView.viewTreeObserver.addOnPreDrawListener(drawHold)
        handler.postDelayed(releaseHold, NotesWindow.FIRST_DRAW_WAIT_MS)
        reload(pendingFirst)
    }

    private fun initialQuery(s: Bundle?): NotesQuery {
        val raw = Settings.raw()
        val wordsOnce = raw.getBoolean(PREF_WORDS_ONCE, false)
        if (s != null) {
            val tab = NotesTab.entries.firstOrNull { it.name == s.getString(STATE_TAB) } ?: NotesTab.ALL
            return NotesQuery(
                tab = tab,
                order = NotesOrder.entries.firstOrNull { it.name == s.getString(STATE_ORDER) } ?: NotesOrder.NEWEST,
                bookId = s.getLong(STATE_BOOK, -1L).takeIf { it > 0 },
                text = s.getString(STATE_TEXT).orEmpty(),
                style = s.getInt(STATE_STYLE, -1).takeIf { it >= 0 && tab == NotesTab.QUOTES },
                wordsOnce = wordsOnce && tab == NotesTab.WORDS,
            )
        }
        // Intent extras win over the remembered tab.
        val i = intent
        val tab = NotesTab.entries.firstOrNull { it.name == i?.getStringExtra(EXTRA_TAB) }
            ?: NotesTab.entries.firstOrNull { it.name == raw.getString(PREF_TAB, null) } ?: NotesTab.ALL
        val order = NotesOrder.entries.firstOrNull { it.name == raw.getString(PREF_ORDER, null) } ?: NotesOrder.NEWEST
        val book = i?.getLongExtra(EXTRA_BOOK_ID, -1L)?.takeIf { it > 0 }
        return NotesQuery(tab = tab, order = order, bookId = book, wordsOnce = wordsOnce && tab == NotesTab.WORDS)
    }

    override fun onResume() {
        super.onResume()
        // Back from the reader or another screen: reload only when a note changed (no idle redraw).
        if (!loading && loadedGen != Long.MIN_VALUE && Library.notesGen != loadedGen) reload(list.firstVisiblePosition)
        else if (!loading && rowWindow.count == 0 && ::emptyBox.isInitialized) updateEmpty() // e.g. 단어 기록 toggled in settings
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(STATE_TAB, q.tab.name)
        out.putString(STATE_ORDER, q.order.name)
        out.putLong(STATE_BOOK, q.bookId ?: -1L)
        out.putString(STATE_TEXT, q.text)
        out.putInt(STATE_STYLE, q.style ?: -1)
        out.putInt(STATE_FIRST, if (::list.isInitialized) list.firstVisiblePosition else pendingFirst)
        out.putBoolean(STATE_SEARCH_OPEN, ::searchRow.isInitialized && searchRow.visibility == View.VISIBLE)
        out.putBoolean(STATE_SELECTING, selecting)
        if (selecting) saveRefs(out, STATE_SELECTION, selected.toLongArray())
        val f = exportFormat
        if (f != null) {
            out.putString(STATE_EXPORT_FORMAT, f.name)
            val refs = exportRefs
            if (refs == null) out.putBoolean(STATE_EXPORT_ALL, true) else saveRefs(out, STATE_EXPORT_REFS, refs)
        }
    }

    /**
     * Saves [refs] under [key]: in the Bundle, or above [NotesWindow.MAX_SAVED_SELECTION] in a cache file named there
     * (the Bundle stays far below the 1 MB binder limit). Exact either way, never "select all" (N §9.4).
     */
    private fun saveRefs(out: Bundle, key: String, refs: LongArray) {
        if (!NotesWindow.saveToFile(refs.size)) {
            out.putLongArray(key, refs)
            return
        }
        val name = "$key.refs"
        try {
            File(cacheDir, name).outputStream().use { NotesWindow.writeRefs(it, refs) }
            out.putString("$key.file", name)
        } catch (t: Throwable) {
            Log.w(NotesPerf.TAG, "saving $key failed", t)
        }
    }

    /** [saveRefs]'s refs back; null when none were saved or the file is gone. */
    private fun restoreRefs(s: Bundle, key: String): LongArray? {
        s.getLongArray(key)?.let { return it }
        val name = s.getString("$key.file") ?: return null
        return try {
            File(cacheDir, name).inputStream().use { NotesWindow.readRefs(it) }
        } catch (t: Throwable) {
            Log.w(NotesPerf.TAG, "restoring $key failed", t)
            null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseDrawHold()
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Platform back handling (no AndroidX); targetSdk 34 still routes back here.")
    override fun onBackPressed() {
        if (selecting) {
            endSelection()
            return
        }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Page keys / volume keys page the list (paged mode), as in the library. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (paged && ::list.isInitialized) {
            val code = event.keyCode
            val dir = LibraryText.pageDirection(code, Settings.app)
            val isVolume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
            if (dir != 0 && (!searchEdit.isFocused || isVolume)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) pager.page(dir)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun releaseDrawHold() {
        handler.removeCallbacks(releaseHold)
        if (!holdDraw) return
        holdDraw = false
        val observer = window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(drawHold)
        window.decorView.invalidate()
    }

    // ============================================================================================ UI build

    private fun buildUi(): View {
        val root = vertical { setBackgroundColor(Ink.WHITE) }
        normalBar = toolbar("독서 노트", R.drawable.ic_arrow_back, onNav = { finish() }, actions = listOf(
            ToolbarAction(R.drawable.ic_search, "검색") { toggleSearch() },
            ToolbarAction(R.drawable.ic_more_vert, "더보기") { menus.overflow(it) },
        ))
        root.addView(normalBar, lp())
        selectBar = buildSelectBar()
        selectBar.visibility = View.GONE
        root.addView(selectBar, lp())

        val tabs = horizontal { minimumHeight = dp(48) }
        for (t in NotesTab.entries) {
            val cell = FrameLayout(this).apply {
                setOnClickListener { setTab(t) }
                contentDescription = t.label
            }
            val text = label(t.label, 15f, maxLines = 1).apply { gravity = Gravity.CENTER }
            val underline = View(this).apply { setBackgroundColor(Ink.BLACK) }
            cell.addView(text, FrameLayout.LayoutParams(MATCH_PARENT, dp(48), Gravity.CENTER))
            cell.addView(underline, FrameLayout.LayoutParams(dp(40), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            tabViews += text to underline
            tabs.addView(cell, lp(0, dp(48), 1f))
        }
        root.addView(tabs, lp())
        root.addView(hairline())

        val filters = horizontal {
            minimumHeight = dp(44)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        bookChip = chip { if (q.bookId != null) setBook(null) else menus.bookChooser() }.apply { maxWidth = dp(150) }
        orderChip = chip { menus.orderChooser() }
        styleChip = chip { menus.styleChooser() }
        filters.addView(bookChip, lp(WRAP_CONTENT, dp(32)))
        filters.addView(orderChip, lp(WRAP_CONTENT, dp(32)).apply { leftMargin = dp(8) })
        filters.addView(styleChip, lp(WRAP_CONTENT, dp(32)).apply { leftMargin = dp(8) })
        root.addView(filters, lp())
        root.addView(hairline())

        searchRow = buildSearchRow()
        searchRow.visibility = View.GONE
        root.addView(searchRow, lp())

        val content = FrameLayout(this)
        list = InkListView(this)
        adapter = NotesAdapter(this, rowWindow, rowCallbacks)
        list.adapter = adapter
        // Row taps and long presses: each row's own listeners (NotesAdapter.bind), so they work when paged too.
        content.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        emptyText = emptyMessage("").apply { keepAll() }
        emptyButton = outlineButton("기록 켜기") { setRecordLookups(true) }.apply { visibility = View.GONE }
        val emptyCol = vertical { gravity = Gravity.CENTER_HORIZONTAL }
        emptyCol.addView(emptyText, lp())
        emptyCol.addView(emptyButton, lp(WRAP_CONTENT, WRAP_CONTENT))
        emptyBox = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            visibility = View.GONE
            addView(FrameLayout(this@NotesActivity).apply {
                addView(emptyCol, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        content.addView(emptyBox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(content, lp(MATCH_PARENT, 0, 1f))

        bar = InkPagerBar(this)
        root.addView(bar)
        pager = ListPager(list, bar)
        pager.rowsPerPage = 0
        pager.onPaged = { first, last -> prefetch(first, last) }
        // Far jumps fetch first, then move (N §9.7 [Δ]): the label opens our own number pad.
        bar.label.setOnClickListener { openPageNumPad() }
        paged = ListPaging.paged(Settings.app.listPaging, DeviceClass.cached(this))
        list.paged = paged
        if (paged) list.pager = pager else bar.visibility = View.GONE
        updateChrome()
        return root
    }

    private fun chip(onClick: (View) -> Unit): TextView = label("", 14f, maxLines = 1).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = borderBox(radiusDp = 16f)
        setOnClickListener(onClick)
    }

    private fun buildSelectBar(): LinearLayout {
        val col = vertical { setBackgroundColor(Ink.WHITE) }
        val row = horizontal { minimumHeight = dp(56); setPadding(dp(4), 0, dp(4), 0) }
        row.addView(iconButton(R.drawable.ic_close, "선택 끝내기") { endSelection() })
        selectTitle = label("", 18f, bold = true, maxLines = 1).apply { setPadding(dp(12), 0, dp(8), 0) }
        row.addView(selectTitle, lp(0, WRAP_CONTENT, 1f))
        row.addView(iconButton(R.drawable.ic_share, "공유") { if (selected.isNotEmpty()) menus.shareList(selected.toLongArray()) })
        row.addView(iconButton(R.drawable.ic_upload, "내보내기") { if (selected.isNotEmpty()) menus.exportChooser(selected.toLongArray()) })
        selectColour = iconButton(R.drawable.ic_ink_highlighter, "색 바꾸기") { menus.recolourSelected(it) }
        row.addView(selectColour)
        row.addView(iconButton(R.drawable.ic_delete, "삭제") { if (selected.isNotEmpty()) menus.deleteSelected() })
        row.addView(iconButton(R.drawable.ic_more_vert, "더보기") { menus.selectionOverflow(it) })
        col.addView(row, lp())
        col.addView(hairline())
        return col
    }

    private fun buildSearchRow(): LinearLayout {
        val row = horizontal { minimumHeight = dp(52); setPadding(dp(16), 0, dp(4), 0) }
        searchEdit = EditText(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            hint = "인용문·메모·단어·책 제목 검색"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setTextColor(Ink.BLACK)
            inkCursor(singleLine = true)
            setText(q.text)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    handler.removeCallbacks(searchRunnable)
                    handler.postDelayed(searchRunnable, NotesWindow.SEARCH_DEBOUNCE_MS)
                }
            })
            setOnEditorActionListener { _, _, _ ->
                handler.removeCallbacks(searchRunnable)
                applySearch(text.toString())
                hideKeyboard()
                true
            }
        }
        row.addView(searchEdit, lp(0, WRAP_CONTENT, 1f))
        row.addView(iconButton(R.drawable.ic_close, "지우기") { searchEdit.setText("") })
        val col = vertical()
        col.addView(row, lp())
        col.addView(hairline())
        return col
    }

    // ============================================================================================ chrome state

    /** Tabs, chips and the selection bar from the current state (each setter compares first: no idle redraw). */
    private fun updateChrome() {
        NotesTab.entries.forEachIndexed { i, t ->
            val (text, underline) = tabViews[i]
            val on = t == q.tab
            val tf = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            if (text.typeface != tf) text.typeface = tf
            val vis = if (on) View.VISIBLE else View.INVISIBLE
            if (underline.visibility != vis) underline.visibility = vis
        }
        setText(bookChip, NotesText.scopeLabel(if (q.bookId == null) null else filterBook?.title ?: ""))
        setText(orderChip, NotesText.orderChip(q.order))
        setText(styleChip, NotesText.styleChip(q.style))
        val styleVis = if (q.tab == NotesTab.QUOTES && QuoteStyles.COUNT > 1) View.VISIBLE else View.GONE
        if (styleChip.visibility != styleVis) styleChip.visibility = styleVis
        for (c in arrayOf(bookChip, orderChip, styleChip)) {
            if (c.isEnabled == !selecting) continue
            c.isEnabled = !selecting
            c.setTextColor(if (selecting) Ink.DISABLED else Ink.BLACK)
        }
        if (selecting) {
            setText(selectTitle, NotesText.selectionTitle(selected.size))
            val anyQuote = selected.any { (it ushr 56).toInt() == NoteKind.QUOTE.code }
            val v = if (anyQuote) View.VISIBLE else View.GONE
            if (selectColour.visibility != v) selectColour.visibility = v
        }
    }

    private fun setText(v: TextView, s: String) { if (v.text.toString() != s) v.text = s }

    private fun updateEmpty() {
        val n = rowWindow.count
        if (n > 0) {
            if (emptyBox.visibility != View.GONE) emptyBox.visibility = View.GONE
            return
        }
        val app = Settings.app
        val case = NotesText.emptyCase(q.tab, q.text, q.bookId != null, app.recordLookups)
        setText(emptyText, keepAll(NotesText.emptyText(q.tab, q.text, q.bookId != null, app.recordLookups, app.bookmarkByTouch)).toString())
        emptyButton.visibility = if (case == NotesText.Empty.WORDS_OFF) View.VISIBLE else View.GONE
        emptyBox.visibility = View.VISIBLE
    }

    private fun updateBarLabel() {
        pager.labelSuffix = if (exporting) " · 내보내는 중…" else NotesText.countSuffix(rowWindow.count)
        pager.update()
    }

    // ============================================================================================ loading

    private class Loaded(
        val counts: NotesCounts, val all: List<NoteBook>, val pageBooks: List<NoteBook>?, val filterBook: NoteBook?,
        val first: Int, val pageIndex: Int, val page: NotesPage?, val next: NotesPage?, val ms: Long, val split: String,
    )

    /**
     * Reads the counts, the books and the page holding [keepFirst] under the current query in one IO job, then shows
     * them in one pass (one redraw): fetch first, then move.
     */
    internal fun reload(keepFirst: Int) {
        val q0 = q
        val gen = Library.notesGen
        loadedGen = gen
        loading = true
        val tok = ++reloadToken
        handler.removeCallbacks(moveTimeout)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val t0 = SystemClock.uptimeMillis()
                    val c = Notes.counts(q0)
                    val t1 = SystemClock.uptimeMillis()
                    val all = Notes.books(q0.copy(bookId = null))
                    val bookId = q0.bookId
                    val pb = if (!q0.order.byBook) null else if (bookId == null) all else Notes.books(q0)
                    val fb = if (bookId == null) null else all.firstOrNull { it.id == bookId }
                        ?: Library.book(bookId)?.let { NoteBook(it.id, it.title, it.author, it.path, it.trashed, false, 0L, 0) }
                    val t2 = SystemClock.uptimeMillis()
                    val n = c.of(q0.tab)
                    val first = NotesWindow.clampFirst(keepFirst, n)
                    val pIdx = first / Notes.PAGE_ROWS
                    val page = if (n > 0) Notes.page(q0, pIdx, pb) else null
                    // A first row near the page end shows rows of the next page too: fetch it now (no placeholders).
                    val needNext = first % Notes.PAGE_ROWS > Notes.PAGE_ROWS - NotesWindow.PREFETCH_ROWS && (pIdx + 1) * Notes.PAGE_ROWS < n
                    val next = if (needNext) Notes.page(q0, pIdx + 1, pb) else null
                    val t3 = SystemClock.uptimeMillis()
                    Loaded(c, all, pb, fb, first, pIdx, page, next, t3 - t0, "counts ${t1 - t0} ms, books ${t2 - t1} ms, page ${t3 - t2} ms")
                }.onFailure { Log.w(NotesPerf.TAG, "notes load failed", it) }.getOrNull()
            }
            if (tok != reloadToken || isDestroyed) return@launch
            loading = false
            if (r == null) {
                // Back to the query the shown rows belong to: the chrome matches them and the user can retry.
                q = windowQ
                afterApply = null
                releaseDrawHold()
                updateChrome()
                updateEmpty()
                toast(userMessage(IllegalStateException()))
                return@launch
            }
            apply(r)
        }
    }

    private fun apply(r: Loaded) {
        counts = r.counts
        allBooks = r.all
        bookMap = r.all.associateBy { it.id }
        pageBooks = r.pageBooks
        windowQ = q
        filterBook = r.filterBook
        val n = r.counts.of(q.tab)
        rowWindow.reset(n)
        if (r.page != null) rowWindow.put(rowWindow.token, r.pageIndex, r.page)
        if (r.next != null) rowWindow.put(rowWindow.token, r.pageIndex + 1, r.next)
        afterApply?.invoke()
        afterApply = null
        adapter.notifyDataSetChanged()
        if (n > 0) list.setSelection(r.first)
        updateChrome()
        updateEmpty()
        updateBarLabel()
        if (holdDraw) {
            NotesPerf.log("open: first rows ${SystemClock.uptimeMillis() - openedAt} ms (queries ${r.ms} ms: ${r.split}; $n rows)")
            releaseDrawHold()
        } else {
            NotesPerf.log("reload: ${r.ms} ms (${r.split}; $n rows, ${q.tab}, ${q.order})")
        }
    }

    private fun requestPage(p: Int) {
        if (loading || !rowWindow.request(p)) return
        val tok = rowWindow.token
        val q0 = windowQ
        val pb = pageBooks
        scope.launch {
            val t0 = SystemClock.uptimeMillis()
            val page = withContext(Dispatchers.IO) { runCatching { Notes.page(q0, p, pb) }.getOrNull() }
            if (isDestroyed) return@launch
            NotesPerf.log("page $p: ${SystemClock.uptimeMillis() - t0} ms")
            when (rowWindow.put(tok, p, page)) {
                NotesWindow.Put.STALE -> {}
                NotesWindow.Put.MOVE -> {
                    handler.removeCallbacks(moveTimeout)
                    val row = rowWindow.takeMove()
                    adapter.notifyDataSetChanged()
                    if (row >= 0) showRow(row)
                }
                NotesWindow.Put.STORED -> {
                    val expected = minOf(Notes.PAGE_ROWS, rowWindow.count - p * Notes.PAGE_ROWS)
                    if (page != null && page.rows.size < expected && Library.notesGen != loadedGen) {
                        reload(list.firstVisiblePosition) // a delete elsewhere: re-read the counts
                        return@launch
                    }
                    if (rowWindow.visibleIn(p, list.firstVisiblePosition, list.lastVisiblePosition)) adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun prefetch(first: Int, last: Int) {
        if (loading) return
        for (p in rowWindow.prefetch(first, last)) requestPage(p)
    }

    private fun showRow(row: Int) {
        pager.showRow(row)
    }

    /** A far jump: the target page is fetched first, the list moves when it arrives (or after 250 ms). */
    private fun jumpTo(row: Int) {
        if (rowWindow.moveTo(row)) {
            showRow(row)
            return
        }
        handler.removeCallbacks(moveTimeout)
        handler.postDelayed(moveTimeout, NotesWindow.MOVE_WAIT_MS)
        for (p in rowWindow.movePages(rowWindow.pendingMove)) requestPage(p)
    }

    private fun openPageNumPad() {
        val n = rowWindow.count
        if (n == 0 || list.childCount == 0) return
        val step = PagerMath.step(list.childCount)
        var fully = 0
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            if (c.top >= list.paddingTop && c.bottom <= list.height - list.paddingBottom) fully++
        }
        val max = PagerMath.total(n, fully.coerceAtLeast(1), step)
        com.ggumtak.readeraplus.ui.kit.InkNumPad.show(this, "쪽 번호", "1~$max", max.toString().length) { p ->
            if (p !in 1..max) "1~${max}쪽 사이로 입력하세요" else { jumpTo(NotesWindow.rowForPage(p, step)); null }
        }
    }

    // ============================================================================================ query changes

    internal fun setTab(t: NotesTab) {
        if (t == q.tab) return
        val wordsOnce = t == NotesTab.WORDS && Settings.raw().getBoolean(PREF_WORDS_ONCE, false)
        q = q.copy(tab = t, style = if (t == NotesTab.QUOTES) q.style else null, wordsOnce = wordsOnce)
        Settings.raw().edit().putString(PREF_TAB, t.name).apply()
        reload(0)
    }

    internal fun setOrder(o: NotesOrder) {
        if (o == q.order) return
        q = q.copy(order = o)
        Settings.raw().edit().putString(PREF_ORDER, o.name).apply()
        reload(0)
    }

    internal fun setBook(id: Long?) {
        if (id == q.bookId) return
        q = q.copy(bookId = id)
        reload(0)
    }

    internal fun setStyle(style: Int?) {
        if (style == q.style) return
        q = q.copy(style = style)
        reload(0)
    }

    internal fun setWordsOnce(on: Boolean, text: String? = null) {
        Settings.raw().edit().putBoolean(PREF_WORDS_ONCE, on).apply()
        val nq = q.copy(wordsOnce = on && q.tab == NotesTab.WORDS, text = text ?: q.text)
        if (text != null) {
            if (searchRow.visibility != View.VISIBLE) openSearch(focus = false)
            handler.removeCallbacks(searchRunnable)
            if (searchEdit.text.toString() != text) {
                searchEdit.setText(text)
                handler.removeCallbacks(searchRunnable)
            }
        }
        if (nq == q) return
        q = nq
        reload(0)
    }

    internal fun setRecordLookups(on: Boolean) {
        Settings.saveApp(Settings.app.copy(recordLookups = on))
        if (rowWindow.count == 0) updateEmpty()
    }

    private fun applySearch(text: String) {
        val t = text.trim()
        if (t == q.text) return
        q = q.copy(text = t)
        reload(0)
    }

    private fun toggleSearch() {
        if (searchRow.visibility == View.VISIBLE) closeSearch() else openSearch(focus = true)
    }

    private fun openSearch(focus: Boolean) {
        searchRow.visibility = View.VISIBLE
        if (focus) {
            searchEdit.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(searchEdit, 0)
        }
    }

    /** A second 🔍 tap closes the row and clears the text. */
    private fun closeSearch() {
        handler.removeCallbacks(searchRunnable)
        hideKeyboard()
        searchEdit.clearFocus()
        searchRow.visibility = View.GONE
        if (searchEdit.text.isNotEmpty()) searchEdit.setText("")
        handler.removeCallbacks(searchRunnable)
        applySearch("")
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(searchEdit.windowToken, 0)
    }

    // ============================================================================================ rows

    internal fun book(id: Long): NoteBook? = bookMap[id]

    private val rowCallbacks = object : NotesAdapter.Callbacks {
        override fun requestPage(page: Int) = this@NotesActivity.requestPage(page)
        override val tab: NotesTab get() = q.tab
        override val bookImplied: Boolean get() = q.bookId != null || q.order.byBook
        override val byBook: Boolean get() = q.order.byBook
        override val selecting: Boolean get() = this@NotesActivity.selecting
        override fun isSelected(row: NoteRow): Boolean = row.ref.packed() in selected
        override fun book(id: Long): NoteBook? = bookMap[id]
        override fun onMenu(row: NoteRow, anchor: View) {
            if (selecting) toggleRef(row.ref) else menus.rowMenu(row, anchor)
        }
        override fun onSwatch(row: NoteRow, anchor: View) {
            if (selecting) toggleRef(row.ref) else menus.recolour(row, anchor)
        }
        override fun onLookUp(row: NoteRow) { menus.lookUp(row) }
        override fun onBookHeader(book: NoteBook) { setBook(book.id) }
        override fun onRowTap(row: NoteRow, position: Int) = this@NotesActivity.onRowTap(row, position)
    }

    private fun onRowTap(row: NoteRow, position: Int) {
        if (selecting) {
            toggle(row, position)
            return
        }
        when (row.ref.kind) {
            NoteKind.REVIEW -> menus.editNote(row)
            else -> menus.openAt(row)
        }
    }

    // ============================================================================================ selection

    internal fun enterSelection(first: NoteRow?) {
        if (first != null) selected += first.ref.packed()
        if (!selecting || first == null) {
            selecting = true
            normalBar.visibility = View.GONE
            selectBar.visibility = View.VISIBLE
        }
        updateChrome()
        rebindVisible()
    }

    /** Leaves selection mode with the next reload's single redraw (after a batch delete). */
    internal fun endSelectionOnReload() {
        afterApply = { endSelection(rebind = false) }
    }

    internal fun endSelection(rebind: Boolean = true) {
        if (!selecting) return
        selecting = false
        selected.clear()
        selectBar.visibility = View.GONE
        normalBar.visibility = View.VISIBLE
        updateChrome()
        if (rebind) rebindVisible()
    }

    private fun toggle(row: NoteRow, @Suppress("UNUSED_PARAMETER") position: Int) = toggleRef(row.ref)

    private fun toggleRef(ref: NoteRef) {
        val p = ref.packed()
        if (!selected.remove(p)) selected += p
        updateChrome()
        rebindVisible()
    }

    /** Entering, leaving or toggling re-binds the visible rows only (one redraw). */
    internal fun rebindVisible() {
        for (i in 0 until list.childCount) {
            val pos = list.firstVisiblePosition + i
            if (pos < adapter.count) adapter.getView(pos, list.getChildAt(i), list)
        }
    }

    internal fun selectAll() {
        val q0 = q
        scope.launch {
            val refs = withContext(Dispatchers.IO) { runCatching { Notes.refs(q0) }.getOrNull() } ?: return@launch
            if (isDestroyed) return@launch
            if (!selecting) enterSelection(null)
            for (r in refs) selected += r
            updateChrome()
            rebindVisible()
        }
    }

    internal fun clearSelection() {
        selected.clear()
        updateChrome()
        rebindVisible()
    }

    // ============================================================================================ writes and export

    /** Runs [write] on IO, then [done] on main (when still alive) and a reload that keeps the position. */
    internal fun write(write: () -> Unit, done: (() -> Unit)? = null) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { write() }.onFailure { Log.w(NotesPerf.TAG, "write failed", it) }.isSuccess }
            if (isDestroyed) return@launch
            if (ok) done?.invoke() else toast(userMessage(IllegalStateException()))
            reload(list.firstVisiblePosition)
        }
    }

    /** Opens the document picker for an export of [refs] (null = the whole list under the query). */
    internal fun startExport(format: NotesExport.Format, refs: LongArray?) {
        val title = if (q.bookId != null) filterBook?.title else null
        val name = NotesText.fileName(title, format.ext, System.currentTimeMillis())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(format.mime).putExtra(Intent.EXTRA_TITLE, name)
        exportFormat = format
        exportRefs = refs
        // Never text/plain for a .md name (the provider would append .txt); octet-stream only if nothing handles the
        // format's own type. No resolveActivity pre-check: package visibility (API 30+) can hide DocumentsUI from it.
        try {
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_EXPORT)
        } catch (_: ActivityNotFoundException) {
            try {
                i.type = "application/octet-stream"
                @Suppress("DEPRECATION") startActivityForResult(i, REQ_EXPORT)
            } catch (e: ActivityNotFoundException) {
                exportFormat = null
                exportRefs = null
                toast("내보내지 못했습니다: " + userMessage(e))
            }
        }
    }

    @Deprecated("Platform result API (no AndroidX).")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT) return
        val format = exportFormat
        val refs = exportRefs
        exportFormat = null
        exportRefs = null
        val lost = exportLost
        exportLost = false
        val uri = data?.data
        if (lost && resultCode == RESULT_OK) toast("내보내지 못했습니다")
        if (resultCode != RESULT_OK || uri == null || format == null) return
        runExport(uri, format, refs, q)
    }

    /**
     * The export job runs on the app-level [ReaderIo] scope, not this Activity's: leaving the hub during a long export
     * must not cut the file short. Its toast goes through the application context when the hub is gone.
     */
    private fun runExport(uri: Uri, format: NotesExport.Format, refs: LongArray?, q0: NotesQuery) {
        exporting = true
        updateBarLabel()
        val app = applicationContext
        val host = WeakReference(this)
        val main = Handler(Looper.getMainLooper())
        ReaderIo.launch {
            val t0 = SystemClock.uptimeMillis()
            val msg = try {
                val n = openForWrite(app, uri).use { os ->
                    val w = BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))
                    val n = Notes.export(refs, q0, format, w, System.currentTimeMillis())
                    w.flush()
                    n
                }
                NotesPerf.log("export: $n notes, ${SystemClock.uptimeMillis() - t0} ms")
                NotesText.exportedToast(n)
            } catch (t: Throwable) {
                Log.w(NotesPerf.TAG, "export failed", t)
                "내보내지 못했습니다: " + (ownMessage(t) ?: userMessage(t))
            }
            main.post {
                val a = host.get()
                if (a != null && !a.isDestroyed) {
                    a.exporting = false
                    a.updateBarLabel()
                    a.toast(msg)
                } else {
                    app.toast(msg)
                }
            }
        }
    }

    /** Mode "w" first (a new document has nothing to truncate), "wt" when a provider rejects it. */
    private fun openForWrite(ctx: Context, uri: Uri): OutputStream {
        val cr = ctx.contentResolver
        val first = try { cr.openOutputStream(uri, "w") } catch (e: Exception) { null }
        return first ?: cr.openOutputStream(uri, "wt") ?: throw IOException("openOutputStream")
    }
}
