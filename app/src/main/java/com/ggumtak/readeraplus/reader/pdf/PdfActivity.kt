package com.ggumtak.readeraplus.reader.pdf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.ReaderPresence
import com.ggumtak.readeraplus.data.ReadingLog
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.reader.DayClock
import com.ggumtak.readeraplus.reader.IntentFiles
import com.ggumtak.readeraplus.reader.ReaderActivity
import com.ggumtak.readeraplus.reader.ReaderIo
import com.ggumtak.readeraplus.reader.ReaderWindow
import com.ggumtak.readeraplus.reader.extras.TextActions
import com.ggumtak.readeraplus.reader.ReadingDelta
import com.ggumtak.readeraplus.reader.ReadingTracker
import com.ggumtak.readeraplus.reader.ResumeState
import com.ggumtak.readeraplus.reader.ScreenOnKeeper
import com.ggumtak.readeraplus.render.PdfPages
import com.ggumtak.readeraplus.render.PdfText
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkNumPad
import com.ggumtak.readeraplus.ui.kit.alert
import com.ggumtak.readeraplus.ui.kit.prompt
import com.ggumtak.readeraplus.ui.kit.showNoAnim
import com.ggumtak.readeraplus.ui.kit.ToolbarAction
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.toolbar
import com.ggumtak.readeraplus.ui.kit.vertical
import java.io.File
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * The PDF viewer: one fixed-layout page at a time from the platform [android.graphics.pdf.PdfRenderer], with
 * phone-style motion (pinch / animated double-tap zoom, panning with inertia, sliding page turns — see
 * [PdfPageView]; PDFs are read on phones, not on the e-ink device), tap zones, page keys, a page slider and 쪽 이동. Opens a library id or
 * an ACTION_VIEW uri (like the text reader, through [IntentFiles]); saves the page as the book's position
 * (section = page index, offset 0), its progress and reading time.
 *
 * Study tools (Flexcil-like): pen / highlighter / eraser strokes and bookmarks per page ([PdfNotes], saved as a
 * per-book file, the PDF itself is never changed), a page grid ([PdfThumbs]), text search, and a selection loop —
 * drawn with a stylus at any time or with a finger after 선택 — whose text goes to 사전·번역 / 웹 검색 / 복사.
 * Text needs the platform PDF text API ([PdfPages.canReadText]: Android 15, or 12–14 with the PDF system module).
 *
 * Every [PdfPages] call runs on one render thread ([worker]); results come back through [handler] tagged with the
 * document generation, so a late result for a closed document is dropped.
 */
