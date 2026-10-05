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
import android.util.TypedValue
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
import com.ggumtak.readeraplus.data.AutoBackup
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.BookPrefs
import com.ggumtak.readeraplus.data.Bookmark
import com.ggumtak.readeraplus.data.InstallState
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.data.NextPart
import com.ggumtak.readeraplus.data.NotePlace
import com.ggumtak.readeraplus.data.Quote
import com.ggumtak.readeraplus.data.ReaderPresence
import com.ggumtak.readeraplus.data.ReadingLog
import com.ggumtak.readeraplus.data.TxtOverride
import com.ggumtak.readeraplus.engine.LineGeometry
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.reader.extras.BookInsightsHost
import com.ggumtak.readeraplus.reader.extras.Episodes
import com.ggumtak.readeraplus.reader.extras.PageThumbsHost
import com.ggumtak.readeraplus.reader.extras.ThumbBatch
import com.ggumtak.readeraplus.reader.extras.ThumbCell
import com.ggumtak.readeraplus.reader.extras.NoteJumpHost
import com.ggumtak.readeraplus.reader.extras.NotePlaceHost
import com.ggumtak.readeraplus.reader.extras.PageJumpHost
import com.ggumtak.readeraplus.reader.extras.QuoteCache
import com.ggumtak.readeraplus.reader.extras.QuoteRows
import com.ggumtak.readeraplus.reader.extras.ReaderEndHost
import com.ggumtak.readeraplus.reader.extras.ReaderPanels
import com.ggumtak.readeraplus.reader.extras.SelectionController
import com.ggumtak.readeraplus.reader.extras.StatusSampleHost
import com.ggumtak.readeraplus.reader.extras.TtsController
import com.ggumtak.readeraplus.reader.extras.TxtOverrideHost
import com.ggumtak.readeraplus.render.Covers
import com.ggumtak.readeraplus.render.DeviceClass
import com.ggumtak.readeraplus.render.Eink
import com.ggumtak.readeraplus.render.FontFiles
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.HighlightKind
import com.ggumtak.readeraplus.render.ImageCoverage
import com.ggumtak.readeraplus.render.PageDecor
import com.ggumtak.readeraplus.render.PagePalette
import com.ggumtak.readeraplus.render.PageRenderer
import com.ggumtak.readeraplus.render.ProgressMath
import com.ggumtak.readeraplus.render.QuoteLook
import com.ggumtak.readeraplus.render.StatusFit
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.KeyHold
import com.ggumtak.readeraplus.settings.ReadMode
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.Settings
import com.ggumtak.readeraplus.settings.StatusItem
import com.ggumtak.readeraplus.settings.TapAction
import com.ggumtak.readeraplus.ui.library.LibraryActivity
import com.ggumtak.readeraplus.ui.settings.OpenBook
import com.ggumtak.readeraplus.ui.settings.SettingsActivity
import com.ggumtak.readeraplus.ui.kit.Ink
import com.ggumtak.readeraplus.ui.kit.borderBox
import com.ggumtak.readeraplus.ui.kit.chooser
import com.ggumtak.readeraplus.ui.kit.dp
import com.ggumtak.readeraplus.ui.kit.dpF
import com.ggumtak.readeraplus.ui.kit.label
import com.ggumtak.readeraplus.ui.kit.lp
import com.ggumtak.readeraplus.ui.kit.toast
import com.ggumtak.readeraplus.ui.kit.vertical
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

/**
 * The reading screen: opens a book (library id or ACTION_VIEW uri), shows one page at a time on a [PageView],
 * handles taps/swipes/keys, the chrome, the return point ([ReturnNav]), e-ink refresh cadence, auto page turn, keep-screen-on,
 * brightness ([LightController]), the status slots ([StatusModel]), orientation lock, position saving, reading time ([ReadingTracker]) and the end panel ([EndPanel]), and
 * hosts the reader extras through [ReaderHost] and its optional capabilities.
 */
