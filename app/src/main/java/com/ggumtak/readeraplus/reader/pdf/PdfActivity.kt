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
import com.ggumtak.readeraplus.ui.kit.confirm
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import java.io.File
import java.time.ZoneId
import java.util.concurrent.Callable
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
        private const val PREFS = "pdf_viewer"
        /** Longest selected text shown as the action dialog's title. */
        private const val SELECTION_TITLE_CHARS = 200
        private const val NO_TEXT_API =
            "이 폰에서는 PDF 글자를 읽을 수 없습니다.\n(Android 15 이상, 또는 Google Play 시스템 업데이트가 필요합니다)"

        /** Every notes file read and write, in order, across viewer instances (one closing while another opens). */
        private val notesIo: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pdf-notes").apply { isDaemon = true }
        }

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
    private lateinit var chrome: PdfChrome
    private lateinit var side: PdfSidePanel
    private lateinit var message: TextView
    /** The pen tools are out (the tool bar shows the pens; one finger or the pen draws). */
    private var annotating = false
    private var insets = IntArray(4)
    /** How the page being fetched for display starts when zoomed (see [goTo]). */
    private var showFromEnd = false
    private var resumed = false

    private val app: AppSettings get() = Settings.app

    /** The open book's annotations (main thread); saved on [worker] in order. */
    private var notes: PdfNotes? = null
    private var canReadText = false

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
        applyViewerPrefs()
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
        // Back first closes the side panel, then leaves the pen tools, then closes the viewer.
        if (::side.isInitialized && side.onBack()) return
        if (annotating) {
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
        root = FrameLayout(this).apply { setBackgroundColor(PdfChrome.BAR) }
        pageView = PdfPageView(this).also {
            it.host = pageHost
            it.onDetailDropped = { b -> keepSpare(b) }
        }
        root.addView(pageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        message = label("불러오는 중…", 16f, color = 0xFFBDBDBD.toInt()).apply {
            setBackgroundColor(PdfChrome.BAR)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(message, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        chrome = PdfChrome(this, root, chromeListener)
        side = PdfSidePanel(this, root)

        root.setOnApplyWindowInsetsListener { _, wi ->
            insets = ReaderWindow.insetsOf(wi, app.fullscreen)
            chrome.setInsets(insets[0], insets[1], insets[2], insets[3])
            side.setInsets(insets[0], insets[1], insets[2], insets[3])
            placePage()
            wi
        }
        // A size change (rotation, window resize) re-fits the page: the fitted bitmaps are rendered for the old size.
        pageView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) handler.post { onAreaChanged() }
        }
        setContentView(root)
    }

    /** The page area: under the bars while they show, the whole screen (insets aside) in full-screen reading. */
    private fun placePage() {
        val top = chrome.topSpace
        if (pageView.paddingTop == top && pageView.paddingLeft == insets[0] &&
            pageView.paddingRight == insets[2] && pageView.paddingBottom == insets[3]
        ) return
        pageView.setPadding(insets[0], top, insets[2], insets[3])
        // Padding changes the page area without a layout-bounds change: re-fit like a resize.
        handler.post { onAreaChanged() }
    }

    /** Full-screen reading on / off (a tap in the middle of the page). */
    private fun toggleBars() {
        chrome.setShown(!chrome.isShown)
        placePage()
        updateBadge()
    }

    private val chromeListener = object : PdfChrome.Listener {
        override fun onBack() = finish()
        override fun onPages() = showPages(PdfSidePanel.TAB_PAGES)
        override fun onSearch() = askSearch()
        override fun onBookmark() = toggleBookmark()
        override fun onSettings() = showSettings()
        override fun onAnnotate(on: Boolean) = if (on) startAnnotating() else stopAnnotating()
        override fun onPreset(index: Int, again: Boolean) = choosePreset(index, again)
        override fun onTool(mode: Int) = chooseTool(mode)
        override fun onUndo() = undoInk()
        override fun onBadge() = showPages(PdfSidePanel.TAB_PAGES)
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
                // Through the notes thread: a save still pending from an earlier viewer is on disk before this read.
                val dir = notesDir()
                val loaded = notesIo.submit(Callable { PdfNotesStore.load(dir, b.id) }).get()
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
        chrome.setTitle(b.title)
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
        if (::side.isInitialized) side.hide()
        notes = null
        searchRun++
        searchHits = emptyList()
        searchQuery = ""
        pageCount = 0
        current = -1
        updateBadge()
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
        updateBadge()
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
        var zone = PdfMath.tapZone(x, pageView.width)
        if (app.invertTaps) zone = -zone
        if (zone == 0) toggleBars() else turn(zone)
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

    /**
     * Page keys (keyboard arrows, Page Up / Down, Space, volume, learned keys) turn pages before any view sees them:
     * a focused button, slider or tool bar would otherwise take the arrows for focus moves. Dialogs have their own
     * windows, so typing in 찾기 is not affected. A held key repeats.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The side panel's fields take every key (a space in 찾기 is a space).
        if (::side.isInitialized && side.isShown) return super.dispatchKeyEvent(event)
        val dir = keyDirection(event.keyCode)
        if (dir == 0) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) turn(if (event.isShiftPressed && event.keyCode == KeyEvent.KEYCODE_SPACE) -1 else dir)
        return true
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

    // ================================================================== notes: bookmarks, strokes

    private fun notesDir(): File = File(filesDir, NOTES_DIR)

    private val notesSaveRunnable = Runnable { flushNotes() }

    private fun scheduleNotesSave() {
        handler.removeCallbacks(notesSaveRunnable)
        handler.postDelayed(notesSaveRunnable, NOTES_SAVE_DELAY_MS)
    }

    /** Writes changed notes on the process-wide notes thread, in order with every load and save. */
    private fun flushNotes() {
        handler.removeCallbacks(notesSaveRunnable)
        val n = notes ?: return
        val id = book?.id ?: return
        if (!n.dirty) return
        val json = if (n.isEmpty) null else n.toJson()
        n.dirty = false
        val dir = notesDir()
        try {
            notesIo.execute { if (!PdfNotesStore.save(dir, id, json)) Log.w(TAG, "notes save failed") }
        } catch (t: Throwable) {
            Log.w(TAG, "notes save not queued", t)
        }
    }

    /** The page's bookmark ribbon (on the page and in the top bar) and search matches. */
    private fun showPageMarks() {
        val marked = notes?.isBookmarked(current) == true
        pageView.bookmarked = marked
        chrome.setBookmarked(marked)
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

    /** The page navigator (right side panel) on [tab]: pages, bookmarks or pages with ink. */
    private fun showPages(tab: Int) {
        if (pageCount <= 0 || book == null) return
        val gen = generation
        side.showPages(
            tab = tab,
            pageCount = pageCount,
            current = current,
            bookmarks = { notes?.bookmarks() ?: IntArray(0) },
            inkPages = { notes?.pagesWithInk() ?: IntArray(0) },
            requestThumb = { page, w, h, wanted, done -> requestThumb(gen, page, w, h, wanted, done) },
            onPick = { page ->
                pageView.resetZoom()
                goTo(page, fromEnd = false)
            },
            onClearAllInk = {
                confirm("전체 필기 삭제", "이 책의 필기를 모두 지울까요? 되돌릴 수 없습니다.", "지우기") {
                    val n = notes ?: return@confirm
                    val removed = n.clearAllInk()
                    pageView.inkChanged()
                    scheduleNotesSave()
                    side.refresh()
                    toast(if (removed > 0) "필기 ${removed}개를 지웠습니다" else "지울 필기가 없습니다")
                }
            },
        )
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

    // ================================================================== PDF settings (⚙ in the top bar)

    private val prefs by lazy { PdfPrefs(getSharedPreferences(PREFS, MODE_PRIVATE)) }
    private var presets: MutableList<PenPreset> = ArrayList()
    private var selected = 0

    /** Applies the viewer settings to the page view (start, and after each change). */
    private fun applyViewerPrefs() {
        val p = prefs
        pageView.swipeFingers = p.swipeFingers
        pageView.tone = p.pageTone
        pageView.fingerDraws = p.fingerDraws
        updateBadge()
    }

    /** 보기 설정: a dark bottom sheet (Flexcil-like). */
    private fun showSettings() {
        val p = prefs
        val ctx = this
        val body = vertical()
        body.addView(PdfSheet.section(ctx, "보기", first = true))
        body.addView(PdfSheet.choiceRow(ctx, "문서 색상", listOf("기본", "어둡게", "세피아"), p.pageTone) {
            p.pageTone = it
            pageView.tone = it
        })
        body.addView(PdfSheet.switchRow(ctx, "페이지 번호 표시", "오른쪽 아래 '5 / 120 페이지'", p.pageBadge) {
            p.pageBadge = it
            updateBadge()
        })
        body.addView(PdfSheet.section(ctx, "제스처"))
        body.addView(PdfSheet.choiceRow(ctx, "밀어서 넘기기", listOf("한 손가락", "두 손가락"), p.swipeFingers - 1) {
            p.swipeFingers = it + 1
            pageView.swipeFingers = it + 1
            if (!app.swipeToTurn) toast("앱 설정에서 '밀어서 넘기기'가 꺼져 있습니다")
        })
        body.addView(PdfSheet.switchRow(ctx, "한 손가락 패닝(펜)", "켜면 필기 중에도 손가락으로는 넘기고 움직이며, 펜으로만 씁니다", !p.fingerDraws) {
            p.fingerDraws = !it
            pageView.fingerDraws = !it
        })
        body.addView(PdfSheet.section(ctx, "펜 도구"))
        body.addView(PdfSheet.switchRow(ctx, "펜 입력 감도 사용", "스타일러스를 누르는 힘에 따라 펜 굵기가 변합니다", p.pressure) {
            p.pressure = it
            // Only the pressure of the pen in use changes; the tool (or reading) stays as it is.
            loadPresets()
            val pr = presets[selected]
            pageView.penPressure = pr.tool == InkTool.PEN && pr.pressure && it
        })
        body.addView(PdfSheet.section(ctx, "필기"))
        body.addView(PdfSheet.actionRow(ctx, "이 페이지 필기 지우기", "되돌리기로 되살릴 수 있습니다") {
            val n = notes
            if (n != null && current >= 0 && n.clearPage(current)) {
                pageView.inkChanged()
                scheduleNotesSave()
                toast("이 페이지 필기를 지웠습니다")
            } else {
                toast("이 페이지에는 필기가 없습니다")
            }
        })
        body.addView(PdfSheet.actionRow(ctx, "전체 필기 및 주석 삭제", "이 책의 모든 페이지 (책갈피는 남습니다)", danger = true) {
            confirm("전체 필기 삭제", "이 책의 필기를 모두 지울까요? 되돌릴 수 없습니다.", "지우기") {
                val n = notes ?: return@confirm
                val removed = n.clearAllInk()
                pageView.inkChanged()
                scheduleNotesSave()
                toast(if (removed > 0) "필기 ${removed}개를 지웠습니다" else "지울 필기가 없습니다")
            }
        })
        PdfSheet.show(this, "보기 설정", body)
    }

    /** The "5 / 120 페이지" badge when on (hidden in full-screen reading). */
    private fun updateBadge() {
        if (!::chrome.isInitialized) return
        val show = prefs.pageBadge && pageCount > 0 && current >= 0
        chrome.setBadge(if (show) "${current + 1} / $pageCount 페이지" else null)
    }

    // ================================================================== annotating (pens, eraser, 선택)

    /** The pen tools out (the last pen first): one finger (or the pen) draws, two fingers move and zoom the page. */
    private fun startAnnotating() {
        if (notes == null) return
        loadPresets()
        annotating = true
        if (pageView.mode == PdfPageView.MODE_NONE) applyPreset()
        if (!chrome.isShown) toggleBars()
        refreshTools()
    }

    private fun stopAnnotating() {
        annotating = false
        pageView.mode = PdfPageView.MODE_NONE
        if (::chrome.isInitialized) chrome.setReading()
        flushNotes()
    }

    private fun refreshTools() {
        if (annotating) chrome.setWriting(presets, selected, pageView.mode) else chrome.setReading()
    }

    /** A preset tapped in the tool bar: chosen, or (tapped again) its thickness / colour sheet. */
    private fun choosePreset(i: Int, again: Boolean) {
        if (again) {
            editPreset(i)
            return
        }
        selected = i
        prefs.selectedPreset = i
        applyPreset()
        refreshTools()
    }

    /** 지우개 / 선택 from the tool bar, or 형광펜 from the reading tool bar (the pen tools come out). */
    private fun chooseTool(mode: Int) {
        if (notes == null) return
        loadPresets()
        if (!annotating) {
            annotating = true
            if (!chrome.isShown) toggleBars()
        }
        if (mode == PdfPageView.MODE_HIGHLIGHTER) {
            val hl = presets.indexOfFirst { it.tool == InkTool.HIGHLIGHTER }
            if (hl >= 0) {
                selected = hl
                prefs.selectedPreset = hl
            }
            applyPreset()
        } else {
            pageView.mode = mode
        }
        refreshTools()
    }

    private fun loadPresets() {
        if (presets.isNotEmpty()) return
        presets = prefs.presets.toMutableList()
        selected = prefs.selectedPreset.coerceIn(0, presets.size - 1)
    }

    /** Makes preset [selected] the active tool of the page view. */
    private fun applyPreset() {
        loadPresets()
        val pr = presets[selected]
        if (pr.tool == InkTool.HIGHLIGHTER) {
            pageView.highlighterColor = pr.color
            pageView.highlighterWidth = pr.width
            pageView.mode = PdfPageView.MODE_HIGHLIGHTER
        } else {
            pageView.penColor = pr.color
            pageView.penWidth = pr.width
            pageView.penPressure = pr.pressure && prefs.pressure
            pageView.mode = PdfPageView.MODE_PEN
        }
    }

    /** Thickness, colour and pressure of preset [i] (the chosen tool tapped again). */
    private fun editPreset(i: Int) {
        val pr = presets[i]
        val hl = pr.tool == InkTool.HIGHLIGHTER
        PenPanel(
            activity = this,
            title = if (hl) "형광펜" else "펜",
            highlighter = hl,
            color = pr.color,
            width = pr.width,
            minWidth = if (hl) PenPresets.HIGHLIGHTER_MIN else PenPresets.PEN_MIN,
            maxWidth = if (hl) PenPresets.HIGHLIGHTER_MAX else PenPresets.PEN_MAX,
            pxPerPoint = pageView.pxPerPoint,
            pressure = if (hl) null else pr.pressure,
            recent = prefs.recentColors,
            onChange = { color, width, pressure ->
                presets[i] = presets[i].with(color = color, width = PenPresets.clampWidth(pr.tool, width), pressure = pressure)
                prefs.presets = presets
                if (i == selected) applyPreset()
                refreshTools()
            },
            // The colour chosen in the end joins the recent ones (not every step of a slider drag).
            onClose = {
                val c = presets[i].color
                if (c != pr.color) prefs.recentColors = PenPresets.pushRecent(prefs.recentColors, c)
            },
        ).show()
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
        val ctx = this
        val body = vertical()
        val shown = if (text.length > SELECTION_TITLE_CHARS) text.take(SELECTION_TITLE_CHARS) + "…" else text
        body.addView(label(shown, 16f, color = PdfSheet.TEXT).apply { setPadding(dp(20), dp(8), dp(20), dp(12)) })
        var sheet: android.app.Dialog? = null
        fun act(title: String, block: () -> Unit) = body.addView(PdfSheet.actionRow(ctx, title, null) {
            sheet?.dismiss()
            block()
        })
        act("사전 · 번역") { TextActions.lookUp(this, text) }
        act("웹 검색") { TextActions.webSearch(this, text) }
        act("복사") { TextActions.copy(this, text) }
        act("공유") { TextActions.share(this, text) }
        act("형광펜 칠하기") { highlight(page, found.rects) }
        sheet = PdfSheet.show(this, null, body, onDismiss = { pageView.setSelectionMarks(page, emptyList()) })
    }

    /** Highlighter strokes over the selected text's rectangles (one stroke per rectangle). */
    private fun highlight(page: Int, rects: List<RectF>) {
        val n = notes ?: return
        n.beginGroup()
        for (r in rects) {
            if (r.width() <= 0f || r.height() <= 0f) continue
            val cy = (r.top + r.bottom) / 2f
            n.add(page, InkStroke(InkTool.HIGHLIGHTER, pageView.highlighterColor, r.height(), floatArrayOf(r.left, cy, r.right, cy)))
        }
        n.endGroup()
        pageView.inkChanged()
        scheduleNotesSave()
    }

    // ================================================================== search (right side panel)

    private fun askSearch() {
        if (pageCount <= 0) return
        if (!canReadText) {
            toast(NO_TEXT_API)
            return
        }
        side.showSearch(searchQuery, onSearch = { q -> runSearch(q) }, onPick = { page ->
            pageView.resetZoom()
            goTo(page, fromEnd = false)
        })
        if (searchHits.isNotEmpty()) side.setSearchResults(searchQuery, searchHits.map { it.page to it.rects.size })
    }

    /** Searches every page on the render thread, progress and results in the side panel. */
    private fun runSearch(query: String) {
        val run = ++searchRun
        val gen = generation
        val total = pageCount
        side.setSearchProgress(0, total)
        worker.execute {
            val hits = ArrayList<SearchHit>()
            val p = pages
            var done = 0
            if (p != null) {
                for (i in 0 until total) {
                    if (searchRun != run) break
                    val found = try {
                        p.search(i, query)
                    } catch (t: Throwable) {
                        Log.w(TAG, "search failed on page $i", t)
                        break
                    }
                    if (found.isNotEmpty()) hits += SearchHit(i, found.flatten())
                    done = i + 1
                    if (done % 10 == 0) {
                        val d = done
                        handler.post { if (searchRun == run && side.isShown) side.setSearchProgress(d, total) }
                    }
                }
            }
            val finished = done == total
            handler.post {
                if (gen != generation || isDestroyed || searchRun != run || !finished) return@post
                searchQuery = query
                searchHits = hits
                showPageMarks()
                side.setSearchResults(query, hits.map { it.page to it.rects.size })
            }
        }
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
