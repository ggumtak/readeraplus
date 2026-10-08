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
import com.ggumtak.readeraplus.reader.ReadingDelta
import com.ggumtak.readeraplus.reader.ReadingTracker
import com.ggumtak.readeraplus.reader.ResumeState
import com.ggumtak.readeraplus.reader.ScreenOnKeeper
import com.ggumtak.readeraplus.render.PdfPages
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.InkNumPad
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

/**
 * The PDF viewer: one fixed-layout page at a time from the platform [android.graphics.pdf.PdfRenderer], with
 * pinch / double-tap zoom, panning, tap zones, swipes, page keys, a page slider and 쪽 이동. Opens a library id or
 * an ACTION_VIEW uri (like the text reader, through [IntentFiles]); saves the page as the book's position
 * (section = page index, offset 0), its progress and reading time.
 *
 * Every [PdfPages] call runs on one render thread ([worker]); results come back through [handler] tagged with the
 * document generation, so a late result for a closed document is dropped.
 */
class PdfActivity : Activity(), PdfPageView.Host {
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

    private class PageBitmap(val index: Int, val w: Int, val h: Int, val areaW: Int, val areaH: Int, val bitmap: Bitmap)

    private val cache = LinkedHashMap<Int, PageBitmap>(8, 0.75f, true)
    private val inFlight = HashSet<Int>()
    /** A detail bitmap the view no longer draws, reused by the next detail render (handed across threads). */
    private val spareDetail = AtomicReference<Bitmap?>(null)
    private var detailBusy = false

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
        tracker.resume(SystemClock.elapsedRealtime(), dayClock.day(System.currentTimeMillis()))
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        ReaderPresence.inFront = false
        tracker.pause(SystemClock.elapsedRealtime())?.let { writeReading(it) }
        flushPosition()
        if (book != null && current >= 0) ResumeState.paused()
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
            it.host = this
            it.onDetailDropped = { b -> keepSpare(b) }
        }
        root.addView(pageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        message = label("불러오는 중…", 16f, color = Ink.GRAY).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(message, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        topBar = toolbar(
            title = "",
            navIcon = R.drawable.ic_arrow_back,
            navLabel = "닫기",
            onNav = { finish() },
            actions = listOf(ToolbarAction(R.drawable.ic_find_in_page, "쪽 이동") { askPage() }),
        ).apply {
            isClickable = true
            visibility = View.GONE
        }
        root.addView(topBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        pageLabel = label("", 15f).apply {
            minWidth = dp(88)
            gravity = Gravity.CENTER
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

        root.setOnApplyWindowInsetsListener { _, insets ->
            val i = ReaderWindow.insetsOf(insets, app.fullscreen)
            pageView.setPadding(i[0], i[1], i[2], i[3])
            topBar.setPadding(i[0], i[1], i[2], 0)
            bottomBar.setPadding(i[0], 0, i[2], i[3])
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
                handler.post {
                    if (gen != generation || isDestroyed) return@post
                    onOpened(b, count)
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

    private fun onOpened(b: Book, count: Int) {
        book = b
        pageCount = count
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
        generation++
        book = null
        pageCount = 0
        current = -1
        cache.clear()
        inFlight.clear()
        detailBusy = false
        handler.removeCallbacks(detailRunnable)
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
        goTo(next, fromEnd = dir < 0)
    }

    private fun display(p: PageBitmap, fromEnd: Boolean) {
        showMessage(null)
        pageView.setPage(p.index, p.w, p.h, p.bitmap, fromEnd)
        if (book != null) ResumeState.opened(book!!.id)
        scheduleDetail()
    }

    private fun fits(p: PageBitmap): Boolean = p.areaW == areaW() && p.areaH == areaH()

    private fun areaW(): Int = (pageView.width - pageView.paddingLeft - pageView.paddingRight).coerceAtLeast(0)
    private fun areaH(): Int = (pageView.height - pageView.paddingTop - pageView.paddingBottom).coerceAtLeast(0)

    private fun onAreaChanged() {
        pageView.refit()
        if (pageCount <= 0 || current < 0) return
        val shown = cache[current]
        if (shown != null && fits(shown)) return
        cache.clear()
        goTo(current, fromEnd = false)
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
            val result = if (p == null) null else try {
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
                if (index == current && (show || pageView.page != current)) display(result, showFromEnd)
            }
        }
    }

    private fun trimCache() {
        while (cache.size > CACHE_PAGES) {
            val it = cache.entries.iterator()
            var dropped = false
            while (it.hasNext()) {
                val e = it.next()
                if (e.key != current) {
                    it.remove()
                    dropped = true
                    break
                }
            }
            if (!dropped) return
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
                if (bmp != null) pageView.setDetail(v, bmp)
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

    // ================================================================== input (PdfPageView.Host)

    override fun onPageTap(x: Float) {
        keeper.poke()
        if (chromeShown) {
            showChrome(false)
            return
        }
        var zone = PdfMath.tapZone(x, pageView.width)
        if (app.invertTaps) zone = -zone
        if (zone == 0) showChrome(true) else turn(zone)
    }

    override fun onPageSwipe(dir: Int) {
        if (!app.swipeToTurn) return
        turn(dir)
    }

    override fun onViewportChanged() {
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
