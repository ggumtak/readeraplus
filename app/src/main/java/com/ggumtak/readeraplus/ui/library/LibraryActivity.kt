package com.ggumtak.readeraplus.ui.library

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.FileScanner
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.LibraryQuery
import com.ggumtak.readeraplus.data.ReaderPresence
import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.data.ShelfGroup
import com.ggumtak.readeraplus.reader.ReaderActivity
import com.ggumtak.readeraplus.reader.ResumeState
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.LibrarySort
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkListView
import com.ggumtak.readeraplus.ui.kit.InkGridView
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.inkCursor
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.settings.ErrorLines
import com.ggumtak.readeraplus.ui.settings.SettingsActivity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.provider.Settings as SystemSettings

/**
 * Library (launcher) screen, ReadEra-style in black & white: toolbar (drawer / shelf title / view toggle / search /
 * overflow), a drawer overlay with every shelf, book cards (목록), compact rows (간단히) or covers (표지), grouped
 * shelves, search-as-you-type, sort, book menu actions, multi-select with batch actions (T1-13), collections, trash,
 * storage permission flow, background scanning with a status row, SAF open/import and open-last-on-start.
 *
 * Every database call runs on [Dispatchers.IO]; the main thread only binds views. No animations anywhere.
 */
class LibraryActivity : Activity(), LibraryJobs.Listener {

    companion object {
        private const val REQ_OPEN_FILE = 7101
        private const val REQ_OPEN_TREE = 7102
        private const val REQ_ALL_FILES = 7103
        private const val REQ_LEGACY_PERM = 7104

        private const val PREF_SHELF = "library.shelf"
        private const val PREF_PERM_PANEL_HIDDEN = "library.permPanelHidden"
        private const val PREF_LEGACY_ASKED = "library.legacyPermAsked"

        private const val STATE_SHELF = "shelf"
        private const val STATE_GROUP = "group"
        private const val STATE_GROUP_LABEL = "groupLabel"

        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val LOADING_TEXT_DELAY_MS = 300L
        /** The periodic rescan starts only after this long without a touch or key in the library. */
        private const val AUTO_SCAN_IDLE_MS = 3000L
        private const val STATUS_HEIGHT_DP = 36
        /** Longest the launch splash is held while looking up the book to reopen (a stuck database shows the library). */
        private const val OPEN_LAST_MAX_WAIT_MS = 2000L
    }