class PdfActivity : Activity() {
    companion object {
        private const val TAG = "PdfActivity"
        private const val STATE_BOOK = "pdf.book"
        private const val STATE_PAGE = "pdf.page"
        /** Fitted page bitmaps kept: the page on screen and its neighbours. */
        private const val CACHE_PAGES = 4
        /** Largest fitted page bitmap (ARGB_8888: 16 MB). */
        private const val MAX_BASE_PIXELS = 4_000_000
        /** A zoomed viewport is rendered sharp this long after the last zoom / pan change. */
        private const val DETAIL_DELAY_MS = 120L
        private const val SAVE_DELAY_MS = 800L
        private const val NOTES_SAVE_DELAY_MS = 1000L
        private const val NOTES_DIR = "pdf_notes"
        /** Longest selected text shown as the action dialog's title. */
        private const val SELECTION_TITLE_CHARS = 200
        private const val NO_TEXT_API =
            "이 폰에서는 PDF 글자를 읽을 수 없습니다.\n(Android 15 이상, 또는 Google Play 시스템 업데이트가 필요합니다)"
        private val PEN_COLORS = intArrayOf(0xFF000000.toInt(), 0xFFD32F2F.toInt(), 0xFF1565C0.toInt())
        private val HIGHLIGHT_COLORS = intArrayOf(0xFFFFF176.toInt(), 0xFFA5D6A7.toInt(), 0xFFF8BBD0.toInt())

        /** Opens library book [bookId] (a PDF) in the viewer. */
        fun open(context: Context, bookId: Long) {
            context.startActivity(
                Intent(context, PdfActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY }
    }
    /** The open document: touched only on [worker]. */
    private var pages: PdfPages? = null
    /** Bumped whenever a document closes; results of an older generation are ignored. */
    private var generation = 0

    private var book: Book? = null
    private var pageCount = 0
    private var current = -1
    /** Page to show once a book has opened (recreation), else the saved position. */
    private var restoredPage = -1

    private class PageBitmap(index: Int, w: Int, h: Int, val areaW: Int, val areaH: Int, bitmap: Bitmap) :
        PdfPageView.PageImage(index, w, h, bitmap)

    private val cache = HashMap<Int, PageBitmap>(8)
    private val inFlight = HashSet<Int>()
    /** A detail bitmap the view no longer draws, reused by the next detail render (handed across threads). */
    private val spareDetail = AtomicReference<Bitmap?>(null)
    private var detailBusy = false
    /** The viewport whose detail render just failed: not retried until zoom / pan move on. */
    private var failedDetail: PdfPageView.Viewport? = null
    /** The page on screen, for the render thread to skip prefetches that are no longer wanted. */
    @Volatile private var wantedPage = -1

    private val tracker = ReadingTracker()
    private val dayClock = DayClock(ZoneId.systemDefault())
    private lateinit var keeper: ScreenOnKeeper

    private lateinit var root: FrameLayout
    private lateinit var pageView: PdfPageView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var pageLabel: TextView
    private lateinit var slider: SeekBar
    private lateinit var message: TextView
    private var chromeShown = false
    /** How the page being fetched for display starts when zoomed (see [goTo]). */
    private var showFromEnd = false
    private var resumed = false

    private val app: AppSettings get() = Settings.app

    /** The open book's annotations (main thread); saved on [worker] in order. */
    private var notes: PdfNotes? = null
    private var canReadText = false
    private lateinit var annotBar: View
    private lateinit var toolRow: android.widget.LinearLayout
    private lateinit var colorRow: android.widget.LinearLayout
    private var bookmarkButton: View? = null

    /** Last search: its query and the pages with matches (rectangles in page points). */
    private var searchQuery = ""
    private var searchHits: List<SearchHit> = emptyList()
    @Volatile private var searchRun = 0

    private class SearchHit(val page: Int, val rects: List<RectF>)

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        Settings.init(this)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        ReaderWindow.setup(this)
        keeper = ScreenOnKeeper(this)
        buildViews()
        val savedBook = savedInstanceState?.getLong(STATE_BOOK, -1L) ?: -1L
        val start = if (savedBook > 0) {
            restoredPage = savedInstanceState?.getInt(STATE_PAGE, -1) ?: -1
            Intent(this, PdfActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, savedBook).also { setIntent(it) }
        } else intent
        startOpen(start)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val b = book ?: return
        outState.putLong(STATE_BOOK, b.id)
        if (current >= 0) outState.putInt(STATE_PAGE, current)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getLongExtra(ReaderActivity.EXTRA_BOOK_ID, -1L)
        if (id <= 0 && intent.data == null) return
        if (id > 0 && id == book?.id) return
        setIntent(intent)
        closeDocument()
        startOpen(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ReaderPresence.inFront = true
        val a = app
        ReaderWindow.applyFullscreen(this, a.fullscreen)
        if (requestedOrientation != a.orientationLock) requestedOrientation = a.orientationLock
        keeper.enabled = a.keepScreenOn
        pageView.swipeEnabled = a.swipeToTurn
        tracker.resume(SystemClock.elapsedRealtime(), dayClock.day(System.currentTimeMillis()))
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        ReaderPresence.inFront = false
        tracker.pause(SystemClock.elapsedRealtime())?.let { writeReading(it) }
        flushPosition()
        flushNotes()
        if (book != null && current >= 0) ResumeState.paused()
    }

    @Deprecated("Activity.onBackPressed: kept for API 26–32 and simple back handling")
    override fun onBackPressed() {
        // Back first leaves the pen tools, then closes the viewer.
        if (::annotBar.isInitialized && annotBar.visibility == View.VISIBLE) {
            stopAnnotating()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun finish() {
        ResumeState.clear()
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        keeper.dispose()
        closeDocument()
        worker.shutdown()
        super.onDestroy()
    }

    // ================================================================== views

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Ink.WHITE) }
        pageView = PdfPageView(this).also {
            it.host = pageHost
            it.onDetailDropped = { b -> keepSpare(b) }
        }
        root.addView(pageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        message = label("불러오는 중…", 16f, color = Ink.GRAY).apply {
            setBackgroundColor(Ink.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(message, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        topBar = toolbar(
            title = "",
            navIcon = R.drawable.ic_arrow_back,
            navLabel = "닫기",
            onNav = { finish() },
            actions = listOf(
                ToolbarAction(R.drawable.ic_search, "찾기") { askSearch() },
                ToolbarAction(R.drawable.ic_bookmark, "책갈피") { toggleBookmark() },
                ToolbarAction(R.drawable.ic_grid_view, "쪽 목록") { showThumbs() },
                ToolbarAction(R.drawable.ic_edit, "필기") { startAnnotating() },
            ),
        ).apply {
            isClickable = true
            visibility = View.GONE
        }
        bookmarkButton = ArrayList<View>().also {
            topBar.findViewsWithText(it, "책갈피", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        }.firstOrNull()
        root.addView(topBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        pageLabel = label("", 15f).apply {
            minWidth = dp(88)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            contentDescription = "쪽 이동"
            setOnClickListener { askPage() }
        }
        slider = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) pageLabel.text = PdfMath.pageLabel(value, pageCount)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}

                override fun onStopTrackingTouch(s: SeekBar) {
                    goTo(s.progress, fromEnd = false)
                }
            })
        }
        bottomBar = vertical {
            setBackgroundColor(Ink.WHITE)
            isClickable = true
            visibility = View.GONE
            addView(hairline())
            addView(horizontal {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(8), 0, dp(8), 0)
                addView(pageLabel)
                addView(slider, lp(0, FrameLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, lp())
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        annotBar = buildAnnotationBar()
        root.addView(annotBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val i = ReaderWindow.insetsOf(insets, app.fullscreen)
            pageView.setPadding(i[0], i[1], i[2], i[3])
            topBar.setPadding(i[0], i[1], i[2], 0)
            bottomBar.setPadding(i[0], 0, i[2], i[3])
            annotBar.setPadding(i[0], 0, i[2], i[3])
            // Padding changes the page area without a layout-bounds change: re-fit like a resize.
            handler.post { onAreaChanged() }
            insets
        }
        // A size change (rotation, window resize) re-fits the page: the fitted bitmaps are rendered for the old size.
        pageView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) handler.post { onAreaChanged() }
        }
        setContentView(root)
    }

    private fun showChrome(show: Boolean) {
        if (chromeShown == show) return
        chromeShown = show
        topBar.visibility = if (show) View.VISIBLE else View.GONE
        bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) updateChrome()
    }

    private fun updateChrome() {
        pageLabel.text = PdfMath.pageLabel(current, pageCount)
        slider.max = (pageCount - 1).coerceAtLeast(0)
        if (current >= 0) slider.progress = current
    }

    private fun showMessage(text: CharSequence?) {
        message.text = text ?: ""
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ================================================================== opening / closing

    private fun startOpen(intent: Intent) {
        message.setOnClickListener(null)
        message.isClickable = false
        showMessage("불러오는 중…")
        val gen = generation
        worker.execute {
            try {
                val b = IntentFiles.resolveBook(this, intent)
                if (b.format != BookFormat.PDF) {
                    handler.post {
                        if (gen != generation || isDestroyed) return@post
                        ReaderActivity.open(this, b.id)
                        finish()
                    }
                    return@execute
                }
                val f = File(b.path)
                if (!f.isFile) throw DocumentException("파일을 찾을 수 없습니다.\n${b.path}")
                val p = PdfPages.open(f)
                pages = p
                val count = p.pageCount
                val text = p.canReadText
                val loaded = PdfNotesStore.load(notesDir(), b.id)
                handler.post {
                    if (gen != generation || isDestroyed) return@post
                    onOpened(b, count, loaded, text)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "open failed", t)
                val text = (t as? DocumentException)?.message ?: "PDF 파일을 열 수 없습니다."
                handler.post {
                    if (gen != generation || isDestroyed) return@post
                    showMessage("$text\n\n(눌러서 닫기)")
                    message.setOnClickListener { finish() }
                }
            }
        }
    }

    private fun onOpened(b: Book, count: Int, loaded: PdfNotes, text: Boolean) {
        book = b
        pageCount = count
        notes = loaded
        canReadText = text
        pageView.notes = loaded
        (topBar.findViewWithTag<TextView>("title"))?.text = b.title
        val start = if (restoredPage >= 0) restoredPage else b.posSection
        restoredPage = -1
        pageView.resetZoom()
        goTo(PdfMath.clampPage(start, count), fromEnd = false)
    }

    /** Saves the position, ends the document's reading time and closes it on the render thread. */
    private fun closeDocument() {
        tracker.flush(SystemClock.elapsedRealtime())?.let { writeReading(it) }
        flushPosition()
        flushNotes()
        stopAnnotating()
        notes = null
        searchRun++
        searchHits = emptyList()
        searchQuery = ""
        generation++
        book = null
        pageCount = 0
        current = -1
        cache.clear()
        inFlight.clear()
        detailBusy = false
        failedDetail = null
        wantedPage = -1
        handler.removeCallbacks(detailRunnable)
        pageView.clear()
        try {
            worker.execute {
                try {
                    pages?.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "close failed", t)
                }
                pages = null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "close not queued", t)
        }
    }

    // ================================================================== paging

    /** Shows page [index] (clamped); [fromEnd] = reached by going back (a zoomed page starts at its bottom). */
    private fun goTo(index: Int, fromEnd: Boolean) {
        if (pageCount <= 0) return
        val target = PdfMath.clampPage(index, pageCount)
        val changed = target != current
        current = target
        wantedPage = target
        showFromEnd = fromEnd
        if (chromeShown) updateChrome()
        val cached = cache[target]
        if (cached != null && fits(cached)) {
            display(cached, fromEnd)
        } else {
            requestBase(target, fromEnd, show = true)
        }
        if (changed) {
            keeper.poke()
            trackPage()
            schedulePositionSave()
        }
        prefetch()
    }

    private fun turn(dir: Int) {
        if (pageCount <= 0 || current < 0) return
        keeper.poke()
        // A slide still running lands on its page first, so this turn starts from there.
        pageView.finishMotion()
        if (pageView.page == current && pageView.step(dir)) return
        val next = current + dir
        if (next < 0) {
            toast("첫 쪽입니다")
            return
        }
        if (next >= pageCount) {
            toast("마지막 쪽입니다")
            return
        }
        if (pageView.page == current && pageView.animateTurn(dir)) return
        goTo(next, fromEnd = dir < 0)
    }

    private fun display(p: PageBitmap, fromEnd: Boolean) {
        showMessage(null)
        pageView.setPage(p.index, p.w, p.h, p.bitmap, fromEnd)
        updateNeighbors()
        showPageMarks()
        if (book != null) ResumeState.opened(book!!.id)
        scheduleDetail()
    }

    /** Hands the view the neighbours' fitted bitmaps that are ready, for its page slide. */
    private fun updateNeighbors() {
        val prev = cache[current - 1]?.takeIf { fits(it) }
        val next = cache[current + 1]?.takeIf { fits(it) }
        pageView.setNeighbors(prev, next)
    }

    private fun fits(p: PageBitmap): Boolean = p.areaW == areaW() && p.areaH == areaH()

    private fun areaW(): Int = (pageView.width - pageView.paddingLeft - pageView.paddingRight).coerceAtLeast(0)
    private fun areaH(): Int = (pageView.height - pageView.paddingTop - pageView.paddingBottom).coerceAtLeast(0)

    private fun onAreaChanged() {
        pageView.refit()
        if (pageCount <= 0 || current < 0) return
        val shown = cache[current]
        if (shown == null || !fits(shown)) {
            cache.clear()
            goTo(current, fromEnd = false)
        }
        // The view dropped its sharp zoomed bitmap with the old page area.
        scheduleDetail()
    }

    private fun prefetch() {
        for (i in intArrayOf(current + 1, current - 1)) {
            if (i < 0 || i >= pageCount) continue
            val c = cache[i]
            if (c == null || !fits(c)) requestBase(i, fromEnd = false, show = false)
        }
    }

    /** Renders page [index] fitted to the page area on the render thread; shows it if it is still the current page. */
    private fun requestBase(index: Int, fromEnd: Boolean, show: Boolean) {
        val aw = areaW()
        val ah = areaH()
        if (aw <= 0 || ah <= 0) {
            // Not laid out yet: the layout listener runs onAreaChanged once it is.
            pageView.post { if (pageCount > 0 && current == index && areaW() > 0) goTo(index, fromEnd) }
            return
        }
        if (!inFlight.add(index)) return
        val gen = generation
        worker.execute {
            val p = pages
            // A queued prefetch the reader has already moved past (fast turns, a slider jump): not rendered.
            val stale = abs(index - wantedPage) > 1
            val result = if (p == null || stale) null else try {
                val w = p.pageWidth(index)
                val h = p.pageHeight(index)
                val size = PdfMath.renderSize(w, h, PdfMath.fitScale(w, h, aw, ah), MAX_BASE_PIXELS)
                val bmp = Bitmap.createBitmap(PdfMath.packedW(size), PdfMath.packedH(size), Bitmap.Config.ARGB_8888)
                p.renderWhole(index, bmp)
                PageBitmap(index, w, h, aw, ah, bmp)
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "page $index: out of memory")
                null
            } catch (t: Throwable) {
                Log.w(TAG, "page $index render failed", t)
                null
            }
            handler.post {
                if (gen != generation || isDestroyed) return@post
                inFlight.remove(index)
                if (stale && index != current) return@post
                if (stale) {
                    requestBase(index, showFromEnd, show = true)
                    return@post
                }
                if (result == null) {
                    if (index == current) showMessage("${index + 1}쪽을 그리지 못했습니다.")
                    return@post
                }
                if (result.areaW != areaW() || result.areaH != areaH()) {
                    if (index == current) goTo(current, showFromEnd)
                    return@post
                }
                cache[index] = result
                trimCache()
                if (index == current && (show || pageView.page != current)) {
                    display(result, showFromEnd)
                } else if (abs(index - current) == 1) {
                    updateNeighbors()
                }
            }
        }
    }

    /** Drops the cached pages farthest from the current one (its neighbours stay for the slide). */
    private fun trimCache() {
        while (cache.size > CACHE_PAGES) {
            var far = -1
            var farDist = 1
            for (k in cache.keys) {
                val d = abs(k - current)
                if (d > farDist) {
                    far = k
                    farDist = d
                }
            }
            if (far < 0) return
            cache.remove(far)
        }
    }

    // ================================================================== zoomed detail

    private val detailRunnable = Runnable { renderDetail() }

    private fun scheduleDetail() {
        handler.removeCallbacks(detailRunnable)
        if (pageView.zoomed) handler.postDelayed(detailRunnable, DETAIL_DELAY_MS)
    }

    private fun keepSpare(b: Bitmap) {
        spareDetail.set(b)
    }

    private fun renderDetail() {
        if (detailBusy) {
            // The running render reschedules when it ends.
            return
        }
        val v = pageView.detailNeeded() ?: return
        val failed = failedDetail
        if (failed != null && PdfPageView.sameViewport(failed, v)) return
        detailBusy = true
        val gen = generation
        worker.execute {
            val p = pages
            var bmp: Bitmap? = null
            if (p != null) {
                try {
                    val spare = spareDetail.getAndSet(null)
                    val b = if (spare != null && !spare.isRecycled && spare.width == v.width && spare.height == v.height) {
                        spare
                    } else {
                        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                    }
                    drawDetail(p, v, b)
                    bmp = b
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "detail: out of memory")
                } catch (t: Throwable) {
                    Log.w(TAG, "detail render failed", t)
                }
            }
            handler.post {
                if (gen != generation || isDestroyed) return@post
                detailBusy = false
                if (bmp != null) {
                    failedDetail = null
                    pageView.setDetail(v, bmp)
                } else {
                    failedDetail = v
                }
                // Zoom / pan moved on while rendering: one more for where it is now.
                if (pageView.detailNeeded() != null) scheduleDetail()
            }
        }
    }

