package com.ggumtak.readeraplus.reader

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ggumtak.readeraplus.R
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.Bookmark
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.engine.LineGeometry
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.reader.extras.PageJumpHost
import com.ggumtak.readeraplus.reader.extras.ReaderPanels
import com.ggumtak.readeraplus.reader.extras.SelectionController
import com.ggumtak.readeraplus.reader.extras.TtsController
import com.ggumtak.readeraplus.render.Eink
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.render.PageDecor
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.hairline
import com.ggumtak.readeraplus.ui.kit.horizontal
import com.ggumtak.readeraplus.ui.kit.iconButton
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.pressableBackground
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * The reading screen: opens a book (library id or ACTION_VIEW uri), shows one page at a time on a [PageView],
 * handles taps/swipes/keys, the chrome, the return chip, e-ink refresh cadence, auto page turn, keep-screen-on,
 * brightness, orientation lock and position saving, and hosts the reader extras through [ReaderHost].
 */
class ReaderActivity : Activity(), ReaderHost, PageJumpHost {
    companion object {
        const val EXTRA_BOOK_ID = "book_id"

        fun open(context: Context, bookId: Long) {
            context.startActivity(
                Intent(context, ReaderActivity::class.java)
                    .putExtra(EXTRA_BOOK_ID, bookId)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }

        private const val TAG = "ReaderActivity"
        private const val LOADING_DELAY_MS = 300L
        private const val SAVE_DELAY_MS = 1000L
        private const val COUNT_DELAY_MS = 800L
        private const val MAX_RETURN_STACK = 16
        private const val PREF_BRIGHTNESS_COLLAPSED = "reader.brightnessCollapsed"
        private const val PREF_LAST_BRIGHTNESS = "reader.lastBrightness"
        private const val OWNER_QUOTES = "quotes"
        private const val OWNER_SEARCH = "search"
        private const val REFRESH_SETTLE_MS = 16L
        private const val CHROME_COUNTS_MS = 2000L
        /** Manual page turns after a jump that hide the "돌아가기" chip (the reader has moved on). */
        private const val CHIP_HIDE_TURNS = 2
        /** navigateTo page index: the first page starting at or after the offset (go-to by percent). */
        private const val PAGE_AT_OR_AFTER = -3
        /** Per book: text signature + char fraction of the saved position (see [TextPositions]). */
        private const val PREFS_TEXT_POS = "reader_text_positions"
    }

    private enum class Nav { OPEN, TURN, JUMP, RELAYOUT }

    internal val scope = MainScope()
    internal val handler = Handler(Looper.getMainLooper())
    /**
     * Always the live app settings: the reading-settings popup and the TTS settings save tap zones, volume keys and
     * TTS values straight to [Settings] while this activity stays resumed, and every save here copies from the
     * latest value, so no module's change is ever reverted by a stale snapshot.
     */
    internal val app: AppSettings get() = Settings.app
    /** The app settings last pushed into the window and views ([applyAppSettings]). */
    private var appliedApp: AppSettings? = null
    private var unlistenSettings: (() -> Unit)? = null

    private lateinit var root: FrameLayout
    private lateinit var page: PageView
    internal lateinit var chrome: ReaderChrome
        private set
    private lateinit var chip: LinearLayout
    private lateinit var chipLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var brightnessOverlay: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView

    private lateinit var keeper: ScreenOnKeeper
    private val repeatFilter = RepeatFilter(RepeatFilter.NORMAL_MS)
    private val learnedRepeatFilter = RepeatFilter(RepeatFilter.LEARNED_MS, throttleFreshPresses = true)
    private val cadence = EinkCadence()
    /** Turns that arrived while a layout was pending; applied together when it shows ([flushTurns]). */
    private val backlog = TurnBacklog()
    /** When the last page turn was shown (uptime ms), to hold a due full refresh during fast flipping. */
    private var lastTurnAt = Long.MIN_VALUE / 2
    private var cadenceRefreshPending = false
    private val cadenceRefresh = Runnable {
        cadenceRefreshPending = false
        if (!isDestroyed) refreshAfterDraw(0L)
    }
    /** Manual page turns since the last remembered jump (the return chip hides after [CHIP_HIDE_TURNS]). */
    private var turnsSinceJump = 0

    private var bookRef: Book? = null
    internal var session: BookSession? = null
        private set
    private var openJob: Job? = null
    private var navJob: Job? = null
    private val viewReady = CompletableDeferred<Unit>()

    private var curSection = 0
    private var curPageIdx = 0
    private var curLayout: SectionLayout? = null
    /** Where the reader is (survives relayouts without drifting): a page start or an exact jump target. */
    private var anchor = DocPosition.START
    /** A jump whose layout is still pending; a relayout meanwhile must go there, not back to [anchor]. */
    private var pendingJump: PendingNav? = null

    /** [fraction]: a go-to-percent jump (re-resolved once the target section's real length is known), else NaN. */
    private class PendingNav(val section: Int, val offset: Int, val pageIndex: Int, val fraction: Float)
    private var displayedGenId = -1
    private var lastChapterIdx = Int.MIN_VALUE
    private var insets = IntArray(4)

    private val returnStack = ArrayList<DocPosition>()
    private var bookmarks: List<Bookmark> = emptyList()
    private var quotesBySection: Map<Int, List<Highlight>> = emptyMap()
    private val ownerHighlights = HashMap<String, Pair<Int, List<Highlight>>>()

    private var selection: SelectionController? = null
    private var tts: TtsController? = null
    internal var autoTurnOn = false
        private set
    private var chromeVisible = false
    /** Chrome was shown automatically for "메뉴 고정" once after opening. */
    private var pinShown = false
    /** "메뉴 고정": the page already has its between-the-bars size for the book being opened (bars not shown yet). */
    private var pinPending = false
    /** Background decode of a neighbour section's boundary page images (see [prefetchImages]). */
    private var imagePrefetch: Job? = null
    private val textPosPrefs by lazy { getSharedPreferences(PREFS_TEXT_POS, MODE_PRIVATE) }
    /** Last value written to [textPosPrefs] ("b<id>" to value), to skip identical writes. */
    private var lastTextPos: Pair<String, String>? = null
    private var resumedAt = 0L
    private var batteryLevel = -1
    private var batteryAt = 0L
    private var annotationsLoadedAt = 0L
    private var chromeCountsAt = 0L
    private var edgeToastAt = 0L
    private var seekExact = false
    /** Total pages when an exact (page) seek started: the seek bar's scale. */
    private var seekTotal = 0
    private var seekStartProgress = -1

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        ReaderWindow.setup(this)
        keeper = ScreenOnKeeper(this)
        buildViews()
        applyReaderColors(Settings.reader)
        applyAppSettings()
        unlistenSettings = Settings.addListener(settingsListener)
        startOpen(intent)
    }

    /** No close animation back to the library (the platform default would slide over several e-ink frames). */
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** Settings saved by any module (popup, TTS settings, backup restore; any thread). */
    private val settingsListener: () -> Unit = {
        if (Looper.myLooper() == Looper.getMainLooper()) onAppSettingsSaved() else handler.post { onAppSettingsSaved() }
    }

    /** Re-applies the app settings the window and views cache when one of them changed (input reads [app] live). */
    private fun onAppSettingsSaved() {
        if (isDestroyed) return
        val a = app
        val last = appliedApp
        if (last != null && viewPart(last) == viewPart(a)) return
        applyAppSettings()
        // The e-ink mode chosen in the settings applies to the open page at once (not only on the next resume).
        if (last == null || last.einkMode != a.einkMode) prepareEink()
    }

    private fun viewPart(a: AppSettings): List<Any> = listOf(
        a.fullscreen, a.brightness, a.orientationLock, a.keepScreenOn, a.einkRefreshEvery, a.einkRefreshOnChapter,
        a.swipeToTurn, a.verticalSwipe, a.brightnessSwipe, a.longPressSelect, a.pinChrome, a.einkMode,
    )