    internal val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())

    // ---- state
    internal var shelf = Shelf.ALL
        private set
    private var group: String? = null
    private var groupLabel: String? = null
    private var query = ""
    private var searchOpen = false
    private var drawerOpen = false
    internal var listMode = LibraryListMode.LIST
        private set
    private var sort = LibrarySort.RECENT
    internal var hasAccess = false
        private set
    private var loadJob: Job? = null
    private var countsJob: Job? = null
    private var loadedOnce = false
    private var groupsAll: List<ShelfGroup> = emptyList()
    /** Shelf [groupsAll] was loaded for (in-memory group filtering must not use another shelf's groups). */
    private var groupsShelf: Shelf? = null
    /** (first visible position, top offset) of the group list, restored when leaving a group. */
    private var groupScroll: IntArray? = null
    /** Book ids that are in at least one collection (card icon state); null = recompute on next load. */
    private var collectionMembers: Set<Long>? = null
    private var counts: Map<Shelf, Int>? = null
    /** Reload when the window regains focus (after dialogs owned by other modules that may edit books). */
    internal var refreshOnFocus = false
    private var localStatus: String? = null
    /** Writes in flight on [writeDispatcher]; the list is reloaded from the database only once they are all stored. */
    private var pendingWrites = 0
    /** A reload is owed once [pendingWrites] drops to 0. */
    private var reloadAfterWrites = false
    /** Serial IO lane for flag and batch writes so quick taps reach the database in tap order. */
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    /** Multi-select (T1-13): drawn by the book items, edited by taps while [BookSelection.active]. */
    private val selection = BookSelection()
    /** Rows of the book list on screen (whichever view shows them); empty while a group list shows. */
    private var shownRows: List<BookRow> = emptyList()

    // ---- start-up (open the last book on start)
    /** The library views exist ([ensureUi]). Not before the open-last decision, nor while the reader opened by it is up. */
    private var uiBuilt = false
    /** Between [onResume] and [onPause]. */
    private var resumed = false
    /** The last-read book is being looked up at start; the library is neither built nor refreshed meanwhile. */
    private var decidingOpenLast = false
    /** While true the window draws nothing, so the launch splash stays up until the reader covers it. */
    private var holdDraw = false
    private val drawHold = ViewTreeObserver.OnPreDrawListener { !holdDraw }
    private val openLastTimeout = Runnable {
        if (decidingOpenLast) {
            decidingOpenLast = false
            if (resumed) showLibrary() // else the next onResume builds it
        }
    }

    // ---- views
    private lateinit var root: FrameLayout
    private lateinit var main: LinearLayout
    private lateinit var toolbarBar: LinearLayout
    private lateinit var navBtn: ImageButton
    private lateinit var titleView: TextView
    private lateinit var extraBtn: ImageButton
    private lateinit var viewBtn: ImageButton
    /** Mode whose icon [viewBtn] shows. */
    private var viewBtnMode: LibraryListMode? = null
    /** Multi-select toolbar, built on the first long-press (never at start-up). */
    private var selectionBar: SelectionBar? = null
    private lateinit var searchRow: LinearLayout
    private lateinit var searchEdit: EditText
    private lateinit var statusRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var permPanel: LinearLayout
    private lateinit var listView: ListView
    private lateinit var gridView: GridView
    private lateinit var emptyScroll: ScrollView
    private lateinit var emptyText: TextView
    private lateinit var emptyButtons: LinearLayout
    /** The drawer and its scrim, built on the first open (sixteen rows the start-up doesn't need). */
    private var scrim: View? = null
    private var drawer: LinearLayout? = null
    private val drawerItems = HashMap<Shelf, DrawerItem>()

    private lateinit var bookAdapter: BookListAdapter
    private lateinit var compactAdapter: CompactListAdapter
    private lateinit var gridAdapter: BookGridAdapter
    private lateinit var groupAdapter: GroupAdapter

    private class DrawerItem(val row: LinearLayout, val label: TextView, val count: TextView)

    /** "N권 선택" toolbar: title, [더보기] (one book), [전체], [닫기], then the batch actions. */
    private class SelectionBar(
        val root: LinearLayout,
        val title: TextView,
        val more: ImageButton,
        val actions: List<View>,
    )

    private val searchRunnable = Runnable { applySearch(searchEdit.text.toString()) }
    private val loadingRunnable = Runnable { showMessage("불러오는 중…", emptyList()) }
    /** A periodic rescan is due this visit and waits for [AUTO_SCAN_IDLE_MS] of idleness ([autoScanRunnable]). */
    private var autoScanPending = false
    private val autoScanRunnable = Runnable {
        autoScanPending = false
        // Re-check when it fires: a scan started meanwhile (e.g. right after a permission grant) may have
        // finished already, and scanning twice in a row costs seconds of CPU and disk on the Comet.
        val last = Settings.raw().getLong(LibraryJobs.PREF_LAST_SCAN, 0L)
        if (hasAccess && !ReaderPresence.inFront && !LibraryJobs.scanning &&
            LibraryText.rescanDue(last, System.currentTimeMillis())
        ) {
            LibraryJobs.startScan(this, announce = false, periodic = true)
        }
    }

    // ============================================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API 31+: drop the platform splash at once instead of its fade-out (a run of e-ink frames). The theme makes
        // it a plain white window with no icon (values-v31).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setOnExitAnimationListener { it.remove() }
        val app = Settings.app
        listMode = app.libraryListMode
        sort = app.librarySort
        val savedShelf = savedInstanceState?.getString(STATE_SHELF) ?: Settings.raw().getString(PREF_SHELF, null)
        shelf = Shelf.entries.firstOrNull { it.name == savedShelf } ?: Shelf.ALL
        if (savedInstanceState != null && LibraryText.isGrouped(shelf)) {
            group = savedInstanceState.getString(STATE_GROUP)
            groupLabel = savedInstanceState.getString(STATE_GROUP_LABEL)
        }
        hasAccess = hasStorageAccess()
        val i = intent
        val first = ResumeState.activitiesCreated == 1
        val pending = if (first) ResumeState.pending() else null
        when (LibraryText.startMode(app.openLastOnStart, savedInstanceState != null, i?.action, i?.flags ?: 0,
            first, pending?.bookId ?: -1L, pending?.tries ?: 0, ResumeState.MAX_TRIES)
        ) {
            LibraryText.StartMode.RESUME -> startOpenLast(resumeId = pending!!.bookId)
            LibraryText.StartMode.OPEN_LAST -> startOpenLast()
            LibraryText.StartMode.LIBRARY -> ensureUi()
        }
    }

    /** Builds the library views once: at creation, or when the library is first shown after the open-last start. */
    private fun ensureUi() {
        if (uiBuilt) return
        uiBuilt = true
        buildUi()
        setContentView(root)
        updateToolbar()
        releaseDrawHold()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !hasAccess &&
            !Settings.raw().getBoolean(PREF_LEGACY_ASKED, false)
        ) {
            Settings.raw().edit().putBoolean(PREF_LEGACY_ASKED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_LEGACY_PERM)
        }
    }

    private fun releaseDrawHold() {
        if (!holdDraw) return
        holdDraw = false
        val observer = window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(drawHold)
    }

    override fun onStart() {
        super.onStart()
        LibraryJobs.addListener(this)
    }

    override fun onRestart() {
        super.onRestart()
        // Back from the reader / settings: they can add books to collections, so the card icons must be
        // recomputed (the onResume reload picks this up).
        collectionMembers = null
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (decidingOpenLast) return // onLastBookLoaded opens the reader or shows the library
        ensureUi() // back from the reader opened at start
        refreshVisible()
    }

    /** Builds the library (if needed) and runs the per-visit refresh that onResume skipped while deciding. */
    private fun showLibrary() {
        ensureUi()
        refreshVisible()
    }

    /** Per-visit work: permission state, scan scheduling, status strip, counts and the list. */
    private fun refreshVisible() {
        val app = Settings.app
        listMode = app.libraryListMode
        sort = app.librarySort
        showModeButton() // a restored backup may have changed the view
        val access = hasStorageAccess()
        val newlyGranted = access && !hasAccess
        hasAccess = access
        updatePermissionPanel()
        val lastScan = Settings.raw().getLong(LibraryJobs.PREF_LAST_SCAN, 0L)
        cancelAutoScan()
        if (access && newlyGranted) {
            LibraryJobs.startScan(this, announce = true)
        } else if (access && LibraryText.rescanDue(lastScan, System.currentTimeMillis())) {
            // Deferred until the library is idle, so a book opened right away (or open-last-on-start) doesn't
            // share the CPU/disk with the scan; leaving the screen before it fires postpones it to the next visit.
            autoScanPending = true
            restartAutoScanWait()
        }
        updateStatus()
        invalidateCounts()
        reload()
    }

    override fun onStop() {
        LibraryJobs.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SHELF, shelf.name)
        outState.putString(STATE_GROUP, group)
        outState.putString(STATE_GROUP_LABEL, groupLabel)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A dialog or menu in front is not idle: the wait for the periodic scan starts over when it closes.
        restartAutoScanWait()
        if (hasFocus && refreshOnFocus && uiBuilt) {
            invalidateCounts()
            reload()
        }
    }

    override fun onPause() {
        resumed = false
        refreshOnFocus = false
        cancelAutoScan()
        super.onPause()
    }

    /** Touch down or any key (Activity hook): the library is in use, so a pending periodic scan waits longer. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        restartAutoScanWait()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Idle time counts from the end of a drag, not from its start (onUserInteraction only sees the down).
        if (autoScanPending && ev.actionMasked == MotionEvent.ACTION_UP) restartAutoScanWait()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * (Re)starts the idle wait of a pending periodic scan; no-op when none is pending. Without the window focus
     * (a dialog or menu is up, or focus has not arrived after onResume yet) it only drops the wait: the focus
     * change starts it.
     */
    private fun restartAutoScanWait() {
        if (!autoScanPending) return
        handler.removeCallbacks(autoScanRunnable)
        if (hasWindowFocus()) handler.postDelayed(autoScanRunnable, AUTO_SCAN_IDLE_MS)
    }

    private fun cancelAutoScan() {
        autoScanPending = false
        handler.removeCallbacks(autoScanRunnable)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> CoverLoader.trim(all = true)
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> CoverLoader.trim(all = false)
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> CoverLoader.trim(all = false)
        }
    }

    @Deprecated("Platform back handling (no AndroidX); targetSdk 34 still routes back here.")
    override fun onBackPressed() {
        if (!uiBuilt) {
            @Suppress("DEPRECATION") super.onBackPressed()
            return
        }
        when (LibraryText.backStep(drawerOpen, searchOpen, group != null, selection.active)) {
            LibraryText.BackStep.CLOSE_DRAWER -> closeDrawer()
            LibraryText.BackStep.END_SELECTION -> endSelection()
            LibraryText.BackStep.CLOSE_SEARCH -> closeSearch()
            LibraryText.BackStep.LEAVE_GROUP -> leaveGroup()
            LibraryText.BackStep.FINISH -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** Hardware page keys / volume keys scroll the list by a screen (e-ink friendly paging). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (uiBuilt && !drawerOpen) {
            val code = event.keyCode
            val dir = LibraryText.pageDirection(code, Settings.app)
            val isVolume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
            // While typing a search, only volume keys page (learned keys could be ordinary text keys).
            if (dir != 0 && (!searchEdit.isFocused || isVolume)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) scrollPage(dir)
                restartAutoScanWait() // consumed here, so Activity.onUserInteraction never sees it
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ============================================================================================ UI build

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Ink.WHITE) }
        main = vertical { setBackgroundColor(Ink.WHITE) }
        main.addView(buildToolbar(), lp())
        main.addView(buildSearchRow(), lp())
        main.addView(buildPermissionPanel(), lp().apply { setMargins(dp(8), dp(8), dp(8), dp(4)) })

        val content = FrameLayout(this)
        bookAdapter = BookListAdapter(this, actions)
        compactAdapter = CompactListAdapter(this, actions)
        groupAdapter = GroupAdapter(this, ::enterGroup, ::onGroupLongPress)
        gridAdapter = BookGridAdapter(this, actions)

        // Library subclasses: the always-visible fast scroller takes only the right edge, not the ⋮ next to it.
        listView = InkListView(this).apply {
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(8))
            isFastScrollEnabled = true
            // Always shown: the auto-hiding fast scroller fades in/out on every scroll (e-ink redraws).
            isFastScrollAlwaysVisible = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            itemsCanFocus = true
            adapter = bookAdapter
        }
        content.addView(listView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val widthPx = resources.displayMetrics.widthPixels
        val cols = LibraryText.gridColumns(widthPx, resources.displayMetrics.density)
        val pad = dp(8)
        val spacing = dp(6)
        gridAdapter.cellWidth = (widthPx - pad - dp(12) - (cols - 1) * spacing) / cols
        gridView = InkGridView(this).apply {
            numColumns = cols
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = spacing
            verticalSpacing = dp(10)
            setPadding(pad, pad, dp(12), pad)
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            isScrollbarFadingEnabled = false
            selector = ColorDrawable(android.graphics.Color.TRANSPARENT)
            isFastScrollEnabled = true
            isFastScrollAlwaysVisible = true
            visibility = View.GONE
            adapter = gridAdapter
        }
        content.addView(gridView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        emptyText = label("", 16f).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        }
        emptyButtons = vertical { gravity = Gravity.CENTER_HORIZONTAL }
        val emptyCol = vertical {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(32))
            addView(emptyText, lp())
            addView(emptyButtons, lp().apply { topMargin = dp(20) })
        }
        emptyScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            setBackgroundColor(Ink.WHITE)
            addView(emptyCol, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        content.addView(emptyScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Scan/import status overlays the bottom of the list instead of pushing it down: showing and hiding it
        // then repaints one strip, not the whole list (twice per background scan on e-ink).
        content.addView(buildStatusRow(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        main.addView(content, lp(MATCH_PARENT, 0, 1f))
        root.addView(main, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** Builds the drawer overlay (scrim + panel) over the library once, when it is first opened. */
    private fun ensureDrawer(): LinearLayout {
        drawer?.let { return it }
        val s = View(this).apply {
            isClickable = true
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(s, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        scrim = s
        val drawerWidth = minOf((resources.displayMetrics.widthPixels * 0.8f).toInt(), dp(320))
        val d = buildDrawer()
        root.addView(d, FrameLayout.LayoutParams(drawerWidth, MATCH_PARENT, Gravity.START))
        drawer = d
        return d
    }

    private fun buildToolbar(): View {
        val bar = vertical { setBackgroundColor(Ink.WHITE) }
        toolbarBar = bar
        val row = horizontal {
            minimumHeight = dp(56)
            setPadding(dp(4), 0, dp(4), 0)
        }
        navBtn = iconButton(R.drawable.ic_menu, "메뉴") { onNavClick() }
        row.addView(navBtn)
        titleView = label("", 20f, bold = true, maxLines = 1).apply { setPadding(dp(12), 0, dp(8), 0) }
        row.addView(titleView, lp(0, WRAP_CONTENT, 1f))
        extraBtn = iconButton(R.drawable.ic_add, "새 컬렉션") { onExtraAction() }.apply { visibility = View.GONE }
        row.addView(extraBtn)
        // 목록 → 간단히 → 표지 in one tap each (no chooser dialog to open and close on e-ink).
        viewBtn = iconButton(modeIcon(listMode), modeDescription(listMode)) { cycleListMode() }
        viewBtnMode = listMode
        row.addView(viewBtn)
        row.addView(iconButton(R.drawable.ic_search, "검색") { toggleSearch() })
        row.addView(iconButton(R.drawable.ic_more_vert, "메뉴") { showOverflow(it) })
        bar.addView(row, lp())
        bar.addView(hairline())
        return bar
    }

    private fun buildSearchRow(): View {
        searchRow = vertical { visibility = View.GONE }
        val row = horizontal { setPadding(dp(12), 0, dp(4), 0); minimumHeight = dp(52) }
        row.addView(icon(R.drawable.ic_search, 22, Ink.GRAY))
        searchEdit = EditText(this).apply {
            hint = "제목, 작가, 파일 이름"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Ink.BLACK)
            setHintTextColor(Ink.GRAY)
            background = null
            setPadding(dp(12), dp(8), dp(8), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    handler.removeCallbacks(searchRunnable)
                    handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
                    restartAutoScanWait() // soft-keyboard typing reaches no activity input hook
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    handler.removeCallbacks(searchRunnable)
                    applySearch(text.toString())
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
            inkCursor(singleLine = true)
        }
        row.addView(searchEdit, lp(0, WRAP_CONTENT, 1f))
        row.addView(iconButton(R.drawable.ic_close, "지우기") {
            if (searchEdit.text.isNullOrEmpty()) closeSearch() else searchEdit.setText("")
        })
        searchRow.addView(row, lp())
        searchRow.addView(hairline())
        return searchRow
    }

    private fun buildStatusRow(): View {
        statusRow = vertical {
            visibility = View.GONE
            setBackgroundColor(Ink.WHITE)
            isClickable = true // taps on the strip must not reach the card below it
        }
        statusRow.addView(hairline())
        statusText = label("", 14f, maxLines = 1).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        statusRow.addView(statusText, lp(MATCH_PARENT, dp(STATUS_HEIGHT_DP)))
        return statusRow
    }

    /** Shows/hides the bottom status strip and reserves list space under it so the last card stays reachable. */
    private fun setStatusVisible(visible: Boolean) {
        if ((statusRow.visibility == View.VISIBLE) == visible) return
        statusRow.visibility = if (visible) View.VISIBLE else View.GONE
        val extra = if (visible) dp(STATUS_HEIGHT_DP) + 1 else 0
        listView.setPadding(listView.paddingLeft, listView.paddingTop, listView.paddingRight, dp(8) + extra)
        gridView.setPadding(gridView.paddingLeft, gridView.paddingTop, gridView.paddingRight, dp(8) + extra)
    }

    private fun buildPermissionPanel(): View {
        permPanel = vertical {
            background = borderBox()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        permPanel.addView(label("모든 파일 접근 권한이 필요합니다", 17f, bold = true), lp())
        permPanel.addView(
            label(
                "기기에 있는 EPUB · TXT · PDF 파일을 찾아 서재에 보여 주려면 ‘모든 파일 접근’을 허용하세요. " +
                    "허용하고 돌아오면 자동으로 스캔합니다. 설정 화면이 열리지 않으면 ‘폴더 추가’로 책 폴더를 고르세요.",
                14f,
                color = Ink.GRAY,
            ).apply { setPadding(0, dp(6), 0, dp(10)); setLineSpacing(0f, 1.2f) },
            lp(),
        )
        val buttons = horizontal()
        fun add(text: String, onClick: (View) -> Unit) {
            buttons.addView(textButton(text, onClick).apply { minWidth = 0 }, lp(0, WRAP_CONTENT, 1f).apply {
                if (buttons.childCount > 0) leftMargin = dp(8)
            })
        }
        add("권한 허용") { requestStorageAccess() }
        add("폴더 추가") { pickTree() }
        add("닫기") {
            Settings.raw().edit().putBoolean(PREF_PERM_PANEL_HIDDEN, true).apply()
            permPanel.visibility = View.GONE
        }
        permPanel.addView(buttons, lp())
        return permPanel
    }

    private fun buildDrawer(): LinearLayout {
        val panel = horizontal {
            gravity = Gravity.TOP
            setBackgroundColor(Ink.WHITE)
            isClickable = true
            visibility = View.GONE
        }
        val col = vertical()
        val header = horizontal {
            minimumHeight = dp(64)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        header.addView(icon(R.drawable.ic_auto_stories, 28).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(20) })
        header.addView(label(getString(R.string.app_name), 20f, bold = true, maxLines = 1), lp(0, WRAP_CONTENT, 1f))
        col.addView(header, lp())
        col.addView(hairline())
        col.addView(View(this), lp(MATCH_PARENT, dp(4)))
        Shelf.entries.forEach { s ->
            val item = drawerRow(shelfIcon(s), s.label) { selectShelf(s) }
            drawerItems[s] = item
            col.addView(item.row, lp())
        }
        col.addView(View(this), lp(MATCH_PARENT, dp(4)))
        col.addView(hairline())
        col.addView(View(this), lp(MATCH_PARENT, dp(4)))
        col.addView(drawerRow(R.drawable.ic_settings, "설정") { closeDrawer(); SettingsActivity.open(this) }.row, lp())
        // ic_history, not the spec's ic_schedule: that clock is already 읽을 책's icon a few rows up.
        col.addView(drawerRow(R.drawable.ic_history, "읽기 기록") {
            closeDrawer()
            SettingsActivity.open(this, SettingsActivity.PAGE_STATS)
        }.row, lp())
        col.addView(drawerRow(R.drawable.ic_file_open, "파일 열기") { closeDrawer(); openFilePicker() }.row, lp())
        // Books received there are in the database when this screen resumes: onResume reloads the list and counts.
        col.addView(drawerRow(R.drawable.ic_download, "Wi-Fi로 책 받기") {
            closeDrawer()
            SettingsActivity.open(this, SettingsActivity.PAGE_WIFI)
        }.row, lp())
        col.addView(drawerRow(R.drawable.ic_refresh, "도서 스캔") { closeDrawer(); manualScan() }.row, lp())
        col.addView(drawerRow(R.drawable.ic_info, "정보") {
            closeDrawer()
            SettingsActivity.open(this, SettingsActivity.PAGE_ABOUT)
        }.row, lp())
        col.addView(View(this), lp(MATCH_PARENT, dp(12)))
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        panel.addView(scroll, lp(0, MATCH_PARENT, 1f))
        panel.addView(hairline(vertical = true))
        return panel
    }

    private fun drawerRow(iconRes: Int, text: String, onClick: () -> Unit): DrawerItem {
        val row = horizontal {
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(16), 0)
            background = pressableBackground()
            setOnClickListener { onClick() }
        }
        row.addView(icon(iconRes, 24).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(24) })
        val l = label(text, 17f, maxLines = 1)
        row.addView(l, lp(0, WRAP_CONTENT, 1f))
        val c = label("", 14f, color = Ink.GRAY).apply { setPadding(dp(8), 0, 0, 0) }
        row.addView(c)
        return DrawerItem(row, l, c)
    }

    private fun shelfIcon(s: Shelf): Int = when (s) {
        Shelf.READING_NOW -> R.drawable.ic_autorenew
        Shelf.ALL -> R.drawable.ic_menu_book
        Shelf.FAVORITES -> R.drawable.ic_star
        Shelf.TO_READ -> R.drawable.ic_schedule
        Shelf.HAVE_READ -> R.drawable.ic_done_all
        Shelf.AUTHORS -> R.drawable.ic_person
        Shelf.SERIES -> R.drawable.ic_sell
        Shelf.COLLECTIONS -> R.drawable.ic_library_books
        Shelf.FORMATS -> R.drawable.ic_layers
        Shelf.FOLDERS -> R.drawable.ic_folder
        Shelf.DOWNLOADS -> R.drawable.ic_download
        Shelf.TRASH -> R.drawable.ic_delete
    }

    // ============================================================================================ navigation

    private fun onNavClick() {
        if (group != null) leaveGroup() else openDrawer()
    }

    private fun openDrawer() {
        hideKeyboard()
        val panel = ensureDrawer()
        drawerOpen = true
        drawerItems.forEach { (s, item) ->
            val selected = s == shelf
            item.row.background = if (selected) ColorDrawable(Ink.PRESSED) else pressableBackground()
            item.label.bold(selected)
            item.count.setTextColor(if (selected) Ink.BLACK else Ink.GRAY) // no grey text on the grey row
        }
        scrim?.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        ensureCounts()
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawer?.visibility = View.GONE
        scrim?.visibility = View.GONE
    }

    private fun selectShelf(s: Shelf) {
        closeDrawer()
        if (s == shelf && group == null) return
        endSelection()
        shelf = s
        group = null
        groupLabel = null
        groupScroll = null
        Settings.raw().edit().putString(PREF_SHELF, s.name).apply()
        if (searchOpen) closeSearch(reloadAfter = false)
        updateToolbar()
        reload(scrollTop = true)
    }

    private fun enterGroup(g: ShelfGroup) {
        val first = listView.firstVisiblePosition
        val top = listView.getChildAt(0)?.top ?: 0
        groupScroll = intArrayOf(first, top)
        endSelection()
        group = g.key
        groupLabel = LibraryText.groupTitle(shelf, g)
        if (searchOpen) closeSearch(reloadAfter = false)
        updateToolbar()
        reload(scrollTop = true)
    }

    private fun leaveGroup() {
        endSelection()
        group = null
        groupLabel = null
        if (searchOpen) closeSearch(reloadAfter = false)
        updateToolbar()
        reload()
    }

    private fun updateToolbar() {
        val inGroup = group != null
        titleView.text = if (inGroup) groupLabel ?: shelf.label else shelf.label
        navBtn.setImageResource(if (inGroup) R.drawable.ic_arrow_back else R.drawable.ic_menu)
        navBtn.contentDescription = if (inGroup) "뒤로" else "메뉴"
        when {
            shelf == Shelf.COLLECTIONS && !inGroup -> {
                extraBtn.setImageResource(R.drawable.ic_add)
                extraBtn.contentDescription = "새 컬렉션"
                extraBtn.visibility = View.VISIBLE
            }
            shelf == Shelf.TRASH -> {
                extraBtn.setImageResource(R.drawable.ic_delete_forever)
                extraBtn.contentDescription = "휴지통 비우기"
                extraBtn.visibility = View.VISIBLE
            }
            else -> extraBtn.visibility = View.GONE
        }
        // A group list has no books to show differently.
        viewBtn.visibility = if (LibraryText.isGrouped(shelf) && !inGroup) View.GONE else View.VISIBLE
    }

    private fun onExtraAction() {
        when (shelf) {
            Shelf.COLLECTIONS -> newCollection(null)
            Shelf.TRASH -> confirmEmptyTrash()
            else -> Unit
        }
    }

    // ============================================================================================ search

    private fun toggleSearch() {
        if (searchOpen) closeSearch() else openSearch()
    }

    private fun openSearch() {
        searchOpen = true
        searchRow.visibility = View.VISIBLE
        searchEdit.requestFocus()
        // The row was GONE until now: ask for the keyboard after it is laid out, or the IME ignores the request.
        searchEdit.post {
            if (searchOpen && searchEdit.isFocused) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun closeSearch(reloadAfter: Boolean = true) {
        handler.removeCallbacks(searchRunnable)
        hideKeyboard()
        searchOpen = false
        searchRow.visibility = View.GONE
        if (searchEdit.text.isNotEmpty()) searchEdit.setText("")
        handler.removeCallbacks(searchRunnable)
        if (query.isNotEmpty()) {
            query = ""
            if (reloadAfter) applyQueryChange()
        }
    }

    private fun applySearch(text: String) {
        if (text == query) return
        query = text
        applyQueryChange()
    }

    private fun applyQueryChange() {
        if (LibraryText.isGrouped(shelf) && group == null && groupsShelf == shelf) {
            // Group lists are small: filter in memory, no database round trip.
            showGroups(LibraryText.filterGroups(groupsAll, query), scrollTop = true)
        } else {
            reload(scrollTop = true)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(root.windowToken, 0)
        searchEdit.clearFocus()
    }

    // ============================================================================================ loading

    /** Reloads the current shelf/group/query from the database (IO), keeping the scroll position. */
    internal fun reload(scrollTop: Boolean = false) {
        loadJob?.cancel()
        val s = shelf
        val g = group
        val q = query
        val so = sort
        val grouped = LibraryText.isGrouped(s) && g == null
        if (!loadedOnce) {
            handler.removeCallbacks(loadingRunnable)
            handler.postDelayed(loadingRunnable, LOADING_TEXT_DELAY_MS)
        }
        loadJob = scope.launch {
            if (grouped) {
                val result = withContext(Dispatchers.IO) { runCatching { Library.groups(s) } }
                handler.removeCallbacks(loadingRunnable)
                loadedOnce = true
                result.onSuccess {
                    groupsAll = it
                    groupsShelf = s
                    showGroups(LibraryText.filterGroups(it, q), scrollTop)
                }.onFailure { showError(it) }
            } else {
                val known = collectionMembers
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val books = Library.books(LibraryQuery(s, g, q), so)
                        val members = known ?: collectionMemberIds()
                        members to books.map { BookRow.of(it, it.id in members) }
                    }
                }
                handler.removeCallbacks(loadingRunnable)
                loadedOnce = true
                result.onSuccess { (members, rows) ->
                    collectionMembers = members
                    showBooks(rows, scrollTop)
                }.onFailure { showError(it) }
            }
        }
    }

    /** Ids of books that belong to any collection (blocking). */
    private fun collectionMemberIds(): Set<Long> = Library.collectionMemberIds()

    /**
     * Marks derived data stale after a change (counts, collection icons) and reloads the list ([afterWrites]: once the
     * writes queued on the write lane are stored).
     */
    internal fun changed(collections: Boolean = false, afterWrites: Boolean = false) {
        invalidateCounts()
        if (collections) collectionMembers = null
        if (afterWrites) reloadAfterQueuedWrites() else reload()
    }

    /** Drawer counts are stale: drop them and any computation still running on the old data. */
    private fun invalidateCounts() {
        counts = null
        countsJob?.cancel()
        countsJob = null
    }

    private fun sameRows(a: List<BookRow>, b: List<BookRow>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].book != b[i].book || a[i].inCollection != b[i].inCollection) return false
        }
        return true
    }

    /** The adapter of the list view for the current mode (목록 cards or 간단히 rows). */
    private fun listAdapterForMode(): BookAdapter =
        if (listMode == LibraryListMode.COMPACT) compactAdapter else bookAdapter

    private fun showBooks(rows: List<BookRow>, scrollTop: Boolean) {
        shownRows = rows
        // Books that left the list (moved to another shelf, trashed, filtered out) are no longer checked.
        if (selection.active && selection.retain(rows.mapTo(HashSet(rows.size)) { it.book.id })) updateSelectionBar()
        if (listMode == LibraryListMode.GRID) {
            listView.visibility = View.GONE
            gridView.visibility = View.VISIBLE
            if (!sameRows(gridAdapter.rows, rows)) gridAdapter.submit(rows)
            if (scrollTop) gridView.setSelection(0)
        } else {
            gridView.visibility = View.GONE
            listView.visibility = View.VISIBLE
            val adapter = listAdapterForMode()
            if (listView.adapter !== adapter) {
                listView.adapter = adapter
                adapter.submit(rows)
            } else if (!sameRows(adapter.rows, rows)) {
                adapter.submit(rows)
            }
            if (scrollTop) listView.setSelection(0)
        }
        if (rows.isEmpty()) showEmptyState() else emptyScroll.visibility = View.GONE
    }

    private fun showGroups(groups: List<ShelfGroup>, scrollTop: Boolean) {
        shownRows = emptyList()
        endSelection()
        gridView.visibility = View.GONE
        listView.visibility = View.VISIBLE
        if (listView.adapter !== groupAdapter) listView.adapter = groupAdapter
        if (groupAdapter.shelf != shelf || groupAdapter.groups != groups) groupAdapter.submit(shelf, groups)
        val restore = groupScroll
        if (restore != null) {
            groupScroll = null
            listView.setSelectionFromTop(restore[0], restore[1])
        } else if (scrollTop) {
            listView.setSelection(0)
        }
        if (groups.isEmpty()) showEmptyState() else emptyScroll.visibility = View.GONE
    }

    private fun showEmptyState() {
        val buttons = ArrayList<Pair<String, () -> Unit>>()
        val noSearch = query.isBlank()
        val msg: String
        val storageShelf = shelf == Shelf.ALL || shelf == Shelf.READING_NOW || shelf == Shelf.DOWNLOADS ||
            shelf == Shelf.FOLDERS || shelf == Shelf.FORMATS
        if (!hasAccess && noSearch && storageShelf && group == null) {
            msg = "책을 찾으려면 ‘모든 파일 접근’ 권한이 필요합니다.\n권한을 허용하거나 ‘파일 열기’로 책을 직접 추가하세요."
            buttons += "권한 허용" to { requestStorageAccess() }
            buttons += "폴더 추가" to { pickTree() }
            buttons += "파일 열기" to { openFilePicker() }
        } else {
            msg = LibraryText.emptyMessage(shelf, query, group != null, flagButtons = listMode == LibraryListMode.LIST)
            if (noSearch && group == null) {
                when (shelf) {
                    Shelf.ALL, Shelf.READING_NOW, Shelf.DOWNLOADS, Shelf.FOLDERS, Shelf.FORMATS -> {
                        buttons += "도서 스캔" to { manualScan() }
                        buttons += "파일 열기" to { openFilePicker() }
                    }
                    Shelf.COLLECTIONS -> buttons += "새 컬렉션" to { newCollection(null) }
                    else -> Unit
                }
            }
        }
        showMessage(msg, buttons)
    }

    private fun showMessage(msg: CharSequence, buttons: List<Pair<String, () -> Unit>>) {
        emptyText.text = msg
        emptyButtons.removeAllViews()
        buttons.forEach { (text, action) ->
            emptyButtons.addView(textButton(text) { action() }.apply { minWidth = dp(180) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
        emptyScroll.visibility = View.VISIBLE
    }

    private fun showError(t: Throwable) {
        val msg = ErrorLines.reason(t)?.let { "서재를 불러오지 못했습니다.\n$it" } ?: "서재를 불러오지 못했습니다."
        showMessage(ErrorLines.withGrayDetail(msg, t), listOf("다시 시도" to { reload() }))
    }

    private fun ensureCounts() {
        counts?.let { showCounts(it); return }
        if (countsJob?.isActive == true) return
        countsJob = scope.launch {
            val c = withContext(Dispatchers.IO) {
                runCatching { Library.shelfCounts() }.getOrNull()
            }
            if (c != null) {
                counts = c
                showCounts(c)
            }
        }
    }

    private fun showCounts(c: Map<Shelf, Int>) {
        drawerItems.forEach { (s, item) ->
            val n = c[s] ?: 0
            val text = if (n > 0) n.toString() else ""
            if (item.count.text.toString() != text) item.count.text = text
        }
    }

    // ============================================================================================ status & jobs

    private fun updateStatus() {
        val text = listOfNotNull(localStatus, LibraryJobs.status()).joinToString("  ·  ").ifEmpty { null }
        if (text == null) {
            setStatusVisible(false)
        } else {
            if (statusText.text.toString() != text) statusText.text = text
            setStatusVisible(true)
        }
    }

    override fun onJobProgress() {
        if (uiBuilt && resumed) updateStatus() // else onResume refreshes the strip
    }

    override fun onJobDone(message: String?, rowsChanged: Boolean) {
        if (!uiBuilt) return // the list is loaded fresh when the library is first shown
        // Not in front (e.g. a book is opening): onResume refreshes the strip and the list anyway, and doing it now
        // would compete with the reader (a query, and a redraw of a covered window). A scan that changed no book
        // leaves the list as it is (the periodic one ends on an idle screen).
        if (resumed) {
            updateStatus()
            if (rowsChanged) changed(collections = false)
        }
        if (message != null) toast(message)
    }

    internal fun manualScan() {
        hasAccess = hasStorageAccess()
        if (!hasAccess) {
            requestStorageAccess()
            return
        }
        if (!LibraryJobs.startScan(this, announce = true)) toast("이미 스캔 중입니다")
    }

    // ============================================================================================ permissions

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun updatePermissionPanel() {
        val hidden = Settings.raw().getBoolean(PREF_PERM_PANEL_HIDDEN, false)
        permPanel.visibility = if (!hasAccess && !hidden) View.VISIBLE else View.GONE
    }

    internal fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val specific = Intent(SystemSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            if (tryStartForResult(specific, REQ_ALL_FILES)) return
            if (tryStartForResult(Intent(SystemSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES)) {
                toast("목록에서 ‘${getString(R.string.app_name)}’을 찾아 허용하세요")
                return
            }
            showNoPermissionScreen()
        } else {
            val perm = Manifest.permission.READ_EXTERNAL_STORAGE
            val asked = Settings.raw().getBoolean(PREF_LEGACY_ASKED, false)
            if (asked && !shouldShowRequestPermissionRationale(perm)) {
                // "다시 묻지 않음": requestPermissions would be denied silently — open the app's settings page.
                val details = Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                if (tryStartForResult(details, REQ_ALL_FILES)) {
                    toast("권한 → 저장공간을 허용하세요")
                    return
                }
            }
            Settings.raw().edit().putBoolean(PREF_LEGACY_ASKED, true).apply()
            requestPermissions(arrayOf(perm), REQ_LEGACY_PERM)
        }
    }

    private fun tryStartForResult(intent: Intent, requestCode: Int): Boolean =
        try {
            startActivityForResult(intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION), requestCode)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }

    @Deprecated("Platform permission callback (no AndroidX).")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LEGACY_PERM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            hasAccess = true
            if (uiBuilt) updatePermissionPanel()
            LibraryJobs.startScan(this, announce = true)
        }
    }

    // ============================================================================================ SAF

    internal fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "text/plain", "application/pdf", "*/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        if (!tryStartForResult(intent, REQ_OPEN_FILE)) toast("파일 선택기를 열 수 없습니다")
    }

    internal fun pickTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        if (!tryStartForResult(intent, REQ_OPEN_TREE)) toast("폴더 선택기를 열 수 없습니다")
    }

    @Deprecated("Platform activity result (no AndroidX).")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null || !uiBuilt) return
        when (requestCode) {
            REQ_OPEN_FILE -> {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
                }
                if (uris.isEmpty()) data.data?.let { uris += it }
                if (uris.isNotEmpty()) importPicked(uris)
            }
            REQ_OPEN_TREE -> data.data?.let { onTreePicked(it) }
        }
    }

    private fun importPicked(uris: List<Uri>) {
        val app = applicationContext
        if (uris.size == 1) {
            val uri = uris[0]
            localStatus = "여는 중…"
            updateStatus()
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { LibraryImport.importDocument(app, uri) } }
                localStatus = null
                updateStatus()
                result.onSuccess { book ->
                    if (book == null) {
                        toast("지원하지 않는 파일입니다 (EPUB · TXT · PDF만 열 수 있습니다)")
                    } else {
                        changed()
                        ReaderActivity.open(this@LibraryActivity, book)
                    }
                }.onFailure { toast(ErrorLines.line("파일을 열 수 없습니다", it)) }
            }
            return
        }
        val started = LibraryJobs.startImport { progress ->
            var added = 0
            uris.forEachIndexed { i, u ->
                try {
                    if (LibraryImport.importDocument(app, u) != null) added++
                } catch (t: Throwable) {
                    // skip unreadable document
                }
                progress(i + 1, uris.size)
            }
            LibraryText.importedMessage(added)
        }
        if (!started) toast("이미 가져오는 중입니다")
    }

    private fun onTreePicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // not persistable on this provider; the import below still works for this session
        }
        val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (t: Throwable) { null }
        val root = try { Environment.getExternalStorageDirectory().absolutePath } catch (t: Throwable) { "/storage/emulated/0" }
        val path = docId?.let { LibraryText.docIdToPath(uri.authority, it, root) }
        hasAccess = hasStorageAccess()
        if (hasAccess && path != null) {
            val appContext = applicationContext
            scope.launch {
                // Empty scanFolders = "scan the default roots"; they are needed to tell whether the folder is
                // covered already (getExternalFilesDirs touches the disk: IO).
                val roots = withContext(Dispatchers.IO) {
                    runCatching { FileScanner.defaultRoots(appContext).map { it.absolutePath } }.getOrDefault(emptyList())
                }
                val app = Settings.app
                val merged = LibraryText.addScanFolder(app.scanFolders, path, roots)
                if (merged != null) {
                    Settings.saveApp(app.copy(scanFolders = merged))
                    toast("스캔 폴더에 추가했습니다: $path")
                } else {
                    toast("이미 스캔 범위에 포함된 폴더입니다")
                }
                if (!LibraryJobs.startScan(this@LibraryActivity, announce = true)) toast("이미 스캔 중입니다")
            }
            return
        }
        confirmTreeImport(uri, noPermission = !hasAccess)
    }

    private fun confirmTreeImport(uri: Uri, noPermission: Boolean) {
        val why = if (noPermission) "모든 파일 접근 권한이 없어 이 폴더를 직접 읽을 수 없습니다."
        else "이 폴더는 파일 경로로 읽을 수 없는 저장소입니다."
        confirmDialog(
            title = "폴더에서 가져오기",
            message = "$why\n폴더 안의 EPUB · TXT · PDF 파일을 앱 저장소로 복사해서 서재에 추가할까요?",
            ok = "복사",
        ) { startTreeImport(uri) }
    }

    private fun startTreeImport(uri: Uri) {
        val app = applicationContext
        val started = LibraryJobs.startImport { progress ->
            val n = LibraryImport.importTree(app, uri, progress)
            LibraryText.treeImportedMessage(n)
        }
        if (!started) toast("이미 가져오는 중입니다")
    }

    // ============================================================================================ overflow

    private fun showOverflow(anchor: View) {
        val items = ArrayList<MenuItem>()
        items += MenuItem("정렬: ${sort.label}", R.drawable.ic_sort) { chooseSort() }
        items += MenuItem("보기: ${listMode.label}", modeIcon(listMode)) { chooseMode() }
        if (shelf == Shelf.COLLECTIONS && group == null) items += MenuItem("새 컬렉션", R.drawable.ic_add) { newCollection(null) }
        if (shelf == Shelf.TRASH) items += MenuItem("휴지통 비우기", R.drawable.ic_delete_forever) { confirmEmptyTrash() }
        items += MenuItem("도서 스캔", R.drawable.ic_refresh) { manualScan() }
        items += MenuItem("파일 열기", R.drawable.ic_file_open) { openFilePicker() }
        items += MenuItem("폴더 추가", R.drawable.ic_create_new_folder) { pickTree() }
        items += MenuItem("설정", R.drawable.ic_settings) { SettingsActivity.open(this) }
        popupMenu(anchor, items, 260)
    }

    private fun chooseSort() {
        val options = LibrarySort.entries
        chooser("정렬", options.map { it.label }, sort.ordinal) { i ->
            val s = options[i]
            if (s == sort) return@chooser
            sort = s
            Settings.saveApp(Settings.app.copy(librarySort = s))
            reload(scrollTop = true)
        }
    }

    private fun chooseMode() {
        val options = LibraryListMode.entries
        chooser("보기", options.map { it.label }, listMode.ordinal) { i -> setListMode(options[i]) }
    }

    /** The toolbar toggle: 목록 → 간단히 → 표지 → 목록. */
    private fun cycleListMode() = setListMode(LibraryText.nextListMode(listMode))

    private fun setListMode(m: LibraryListMode) {
        if (m == listMode) return
        listMode = m
        Settings.saveApp(Settings.app.copy(libraryListMode = m))
        showModeButton()
        reload(scrollTop = true)
    }

    private fun showModeButton() {
        if (viewBtnMode == listMode) return
        viewBtnMode = listMode
        viewBtn.setImageResource(modeIcon(listMode))
        viewBtn.contentDescription = modeDescription(listMode)
        viewBtn.setOnLongClickListener { toast(modeDescription(listMode)); true }
    }

    private fun modeIcon(m: LibraryListMode): Int = when (m) {
        LibraryListMode.LIST -> R.drawable.ic_view_list
        LibraryListMode.COMPACT -> R.drawable.ic_format_list_bulleted
        LibraryListMode.GRID -> R.drawable.ic_grid_view
            LibraryListMode.COVERS -> R.drawable.ic_grid_view
    }

    /** The toggle shows the current view; its long-press label says so and what a tap does. */
    private fun modeDescription(m: LibraryListMode): String = "보기: ${m.label} (눌러서 바꾸기)"

    private fun scrollPage(dir: Int) {
        val v: AbsListView = if (gridView.visibility == View.VISIBLE) gridView else listView
        if (v.visibility != View.VISIBLE || v.childCount == 0) return
        val h = v.height - v.paddingTop - v.paddingBottom
        v.scrollListBy(dir * (h - dp(24)).coerceAtLeast(dp(48)))
    }

    // ============================================================================================ open last

    /**
     * Open-last start: decided before any library work. The window is kept from drawing (the launch splash stays up)
     * while the last-read book is looked up on IO; the reader then opens over a window that never drew, so the screen
     * goes splash → page with no library frame (one e-ink update less), and the library's build, list query and cover
     * jobs only run if the user comes back to it.
     */
    private fun startOpenLast(resumeId: Long = -1L) {
        decidingOpenLast = true
        holdDraw = true
        window.decorView.viewTreeObserver.addOnPreDrawListener(drawHold)
        handler.postDelayed(openLastTimeout, OPEN_LAST_MAX_WAIT_MS)
        // Undispatched: the query starts now instead of after the whole launch transaction.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val book = withContext(Dispatchers.IO) {
                runCatching {
                    if (resumeId > 0) {
                        val b = Library.book(resumeId)?.takeIf { !it.trashed && File(it.path).isFile }
                        if (b == null) ResumeState.clear()
                        else ResumeState.noteAttempt()
                        b
                    } else Library.lastOpened()?.takeIf { !it.trashed && File(it.path).isFile }
                }.getOrNull()
            }
            onLastBookLoaded(book)
        }
    }

    private fun onLastBookLoaded(book: Book?) {
        handler.removeCallbacks(openLastTimeout)
        val waiting = decidingOpenLast
        decidingOpenLast = false
        if (isFinishing || isDestroyed) return
        // Only from the foreground (a start from the background would be blocked or yank the user back).
        if (book != null && resumed) {
            try {
                ReaderActivity.open(this, book)
                return // the draw hold stays until the library is shown (onResume → ensureUi)
            } catch (t: Throwable) {
                // fall through: show the library
            }
        }
        if (waiting && resumed) showLibrary() // not resumed: the next onResume builds it
    }

    // ============================================================================================ BookActions

    /** Card callbacks (kept off the public activity API). */
    private val actions = object : BookActions {
        override val selection: BookSelection get() = this@LibraryActivity.selection
        override fun tap(row: BookRow, anchor: View) = onBookTap(row, anchor)
        override fun longPress(row: BookRow, anchor: View) = onBookLongPress(row, anchor)
        override fun showBookMenu(row: BookRow, anchor: View) = bookMenu(row, anchor)
        override fun toggleFlag(row: BookRow, flag: BookFlag) = this@LibraryActivity.toggleFlag(row, flag)
        override fun showCollections(book: Book) = collectionsDialog(book)
    }

    private fun onBookTap(row: BookRow, anchor: View) {
        when {
            selection.active -> toggleSelected(row.book.id)
            row.book.trashed -> bookMenu(row, anchor)
            else -> openBook(row.book)
        }
    }

    /**
     * Long-press starts multi-select with the book checked (T1-13; it used to open the book menu, which is now ⋮ or
     * [더보기]). The trash keeps the menu: its actions (복원, 영구 삭제) have no batch form.
     */
    private fun onBookLongPress(row: BookRow, anchor: View) {
        when {
            row.book.trashed || shelf == Shelf.TRASH -> bookMenu(row, anchor)
            selection.active -> toggleSelected(row.book.id)
            else -> startSelection(row.book.id)
        }
    }

    internal fun openBook(book: Book) {
        if (book.trashed) {
            toast("휴지통에 있는 책입니다. 먼저 복원하세요.")
            return
        }
        ReaderActivity.open(this, book)
    }

    internal fun toggleFlag(row: BookRow, flag: BookFlag) {
        val b = row.book
        val nb = when (flag) {
            BookFlag.FAVORITE -> b.copy(favorite = !b.favorite)
            BookFlag.TO_READ -> if (!b.toRead) b.copy(toRead = true, haveRead = false) else b.copy(toRead = false)
            BookFlag.HAVE_READ -> if (!b.haveRead) b.copy(haveRead = true, toRead = false) else b.copy(haveRead = false)
        }
        replaceRow(BookRow.of(nb, row.inCollection))
        invalidateCounts()
        // Rows equal to the optimistic one are not redrawn by the reload; a book that left this shelf disappears.
        queueWrite("저장하지 못했습니다") {
            when (flag) {
                BookFlag.FAVORITE -> Library.setFavorite(b.id, nb.favorite)
                BookFlag.TO_READ -> Library.setToRead(b.id, nb.toRead)
                BookFlag.HAVE_READ -> Library.setHaveRead(b.id, nb.haveRead)
            }
        }
    }

    /**
     * Runs [write] on the serial write lane, then [done] on the main thread when it succeeded (a failure is a toast).
     * With [reload] the list is reloaded from the database once every queued write is stored: a reload that started
     * before would repaint the old state, so it is dropped now.
     */
    internal fun queueWrite(errorPrefix: String, reload: Boolean = true, done: (() -> Unit)? = null, write: () -> Unit) {
        if (reload) {
            loadJob?.cancel()
            reloadAfterWrites = true
        }
        pendingWrites++
        scope.launch {
            val r = try {
                withContext(writeDispatcher) { runCatching(write) }
            } finally {
                pendingWrites--
            }
            r.onSuccess { done?.invoke() }.onFailure { toast(ErrorLines.line(errorPrefix, it)) }
            if (pendingWrites == 0 && reloadAfterWrites) {
                reloadAfterWrites = false
                reload()
            }
        }
    }

    /** Reloads now, or after the queued writes when some are still running ([queueWrite]). */
    internal fun reloadAfterQueuedWrites() {
        if (pendingWrites == 0) {
            reload()
        } else {
            loadJob?.cancel()
            reloadAfterWrites = true
        }
    }

    /** Swaps one row in place (instant icon feedback) without a database round trip. */
    private fun replaceRow(row: BookRow) {
        val i = shownRows.indexOfFirst { it.book.id == row.book.id }
        if (i < 0) return
        shownRows = shownRows.toMutableList().also { it[i] = row }
        val adapter = if (listMode == LibraryListMode.GRID) gridAdapter else listAdapterForMode()
        if (adapter.rows.getOrNull(i)?.book?.id == row.book.id) adapter.submit(shownRows)
    }

    // ============================================================================================ multi-select

    private fun startSelection(id: Long) {
        if (shownRows.isEmpty()) return
        if (searchOpen) hideKeyboard()
        selection.start(id)
        val bar = selectionBar ?: buildSelectionBar().also { selectionBar = it }
        toolbarBar.visibility = View.GONE
        bar.root.visibility = View.VISIBLE
        updateSelectionBar()
        refreshSelectionViews()
    }

    /** Leaves selection mode (Back, [닫기], a batch action, another shelf or group). No-op when not selecting. */
    internal fun endSelection() {
        if (!selection.active) return
        selection.end()
        selectionBar?.root?.visibility = View.GONE
        toolbarBar.visibility = View.VISIBLE
        refreshSelectionViews()
    }

    private fun toggleSelected(id: Long) {
        selection.toggle(id)
        updateSelectionBar()
        refreshSelectionViews()
    }

    /** Redraws the check boxes of the items on screen; the others bind with the current state when they scroll in. */
    private fun refreshSelectionViews() {
        val v: AbsListView = if (gridView.visibility == View.VISIBLE) gridView else listView
        for (i in 0 until v.childCount) (v.getChildAt(i).tag as? SelectableHolder)?.showSelection()
    }

    private fun updateSelectionBar() {
        val bar = selectionBar ?: return
        val n = selection.size
        val title = LibraryText.selectionTitle(n)
        if (bar.title.text.toString() != title) bar.title.text = title
        val single = if (n == 1) View.VISIBLE else View.GONE
        if (bar.more.visibility != single) bar.more.visibility = single
        val enabled = n > 0
        bar.actions.forEach { cell ->
            if (cell.isEnabled != enabled) {
                cell.isEnabled = enabled
                cell.alpha = if (enabled) 1f else 0.4f
            }
        }
    }

    /**
     * The selection toolbar, in place of the normal one: "N권 선택" [⋮ 더보기] [전체] [닫기] over the batch actions
     * [컬렉션에 추가] [다 읽음으로] [읽을 책으로] [휴지통]. Built once, on the first long-press.
     */
    private fun buildSelectionBar(): SelectionBar {
        val bar = vertical { setBackgroundColor(Ink.WHITE); visibility = View.GONE }
        val top = horizontal {
            minimumHeight = dp(56)
            setPadding(dp(4), 0, dp(4), 0)
        }
        val title = label("", 20f, bold = true, maxLines = 1).apply { setPadding(dp(12), 0, dp(8), 0) }
        top.addView(title, lp(0, WRAP_CONTENT, 1f))
        lateinit var more: ImageButton
        more = iconButton(R.drawable.ic_more_vert, "더보기") { showSelectedBookMenu(more) }.apply { visibility = View.GONE }
        top.addView(more)
        top.addView(flatButton("전체") { selectAllShown() })
        top.addView(flatButton("닫기") { endSelection() })
        bar.addView(top, lp())

        val actionsRow = horizontal { setPadding(dp(4), 0, dp(4), dp(4)) }
        val cells = listOf(
            actionCell(R.drawable.ic_library_books, "컬렉션에 추가") { batchAddToCollection() },
            actionCell(R.drawable.ic_done_all, "다 읽음으로") { batchShelf(Shelf.HAVE_READ) },
            actionCell(R.drawable.ic_schedule, "읽을 책으로") { batchShelf(Shelf.TO_READ) },
            actionCell(R.drawable.ic_delete, "휴지통") { batchTrash() },
        )
        cells.forEach { actionsRow.addView(it, lp(0, dp(64), 1f)) }
        bar.addView(actionsRow, lp())
        bar.addView(hairline())
        // Right under the (hidden) normal toolbar: the list moves down by the actions row, once, on entering.
        main.addView(bar, main.indexOfChild(toolbarBar) + 1, lp())
        return SelectionBar(bar, title, more, cells)
    }

    /** Borderless bold text button of the selection toolbar (48dp high). */
    private fun flatButton(text: String, onClick: () -> Unit): TextView = label(text, 16f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minWidth = dp(56)
        setPadding(dp(12), 0, dp(12), 0)
        background = pressableBackground()
        setOnClickListener { onClick() }
    }

    /** A batch action: icon over a one-line label, the whole cell pressable. */
    private fun actionCell(iconRes: Int, text: String, onClick: () -> Unit): View = vertical {
        gravity = Gravity.CENTER
        background = pressableBackground()
        contentDescription = text
        addView(icon(iconRes, 24))
        addView(label(text, 13f, maxLines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setOnClickListener { if (selection.size > 0) onClick() }
    }

    /** [전체]: checks every listed book (all of them, not only the ones on screen), or unchecks them all. */
    private fun selectAllShown() {
        if (selection.toggleAll(shownRows.map { it.book.id })) {
            updateSelectionBar()
            refreshSelectionViews()
        }
    }

    /**
     * [더보기] with one book checked: the single-book menu (grid cells have no ⋮), with the flags (the card buttons
     * are hidden while selecting); picking an item ends selection.
     */
    private fun showSelectedBookMenu(anchor: View) {
        val id = selection.single() ?: return
        val row = shownRows.firstOrNull { it.book.id == id } ?: return
        bookMenu(row, anchor, flags = true) { endSelection() }
    }

    /** [다 읽음으로] / [읽을 책으로]: one transaction for all checked books (the same rules as the card flags). */
    private fun batchShelf(target: Shelf) {
        val ids = selection.snapshot()
        endSelection()
        invalidateCounts()
        queueWrite("저장하지 못했습니다", done = { toast(LibraryText.addedToShelf(target, ids.size)) }) {
            if (target == Shelf.HAVE_READ) Library.setHaveRead(ids, true) else Library.setToRead(ids, true)
        }
    }

    /** [휴지통]: one book goes at once (like the book menu); several ask first, since the trash restores one by one. */
    private fun batchTrash() {
        val ids = selection.snapshot()
        val run = {
            endSelection()
            invalidateCounts()
            queueWrite("휴지통으로 이동하지 못했습니다", done = { toast(LibraryText.trashedMessage(ids.size)) }) {
                Library.trash(ids)
            }
        }
        if (ids.size == 1) run() else confirmDialog("휴지통으로 이동", LibraryText.trashQuestion(ids.size), "이동") { run() }
    }

    /** [컬렉션에 추가]: pick a collection (or make one); the checked books are added in one transaction. */
    private fun batchAddToCollection() {
        val ids = selection.snapshot()
        pickCollection { c ->
            endSelection()
            collectionMembers = null
            invalidateCounts()
            queueWrite("저장하지 못했습니다", done = { toast(LibraryText.addedToCollection(c.name, ids.size)) }) {
                Library.addToCollection(ids, c.id)
            }
        }
    }

    internal fun onGroupLongPress(g: ShelfGroup): Boolean {
        if (shelf != Shelf.COLLECTIONS) return false
        collectionGroupMenu(g)
        return true
    }
}