    /** Render thread: the visible part of the page of [v] at full resolution into [b] (transparent off the page). */
    private fun drawDetail(p: PdfPages, v: PdfPageView.Viewport, b: Bitmap) {
        b.eraseColor(Color.TRANSPARENT)
        val w = p.pageWidth(v.page) * v.scale
        val h = p.pageHeight(v.page) * v.scale
        val clip = Rect(
            maxOf(0, Math.ceil(v.left.toDouble()).toInt()),
            maxOf(0, Math.ceil(v.top.toDouble()).toInt()),
            minOf(v.width, Math.floor((v.left + w).toDouble()).toInt()),
            minOf(v.height, Math.floor((v.top + h).toDouble()).toInt()),
        )
        if (clip.isEmpty) return
        Canvas(b).drawRect(clip, Paint().apply { color = Color.WHITE })
        val m = Matrix()
        m.setScale(v.scale, v.scale)
        m.postTranslate(v.left, v.top)
        p.renderPart(v.page, b, clip, m)
    }

    // ================================================================== input (the page view's host)

    private val pageHost = object : PdfPageView.Host {
        override fun onPageTap(x: Float) = pageTap(x)
        override fun onPageSettled(image: PdfPageView.PageImage) = pageSettled(image)
        override fun onViewportChanged() = viewportChanged()
        override fun onInkChanged(page: Int) = scheduleNotesSave()
        override fun onLasso(page: Int, poly: FloatArray) = runLasso(page, poly)
    }