    /**
     * The user's vendor e-ink mode for the page view ([AppSettings.einkMode]; the default leaves the device's own
     * per-app setting alone, like any other reader); posted so it runs once the view is attached.
     */
    private fun prepareEink() {
        page.post { if (!isDestroyed) safely { Eink.prepareReaderView(page, app.einkMode) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        if (id <= 0 && intent.data == null) return
        if (id > 0 && id == bookRef?.id && session != null) return
        setIntent(intent)
        closeCurrentBook()
        startOpen(intent)
    }

    override fun onResume() {
        super.onResume()
        applyAppSettings()
        resumedAt = SystemClock.elapsedRealtime()
        // Per-view refresh modes may be reset by the firmware while another app was in front.
        prepareEink()
        val s = session
        if (s != null) {
            val r = Settings.reader
            if (r != s.settings) applySettings(r) else refreshDecor(onlyIfChanged = true)
        }
        keeper.poke()
    }

    override fun onPause() {
        super.onPause()
        stopAutoTurn(showToast = false)
        savePositionNow(persistText = true)
        flushReadingTime()
        keeper.release()
    }

    override fun onDestroy() {
        // Panels first: their dismiss flushes a pending settings change into the still-open session, and cancels the
        // TOC / search work that would otherwise keep converting the closed book's sections.
        safely { ReaderPanels.dismissAll(this) }
        unlistenSettings?.invoke()
        unlistenSettings = null
        handler.removeCallbacksAndMessages(null)
        keeper.dispose()
        safely { tts?.release() }
        tts = null
        safely { selection?.clear() }
        selection = null
        session?.close()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        ReaderWindow.applyFullscreen(this, app.fullscreen)
        // Dialogs of the extras (contents, search) may have changed bookmarks/quotes.
        if (session != null && SystemClock.uptimeMillis() - annotationsLoadedAt > 1000) reloadAnnotations()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The page view's new size arrives through onSizeChanged → relayout.
        root.requestApplyInsets()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) session?.trimMemory()
    }

    // ================================================================== views

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Ink.WHITE) }
        page = PageView(this, pageCallbacks)
        root.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        statusText = label("불러오는 중…", 18f).apply {
            gravity = Gravity.CENTER
            background = borderBox()
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
        }
        root.addView(statusText, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        brightnessOverlay = label("", 20f, bold = true).apply {
            gravity = Gravity.CENTER
            background = borderBox()
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
        }
        root.addView(brightnessOverlay, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        chip = horizontal {
            background = borderBox()
            visibility = View.GONE
            isClickable = true
        }
        chipLabel = label("", 16f, bold = true, maxLines = 1).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            background = pressableBackground()
            setOnClickListener { useReturnChip() }
        }
        chip.addView(chipLabel, lp(WRAP_CONTENT, WRAP_CONTENT))
        chip.addView(hairline(vertical = true))
        chip.addView(iconButton(R.drawable.ic_close, "닫기", sizeDp = 44) { dismissReturnChip() })
        root.addView(chip, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        chrome = ReaderChrome(this, chromeActions)
        chrome.attach(root)
        chrome.setBrightnessCollapsed(Settings.raw().getBoolean(PREF_BRIGHTNESS_COLLAPSED, false))

        errorText = label("", 16f, color = Ink.GRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }
        errorPanel = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ink.WHITE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
            isClickable = true
        }
        errorPanel.addView(label("문서를 열 수 없습니다", 20f, bold = true).apply { gravity = Gravity.CENTER }, lp())
        errorPanel.addView(errorText, lp())
        errorPanel.addView(label("닫기", 18f, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(120)
            minHeight = dp(48)
            setPadding(dp(24), 0, dp(24), 0)
            background = borderBox()
            setOnClickListener { finish() }
        }, lp(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(errorPanel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        root.setOnApplyWindowInsetsListener { _, wi ->
            applyInsets(ReaderWindow.insetsOf(wi, app.fullscreen))
            wi
        }
        // Bar heights change with the title, the brightness row, insets and rotation: keep the pinned page area and
        // the return chip clear of them.
        val barsResized = View.OnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot) handler.post { if (!isDestroyed) onBarsResized() }
        }
        chrome.top.addOnLayoutChangeListener(barsResized)
        chrome.bottom.addOnLayoutChangeListener(barsResized)
        // Bars the extras add over the page (search results, TTS): the return chip moves above them.
        root.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) {
                if (isOwnView(child)) return
                child.addOnLayoutChangeListener(barsResized)
                handler.post { if (!isDestroyed) updateChipPosition() }
            }

            override fun onChildViewRemoved(parent: View, child: View) {
                child.removeOnLayoutChangeListener(barsResized)
                handler.post { if (!isDestroyed) updateChipPosition() }
            }
        })
        setContentView(root)
    }

    private fun isOwnView(v: View): Boolean =
        v === page || v === statusText || v === brightnessOverlay || v === chip || v === errorPanel || chrome.owns(v)

    private fun onBarsResized() {
        applyPinnedArea()
        updateChipPosition()
    }

    /** Left/right margins follow the insets here; top/bottom belong to [applyPinnedArea] (insets or pinned bars). */
    private fun applyInsets(i: IntArray) {
        if (i.contentEquals(insets)) return
        insets = i
        val lp = page.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin != i[0] || lp.rightMargin != i[2]) {
            lp.leftMargin = i[0]
            lp.rightMargin = i[2]
            page.layoutParams = lp
        }
        chrome.setInsets(i[0], i[1], i[2], i[3])
        updateChipPosition()
        applyPinnedArea()
    }

    private fun applyAppSettings() {
        ReaderWindow.applyFullscreen(this, app.fullscreen)
        ReaderWindow.applyBrightness(this, app.brightness)
        if (requestedOrientation != app.orientationLock) requestedOrientation = app.orientationLock
        keeper.enabled = app.keepScreenOn
        cadence.every = app.einkRefreshEvery
        cadence.onChapter = app.einkRefreshOnChapter
        page.swipeToTurn = app.swipeToTurn
        page.verticalSwipe = app.verticalSwipe
        page.brightnessSwipe = app.brightnessSwipe
        page.longPressEnabled = app.longPressSelect
        appliedApp = app
        chrome.setPinned(app.pinChrome)
        applyPinnedArea()
        root.requestApplyInsets()
    }

    private fun applyReaderColors(s: ReaderSettings) {
        val bg = if (s.invert) Ink.BLACK else Ink.WHITE
        root.setBackgroundColor(bg)
        page.blankColor = bg
    }

    private val loadingRunnable = Runnable { statusText.visibility = View.VISIBLE }

    private fun scheduleLoadingText() {
        handler.removeCallbacks(loadingRunnable)
        handler.postDelayed(loadingRunnable, LOADING_DELAY_MS)
    }

    private fun cancelLoadingText() {
        handler.removeCallbacks(loadingRunnable)
        if (statusText.visibility != View.GONE) statusText.visibility = View.GONE
    }

    private fun showError(message: String) {
        cancelLoadingText()
        setChromeVisible(false)
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
    }

    // ================================================================== opening

    private fun startOpen(intent: Intent) {
        openJob?.cancel()
        errorPanel.visibility = View.GONE
        scheduleLoadingText()
        val settings = Settings.reader
        // Load the reading typeface while the document opens (a CJK font asset can take ~100 ms to inflate);
        // the layout thread then finds it in FontManager's cache.
        scope.launch(Dispatchers.Default) {
            try {
                FontManager.typeface(settings.fontId, settings.fontWeight)
            } catch (t: Throwable) {
                Log.w(TAG, "font warm-up failed", t)
            }
        }
        openJob = scope.launch {
            var doc: BookDocument? = null
            var adopted = false
            try {
                val (b, d, storedPos) = withContext(Dispatchers.IO) {
                    val b = IntentFiles.resolveBook(this@ReaderActivity, intent)
                    val f = File(b.path)
                    if (!f.isFile) throw DocumentException("파일을 찾을 수 없습니다.\n${b.path}")
                    val d = Documents.open(f, settings.parseOptions(b.encoding))
                    doc = d
                    Triple(b, d, readTextPosition(b.id))
                }
                if (d.sections.isEmpty()) throw DocumentException("내용이 없는 문서입니다.")
                if (intent.getLongExtra(EXTRA_BOOK_ID, -1L) != b.id && getIntent() === intent) {
                    // A recreated activity reopens by id: a content:// grant may be gone by then.
                    setIntent(Intent(intent).putExtra(EXTRA_BOOK_ID, b.id))
                }
                bookRef = b
                chrome.setTitle(b.title)
                val s = BookSession(this@ReaderActivity, b, d, settings)
                s.listener = sessionListener
                session = s
                adopted = true
                viewReady.await()
                if (app.pinChrome && !pinShown) {
                    // "메뉴 고정": give the page its between-the-bars size before the first layout, so the book is laid
                    // out and drawn once (showing the bars then changes nothing).
                    pinPending = true
                    applyPinnedArea()
                }
                val (vw, vh) = pageTargetSize()
                s.setViewport(vw, vh)
                // Cached page counts load in parallel with the first layout.
                s.startCounting(COUNT_DELAY_MS)
                // A TXT position saved under other parse options (chapter detection, replace rules, encoding, ...)
                // is found again by its char fraction instead of reading stale (section, offset) coordinates.
                val remap = TextPositions.remapFraction(
                    storedPos, LayoutKeys.textSignature(settings, d.format, b.encoding),
                    b.posSection, b.posOffset, b.progress,
                )
                val start = if (remap != null) s.counts.locateFraction(remap) else DocPosition(b.posSection, b.posOffset)
                val sec = start.section.coerceIn(0, s.sectionCount - 1)
                val l = s.layout(sec)
                if (l == null) {
                    if (session === s && !s.isClosed) showError("페이지를 배치하지 못했습니다.")
                    return@launch
                }
                val off = start.offset.coerceIn(0, l.content.length)
                preloadImages(s, l, l.pageForOffset(off))
                if (session !== s) return@launch
                showPage(sec, l, l.pageForOffset(off), Nav.OPEN, anchorOffset = off)
                writeTextPosition(b, s, anchor)
                afterOpen()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "open failed", t)
                showError(describe(t))
            } finally {
                if (!adopted) {
                    try {
                        doc?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is OutOfMemoryError -> "메모리가 부족합니다."
        is DocumentException -> t.message ?: "알 수 없는 오류"
        is SecurityException -> "파일 접근 권한이 없습니다."
        else -> t.message?.let { "$it (${t.javaClass.simpleName})" } ?: t.javaClass.simpleName
    }

    private fun afterOpen() {
        resumedAt = SystemClock.elapsedRealtime()
        if (selection == null) selection = safely { SelectionController(this) }
        // Created up front (cheap: the engine starts on start()) so the selection popup's "여기서 읽기" finds
        // this host's controller, and BACK / volume keys see the same TTS session whoever started it.
        if (tts == null) tts = safely { TtsController(this) }
        reloadAnnotations()
    }

    private fun closeCurrentBook() {
        // Panels first, while the session and book still exist: the settings popup's pending change applies to this
        // book, and no TOC / search / bar is left acting on the next one.
        safely { ReaderPanels.dismissAll(this) }
        savePositionNow(persistText = true)
        flushReadingTime()
        stopAutoTurn(showToast = false)
        safely { tts?.release() }
        tts = null
        safely { selection?.clear() }
        selection = null
        navJob?.cancel()
        openJob?.cancel()
        session?.close()
        session = null
        bookRef = null
        curLayout = null
        curSection = 0
        curPageIdx = 0
        anchor = DocPosition.START
        displayedGenId = -1
        lastChapterIdx = Int.MIN_VALUE
        bookmarks = emptyList()
        quotesBySection = emptyMap()
        ownerHighlights.clear()
        dismissReturnChip()
        backlog.clear()
        pendingJump = null
        handler.removeCallbacks(cadenceRefresh)
        cadenceRefreshPending = false
        imagePrefetch?.cancel()
        imagePrefetch = null
        pinShown = false
        setChromeVisible(false)
        page.frame = null
        page.invalidate()
    }

    private fun reloadAnnotations() {
        val b = bookRef ?: return
        annotationsLoadedAt = SystemClock.uptimeMillis()
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    Library.bookmarks(b.id) to Library.quotes(b.id)
                } catch (t: Throwable) {
                    Log.w(TAG, "annotations load failed", t)
                    null
                }
            } ?: return@launch
            if (bookRef?.id != b.id) return@launch
            // Keep bookmarks added a moment ago that are still being saved.
            val unsaved = bookmarks.filter { it.id < 0 }
            bookmarks = loaded.first + unsaved
            val map = HashMap<Int, List<Highlight>>()
            for ((sec, qs) in loaded.second.groupBy { it.section }) {
                map[sec] = qs.map { Highlight(it.start, it.end, HighlightKind.QUOTE) }
            }
            quotesBySection = map
            refreshDecor(onlyIfChanged = true)
            if (chromeVisible) bindChrome()
        }
    }

    // ================================================================== session callbacks

    private val sessionListener = object : BookSession.Listener {
        override fun onCountsChanged(complete: Boolean) {
            if (curLayout == null) return
            if (complete) {
                refreshDecor(onlyIfChanged = true)
                if (chromeVisible) bindChrome()
                if (chip.visibility == View.VISIBLE) showReturnChip()
                return
            }
            val now = SystemClock.uptimeMillis()
            // While counting, the label's estimate changes often: refresh the chrome sparingly (e-ink).
            if (chromeVisible && !chrome.isSeeking && now - chromeCountsAt > CHROME_COUNTS_MS) {
                chromeCountsAt = now
                bindChrome()
            }
        }

        override fun onSectionStored(section: Int, layout: SectionLayout) {
            // A neighbour prefetched while the reader sits on a section boundary: decode the page it would turn to.
            val s = session ?: return
            val l = curLayout ?: return
            if (s.peek(section) !== layout || layoutStale()) return
            if (section == curSection + 1 && curPageIdx == l.pageCount - 1) {
                prefetchImages(s, layout, 0)
            } else if (section == curSection - 1 && curPageIdx == 0) {
                prefetchImages(s, layout, layout.pageCount - 1)
            }
        }
    }

    private fun onViewSizeChanged(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        viewReady.complete(Unit)
        val s = session ?: return
        if (!s.setViewport(w, h)) return
        relayout()
    }

    // ================================================================== navigation core

    private fun layoutStale(): Boolean = session?.generation?.id != displayedGenId

    /** Shows [pageIndex] of [layout] (which belongs to the current generation). */
    private fun showPage(section: Int, layout: SectionLayout, pageIndex: Int, kind: Nav, anchorOffset: Int = -1) {
        val s = session ?: return
        val gen = s.generation ?: return
        val idx = pageIndex.coerceIn(0, (layout.pageCount - 1).coerceAtLeast(0))
        val renderer = try {
            s.renderer()
        } catch (t: Throwable) {
            Log.w(TAG, "renderer failed", t)
            showError(describe(t))
            return
        }
        val sectionChanged = section != curSection || curLayout == null
        curSection = section
        curLayout = layout
        curPageIdx = idx
        displayedGenId = gen.id
        s.touch(section)
        val p = layout.pages.getOrNull(idx)
        anchor = DocPosition(section, if (anchorOffset >= 0) anchorOffset else p?.start ?: 0)
        cancelLoadingText()
        errorPanel.visibility = View.GONE
        page.frame = PageFrame(
            renderer, layout, idx, gen.geometry.contentLeft.toFloat(), gen.geometry.contentTop.toFloat(),
            buildDecor(),
        )
        page.invalidate()
        safely { selection?.onPageChanged() }
        if (chromeVisible) bindChrome()
        if (app.pinChrome && !pinShown && !chromeVisible) {
            pinShown = true
            setChromeVisible(true)
        }

        val chapterIdx = s.chapters.indexAt(section, p?.start ?: 0)
        val chapterChanged = if (s.chapters.size > 0) chapterIdx != lastChapterIdx else sectionChanged
        lastChapterIdx = chapterIdx
        if (kind == Nav.TURN || kind == Nav.JUMP) onTurnShown(kind, chapterChanged)
        if (kind != Nav.RELAYOUT) schedulePositionSave()
        s.prefetch(section + 1)
        s.prefetch(section - 1)
        // The renderer pre-decodes the neighbouring pages of this section only: at a section boundary, decode the
        // page of the cached neighbour section a turn would show (a pending neighbour does it on arrival).
        if (idx == layout.pageCount - 1) s.peek(section + 1)?.let { prefetchImages(s, it, 0) }
        if (idx == 0) s.peek(section - 1)?.let { prefetchImages(s, it, it.pageCount - 1) }
        keeper.poke()
    }

    /**
     * Shows the page containing [offset] of [section] ([pageIndex] ≥ 0 selects a page directly, -2 = last page).
     * Uses the cached layout when present; otherwise lays the section out (the old page stays visible, and
     * "불러오는 중…" only appears after 300 ms).
     */
    private fun navigateTo(section: Int, offset: Int, pageIndex: Int, kind: Nav, fraction: Float = Float.NaN) {
        val s = session ?: return
        val sec = section.coerceIn(0, s.sectionCount - 1)
        navJob?.cancel()
        navJob = null
        pendingJump = if (kind == Nav.JUMP) PendingNav(sec, offset, pageIndex, fraction) else null
        val cached = if (layoutStale()) null else s.peek(sec)
        if (cached != null) {
            val tp = targetPage(cached, offset, pageIndex)
            if (!needsImageDecode(s, cached, tp)) {
                display(sec, cached, offset, pageIndex, kind, fraction)
                return
            }
            // Cached layout, but its images are not decoded yet: decode them on the IO pool first, so onDraw never
            // decodes on the UI thread (the old page stays up meanwhile).
            scheduleLoadingText()
            navJob = scope.launch {
                preloadImages(s, cached, tp)
                if (session !== s) return@launch
                endNavJob(coroutineContext[Job])
                if (layoutStale() || s.peek(sec) !== cached) {
                    navigateTo(sec, offset, pageIndex, kind, fraction)
                    return@launch
                }
                display(sec, cached, offset, pageIndex, kind, fraction)
            }
            return
        }
        scheduleLoadingText()
        navJob = scope.launch {
            val l = s.layout(sec)
            if (session !== s) return@launch
            if (l == null) {
                endNavJob(coroutineContext[Job])
                layoutFailed(s)
                return@launch
            }
            preloadImages(s, l, targetPage(l, offset, pageIndex))
            if (session !== s) return@launch
            endNavJob(coroutineContext[Job])
            display(sec, l, offset, pageIndex, kind, fraction)
        }
    }

    /**
     * The navigation coroutine [job] is about to show its page: it no longer counts as pending, so turns made from
     * the page it shows (the backlog, TTS) run synchronously instead of being queued behind it.
     */
    private fun endNavJob(job: Job?) {
        if (job != null && navJob === job) navJob = null
    }

    private fun targetPage(l: SectionLayout, offset: Int, pageIndex: Int): Int = when {
        pageIndex == -2 -> l.pageCount - 1
        pageIndex >= 0 -> pageIndex
        pageIndex == PAGE_AT_OR_AFTER -> pageAtOrAfter(l, offset)
        else -> l.pageForOffset(offset.coerceIn(0, l.content.length))
    }

    /** First page of [l] starting at or after [offset] (its last page when none does). */
    private fun pageAtOrAfter(l: SectionLayout, offset: Int): Int {
        val off = offset.coerceIn(0, l.content.length)
        val idx = l.pageForOffset(off)
        val p = l.pages.getOrNull(idx) ?: return idx
        return if (p.start < off && idx + 1 < l.pageCount) idx + 1 else idx
    }

    /** Decodes the images of the page about to be shown on the IO pool so onDraw never decodes them. */
    private suspend fun preloadImages(s: BookSession, l: SectionLayout, pageIndex: Int) {
        if (!needsImageDecode(s, l, pageIndex)) return
        // A boundary prefetch may be decoding this very page: wait for it rather than decode the image twice.
        imagePrefetch?.let { if (it.isActive) it.join() }
        if (!needsImageDecode(s, l, pageIndex)) return
        val r = safely { s.renderer() } ?: return
        withContext(Dispatchers.IO) { runCatching { r.preload(l, pageIndex) } }
    }

    /**
     * True when page [pageIndex] of [l] shows an image not yet decoded at its drawn size (the renderer's own
     * target size: the image line's box rounded to px) and not known to be undecodable.
     */
    private fun needsImageDecode(s: BookSession, l: SectionLayout, pageIndex: Int): Boolean {
        val p = l.pages.getOrNull(pageIndex) ?: return false
        val lines = p.lines
        for (i in lines.indices) {
            val ln = lines[i]
            val img = ln.imageBlock ?: continue
            val w = Math.round(ln.imageWidth).coerceAtLeast(1)
            val h = Math.round(ln.imageHeight).coerceAtLeast(1)
            val known = try {
                s.images.isKnownFailure(img.src, w, h) || s.images.peek(img.src, w, h) != null
            } catch (t: Throwable) {
                true
            }
            if (!known) return true
        }
        return false
    }

    /** Decodes the images of [pageIndex] of [l] in the background (a neighbour section's boundary page). */
    private fun prefetchImages(s: BookSession, l: SectionLayout, pageIndex: Int) {
        if (!needsImageDecode(s, l, pageIndex)) return
        val r = safely { s.renderer() } ?: return
        imagePrefetch = scope.launch(Dispatchers.IO) { runCatching { r.preload(l, pageIndex) } }
    }

    /** A foreground layout returned nothing although the session is still current. */
    private fun layoutFailed(s: BookSession) {
        backlog.clear()
        pendingJump = null
        cancelLoadingText()
        if (s.isClosed) return
        if (curLayout == null || layoutStale()) {
            // Nothing valid on screen to fall back to.
            showError("페이지를 배치하지 못했습니다.")
        } else {
            toast("이 부분을 표시하지 못했습니다")
        }
    }

    private fun display(sec: Int, l: SectionLayout, offset: Int, pageIndex: Int, kind: Nav, fraction: Float = Float.NaN) {
        pendingJump = null
        when {
            pageIndex == -2 -> showPage(sec, l, l.pageCount - 1, kind)
            pageIndex >= 0 -> showPage(sec, l, pageIndex, kind)
            pageIndex == PAGE_AT_OR_AFTER -> {
                if (displayProgressTarget(sec, l, offset, kind, fraction)) return
            }
            else -> {
                val off = offset.coerceIn(0, l.content.length)
                showPage(sec, l, l.pageForOffset(off), kind, anchorOffset = if (kind == Nav.TURN) -1 else off)
            }
        }
        flushTurns()
    }

    /**
     * Shows the go-to-percent target: the first page starting at or after the char position of [fraction], so the
     * footer (by chars until the pages are counted) reads the typed percent. Laying the section out replaced its
     * estimated length with the real one, so the position is found again first. Returns true when it moved on to the
     * next section instead (the target is past this section's last page start); that navigation shows the page.
     */
    private fun displayProgressTarget(sec: Int, l: SectionLayout, offset: Int, kind: Nav, fraction: Float): Boolean {
        val s = session ?: return true
        var off = offset
        if (!fraction.isNaN()) {
            val again = s.counts.locateProgress(fraction)
            if (again.section == sec) off = again.offset
        }
        off = off.coerceIn(0, l.content.length)
        val idx = pageAtOrAfter(l, off)
        val p = l.pages.getOrNull(idx)
        if (p != null && p.start < off && idx == l.pageCount - 1 && sec + 1 < s.sectionCount) {
            navigateTo(sec + 1, 0, 0, kind)
            return true
        }
        showPage(sec, l, idx, kind)
        return false
    }

    private fun relayout() {
        val s = session ?: return
        // Still opening: the pending first layout retries with the new generation by itself.
        if (curLayout == null) {
            s.startCounting(COUNT_DELAY_MS)
            return
        }
        val target = anchor
        // Restart counting here, not after the page shows: a jump may cancel this job before it finishes.
        s.startCounting(COUNT_DELAY_MS)
        val jump = pendingJump
        if (jump != null && navJob?.isActive == true) {
            // A resize / settings change arrived while a jump was still laying out: finish the jump in the new
            // generation instead of snapping back to the page that was showing before it.
            navigateTo(jump.section, jump.offset, jump.pageIndex, Nav.JUMP, jump.fraction)
            return
        }
        navJob?.cancel()
        navJob = scope.launch {
            val sec = target.section.coerceIn(0, s.sectionCount - 1)
            val l = s.layout(sec)
            if (session !== s) return@launch
            if (l == null) {
                layoutFailed(s)
                return@launch
            }
            val off = target.offset.coerceIn(0, l.content.length)
            // New image sizes after a font / size change: decode them here, not in onDraw.
            preloadImages(s, l, l.pageForOffset(off))
            if (session !== s) return@launch
            endNavJob(coroutineContext[Job])
            showPage(sec, l, l.pageForOffset(off), Nav.RELAYOUT, anchorOffset = off)
            flushTurns()
        }
    }

    override fun nextPage(): Boolean = turn(true)

    override fun prevPage(): Boolean = turn(false)

    /**
     * One page forward / back. Inside the section (and into a laid-out neighbour) the page shows synchronously, so
     * turns keep up with the fastest taps. While a layout is pending (a section being laid out, a jump, a relayout)
     * the turn is counted instead and applied together with the others when that layout shows ([flushTurns]): no
     * turn is dropped and none is replayed one draw at a time. False at the first / last page of the book.
     */
    private fun turn(next: Boolean): Boolean {
        val s = session ?: return false
        val l = curLayout ?: return false
        if (navJob?.isActive == true) {
            backlog.add(next)
            return true
        }
        if (layoutStale()) return false
        if (next) {
            if (curPageIdx < l.pageCount - 1) {
                showPage(curSection, l, curPageIdx + 1, Nav.TURN)
                return true
            }
            if (curSection + 1 >= s.sectionCount) return false
            navigateTo(curSection + 1, 0, 0, Nav.TURN)
        } else {
            if (curPageIdx > 0) {
                showPage(curSection, l, curPageIdx - 1, Nav.TURN)
                return true
            }
            if (curSection <= 0) return false
            navigateTo(curSection - 1, 0, -2, Nav.TURN)
        }
        return true
    }

    /**
     * Applies the turns counted while a layout was pending, from the page now shown, in one step: straight to the
     * page they add up to when the sections in between are laid out or counted, else to the next unknown section's
     * boundary with the rest kept for when it shows.
     */
    private fun flushTurns() {
        if (backlog.isEmpty) return
        val s = session
        val l = curLayout
        if (s == null || l == null) {
            backlog.clear()
            return
        }
        if (navJob?.isActive == true || layoutStale()) return
        val n = backlog.take()
        val walk = TurnMath.walk(curSection, curPageIdx, n, s.sectionCount) { sec ->
            when {
                sec == curSection -> l.pageCount
                else -> s.peek(sec)?.pageCount ?: if (s.counts.isKnown(sec)) s.counts.pages(sec) else -1
            }
        }
        // Kept before navigating: a synchronous display of the next section flushes the rest right away.
        backlog.restore(walk.remaining)
        if (walk.hitEdge) edgeToast(n > 0)
        if (walk.section == curSection) {
            if (walk.pageIndex != curPageIdx) showPage(curSection, l, walk.pageIndex, Nav.TURN)
        } else {
            navigateTo(walk.section, 0, walk.pageIndex, Nav.TURN)
        }
    }

    override fun goTo(pos: DocPosition, remember: Boolean) {
        if (session == null) return
        // A "jump" to the page already shown (e.g. the current chapter in the TOC) is not worth a return chip.
        if (remember && curLayout != null && !isOnCurrentPage(pos)) pushReturn(currentPosition())
        jumpTo(pos.section, pos.offset, -1)
    }

    /** An explicit jump (TOC, link, 페이지 이동, seek bar, return chip): turns still pending belong to the page left. */
    private fun jumpTo(section: Int, offset: Int, pageIndex: Int, fraction: Float = Float.NaN) {
        backlog.clear()
        navigateTo(section, offset, pageIndex, Nav.JUMP, fraction)
    }

    // ------------------------------------------------------------------ PageJumpHost (페이지 이동)

    override fun progressFraction(): Float = progress()

    /**
     * Go to [fraction] of the book so that the footer then reads floor(fraction × 100)%: by pages once they are
     * counted (the first page showing that percent), else by characters (the first page starting at or after that
     * char), the same measure [progress] uses.
     */
    override fun goToProgress(fraction: Float) {
        val s = session ?: return
        val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        val c = s.counts
        if (c.isComplete) {
            val (sec, idx) = c.locate(PageProgress.pageFor(f, c.total()))
            goToPage(sec, idx, remember = true)
            return
        }
        val pos = c.locateProgress(f)
        val p = currentPage
        val here = p != null && isOnCurrentPage(pos) && p.start >= pos.offset
        if (curLayout != null && !here) pushReturn(currentPosition())
        jumpTo(pos.section, pos.offset, PAGE_AT_OR_AFTER, f)
    }

    private fun isOnCurrentPage(pos: DocPosition): Boolean {
        val l = curLayout ?: return false
        val p = currentPage ?: return false
        return pos.section == curSection && !layoutStale() && onPage(pos.offset, p, curPageIdx == l.pageCount - 1)
    }

    /** Jump to a page of a section (seek bar, 페이지 이동): one exact draw, laid out first when needed. */
    override fun goToPage(section: Int, pageIndex: Int, remember: Boolean) {
        if (session == null) return
        val here = section == curSection && pageIndex == curPageIdx && !layoutStale()
        if (remember && curLayout != null && !here) pushReturn(currentPosition())
        jumpTo(section, 0, pageIndex.coerceAtLeast(0))
    }

    /** Page turn requested by the user (tap, swipe, key, wheel). */
    private fun userTurn(next: Boolean) {
        if (session == null || curLayout == null) return
        ownerHighlights.remove(OWNER_SEARCH)
        val ok = turn(next)
        if (ok) onManualTurn()
        if (ttsSpeaking()) safely { tts?.onUserNavigated() }
        if (!ok && navJob?.isActive != true && !layoutStale()) edgeToast(next)
    }

    private fun edgeToast(next: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - edgeToastAt > 2000) {
            edgeToastAt = now
            toast(if (next) "마지막 페이지입니다" else "첫 페이지입니다")
        }
    }

    /**
     * Full e-ink refresh cadence for a shown turn / jump. A refresh that falls due while pages are being flipped fast
     * waits (and keeps waiting while the flipping goes on): a flash between two quick turns stalls the panel.
     */
    private fun onTurnShown(kind: Nav, chapterChanged: Boolean) {
        val due = cadence.onTurn(chapterChanged)
        val now = SystemClock.uptimeMillis()
        if (due || cadenceRefreshPending) {
            val delay = if (kind == Nav.TURN) EinkCadence.refreshDelay(now, lastTurnAt) else 0L
            handler.removeCallbacks(cadenceRefresh)
            if (delay > 0L) {
                cadenceRefreshPending = true
                handler.postDelayed(cadenceRefresh, delay)
            } else {
                cadenceRefreshPending = false
                refreshAfterDraw(0L)
            }
        }
        if (kind == Nav.TURN) lastTurnAt = now
    }

    /** A page turn the reader made (not auto turn / TTS): enough of them after a jump retire the return chip. */
    private fun onManualTurn() {
        if (chip.visibility != View.VISIBLE) return
        turnsSinceJump++
        if (turnsSinceJump >= CHIP_HIDE_TURNS) dismissReturnChip()
    }

    private fun jumpChapter(next: Boolean) {
        val s = session ?: return
        val p = currentPage ?: return
        val ch = s.chapters
        if (ch.size == 0) {
            val target = when {
                next -> curSection + 1
                curPageIdx > 0 -> curSection
                else -> curSection - 1
            }
            if (target in 0 until s.sectionCount) goTo(DocPosition(target, 0), remember = false)
            return
        }
        val idx = if (next) ch.nextAfter(curSection, maxOf(p.end - 1, p.start)) else ch.lastBefore(curSection, p.start)
        if (idx >= 0) {
            goTo(ch.position(idx), remember = false)
        } else if (!next && (curSection > 0 || curPageIdx > 0)) {
            goTo(DocPosition.START, remember = false)
        }
    }

    // ================================================================== decor (header, footer, highlights)

    private fun buildDecor(): PageDecor {
        val s = session ?: return PageDecor()
        val l = curLayout ?: return PageDecor()
        val p = l.pages.getOrNull(curPageIdx) ?: return PageDecor()
        val st = s.settings
        val hl = ArrayList<Highlight>()
        quotesBySection[curSection]?.let { addOverlapping(hl, it, p) }
        for ((sec, list) in ownerHighlights.values) if (sec == curSection) addOverlapping(hl, list, p)
        val header = if (st.showHeader) chapterTitle(curSection, p.start) else null
        var left: String? = null
        var right: String? = null
        if (st.showFooter) {
            left = ReaderFormat.footerLeft(
                if (st.footerPage) pageLabelOf(curSection, curPageIdx) else null,
                if (st.footerChapterLeft) chapterPagesLeft(s, l, p) else null,
            )
            right = ReaderFormat.footerRight(
                if (st.footerPercent) ReaderFormat.percent(progress()) else null,
                if (st.footerClock) clock() else null,
                if (st.footerBattery) battery() else null,
            )
        }
        return PageDecor(hl, isBookmarked(l, p), header, left, right)
    }

    private fun addOverlapping(out: ArrayList<Highlight>, list: List<Highlight>, p: PageInfo) {
        for (h in list) if (h.end > h.start && h.end > p.start && h.start < p.end) out += h
    }

    /** Rebuilds the decor of the page on screen (highlights/bookmarks/footer changed) and redraws. */
    private fun refreshDecor(onlyIfChanged: Boolean = false) {
        val f = page.frame ?: return
        if (f.layout !== curLayout || f.pageIndex != curPageIdx) return
        val d = buildDecor()
        if (onlyIfChanged && sameDecor(d, f.decor)) return
        page.frame = PageFrame(f.renderer, f.layout, f.pageIndex, f.left, f.top, d)
        page.invalidate()
    }

    private fun sameDecor(a: PageDecor, b: PageDecor): Boolean {
        if (a.bookmarked != b.bookmarked || a.header != b.header || a.footerLeft != b.footerLeft ||
            a.footerRight != b.footerRight || a.highlights.size != b.highlights.size
        ) return false
        for (i in a.highlights.indices) {
            val x = a.highlights[i]
            val y = b.highlights[i]
            if (x.start != y.start || x.end != y.end || x.kind != y.kind) return false
        }
        return true
    }

    private fun chapterTitle(section: Int, offset: Int): String? {
        val s = session ?: return null
        val i = s.chapters.indexAt(section, offset)
        if (i >= 0) return s.chapters.title(i)
        return s.document.sections.getOrNull(section)?.title ?: bookRef?.title
    }

    private fun isBookmarked(l: SectionLayout, p: PageInfo): Boolean {
        val last = curPageIdx == l.pageCount - 1
        return bookmarks.any { it.section == curSection && onPage(it.offset, p, last) }
    }

    private fun onPage(offset: Int, p: PageInfo, lastPage: Boolean): Boolean =
        offset >= p.start && (offset < p.end || (lastPage && offset == p.end) || p.start == p.end && offset == p.start)

    private fun chapterPagesLeft(s: BookSession, l: SectionLayout, p: PageInfo): Int {
        val c = s.counts
        val next = s.chapters.nextAfter(curSection, p.start)
        if (next < 0) return (c.total() - c.globalPage(curSection, curPageIdx)).coerceAtLeast(0)
        val ns = s.chapters.section(next)
        val no = s.chapters.offset(next)
        val target = if (ns == curSection) l else s.peek(ns)
        val tIdx: Int
        val atStart: Boolean
        if (target != null) {
            tIdx = target.pageForOffset(no)
            atStart = target.pages.getOrNull(tIdx)?.start == no
        } else {
            tIdx = if (no == 0) 0 else c.estimatePageIndex(ns, no)
            atStart = no == 0
        }
        return c.pagesLeftUntil(curSection, curPageIdx, l.pageCount, ns, tIdx, atStart)
    }

    private fun clock(): String {
        val cal = Calendar.getInstance()
        return ReaderFormat.clock(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(this))
    }

    /** Battery percent from the sticky broadcast, read at most once a minute (on page turns only). */
    private fun battery(): Int {
        val now = SystemClock.uptimeMillis()
        if (batteryLevel >= 0 && now - batteryAt < 60_000) return batteryLevel
        batteryAt = now
        batteryLevel = try {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        } catch (t: Throwable) {
            -1
        }
        return batteryLevel
    }

    /** 0..1 reading progress: by pages once counted, else by characters (last page = 1). */
    private fun progress(): Float {
        val s = session ?: return 0f
        val c = s.counts
        val l = curLayout ?: return c.charProgress(anchor.section, anchor.offset)
        if (curSection == s.sectionCount - 1 && curPageIdx == l.pageCount - 1) return 1f
        if (c.isComplete) return PageProgress.of(c.globalPage(curSection, curPageIdx), c.total())
        val p = l.pages.getOrNull(curPageIdx)
        return c.charProgress(curSection, p?.start ?: anchor.offset)
    }

    /** "page / total" of (section, pageIndex): plain numbers, estimated until the counts are complete. */
    private fun pageLabelOf(section: Int, pageIndex: Int): String {
        val c = session?.counts ?: return ""
        return ReaderFormat.pageLabel(c.globalPage(section, pageIndex), c.total())
    }

    /** Global page of a position (estimated for a section that is not laid out). */
    private fun globalPageOf(pos: DocPosition): Int {
        val s = session ?: return 1
        val sec = pos.section.coerceIn(0, s.sectionCount - 1)
        val idx = s.peek(sec)?.pageForOffset(pos.offset) ?: s.counts.estimatePageIndex(sec, pos.offset)
        return s.counts.globalPage(sec, idx)
    }

    // ================================================================== ReaderHost

    override val activity: Activity get() = this
    override val book: Book get() = bookRef ?: throw IllegalStateException("book not loaded")
    override val document: BookDocument? get() = session?.document
    override val currentLayout: SectionLayout? get() = curLayout
    override val currentPageIndex: Int get() = curPageIdx
    override val currentPage: PageInfo? get() = curLayout?.pages?.getOrNull(curPageIdx)
    override val pageView: View get() = page

    override fun currentPosition(): DocPosition = currentPage?.let { DocPosition(curSection, it.start) } ?: anchor

    override fun pageLabel(pos: DocPosition): String {
        val s = session ?: return ""
        return ReaderFormat.pageLabel(globalPageOf(pos), s.counts.total())
    }

    override fun totalPagesKnown(): Boolean = session?.counts?.isComplete == true

    override fun setHighlights(owner: String, section: Int, highlights: List<Highlight>) {
        if (owner == OWNER_QUOTES) {
            val m = HashMap(quotesBySection)
            if (highlights.isEmpty()) m.remove(section) else m[section] = highlights
            quotesBySection = m
        } else if (highlights.isEmpty()) {
            ownerHighlights.remove(owner)
        } else {
            ownerHighlights[owner] = section to highlights
        }
        refreshDecor(onlyIfChanged = true)
    }

    override fun applySettings(settings: ReaderSettings) {
        val s = session
        val old = s?.settings ?: Settings.reader
        Settings.saveReader(settings)
        applyReaderColors(settings)
        val b = bookRef
        if (s == null || b == null) return
        // Only the options this book's parser reads: a TXT option changed while reading an EPUB (or the EPUB
        // publisher styles in a TXT) is saved for the other books but never re-opens this one.
        if (LayoutKeys.parseChanged(old, settings, s.document.format, b.encoding)) {
            reopenDocument(settings)
            return
        }
        when (s.updateSettings(settings)) {
            BookSession.Change.NONE -> {}
            BookSession.Change.REPAINT -> repaint()
            BookSession.Change.RELAYOUT -> relayout()
        }
    }

    /** Same layout, new colours/footer items: redraw with a renderer for the new settings. */
    private fun repaint() {
        val s = session ?: return
        val f = page.frame ?: return
        if (layoutStale() || f.layout !== curLayout) return
        page.frame = PageFrame(s.renderer(), f.layout, f.pageIndex, f.left, f.top, buildDecor())
        page.invalidate()
    }

    /** Parse options changed: open the document again and return to the same place (by ratio if sections moved). */
    private fun reopenDocument(newSettings: ReaderSettings) {
        val old = session ?: return
        val b = bookRef ?: return
        val pos = anchor
        val oldCount = old.sectionCount
        val ratio = old.counts.charProgress(pos.section, pos.offset)
        navJob?.cancel()
        openJob?.cancel()
        scheduleLoadingText()
        openJob = scope.launch {
            var doc: BookDocument? = null
            var fresh: BookSession? = null
            var adopted = false
            try {
                val d = withContext(Dispatchers.IO) {
                    Documents.open(File(b.path), newSettings.parseOptions(b.encoding)).also { doc = it }
                }
                if (d.sections.isEmpty()) throw DocumentException("내용이 없는 문서입니다.")
                val s = BookSession(this@ReaderActivity, b, d, newSettings)
                fresh = s
                s.listener = sessionListener
                val (vw, vh) = pageTargetSize()
                s.setViewport(vw, vh)
                val target = if (s.sectionCount == oldCount) pos else s.counts.locateFraction(ratio)
                val sec = target.section.coerceIn(0, s.sectionCount - 1)
                val l = s.layout(sec)
                if (l != null) preloadImages(s, l, l.pageForOffset(target.offset.coerceIn(0, l.content.length)))
                if (session !== old) return@launch
                if (l == null) {
                    // Keep reading the old parse rather than showing nothing.
                    toast("새 설정으로 문서를 배치하지 못했습니다")
                    return@launch
                }
                session = s
                adopted = true
                old.close()
                safely { tts?.stop() }
                safely { selection?.clear() }
                // The search-results bar holds hits in the old parse's coordinates (the settings popup that started
                // this re-open stays open: only the bar goes).
                safely { ReaderPanels.closeSearchBar(this@ReaderActivity) }
                ownerHighlights.clear()
                dismissReturnChip()
                backlog.clear()
                lastChapterIdx = Int.MIN_VALUE
                val off = target.offset.coerceIn(0, l.content.length)
                // Positions saved from now on are in the new parse's coordinates.
                writeTextPosition(b, s, DocPosition(sec, off))
                val (nw, nh) = pageTargetSize()
                if (s.setViewport(nw, nh)) {
                    // The view was resized while this session was being built (size changes went to the old
                    // one): lay the target out again for the current size instead of showing a stale layout.
                    anchor = DocPosition(sec, off)
                    relayout()
                    return@launch
                }
                showPage(sec, l, l.pageForOffset(off), Nav.JUMP, anchorOffset = off)
                s.startCounting(COUNT_DELAY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "reopen failed", t)
                cancelLoadingText()
                toast("문서를 다시 불러오지 못했습니다: ${describe(t)}")
            } finally {
                if (!adopted) {
                    if (fresh != null) fresh.close() else try {
                        doc?.close()
                    } catch (_: Throwable) {
                    }
                    cancelLoadingText()
                }
            }
        }
    }

    override fun setChromeVisible(visible: Boolean) {
        val v = visible && session != null && curLayout != null
        chromeVisible = v
        pinPending = false
        chrome.setVisible(v)
        chrome.setPinned(app.pinChrome)
        if (v) {
            bindChrome()
            chrome.top.post { updateChipPosition() }
        }
        updateChipPosition()
        applyPinnedArea()
    }

    /** "메뉴 고정" area wanted: the pinned bars are shown, or about to be shown for the book being opened. */
    private fun pinnedArea(): Boolean = app.pinChrome && (chromeVisible || pinPending)

    /**
     * The only owner of the page's top/bottom margins: the system-bar insets, or with "메뉴 고정" (while the bars are
     * shown) the space the bars take, so no text hides under them. A size change re-lays out through
     * onViewSizeChanged, keeping the position; showing / hiding unpinned chrome never changes the page size.
     */
    private fun applyPinnedArea() {
        val lp = page.layoutParams as? FrameLayout.LayoutParams ?: return
        var t = insets[1]
        var b = insets[3]
        if (pinnedArea()) {
            // The bars pad themselves by the insets; max() covers bars that can't be measured yet.
            val h = chromeBarHeights()
            t = maxOf(t, h[0])
            b = maxOf(b, h[1])
        }
        if (lp.topMargin != t || lp.bottomMargin != b) {
            lp.topMargin = t
            lp.bottomMargin = b
            page.layoutParams = lp
        }
    }

    /** Heights of the top / bottom chrome bars; measured now when they are hidden or not laid out yet. */
    private fun chromeBarHeights(): IntArray {
        val top = chrome.top
        val bottom = chrome.bottom
        fun laidOut(v: View) = v.visibility == View.VISIBLE && !v.isLayoutRequested && v.height > 0
        if (laidOut(top) && laidOut(bottom)) return intArrayOf(top.height, bottom.height)
        val w = root.width
        if (w <= 0) return intArrayOf(top.height, bottom.height)
        val ws = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val hs = if (root.height > 0) {
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST)
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
        top.measure(ws, hs)
        bottom.measure(ws, hs)
        return intArrayOf(top.measuredHeight, bottom.measuredHeight)
    }

    /** Size the page view gets at the next layout (its margins may have changed since the last one). */
    private fun pageTargetSize(): Pair<Int, Int> {
        val lp = page.layoutParams as? FrameLayout.LayoutParams
        val w = root.width
        val h = root.height
        if (lp == null || w <= 0 || h <= 0) return page.width to page.height
        val pw = w - root.paddingLeft - root.paddingRight - lp.leftMargin - lp.rightMargin
        val ph = h - root.paddingTop - root.paddingBottom - lp.topMargin - lp.bottomMargin
        return if (pw > 0 && ph > 0) pw to ph else page.width to page.height
    }

    private fun togglePin() {
        val on = !app.pinChrome
        saveApp(app.copy(pinChrome = on))
        chrome.setPinned(on)
        if (on && !chromeVisible) setChromeVisible(true) else applyPinnedArea()
    }

    override fun hitTest(x: Float, y: Float): Int {
        val f = page.frame ?: return -1
        val p = f.layout.pages.getOrNull(f.pageIndex) ?: return -1
        return try {
            LineGeometry.hitTest(f.layout, p, x - f.left, y - f.top)
        } catch (t: Throwable) {
            Log.w(TAG, "hitTest failed", t)
            -1
        }
    }

    override fun textOf(section: Int, start: Int, end: Int): String {
        val s = session ?: return ""
        val content = s.peek(section)?.content ?: try {
            s.document.loadSection(section)
        } catch (t: Throwable) {
            Log.w(TAG, "textOf load failed", t)
            return ""
        }
        val a = start.coerceIn(0, content.length)
        val e = end.coerceIn(a, content.length)
        return content.text.substring(a, e)
    }

    override fun toggleBookmark() {
        val b = bookRef ?: return
        val l = curLayout ?: return
        val p = currentPage ?: return
        val last = curPageIdx == l.pageCount - 1
        val hits = bookmarks.filter { it.section == curSection && onPage(it.offset, p, last) }
        if (hits.isNotEmpty()) {
            val gone = hits.toSet()
            bookmarks = bookmarks.filter { it !in gone }
            ReaderIo.launch { for (h in hits) if (h.id > 0) Library.deleteBookmark(h.id) }
        } else {
            val sec = curSection
            val off = p.start
            val snippet = ReaderFormat.snippet(l.content.text, p.start, p.end)
            val temp = Bookmark(-SystemClock.uptimeMillis(), b.id, sec, off, snippet, System.currentTimeMillis())
            bookmarks = bookmarks + temp
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        Library.addBookmark(b.id, sec, off, snippet)
                    } catch (t: Throwable) {
                        Log.w(TAG, "addBookmark failed", t)
                        null
                    }
                }
                if (saved == null) return@launch
                if (bookmarks.any { it === temp }) {
                    // A reload may already have brought the saved row in: keep one copy.
                    bookmarks = bookmarks.map { if (it === temp) saved else it }.distinctBy { it.id }
                } else {
                    // Removed again before the insert finished.
                    ReaderIo.launch { Library.deleteBookmark(saved.id) }
                }
            }
        }
        refreshDecor(onlyIfChanged = true)
        if (chromeVisible) bindChrome()
    }

    override fun redraw() {
        refreshDecor()
    }

    // ================================================================== touch

    private val pageCallbacks = object : PageView.Callbacks {
        override fun onTouchStarted() {
            keeper.poke()
            if (autoTurnOn) stopAutoTurn(showToast = true)
        }

        override fun isSelectionActive(): Boolean = safely { selection?.isActive } == true

        override fun onSelectionTouch(ev: MotionEvent): Boolean = safely { selection?.onTouchEvent(ev) } ?: false

        override fun onTap(x: Float, y: Float) = handleTap(x, y)

        override fun onSwipe(dir: SwipeDir) {
            if (chromeVisible && !app.pinChrome) setChromeVisible(false)
            userTurn(dir == SwipeDir.NEXT)
        }

        override fun onLongPress(x: Float, y: Float): Boolean {
            if (!app.longPressSelect || curLayout == null) return false
            if (chromeVisible) setChromeVisible(false)
            return safely { selection?.startAt(x, y) } == true
        }

        override fun brightnessStart(): Float =
            if (app.brightness >= 0f) app.brightness else ReaderWindow.systemBrightness(this@ReaderActivity)

        override fun onBrightness(value: Float, done: Boolean) = setBrightness(value, done, overlay = true)

        override fun onViewSizeChanged(w: Int, h: Int) = this@ReaderActivity.onViewSizeChanged(w, h)

        override fun onWheel(next: Boolean) {
            keeper.poke()
            if (safely { selection?.isActive } == true) return
            userTurn(next)
        }
    }

    private fun handleTap(x: Float, y: Float) {
        if (chromeVisible && !app.pinChrome) {
            setChromeVisible(false)
            return
        }
        val l = curLayout ?: return
        if (session == null) return
        val off = hitTest(x, y)
        if (off in 0 until l.content.length) {
            val link = l.content.styleAt(off).link
            if (link != null && fingerOnChar(off, x, y)) {
                followLink(link)
                return
            }
        }
        when (TapZones.corner(x, y, page.width, page.height)) {
            Corner.TOP_RIGHT -> if (app.bookmarkByTouch) {
                toggleBookmark()
                return
            }
            Corner.TOP_LEFT -> if (app.invertByTouch) {
                toggleInvert()
                return
            }
            Corner.NONE -> {}
        }
        runTapAction(TapZones.actionAt(app, x, y, page.width, page.height))
    }

    /** hitTest snaps to the nearest char of a line; links need the finger on (or right next to) the glyph. */
    private fun fingerOnChar(offset: Int, x: Float, y: Float): Boolean {
        val f = page.frame ?: return false
        val p = f.layout.pages.getOrNull(f.pageIndex) ?: return false
        val slop = dp(10).toFloat()
        val cx = x - f.left
        val cy = y - f.top
        return try {
            LineGeometry.rangeRects(f.layout, p, offset, offset + 1).any {
                cx >= it.left - slop && cx <= it.right + slop && cy >= it.top - slop && cy <= it.bottom + slop
            }
        } catch (t: Throwable) {
            false
        }
    }

    internal fun runTapAction(a: TapAction) {
        when (a) {
            TapAction.NONE -> {}
            TapAction.NEXT -> userTurn(true)
            TapAction.PREV -> userTurn(false)
            TapAction.MENU -> setChromeVisible(!chromeVisible)
            TapAction.BOOKMARK -> toggleBookmark()
            TapAction.TOC -> openContents()
            TapAction.SEARCH -> openSearch()
            TapAction.SETTINGS -> openReadingSettings()
            TapAction.TTS -> startTts()
            TapAction.NEXT_CHAPTER -> jumpChapter(true)
            TapAction.PREV_CHAPTER -> jumpChapter(false)
            TapAction.REFRESH -> refreshAfterDraw(0L)
            TapAction.INVERT -> toggleInvert()
        }
    }

    private fun followLink(href: String) {
        val s = session ?: return
        val sec = curSection
        scope.launch {
            val pos = withContext(Dispatchers.IO) {
                try {
                    s.document.resolveLink(sec, href)
                } catch (t: Throwable) {
                    Log.w(TAG, "resolveLink failed", t)
                    null
                }
            }
            if (session !== s) return@launch
            if (pos != null) {
                goTo(pos, remember = true)
            } else if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href)))
                } catch (t: Throwable) {
                    toast("링크를 열 수 없습니다")
                }
            }
        }
    }

    // ================================================================== keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || session == null || errorPanel.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }
        val action = KeyMap.resolve(code, event.isShiftPressed, app)
        if (action == KeyAction.NONE) return super.dispatchKeyEvent(event)
        val learned = code in app.nextPageKeys || code in app.prevPageKeys
        if (chromeVisible && !learned && KeyMap.isFocusKey(code)) return super.dispatchKeyEvent(event)
        // While listening to TTS the volume keys control the speech volume.
        if (!learned && KeyMap.isVolumeKey(code) && ttsSpeaking()) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Learned keys (vendor function / fingerprint keys) bounce: one touch must turn one page.
            val filter = if (learned) learnedRepeatFilter else repeatFilter
            if (!filter.accept(event.repeatCount, event.eventTime)) return true
            keeper.poke()
            when (action) {
                KeyAction.NEXT -> userTurn(true)
                KeyAction.PREV -> userTurn(false)
                KeyAction.MENU -> if (event.repeatCount == 0) setChromeVisible(!chromeVisible)
                KeyAction.NONE -> {}
            }
        }
        // Consume DOWN and UP so the volume panel never shows.
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            safely { selection?.isActive } == true -> safely { selection?.clear() }
            ttsSpeaking() -> safely { tts?.stop() }
            // The search-results bar goes first; the next BACK leaves the book.
            safely { ReaderPanels.closeSearchBar(this) } == true -> {}
            chromeVisible -> setChromeVisible(false)
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ================================================================== chrome

    private fun bindChrome() {
        val s = session ?: return
        val l = curLayout ?: return
        chrome.setTitle(bookRef?.title ?: "")
        val c = s.counts
        val label = pageLabelOf(curSection, curPageIdx)
        if (c.isComplete) {
            chrome.setPage(label, c.total() - 1, c.globalPage(curSection, curPageIdx) - 1)
        } else {
            chrome.setPage(label, 1000, Math.round(progress() * 1000f))
        }
        val p = l.pages.getOrNull(curPageIdx)
        chrome.setBookmarked(p != null && isBookmarked(l, p))
        chrome.setRotationLocked(app.orientationLock != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        chrome.setBrightness(app.brightness, ReaderWindow.systemBrightness(this))
    }

    private val chromeActions = object : ReaderChrome.Actions {
        override fun onBack() = finish()
        override fun onTts() = startTts()
        override fun onSearch() = openSearch()
        override fun onToc() = openContents()
        override fun onSettings(anchor: View) {
            if (session != null) safely { ReaderPanels.showReadingSettings(this@ReaderActivity, anchor) }
        }

        override fun onMore(anchor: View) {
            if (session != null) showOverflowMenu(anchor)
        }

        override fun onBrightnessAuto() {
            if (app.brightness < 0f) {
                setBrightness(Settings.raw().getFloat(PREF_LAST_BRIGHTNESS, 0.5f), done = true, overlay = false)
            } else {
                saveApp(app.copy(brightness = -1f))
                ReaderWindow.applyBrightness(this@ReaderActivity, -1f)
            }
            bindChrome()
        }

        override fun onBrightness(value: Float, done: Boolean) = setBrightness(value, done, overlay = false)

        override fun onBrightnessCollapsed(collapsed: Boolean) {
            Settings.raw().edit().putBoolean(PREF_BRIGHTNESS_COLLAPSED, collapsed).apply()
            chrome.top.post { updateChipPosition() }
        }

        override fun onPageLabel() {
            if (session != null) safely { ReaderPanels.showGoTo(this@ReaderActivity) }
        }

        override fun onRotation() {
            val lock = if (app.orientationLock != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            setOrientationLock(lock)
        }

        override fun onRotationChooser() = showOrientationChooser()

        override fun onBookmark() = toggleBookmark()

        override fun onPin() = togglePin()

        override fun onSeekStart() {
            val c = session?.counts
            seekExact = c?.isComplete == true
            seekTotal = if (seekExact) c?.total() ?: 0 else 0
            seekStartProgress = -1
        }

        override fun onSeekPreview(progress: Int): String {
            val s = session ?: return ""
            if (seekStartProgress < 0) seekStartProgress = progress
            val c = s.counts
            return if (seekExact) {
                val (sec, idx) = c.locate(progress + 1)
                val off = s.peek(sec)?.pages?.getOrNull(idx)?.start
                    ?: (c.charLength(sec).toLong() * idx / c.pages(sec).coerceAtLeast(1)).toInt()
                ReaderFormat.previewLabel(progress + 1, chapterTitle(sec, off))
            } else {
                val pos = c.locateProgress(progress / 1000f)
                ReaderFormat.previewLabel(globalPageOf(pos), chapterTitle(pos.section, pos.offset))
            }
        }

        override fun onSeekDone(progress: Int) {
            val s = session ?: return
            if (progress == seekStartProgress) return
            val c = s.counts
            when {
                seekExact && c.isComplete && c.total() == seekTotal -> {
                    val (sec, idx) = c.locate(progress + 1)
                    goToPage(sec, idx, remember = true)
                }
                // The pages were re-counted while dragging (relayout): keep the dragged fraction.
                seekExact -> goToProgress(PageProgress.of(progress + 1, seekTotal))
                // By ‰ while counting: the same measure as the footer, like 페이지 이동 in percent.
                else -> goToProgress(progress / 1000f)
            }
        }
    }

    private fun setBrightness(value: Float, done: Boolean, overlay: Boolean) {
        val v = value.coerceIn(0f, 1f)
        ReaderWindow.applyBrightness(this, v)
        if (overlay) {
            brightnessOverlay.text = ReaderFormat.brightness(v)
            brightnessOverlay.visibility = if (done) View.GONE else View.VISIBLE
        }
        if (done) {
            Settings.raw().edit().putFloat(PREF_LAST_BRIGHTNESS, v).apply()
            saveApp(app.copy(brightness = v))
            if (chromeVisible) bindChrome()
        }
    }

    internal fun setOrientationLock(lock: Int) {
        saveApp(app.copy(orientationLock = lock))
        requestedOrientation = lock
        if (chromeVisible) bindChrome()
    }

    /** Saves [a] (built from the live [app]); the caller has already applied its own change to the views. */
    internal fun saveApp(a: AppSettings) {
        appliedApp = a
        Settings.saveApp(a)
    }

    internal fun openContents() {
        if (session == null) return
        setChromeVisible(false)
        safely { ReaderPanels.showContents(this) }
    }

    internal fun openSearch() {
        if (session == null) return
        setChromeVisible(false)
        safely { ReaderPanels.showSearch(this) }
    }

    private fun openReadingSettings() {
        if (session == null || curLayout == null) return
        if (!app.pinChrome) {
            // The popup hides unpinned bars and takes the top bar's place: showing them first would only cost two
            // extra e-ink updates.
            safely { ReaderPanels.showReadingSettings(this, chrome.gear) }
            return
        }
        if (!chromeVisible) setChromeVisible(true)
        chrome.gear.post { safely { ReaderPanels.showReadingSettings(this, chrome.gear) } }
    }

    internal fun startTts() {
        if (session == null || curLayout == null) return
        setChromeVisible(false)
        stopAutoTurn(showToast = false)
        val t = tts ?: safely { TtsController(this) }?.also { tts = it } ?: return
        safely { t.start() }
    }

    /** TTS session active (the controller's own × may have stopped it, so ask it rather than remember). */
    private fun ttsSpeaking(): Boolean = tts != null && safely { tts?.isSpeaking } == true

    internal fun toggleInvert() {
        val s = session ?: return
        applySettings(s.settings.copy(invert = !s.settings.invert))
    }

    /** Full refresh of the whole reader window (page, chrome, overlays), not just the page view. */
    internal fun refreshScreen() {
        cadence.reset()
        safely { Eink.fullRefresh(root) }
    }

    /**
     * Full e-ink refresh once the current frame (new page, or a just-dismissed popup) has been drawn:
     * refreshing earlier would flash the old content and leave the new page's ghosting behind.
     */
    internal fun refreshAfterDraw(extraDelayMs: Long) {
        page.invalidate()
        Choreographer.getInstance().postFrameCallback {
            // Runs before this frame's traversal; the posted message runs after it.
            handler.postDelayed({ if (!isDestroyed) refreshScreen() }, REFRESH_SETTLE_MS + extraDelayMs)
        }
    }

    internal fun currentBookOrNull(): Book? = bookRef

    internal fun isCurrentPageBookmarked(): Boolean {
        val l = curLayout ?: return false
        val p = currentPage ?: return false
        return isBookmarked(l, p)
    }

    // ================================================================== return chip

    /** A remembered jump: the chip offers the way back until used, closed, or [CHIP_HIDE_TURNS] manual turns. */
    private fun pushReturn(pos: DocPosition) {
        if (returnStack.lastOrNull() != pos) returnStack.add(pos)
        while (returnStack.size > MAX_RETURN_STACK) returnStack.removeAt(0)
        turnsSinceJump = 0
        showReturnChip()
    }

    private fun showReturnChip() {
        val top = returnStack.lastOrNull()
        if (top == null) {
            chip.visibility = View.GONE
            return
        }
        chipLabel.text = ReaderFormat.returnChip(globalPageOf(top))
        chip.visibility = View.VISIBLE
        updateChipPosition()
    }

    private fun useReturnChip() {
        val pos = returnStack.removeLastOrNull() ?: return
        chip.visibility = View.GONE
        jumpTo(pos.section, pos.offset, -1)
    }

    private fun dismissReturnChip() {
        returnStack.clear()
        turnsSinceJump = 0
        if (::chip.isInitialized) chip.visibility = View.GONE
    }

    private fun updateChipPosition() {
        if (!::chip.isInitialized) return
        val lp = chip.layoutParams as FrameLayout.LayoutParams
        var bottom = if (chromeVisible) {
            (chrome.bottomHeight.takeIf { it > 0 } ?: dp(120)) + dp(8)
        } else {
            insets[3] + dp(6)
        }
        // Above the extras' bottom bars (search results, TTS), which would otherwise cover it and take its taps.
        val bars = overlayBarsHeight()
        if (bars > 0) bottom = maxOf(bottom, bars + dp(6))
        val left = insets[0] + dp(8)
        if (lp.bottomMargin != bottom || lp.leftMargin != left) {
            lp.bottomMargin = bottom
            lp.leftMargin = left
            chip.layoutParams = lp
        }
    }

    /** Height of the full-width bottom bars the extras show over the page (0 when none is shown). */
    private fun overlayBarsHeight(): Int {
        if (!::root.isInitialized) return 0
        var h = 0
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v.visibility != View.VISIBLE || isOwnView(v)) continue
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            if (lp.width != MATCH_PARENT || (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK) != Gravity.BOTTOM) continue
            h = maxOf(h, v.height + lp.bottomMargin)
        }
        return h
    }

    // ================================================================== auto page turn, refresh

    private val autoTurnRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!autoTurnOn) return
            val turned = nextPage()
            if (!turned && navJob?.isActive != true && !layoutStale()) {
                stopAutoTurn(showToast = false)
                toast("마지막 페이지입니다")
                return
            }
            handler.postDelayed(this, autoTurnPeriod())
        }
    }

    private fun autoTurnPeriod(): Long = app.autoTurnSeconds.coerceIn(3, 3600) * 1000L

    internal fun toggleAutoTurn() {
        if (autoTurnOn) stopAutoTurn(showToast = true) else startAutoTurn()
    }

    private fun startAutoTurn() {
        if (session == null) return
        autoTurnOn = true
        handler.removeCallbacks(autoTurnRunnable)
        handler.postDelayed(autoTurnRunnable, autoTurnPeriod())
        setChromeVisible(false)
        toast(ReaderFormat.autoTurnOn(app.autoTurnSeconds.coerceIn(3, 3600)))
    }

    private fun stopAutoTurn(showToast: Boolean) {
        if (!autoTurnOn) return
        autoTurnOn = false
        handler.removeCallbacks(autoTurnRunnable)
        if (showToast) toast("자동 넘김 꺼짐")
    }

    // ================================================================== persistence

    private val saveRunnable = Runnable { savePositionNow() }

    private fun schedulePositionSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    /**
     * Saves the position to the library. [persistText] (pause, close) also records which parse the coordinates
     * belong to (TXT), so a later open under other parse options can find the place again ([TextPositions]).
     */
    private fun savePositionNow(persistText: Boolean = false) {
        handler.removeCallbacks(saveRunnable)
        val b = bookRef ?: return
        val s = session ?: return
        if (curLayout == null) return
        val pos = anchor
        val prog = progress()
        ReaderIo.launch { Library.savePosition(b.id, pos.section, pos.offset, prog) }
        if (persistText) writeTextPosition(b, s, pos)
    }

    /** (signature|fraction) recorded for [bookId], or null (IO thread safe). */
    private fun readTextPosition(bookId: Long): String? = try {
        textPosPrefs.getString("b$bookId", null)
    } catch (t: Throwable) {
        null
    }

    /** Records the parse signature of [s] and the char fraction of [pos] for [b] (TXT only; no identical writes). */
    private fun writeTextPosition(b: Book, s: BookSession, pos: DocPosition) {
        try {
            val sig = LayoutKeys.textSignature(s.settings, s.document.format, b.encoding) ?: return
            val key = "b${b.id}"
            val value = TextPositions.encode(sig, s.counts.charProgress(pos.section, pos.offset))
            if (lastTextPos == key to value) return
            lastTextPos = key to value
            textPosPrefs.edit().putString(key, value).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "text position save failed", t)
        }
    }

    private fun flushReadingTime() {
        val b = bookRef ?: return
        if (resumedAt <= 0L || curLayout == null) return
        val secs = (SystemClock.elapsedRealtime() - resumedAt) / 1000
        resumedAt = SystemClock.elapsedRealtime()
        if (secs > 0) ReaderIo.launch { Library.addReadingTime(b.id, secs) }
    }

    /** Runs a call into another module; a failure there must not take the reader down. */
    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "extras call failed", t)
        null
    }
}
