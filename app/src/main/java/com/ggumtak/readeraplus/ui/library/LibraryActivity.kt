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
import android.view.View
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
import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.data.ShelfGroup
import com.ggumtak.readeraplus.reader.ReaderActivity
import com.ggumtak.readeraplus.settings.LibraryListMode
import com.ggumtak.readeraplus.settings.LibrarySort
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.MenuItem
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.einkListView
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.icon
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.popupMenu
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import com.ggumtak.readeraplus.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.provider.Settings as SystemSettings

/**
 * Library (launcher) screen, ReadEra-style in black & white: toolbar (drawer / shelf title / search /
 * overflow), a drawer overlay with every shelf, book cards (list) or covers (grid), grouped shelves,
 * search-as-you-type, sort, book menu actions, collections, trash, storage permission flow, background
 * scanning with a status row, SAF open/import, open-last-on-start and the about/licenses dialog.
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
        private const val AUTO_SCAN_DELAY_MS = 1500L
        private const val STATUS_HEIGHT_DP = 36
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
    /** Flag writes in flight; the list is reloaded from the database only once they are all stored. */
    private var pendingWrites = 0
    /** Serial IO lane for flag writes so quick taps reach the database in tap order. */
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    // ---- views
    private lateinit var root: FrameLayout
    private lateinit var navBtn: ImageButton
    private lateinit var titleView: TextView
    private lateinit var extraBtn: ImageButton
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
    private lateinit var scrim: View
    private lateinit var drawer: LinearLayout
    private val drawerItems = HashMap<Shelf, DrawerItem>()

    private lateinit var bookAdapter: BookListAdapter
    private lateinit var gridAdapter: BookGridAdapter
    private lateinit var groupAdapter: GroupAdapter

    private class DrawerItem(val row: LinearLayout, val label: TextView, val count: TextView)

    private val searchRunnable = Runnable { applySearch(searchEdit.text.toString()) }
    private val loadingRunnable = Runnable { showMessage("불러오는 중…", emptyList()) }
    private val autoScanRunnable = Runnable {
        // Re-check when it fires: a scan started meanwhile (e.g. right after a permission grant) may have
        // finished already, and scanning twice in a row costs seconds of CPU and disk on the Comet.
        val last = Settings.raw().getLong(LibraryJobs.PREF_LAST_SCAN, 0L)
        if (hasAccess && !LibraryJobs.scanning && LibraryText.rescanDue(last, System.currentTimeMillis())) {
            LibraryJobs.startScan(this, announce = false)
        }
    }

    // ============================================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = Settings.app
        listMode = app.libraryListMode
        sort = app.librarySort
        val savedShelf = savedInstanceState?.getString(STATE_SHELF) ?: Settings.raw().getString(PREF_SHELF, null)
        shelf = Shelf.entries.firstOrNull { it.name == savedShelf } ?: Shelf.ALL
        if (savedInstanceState != null && LibraryText.isGrouped(shelf)) {
            group = savedInstanceState.getString(STATE_GROUP)
            groupLabel = savedInstanceState.getString(STATE_GROUP_LABEL)
        }
        buildUi()
        setContentView(root)
        updateToolbar()
        hasAccess = hasStorageAccess()
        if (savedInstanceState == null) maybeOpenLast()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !hasAccess &&
            !Settings.raw().getBoolean(PREF_LEGACY_ASKED, false)
        ) {
            Settings.raw().edit().putBoolean(PREF_LEGACY_ASKED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_LEGACY_PERM)
        }
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
        val app = Settings.app
        listMode = app.libraryListMode
        sort = app.librarySort
        val access = hasStorageAccess()
        val newlyGranted = access && !hasAccess
        hasAccess = access
        updatePermissionPanel()
        val lastScan = Settings.raw().getLong(LibraryJobs.PREF_LAST_SCAN, 0L)
        handler.removeCallbacks(autoScanRunnable)
        if (access && newlyGranted) {
            LibraryJobs.startScan(this, announce = true)
        } else if (access && LibraryText.rescanDue(lastScan, System.currentTimeMillis())) {
            // Deferred so a book opened right away (or open-last-on-start) doesn't share the CPU/disk with
            // the scan; leaving the screen before it fires postpones the scan to the next visit.
            handler.postDelayed(autoScanRunnable, AUTO_SCAN_DELAY_MS)
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
        if (hasFocus && refreshOnFocus) {
            invalidateCounts()
            reload()
        }
    }

    override fun onPause() {
        refreshOnFocus = false
        handler.removeCallbacks(autoScanRunnable)
        super.onPause()
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
        when (LibraryText.backStep(drawerOpen, searchOpen, group != null)) {
            LibraryText.BackStep.CLOSE_DRAWER -> closeDrawer()
            LibraryText.BackStep.CLOSE_SEARCH -> closeSearch()
            LibraryText.BackStep.LEAVE_GROUP -> leaveGroup()
            LibraryText.BackStep.FINISH -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** Hardware page keys / volume keys scroll the list by a screen (e-ink friendly paging). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!drawerOpen) {
            val a = Settings.app
            val code = event.keyCode
            val dir = LibraryText.keyDirection(code, a.volumeKeysTurn, a.invertVolumeKeys, a.nextPageKeys, a.prevPageKeys)
            val isVolume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
            // While typing a search, only volume keys page (learned keys could be ordinary text keys).
            if (dir != 0 && (!searchEdit.isFocused || isVolume)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) scrollPage(dir)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ============================================================================================ UI build

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Ink.WHITE) }
        val main = vertical { setBackgroundColor(Ink.WHITE) }
        main.addView(buildToolbar(), lp())
        main.addView(buildSearchRow(), lp())
        main.addView(buildPermissionPanel(), lp().apply { setMargins(dp(8), dp(8), dp(8), dp(4)) })

        val content = FrameLayout(this)
        bookAdapter = BookListAdapter(this, actions)
        groupAdapter = GroupAdapter(this, ::enterGroup, ::onGroupLongPress)
        gridAdapter = BookGridAdapter(this, actions)

        listView = einkListView().apply {
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
        gridAdapter.cellWidth = (widthPx - 2 * pad - (cols - 1) * spacing) / cols
        gridView = GridView(this).apply {
            numColumns = cols
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = spacing
            verticalSpacing = dp(10)
            setPadding(pad, pad, pad, pad)
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

        scrim = View(this).apply {
            isClickable = true
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val drawerWidth = minOf((widthPx * 0.8f).toInt(), dp(320))
        drawer = buildDrawer()
        root.addView(drawer, FrameLayout.LayoutParams(drawerWidth, MATCH_PARENT, Gravity.START))
    }

    private fun buildToolbar(): View {
        val bar = vertical { setBackgroundColor(Ink.WHITE) }
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
                "기기에 있는 EPUB · TXT 파일을 찾아 서재에 보여 주려면 ‘모든 파일 접근’을 허용하세요. " +
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
        col.addView(drawerRow(R.drawable.ic_file_open, "파일 열기") { closeDrawer(); openFilePicker() }.row, lp())
        col.addView(drawerRow(R.drawable.ic_refresh, "도서 스캔") { closeDrawer(); manualScan() }.row, lp())
        col.addView(drawerRow(R.drawable.ic_info, "정보") { closeDrawer(); showAbout() }.row, lp())
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
        drawerOpen = true
        drawerItems.forEach { (s, item) ->
            val selected = s == shelf
            item.row.background = if (selected) ColorDrawable(Ink.PRESSED) else pressableBackground()
            item.label.bold(selected)
            item.count.setTextColor(if (selected) Ink.BLACK else Ink.GRAY) // no grey text on the grey row
        }
        scrim.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
        ensureCounts()
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawer.visibility = View.GONE
        scrim.visibility = View.GONE
    }

    private fun selectShelf(s: Shelf) {
        closeDrawer()
        if (s == shelf && group == null) return
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
        group = g.key
        groupLabel = LibraryText.groupTitle(shelf, g)
        if (searchOpen) closeSearch(reloadAfter = false)
        updateToolbar()
        reload(scrollTop = true)
    }

    private fun leaveGroup() {
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
    private fun collectionMemberIds(): Set<Long> {
        val set = HashSet<Long>()
        for (c in Library.collections()) {
            Library.books(LibraryQuery(Shelf.COLLECTIONS, c.id.toString()), LibrarySort.TITLE).forEach { set += it.id }
        }
        return set
    }

    /** Marks derived data stale after a change (counts, collection icons) and reloads the list. */
    internal fun changed(collections: Boolean = false) {
        invalidateCounts()
        if (collections) collectionMembers = null
        reload()
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

    private fun showBooks(rows: List<BookRow>, scrollTop: Boolean) {
        val grid = listMode == LibraryListMode.GRID
        if (grid) {
            listView.visibility = View.GONE
            gridView.visibility = View.VISIBLE
            if (!sameRows(gridAdapter.rows, rows)) gridAdapter.submit(rows)
            if (scrollTop) gridView.setSelection(0)
        } else {
            gridView.visibility = View.GONE
            listView.visibility = View.VISIBLE
            if (listView.adapter !== bookAdapter) {
                listView.adapter = bookAdapter
                bookAdapter.submit(rows)
            } else if (!sameRows(bookAdapter.rows, rows)) {
                bookAdapter.submit(rows)
            }
            if (scrollTop) listView.setSelection(0)
        }
        if (rows.isEmpty()) showEmptyState() else emptyScroll.visibility = View.GONE
    }

    private fun showGroups(groups: List<ShelfGroup>, scrollTop: Boolean) {
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
            msg = LibraryText.emptyMessage(shelf, query, group != null)
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

    private fun showMessage(msg: String, buttons: List<Pair<String, () -> Unit>>) {
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
        showMessage("서재를 불러오지 못했습니다.\n${t.message ?: t.javaClass.simpleName}", listOf("다시 시도" to { reload() }))
    }

    private fun ensureCounts() {
        counts?.let { showCounts(it); return }
        if (countsJob?.isActive == true) return
        countsJob = scope.launch {
            val c = withContext(Dispatchers.IO) {
                runCatching {
                    val map = LinkedHashMap<Shelf, Int>()
                    for (s in Shelf.entries) {
                        map[s] = if (LibraryText.isGrouped(s)) Library.groups(s).size
                        else Library.books(LibraryQuery(s), LibrarySort.TITLE).size
                    }
                    map
                }.getOrNull()
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

    override fun onJobProgress() = updateStatus()

    override fun onJobDone(message: String?) {
        updateStatus()
        changed(collections = false)
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
            updatePermissionPanel()
            LibraryJobs.startScan(this, announce = true)
        }
    }

    // ============================================================================================ SAF

    internal fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "text/plain", "*/*"))
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
        if (resultCode != RESULT_OK || data == null) return
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
                        toast("지원하지 않는 파일입니다 (EPUB · TXT만 열 수 있습니다)")
                    } else {
                        changed()
                        ReaderActivity.open(this@LibraryActivity, book.id)
                    }
                }.onFailure { toast("파일을 열 수 없습니다: ${it.message ?: it.javaClass.simpleName}") }
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
            "문서 ${added}개를 추가했습니다"
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
            message = "$why\n폴더 안의 EPUB · TXT 파일을 앱 저장소로 복사해서 서재에 추가할까요?",
            ok = "복사",
        ) { startTreeImport(uri) }
    }

    private fun startTreeImport(uri: Uri) {
        val app = applicationContext
        val started = LibraryJobs.startImport { progress ->
            val n = LibraryImport.importTree(app, uri, progress)
            "문서 ${n}개를 가져왔습니다"
        }
        if (!started) toast("이미 가져오는 중입니다")
    }

    // ============================================================================================ overflow

    private fun showOverflow(anchor: View) {
        val items = ArrayList<MenuItem>()
        items += MenuItem("정렬: ${sort.label}", R.drawable.ic_sort) { chooseSort() }
        items += MenuItem(
            "보기: ${listMode.label}",
            if (listMode == LibraryListMode.GRID) R.drawable.ic_grid_view else R.drawable.ic_view_list,
        ) { chooseMode() }
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
        chooser("보기", options.map { it.label }, listMode.ordinal) { i ->
            val m = options[i]
            if (m == listMode) return@chooser
            listMode = m
            Settings.saveApp(Settings.app.copy(libraryListMode = m))
            reload(scrollTop = true)
        }
    }

    private fun scrollPage(dir: Int) {
        val v: AbsListView = if (gridView.visibility == View.VISIBLE) gridView else listView
        if (v.visibility != View.VISIBLE || v.childCount == 0) return
        val h = v.height - v.paddingTop - v.paddingBottom
        v.scrollListBy(dir * (h - dp(24)).coerceAtLeast(dp(48)))
    }

    // ============================================================================================ open last

    private fun maybeOpenLast() {
        if (!Settings.app.openLastOnStart) return
        val i = intent ?: return
        if (i.action != Intent.ACTION_MAIN) return
        if (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        scope.launch {
            val book = withContext(Dispatchers.IO) {
                runCatching { Library.lastOpened()?.takeIf { !it.trashed && File(it.path).isFile } }.getOrNull()
            }
            if (book != null && !isFinishing) ReaderActivity.open(this@LibraryActivity, book.id)
        }
    }

    // ============================================================================================ BookActions

    /** Card callbacks (kept off the public activity API). */
    private val actions = object : BookActions {
        override fun openBook(book: Book) = this@LibraryActivity.openBook(book)
        override fun showBookMenu(row: BookRow, anchor: View) = bookMenu(row, anchor)
        override fun toggleFlag(row: BookRow, flag: BookFlag) = this@LibraryActivity.toggleFlag(row, flag)
        override fun showCollections(book: Book) = collectionsDialog(book)
    }

    internal fun openBook(book: Book) {
        if (book.trashed) {
            toast("휴지통에 있는 문서입니다. 먼저 복원하세요.")
            return
        }
        ReaderActivity.open(this, book.id)
    }

    internal fun toggleFlag(row: BookRow, flag: BookFlag) {
        val b = row.book
        val nb = when (flag) {
            BookFlag.FAVORITE -> b.copy(favorite = !b.favorite)
            BookFlag.TO_READ -> if (!b.toRead) b.copy(toRead = true, haveRead = false) else b.copy(toRead = false)
            BookFlag.HAVE_READ -> if (!b.haveRead) b.copy(haveRead = true, toRead = false) else b.copy(haveRead = false)
        }
        // A reload that started before this tap would repaint the old icon: drop it, reload after the write.
        loadJob?.cancel()
        replaceRow(BookRow.of(nb, row.inCollection))
        invalidateCounts()
        pendingWrites++
        scope.launch {
            val r = try {
                withContext(writeDispatcher) {
                    runCatching {
                        when (flag) {
                            BookFlag.FAVORITE -> Library.setFavorite(b.id, nb.favorite)
                            BookFlag.TO_READ -> Library.setToRead(b.id, nb.toRead)
                            BookFlag.HAVE_READ -> Library.setHaveRead(b.id, nb.haveRead)
                        }
                    }
                }
            } finally {
                pendingWrites--
            }
            r.onFailure { toast("저장하지 못했습니다: ${it.message ?: it.javaClass.simpleName}") }
            // Rows equal to the optimistic ones are not redrawn; a book that left this shelf disappears.
            if (pendingWrites == 0) reload()
        }
    }

    /** Swaps one row in place (instant icon feedback) without a database round trip. */
    private fun replaceRow(row: BookRow) {
        fun swap(rows: List<BookRow>): List<BookRow>? {
            val i = rows.indexOfFirst { it.book.id == row.book.id }
            if (i < 0) return null
            return rows.toMutableList().also { it[i] = row }
        }
        swap(bookAdapter.rows)?.let { bookAdapter.submit(it) }
        swap(gridAdapter.rows)?.let { gridAdapter.submit(it) }
    }

    internal fun onGroupLongPress(g: ShelfGroup): Boolean {
        if (shelf != Shelf.COLLECTIONS) return false
        collectionGroupMenu(g)
        return true
    }
}