    private fun pageTap(x: Float) {
        keeper.poke()
        if (chromeShown) {
            showChrome(false)
            return
        }
        var zone = PdfMath.tapZone(x, pageView.width)
        if (app.invertTaps) zone = -zone
        if (zone == 0) showChrome(true) else turn(zone)
    }

    private fun pageSettled(image: PdfPageView.PageImage) {
        // The slid-in page: back in the cache if it was trimmed meanwhile, so it shows at once.
        if (image is PageBitmap && fits(image)) cache[image.index] = image
        goTo(image.index, fromEnd = image.index < current)
    }

    private fun viewportChanged() {
        keeper.poke()
        scheduleDetail()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val dir = keyDirection(keyCode)
        if (dir != 0) {
            turn(dir)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyDirection(keyCode) != 0) return true
        return super.onKeyUp(keyCode, event)
    }

    /** +1 next page, -1 previous, 0 = not a page key (the volume keys only when they turn pages in 설정). */
    private fun keyDirection(keyCode: Int): Int {
        val a = app
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (!a.volumeKeysTurn) 0 else if (a.invertVolumeKeys) -1 else 1
            KeyEvent.KEYCODE_VOLUME_UP -> if (!a.volumeKeysTurn) 0 else if (a.invertVolumeKeys) 1 else -1
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE -> 1
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> -1
            else -> when (keyCode) {
                in a.nextPageKeys -> 1
                in a.prevPageKeys -> -1
                else -> 0
            }
        }
    }

    private fun askPage() {
        if (pageCount <= 0) return
        InkNumPad.show(this, "쪽 이동", "1–$pageCount", pageCount.toString().length) { n ->
            if (n in 1..pageCount) {
                pageView.resetZoom()
                goTo(n - 1, fromEnd = false)
                showChrome(false)
                null
            } else {
                "${n}쪽이 없습니다"
            }
        }
    }

    // ================================================================== notes: bookmarks, strokes

    private fun notesDir(): File = File(filesDir, NOTES_DIR)

    private val notesSaveRunnable = Runnable { flushNotes() }

    private fun scheduleNotesSave() {
        handler.removeCallbacks(notesSaveRunnable)
        handler.postDelayed(notesSaveRunnable, NOTES_SAVE_DELAY_MS)
    }

    /** Writes changed notes on the render thread (in order with every other save of this viewer). */
    private fun flushNotes() {
        handler.removeCallbacks(notesSaveRunnable)
        val n = notes ?: return
        val id = book?.id ?: return
        if (!n.dirty) return
        val json = if (n.isEmpty) null else n.toJson()
        n.dirty = false
        val dir = notesDir()
        try {
            worker.execute { if (!PdfNotesStore.save(dir, id, json)) Log.w(TAG, "notes save failed") }
        } catch (t: Throwable) {
            Log.w(TAG, "notes save not queued", t)
        }
    }

    /** The page's bookmark ribbon and search matches. */
    private fun showPageMarks() {
        val marked = notes?.isBookmarked(current) == true
        pageView.bookmarked = marked
        (bookmarkButton as? android.widget.ImageButton)?.setImageResource(
            if (marked) R.drawable.ic_bookmark_fill else R.drawable.ic_bookmark,
        )
        pageView.setSearchMarks(current, searchHits.firstOrNull { it.page == current }?.rects ?: emptyList())
    }

    private fun toggleBookmark() {
        val n = notes ?: return
        if (current < 0) return
        val on = n.toggleBookmark(current)
        showPageMarks()
        toast(if (on) "책갈피를 꽂았습니다" else "책갈피를 뺐습니다")
        scheduleNotesSave()
    }

    private fun showThumbs() {
        val b = book ?: return
        if (pageCount <= 0) return
        showChrome(false)
        val gen = generation
        PdfThumbs(
            activity = this,
            title = b.title,
            pageCount = pageCount,
            current = current,
            bookmarks = { notes?.bookmarks() ?: IntArray(0) },
            requestThumb = { page, w, h, wanted, done -> requestThumb(gen, page, w, h, wanted, done) },
            onPick = { page ->
                pageView.resetZoom()
                goTo(page, fromEnd = false)
            },
        ).show()
    }

    /** A page fitted inside w×h for the page grid, rendered on the render thread when still [wanted]. */
    private fun requestThumb(gen: Int, page: Int, w: Int, h: Int, wanted: () -> Boolean, done: (Bitmap?) -> Unit) {
        try {
            worker.execute {
                val p = pages
                val bmp = if (p == null || !wanted()) null else try {
                    val pw = p.pageWidth(page)
                    val ph = p.pageHeight(page)
                    val size = PdfMath.renderSize(pw, ph, PdfMath.fitScale(pw, ph, w, h), w * h)
                    Bitmap.createBitmap(PdfMath.packedW(size), PdfMath.packedH(size), Bitmap.Config.ARGB_8888)
                        .also { p.renderWhole(page, it) }
                } catch (t: Throwable) {
                    Log.w(TAG, "thumb $page failed", t)
                    null
                }
                handler.post { if (gen == generation && !isDestroyed) done(bmp) else done(null) }
            }
        } catch (t: Throwable) {
            done(null)
        }
    }

    // ================================================================== annotating (pen, highlighter, eraser, 선택)

    private fun buildAnnotationBar(): View {
        toolRow = horizontal { gravity = Gravity.CENTER_VERTICAL }
        colorRow = horizontal { gravity = Gravity.CENTER_VERTICAL }
        val row = horizontal {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(4), 0, dp(4), 0)
            addView(toolRow)
            addView(colorRow)
        }
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        return vertical {
            setBackgroundColor(Ink.WHITE)
            isClickable = true
            visibility = View.GONE
            addView(hairline())
            addView(scroll, lp())
        }
    }

    /** Shows the tools (pen first): one finger draws, two fingers move and zoom the page. */
    private fun startAnnotating() {
        if (notes == null) return
        showChrome(false)
        if (pageView.mode == PdfPageView.MODE_NONE) pageView.mode = PdfPageView.MODE_PEN
        annotBar.visibility = View.VISIBLE
        refreshAnnotationBar()
    }

    private fun stopAnnotating() {
        pageView.mode = PdfPageView.MODE_NONE
        if (::annotBar.isInitialized) annotBar.visibility = View.GONE
        flushNotes()
    }

    private fun refreshAnnotationBar() {
        toolRow.removeAllViews()
        fun tool(text: String, mode: Int) = toolRow.addView(chip(text, pageView.mode == mode) {
            pageView.mode = mode
            refreshAnnotationBar()
        })
        tool("펜", PdfPageView.MODE_PEN)
        tool("형광펜", PdfPageView.MODE_HIGHLIGHTER)
        tool("지우개", PdfPageView.MODE_ERASER)
        tool("선택", PdfPageView.MODE_LASSO)
        toolRow.addView(chip("되돌리기", false) { undoInk() })
        toolRow.addView(chip("◀", false) { turn(-1) })
        toolRow.addView(chip("▶", false) { turn(1) })
        toolRow.addView(chip("완료", false) { stopAnnotating() })
        colorRow.removeAllViews()
        val (colors, chosen) = when (pageView.mode) {
            PdfPageView.MODE_PEN -> PEN_COLORS to pageView.penColor
            PdfPageView.MODE_HIGHLIGHTER -> HIGHLIGHT_COLORS to pageView.highlighterColor
            else -> return
        }
        for (c in colors) colorRow.addView(swatch(c, c == chosen) {
            if (pageView.mode == PdfPageView.MODE_PEN) pageView.penColor = c else pageView.highlighterColor = c
            refreshAnnotationBar()
        })
    }

    /** A text button of the tool bar; [on] = the chosen tool (inverted). */
    private fun chip(text: String, on: Boolean, onClick: () -> Unit): View = label(text, 15f, bold = on).apply {
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(40)
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (on) Ink.WHITE else Ink.BLACK)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (on) Ink.BLACK else Ink.WHITE)
            setStroke(dp(1), Ink.BLACK)
        }
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(40),
        ).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { onClick() }
    }

    /** A colour choice: a filled circle, ringed when chosen. */
    private fun swatch(color: Int, on: Boolean, onClick: () -> Unit): View = View(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(if (on) 3 else 1), if (on) Ink.BLACK else Ink.LINE_LIGHT)
        }
        contentDescription = "색"
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(6), 0, dp(6), 0) }
        setOnClickListener { onClick() }
    }

    private fun undoInk() {
        val n = notes ?: return
        val page = n.undo()
        if (page < 0) {
            toast("되돌릴 것이 없습니다")
            return
        }
        pageView.inkChanged()
        if (page != current) goTo(page, fromEnd = false)
        scheduleNotesSave()
    }

    // ================================================================== selection loop → 사전 · 검색

    /** The text inside a loop drawn on [page] ([poly] in page points), found on the render thread. */
    private fun runLasso(page: Int, poly: FloatArray) {
        if (!canReadText) {
            pageView.clearLasso()
            toast(NO_TEXT_API)
            return
        }
        val gen = generation
        worker.execute {
            val p = pages
            val found = if (p == null) null else try {
                lassoText(p, page, poly)
            } catch (t: Throwable) {
                Log.w(TAG, "selection failed", t)
                null
            }
            handler.post { if (gen == generation && !isDestroyed) showSelection(page, found) }
        }
    }

    /** Render thread: the loop's text — between its first and last characters, else the lines it crosses. */
    private fun lassoText(p: PdfPages, page: Int, poly: FloatArray): PdfText? {
        val n = poly.size / 2
        val runs = p.textRuns(page) ?: return null
        // Each run's rectangles as "lines", remembering which run each came from.
        var count = 0
        for (r in runs) count += r.rects.size
        val lines = FloatArray(count * 4)
        val owner = IntArray(count)
        var k = 0
        runs.forEachIndexed { ri, r ->
            for (rect in r.rects) {
                lines[k * 4] = rect.left
                lines[k * 4 + 1] = rect.top
                lines[k * 4 + 2] = rect.right
                lines[k * 4 + 3] = rect.bottom
                owner[k] = ri
                k++
            }
        }
        val ends = PdfLasso.ends(poly, n, lines, count)
        if (ends != null) {
            p.selectBetween(page, ends[0], ends[1], ends[2], ends[3])?.let { return it }
        }
        if (count == 0) {
            // No run rectangles from this device: select between the loop's corners.
            val b = InkMath.bounds(poly, n)
            return p.selectBetween(page, b[0], b[1], b[2], b[3])
        }
        val crossed = PdfLasso.crossed(poly, n, lines, count)
        if (crossed.isEmpty()) return null
        val text = StringBuilder()
        val rects = ArrayList<RectF>()
        var lastRun = -1
        for (i in crossed) {
            if (owner[i] != lastRun) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(runs[owner[i]].text.trim())
                lastRun = owner[i]
            }
            rects += RectF(lines[i * 4], lines[i * 4 + 1], lines[i * 4 + 2], lines[i * 4 + 3])
        }
        return text.toString().trim().takeIf { it.isNotEmpty() }?.let { PdfText(it, rects) }
    }

    private fun showSelection(page: Int, found: PdfText?) {
        pageView.clearLasso()
        if (found == null || found.text.isBlank()) {
            toast("고른 곳에서 글자를 찾지 못했습니다 (스캔한 PDF에는 글자 정보가 없습니다)")
            return
        }
        val text = found.text
        pageView.setSelectionMarks(page, found.rects)
        val title = if (text.length > SELECTION_TITLE_CHARS) text.take(SELECTION_TITLE_CHARS) + "…" else text
        val items = arrayOf("사전 · 번역", "웹 검색", "복사", "공유", "형광펜 칠하기")
        alert().setTitle(title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> TextActions.lookUp(this, text)
                    1 -> TextActions.webSearch(this, text)
                    2 -> TextActions.copy(this, text)
                    3 -> TextActions.share(this, text)
                    4 -> highlight(page, found.rects)
                }
            }
            .setNegativeButton("닫기", null)
            .setOnDismissListener { pageView.setSelectionMarks(page, emptyList()) }
            .showNoAnim()
    }

    /** Highlighter strokes over the selected text's rectangles (one stroke per rectangle). */
    private fun highlight(page: Int, rects: List<RectF>) {
        val n = notes ?: return
        for (r in rects) {
            if (r.width() <= 0f || r.height() <= 0f) continue
            val cy = (r.top + r.bottom) / 2f
            n.add(page, InkStroke(InkTool.HIGHLIGHTER, pageView.highlighterColor, r.height(), floatArrayOf(r.left, cy, r.right, cy)))
        }
        pageView.inkChanged()
        scheduleNotesSave()
    }

    // ================================================================== search

    private fun askSearch() {
        if (pageCount <= 0) return
        if (!canReadText) {
            toast(NO_TEXT_API)
            return
        }
        if (searchHits.isNotEmpty()) {
            showSearchResults()
            return
        }
        prompt("PDF에서 찾기", searchQuery, "찾을 낱말") { q -> if (q.isNotBlank()) runSearch(q.trim()) }
    }

    /** Searches every page on the render thread with a progress dialog (취소 stops it). */
    private fun runSearch(query: String) {
        val run = ++searchRun
        val gen = generation
        val total = pageCount
        val progress = alert().setTitle("‘$query’ 찾는 중")
            .setMessage("0 / ${total}쪽")
            .setNegativeButton("취소") { _, _ -> searchRun++ }
            .setCancelable(false)
            .showNoAnim()
        worker.execute {
            val hits = ArrayList<SearchHit>()
            val p = pages
            var done = 0
            if (p != null) {
                for (i in 0 until total) {
                    if (searchRun != run) break
                    val found = p.search(i, query)
                    if (found.isNotEmpty()) hits += SearchHit(i, found.flatten())
                    done = i + 1
                    if (done % 10 == 0) {
                        val d = done
                        handler.post { if (searchRun == run) progress.setMessage("$d / ${total}쪽") }
                    }
                }
            }
            val finished = done == total
            handler.post {
                progress.dismiss()
                if (gen != generation || isDestroyed || searchRun != run || !finished) return@post
                searchQuery = query
                searchHits = hits
                showPageMarks()
                if (hits.isEmpty()) {
                    toast("‘$query’을(를) 찾지 못했습니다")
                } else {
                    showSearchResults()
                }
            }
        }
    }

    private fun showSearchResults() {
        val hits = searchHits
        val labels = ArrayList<String>(hits.size + 2)
        labels += "새로 찾기…"
        labels += "찾기 결과 지우기"
        for (h in hits) labels += "${h.page + 1}쪽 · ${h.rects.size}곳"
        val total = hits.sumOf { it.rects.size }
        alert().setTitle("‘$searchQuery’ ${hits.size}쪽 · ${total}곳")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> prompt("PDF에서 찾기", searchQuery, "찾을 낱말") { q -> if (q.isNotBlank()) runSearch(q.trim()) }
                    1 -> {
                        searchHits = emptyList()
                        showPageMarks()
                    }
                    else -> {
                        showChrome(false)
                        goTo(hits[which - 2].page, fromEnd = false)
                    }
                }
            }
            .setNegativeButton("닫기", null)
            .showNoAnim()
    }

    // ================================================================== position & reading time

    private val saveRunnable = Runnable { flushPosition() }

    private fun schedulePositionSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private fun flushPosition() {
        handler.removeCallbacks(saveRunnable)
        val b = book ?: return
        val page = current
        if (page < 0) return
        val progress = PdfMath.progress(page, pageCount)
        val id = b.id
        ReaderIo.launch { Library.savePosition(id, page, 0, progress) }
    }

    private fun trackPage() {
        tracker.onPageShown(SystemClock.elapsedRealtime(), dayClock.day(System.currentTimeMillis()), 0)?.let { writeReading(it) }
    }

    private fun writeReading(d: ReadingDelta) {
        val id = book?.id ?: return
        ReaderIo.launch {
            try {
                ReadingLog.add(id, d.day, d.seconds, d.pages, d.chars)
            } catch (t: Throwable) {
                Log.w(TAG, "reading log write failed", t)
            }
            try {
                Library.addReadingTime(id, d.seconds)
            } catch (t: Throwable) {
                Log.w(TAG, "reading time write failed", t)
            }
        }
    }
}