class ReaderActivity : Activity(), ReaderHost, PageJumpHost, BookInsightsHost, TxtOverrideHost, ReaderEndHost,
    StatusSampleHost, NotePlaceHost, QuotePlaceHost, NoteJumpHost, PageThumbsHost {
    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val STATE_BOOK = "rp.book"
        private const val STATE_SECTION = "rp.section"
        private const val STATE_OFFSET = "rp.offset"
        private const val STATE_AT = "rp.at"
        /** N §6.2 / PLAN C8: a recreated reader that was peeking at a note keeps not saving until a turn. */
        private const val STATE_PEEK = "rp.peek"

        fun open(context: Context, bookId: Long, jump: ReaderJump? = null) {
            context.startActivity(
                Intent(context, ReaderActivity::class.java)
                    .putExtra(EXTRA_BOOK_ID, bookId)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION).also { jump?.put(it) },
            )
        }

        private const val TAG = "ReaderActivity"
        private const val LOADING_DELAY_MS = 300L
        /** Long-press selection needs the finger on a glyph's box (within this slop), not in the leading around it. */
        private const val GLYPH_SLOP_DP = 4f
        private const val SAVE_DELAY_MS = 1000L
        private const val COUNT_DELAY_MS = 800L
        private const val OWNER_QUOTES = "quotes"
        private const val OWNER_SEARCH = "search"
        /** N §6.1: the note an open / a new intent jumped to, marked until the first manual turn. */
        private const val OWNER_JUMP = "jump"
        /** N §6.5: note places backfilled per main-thread message (interleaves with input). */
        private const val BACKFILL_CHUNK = 64
        /** N §6.5: at most this many places are backfilled per open. */
        private const val BACKFILL_MAX = 500
        private const val REFRESH_SETTLE_MS = 16L
        private const val CHROME_COUNTS_MS = 2000L
        /** navigateTo page index: the first page starting at or after the offset (go-to by percent). */
        private const val PAGE_AT_OR_AFTER = -3
        /** Per book: text signature + char fraction of the saved position (see [TextPositions]). */
        private const val PREFS_TEXT_POS = "reader_text_positions"
        /** The footer's episode numbers are parsed this long after the first page (off the open path). */
        private const val EPISODES_DELAY_MS = 800L
        /** A refresh due when a panel closed waits until the panel has left the screen. */
        private const val PANEL_GONE_MS = 120L
        /** Pages of the "10쪽씩" key hold (T1-4). */
        private const val HOLD_PAGES = 10
        private const val MIN_LONG_PRESS_MS = 200
        private const val MAX_LONG_PRESS_MS = 2000
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
    private lateinit var light: LightController
    private lateinit var returnNav: ReturnNav
    internal lateinit var chrome: ReaderChrome
        private set
    private lateinit var statusText: TextView
    private lateinit var brightnessOverlay: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var errorDetailText: TextView
    private lateinit var errorEncoding: TextView
    /** The TXT book whose file could not be read or parsed: [인코딩 바꾸기] reopens it with another encoding. */
    private var failedBook: Book? = null

    private lateinit var keeper: ScreenOnKeeper
    /** Assigned vendor / remote keys bounce: one touch must turn one page (fresh presses included). */
    private val learnedRepeatFilter = RepeatFilter(RepeatFilter.LEARNED_MS, throttleFreshPresses = true)
    /** The page key held down, and where the reader was when it went down (the hold action's anchor, T1-4). */
    private val heldKey = HeldKey()
    private var holdSection = 0
    private var holdPageIdx = 0
    private var holdStart = 0
    private var holdEnd = 0
    /** The key-down's own turn happened (a "10쪽씩" hold adds the other nine). */
    private var holdTurned = false
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
    private var bookRef: Book? = null
    internal var session: BookSession? = null
        private set
    /** This book's own TXT options (T1-9; read in the open path's IO block), or null. */
    private var bookOverride: TxtOverride? = null
    /** A new encoding from 설정 is being saved and the activity re-created ([takeSettingsEdits]): no re-parse till then. */
    private var recreatePending = false
    /**
     * The effective settings the session has, or is being re-opened with: `Settings.reader.withTxt(bookOverride)` as
     * last applied. onResume and the settings listener compare against it, so a change is applied once (no relayout
     * loop, no second re-parse while one is running).
     */
    private var readerTarget: ReaderSettings? = null
    /** A re-parse ([reopenDocument]) is running; [reopenDone] run once it shows its page. */
    private var reopening = false
    private val reopenDone = ArrayList<() -> Unit>()
    /** The book being opened / re-parsed, for the delayed loading text (set on the IO thread). */
    @Volatile private var openingPath: String? = null
    @Volatile private var openingBytes = 0L
    private var openJob: Job? = null
    private var navJob: Job? = null
    private val viewReady = CompletableDeferred<Unit>()
    /**
     * [startOpen] published its session and sizes the first viewport itself (C16: a note opens with natural
     * pagination): a first measure meanwhile must not build an anchored generation first.
     */
    private var openOwnsViewport = false
    /** PLAN §1.6.2: [afterOpen] is still due for the book being opened (once per open, whichever session shows it). */
    private var afterOpenPending = false
    /** Runs [afterOpen] after the first successful body draw ([PageView.afterFirstFrame]). */
    private val afterFirstPage = Runnable {
        val s = session
        val b = bookRef
        if (isDestroyed || !afterOpenPending || s == null || b == null) return@Runnable
        afterOpenPending = false
        writeTextPosition(b, s, anchor)
        afterOpen()
    }

    private var curSection = 0
    private var curPageIdx = 0
    private var curLayout: SectionLayout? = null
    /** Where the reader is (survives relayouts without drifting): a page start or an exact jump target. */
    private var anchor = DocPosition.START
    /** A jump whose layout is still pending; a relayout meanwhile must go there, not back to [anchor]. */
    private var pendingJump: PendingNav? = null
    /** S §1.10: the scroll viewport while [AppSettings.readMode] is SCROLL and a page is shown; null in paged mode. */
    private var scroll: ScrollReader? = null
    /** [AppSettings.readMode] is SCROLL as last applied ([applyReadMode]); the next [showPage] attaches / detaches. */
    private var scrollWanted = false
    /** [switchMode] is showing the same place in the other mode (scroll settle kind SWITCH). */
    private var switchingMode = false
    /** A new generation was announced to [scroll]; its settles are not bookkept until the next showAt. */
    private var scrollFrozen = false
    /** Top page (section shl 32 | page) at the last scroll settle, to track a page only when it changed. */
    private var scrollSettledTop = -1L
    /** The scroll decor last handed to [scroll] (refreshDecor(onlyIfChanged) compares against it). */
    private var scrollDecor: PageDecor? = null
    /** A drag started (onScrollStart) and its settle is still due. */
    private var scrollGesture = false
    /** STEP: the chrome and the search highlight go with the release frame (one e-ink update per gesture). */
    private var scrollCloseAtSettle = false
    /** [flushScrollTurns] is stepping: its settles only keep the position; one bookkeeping pass follows the loop. */
    private var scrollFlushing = false
    /** A step of the running flush settled, and the pixels its steps moved. */
    private var flushSettled = false
    private var flushMovedPx = 0f
    /** A long press focused the section under the finger for the selection ([vpage] returns it to the anchor). */
    private var scrollFocusHeld = false
    /** Whole screens of drag / fling for [onManualTurn] (one per screen). */
    private val scrollScreens = ScreenCounter()
    /** Foreground layout of the section a scroll stopped at ([onScrollBlocked]). */
    private var stripJob: Job? = null
    private var stripSection = -1
    private var scrollEmGen = -1
    private var scrollEmPx = 0f

    /** [fraction]: a go-to-percent jump (re-resolved once the target section's real length is known), else NaN. */
    private class PendingNav(val section: Int, val offset: Int, val pageIndex: Int, val fraction: Float)
    private var displayedGenId = -1
    private var lastChapterIdx = Int.MIN_VALUE
    /** [ReaderWindow.insetsOf]: left, top, right, bottom and the cutout-only part of top. */
    private var insets = IntArray(ReaderWindow.INSETS)
    /** The cutout band at the page view's top ([applyPageInsets]); the session's text box starts below it. */
    override var pageCutoutTop = 0
        private set

    private var bookmarks: List<Bookmark> = emptyList()
    /** Ids of bookmarks the user removed while their insert was still running ([toggleBookmark]). */
    private val removedTemps = HashSet<Long>()
    /** Per section (for [quoteCheckSession]): the quotes that hold on this text, as page highlights ([quotesFor]). */
    private val quotesBySection = HashMap<Int, List<Highlight>>()
    private var thumbs: PageThumbs? = null
    private var thumbPaintVersion = 0
    private var thumbDecorVersions = IntArray(0)
    private fun bumpThumbDecor(section: Int) {
        if (section in thumbDecorVersions.indices) thumbDecorVersions[section]++
    }
    override val thumbnailsShown: Boolean get() = !scrollMode
    override fun thumbTotal(): Int = if (curLayout == null || page.frame == null || scrollMode) 0 else session?.counts?.total() ?: 0
    override fun thumbCurrent(): Int = session?.counts?.globalPage(curSection, curPageIdx) ?: 0
    override fun thumbAspect(): Float {
        val g = session?.generation?.geometry ?: return 0f
        // The page below the camera band (PageThumbs draws that part): no empty strip of paper on top.
        return if (g.viewWidth > 0) (g.viewHeight - g.cutoutTop).toFloat() / g.viewWidth else 0f
    }
    override fun requestThumbs(first: Int, count: Int, widthPx: Int, heightPx: Int, progressive: Boolean, onBatch: (ThumbBatch) -> Unit) {
        val s = session ?: return
        if (thumbTotal() <= 0) return
        if (thumbDecorVersions.size != s.sectionCount) thumbDecorVersions = IntArray(s.sectionCount)
        val t = thumbs ?: PageThumbs(this, s, thumbSource, DeviceClass.cached(this) == true).also { thumbs = it }
        t.request(first, count, widthPx, heightPx, progressive, onBatch)
    }
    override fun cancelThumbs() { thumbs?.cancel() }
    private val thumbSource = object : PageThumbs.Source {
        override fun decorFor(section: Int, layout: SectionLayout, pageIndex: Int): PageThumbs.Decor {
            val p = layout.pages.getOrNull(pageIndex) ?: return PageThumbs.Decor.NONE
            val hl = ArrayList<Highlight>()
            addOverlapping(hl, quotesFor(section, layout.content.text), p)
            var marks = 0
            if (bookmarks.any { it.section == section && onPage(it.offset, p, pageIndex == layout.pageCount - 1) }) marks = marks or ThumbCell.MARK_BOOKMARK
            if (hl.isNotEmpty()) marks = marks or ThumbCell.MARK_QUOTE
            if (quoteRows[section]?.any { it.note.isNotBlank() && it.end > p.start && it.start < p.end && !quoteMoved(it) } == true)
                marks = marks or ThumbCell.MARK_NOTE
            return PageThumbs.Decor(hl, marks)
        }
        override fun paintVersion(): Int = thumbPaintVersion
        override fun decorVersion(section: Int): Int = thumbDecorVersions.getOrElse(section) { 0 }
        override fun currentPage(): Int = thumbCurrent()
        override fun currentSection(): Int = if (session != null) curSection else -1
    }

    private val ownerHighlights = HashMap<String, Pair<Int, List<Highlight>>>()

    private var selection: SelectionController? = null
    private var tts: TtsController? = null
    internal var autoTurnOn = false
        private set
    private var chromeVisible = false
    /** Background decode of a neighbour section's boundary page images (see [prefetchImages]). */
    private var imagePrefetch: Job? = null
    private val textPosPrefs by lazy { getSharedPreferences(PREFS_TEXT_POS, MODE_PRIVATE) }
    /** Last value written to [textPosPrefs] ("b<id>" to value), to skip identical writes. */
    private var lastTextPos: Pair<String, String>? = null
    private var batteryLevel = -1
    private var batteryAt = 0L
    /** The sticky battery broadcast's filter, built once (the battery is read at most once a minute). */
    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    /** The status slots of the page on screen: one shared [StatusModel.decor], filled from [statusInputs]. */
    private val status = StatusModel()
    private val statusInputs = StatusInputs()
    /** The clock's time zone and 12/24 h format, read on the first clock sample after [onResume] (null = re-read). */
    private var clockZone: TimeZone? = null
    private var clock24 = true
    private var annotationsLoadedAt = 0L
    private var chromeCountsAt = 0L
    private var edgeToastAt = 0L
    private var seekExact = false
    /** Total pages when an exact (page) seek started: the seek bar's scale. */
    private var seekTotal = 0
    private var seekStartProgress = -1
    /** The brightness overlay's text follows a drag at most 4 times a second (the window follows every event). */
    private val brightnessThrottle = Throttle(Throttle.LABEL_MS)
    /** Brightness whose overlay text was held back by [brightnessThrottle] (NaN = none). */
    private var brightnessHeld = Float.NaN
    private val showHeldBrightness = Runnable {
        val v = brightnessHeld
        brightnessHeld = Float.NaN
        if (!v.isNaN() && brightnessOverlay.visibility == View.VISIBLE) {
            brightnessThrottle.mark(SystemClock.uptimeMillis())
            brightnessOverlay.text = ReaderFormat.brightness(v)
        }
    }
    /** When [startOpen] began (uptime ms), for the "open … first page N ms" log. */
    private var openStartedAt = 0L
    /** Event time of the last key press (uptime ms): where a key turn's "turn N ms" starts. */
    private var keyInputAt = 0L
    /** Input event time of a user turn whose page is not shown yet (0 = none; only with [ReaderPerf.turns]). */
    private var perfTurnFrom = 0L
    private var perfRequestAt = 0L
    private val insetsGate = InsetsGate()
    private var focusSince = 0L
    private var insetsFullscreen: Boolean? = null
    private var configChanged = false
    private val settleInsets = Runnable {
        val wi = if (isDestroyed) null else root.rootWindowInsets
        if (wi != null) insetsGate.settle(ReaderWindow.insetsOf(wi, app.fullscreen))?.let(::applyInsets)
    }
    /** Real reading time, pages and characters (T1-6), flushed to ReadingLog on pause, close and day change. */
    private val tracker = ReadingTracker()
    private val dayClock = DayClock()
    /** Reading speed for 남은 시간 (T1-7): ReadingLog.cpm of this book, loaded after the first page and after each flush. */
    @Volatile private var cpm = ReadingLog.DEFAULT_CPM
    /**
     * The footer's 회차 (T1-5), from BookSession.episodes: per TOC entry the episode number to show (its own, else the
     * last one before it; -1 before the first), null until parsed. [epNumbered]: the titles carry numbers.
     */
    private var epShown: IntArray? = null
    private var epNumbered = false
    private var epMax = 0
    private var episodesAsked = false
    /** [askEpisodes] is posted (not re-posted on every turn while it waits). */
    private var episodesPending = false
    private val askEpisodes = Runnable {
        episodesPending = false
        if (!isDestroyed) loadEpisodes()
    }
    /** Built the first time a book ends in this reader, not on the way to the first page. */
    private var endPanel: EndPanel? = null
    /** The end panel's data is being loaded (it shows when it arrives). */
    private var endLoading = false
    /** The window lost the focus to a panel (TOC, search, the settings popup, a menu) while a book was shown. */
    private var panelOpen = false
    /** Between onResume and onPause. */
    private var inFront = false
    /** Page colours last set by [applyReaderColors] (null = none yet). */
    private var readerPalette: PagePalette? = null
    /** The device class as [updateQuoteLook] last got it (null = not known yet): the chrome's look follows it. */
    private var einkClass: Boolean? = null
    /** The page on screen when the reader paused (-1 = none): TTS may turn pages with the screen off (T1-11). */
    private var pausedSection = -1
    private var pausedPageIdx = -1
    /** TTS (the only caller of [nextPage] / [prevPage] / [goTo] while paused) moved the page since the pause. */
    private var turnedInBackground = false
    /** Exact saved place of a recreated reader; consumed by its first successful open. */
    private var restoredPlace: ReaderRestore.Place? = null
    /** N §6.2: the book shows a note's page without moving the reading position (no position writes). */
    private val peek = PeekRule()
    private val peekUntilTurn: Boolean get() = peek.active
    /** [startOpen] is showing the first page of an open at a note (scroll: CONTEXT placement for a mid-line target). */
    private var openedAtNote = false
    /** The note jump consumed by the open, for [afterOpen]'s anchor check (N §6.4); null when none is due. */
    private var openedJump: OpenedJump? = null
    /** N §6.4: the running anchor search; cancelled by a manual turn, a user jump or closing. */
    private var anchorJob: Job? = null
    /** The session [sigNote] / [sigPlain] were computed for (they follow re-parses, which make a new session). */
    private var sigSession: BookSession? = null
    /** [NoteSig] of the session's text: what a quote's / jump's `sig` is compared with. */
    private var sigNote: String? = null
    /** The plain [LayoutKeys.textSignature] (null for EPUB). */
    private var sigPlain: String? = null
    /** The book's quotes by section as loaded, before the K2 check ([quotesFor]). */
    private var quoteRows: Map<Int, List<Quote>> = emptyMap()
    /** Quote edits sent to [setHighlights]: a [reloadAnnotations] read that started before the latest one is stale. */
    private var quoteEdits = 0
    /** Per quote id: whether its place moved, as the K2 check found it (the TOC's "· 위치 바뀜"). */
    private val quoteMovedById = HashMap<Long, Boolean>()
    private var quoteCheckSession: BookSession? = null
    /** N §6.5: the book whose note places are being / were backfilled in this open (once per open). */
    private var backfillBook = -1L

    /** The jump an open consumed: the request, where it resolved to, and whether the stored coordinates held. */
    private class OpenedJump(val jump: ReaderJump, val target: DocPosition, val exact: Boolean, val matched: Boolean)

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        // S §1.10: the library's auto-scan and auto-backup yield from the very start of the open, not from onResume.
        ReaderPresence.inFront = true
        super.onCreate(savedInstanceState)
        // A book opened from a file manager starts the process: its splash (plain white, see values-v31) goes at once,
        // without the platform's exit animation.
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        Settings.init(this)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        ReaderWindow.setup(this)
        keeper = ScreenOnKeeper(this)
        buildViews()
        // The window's brightness override only (no IO): the device light waits for the first page.
        light.onCreate()
        applyReaderColors(Settings.reader)
        applyAppSettings()
        unlistenSettings = Settings.addListener(settingsListener)
        val place = ReaderRestore.Place.from(
            savedInstanceState?.getLong(STATE_BOOK, -1L) ?: -1L,
            savedInstanceState?.getInt(STATE_SECTION, -1) ?: -1,
            savedInstanceState?.getInt(STATE_OFFSET, 0) ?: 0,
            savedInstanceState?.getLong(STATE_AT, 0L) ?: 0L,
        )
        val start = if (place != null) {
            restoredPlace = place
            peek.restore(savedInstanceState?.getBoolean(STATE_PEEK, false) == true)
            // Android keeps the original launch intent: a by-id intent avoids an expired VIEW grant or old jump.
            Intent(this, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, place.bookId).also { setIntent(it) }
        } else intent
        startOpen(start)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val b = bookRef ?: return
        outState.putLong(STATE_BOOK, b.id)
        if (curLayout != null) {
            outState.putInt(STATE_SECTION, anchor.section)
            outState.putInt(STATE_OFFSET, anchor.offset)
            outState.putLong(STATE_AT, System.currentTimeMillis())
        }
        outState.putBoolean(STATE_PEEK, peekUntilTurn)
    }

    /** finish() ran: the reader was closed by the user (or by the app for them), not by an outside launch. */
    private var userFinished = false

    /** No close animation back to the library (the platform default would slide over several e-ink frames). */
    override fun finish() {
        userFinished = true
        ResumeState.clear()
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * Settings saved by any module (popup, TTS settings, backup restore; any thread). The reading settings are
     * compared a message later: a save made by [applySettings] has been applied by then and compares equal.
     */
    private val settingsListener: () -> Unit = {
        if (Looper.myLooper() == Looper.getMainLooper()) onAppSettingsSaved() else handler.post { onAppSettingsSaved() }
        handler.removeCallbacks(syncReaderSettings)
        handler.post(syncReaderSettings)
    }

    /**
     * Applies the saved reading settings when they differ from what the open book has or is getting; only in front
     * (the popup, TTS settings): changes made in 설정 apply once, in [onResume], not as a relayout / re-parse each.
     */
    private val syncReaderSettings = Runnable {
        if (!isDestroyed && inFront && session != null && Settings.reader.withTxt(bookOverride) != readerTarget) {
            applyToSession(Settings.reader)
        }
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
        a.swipeToTurn, a.verticalSwipe, a.brightnessSwipe, a.longPressSelect, a.einkMode,
        a.longPressMs, a.einkRefreshEveryNight, a.einkRefreshMethod, a.einkFlashMs, a.brightnessDevice, a.readMode,
        a.scrollStyle, a.highlightLook,
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
        if (id > 0 && id == bookRef?.id && session != null) {
            // N §6.3: the book is already being read; a note's jump is a normal remembered jump (no reopen, no peek).
            ReaderJump.from(intent)?.let(::jumpToNote)
            return
        }
        setIntent(intent)
        closeCurrentBook()
        startOpen(intent)
    }

    /** S §3.2: a pending auto backup waits while the reader is visible again. */
    override fun onStart() {
        super.onStart()
        AutoBackup.cancelScheduled()
    }

    /** S §3.2: the daily auto backup may run a moment after the reader left the screen (never on a rotation). */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AutoBackup.schedule(applicationContext, 5_000) { ReaderPresence.inFront }
    }

    /**
     * N §6.3: [jump] for the open book: `goTo(remember = true)`, then the mark and the anchor check once the target's
     * layout is there ([checkJumpAnchor]).
     */
    private fun jumpToNote(jump: ReaderJump) {
        val s = session ?: return
        val target = ReaderJump.resolve(jump, noteSig(s), s.sectionCount) { f -> s.counts.locateFraction(f) }
        if (target == null) {
            toast("노트가 있던 곳을 찾지 못했습니다")
            return
        }
        clearJumpMark()
        val exact = target == DocPosition(jump.section, jump.offset)
        if (!exact) {
            goTo(target, remember = true)
            checkJumpAnchor(s, OpenedJump(jump, target, exact = false, matched = false))
            return
        }
        // The mark goes in before the jump, so the page and the mark are one update (C23): the target section is laid
        // out first when it is not cached (the old page stays; the jump then shows from the cache).
        anchorJob?.cancel()
        anchorJob = scope.launch {
            val sec = target.section.coerceIn(0, s.sectionCount - 1)
            val l = s.peek(sec) ?: s.layout(sec)
            if (session !== s) return@launch
            val matched = l != null &&
                JumpAnchor.matches(l.content.text, target.offset.coerceIn(0, l.content.length), jump.anchor)
            if (matched) putJumpMark(sec, target.offset.coerceIn(0, l!!.content.length), jump, l.content.length)
            anchorJob = null // goTo's user jump cancels a pending search: not this job
            goTo(target, remember = true)
            checkJumpAnchor(s, OpenedJump(jump, target, exact = true, matched = matched))
        }
    }

    override fun onResume() {
        super.onResume()
        // The library's periodic auto-scan yields while a book is in front.
        ReaderPresence.inFront = true
        inFront = true
        applyAppSettings()
        light.onResume()
        // The clock's zone / 12-24 h and the battery may have changed while away: sampled again by the refresh below
        // (it rides on the resume redraw, so no extra e-ink update) or by the next page shown.
        clockZone = null
        batteryLevel = -1
        tracker.resume(SystemClock.elapsedRealtime(), dayClock.day(System.currentTimeMillis()))
        safely { tts?.onReaderResumed() }
        // Per-view refresh modes may be reset by the firmware while another app was in front.
        prepareEink()
        if (session != null) {
            // 설정 → 이 책의 TXT 정리: new TXT options join the compare below (one re-parse); a new encoding re-opens.
            val reopening = takeSettingsEdits()
            // The book's effective settings, as the open path built them (no relayout when only this merge differs).
            val r = Settings.reader
            if (!reopening && r.withTxt(bookOverride) != readerTarget) applyToSession(r)
            // Clock and battery: same main-thread step as any repaint above, so still one redraw.
            refreshDecor(onlyIfChanged = true, sample = true)
        }
        // T1-11: TTS turned pages while the reader was in the background: one full refresh for the page now shown
        // (never on a wake that finds the same page, nor for a relayout of the page the reader left).
        if (turnedInBackground && pausedSection >= 0 && curLayout != null &&
            (curSection != pausedSection || curPageIdx != pausedPageIdx)
        ) {
            refreshAfterDraw(0L)
        }
        turnedInBackground = false
        pausedSection = -1
        keeper.poke()
    }

    override fun onPause() {
        super.onPause()
        // S §1.10: a running drag / fling settles first, so the save below has the line on top now.
        scroll?.stopMotion()
        ReaderPresence.inFront = false
        inFront = false
        panelOpen = false
        stopAutoTurn(showToast = false)
        // N §6.2: no save while peeking at a note (savePositionNow checks it too).
        if (!peekUntilTurn) savePositionNow(persistText = true)
        if (bookRef != null) ResumeState.paused()
        // Paused because the system is finishing it (an outside launch cleared the task): known before the library
        // that launch starts is created (ResumeState.dropped). The user's own closes went through finish().
        if (isFinishing && !userFinished && !isChangingConfigurations) ResumeState.dropped(this)
        if (curLayout != null) {
            pausedSection = curSection
            pausedPageIdx = curPageIdx
        }
        bookRef?.let { b -> tracker.pause(SystemClock.elapsedRealtime())?.let { writeReading(b.id, it) } }
        // From here TTS (if speaking) counts its own time: the tracker counts nothing until onResume.
        safely { tts?.onReaderPaused() }
        keeper.release()
        light.onPause()
    }

    override fun onDestroy() {
        // A close by the user (finish()) clears the resume state; a finish by the system (an outside launch that
        // cleared the task) leaves the book for the library to reopen (ResumeState.takeInterrupted); a destroy
        // without finishing (memory, "Don't keep activities") keeps the book with the task record.
        if (!isChangingConfigurations) {
            when {
                isFinishing && userFinished -> ResumeState.closed(this)
                isFinishing -> ResumeState.dropped(this)
                else -> ResumeState.detached(this)
            }
        }
        light.onDestroy(isFinishing)
        detachScroll()
        anchorJob?.cancel()
        anchorJob = null
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
        thumbs?.close(); thumbs = null
        page.afterFirstFrame = null
        afterOpenPending = false
        session?.close()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            focusSince = 0L
            handler.removeCallbacks(settleInsets)
            // A panel window (TOC, search, the reading-settings popup, a menu) took the focus over the page.
            if (curLayout != null && inFront) panelOpen = true
            return
        }
        focusSince = SystemClock.uptimeMillis()
        ReaderWindow.applyFullscreen(this, app.fullscreen)
        handler.removeCallbacks(settleInsets)
        handler.postDelayed(settleInsets, InsetsGate.SETTLE_MS)
        // Dialogs of the extras (contents, search) may have changed bookmarks/quotes. Only once afterOpen made the
        // first load (0 = not yet): no read, backfill or DB write before the first page (PLAN §1.6.2 step 8).
        if (session != null && annotationsLoadedAt != 0L && SystemClock.uptimeMillis() - annotationsLoadedAt > 1000) {
            reloadAnnotations()
        }
        if (panelOpen) {
            panelOpen = false
            onPanelClosed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configChanged = true
        // The page view's new size arrives through onSizeChanged → relayout.
        root.requestApplyInsets()
        // Unchanged insets never reach chrome.setInsets: the bars (if up) are sized for the new width here.
        chrome.onConfigurationChanged()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            session?.trimMemory()
            scroll?.onTrimMemory()
            thumbs?.clear()
        }
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

        light = LightController(lightHost)
        returnNav = ReturnNav(this, returnHost)
        chrome = ReaderChrome(this, chromeActions, returnNav.dock, light)
        light.attach(chrome)
        // The floating return chip: an overlay under the bars (it never resizes the page, C24); empty until shown.
        root.addView(returnNav.chip, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        chrome.attach(root)

        errorText = label("", 16f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        errorDetailText = label("", 14f, color = Ink.GRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        errorPanel = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ink.WHITE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
            isClickable = true
        }
        errorPanel.addView(label("책을 열 수 없습니다", 20f, bold = true).apply { gravity = Gravity.CENTER }, lp())
        errorPanel.addView(errorText, lp())
        errorPanel.addView(errorDetailText, lp())
        // Stacked, not side by side: three labels in one row do not fit 360 dp at large font scales.
        errorPanel.addView(errorButton("다시 시도") { retryOpen() }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        errorEncoding = errorButton("인코딩 바꾸기") { failedBook?.let { chooseEncodingAndRetry(it) } }
        errorPanel.addView(errorEncoding, lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        errorPanel.addView(errorButton("닫기") { finish() }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(errorPanel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        root.setOnApplyWindowInsetsListener { _, wi ->
            val settled = focusSince > 0L && SystemClock.uptimeMillis() - focusSince >= InsetsGate.SETTLE_MS
            val forced = curLayout == null || configChanged || insetsFullscreen != app.fullscreen
            configChanged = false
            insetsFullscreen = app.fullscreen
            insetsGate.offer(ReaderWindow.insetsOf(wi, app.fullscreen), settled, forced)?.let(::applyInsets)
            // The bars only (never the page): the bottom bar stays above the system's swipe strip.
            chrome.setGestureBottom(ReaderWindow.gestureBottom(wi))
            wi
        }
        // Bars the extras add over the page (search results, TTS): the return chip moves above them. The chrome's own
        // bars are overlays: their height changes (title, options panel, return strip) never move the page.
        val barsResized = View.OnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot) handler.post { if (!isDestroyed) onBarsResized() }
        }
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

    private fun errorButton(text: String, onClick: () -> Unit): TextView = label(text, 18f, bold = true, maxLines = 1).apply {
        gravity = Gravity.CENTER
        minWidth = dp(180)
        minHeight = dp(48)
        setPadding(dp(24), 0, dp(24), 0)
        background = borderBox()
        setOnClickListener { onClick() }
    }

    private fun isOwnView(v: View): Boolean =
        v === page || v === statusText || v === brightnessOverlay || v === returnNav.chip || v === errorPanel || chrome.owns(v) ||
            endPanel?.owns(v) == true

    private fun onBarsResized() = updateChipPosition()

    /**
     * The (gated, C18) system-bar insets: left/right margins here, top/bottom in [applyPageInsets]. Nothing else ever
     * changes the page view's size.
     */
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
        applyPageInsets()
    }

    private fun applyAppSettings() {
        // The brightness (window or device path) and page.brightnessSwipe: LightController is their only writer.
        light.onAppSettingsApplied()
        ReaderWindow.applyFullscreen(this, app.fullscreen)
        if (requestedOrientation != app.orientationLock) requestedOrientation = app.orientationLock
        keeper.enabled = app.keepScreenOn
        applyCadence(Settings.reader)
        cadence.onChapter = app.einkRefreshOnChapter
        // Read by Eink.fullRefresh (view) wherever it is called from; the reader's own refreshes pass them directly.
        Eink.configure(app.einkRefreshMethod, app.einkFlashMs)
        page.swipeToTurn = app.swipeToTurn
        page.verticalSwipe = app.verticalSwipe
        page.longPressEnabled = app.longPressSelect
        page.longPressMs = app.longPressMs.coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS).toLong()
        appliedApp = app
        // PLAN §1.6.2 order: brightness (U, the first line above) → quote look (N) → read mode (S) → page insets (U).
        updateQuoteLook(DeviceClass.cached(this))
        applyReadMode()
        applyPageInsets()
        root.requestApplyInsets()
    }

    /**
     * S §1.10: resolves the scroll motion (scrollStyle + DeviceClass, applied at the next touch) and, when the read
     * mode changed while a page is shown, switches the book to the other mode. Paged mode with no scroll viewport
     * returns at once: no DeviceClass read, no object.
     */
    private fun applyReadMode() {
        val want = app.readMode == ReadMode.SCROLL
        if (!want && scroll == null) {
            scrollWanted = false
            return
        }
        scroll?.onDeviceClass()
        if (want == scrollWanted) return
        scrollWanted = want
        if (session != null && curLayout != null) switchMode()
    }

    /**
     * S §1.10: the same place in the other mode, in one frame and without laying anything out. Scroll → paged shows
     * the page holding the anchor line with that line kept as the anchor, so both round trips are exact. While a
     * layout is pending (jump, relayout, re-parse) the page it shows attaches or detaches the viewport ([showPage]).
     */
    private fun switchMode() {
        val l = curLayout ?: return
        if (session == null || layoutStale() || navJob?.isActive == true || reopening) return
        stopAutoTurn(showToast = false)
        safely { selection?.clear() }
        val sc = scroll
        // Settles a fling or drag first, so the anchor is the line on top now.
        sc?.stopMotion()
        val a = sc?.let { DocPosition(ScrollWiring.section(it.anchor()), ScrollWiring.offset(it.anchor())) } ?: anchor
        switchingMode = true
        try {
            if (scrollWanted) {
                val p = l.pages.getOrNull(curPageIdx)
                val o = ScrollWiring.switchOffset(
                    a.section, a.offset, curSection, p?.start ?: 0, p?.end ?: 0, curPageIdx == l.pageCount - 1,
                )
                showPage(curSection, l, curPageIdx, Nav.RELAYOUT, anchorOffset = o)
            } else {
                // The top page holds the anchor line after every settle: its layout is normally [l]. Should the
                // anchor's section differ and not be cached, the top page's start is shown instead.
                val la = if (a.section == curSection) l else session?.peek(a.section)
                if (la != null) {
                    val off = a.offset.coerceIn(0, la.content.length)
                    showPage(a.section, la, AnchorMath.pageFor(la, off), Nav.RELAYOUT, anchorOffset = off)
                } else {
                    showPage(curSection, l, curPageIdx, Nav.RELAYOUT)
                }
            }
        } finally {
            switchingMode = false
        }
    }

    /** Creates or releases the scroll viewport to match [scrollWanted] (main thread, inside [showPage]). */
    private fun attachScroll(on: Boolean) {
        if (on) {
            if (scroll != null) return
            val sc = ScrollReader(page, scrollHost)
            sc.onDeviceClass()
            scroll = sc
            page.frame = null
            page.scroll = sc
            scrollSettledTop = -1L
            scrollScreens.reset()
        } else {
            detachScroll()
        }
    }

    private fun detachScroll() {
        val sc = scroll ?: return
        sc.detach()
        scroll = null
        page.scroll = null
        scrollDecor = null
        scrollFrozen = false
        scrollGesture = false
        scrollCloseAtSettle = false
        scrollFocusHeld = false
        stripJob?.cancel()
        stripJob = null
        stripSection = -1
    }

    /** ⋮ "스크롤로 보기" / "페이지로 보기" (S §1.2): saves the read mode and switches the open book at once. */
    internal fun toggleReadMode() {
        val m = if (app.readMode == ReadMode.SCROLL) ReadMode.PAGED else ReadMode.SCROLL
        saveApp(app.copy(readMode = m))
        // AUTO is resolved before the first scroll gesture (the probe runs once ever, on its own thread).
        if (m == ReadMode.SCROLL && DeviceClass.cached(this) == null) {
            DeviceClass.probeAsync(applicationContext) { if (!isDestroyed) scroll?.onDeviceClass() }
        }
        applyReadMode()
    }

    internal val scrollMode: Boolean get() = app.readMode == ReadMode.SCROLL

    /**
     * N §6.6: the quote look (colour / ink) for the user's choice and the device class; one decor refresh when it
     * changed (user-initiated, or the post-open probe).
     */
    private fun updateQuoteLook(eink: Boolean?) {
        val before = QuoteLook.generation
        QuoteLook.update(app.highlightLook, eink)
        if (QuoteLook.generation != before) refreshDecor()
        if (eink != einkClass) {
            einkClass = eink
            pushChromeLook()
        }
    }

    /** The refresh cadence for the page's colours: a dark page (밤 모드, the 마루뷰어 theme) has its own (T1-3b). */
    private fun applyCadence(s: ReaderSettings) {
        cadence.every = EinkCadence.everyFor(app.einkRefreshEvery, app.einkRefreshEveryNight, PagePalette.of(s).dark)
    }

    /**
     * The window and the blank page take the page's background ([PagePalette]: 흑백 반전, else the 화면 색); the
     * chrome and the system bars follow the same colours.
     */
    private fun applyReaderColors(s: ReaderSettings) {
        val palette = PagePalette.of(s)
        // Unchanged colours: no background reset (it would redraw the window, an e-ink update).
        if (palette === readerPalette) return
        thumbPaintVersion++
        readerPalette = palette
        root.setBackgroundColor(palette.background)
        page.blankColor = palette.background
        ReaderWindow.applyBarLook(this, palette.dark)
        pushChromeLook()
    }

    /**
     * The chrome's colours (`render/ChromePalette`, U §2.1) for the page's colours and the device class. Only stored
     * while the bars are hidden (they take them in the update that shows them): nothing is drawn for it before a page.
     */
    private fun pushChromeLook() {
        val p = readerPalette ?: PagePalette.PAPER
        chrome.setLook(p, einkClass)
        returnNav.setLook(p, einkClass)
    }

    /** "목차를 만드는 중…" while a big TXT is parsed in full (A5), else "불러오는 중…". */
    private val loadingRunnable = Runnable {
        val path = openingPath
        val building = path != null && TxtDocuments.isBuildingIndex(path)
        val text = ReaderFormat.loadingText(building, if (building) openingBytes else 0L)
        if (statusText.text.toString() != text) statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    private fun scheduleLoadingText() {
        handler.removeCallbacks(loadingRunnable)
        handler.postDelayed(loadingRunnable, LOADING_DELAY_MS)
    }

    private fun cancelLoadingText() {
        handler.removeCallbacks(loadingRunnable)
        if (statusText.visibility != View.GONE) statusText.visibility = View.GONE
    }

    /**
     * The error panel: [message] (from [ReaderFormat.openError] or our own; left out when it only repeats the title),
     * an optional grey [detail] line, and [다시 시도] / [인코딩 바꾸기] / [닫기]. [book] is a TXT book whose file could
     * not be read or parsed, the only failure another encoding can fix (not a missing file, nor a layout failure): it
     * offers [인코딩 바꾸기].
     */
    private fun showError(message: String, detail: String? = null, book: Book? = null) {
        cancelLoadingText()
        setChromeVisible(false)
        errorText.text = message
        errorText.visibility = if (message == ReaderFormat.OPEN_FAILED) View.GONE else View.VISIBLE
        errorDetailText.text = detail ?: ""
        errorDetailText.visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        failedBook = book
        errorEncoding.visibility = if (book?.format == BookFormat.TXT) View.VISIBLE else View.GONE
        errorPanel.visibility = View.VISIBLE
    }

    private fun showError(t: Throwable, book: Book? = null) =
        showError(ReaderFormat.openError(t), ReaderFormat.openErrorDetail(t), book)

    /** [다시 시도]: the same intent again (a book that opened but could not be laid out is closed first). */
    private fun retryOpen() {
        if (session != null || bookRef != null) closeCurrentBook()
        startOpen(intent)
    }

    /** [인코딩 바꾸기] (TXT): saves the chosen encoding for [b], then opens it again with it. */
    private fun chooseEncodingAndRetry(b: Book) {
        val options = listOf("") + TxtDocuments.ENCODINGS
        val labels = options.map { ReaderFormat.encodingLabel(it) }
        val current = options.indexOfFirst { it.equals(b.encoding.trim(), ignoreCase = true) }.coerceAtLeast(0)
        chooser("인코딩", labels, current) { i ->
            val enc = options[i]
            if (enc.equals(b.encoding.trim(), ignoreCase = true)) {
                retryOpen()
                return@chooser
            }
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        Library.setEncoding(b.id, enc)
                        // The TXT thumbnail is a rendering of the first page: redraw it with the new encoding.
                        runCatching { Covers.invalidate(applicationContext, b.id) }
                        true
                    } catch (t: Throwable) {
                        Log.w(TAG, "setEncoding failed", t)
                        false
                    }
                }
                if (isDestroyed) return@launch
                if (saved) retryOpen() else toast("인코딩을 저장하지 못했습니다")
            }
        }
    }

    // ================================================================== opening

    private fun startOpen(intent: Intent) {
        openStartedAt = SystemClock.uptimeMillis()
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
            // The book while its file is read and parsed: a failure there may be the TXT encoding's ([인코딩 바꾸기]).
            var parsing: Book? = null
            var adopted = false
            try {
                val opened = withContext(Dispatchers.IO) {
                    val b = IntentFiles.resolveBook(this@ReaderActivity, intent)
                    val f = File(b.path)
                    if (!f.isFile) throw DocumentException("파일을 찾을 수 없습니다\n${b.path}")
                    parsing = b
                    // T1-9: the book's own TXT options, one primary-key read on the connection resolveBook just used.
                    val over = if (b.format == BookFormat.TXT) txtOverrideOf(b.id) else null
                    val eff = settings.withTxt(over)
                    openingBytes = b.sizeBytes
                    openingPath = b.path
                    val d = Documents.open(f, eff.parseOptions(b.encoding))
                    doc = d
                    if (d.sections.isEmpty()) throw DocumentException("내용이 없는 책입니다")
                    parsing = null
                    // A12-2: a user font's catalogue (a folder scan) is read here rather than by the renderer on the
                    // main thread; after the parse, when the font warm-up has usually scanned already.
                    loadUserFont(eff.fontId)
                    Opened(b, d, readTextPosition(b.id), over, eff)
                }
                val b = opened.book
                val d = opened.document
                val storedPos = opened.textPosition
                val eff = opened.settings
                if (intent.getLongExtra(EXTRA_BOOK_ID, -1L) != b.id && getIntent() === intent) {
                    // Retry in this instance opens by id. Recreation uses onSaveInstanceState instead.
                    setIntent(Intent(intent).putExtra(EXTRA_BOOK_ID, b.id))
                }
                bookRef = b
                bookOverride = opened.override
                // What 설정 changed for this book is in what the open just read (its writes are made at once): edits
                // nobody took (the reader was re-created meanwhile) must not be applied over newer ones later.
                OpenBook.take(b.id)
                readerTarget = eff
                chrome.setTitle(b.title)
                val s = BookSession(this@ReaderActivity, b, d, eff)
                // PLAN §1.6.1: restored place > note jump > TXT fraction remap > DB row, all before the session is
                // published (A §5.5: a resize during the open anchors at the same start).
                val place = restoredPlace
                restoredPlace = null
                // N §6.1: six getExtra calls, no I/O (a restored reader's by-id intent has none). A place kept from
                // another book (its open failed) never suppresses this book's jump.
                val jump = if (place == null || place.bookId != b.id) ReaderJump.from(intent) else null
                val sigText = LayoutKeys.textSignature(eff, d.format, b.encoding)
                val noteSig = NoteSig.of(sigText, b.sizeBytes)
                // A TXT position saved under other parse options (chapter detection, replace rules, encoding, ...)
                // is found again by its char fraction instead of reading stale (section, offset) coordinates.
                val remap = TextPositions.remapFraction(storedPos, sigText, b.posSection, b.posOffset, b.progress)
                val saved = if (remap != null) s.counts.locateFraction(remap) else DocPosition(b.posSection, b.posOffset)
                val kept = place?.let { ReaderRestore.start(it, b.id, b.lastReadAt, remapped = remap != null) }
                // A restored peek only holds for the restored page; the DB row won (newer, remapped): read normally.
                if (place != null && kept == null) peek.reset()
                val target = if (kept == null && jump != null) {
                    ReaderJump.resolve(jump, noteSig, s.sectionCount) { f -> s.counts.locateFraction(f) }
                } else null
                val start = kept ?: target ?: saved
                val sec = start.section.coerceIn(0, s.sectionCount - 1)
                anchor = DocPosition(sec, start.offset.coerceAtLeast(0))
                sigSession = s
                sigPlain = sigText
                sigNote = noteSig
                s.listener = sessionListener
                thumbs?.close(); thumbs = null
                thumbDecorVersions = IntArray(0)
                openOwnsViewport = true
                session = s
                adopted = true
                afterOpenPending = true
                viewReady.await()
                val (vw, vh) = pageTargetSize()
                // PLAN C16: a note opens with natural pagination; restored and normal opens are anchored at the start.
                s.setViewport(vw, vh, pageCutoutTop, if (target != null) null else AnchorSpec(sec, anchor.offset))
                openOwnsViewport = false
                // Cached page counts load in parallel with the first layout.
                s.startCounting(COUNT_DELAY_MS)
                val l = s.layout(sec)
                if (l == null) {
                    if (session === s && !s.isClosed) showError("페이지를 나누지 못했습니다")
                    return@launch
                }
                val off = start.offset.coerceIn(0, l.content.length)
                val idx = if (target != null) l.pageForOffset(off) else AnchorMath.pageFor(l, off)
                // N §6.1 2: the stored coordinates were used and the note's text is still there (O(anchor), in memory).
                val exact = jump != null && target == DocPosition(jump.section, jump.offset)
                val matched = exact && JumpAnchor.matches(l.content.text, off, jump!!.anchor)
                if (matched && jump!!.end > jump.offset) {
                    // Into the map buildDecor reads, with no redraw call: the first frame shows the mark.
                    ownerHighlights[OWNER_JUMP] =
                        sec to listOf(Highlight(off, minOf(jump.end, l.content.length), HighlightKind.SEARCH))
                }
                preloadImages(s, l, idx)
                if (session !== s) return@launch
                // Paged: a note opens at its page start (A's JUMP rule). Scroll (attached by this show when wanted):
                // the exact offset, with CONTEXT placement for a mid-line note (S §1.10 JUMP rule, [openedAtNote]).
                openedAtNote = target != null
                val shown = try {
                    showPage(sec, l, idx, Nav.OPEN, anchorOffset = if (target != null && !scrollWanted) -1 else off)
                } finally {
                    openedAtNote = false
                }
                // A renderer failure shows the error panel: the jump stays in the intent for [다시 시도], no return
                // point or peek, and afterOpen waits for a page that really shows.
                if (!shown || session !== s) return@launch
                if (target != null) {
                    // Same main-thread message as the page and the mark: one e-ink update (PLAN C23).
                    if (!isOnCurrentPage(saved)) safely { returnNav.onJump(saved) }
                    peek.start()
                    // A recreation in this process opens at the saved place, not at the note again (the by-id
                    // intent of a restored reader covers process death, PLAN C8).
                    setIntent(ReaderJump.strip(getIntent()))
                    openedJump = OpenedJump(jump!!, target, exact, matched)
                }
                if (jump != null && target == null) toast("노트가 있던 곳을 찾지 못했습니다")
                // Once per open: a re-parse that replaces this session before the first draw re-arms it.
                page.afterFirstFrame = afterFirstPage
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "open failed", t)
                showError(t, parsing)
            } finally {
                openOwnsViewport = false
                if (!adopted) {
                    try {
                        doc?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /** What the open path's IO block hands back: the book, its document and the settings it was parsed with. */
    private class Opened(
        val book: Book,
        val document: BookDocument,
        val textPosition: String?,
        val override: TxtOverride?,
        val settings: ReaderSettings,
    )

    /** [bookId]'s own TXT options (IO thread), or null (none, or the read failed: the defaults apply). */
    private fun txtOverrideOf(bookId: Long): TxtOverride? = try {
        BookPrefs.txtOverride(bookId)?.takeUnless { it.isEmpty }
    } catch (t: Throwable) {
        Log.w(TAG, "txt override read failed", t)
        null
    }

    /** Looks a user font up once (IO thread): its first lookup scans the font folders. */
    private fun loadUserFont(fontId: String) {
        if (!fontId.startsWith(FontFiles.USER_PREFIX)) return
        try {
            FontManager.font(fontId)
        } catch (t: Throwable) {
            Log.w(TAG, "user font lookup failed", t)
        }
    }

    /** Everything the first page did not wait for (spec rule 2: after the first page, never before it). */
    private fun afterOpen() {
        val t0 = if (ReaderPerf.turns) SystemClock.uptimeMillis() else 0L
        val s = session ?: return
        val id = bookRef?.id ?: return
        val appCtx = applicationContext
        // PLAN §1.6.2, in this order; every IO step is launched, never awaited.
        if (!isFinishing) ResumeState.opened(id, this)                                  // 1 (R); not once closed
        // 2 (S): on main (apply(): in memory at once), so it sees the settings prefs before step 4's probe thread
        // can write "deviceClass" there (DA-B: a fresh install's restore offer is decided on untouched prefs).
        try {
            InstallState.ensure(this)
        } catch (t: Throwable) {
            Log.w(TAG, "install state", t)
        }
        light.afterFirstPage()                                                          // 3 (U)
        DeviceClass.probeAsync(appCtx) { eink ->                                        // 4 (S + N), on main
            if (isDestroyed || session !== s) return@probeAsync
            updateQuoteLook(eink)
            scroll?.onDeviceClass()
        }
        loadReturnMark(s, id)                                                           // 5 (U §3.3)
        if (s.settings.shows(StatusItem.EPISODE)) scheduleEpisodes()                   // 6 (U)
        openedJump?.let { openedJump = null; checkJumpAnchor(s, it) }                   // 7 (N §6.4)
        // 8 (N §6.5): the note-place backfill runs when reloadAnnotations delivers (below).
        if (selection == null) selection = safely { SelectionController(this) }
        // Every long press selects the word of the glyph under the finger, also while a selection shows (a press on
        // blank paper or a space keeps that selection).
        selection?.glyphAt = { x, y -> glyphAtView(x, y, dpF(GLYPH_SLOP_DP)) }
        // Created up front (cheap: the engine starts on start()) so the selection popup's "여기부터 듣기" finds
        // this host's controller, and BACK / volume keys see the same TTS session whoever started it.
        if (tts == null) tts = safely { TtsController(this) }
        // N §6.2: "여기부터 듣기" reads from the selection and ends a peek.
        selection?.onReadAloud = { p ->
            endPeek(PeekRule.Event.READ_HERE)
            (tts ?: safely { TtsController(this) }?.also { tts = it })?.let { t -> safely { t.startFrom(p) } }
        }
        reloadAnnotations()
        // A12-1: cache files the open computed but left for later (the EPUB section plan).
        ReaderIo.launch { Documents.writeDeferredCaches() }
        loadSpeed()
        if (ReaderPerf.turns) Log.d(ReaderPerf.TAG, "afterOpen ${SystemClock.uptimeMillis() - t0} ms")
    }

    /**
     * U §3.3: the pinned return point, read on IO after the first page; applied only to the book and session it was
     * read for (C13: a book switched meanwhile must not get the previous one's pin).
     */
    private fun loadReturnMark(s: BookSession, id: Long) {
        ReaderIo.launch {
            val text = try {
                BookPrefs.returnMark(id)
            } catch (t: Throwable) {
                Log.w(TAG, "return mark load failed", t)
                null
            }
            handler.post {
                if (isDestroyed || bookRef?.id != id || session !== s) return@post
                safely { returnNav.restore(text) }
            }
        }
    }

    /** ReadingLog's reading speed for this book (T1-7), on IO; [ReadingLog.DEFAULT_CPM] until known. */
    private fun loadSpeed() {
        val id = bookRef?.id ?: return
        ReaderIo.launch {
            val v = ReadingLog.cpm(id)
            if (v != null && v > 0) handler.post { if (bookRef?.id == id) cpm = v }
        }
    }

    /**
     * Asks for the status slots' episode numbers a moment after the first page (the parse runs on Dispatchers.Default);
     * only while a slot shows [StatusItem.EPISODE] (U §5.3).
     */
    private fun scheduleEpisodes() {
        if (episodesAsked || episodesPending || epShown != null) return
        if (session?.settings?.shows(StatusItem.EPISODE) != true) return
        episodesPending = true
        handler.postDelayed(askEpisodes, EPISODES_DELAY_MS)
    }

    private fun loadEpisodes() {
        val s = session ?: return
        if (episodesAsked) return
        episodesAsked = true
        episodes { e ->
            if (session !== s || e == null) return@episodes
            setEpisodes(e)
            refreshDecor(onlyIfChanged = true)
        }
    }

    /** Keeps what the footer needs from [e]: per TOC entry the number to show (its own, else the last one before it). */
    private fun setEpisodes(e: Episodes) {
        val nums = e.numbers
        val shown = IntArray(nums.size)
        var last = -1
        for (i in nums.indices) {
            if (nums[i] > 0) last = nums[i]
            shown[i] = last
        }
        epNumbered = safely { e.usableForJump } == true
        epMax = safely { e.maxNumber } ?: -1
        epShown = shown
    }

    private fun closeCurrentBook() {
        // Panels first, while the session and book still exist: the settings popup's pending change applies to this
        // book, and no TOC / search / bar is left acting on the next one.
        safely { ReaderPanels.dismissAll(this) }
        // Settles a running drag or fling, so the position saved is the line on top now.
        scroll?.stopMotion()
        savePositionNow(persistText = true)
        bookRef?.let { b -> tracker.flush(SystemClock.elapsedRealtime())?.let { writeReading(b.id, it) } }
        stopAutoTurn(showToast = false)
        safely { tts?.release() }
        tts = null
        safely { selection?.clear() }
        selection = null
        navJob?.cancel()
        openJob?.cancel()
        thumbs?.close(); thumbs = null
        page.afterFirstFrame = null
        afterOpenPending = false
        session?.close()
        session = null
        bookRef = null
        bookOverride = null
        readerTarget = null
        reopening = false
        reopenDone.clear()
        cpm = ReadingLog.DEFAULT_CPM
        handler.removeCallbacks(askEpisodes)
        episodesPending = false
        episodesAsked = false
        epShown = null
        endLoading = false
        endPanel?.hide()
        pausedSection = -1
        turnedInBackground = false
        curLayout = null
        curSection = 0
        curPageIdx = 0
        anchor = DocPosition.START
        displayedGenId = -1
        lastChapterIdx = Int.MIN_VALUE
        bookmarks = emptyList()
        quotesBySection.clear()
        quoteRows = emptyMap()
        quoteMovedById.clear()
        quoteCheckSession = null
        sigSession = null
        sigNote = null
        sigPlain = null
        backfillBook = -1L
        // The next open's first annotation load is afterOpen's (onWindowFocusChanged waits for it).
        annotationsLoadedAt = 0L
        // A recreated reader's saved place belongs to its own book only (PLAN §1.6.1).
        restoredPlace = null
        peek.reset()
        openedJump = null
        anchorJob?.cancel()
        anchorJob = null
        ownerHighlights.clear()
        // The pin and the jump origins belong to this book; a pin load still in flight is dropped (U §3.3).
        returnNav.reset()
        backlog.clear()
        pendingJump = null
        handler.removeCallbacks(cadenceRefresh)
        cadenceRefreshPending = false
        imagePrefetch?.cancel()
        imagePrefetch = null
        setChromeVisible(false)
        detachScroll()
        page.frame = null
        page.invalidate()
    }

    private fun reloadAnnotations() {
        val b = bookRef ?: return
        annotationsLoadedAt = SystemClock.uptimeMillis()
        val edits = quoteEdits
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
            val s = session ?: return@launch
            // A quote edited while this read ran ([setHighlights]) owns QuoteCache and the drawn quotes: rows read
            // before its write would take it off the page again (a second and third e-ink update).
            val quotesCurrent = edits == quoteEdits
            // A12-4: the selection's quote lookups use these rows instead of querying them again.
            if (quotesCurrent) QuoteCache.put(b.id, loaded.second)
            // Keep bookmarks added a moment ago that are still being saved.
            val unsaved = bookmarks.filter { it.id < 0 }
            val nextMarks = loaded.first + unsaved
            val nextQuotes = if (quotesCurrent) loaded.second.groupBy { it.section } else quoteRows
            val changedSections = HashSet<Int>()
            changedSections.addAll(quoteRows.keys); changedSections.addAll(nextQuotes.keys)
            for (mark in bookmarks) changedSections.add(mark.section)
            for (mark in nextMarks) changedSections.add(mark.section)
            for (sec in changedSections) {
                if (quoteRows[sec] != nextQuotes[sec] ||
                    bookmarks.filter { it.section == sec } != nextMarks.filter { it.section == sec }) bumpThumbDecor(sec)
            }
            bookmarks = nextMarks
            if (quotesCurrent) {
                quoteRows = nextQuotes
                // Scroll: the strips cache their quotes, so the sections whose drawn quotes changed are told (below).
                val drawn = if (scroll != null) HashMap(quotesBySection) else null
                // N §6.6 / PLAN K2: checked again lazily, per section, the first time the page decor needs it.
                resetQuoteChecks(s)
                scroll?.let { sc -> if (drawn != null) notifyQuoteChanges(sc, s, drawn) }
            }
            refreshDecor(onlyIfChanged = true)
            if (chromeVisible) bindChrome()
            backfillPlaces(s, b.id, loaded.second, loaded.first)
        }
    }

    /**
     * Scroll: the strips cache their quotes. After new rows, tells the viewport about each section whose drawn quotes
     * changed ([drawn]: the cache before the reload); an unchanged reload (a focus regain) redraws nothing.
     */
    private fun notifyQuoteChanges(sc: ScrollReader, s: BookSession, drawn: Map<Int, List<Highlight>>) {
        val sections = HashSet<Int>(drawn.keys)
        sections.addAll(quoteRows.keys)
        for (sec in sections) {
            val now = quotesFor(sec, sectionText(s, sec))
            if (!sameHighlights(drawn[sec] ?: emptyList(), now)) sc.onHighlightsChanged(sec)
        }
    }

    /**
     * The laid-out text of [section] for the quote check: the session's cache, else the scroll viewport's own copy
     * (it may keep a section the session evicted; read-only when the session has none). Null when neither has it.
     */
    private fun sectionText(s: BookSession, section: Int): CharSequence? =
        (s.peek(section) ?: scroll?.layoutOf(section))?.content?.text

    /** Drops the per-section quote checks (new rows, or another session's text). */
    private fun resetQuoteChecks(s: BookSession) {
        quoteCheckSession = s
        quotesBySection.clear()
        quoteMovedById.clear()
    }

    /**
     * The quotes drawn in [section] (PLAN K2 refinement of N §6.5): a quote made against this text (its sig is the
     * session's [NoteSig]) is drawn; a legacy `''` or another sig is drawn only when its text is still at its start
     * ([JumpAnchor.matches], ≤ 24 visible chars). Checked once per section of [text] (the section's laid-out text) and
     * cached for the session; without [text] nothing is checked or cached. Highlights carry the quote colour.
     */
    private fun quotesFor(section: Int, text: CharSequence?): List<Highlight> {
        val s = session ?: return emptyList()
        if (quoteCheckSession !== s) resetQuoteChecks(s)
        quotesBySection[section]?.let { return it }
        val rows = quoteRows[section]
        if (rows.isNullOrEmpty() || text == null) return emptyList()
        val sig = placeSig(s)
        val out = ArrayList<Highlight>(rows.size)
        for (q in rows) {
            // The contents dialog's rule (QuoteRows.placeChanged), with the anchor compared only when the sig differs.
            val anchorMatch = if (q.sig == sig) null else anchorMatches(q, text)
            val holds = !QuoteRows.placeChanged(q.sig, sig, anchorMatch)
            quoteMovedById[q.id] = !holds
            if (holds) out += Highlight(q.start, q.end, HighlightKind.QUOTE, q.style)
        }
        quotesBySection[section] = out
        return out
    }

    /** Whether [q]'s text is still at its start in [text] (its section's laid-out text; ≤ 24 visible chars). */
    private fun anchorMatches(q: Quote, text: CharSequence): Boolean =
        q.start >= 0 && q.start < text.length && JumpAnchor.matches(text, q.start, q.text)

    /**
     * [NotePlaceHost]: the reader's own anchor check for [q] on its section's text, for any section laid out (the
     * session's cache or the scroll strips), so the contents dialog and the selection send the set the page draws
     * (PLAN K2: one judge). Null when that text is not at hand.
     */
    override fun quoteAnchorMatch(q: Quote): Boolean? {
        val s = session ?: return null
        val text = sectionText(s, q.section) ?: return null
        return anchorMatches(q, text)
    }

    /** TOC "· 위치 바뀜" (PLAN K2): the page's result when the section was checked, else the sig. */
    override fun quoteMoved(quote: Quote): Boolean {
        val s = session ?: return false
        if (quoteCheckSession === s) quoteMovedById[quote.id]?.let { return it }
        val sig = placeSig(s)
        return quote.sig != sig && quote.sig.isNotEmpty() && sig.isNotEmpty()
    }

    /** [NotePlace.sig] of the session: its [NoteSig] for TXT, '' for EPUB (what notes store and the TOC compares). */
    private fun placeSig(s: BookSession): String {
        val sig = noteSig(s)
        return if (sigPlain == null) "" else sig
    }

    /**
     * Opens [jump] (a note of the open book) through the note-jump path: [ReaderJump.resolve] (stored place, else the
     * fraction), the mark and the anchor check (N §6.3); a remembered jump. Main thread. For the contents dialog's
     * moved quotes (EX-N); the contract interface is added at merge.
     */
    override fun openNote(jump: ReaderJump) = jumpToNote(jump)

    /** The session's [NoteSig] (computed once per session: re-parses make a new one). */
    private fun noteSig(s: BookSession): String {
        if (sigSession !== s) {
            val b = bookRef
            sigPlain = if (b == null) null else LayoutKeys.textSignature(s.settings, s.document.format, b.encoding)
            sigNote = NoteSig.of(sigPlain, b?.sizeBytes ?: 0L)
            sigSession = s
        }
        return sigNote ?: ""
    }

    /**
     * N §6.5 [NotePlaceHost]: where a note at [pos] sits: the chapter title (cleaned, ≤ 200; "" without a TOC entry),
     * the char progress and the session's [NoteSig] ("" for EPUB). [NotePlace.UNKNOWN] without a session.
     */
    override fun notePlace(pos: DocPosition): NotePlace {
        val s = session ?: return NotePlace.UNKNOWN
        return try {
            val ci = s.chapters.indexAt(pos.section, pos.offset)
            NotePlace(
                NotePlaceText.chapter(if (ci >= 0) s.chapters.title(ci) else null),
                s.counts.charProgress(pos.section, pos.offset),
                placeSig(s),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "note place failed", t)
            NotePlace.UNKNOWN
        }
    }

    /**
     * N §6.5 backfill: quotes and bookmarks saved without a place (frac < 0) get one, at most [BACKFILL_MAX] per open,
     * [BACKFILL_CHUNK] per main-thread message (interleaved with input), then one write on IO. Backfilled rows keep
     * sig '' (the sig at creation time is not knowable later). Once per open; each row only ever once.
     */
    private fun backfillPlaces(s: BookSession, bookId: Long, quotes: List<Quote>, marks: List<Bookmark>) {
        if (backfillBook == bookId) return
        val todo = ArrayList<DocPosition>()
        val ids = ArrayList<Long>()
        var quoteCount = 0
        for (q in quotes) {
            if (todo.size >= BACKFILL_MAX) break
            if (q.frac < 0f && q.id > 0) { todo += DocPosition(q.section, q.start); ids += q.id; quoteCount++ }
        }
        for (m in marks) {
            if (todo.size >= BACKFILL_MAX) break
            if (m.frac < 0f && m.id > 0) { todo += DocPosition(m.section, m.offset); ids += m.id }
        }
        if (todo.isEmpty()) return
        backfillBook = bookId
        val qPlaces = HashMap<Long, NotePlace>()
        val bPlaces = HashMap<Long, NotePlace>()
        var next = 0
        val step = object : Runnable {
            override fun run() {
                if (isDestroyed || session !== s || backfillBook != bookId) return
                val end = minOf(next + BACKFILL_CHUNK, todo.size)
                while (next < end) {
                    val place = notePlace(todo[next]).let { NotePlace(it.chapter, it.frac, "") }
                    if (place.frac >= 0f) (if (next < quoteCount) qPlaces else bPlaces)[ids[next]] = place
                    next++
                }
                if (next < todo.size) {
                    handler.post(this)
                    return
                }
                if (qPlaces.isEmpty() && bPlaces.isEmpty()) return
                ReaderIo.launch { Library.fillNotePlaces(bookId, qPlaces, bPlaces) }
            }
        }
        handler.post(step)
    }

    /**
     * N §6.2: [e] may end a peek; then normal saving resumes with the page now shown (the turn / jump that ended it
     * already passed its own save while the peek was on). Scroll mode calls it with SCROLL_SETTLE at the first user
     * settle.
     */
    private fun endPeek(e: PeekRule.Event) {
        if (!peek.on(e)) return
        // The note's anchor search must not move a page the user now reads (TTS, auto turn, 여기부터 듣기).
        anchorJob?.cancel()
        if (curLayout == null) return
        schedulePositionSave()
        // A re-parse during the peek skipped the text signature: record it with the parse now shown.
        val b = bookRef
        val s = session
        if (b != null && s != null) writeTextPosition(b, s, anchor)
    }

    /** Removes the note mark (a manual turn, a new note jump). */
    private fun clearJumpMark() {
        ownerHighlights.remove(OWNER_JUMP)?.let { (section, _) -> scroll?.onHighlightsChanged(section) }
    }

    /**
     * N §6.4: the note's text is not where its coordinates point (a changed file, other TXT options) or the jump fell
     * back to its fraction: search it outward from the target ([JumpAnchor.search], capped), off main and
     * cancellable (a manual turn, a user jump, closing). Found → the page and the mark move there without a return
     * point; not found → the page stays near the place. The found position is not written back to the note (v1).
     */
    private fun checkJumpAnchor(s: BookSession, oj: OpenedJump) {
        val anchorText = oj.jump.anchor
        if (oj.matched || !JumpAnchor.hasText(anchorText)) return
        anchorJob?.cancel()
        anchorJob = scope.launch {
            val sec = oj.target.section.coerceIn(0, s.sectionCount - 1)
            val here = if (oj.exact) s.layout(sec) else s.peek(sec)
            if (session !== s) return@launch
            if (oj.exact && here != null) {
                val off = oj.target.offset.coerceIn(0, here.content.length)
                if (JumpAnchor.matches(here.content.text, off, anchorText)) {
                    // A new intent's jump whose section was not laid out yet: the mark arrives with its page.
                    if (putJumpMark(sec, off, oj.jump, here.content.length)) refreshDecor(onlyIfChanged = true)
                    return@launch
                }
            }
            val center = here?.content?.text
            var foundLength = 0
            val found = withContext(Dispatchers.Default) {
                JumpAnchor.search(sec, s.sectionCount, anchorText, load = { i ->
                    val t = if (i == sec && center != null) center else try {
                        s.document.loadSection(i).text
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        null
                    }
                    if (t != null) foundLength = t.length
                    t
                }, check = { ensureActive() })
            }
            if (session !== s) return@launch
            if (found == null) {
                toast("노트가 있던 곳을 찾지 못해 가까운 위치를 열었습니다")
                return@launch
            }
            clearJumpMark()
            putJumpMark(found.section, found.offset, oj.jump, foundLength)
            // Not a user jump: no return point, the peek goes on (the page is still the note's).
            peek.on(PeekRule.Event.ANCHOR_MOVE)
            backlog.clear()
            navigateTo(found.section, found.offset, -1, Nav.JUMP)
            toast("노트 위치를 다시 찾았습니다")
        }
    }

    /** Puts the note mark at [offset] of [section] (same length as the jump's range); false when it has none. */
    private fun putJumpMark(section: Int, offset: Int, jump: ReaderJump, length: Int): Boolean {
        if (jump.end <= jump.offset) return false
        val end = minOf(offset + (jump.end - jump.offset), length)
        if (end <= offset) return false
        ownerHighlights[OWNER_JUMP] = section to listOf(Highlight(offset, end, HighlightKind.SEARCH))
        scroll?.onHighlightsChanged(section)
        return true
    }

    // ================================================================== session callbacks

    private val sessionListener = object : BookSession.Listener {
        override fun onCountsChanged(complete: Boolean) {
            if (curLayout == null) return
            if (complete) {
                // The exact page numbers (the default header's 쪽 번호 since 2026-10-05) show at once on a phone. On
                // e-ink they wait for the next redraw (a turn, a scroll step, any other refresh), like the clock: no
                // screen update seconds after the open without a user action.
                if (DeviceClass.cached(this@ReaderActivity) != true) refreshDecor(onlyIfChanged = true)
                // The page label and the return strip's page numbers become exact (bindChrome binds the strip).
                if (chromeVisible) bindChrome() else returnNav.bind()
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
            scroll?.let {
                it.onSectionStored(section, layout)
                if (section == stripSection) {
                    stripSection = -1
                    cancelLoadingText()
                }
                return
            }
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
        endPanel?.let { if (it.isShowing) it.fit(root.width) }
        // startOpen sizes the first viewport itself (pageTargetSize, so this size is not lost): no anchored build here.
        if (openOwnsViewport) return
        val s = session ?: return
        // A rotation mid-fling keeps the line that is on top now (settled against the old frame), A §5.5.
        scroll?.stopMotion()
        if (!s.setViewport(w, h, pageCutoutTop, keepHere())) return
        onNewGeneration()
        relayout()
    }

    /** U6 (A §5.5): what a rebuild keeps as the first char of the page being read (the scroll anchor line). */
    private fun keepHere(): AnchorSpec = AnchorSpec(anchor.section, anchor.offset)

    /** The session made a new generation: the scroll viewport keeps drawing its frozen frame until the next show. */
    private fun onNewGeneration() {
        val sc = scroll ?: return
        sc.onGenerationChanged()
        scrollFrozen = true
        stripJob?.cancel()
        stripJob = null
        stripSection = -1
    }

    // ================================================================== navigation core

    private fun layoutStale(): Boolean = session?.generation?.id != displayedGenId

    /**
     * Shows [pageIndex] of [layout] (which belongs to the current generation). False when nothing was shown (no
     * session or generation, or the renderer failed: the error panel shows).
     */
    private fun showPage(section: Int, layout: SectionLayout, pageIndex: Int, kind: Nav, anchorOffset: Int = -1): Boolean {
        val s = session ?: return false
        if (scrollWanted != (scroll != null)) attachScroll(scrollWanted)
        scroll?.let {
            return showScroll(it, s, section, layout, pageIndex, kind, anchorOffset)
        }
        val gen = s.generation ?: return false
        val idx = pageIndex.coerceIn(0, (layout.pageCount - 1).coerceAtLeast(0))
        val renderer = try {
            s.renderer()
        } catch (t: Throwable) {
            Log.w(TAG, "renderer failed", t)
            showError(t)
            return false
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
            buildDecor(sample = true),
        )
        page.invalidate()
        if (kind == Nav.OPEN) page.traceOpen(bookRef?.id ?: -1L, openStartedAt)
        if (ReaderPerf.turns) {
            val started = when (kind) {
                Nav.OPEN -> openStartedAt
                Nav.TURN -> perfTurnFrom.takeIf { it != 0L } ?: perfRequestAt
                else -> perfRequestAt
            }
            val elapsed = if (started > 0L) SystemClock.uptimeMillis() - started else 0L
            Log.d(ReaderPerf.TAG, "show $kind s:$section o:${p?.start ?: 0} a:${anchor.offset} g:${gen.id} ${elapsed}ms")
        }
        if (perfTurnFrom != 0L) {
            if (kind == Nav.TURN) page.traceTurn(perfTurnFrom)
            perfTurnFrom = 0L
        }
        safely { selection?.onPageChanged() }
        if (chromeVisible) bindChrome() else returnNav.bind()

        // A relayout shows the same place again: the page on screen keeps counting.
        if (kind != Nav.RELAYOUT) trackPage(p)
        val chapterIdx = s.chapters.indexAt(section, p?.start ?: 0)
        val chapterChanged = if (s.chapters.size > 0) chapterIdx != lastChapterIdx else sectionChanged
        lastChapterIdx = chapterIdx
        if (kind == Nav.TURN || kind == Nav.JUMP) onTurnShown(kind, chapterChanged, layout, idx)
        if (kind != Nav.RELAYOUT) schedulePositionSave()
        s.prefetch(section + 1)
        s.prefetch(section - 1)
        // The renderer pre-decodes the neighbouring pages of this section only: at a section boundary, decode the
        // page of the cached neighbour section a turn would show (a pending neighbour does it on arrival).
        if (idx == layout.pageCount - 1) s.peek(section + 1)?.let { prefetchImages(s, it, 0) }
        if (idx == 0) s.peek(section - 1)?.let { prefetchImages(s, it, it.pageCount - 1) }
        keeper.poke()
        return true
    }

    /**
     * S §1.10 showPage branch: puts [anchorOffset] (else the start of [pageIndex]) at the top of the scroll viewport,
     * or 25 % down for a jump to a mid-line offset. The settle that ends [ScrollReader.showAt] does the page
     * bookkeeping ([onScrollSettled]).
     */
    private fun showScroll(
        sc: ScrollReader, s: BookSession, section: Int, layout: SectionLayout, pageIndex: Int, kind: Nav, anchorOffset: Int,
    ): Boolean {
        s.generation ?: return false
        try {
            s.renderer()
        } catch (t: Throwable) {
            Log.w(TAG, "renderer failed", t)
            showError(t)
            return false
        }
        val idx = pageIndex.coerceIn(0, (layout.pageCount - 1).coerceAtLeast(0))
        val off = if (anchorOffset >= 0) anchorOffset.coerceIn(0, layout.content.length) else layout.pages.getOrNull(idx)?.start ?: 0
        // An open at a note ([openedAtNote]) places a mid-line target like a jump (CONTEXT, PLAN §1.6.1).
        val context = ScrollWiring.contextPlacement(
            kind == Nav.JUMP || (kind == Nav.OPEN && openedAtNote), ScrollWiring.isLineStart(layout, off),
        )
        val settle = when (kind) {
            Nav.OPEN -> SettleKind.OPEN
            Nav.TURN -> SettleKind.STEP
            Nav.JUMP -> SettleKind.JUMP
            Nav.RELAYOUT -> if (switchingMode) SettleKind.SWITCH else SettleKind.RELAYOUT
        }
        scrollFrozen = false
        if (kind != Nav.RELAYOUT) scrollScreens.reset()
        // A section a scroll was waiting for no longer matters: this show replaces the viewport.
        stripJob?.cancel()
        stripJob = null
        stripSection = -1
        sc.showAt(section, layout, off, if (context) Placement.CONTEXT else Placement.TOP, settle)
        if (kind == Nav.OPEN) page.traceOpen(bookRef?.id ?: -1L, openStartedAt)
        return true
    }

    /**
     * S §1.10 "Scroll settle": the single place where scroll mode does the page bookkeeping (anchor, top page,
     * tracker, cadence, save, chrome, TTS, return chip, selection). Runs in the same main-thread task as the
     * viewport's invalidate, so a STEP moves the text and updates the status in one e-ink frame (U §5.6).
     */
    private fun onScrollSettled(kind: SettleKind, movedPx: Float) {
        val sc = scroll ?: return
        val s = session ?: return
        val gen = s.generation ?: return
        if (scrollFrozen) return
        val top = sc.topPage()
        val sec = ScrollWiring.section(top)
        val idx = top.toInt()
        val l = sc.layoutOf(sec) ?: return
        val a = sc.anchor()
        // cur* already follow the top page (onScrollTopChanged): compare with the last settle instead.
        val before = scrollSettledTop
        val pageChanged = top != before || displayedGenId != gen.id
        curSection = sec
        curLayout = l
        curPageIdx = idx
        displayedGenId = gen.id
        anchor = DocPosition(ScrollWiring.section(a), ScrollWiring.offset(a))
        scrollSettledTop = top
        if (scrollFlushing) {
            // S §1.10: the steps of one flush are one drawn screen, bookkept once at its end (flushScrollTurns).
            flushSettled = true
            flushMovedPx += movedPx
            return
        }
        if (kind == SettleKind.STEP && !backlog.isEmpty && navJob?.isActive != true) {
            // A step that waited for its section arrived with turns queued behind it (turn): they go now, in the same
            // frame, with one bookkeeping pass for the screen finally drawn.
            flushScrollTurns(sc, backlog.take(), before, movedPx)
            return
        }
        scrollBookkeeping(kind, movedPx, before, pageChanged)
    }

    /**
     * The page bookkeeping of a scroll settle for the top page now in cur* ([onScrollSettled]); [before] is the top page
     * at the settle before it.
     */
    private fun scrollBookkeeping(kind: SettleKind, movedPx: Float, before: Long, pageChanged: Boolean) {
        val s = session ?: return
        val gen = s.generation ?: return
        val l = curLayout ?: return
        val sec = curSection
        val idx = curPageIdx
        val sectionChanged = before < 0 || ScrollWiring.section(before) != sec
        // A drag that stopped at a section still being laid out keeps its "불러오는 중…".
        if (stripSection < 0) cancelLoadingText()
        if (errorPanel.visibility != View.GONE) errorPanel.visibility = View.GONE
        val gesture = scrollGesture
        scrollGesture = false
        if (scrollCloseAtSettle) {
            // STEP: the chrome and the search hit leave with the release frame (one e-ink update per gesture).
            scrollCloseAtSettle = false
            if (chromeVisible) setChromeVisible(false)
            dropSearchHighlight()
        }
        if (perfTurnFrom != 0L) {
            if (kind == SettleKind.STEP) page.traceTurn(perfTurnFrom)
            perfTurnFrom = 0L
        }
        val p = l.pages.getOrNull(idx)
        val relayout = kind == SettleKind.RELAYOUT || kind == SettleKind.SWITCH
        if (pageChanged && !relayout) trackPage(p)
        val chapterIdx = s.chapters.indexAt(sec, p?.start ?: 0)
        val chapterChanged = if (s.chapters.size > 0) chapterIdx != lastChapterIdx else sectionChanged
        lastChapterIdx = chapterIdx
        val moved = movedPx != 0f || pageChanged
        when (kind) {
            // One cadence turn per step, release or fling; live SMOOTH frames never count.
            SettleKind.STEP, SettleKind.DRAG, SettleKind.FLING -> if (moved) onTurnShown(Nav.TURN, chapterChanged, l, idx)
            SettleKind.JUMP -> onTurnShown(Nav.JUMP, chapterChanged, l, idx)
            else -> {}
        }
        if (moved && (kind == SettleKind.DRAG || kind == SettleKind.FLING || (kind == SettleKind.STEP && gesture))) {
            clearJumpMark()
            endPeek(PeekRule.Event.SCROLL_SETTLE)
        }
        if (!relayout) schedulePositionSave()
        keeper.poke()
        if (chromeVisible) bindChrome() else returnNav.bind()
        if (kind == SettleKind.DRAG || kind == SettleKind.FLING) {
            // TTS restarts only when the spoken sentence left the screen (its navRestart checks currentPage).
            if (ttsSpeaking()) safely { tts?.onUserNavigated() }
            repeat(scrollScreens.add(movedPx, gen.geometry.contentHeight.toFloat())) { onManualTurn() }
        } else if (kind == SettleKind.STEP && gesture) {
            // A STEP release is a one-screen step of its own (taps and keys count theirs in userTurn).
            if (ttsSpeaking()) safely { tts?.onUserNavigated() }
            onManualTurn()
        }
        safely { selection?.onPageChanged() }
        if (ReaderPerf.turns) {
            val nav = when (kind) {
                SettleKind.OPEN -> Nav.OPEN
                SettleKind.JUMP -> Nav.JUMP
                SettleKind.RELAYOUT, SettleKind.SWITCH -> Nav.RELAYOUT
                else -> Nav.TURN
            }
            val started = if (kind == SettleKind.OPEN) openStartedAt else perfRequestAt
            val elapsed = if (started > 0L) SystemClock.uptimeMillis() - started else 0L
            Log.d(ReaderPerf.TAG, "show $nav s:${anchor.section} o:${anchor.offset} a:${anchor.offset} g:${gen.id} ${elapsed}ms")
        }
    }

    /** The top page changed (also mid-drag): the header / footer strings follow it ([buildDecor] reads cur*). */
    private fun onScrollTopChanged(section: Int, pageIndex: Int) {
        if (scrollFrozen) return
        val l = scroll?.layoutOf(section) ?: return
        curSection = section
        curLayout = l
        curPageIdx = pageIndex
    }

    /** A scroll stopped at a section that is not laid out: lay it out in the foreground ("불러오는 중…" after 300 ms). */
    private fun onScrollBlocked(section: Int) {
        val s = session ?: return
        if (stripSection == section && stripJob?.isActive == true) return
        stripJob?.cancel()
        stripSection = section
        scheduleLoadingText()
        val genId = s.generation?.id
        stripJob = scope.launch {
            val l = s.layout(section)
            // A rebuild (rotation, settings) completes waiting layouts with null: not a failure.
            if (session !== s || s.generation?.id != genId || scrollFrozen) return@launch
            if (stripSection == section) {
                stripSection = -1
                cancelLoadingText()
            }
            if (l == null && !s.isClosed) {
                // The viewport keeps the last known line; queued steps toward the failed section are dropped.
                scroll?.stopMotion()
                backlog.clear()
                toast("이 부분을 표시하지 못했습니다")
            }
        }
    }

    /** The search hit goes at the first step or drag; the scroll strips cache their highlights, so they are told. */
    private fun dropSearchHighlight() {
        val gone = ownerHighlights.remove(OWNER_SEARCH) ?: return
        scroll?.onHighlightsChanged(gone.first)
    }

    /** The virtual page (S §1.6) in scroll mode, after returning a long press's focus once its selection ended. */
    private fun vpage(): VirtualPage? {
        val sc = scroll ?: return null
        if (scrollFocusHeld && safely { selection?.isActive } != true) {
            scrollFocusHeld = false
            sc.clearFocus()
        }
        return sc.virtualPage()
    }

    /** Bookmarks inside the lines visible in the scroll viewport (ribbon, toggle). */
    private fun visibleBookmarks(sc: ScrollReader): List<Bookmark> {
        if (bookmarks.isEmpty()) return emptyList()
        val out = ArrayList<Bookmark>()
        sc.visibleRanges { sec, start, end ->
            for (bm in bookmarks) if (bm.section == sec && bm.offset >= start && bm.offset < end) out += bm
        }
        return out
    }

    /** The ribbon flag of the scroll decor (top-page changes, settles): no allocation per call. */
    private fun anyVisibleBookmark(sc: ScrollReader): Boolean {
        if (bookmarks.isEmpty()) return false
        bookmarkSeen = false
        sc.visibleRanges(bookmarkVisitor)
        return bookmarkSeen
    }

    private var bookmarkSeen = false
    private val bookmarkVisitor: (Int, Int, Int) -> Unit = { sec, start, end ->
        if (!bookmarkSeen) {
            val list = bookmarks
            for (i in list.indices) {
                val bm = list[i]
                if (bm.section == sec && bm.offset >= start && bm.offset < end) {
                    bookmarkSeen = true
                    break
                }
            }
        }
    }

    private fun sameHighlights(a: List<Highlight>?, b: List<Highlight>?): Boolean {
        if (a === b) return true
        if (a == null || b == null || a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x.start != y.start || x.end != y.end || x.kind != y.kind || x.style != y.style) return false
        }
        return true
    }

    /** ScrollReader's view of the reader (main thread). */
    private val scrollHost = object : ScrollReader.Host {
        override fun session(): BookSession? = session
        override fun renderer(): PageRenderer? = session?.let { s -> safely { s.renderer() } }
        override fun geometry(): PageGeometry? = session?.generation?.geometry
        // U §5.6: called at show, top-page change and settle (re-sampled there), in the step that invalidates.
        override fun decor(): PageDecor = buildDecor(sample = true).also { scrollDecor = it }

        /** Quotes and owner highlights of [section], merged and sorted by start (built once per section by the viewport). */
        override fun highlights(section: Int): List<Highlight> {
            // PLAN K2: the quotes that hold on this text (checked once per section, cached for the session).
            val quotes = if (quoteRows.isEmpty()) null else session?.let { s -> quotesFor(section, sectionText(s, section)) }
            var out: ArrayList<Highlight>? = null
            for ((sec, list) in ownerHighlights.values) {
                if (sec != section || list.isEmpty()) continue
                val o = out ?: ArrayList<Highlight>(quotes ?: emptyList()).also { out = it }
                o.addAll(list)
            }
            val merged: List<Highlight> = out ?: quotes ?: return emptyList()
            for (i in 1 until merged.size) {
                if (merged[i].start < merged[i - 1].start) return merged.sortedBy { it.start }
            }
            return merged
        }

        override fun unitGap(section: Int): Float {
            val s = session ?: return 0f
            val g = s.generation ?: return 0f
            if (!s.startsUnit(section)) return 0f
            if (g.id != scrollEmGen) {
                scrollEmGen = g.id
                scrollEmPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, g.settings.fontSizeSp, resources.displayMetrics)
            }
            return ScrollMath.CHAPTER_GAP_EM * scrollEmPx
        }

        override fun onTopPageChanged(section: Int, page: Int) = onScrollTopChanged(section, page)
        override fun onSettled(kind: SettleKind, movedPx: Float) = onScrollSettled(kind, movedPx)
        override fun onBlocked(section: Int) = onScrollBlocked(section)
    }

    /**
     * Shows the page containing [offset] of [section] ([pageIndex] ≥ 0 selects a page directly, -2 = last page).
     * Uses the cached layout when present; otherwise lays the section out (the old page stays visible, and
     * "불러오는 중…" only appears after 300 ms).
     */
    private fun navigateTo(section: Int, offset: Int, pageIndex: Int, kind: Nav, fraction: Float = Float.NaN) {
        val s = session ?: return
        if (ReaderPerf.turns) perfRequestAt = SystemClock.uptimeMillis()
        val sec = section.coerceIn(0, s.sectionCount - 1)
        navJob?.cancel()
        navJob = null
        pendingJump = if (kind == Nav.JUMP) PendingNav(sec, offset, pageIndex, fraction) else null
        val cached = if (layoutStale()) null else s.peek(sec)
        if (cached != null) {
            val tp = targetPage(cached, offset, pageIndex)
            if (!needsPreload(s, cached, tp)) {
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
        if (scrollWanted) {
            preloadScrollImages(s, l, pageIndex)
            return
        }
        if (!needsImageDecode(s, l, pageIndex)) return
        // A boundary prefetch may be decoding this very page: wait for it rather than decode the image twice.
        imagePrefetch?.let { if (it.isActive) it.join() }
        if (!needsImageDecode(s, l, pageIndex)) return
        val r = safely { s.renderer() } ?: return
        withContext(Dispatchers.IO) { runCatching { r.preload(l, pageIndex) } }
    }

    /**
     * Scroll (S §1.10): the first viewport can show the next page too (and the one above for a CONTEXT placement),
     * so their images are decoded before it is shown. Text-only pages cost one scan each and nothing else.
     */
    private suspend fun preloadScrollImages(s: BookSession, l: SectionLayout, pageIndex: Int) {
        if (!needsPreload(s, l, pageIndex)) return
        val from = (pageIndex - 1).coerceAtLeast(0)
        val to = (pageIndex + 1).coerceAtMost(l.pageCount - 1)
        imagePrefetch?.let { if (it.isActive) it.join() }
        // Decided on the main thread (like the paged check); only the decoding runs on IO.
        val due = (from..to).filter { needsImageDecode(s, l, it) }
        if (due.isEmpty()) return
        val r = safely { s.renderer() } ?: return
        withContext(Dispatchers.IO) { for (i in due) runCatching { r.preload(l, i) } }
    }

    /** [preloadImages] has work to do: the page itself, and in scroll mode also the pages above and below it. */
    private fun needsPreload(s: BookSession, l: SectionLayout, pageIndex: Int): Boolean {
        if (!scrollWanted) return needsImageDecode(s, l, pageIndex)
        for (i in (pageIndex - 1).coerceAtLeast(0)..(pageIndex + 1).coerceAtMost(l.pageCount - 1)) {
            if (needsImageDecode(s, l, i)) return true
        }
        return false
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
            showError("페이지를 나누지 못했습니다")
        } else {
            // The old page stays: the return chip / strip (a return jump binds them with its page) match it again.
            if (chromeVisible) bindChrome() else returnNav.bind()
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
                // The mode the page is shown in (showPage attaches / detaches the viewport for a pending switch).
                val pageStart = kind == Nav.TURN || (kind == Nav.JUMP && !scrollWanted)
                showPage(sec, l, l.pageForOffset(off), kind, anchorOffset = if (pageStart) -1 else off)
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
        // Settles a running drag first: the anchor is the line on top now (it already is after onViewSizeChanged).
        scroll?.stopMotion()
        if (ReaderPerf.turns) perfRequestAt = SystemClock.uptimeMillis()
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
            // The page the anchor opened (A §5.5).
            val idx = AnchorMath.pageFor(l, off)
            // New image sizes after a font / size change: decode them here, not in onDraw.
            preloadImages(s, l, idx)
            if (session !== s) return@launch
            endNavJob(coroutineContext[Job])
            showPage(sec, l, idx, Nav.RELAYOUT, anchorOffset = off)
            flushTurns()
        }
    }

    // While the finger moves or a scroll settles, TTS (the only extra calling these) never moves the text (S §1.10).
    override fun nextPage(): Boolean {
        if (scroll?.userMoving() == true) return true
        return turn(true).also { if (it && !inFront) turnedInBackground = true }
    }

    override fun prevPage(): Boolean {
        if (scroll?.userMoving() == true) return true
        return turn(false).also { if (it && !inFront) turnedInBackground = true }
    }

    /**
     * One page forward / back. Inside the section (and into a laid-out neighbour) the page shows synchronously, so
     * turns keep up with the fastest taps. While a layout is pending (a section being laid out, a jump, a relayout)
     * the turn is counted instead and applied together with the others when that layout shows ([flushTurns]): no
     * turn is dropped and none is replayed one draw at a time. False at the first / last page of the book.
     */
    private fun turn(next: Boolean): Boolean {
        val s = session ?: return false
        val l = curLayout ?: return false
        // Scroll: a step waiting for its section holds the turns after it here too (S §1.10), one flush when it shows.
        if (navJob?.isActive == true || scroll?.pending == true) {
            backlog.add(next)
            return true
        }
        if (layoutStale()) return false
        scroll?.let { return scrollTurn(it, next) }
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
     * One screen in scroll mode: instant on every device (no page-turn animation); the cut line becomes the top line.
     * A section that is not laid out yet keeps the step waiting in the viewport until it arrives ([onScrollBlocked]);
     * the turns after it wait in [backlog] ([turn]) and are flushed by its settle. False at the book's first / last line.
     */
    private fun scrollTurn(sc: ScrollReader, next: Boolean): Boolean = when (sc.step(next)) {
        Step.MOVED, Step.NEED_SECTION -> true
        Step.EDGE -> false
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
        scroll?.let {
            // A step waiting for its section keeps the turns queued: its settle flushes them ([onScrollSettled]).
            if (!it.pending) flushScrollTurns(it, backlog.take())
            return
        }
        val n = backlog.take()
        val walk = TurnMath.walk(curSection, curPageIdx, n, s.sectionCount) { sec ->
            when {
                sec == curSection -> l.pageCount
                else -> s.peek(sec)?.pageCount ?: if (s.counts.isKnown(sec)) s.counts.pages(sec) else -1
            }
        }
        // Kept before navigating: a synchronous display of the next section flushes the rest right away.
        backlog.restore(walk.remaining)
        if (walk.section == curSection) {
            if (walk.pageIndex != curPageIdx) showPage(curSection, l, walk.pageIndex, Nav.TURN)
        } else {
            navigateTo(walk.section, 0, walk.pageIndex, Nav.TURN)
        }
        // After the move: turns past the last page open the end panel from the last page.
        if (walk.hitEdge) edgeReached(n > 0)
    }

    /**
     * S §1.10: up to [ScrollWiring.MAX_FLUSH_STEPS] queued steps at once, one draw at the end (the invalidates of one
     * task make one frame) and one bookkeeping pass for the screen drawn (one cadence turn, save, chrome bind). A step
     * that waits for a section keeps the steps not applied yet in [backlog]. [before] is the top page at the settle
     * before the flush; [carriedPx] the pixels of a step that settled just before it ([onScrollSettled]), else NaN.
     */
    private fun flushScrollTurns(sc: ScrollReader, n: Int, before: Long = scrollSettledTop, carriedPx: Float = Float.NaN) {
        val next = n > 0
        val steps = ScrollWiring.flushSteps(n)
        flushSettled = !carriedPx.isNaN()
        flushMovedPx = if (carriedPx.isNaN()) 0f else carriedPx
        var edge = false
        scrollFlushing = true
        try {
            for (i in 0 until steps) {
                val r = sc.step(next)
                if (r == Step.EDGE) {
                    edge = true
                    break
                }
                if (r == Step.NEED_SECTION) {
                    val left = ScrollWiring.flushLeft(steps, i, sc.pending)
                    backlog.restore(if (next) left else -left)
                    break
                }
            }
        } finally {
            scrollFlushing = false
        }
        if (flushSettled) scrollBookkeeping(SettleKind.STEP, flushMovedPx, before, scrollSettledTop != before)
        if (edge) edgeReached(next)
    }

    override fun goTo(pos: DocPosition, remember: Boolean) {
        if (session == null) return
        scroll?.let { sc ->
            // TTS: never yank the text from under a moving finger.
            if (!remember && sc.userMoving()) return
            // A sentence already wholly on screen (e.g. below a section seam) causes no motion: its section is the
            // virtual page until the next settle (currentPosition / currentPage describe it; no redraw, S §1.10).
            if (!remember && ttsSpeaking() && sc.lineWhollyVisible(pos.section, pos.offset)) {
                sc.focusSection(pos.section)
                return
            }
        }
        // T1-11: only TTS's follow (remember = false) turns pages behind a paused reader; a user's remembered jump
        // delivered before onResume (a note from the notes hub) is not one, so no extra full refresh on resume.
        if (!inFront && !remember) turnedInBackground = true
        // A "jump" to the page already shown (e.g. the current chapter in the TOC) is not worth a return point.
        if (remember && curLayout != null && !isOnCurrentPage(pos)) returnNav.onJump(currentPosition())
        jumpTo(pos.section, pos.offset, -1)
    }

    /** An explicit jump (TOC, link, 페이지 이동, seek bar, return point): turns still pending belong to the page left. */
    private fun jumpTo(section: Int, offset: Int, pageIndex: Int, fraction: Float = Float.NaN) {
        // N §6.2 / §6.4: the user went elsewhere (a remembered jump, the return point): reading resumes normally.
        endPeek(PeekRule.Event.USER_JUMP)
        anchorJob?.cancel()
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
        val here = p != null && isOnCurrentPage(pos) && currentPosition().section == pos.section && p.start >= pos.offset
        if (curLayout != null && !here) returnNav.onJump(currentPosition())
        jumpTo(pos.section, pos.offset, PAGE_AT_OR_AFTER, f)
    }

    private fun isOnCurrentPage(pos: DocPosition): Boolean {
        scroll?.let { return !layoutStale() && it.lineWhollyVisible(pos.section, pos.offset) }
        val l = curLayout ?: return false
        val p = currentPage ?: return false
        return pos.section == curSection && !layoutStale() && onPage(pos.offset, p, curPageIdx == l.pageCount - 1)
    }

    /** Jump to a page of a section (seek bar, 페이지 이동): one exact draw, laid out first when needed. */
    override fun goToPage(section: Int, pageIndex: Int, remember: Boolean) {
        if (session == null) return
        // Scroll: the text may sit mid-page, so the jump (to that page's start) always counts as one.
        val here = scroll == null && section == curSection && pageIndex == curPageIdx && !layoutStale()
        if (remember && curLayout != null && !here) returnNav.onJump(currentPosition())
        jumpTo(section, 0, pageIndex.coerceAtLeast(0))
    }

    /** Page turn requested by the user (tap, swipe, key, wheel). False when nothing turned (the book's first / last page). */
    private fun userTurn(next: Boolean): Boolean {
        if (session == null || curLayout == null) return false
        dropSearchHighlight()
        clearJumpMark()
        anchorJob?.cancel()
        // "turn N ms" starts at the input event that asked for this turn (the latest one: all run on this thread).
        if (ReaderPerf.turns) perfTurnFrom = maxOf(page.lastInputAt, keyInputAt)
        // A key, tap or remote turn is no drag: flags left by a STEP release that hit the book's edge (no settle) must
        // not make this step's settle count as a gesture. A finger still dragging (or its step waiting) keeps them.
        scroll?.let { sc ->
            // A key during a fling stops the text where it is first (that settle ends the gesture), then turns from
            // there; a turn never lands in the backlog behind a fling's empty-section walk.
            if (sc.flinging) sc.stopMotion()
            if (!sc.userMoving()) {
                scrollGesture = false
                scrollCloseAtSettle = false
            }
        }
        val ok = turn(next)
        if (!ok) perfTurnFrom = 0L
        if (ok) endPeek(PeekRule.Event.MANUAL_TURN)
        if (ok) onManualTurn()
        if (ttsSpeaking()) safely { tts?.onUserNavigated() }
        if (!ok && navJob?.isActive != true && !layoutStale()) edgeReached(next)
        return ok
    }

    /** "Next" on the book's last page opens the end panel (T1-2); "previous" on the first page says so. */
    private fun edgeReached(next: Boolean) {
        if (next) showBookEnd() else firstPageToast()
    }

    private fun firstPageToast() {
        val now = SystemClock.uptimeMillis()
        if (now - edgeToastAt > 2000) {
            edgeToastAt = now
            toast("첫 페이지입니다")
        }
    }

    /**
     * Full e-ink refresh cadence for a shown turn / jump. A refresh that falls due while pages are being flipped fast
     * waits (and keeps waiting while the flipping goes on): a flash between two quick turns stalls the panel.
     */
    private fun onTurnShown(kind: Nav, chapterChanged: Boolean, layout: SectionLayout, pageIndex: Int) {
        // T1-3c, opt-in: the picture share is only looked at when "그림 있는 쪽에서 새로고침" is on.
        val imageDue = app.einkFlashImages && cadence.imageDue(imageCoverage(layout, pageIndex))
        val due = cadence.onTurn(chapterChanged, imageDue)
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

    /** A page turn the reader made (not auto turn / TTS): two of them after a jump retire the return chip. */
    private fun onManualTurn() = returnNav.onManualTurn()

    private fun imageCoverage(layout: SectionLayout, pageIndex: Int): Float = try {
        ImageCoverage.of(layout, pageIndex)
    } catch (t: Throwable) {
        0f
    }

    /**
     * The previous / next chapter from the current page (다음 화 / 이전 화: keys, tap zones, the chrome's buttons).
     * Sequential navigation: no return point (remember = false).
     */
    private fun jumpChapter(next: Boolean) {
        if (scroll != null) {
            // One frame of reference: the virtual page, else (no wholly visible line) the top page.
            val vp = vpage()
            val tp = curLayout?.pages?.getOrNull(curPageIdx)
            val sec = vp?.section ?: curSection
            val start = vp?.page?.start ?: tp?.start ?: return
            val end = vp?.page?.end ?: tp?.end ?: return
            val pi = ScrollWiring.chapterPageIndex(start, vp?.pageIndex ?: curPageIdx)
            // A user action: the private jump, never the ReaderHost goTo that is held back during motion.
            chapterTarget(sec, pi, start, end, next)?.let { jumpTo(it.section, it.offset, -1) }
            return
        }
        val p = currentPage ?: return
        chapterTarget(curSection, curPageIdx, p.start, p.end, next)?.let { goTo(it, remember = false) }
    }

    /**
     * Where 다음 화 / 이전 화 goes from the page [pageIndex] ([start]..[end]) of [section]: the next chapter's start, or
     * the start of the chapter the page is in (the previous one's when the page is that start); without a TOC the
     * next / this / previous section. Null when there is nowhere to go.
     */
    private fun chapterTarget(section: Int, pageIndex: Int, start: Int, end: Int, next: Boolean): DocPosition? {
        val s = session ?: return null
        val ch = s.chapters
        if (ch.size == 0) {
            val target = when {
                next -> section + 1
                pageIndex > 0 -> section
                else -> section - 1
            }
            return if (target in 0 until s.sectionCount) DocPosition(target, 0) else null
        }
        val idx = if (next) ch.nextAfter(section, maxOf(end - 1, start)) else ch.lastBefore(section, start)
        return when {
            idx >= 0 -> ch.position(idx)
            !next && (section > 0 || pageIndex > 0) -> DocPosition.START
            else -> null
        }
    }

    // ================================================================== decor (highlights, bookmark, status slots)

    /**
     * The decor of the page on screen: its highlights (a list only when one overlaps), the bookmark ribbon and the
     * status slots. [sample] re-reads the clock and the battery: only for a page shown (turn, jump, open, relayout)
     * and on resume; background refreshes reuse the last sample, so they redraw only when their own data changed.
     *
     * Mutation invariant (U §5.3): this updates the one shared [StatusModel.decor] the frame on screen also points
     * to, so it may only run in the main-thread step that installs the new frame (callers check for a stale frame
     * first).
     */
    private fun buildDecor(sample: Boolean = false): PageDecor {
        val s = session ?: return PageDecor()
        val l = curLayout ?: return PageDecor()
        val p = l.pages.getOrNull(curPageIdx) ?: return PageDecor()
        var hl: ArrayList<Highlight>? = null
        // Scroll: the strips carry their own highlights (isBookmarked: a bookmark anywhere on screen); the status uses
        // the anchor line (fillStatus), so one PageDecor serves both modes.
        if (scroll == null) {
            // Guarded: an empty map costs no key boxing or iterator on a turn.
            if (quoteRows.isNotEmpty()) hl = addOverlapping(hl, quotesFor(curSection, l.content.text), p)
            if (ownerHighlights.isNotEmpty()) {
                for ((sec, list) in ownerHighlights.values) if (sec == curSection) hl = addOverlapping(hl, list, p)
            }
        }
        fillStatus(s, l, p, statusInputs, sample, all = false)
        status.update(s.settings, statusInputs, statusTrackPx(s), s.generation?.geometry?.cutoutTop ?: 0)
        return PageDecor(hl ?: emptyList(), isBookmarked(l, p), status.decor, status.decor.version)
    }

    private fun addOverlapping(into: ArrayList<Highlight>?, list: List<Highlight>, p: PageInfo): ArrayList<Highlight>? {
        var out = into
        for (i in list.indices) {
            val h = list[i]
            if (h.end > h.start && h.end > p.start && h.start < p.end) (out ?: ArrayList<Highlight>().also { out = it }) += h
        }
        return out
    }

    /**
     * Fills [inp] for page [p] of [l] (the page on screen): only the inputs of the items a slot shows ([all] = every
     * item, for [statusSample]), and the progress line's position when it is on. O(1) per item except the chapter
     * lookup (O(log C)); allocates nothing for the items themselves (but the boxed section key of [chapterStart]).
     */
    private fun fillStatus(s: BookSession, l: SectionLayout, p: PageInfo, inp: StatusInputs, sample: Boolean, all: Boolean) {
        val st = s.settings
        val c = s.counts
        // U §5.6: scroll mode reads the anchor line (the first half-visible one, set at settle): the real paged page
        // holding it, its char progress (1 at atBookEnd()) and whether it starts the chapter. Paged: the page shown.
        var lay = l
        var sec = curSection
        var pageIdx = curPageIdx
        var at = p.start
        val sc = scroll
        // While a finger or fling moves, every item follows the live top page (cur*), as progress() and the pages
        // left do; the exact anchor line comes back at the settle.
        if (sc != null && !layoutStale() && !sc.userMoving()) {
            val a = sc.anchor()
            val asec = ScrollWiring.section(a)
            sc.layoutOf(asec)?.let { al ->
                lay = al
                sec = asec
                at = ScrollWiring.offset(a).coerceIn(0, al.content.length)
                pageIdx = al.pageForOffset(at)
            }
        }
        val lastOfBook = if (sc != null) sc.atBookEnd() else curSection == s.sectionCount - 1 && curPageIdx == l.pageCount - 1
        if (all || st.shows(StatusItem.PAGE)) {
            inp.page = c.globalPage(sec, pageIdx)
            inp.total = c.total()
        }
        if (all || st.shows(StatusItem.PERCENT)) inp.percent = ReaderFormat.percent(progress())
        inp.bar = if (st.progressBar) (if (lastOfBook) 1f else c.charProgress(sec, at)) else -1f
        val chapterShown = all || st.shows(StatusItem.CHAPTER)
        val episodeShown = all || st.shows(StatusItem.EPISODE)
        if (chapterShown || episodeShown) {
            val ch = s.chapters
            val idx = ch.indexAt(sec, at)
            if (chapterShown) {
                inp.chapterTitle = chapterTitle(s, idx, sec)
                // U polish 16: the page that begins the chapter does not repeat its title above it.
                inp.chapterStartsHere = idx >= 0 && ch.section(idx) == sec && ch.offset(idx) == at
            }
            if (episodeShown) fillEpisode(s, idx, inp)
        }
        if (all || st.shows(StatusItem.BOOK_TITLE)) inp.bookTitle = bookRef?.title
        if (all || st.shows(StatusItem.CHAPTER_PAGES_LEFT)) chapterPage(s, lay, sec, pageIdx, inp)
        if (all || st.shows(StatusItem.TIME_LEFT_EPISODE)) inp.minutesEpisode = minutesLeftOrNone(false)
        if (all || st.shows(StatusItem.TIME_LEFT_BOOK)) inp.minutesBook = minutesLeftOrNone(true)
        val clockShown = all || st.shows(StatusItem.CLOCK) || st.shows(StatusItem.CLOCK_BATTERY)
        if (!clockShown) {
            // Read again when a slot shows it next (never an old time drawn by a repaint).
            inp.minuteOfDay = -1
        } else if (sample || inp.minuteOfDay < 0) {
            inp.minuteOfDay = minuteOfDay()
            inp.is24 = clock24
        }
        val batteryShown = all || st.shows(StatusItem.BATTERY) || st.shows(StatusItem.CLOCK_BATTERY)
        if (!batteryShown) inp.battery = -1 else if (sample || inp.battery < 0) inp.battery = battery()
    }

    /**
     * The 회차 inputs (T1-5) for chapter [chapterIdx]: unknown (-1, an empty slot) until the episodes are parsed, before
     * the first numbered one, or outside the TOC.
     */
    private fun fillEpisode(s: BookSession, chapterIdx: Int, inp: StatusInputs) {
        inp.epNumbered = false
        inp.epNumber = -1
        inp.epMax = -1
        inp.tocIndex = -1
        inp.tocCount = 0
        val shown = epShown
        if (shown == null) {
            // Turned on while reading (the popup): ask now; the item appears once they are parsed.
            scheduleEpisodes()
            return
        }
        if (chapterIdx < 0) return
        val toc = s.chapters.tocIndex(chapterIdx)
        if (toc !in shown.indices) return
        if (epNumbered && shown[toc] <= 0) return
        inp.epNumbered = epNumbered
        inp.epNumber = shown[toc]
        inp.epMax = epMax
        inp.tocIndex = toc
        inp.tocCount = shown.size
    }

    /** Pixels the progress dot travels (ProgressMath over the progress line's own lane); 0 without the line. */
    private fun statusTrackPx(s: BookSession): Int {
        if (!s.settings.progressBar) return 0
        val g = s.generation?.geometry ?: return 0
        val density = resources.displayMetrics.density
        return ProgressMath.trackPx(g.viewWidth, StatusFit.lanePx(density).toFloat(), density)
    }

    override fun statusSample(item: StatusItem): String? {
        val s = session ?: return null
        val l = curLayout ?: return null
        val p = l.pages.getOrNull(curPageIdx) ?: return null
        if (layoutStale()) return null
        // A scratch copy: the chooser's samples must never touch the page's shared decor.
        val inp = StatusInputs()
        fillStatus(s, l, p, inp, sample = true, all = true)
        return status.sample(item, inp)
    }

    /**
     * Rebuilds the decor of the page on screen (highlights/bookmarks/status changed) and redraws. [sample]: also
     * re-read the clock and battery ([onResume] only; see [buildDecor]).
     */
    private fun refreshDecor(onlyIfChanged: Boolean = false, sample: Boolean = false) {
        scroll?.let { sc ->
            // The viewport's decor is rebuilt by its own call to scrollHost.decor() (in this same step).
            if (scrollFrozen || layoutStale()) return
            val last = scrollDecor
            if (onlyIfChanged && last != null && sameDecor(buildDecor(sample), last)) return
            sc.onDecorChanged()
            return
        }
        val f = page.frame ?: return
        // Before buildDecor: a stale frame (or one a pending relayout replaces) must not have the shared status decor
        // changed under it.
        if (f.layout !== curLayout || f.pageIndex != curPageIdx || layoutStale()) return
        val d = buildDecor(sample)
        if (onlyIfChanged && sameDecor(d, f.decor)) return
        page.frame = PageFrame(f.renderer, f.layout, f.pageIndex, f.left, f.top, d)
        page.invalidate()
    }

    private fun sameDecor(a: PageDecor, b: PageDecor): Boolean = DecorDiff.same(a, b)

    private fun chapterTitle(section: Int, offset: Int): String? {
        val s = session ?: return null
        return chapterTitle(s, s.chapters.indexAt(section, offset), section)
    }

    /** Title of chapter [i] (ChapterIndex), else of [section], else of the book. */
    private fun chapterTitle(s: BookSession, i: Int, section: Int): String? {
        if (i >= 0) return s.chapters.title(i)
        return s.document.sections.getOrNull(section)?.title ?: bookRef?.title
    }

    private fun isBookmarked(l: SectionLayout, p: PageInfo): Boolean {
        scroll?.let { return anyVisibleBookmark(it) }
        val last = curPageIdx == l.pageCount - 1
        val list = bookmarks
        for (i in list.indices) if (list[i].section == curSection && onPage(list[i].offset, p, last)) return true
        return false
    }

    private fun onPage(offset: Int, p: PageInfo, lastPage: Boolean): Boolean =
        offset >= p.start && (offset < p.end || (lastPage && offset == p.end) || p.start == p.end && offset == p.start)

    /**
     * R2 "2/32": page [pageIdx] of [sec] (laid out as [l]) within its chapter, the chapter of the page's first
     * character. Before the first TOC entry the front matter counts from page 1; without a TOC both stay −1 (an
     * empty slot). Cached layouts and the page estimates only: no IO, O(C) like the chapter title.
     */
    private fun chapterPage(s: BookSession, l: SectionLayout, sec: Int, pageIdx: Int, inp: StatusInputs) {
        inp.chapterPage = -1
        inp.chapterPages = -1
        val ch = s.chapters
        val start = l.pages.getOrNull(pageIdx)?.start ?: return
        if (ch.size == 0) return
        val c = s.counts
        val idx = ch.indexAt(sec, start)
        val next = ch.nextAfter(sec, start)
        val first = if (idx < 0) 1 else chapterStart(s, l, sec, idx)
        val end = if (next < 0) c.total() + 1 else chapterStart(s, l, sec, next)
        inp.setChapterPage(c.globalPage(sec, pageIdx), first, end)
    }

    /**
     * [PageCounts.chapterStart] of TOC entry [i]: its section's layout when cached, else the estimate. An entry in
     * another section is looked up in [BookSession.peek] (an Int-keyed HashMap: a section number above 127 is boxed,
     * one small object, at most twice per turn); an entry in [sec] uses [l] and allocates nothing.
     */
    private fun chapterStart(s: BookSession, l: SectionLayout, sec: Int, i: Int): Int {
        val c = s.counts
        val ns = s.chapters.section(i)
        val no = s.chapters.offset(i)
        val target = if (ns == sec) l else s.peek(ns)
        if (target == null) return c.chapterStart(ns, if (no == 0) 0 else c.estimatePageIndex(ns, no), no == 0)
        val tIdx = target.pageForOffset(no)
        return c.chapterStart(ns, tIdx, target.pages.getOrNull(tIdx)?.start == no)
    }

    /**
     * Minutes since local midnight, without a Calendar: the zone and the 12/24 h format are read once after each
     * [onResume] (they can only change while the reader is away).
     */
    private fun minuteOfDay(): Int {
        val zone = clockZone ?: TimeZone.getDefault().also {
            clockZone = it
            clock24 = DateFormat.is24HourFormat(this)
        }
        val now = System.currentTimeMillis()
        return StatusClock.minuteOfDay(now, zone.getOffset(now))
    }

    /** Battery percent from the sticky broadcast, read at most once a minute (on page turns only). */
    private fun battery(): Int {
        val now = SystemClock.uptimeMillis()
        if (batteryLevel >= 0 && now - batteryAt < 60_000) return batteryLevel
        batteryAt = now
        batteryLevel = try {
            val i = registerReceiver(null, batteryFilter)
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
        if (scroll?.atBookEnd() == true) return 1f
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
    // Scroll mode: the virtual page (S §1.6), i.e. the wholly visible lines of the focus section.
    override val currentLayout: SectionLayout?
        get() {
            if (scroll != null) return vpage()?.layout
            return curLayout
        }
    override val currentPageIndex: Int
        get() {
            if (scroll != null) return vpage()?.pageIndex ?: curPageIdx
            return curPageIdx
        }
    override val currentPage: PageInfo?
        get() {
            if (scroll != null) return vpage()?.page
            return curLayout?.pages?.getOrNull(curPageIdx)
        }
    override val pageView: View get() = page

    override fun currentPosition(): DocPosition {
        if (scroll != null) return vpage()?.let { DocPosition(it.section, it.page.start) } ?: anchor
        return currentPage?.let { DocPosition(curSection, it.start) } ?: anchor
    }

    override fun pageLabel(pos: DocPosition): String {
        val s = session ?: return ""
        return ReaderFormat.pageLabel(globalPageOf(pos), s.counts.total())
    }

    override fun totalPagesKnown(): Boolean = session?.counts?.isComplete == true

    override fun setHighlights(owner: String, section: Int, highlights: List<Highlight>) {
        if (owner == OWNER_QUOTES) {
            // A quote was added, recoloured or deleted. The contents dialog / selection send the section's quotes by
            // the same K2 rule (QuoteRows.placeChanged, with this reader's anchor check: quoteAnchorMatch) with their
            // colours: drawn now (one update). The rows come from QuoteCache, which they fill first (a quote still
            // being inserted is only in [highlights]): no database read here, which would race their own write and
            // take the quote off the page again before their completion draws it back (2–3 e-ink updates).
            val s = session
            if (s != null && quoteCheckSession !== s) resetQuoteChecks(s)
            quoteEdits++
            bookRef?.let { b -> QuoteCache.get(b.id) }?.let { all ->
                val next = all.groupBy { it.section }
                for (sec in quoteRows.keys + next.keys) {
                    if (sec == section || quoteRows[sec] == next[sec]) continue
                    // Another section's rows changed meanwhile: checked again when its decor needs it.
                    bumpThumbDecor(sec)
                    quotesBySection.remove(sec)
                    scroll?.onHighlightsChanged(sec)
                }
                quoteRows = next
            }
            bumpThumbDecor(section)
            // A section the reader has no text for is checked when it is laid out (from the rows, not this list).
            if (s != null && sectionText(s, section) != null) quotesBySection[section] = highlights
            else quotesBySection.remove(section)
            // Scroll: the strips cache their quotes (rebuilt here, in the same step as the redraw).
            scroll?.onHighlightsChanged(section)
            refreshDecor(onlyIfChanged = true)
            if (chromeVisible) bindChrome()
            return
        }
        // Scroll: the section this owner marked before (its strips are rebuilt once the map is updated).
        val left = if (scroll == null) -1 else ownerHighlights[owner]?.first ?: -1
        if (highlights.isEmpty()) ownerHighlights.remove(owner) else ownerHighlights[owner] = section to highlights
        scroll?.let { sc ->
            if (left >= 0 && left != section) sc.onHighlightsChanged(left)
            sc.onHighlightsChanged(section)
        }
        refreshDecor(onlyIfChanged = true)
    }

    /**
     * [settings] are the GLOBAL reading settings (R2): saved as they are, and applied to this book merged with its own
     * TXT options ([applyToSession]). Never the session's effective settings, which would save this book's override
     * for every book.
     */
    override fun applySettings(settings: ReaderSettings) {
        Settings.saveReader(settings)
        applyToSession(settings)
    }

    /**
     * Applies [global] merged with this book's TXT options ([withTxt]) to the open book: a re-parse when this
     * book's parse options changed, else a relayout, a repaint or nothing. [onApplied] runs once the book shows the
     * result (right away when nothing had to be re-parsed).
     */
    private fun applyToSession(global: ReaderSettings, onApplied: (() -> Unit)? = null) {
        // The book is about to be re-opened with a new encoding: a re-parse with the old one would be thrown away.
        if (recreatePending) return
        applyReaderColors(global)
        applyCadence(global)
        val s = session
        val b = bookRef
        if (s == null || b == null) return
        val eff = global.withTxt(bookOverride)
        val before = readerTarget ?: s.settings
        readerTarget = eff
        // Only the options this book's parser reads: a TXT option changed while reading an EPUB (or the EPUB
        // publisher styles in a TXT) is saved for the other books but never re-opens this one.
        if (LayoutKeys.parseChanged(before, eff, s.document.format, b.encoding)) {
            reopenDocument(eff, onApplied)
            return
        }
        if (reopening) {
            // Same parse options as the re-parse that is running: it takes [eff] when it shows its page.
            if (onApplied != null) reopenDone += onApplied
            return
        }
        // Settles a running drag first, so the rebuild keeps the line on top now (A §5.5).
        scroll?.stopMotion()
        when (s.updateSettings(eff, keepHere())) {
            BookSession.Change.NONE -> {}
            BookSession.Change.REPAINT -> { thumbPaintVersion++; repaint() }
            BookSession.Change.RELAYOUT -> {
                onNewGeneration()
                relayout()
            }
        }
        onApplied?.invoke()
    }

    /** Same layout, new colours/footer items: redraw with a renderer for the new settings. */
    private fun repaint() {
        val s = session ?: return
        scroll?.let {
            if (!scrollFrozen && !layoutStale()) it.onDecorChanged()
            return
        }
        val f = page.frame ?: return
        if (layoutStale() || f.layout !== curLayout || f.pageIndex != curPageIdx) return
        page.frame = PageFrame(s.renderer(), f.layout, f.pageIndex, f.left, f.top, buildDecor())
        page.invalidate()
    }

    /**
     * Parse options changed: open the document again and return to the same place (by ratio if sections moved).
     * [onApplied] (and those of re-parses this one replaces) run once its page shows.
     */
    private fun reopenDocument(newSettings: ReaderSettings, onApplied: (() -> Unit)? = null) {
        val old = session ?: return
        val b = bookRef ?: return
        // Settles a running drag first, so the anchor is the line on top now.
        scroll?.stopMotion()
        val pos = anchor
        // A §5.5: the visible text at the anchor, found again near it in the new parse.
        val needle = old.peek(pos.section)?.let { TextRefind.snippet(it.content.text, pos.offset) }
        val oldCount = old.sectionCount
        val ratio = old.counts.charProgress(pos.section, pos.offset)
        if (onApplied != null) reopenDone += onApplied
        reopening = true
        navJob?.cancel()
        openJob?.cancel()
        openingBytes = b.sizeBytes
        openingPath = b.path
        scheduleLoadingText()
        openJob = scope.launch {
            val job = coroutineContext[Job]
            var doc: BookDocument? = null
            var fresh: BookSession? = null
            var adopted = false
            try {
                val d = withContext(Dispatchers.IO) {
                    Documents.open(File(b.path), newSettings.parseOptions(b.encoding)).also { doc = it }
                }
                if (d.sections.isEmpty()) throw DocumentException("내용이 없는 책입니다")
                // Layout-only changes made while parsing (same parse options) are taken along.
                val use = readerTarget?.takeIf { !LayoutKeys.parseChanged(newSettings, it, d.format, b.encoding) } ?: newSettings
                val s = BookSession(this@ReaderActivity, b, d, use)
                fresh = s
                s.listener = sessionListener
                val target = if (s.sectionCount == oldCount) pos else s.counts.locateFraction(ratio)
                val sec = target.section.coerceIn(0, s.sectionCount - 1)
                val (vw, vh) = pageTargetSize()
                // The needle is text of the old anchor's section: searched only where that section still is.
                s.setViewport(vw, vh, pageCutoutTop, AnchorSpec(sec, target.offset, if (target === pos && sec == pos.section) needle else null))
                val l = s.layout(sec)
                if (l != null) {
                    val at = if (l.anchorBreak >= 0) l.anchorBreak else target.offset.coerceIn(0, l.content.length)
                    preloadImages(s, l, AnchorMath.pageFor(l, at))
                }
                if (session !== old) return@launch
                if (l == null) {
                    // Keep reading the old parse rather than showing nothing.
                    readerTarget = old.settings
                    reopenDone.clear()
                    toast("새 설정으로 책을 표시하지 못했습니다")
                    return@launch
                }
                // The pin's place in the old parse (its counts), found again in the new one below (U §3.5 item 8).
                val markF = returnNav.markFraction()
                onNewGeneration()
                thumbs?.close(); thumbs = null
                thumbDecorVersions = IntArray(0)
                session = s
                adopted = true
                // The open's afterOpen has not run yet (this re-parse cancelled the open, or replaced its session
                // before the first good draw): it goes with this session's first page.
                if (afterOpenPending) page.afterFirstFrame = afterFirstPage
                reopening = false
                old.close()
                // The new parse has its own TOC: the footer's episode numbers are parsed again.
                handler.removeCallbacks(askEpisodes)
                episodesPending = false
                episodesAsked = false
                epShown = null
                safely { tts?.stop() }
                safely { selection?.clear() }
                // The search-results bar holds hits in the old parse's coordinates (the settings popup that started
                // this re-open stays open: only the bar goes).
                safely { ReaderPanels.closeSearchBar(this@ReaderActivity) }
                ownerHighlights.clear()
                returnNav.reparsed(markF, exact = d.format == BookFormat.EPUB && s.sectionCount == oldCount)
                backlog.clear()
                lastChapterIdx = Int.MIN_VALUE
                // Where the needle was found again (the anchored break), else the estimate.
                val off = if (l.anchorBreak >= 0) l.anchorBreak else target.offset.coerceIn(0, l.content.length)
                // Positions saved from now on are in the new parse's coordinates.
                writeTextPosition(b, s, DocPosition(sec, off))
                val (nw, nh) = pageTargetSize()
                // A layout change made after this session was built (settings) or a resize that went to the old one:
                // lay the target out again instead of showing a stale layout.
                val want = readerTarget
                val keep = AnchorSpec(sec, off)
                val changed = want != null && want != s.settings && s.updateSettings(want, keep) != BookSession.Change.NONE
                if (s.setViewport(nw, nh, pageCutoutTop, keep) || changed) {
                    anchor = DocPosition(sec, off)
                    relayout()
                } else {
                    showPage(sec, l, AnchorMath.pageFor(l, off), Nav.JUMP, anchorOffset = off)
                    s.startCounting(COUNT_DELAY_MS)
                }
                if (s.settings.shows(com.ggumtak.readeraplus.settings.StatusItem.EPISODE)) scheduleEpisodes()
                val done = ArrayList(reopenDone)
                reopenDone.clear()
                for (f in done) safely { f() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "reopen failed", t)
                cancelLoadingText()
                if (session === old) readerTarget = old.settings
                reopenDone.clear()
                val why = ReaderFormat.openError(t)
                toast(if (why == ReaderFormat.OPEN_FAILED) "책을 다시 불러오지 못했습니다" else why)
            } finally {
                if (openJob === job) reopening = false
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
        chrome.setVisible(v)
        // The chrome is an overlay: the page keeps its size. The floating chip hides under the open menu (whose strip
        // shows the same places) and comes back when it closes.
        if (v) returnNav.onChromeShown() else returnNav.onChromeHidden()
        if (v) bindChrome()
        updateChipPosition()
    }

    /**
     * The page view's top/bottom margins: the system-bar insets, nothing else (U §2.6, C18). A top inset that only a
     * display cutout takes (fullscreen on the S25: no bar shown there) is no margin: the view reaches into the camera
     * band (its paper and a bookmark ribbon go there), and the session lays the header's band and the text box out
     * below the band ([pageCutoutTop], LayoutKeys.geometry), as when the view started below it: the camera band is left
     * out like a system bar (user's screenshot, 2026-10-05).
     */
    private fun applyPageInsets() {
        val lp = page.layoutParams as? FrameLayout.LayoutParams ?: return
        val cut = insets[4]
        val t = InsetSplit.pageTopMargin(insets[1], cut)
        val b = insets[3]
        val bandMoved = cut != pageCutoutTop
        pageCutoutTop = cut
        if (lp.topMargin != t || lp.bottomMargin != b) {
            lp.topMargin = t
            lp.bottomMargin = b
            page.layoutParams = lp
        } else if (bandMoved) {
            // The text box moves by the band alone. After the layout pass, never with the size measured now: a rotation
            // (portrait ↔ side cutout) changes the band and the size in one step, and its onSizeChanged lays the new
            // size out with the new band first; this call then finds that viewport and builds nothing. When the view
            // keeps its size (no onSizeChanged), this is the one rebuild.
            handler.removeCallbacks(bandRelayout)
            handler.post(bandRelayout)
        }
    }

    /** [applyPageInsets]: the band moved; setViewport ignores an unchanged (width, height, band). */
    private val bandRelayout = Runnable { if (!isDestroyed && page.width > 0) onViewSizeChanged(page.width, page.height) }

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

    override fun hitTest(x: Float, y: Float): Int {
        if (scroll != null) {
            // The virtual page's lines are already shifted to the content box (no allocation: selection calibrates
            // with ~1,400 calls).
            val vp = vpage() ?: return -1
            val g = session?.generation?.geometry ?: return -1
            return try {
                LineGeometry.hitTest(vp.layout, vp.page, x - g.contentLeft, y - g.contentTop)
            } catch (t: Throwable) {
                Log.w(TAG, "hitTest failed", t)
                -1
            }
        }
        val f = page.frame ?: return -1
        val p = f.layout.pages.getOrNull(f.pageIndex) ?: return -1
        return try {
            LineGeometry.hitTest(f.layout, p, x - f.left, y - f.top)
        } catch (t: Throwable) {
            Log.w(TAG, "hitTest failed", t)
            -1
        }
    }

    /**
     * The visible char whose glyph box (advance × the line's glyph band, [slopPx] wider on each side) holds (x, y) in
     * view px, or -1: unlike [hitTest] it never snaps, so margins, line gaps, spaces, blank line ends and images give -1.
     */
    private fun glyphAtView(x: Float, y: Float, slopPx: Float): Int {
        if (scroll != null) {
            val vp = vpage() ?: return -1
            val g = session?.generation?.geometry ?: return -1
            return try {
                LineGeometry.glyphAt(vp.layout, vp.page, x - g.contentLeft, y - g.contentTop, slopPx, slopY = 0f)
            } catch (t: Throwable) {
                Log.w(TAG, "glyphAt failed", t)
                -1
            }
        }
        val f = page.frame ?: return -1
        val p = f.layout.pages.getOrNull(f.pageIndex) ?: return -1
        return try {
            // Sideways slop only: above and below, the band's own air is the margin (a vertical slop would reach
            // across most of the white gap between two lines).
            LineGeometry.glyphAt(f.layout, p, x - f.left, y - f.top, slopPx, slopY = 0f)
        } catch (t: Throwable) {
            Log.w(TAG, "glyphAt failed", t)
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
        // Scroll: the virtual page (the lines wholly on screen); a bookmark anywhere on screen is removed.
        val vp = vpage()
        val l = vp?.layout ?: curLayout ?: return
        val p = currentPage ?: return
        val sec = vp?.section ?: curSection
        val hits = scroll?.let { visibleBookmarks(it) } ?: run {
            val last = curPageIdx == l.pageCount - 1
            bookmarks.filter { it.section == curSection && onPage(it.offset, p, last) }
        }
        bumpThumbDecor(sec)
        for (mark in hits) if (mark.section != sec) bumpThumbDecor(mark.section)
        if (hits.isNotEmpty()) {
            val gone = hits.toSet()
            bookmarks = bookmarks.filter { it !in gone }
            // One still being inserted is deleted when its insert returns (below).
            for (h in hits) if (h.id < 0) removedTemps += h.id
            ReaderIo.launch { for (h in hits) if (h.id > 0) Library.deleteBookmark(h.id) }
        } else {
            val off = p.start
            val snippet = ReaderFormat.snippet(l.content.text, p.start, p.end)
            // N §6.5: the place is computed here on main, before the IO launch.
            val place = notePlace(DocPosition(sec, off))
            val temp = Bookmark(-SystemClock.uptimeMillis(), b.id, sec, off, snippet, System.currentTimeMillis())
            bookmarks = bookmarks + temp
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        Library.addBookmark(b.id, sec, off, snippet, place)
                    } catch (t: Throwable) {
                        Log.w(TAG, "addBookmark failed", t)
                        null
                    }
                }
                if (saved == null) {
                    removedTemps -= temp.id
                    return@launch
                }
                if (bookmarks.any { it === temp }) {
                    // A reload may already have brought the saved row in: keep one copy.
                    bookmarks = bookmarks.map { if (it === temp) saved else it }.distinctBy { it.id }
                } else if (removedTemps.remove(temp.id)) {
                    // Removed again before the insert finished.
                    ReaderIo.launch { Library.deleteBookmark(saved.id) }
                }
                // Else the book was closed or switched meanwhile: the saved row stays (the next load shows it).
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
        /** A drag started in scroll mode (S §1.10). */
        override fun onScrollStart() {
            val sc = scroll ?: return
            scrollGesture = true
            if (sc.live) {
                if (chromeVisible) setChromeVisible(false)
                dropSearchHighlight()
            } else {
                // STEP draws nothing until the release: the chrome and the search hit go with that frame.
                scrollCloseAtSettle = true
            }
            // TTS's follow stands still while the finger moves (the settle re-arms its check).
            if (ttsSpeaking()) safely { tts?.onUserNavigated() }
        }

        override fun onTouchStarted() {
            keeper.poke()
            if (autoTurnOn) stopAutoTurn(showToast = true)
            // A drag that ended without a settle (at a book edge, or cancelled in STEP) leaves nothing behind.
            scroll?.let { sc ->
                scrollGesture = false
                scrollCloseAtSettle = false
                // This touch stops a step waiting for its section (PageView's stopMotion): the turns queued
                // behind that step go with it.
                if (sc.pending) backlog.clear()
            }
        }

        override fun isSelectionActive(): Boolean = safely { selection?.isActive } == true

        override fun onSelectionTouch(ev: MotionEvent): Boolean = safely { selection?.onTouchEvent(ev) } ?: false

        override fun onTap(x: Float, y: Float) = handleTap(x, y)

        override fun onSwipe(dir: SwipeDir) {
            if (chromeVisible) setChromeVisible(false)
            userTurn(dir == SwipeDir.NEXT)
        }

        override fun onLongPress(x: Float, y: Float): Boolean {
            if (!app.longPressSelect || curLayout == null) return false
            // Scroll: the selection works on the wholly visible lines of the section under the finger.
            val sc = scroll
            if (sc != null) {
                // Held for the selection only once it exists (vpage() returns an unheld focus to the anchor).
                scrollFocusHeld = false
                if (!sc.focusAt(y)) return false
            }
            // Only with the finger on a glyph: a press on a margin, in the leading between lines or paragraphs, on the
            // blank end of a short line or below the text selects nothing (and its release turns no page).
            if (glyphAtView(x, y, dpF(GLYPH_SLOP_DP)) < 0) {
                sc?.let { releaseFocus(it) }
                return false
            }
            if (safely { selection?.startAt(x, y) } != true) {
                sc?.let { releaseFocus(it) }
                return false
            }
            if (sc != null) scrollFocusHeld = true
            // Only once something is selected: a long press that selects nothing leaves the menu as it was.
            if (chromeVisible) setChromeVisible(false)
            return true
        }

        override fun brightnessStart(): Float = light.currentPos()

        override fun onBrightness(value: Float, done: Boolean) {
            light.onDrag(value, done)
            showBrightnessOverlay(value.coerceIn(0f, 1f), done)
        }

        override fun onViewSizeChanged(w: Int, h: Int) = this@ReaderActivity.onViewSizeChanged(w, h)

        override fun onWheel(next: Boolean) {
            keeper.poke()
            if (safely { selection?.isActive } == true) return
            userTurn(next)
        }
    }

    private fun handleTap(x: Float, y: Float) {
        if (chromeVisible) {
            closeChrome()
            return
        }
        val l = curLayout ?: return
        if (session == null) return
        scroll?.let { if (scrollLinkTap(it, x, y)) return }
        val off = if (scroll == null) hitTest(x, y) else -1
        if (off in 0 until l.content.length) {
            val link = l.content.styleAt(off).link
            if (link != null && fingerOnChar(off, x, y)) {
                followLink(link)
                return
            }
        }
        // The zones cover the page below the camera band (fullscreen S25), as when the view started below it: the
        // bookmark corner and the grid rows stay where they were (a tap in the band counts as the top row).
        val band = pageCutoutTop
        val zy = y - band
        val zh = (page.height - band).coerceAtLeast(1)
        when (TapZones.corner(x, zy, page.width, zh)) {
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
        runTapAction(TapZones.actionAt(app, x, zy, page.width, zh))
    }

    /**
     * Scroll: a link in either section of a seam is tappable. The section under the finger is focused for the test
     * (its virtual page is what [hitTest] indexes, not the top page), then the focus returns to the anchor.
     */
    private fun scrollLinkTap(sc: ScrollReader, x: Float, y: Float): Boolean {
        scrollFocusHeld = false
        if (!sc.focusAt(y)) return false
        var link: String? = null
        var section = -1
        val vp = sc.virtualPage()
        if (vp != null) {
            val off = hitTest(x, y)
            if (off in 0 until vp.layout.content.length) {
                val ln = vp.layout.content.styleAt(off).link
                if (ln != null && fingerOnChar(off, x, y)) {
                    link = ln
                    section = vp.section
                }
            }
        }
        sc.clearFocus()
        if (link == null) return false
        followLink(link, section)
        return true
    }

    /** A long press that started no selection gives the virtual page back to the anchor section. */
    private fun releaseFocus(sc: ScrollReader) {
        scrollFocusHeld = false
        sc.clearFocus()
    }

    /** hitTest snaps to the nearest char of a line; a link needs the finger on (or within [slopDp] of) that char's box. */
    private fun fingerOnChar(offset: Int, x: Float, y: Float, slopDp: Int = 10): Boolean {
        if (scroll != null) {
            val vp = vpage() ?: return false
            val g = session?.generation?.geometry ?: return false
            val slop = dp(slopDp).toFloat()
            val cx = x - g.contentLeft
            val cy = y - g.contentTop
            return try {
                LineGeometry.rangeRects(vp.layout, vp.page, offset, offset + 1).any {
                    cx >= it.left - slop && cx <= it.right + slop && cy >= it.top - slop && cy <= it.bottom + slop
                }
            } catch (t: Throwable) {
                false
            }
        }
        val f = page.frame ?: return false
        val p = f.layout.pages.getOrNull(f.pageIndex) ?: return false
        val slop = dp(slopDp).toFloat()
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
            TapAction.MENU -> if (chromeVisible) closeChrome() else setChromeVisible(true)
            TapAction.BOOKMARK -> toggleBookmark()
            TapAction.TOC -> openContents()
            TapAction.SEARCH -> openSearch()
            TapAction.SETTINGS -> openReadingSettings()
            TapAction.TTS -> startTts()
            TapAction.NEXT_CHAPTER -> jumpChapter(true)
            TapAction.PREV_CHAPTER -> jumpChapter(false)
            TapAction.REFRESH -> refreshAfterDraw(0L)
            TapAction.INVERT -> toggleInvert()
            TapAction.GOTO -> ReaderPanels.showGoTo(this)
            TapAction.AUTO_TURN -> toggleAutoTurn()
        }
    }

    private fun followLink(href: String, section: Int = curSection) {
        val s = session ?: return
        val sec = section
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

    /**
     * Keys (T1-4): [KeyMap.action] resolves the key (bindings, learned keys, built-in keys). DOWN and UP of the
     * reader's keys are consumed, so the volume panel never shows; a key that is not the reader's (or bound to 없음)
     * goes to the system. Page keys turn at once on key-down; holding one does what [AppSettings.keyHold] says
     * ([pageKey]). Other actions run once per press.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || session == null || errorPanel.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }
        val a = app
        val action = KeyMap.action(code, event.isShiftPressed, a)
        if (action == TapAction.NONE) return super.dispatchKeyEvent(event)
        val assigned = KeyMap.isAssigned(code, a)
        if (chromeVisible && !assigned && KeyMap.isFocusKey(code)) return super.dispatchKeyEvent(event)
        // While listening to TTS the (unassigned) volume keys control the speech volume.
        if (!assigned && KeyMap.isVolumeKey(code) && ttsSpeaking()) return super.dispatchKeyEvent(event)
        if (endPanel?.isShowing == true || endLoading) return endPanelKey(event, action, assigned)
        if (event.action == KeyEvent.ACTION_UP) {
            heldKey.up(code)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // Vendor function / fingerprint keys and remotes bounce: one touch must turn one page.
        if ((assigned && !KeyMap.isBuiltIn(code)) || code in a.nextPageKeys || code in a.prevPageKeys) {
            if (!learnedRepeatFilter.accept(event.repeatCount, event.eventTime)) {
                if (event.repeatCount == 0) heldKey.cancel()
                return true
            }
        }
        keeper.poke()
        when (action) {
            TapAction.NEXT -> pageKey(event, true, a)
            TapAction.PREV -> pageKey(event, false, a)
            else -> if (event.repeatCount == 0) runTapAction(action)
        }
        return true
    }

    /**
     * A page key's key-down: the page turns at once; a held key then turns on at a readable pace, or does its hold
     * action once, from the page where the key went down (T1-4: without that anchor, holding "next" on a chapter's
     * last page would skip a whole episode).
     */
    private fun pageKey(event: KeyEvent, next: Boolean, a: AppSettings) {
        val hold = a.keyHold
        when (heldKey.down(event.keyCode, event.repeatCount, event.eventTime, hold, KeyMap.repeatMs(a.einkMode))) {
            HeldKey.Step.TURN -> {
                val fresh = event.repeatCount == 0
                if (fresh) rememberHoldAnchor()
                keyInputAt = event.eventTime
                val turned = userTurn(next)
                if (fresh) holdTurned = turned
            }
            HeldKey.Step.HOLD -> holdAction(hold, next)
            HeldKey.Step.NONE -> {}
        }
    }

    private fun rememberHoldAnchor() {
        if (scroll != null) {
            // One frame of reference: the virtual page's section, page and range.
            val vp = vpage()
            if (vp != null) {
                holdSection = vp.section
                holdPageIdx = ScrollWiring.chapterPageIndex(vp.page.start, vp.pageIndex)
                holdStart = vp.page.start
                holdEnd = vp.page.end
                return
            }
        }
        val p = currentPage
        holdSection = curSection
        holdPageIdx = curPageIdx
        holdStart = p?.start ?: anchor.offset
        holdEnd = p?.end ?: anchor.offset
    }

    /** The hold action ([KeyHold.CHAPTER] / [KeyHold.TEN]) relative to where the key went down. */
    private fun holdAction(hold: KeyHold, next: Boolean) {
        if (session == null || curLayout == null || endPanel?.isShowing == true || endLoading) return
        when (hold) {
            KeyHold.CHAPTER -> {
                val target = chapterTarget(holdSection, holdPageIdx, holdStart, holdEnd, next) ?: return
                // The key-down's turn may already have reached that chapter's first page: stay there.
                if (isOnCurrentPage(target) && navJob?.isActive != true) return
                if (scroll != null) jumpTo(target.section, target.offset, -1) else goTo(target, remember = false)
            }
            KeyHold.TEN -> {
                // The other pages of the ten, applied at once like a burst of taps (queued behind a pending layout).
                val n = HOLD_PAGES - if (holdTurned) 1 else 0
                backlog.restore(if (next) n else -n)
                if (navJob?.isActive != true) flushTurns()
                if (ttsSpeaking()) safely { tts?.onUserNavigated() }
            }
            KeyHold.REPEAT, KeyHold.SINGLE -> {}
        }
    }

    /**
     * Keys while the end panel shows (or loads): a held key does nothing (it must neither close the panel nor press a
     * button); "previous" closes it; arrow / enter keys move between its buttons; anything else is swallowed.
     */
    private fun endPanelKey(event: KeyEvent, action: TapAction, assigned: Boolean): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount > 0) return true
        if (!assigned && KeyMap.isFocusKey(event.keyCode)) return super.dispatchKeyEvent(event)
        if (!down) {
            heldKey.up(event.keyCode)
            return true
        }
        heldKey.cancel()
        if (action == TapAction.PREV) closeEndPanel()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            endPanel?.isShowing == true -> closeEndPanel()
            safely { selection?.isActive } == true -> safely { selection?.clear() }
            ttsSpeaking() -> safely { tts?.stop() }
            // The search-results bar goes first; the next BACK leaves the book.
            safely { ReaderPanels.closeSearchBar(this) } == true -> {}
            chromeVisible -> closeChrome()
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
        returnNav.bind()
        chrome.setPinned(returnNav.pinned, returnNav.markOnScreen())
        light.bind()
    }

    /** LightController's view of the reader (a private object: its members would clash with the activity's own). */
    private val lightHost = object : LightHost {
        override val activity: Activity get() = this@ReaderActivity
        override val handler: Handler get() = this@ReaderActivity.handler
        override val app: AppSettings get() = this@ReaderActivity.app
        override val chromeVisible: Boolean get() = this@ReaderActivity.chromeVisible
        override fun saveApp(a: AppSettings) = this@ReaderActivity.saveApp(a)
        override fun setPageBrightnessSwipe(on: Boolean) {
            page.brightnessSwipe = on
        }

        override fun showChrome() = setChromeVisible(true)
    }

    /** ReturnNav's view of the reader (U §3.5): positions, page numbers, the jump and the pin's storage. */
    private val returnHost = object : ReturnHost {
        override val chromeVisible: Boolean get() = this@ReaderActivity.chromeVisible
        // Paged: the page start; scroll mode: the virtual page start (ReaderHost.currentPosition()'s scroll branch).
        override fun currentPosition(): DocPosition = this@ReaderActivity.currentPosition()
        override fun isOnCurrentPage(pos: DocPosition): Boolean = this@ReaderActivity.isOnCurrentPage(pos)
        private val pages = ReturnPageMemo()

        // The exact page while the place's section is laid out, remembered for this layout (ReturnPageMemo): after a
        // far jump that section leaves the 4-section cache, and the char estimate can read one page short.
        override fun globalPageOf(pos: DocPosition): Int {
            val s = session ?: return 1
            val sec = pos.section.coerceIn(0, s.sectionCount - 1)
            val l = s.peek(sec)
            val idx = pages.resolve(s.generation, sec, pos.offset, if (l != null) l.pageForOffset(pos.offset) else -1)
            return s.counts.globalPage(sec, if (idx >= 0) idx else s.counts.estimatePageIndex(sec, pos.offset))
        }

        override fun jumpToReturn(pos: DocPosition): Boolean {
            jumpTo(pos.section, pos.offset, -1)
            // No navigation pending: display() showed the page now (a layout or preload shows it later, and binds).
            return navJob == null
        }
        override fun charProgressOf(pos: DocPosition): Float =
            session?.counts?.charProgress(pos.section, pos.offset) ?: 0f

        override fun clampPosition(pos: DocPosition): DocPosition {
            val s = session ?: return DocPosition.START
            val sec = pos.section.coerceIn(0, (s.sectionCount - 1).coerceAtLeast(0))
            // Capped only by a real length: a section not laid out has an estimate (EPUB: zip bytes / 3), which
            // would move a pin past its first third to an earlier place.
            val known = s.peek(sec)?.content?.length
            val off = pos.offset.coerceAtLeast(0).let { if (known != null) it.coerceAtMost(known) else it }
            return DocPosition(sec, off)
        }

        override fun locateFraction(f: Float): DocPosition = session?.counts?.locateFraction(f) ?: DocPosition.START

        override fun textSignature(): String? {
            val s = session ?: return null
            val b = bookRef ?: return null
            return LayoutKeys.textSignature(s.settings, s.document.format, b.encoding)
        }

        override fun saveReturnMark(text: String?) {
            val id = bookRef?.id ?: return
            ReaderIo.launch { BookPrefs.setReturnMark(id, text) }
        }

        override fun onReturnChanged() {
            if (chromeVisible) bindChrome()
            updateChipPosition()
        }
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

        override fun onPageLabel() {
            if (session != null) safely { ReaderPanels.showGoTo(this@ReaderActivity) }
        }

        override fun onChapter(next: Boolean) = jumpChapter(next)

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

        override fun onPinHere() = returnNav.onPinPressed()

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

    /**
     * The left-edge brightness drag's overlay: its text changes at most every [Throttle.LABEL_MS] (a value held back
     * shows once the finger rests), and the final value on [done], when it hides.
     */
    private fun showBrightnessOverlay(v: Float, done: Boolean) {
        if (done) {
            dropHeldBrightness()
            brightnessThrottle.reset()
            brightnessOverlay.text = ReaderFormat.brightness(v)
            brightnessOverlay.visibility = View.GONE
            return
        }
        val now = SystemClock.uptimeMillis()
        if (brightnessThrottle.tryAcquire(now)) {
            dropHeldBrightness()
            brightnessOverlay.text = ReaderFormat.brightness(v)
            brightnessOverlay.visibility = View.VISIBLE
            return
        }
        if (brightnessHeld.isNaN()) handler.postDelayed(showHeldBrightness, brightnessThrottle.waitMs(now))
        brightnessHeld = v
    }

    private fun dropHeldBrightness() {
        if (brightnessHeld.isNaN()) return
        brightnessHeld = Float.NaN
        handler.removeCallbacks(showHeldBrightness)
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
        // The popup hides the bars and takes the top bar's place: showing them first would only cost two extra e-ink
        // updates.
        safely { ReaderPanels.showReadingSettings(this, chrome.gear) }
    }

    internal fun startTts() {
        if (session == null || curLayout == null) return
        setChromeVisible(false)
        stopAutoTurn(showToast = false)
        val t = tts ?: safely { TtsController(this) }?.also { tts = it } ?: return
        endPeek(PeekRule.Event.TTS_START)
        safely { t.start() }
    }

    /** TTS session active (the controller's own × may have stopped it, so ask it rather than remember). */
    private fun ttsSpeaking(): Boolean = tts != null && safely { tts?.isSpeaking } == true

    /** 흑백 반전: a global setting (R2: never the session's effective settings, which carry this book's TXT options). */
    internal fun toggleInvert() {
        if (session == null) return
        val g = Settings.reader
        applySettings(g.copy(invert = !g.invert))
    }

    /** Full refresh of the whole reader window (page, chrome, overlays), not just the page view. */
    internal fun refreshScreen() {
        cadence.reset()
        val a = app
        safely { Eink.fullRefresh(root, a.einkRefreshMethod, a.einkFlashMs) }
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

    /**
     * The top bar has room for its bookmark button (U §2.2: 352 dp of row between the insets); otherwise the ⋮ menu
     * carries "북마크 추가" / "북마크 삭제" (C22).
     */
    internal fun bookmarkInChrome(): Boolean {
        val w = root.width - insets[0] - insets[2]
        return w <= 0 || ChromeMath.bookmarkFits(w, resources.displayMetrics.density)
    }

    /**
     * The app's settings (at [settingsPage], or its start) from the reader: the ⋮ menu, the reading-settings popup,
     * the font chooser. The light restores nothing for our own settings page and re-reads its verdict on return (U §4.2).
     */
    internal fun openAppSettings(settingsPage: String? = null) {
        light.markOwnLaunch()
        // The open book goes along, so 읽기 설정 can offer its own TXT options ([takeSettingsEdits] takes them back).
        val b = bookRef
        val book = if (b != null && session != null) OpenBook.Info(b.id, b.title, b.format, b.encoding, bookOverride) else null
        SettingsActivity.open(this, settingsPage, book)
    }

    /**
     * The edits 설정 made for this book ([OpenBook], once): new TXT options become [bookOverride], applied by
     * [onResume]'s one settings compare (one re-parse for any number of changes); a new encoding re-opens the book,
     * like the library's "인코딩 변경". True when it re-opens.
     */
    private fun takeSettingsEdits(): Boolean {
        if (recreatePending) return true
        val b = bookRef ?: return false
        val e = OpenBook.take(b.id) ?: return false
        if (e.overrideChanged) bookOverride = e.override
        val id = b.id
        val o = bookOverride
        val overrideChanged = e.overrideChanged
        val enc = e.encoding?.trim()
        if (enc == null || enc == b.encoding.trim()) {
            // 설정 saved it already; again here, so it holds even when that write was cut short.
            if (overrideChanged) ReaderIo.launch { BookPrefs.setTxtOverride(id, o) }
            return false
        }
        recreatePending = true
        scope.launch {
            // 설정 saved both already; written again here, in this order, so the re-open reads them whatever its own
            // writes are still doing.
            withContext(Dispatchers.IO) {
                runCatching {
                    if (overrideChanged) BookPrefs.setTxtOverride(id, o)
                    Library.setEncoding(id, enc)
                }
            }
            // The host caches the Book (and its encoding); re-creating the activity re-reads it from the library.
            if (!isFinishing && !isDestroyed) recreate()
        }
        return true
    }

    internal fun isCurrentPageBookmarked(): Boolean {
        scroll?.let { return anyVisibleBookmark(it) }
        val l = curLayout ?: return false
        val p = currentPage ?: return false
        return isBookmarked(l, p)
    }

    /** The reader closes its chrome (tap, BACK, menu key): like any closed panel, one turn toward the cadence. */
    private fun closeChrome() {
        if (!chromeVisible) return
        setChromeVisible(false)
        onPanelClosed()
    }

    /**
     * A panel over the page closed (T1-3d): it counts as one turn of the e-ink cadence (nothing when none is set),
     * and a refresh that falls due runs once the panel has left the screen.
     */
    private fun onPanelClosed() {
        if (curLayout == null || session == null) return
        if (cadence.onPanelClosed()) refreshAfterDraw(PANEL_GONE_MS)
    }

    // ================================================================== BookInsightsHost (T1-1, T1-5, T1-7)

    override fun episodes(onReady: (Episodes?) -> Unit) {
        val s = session
        if (s == null) {
            onReady(null)
            return
        }
        s.episodes(onReady)
    }

    override fun minutesLeft(bookScope: Boolean): Int? = minutesLeftOrNone(bookScope).takeIf { it >= 0 }

    /** [minutesLeft] without boxing (the status slots ask on every turn): -1 = unknown. */
    private fun minutesLeftOrNone(bookScope: Boolean): Int {
        val chars = charsLeft(bookScope)
        return if (chars < 0L) -1 else ReaderFormat.minutesFor(chars, cpm)
    }

    override fun charsPerMinute(): Int = cpm

    /**
     * Characters from the current page's start to the end of the episode (the next TOC entry after the page; the
     * book's end in the last one) or of the book; -1 without a page, or without a TOC for the episode. O(1) on a
     * turn: the book through PageCounts' suffix sums, the episode through [BookSession.charsLeftInChapter] (the TOC
     * is scanned once per chapter, not per page). -1 when unknown (a primitive: no boxing on a turn).
     */
    private fun charsLeft(bookScope: Boolean): Long {
        val s = session ?: return -1L
        val l = curLayout ?: return -1L
        if (layoutStale()) return -1L
        val p = l.pages.getOrNull(curPageIdx) ?: return -1L
        val sec = curSection
        if (bookScope) return (l.content.length - p.start).coerceAtLeast(0).toLong() + s.counts.charsAfter(sec)
        if (s.chapters.size == 0) return -1L
        return s.charsLeftInChapter(sec, p.start).coerceAtLeast(0L)
    }

    // ================================================================== TxtOverrideHost (T1-9)

    override val txtOverride: TxtOverride? get() = bookOverride

    override fun applyTxtOverride(o: TxtOverride?, onApplied: (() -> Unit)?) {
        val b = bookRef ?: return
        val v = o?.takeUnless { it.isEmpty }
        bookOverride = v
        ReaderIo.launch { BookPrefs.setTxtOverride(b.id, v) }
        applyToSession(Settings.reader, onApplied)
    }

    // ================================================================== end panel (T1-2)

    /**
     * "Next" on the last page (tap, key, swipe, auto turn, TTS): the end panel. When [AppSettings.autoMarkFinished]
     * the book is marked 다 읽음 with progress 1.0 and its finish time; the next part is looked for. That work runs on
     * IO first, and the panel is shown filled in (one e-ink update).
     */
    override fun showBookEnd() {
        val s = session ?: return
        val b = bookRef ?: return
        if (curLayout == null || endPanel?.isShowing == true || endLoading) return
        endLoading = true
        stopAutoTurn(showToast = false)
        setChromeVisible(false)
        // Progress 1.0 (the last page) and the reading so far, before the book's total time is read back. The panel
        // covers the page from here: it counts again only when the panel closes on it ([closeEndPanel]).
        savePositionNow()
        val delta = tracker.flush(SystemClock.elapsedRealtime())
        val mark = app.autoMarkFinished
        scope.launch {
            val info = withContext(Dispatchers.IO) { loadEnd(b, delta, mark) }
            endLoading = false
            if (session !== s || bookRef?.id != b.id || isFinishing) return@launch
            endPanel().show(info, root.width)
        }
    }

    /** The end panel, built and attached on first use (assigned before attaching: [isOwnView] must know it). */
    private fun endPanel(): EndPanel = endPanel ?: EndPanel(this, endActions).also {
        endPanel = it
        it.attach(root)
    }

    /** The end panel closes and leaves the reader on the last page (BACK, "previous", a tap beside its box). */
    private fun closeEndPanel() {
        val panel = endPanel ?: return
        if (!panel.isShowing) return
        panel.hide()
        // The real page on top (scroll: not the virtual page), so `pages` means the same in both modes.
        trackPage(curLayout?.pages?.getOrNull(curPageIdx))
    }

    /** Blocking (IO): stores [delta], marks [b] finished when [mark], finds its next part; never throws. */
    private fun loadEnd(b: Book, delta: ReadingDelta?, mark: Boolean): EndInfo {
        if (delta != null) storeReading(b.id, delta)
        val fresh = try {
            Library.book(b.id)
        } catch (t: Throwable) {
            Log.w(TAG, "book reload failed", t)
            null
        } ?: b
        var finished = fresh.haveRead
        if (mark) {
            try {
                if (!fresh.haveRead) Library.setHaveRead(b.id, true)
                finished = true
                if (BookPrefs.finishedAt(b.id) == 0L) BookPrefs.setFinishedAt(b.id, System.currentTimeMillis())
            } catch (t: Throwable) {
                Log.w(TAG, "mark finished failed", t)
            }
        }
        val next = try {
            NextPart.find(fresh)?.takeIf { it.isFile && it.absolutePath != File(b.path).absolutePath }
        } catch (t: Throwable) {
            Log.w(TAG, "next part lookup failed", t)
            null
        }
        return EndInfo(fresh.title, fresh.readingSeconds, next, finished)
    }

    private val endActions = object : EndPanel.Actions {
        override fun onEndNextPart(file: File) {
            scope.launch {
                val next = withContext(Dispatchers.IO) {
                    try {
                        Library.addOrUpdateFile(file)
                    } catch (t: Throwable) {
                        Log.w(TAG, "next part add failed", t)
                        null
                    }
                }
                if (isDestroyed) return@launch
                if (next == null) {
                    toast("다음 권을 열지 못했습니다")
                    return@launch
                }
                endPanel?.hide()
                openBook(next.id)
            }
        }

        override fun onEndFinished(finished: Boolean) {
            val id = bookRef?.id ?: return
            ReaderIo.launch {
                Library.setHaveRead(id, finished)
                BookPrefs.setFinishedAt(id, if (finished) System.currentTimeMillis() else 0L)
            }
        }

        override fun onEndLibrary() {
            endPanel?.hide()
            // Closed by the user before the library's new intent is delivered (it must not reopen this book).
            userFinished = true
            ResumeState.clear()
            // The library below this reader when it was opened from there, else a new one (never the open-last start:
            // the intent has no MAIN action).
            startActivity(
                Intent(this@ReaderActivity, LibraryActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION,
                ),
            )
            finish()
        }

        override fun onEndRestart() {
            endPanel?.hide()
            jumpTo(0, 0, -1)
        }

        override fun onEndReview() {
            safely { ReaderPanels.showReview(this@ReaderActivity) }
        }

        override fun onEndClose() {
            closeEndPanel()
        }
    }

    /** Opens library book [id] in this reader instead of the current one (the next part). */
    private fun openBook(id: Long) {
        val i = Intent(this, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, id)
        setIntent(i)
        closeCurrentBook()
        startOpen(i)
    }

    // ================================================================== return point

    /**
     * The floating return chip's place (C24): above the progress lane and the extras' bottom bars (search results,
     * TTS, which would otherwise cover it and take its taps). An overlay: it may cover the footer band for its two
     * turns and never resizes the page. Only shown with the chrome hidden.
     */
    private fun updateChipPosition() {
        if (!::returnNav.isInitialized) return
        val chip = returnNav.chip
        val lp = chip.layoutParams as? FrameLayout.LayoutParams ?: return
        var bottom = insets[3] + StatusFit.laneTopPx(resources.displayMetrics.density) + dp(4)
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
                showBookEnd()
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
        endPeek(PeekRule.Event.AUTO_TURN_START)
        autoTurnOn = true
        handler.removeCallbacks(autoTurnRunnable)
        handler.postDelayed(autoTurnRunnable, autoTurnPeriod())
        setChromeVisible(false)
        val sec = app.autoTurnSeconds.coerceIn(3, 3600)
        toast(if (scroll != null) ScrollWiring.autoScrollOn(sec) else ReaderFormat.autoTurnOn(sec))
    }

    private fun stopAutoTurn(showToast: Boolean) {
        if (!autoTurnOn) return
        autoTurnOn = false
        handler.removeCallbacks(autoTurnRunnable)
        if (showToast) toast(ScrollWiring.autoOff(scroll != null))
    }

    // ================================================================== persistence

    private val saveRunnable = Runnable { savePositionNow() }

    private fun schedulePositionSave() {
        handler.removeCallbacks(saveRunnable)
        if (!peek.savesPosition) return
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    /**
     * Saves the position to the library. [persistText] (pause, close) also records which parse the coordinates
     * belong to (TXT), so a later open under other parse options can find the place again ([TextPositions]), and
     * bumps `notesGen` after the commit: last_read_at feeds the hub's BOOK_RECENT order and the review arm's time
     * (N §5.1), once per pause or close, not per page.
     */
    private fun savePositionNow(persistText: Boolean = false) {
        handler.removeCallbacks(saveRunnable)
        // N §6.2: a note being peeked at leaves the saved position, progress and last-read time as they were.
        if (!peek.savesPosition) return
        val b = bookRef ?: return
        val s = session ?: return
        if (curLayout == null) return
        val pos = anchor
        val prog = progress()
        ReaderIo.launch {
            Library.savePosition(b.id, pos.section, pos.offset, prog)
            if (persistText) Library.notesChanged()
        }
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
        if (!peek.savesPosition) return
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

    // ================================================================== reading log (T1-6)

    /** A page is on screen: the tracker closes the one before it (and flushes the day that ended, if any). */
    private fun trackPage(p: PageInfo?) {
        val chars = if (p != null) p.end - p.start else 0
        val d = tracker.onPageShown(SystemClock.elapsedRealtime(), dayClock.day(System.currentTimeMillis()), chars) ?: return
        bookRef?.let { writeReading(it.id, d) }
    }

    /** Stores [d] for book [id] on IO (ReadingLog + the book's total time), then refreshes the reading speed. */
    private fun writeReading(id: Long, d: ReadingDelta) {
        ReaderIo.launch { storeReading(id, d) }
    }

    /** Blocking (IO): [writeReading]'s work; the end panel runs it before reading the book's total time. */
    private fun storeReading(id: Long, d: ReadingDelta) {
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
        val v = try {
            ReadingLog.cpm(id)
        } catch (t: Throwable) {
            null
        }
        if (v != null && v > 0) handler.post { if (bookRef?.id == id) cpm = v }
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
