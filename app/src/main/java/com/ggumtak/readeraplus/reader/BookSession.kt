package com.ggumtak.readeraplus.reader

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.engine.FontMetricsPx
import com.ggumtak.readeraplus.engine.IntSize
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.RunStyle
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.engine.TextMeasurer
import com.ggumtak.readeraplus.engine.Typesetter
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.epub.EpubBook
import com.ggumtak.readeraplus.reader.extras.Episodes
import com.ggumtak.readeraplus.render.AndroidTextMeasurer
import com.ggumtak.readeraplus.render.FontCatalog
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.render.FontSource
import com.ggumtak.readeraplus.render.ImageCache
import com.ggumtak.readeraplus.render.PageRenderer
import com.ggumtak.readeraplus.settings.ReaderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One open book in the reader: the document, current settings, layout generation (content box + config),
 * an LRU of laid-out sections, prefetching and background page counting.
 *
 * Threading: all public members are main-thread only. Layouts run on one "reader-layout" thread (foreground
 * requests and prefetch, so its measurer is single-threaded); page counting runs on a background-priority
 * "reader-count" thread with its own measurer. Results come back to the main thread and are dropped when the
 * generation changed in the meantime.
 *
 * Settings ([initialSettings], [updateSettings]) are the book's EFFECTIVE settings: the global ones merged with the
 * book's own TXT options (`Settings.reader.withTxt(override)`, T1-9), so the parse, the layout and the page-count key
 * all follow what this book actually uses.
 */
class BookSession(
    private val context: Context,
    val book: Book,
    val document: BookDocument,
    initialSettings: ReaderSettings,
) {
    /** Main-thread callbacks. */
    interface Listener {
        /** Page counts progressed; [complete] once every section is counted (or loaded from the cache). */
        fun onCountsChanged(complete: Boolean)

        /** [layout] of [section] was just laid out and cached for the current generation (e.g. a prefetch). */
        fun onSectionStored(section: Int, layout: SectionLayout) {}
    }

    /** Immutable parameters of one layout generation (any layout-affecting change creates a new one). */
    class Generation(
        val id: Int,
        val settings: ReaderSettings,
        val geometry: PageGeometry,
        val config: LayoutConfig,
        val density: Float,
    )

    enum class Change { NONE, REPAINT, RELAYOUT }

    /** [failed]: the section could not be loaded/typeset and was counted as its error page instead. */
    private class CountResult(val pages: Int, val chars: Int, val anchors: Map<String, Int>, val failed: Boolean)

    /**
     * A layout request in flight. Requests nobody waits for (prefetches, or jumps the user already abandoned)
     * that have not started yet yield to a foreground request.
     */
    private class Pending(val gen: Generation) {
        val deferred = CompletableDeferred<SectionLayout?>()
        var job: Job? = null
        /** Coroutines currently awaiting this layout (main thread only). */
        @JvmField var waiters = 0
        /** Set on the layout thread when the work begins (from then on it runs to completion). */
        @Volatile @JvmField var started = false
    }

    var listener: Listener? = null

    var settings: ReaderSettings = initialSettings
        private set

    val sectionCount: Int = document.sections.size
    val counts = PageCounts(IntArray(sectionCount) { document.sections[it].approxChars })
    val chapters = ChapterIndex(document.toc, sectionCount)
    val images = ImageCache(document)

    var generation: Generation? = null
        private set

    /** Page-count cache key of the current generation (null until computed). */
    var layoutKey: String? = null
        private set

    private var genCounter = 0
    /**
     * Id of the current generation for the worker threads (-1 once closed): a layout or count still running for an
     * older generation stops at its next measuring call instead of holding the single layout thread.
     */
    @Volatile private var liveGenId = 0
    private var viewW = 0
    private var viewH = 0
    /** Uptime when the current generation was created (partial counts are saved only for settled layouts). */
    private var generationBornAt = 0L

    private val scope = MainScope()
    private var genJob: Job = SupervisorJob(scope.coroutineContext[Job])

    private val layoutExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({ setPriority(Process.THREAD_PRIORITY_DEFAULT); r.run() }, "reader-layout").apply { isDaemon = true }
    }
    private val countExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({ setPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "reader-count").apply { isDaemon = true }
    }
    private val layoutDispatcher = layoutExec.asCoroutineDispatcher()
    private val countDispatcher = countExec.asCoroutineDispatcher()

    private val cache = HashMap<Int, SectionLayout>()
    private val lru = ArrayList<Int>()
    private var protectedSection = -1
    private val pending = HashMap<Int, Pending>()
    private var countJob: Job? = null
    private var closed = false

    /**
     * Sections whose content could not be loaded or typeset in this session (any thread): they show and count as
     * their error page, which is never written to the page-count cache.
     */
    private val failedSections: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** Counted sections of [layoutKey] already in the cache (loaded or saved), so an unchanged array isn't rewritten. */
    private var savedKnown = 0
    /** Page-count saves run on ReaderIo's pool: a save older than one already written is dropped (see [saveCounts]). */
    private var saveSeq = 0
    private val saveLock = Any()
    private var writtenSeq = 0 // guarded by saveLock

    /** Chapter span of the last [charsLeftInChapter] query (packed positions); empty until the first one. */
    private var spanFrom = Long.MAX_VALUE
    private var spanTo = Long.MIN_VALUE

    private val episodesOnce = Once<Episodes>()

    // Confined to the layout thread.
    private var layoutGenId = -1
    private var layoutMeasurer: AndroidTextMeasurer? = null
    // Confined to the count thread.
    private var countGenId = -1
    private var countMeasurer: AndroidTextMeasurer? = null
    // Main thread: renderer for drawing (same metrics as the layout measurer of the current settings).
    private var renderer: PageRenderer? = null
    private var rendererSettings: ReaderSettings? = null

    val isClosed: Boolean get() = closed

    /** Sets the page view size. Returns true when the geometry changed (a new generation was created). */
    fun setViewport(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width == viewW && height == viewH && generation != null) return false
        viewW = width
        viewH = height
        rebuild()
        return true
    }

    /** Applies new settings: RELAYOUT when layout-affecting fields changed, REPAINT for colours/footer only. */
    fun updateSettings(new: ReaderSettings): Change {
        if (new == settings) return Change.NONE
        // Only what can change this book's pages counts: the other format's options and weight steps that keep the
        // same font file (only the synthetic stroke changes) are a repaint.
        val relayout = LayoutKeys.layoutChanged(forLayout(settings), forLayout(new), document.format)
        settings = new
        if (!relayout) return Change.REPAINT
        rebuild()
        return Change.RELAYOUT
    }

    private fun rebuild() {
        if (closed || viewW <= 0 || viewH <= 0) return
        val dm = context.resources.displayMetrics
        val statusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.statusFontSizeSp, dm)
        val g = LayoutKeys.geometry(settings, viewW, viewH, dm.density, statusPx)
        genCounter++
        liveGenId = genCounter
        generationBornAt = SystemClock.uptimeMillis()
        generation = Generation(genCounter, settings, g, LayoutKeys.config(settings, g, txt = document.format == BookFormat.TXT), dm.density)
        invalidateJobs()
        cache.clear()
        lru.clear()
        counts.reset()
        counts.charsPerPageHint = LayoutKeys.charsPerPageHint(generation!!.config, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp, dm))
        layoutKey = null
        savedKnown = 0
    }

    private fun invalidateJobs() {
        genJob.cancel()
        genJob = SupervisorJob(scope.coroutineContext[Job])
        countJob = null
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.deferred.complete(null) }
    }

    /** Renderer matching the current settings (created lazily on the main thread; typefaces are cached). */
    fun renderer(): PageRenderer {
        val r = renderer
        if (r != null && rendererSettings === settings) return r
        val s = settings
        val m = AndroidTextMeasurer(context, s) { images.size(it) }
        return PageRenderer(context, m, images).also {
            renderer = it
            rendererSettings = s
        }
    }

    /** Cached layout of [section] (does not change the LRU order). */
    fun peek(section: Int): SectionLayout? = cache[section]

    /** Marks [section] as displayed: most recently used and never evicted while displayed. */
    fun touch(section: Int) {
        protectedSection = section
        if (lru.remove(section)) lru.add(section)
    }

    /**
     * Lays out [section] (or returns the cached layout) with priority over queued prefetches. Retries while the
     * generation keeps changing underneath (resize, settings); null when the session closed, no viewport is set
     * yet, or the layout genuinely failed in the current generation.
     */
    suspend fun layout(section: Int): SectionLayout? {
        if (section !in 0 until sectionCount) return null
        repeat(MAX_ATTEMPTS) {
            cache[section]?.let { return it }
            val p = request(section, foreground = true) ?: return null
            p.waiters++
            val l = try {
                p.deferred.await()
            } finally {
                p.waiters--
            }
            if (closed) return null
            // A rebuild may have slipped in between completion and this resumption: only return a layout
            // that still belongs to the current generation.
            if (l != null && p.gen === generation) {
                if (cache[section] !== l) store(section, l) // evicted before we resumed: keep it cached
                return l
            }
            // Same generation and no result: the layout itself failed (don't spin on it).
            if (l == null && p.gen === generation) return null
        }
        return null
    }

    /** Starts laying out [section] in the background if it is not cached yet. */
    fun prefetch(section: Int) {
        if (section in 0 until sectionCount && !cache.containsKey(section)) request(section, foreground = false)
    }

    private fun request(section: Int, foreground: Boolean): Pending? {
        if (closed) return null
        val gen = generation ?: return null
        // A jump must not wait behind neighbour prefetches queued on the single layout thread.
        if (foreground) dropIdlePrefetches(except = section)
        pending[section]?.let { return it }
        val p = Pending(gen)
        pending[section] = p
        p.job = scope.launch(genJob) {
            var result: SectionLayout? = null
            try {
                result = withContext(layoutDispatcher) {
                    p.started = true
                    layoutOnThread(gen, section)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "layout failed for section $section", t)
            } finally {
                if (pending[section] === p) pending.remove(section)
                val ok = result != null && gen === generation && !closed
                if (ok) store(section, result!!)
                p.deferred.complete(if (ok) result else null)
            }
        }
        return p
    }

    /** Cancels queued (not started) requests nobody waits for, so a foreground layout runs next. */
    private fun dropIdlePrefetches(except: Int) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val p = e.value
            if (e.key == except || p.waiters > 0 || p.started) continue
            it.remove()
            p.job?.cancel()
            p.deferred.complete(null)
        }
    }

    private fun store(section: Int, layout: SectionLayout) {
        val fresh = cache[section] !== layout
        cache[section] = layout
        lru.remove(section)
        lru.add(section)
        while (lru.size > MAX_CACHED) {
            val victim = lru.firstOrNull { it != protectedSection } ?: break
            lru.remove(victim)
            cache.remove(victim)
        }
        counts.set(section, layout.pageCount, layout.content.length)
        resolveAnchors(section, layout.content.anchors)
        if (fresh && cache[section] === layout) {
            try {
                listener?.onSectionStored(section, layout)
            } catch (t: Throwable) {
                Log.w(TAG, "stored listener failed", t)
            }
        }
    }

    // ------------------------------------------------------------------ worker-thread code

    private fun layoutOnThread(gen: Generation, section: Int): SectionLayout {
        if (layoutGenId != gen.id || layoutMeasurer == null) {
            layoutMeasurer = AndroidTextMeasurer(context, gen.settings) { images.size(it) }
            layoutGenId = gen.id
        }
        val m = StaleCheck(layoutMeasurer!!, gen.id)
        m.check()
        val content = loadContent(section).content
        return try {
            Typesetter(m, gen.config).layout(content)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "typesetting failed for section $section", t)
            failedSections.add(section)
            Typesetter(m, gen.config).layout(errorContent(t))
        }
    }

    /**
     * Counts [section] exactly as [layoutOnThread] lays it out: a section that fails is counted as its error
     * page, so page numbers stay consistent with what is shown (flagged so such counts are never cached).
     */
    private fun countOnThread(gen: Generation, section: Int): CountResult {
        if (countGenId != gen.id || countMeasurer == null) {
            countMeasurer = AndroidTextMeasurer(context, gen.settings) { images.size(it) }
            countGenId = gen.id
        }
        val m = StaleCheck(countMeasurer!!, gen.id)
        m.check()
        val loaded = loadContent(section)
        val c = loaded.content
        return try {
            val pages = Typesetter(m, gen.config).countPages(c)
            CountResult(pages, if (loaded.failed) -1 else c.length, c.anchors, loaded.failed)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "counting failed for section $section", t)
            failedSections.add(section)
            val pages = try {
                Typesetter(m, gen.config).countPages(errorContent(t))
            } catch (_: Throwable) {
                1
            }
            CountResult(pages, -1, emptyMap(), true)
        }
    }

    private class Loaded(val content: SectionContent, val failed: Boolean)

    /** Thrown on a worker thread when the generation it works for is gone (settings/size changed, closed). */
    private class StaleWork : CancellationException("stale layout generation")

    /**
     * Measurer wrapper giving the typesetter a cancellation point: measuring (once per paragraph run, the bulk of
     * a layout's cost) stops with [StaleWork] as soon as [genId] is no longer the live generation, so a stale
     * prefetch or count never holds up the layout the user is waiting for.
     */
    private inner class StaleCheck(private val inner: TextMeasurer, private val genId: Int) : TextMeasurer {
        override val emPx: Float get() = inner.emPx

        fun check() {
            if (liveGenId != genId) throw StaleWork()
        }

        override fun measure(text: String, start: Int, end: Int, style: RunStyle, out: FloatArray, outOffset: Int) {
            check()
            inner.measure(text, start, end, style, out, outOffset)
        }

        override fun metrics(style: RunStyle): FontMetricsPx = inner.metrics(style)

        override fun imageSize(src: String): IntSize? = inner.imageSize(src)
    }

    /** Section content; a readable error paragraph instead of a crash when the section can't be parsed. */
    private fun loadContent(section: Int): Loaded =
        try {
            Loaded(document.loadSection(section), false)
        } catch (t: Throwable) {
            Log.w(TAG, "loadSection($section) failed", t)
            failedSections.add(section)
            Loaded(errorContent(t), true)
        }

    private fun errorContent(t: Throwable): SectionContent {
        val msg = ReaderFormat.sectionError(t)
        return SectionContent(msg, listOf(ParagraphBlock(0, msg.length)))
    }

    // ------------------------------------------------------------------ page counting

    /**
     * Loads cached page counts for the current generation and counts the sections they lack in the background
     * (starting after [countDelayMs] so the first pages and prefetches win the CPU), saving as it goes.
     */
    fun startCounting(countDelayMs: Long = 0) {
        if (closed) return
        countJob?.cancel()
        val gen = generation ?: return
        countJob = scope.launch(genJob) {
            try {
                countAll(gen, countDelayMs)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Counting is an optimisation: never let it take the reader down (totals stay estimated).
                Log.w(TAG, "page counting failed", t)
            }
        }
    }

    /**
     * A2: the cache may hold a partial array (-1 = not counted yet), so counting resumes where the last session
     * stopped. Order ([CountOrder]): the section on screen, 3 samples at 25 / 50 / 75 %, then the rest in order.
     * The counts are saved every [SAVE_EVERY] counted sections, when complete, and on [close] (partial arrays only
     * for a settled layout, see [saveCounts]).
     */
    private suspend fun countAll(gen: Generation, countDelayMs: Long) {
        val key = withContext(Dispatchers.IO) { computeKey(gen) }
        if (gen !== generation) return
        layoutKey = key
        savedKnown = 0
        if (counts.isComplete) {
            // Every section was already laid out in the foreground (small book).
            notifyCounts(true)
            saveCounts(key)
            return
        }
        val saved = withContext(Dispatchers.IO) {
            try {
                Library.pageCounts(book.id, key)
            } catch (t: Throwable) {
                Log.w(TAG, "pageCounts failed", t)
                null
            }
        }
        if (gen !== generation) return
        if (saved != null && saved.size == sectionCount) {
            counts.setKnown(saved)
            savedKnown = PageCounts.countedIn(saved)
            if (counts.isComplete) {
                notifyCounts(true)
                saveCounts(key) // only when the foreground counted what the cache lacked
                return
            }
            if (savedKnown > 0) notifyCounts(false)
        }
        if (countDelayMs > 0) delay(countDelayMs)
        if (gen !== generation || closed) return
        // The section on screen, unless its foreground layout is still running (it counts itself when stored).
        val shown = protectedSection.takeIf { it >= 0 && !pending.containsKey(it) } ?: -1
        val order = CountOrder.plan(sectionCount, shown, samplableSections())
        var sinceSave = 0
        for (i in order) {
            if (gen !== generation || closed) return
            if (counts.isKnown(i)) continue
            val r = withContext(countDispatcher) { countOnThread(gen, i) }
            if (gen !== generation) return
            if (!counts.isKnown(i)) counts.set(i, r.pages, if (r.chars >= 0) r.chars else counts.charLength(i))
            resolveAnchors(i, r.anchors)
            val complete = counts.isComplete
            notifyCounts(complete)
            if (complete) break
            if (++sinceSave >= SAVE_EVERY) {
                sinceSave = 0
                saveCounts(key)
            }
        }
        saveCounts(key)
    }

    /**
     * Sections the counter may sample out of order (see [CountOrder.plan]): null (all) for TXT; for an EPUB only the
     * sections that are a whole spine item, none when the split plan can't be read.
     */
    private fun samplableSections(): BooleanArray? {
        if (document.format != BookFormat.EPUB) return null
        val out = BooleanArray(sectionCount)
        val parts = try {
            (document as? EpubBook)?.partCounts
        } catch (t: Throwable) {
            null
        } ?: return out
        var first = 0
        for (n in parts) {
            if (n == 1 && first < sectionCount) out[first] = true
            first += n
        }
        return if (first == sectionCount) out else BooleanArray(sectionCount)
    }

    private fun notifyCounts(complete: Boolean) {
        try {
            listener?.onCountsChanged(complete)
        } catch (t: Throwable) {
            Log.w(TAG, "counts listener failed", t)
        }
    }

    /**
     * Saves this generation's counts under [key] when they hold more than the cache has (partial: -1 for sections
     * not counted yet or counted as an error page). The array is copied here on the main thread; the write runs on
     * [ReaderIo] (outlives the activity) and is dropped when a later save of this session was written first.
     * Incomplete counts are saved only for a layout that has lasted [SAVE_SETTLE_MS]: the cache keeps 3 keys per
     * book, and saving every font size tried in the settings popup (or every TXT option, each a new session) would
     * push out the complete counts of the layout the reader goes back to.
     */
    private fun saveCounts(key: String) {
        if (key != layoutKey) return
        val known = counts.knownCount
        val age = SystemClock.uptimeMillis() - generationBornAt
        if (!CountSaves.due(known, savedKnown, counts.isComplete, age)) return
        val arr = counts.toArray()
        val counted = CountSaves.maskFailed(arr, failedSections)
        savedKnown = known
        if (counted <= 0) return
        val id = book.id
        val seq = ++saveSeq
        ReaderIo.launch {
            synchronized(saveLock) {
                if (seq > writtenSeq) {
                    Library.savePageCounts(id, key, arr)
                    writtenSeq = seq
                }
            }
        }
    }

    private fun resolveAnchors(section: Int, anchors: Map<String, Int>) {
        if (chapters.resolveAnchors(section, anchors)) {
            spanFrom = Long.MAX_VALUE
            spanTo = Long.MIN_VALUE
        }
    }

    private fun computeKey(gen: Generation): String {
        val fontIdentity = try {
            val f = FontManager.font(gen.settings.fontId)
            if (f == null) {
                "missing"
            } else {
                buildString {
                    append(f.source.name).append(':').append(f.path)
                    f.boldPath?.let { append('|').append(it) }
                    if (f.source != FontSource.BUNDLED) {
                        val file = File(f.path)
                        append(':').append(file.length()).append(':').append(file.lastModified())
                    }
                }
            }
        } catch (t: Throwable) {
            "unknown"
        }
        // The book file itself (an edited TXT keeps its id and section count but not its pages) and the
        // system font scale (sp → px) are part of the identity too.
        val bookFile = try {
            val f = File(book.path)
            "${f.length()}:${f.lastModified()}"
        } catch (t: Throwable) {
            "?"
        }
        val fontScale = context.resources.configuration.fontScale
        return LayoutKeys.keyFor(
            forLayout(gen.settings), document.format, book.encoding, gen.geometry, gen.density,
            "$fontIdentity|file=$bookFile|fs=$fontScale", LayoutKeys.ALGO_VERSION,
        )
    }

    /**
     * [s] with its font weight replaced by the layout weight class ([LayoutKeys.layoutWeight]): weights that
     * measure identically compare and hash the same, so e.g. 400 → 450 → 500 on a static font is only a repaint and
     * keeps the cached page counts. Falls back to the raw weight when the font can't be resolved.
     */
    private fun forLayout(s: ReaderSettings): ReaderSettings {
        val w = try {
            val f = FontManager.font(s.fontId) ?: FontManager.font(FontCatalog.DEFAULT_ID)
            if (f == null) {
                s.fontWeight
            } else {
                LayoutKeys.layoutWeight(s.fontWeight, f.variable, f.source == FontSource.SYSTEM, f.boldPath != null)
            }
        } catch (t: Throwable) {
            s.fontWeight
        }
        return if (w == s.fontWeight) s else s.copy(fontWeight = w)
    }

    /**
     * The book's episode numbers ([Episodes.of] over `document.toc` titles; index = TOC index), parsed once per
     * session on Dispatchers.Default by whichever asks first — the TOC dialog (via BookInsightsHost) or the footer's
     * 회차 item — and then reused. Never on the open path (≈ 20 ms for 2,000 titles on the device).
     * [onReady] runs on the main thread: immediately when already parsed, else when parsing ends (callers asking
     * meanwhile are queued, one parse only); with null when the TOC is empty, parsing threw, or the session closed.
     * Main thread only.
     */
    fun episodes(onReady: (Episodes?) -> Unit) {
        val deliver: (Episodes?) -> Unit = { e ->
            try {
                onReady(e)
            } catch (t: Throwable) {
                Log.w(TAG, "episodes listener failed", t)
            }
        }
        if (closed) {
            deliver(null)
            return
        }
        episodesOnce.get(deliver) {
            val toc = document.toc
            if (toc.isEmpty()) {
                episodesOnce.complete(null)
            } else {
                scope.launch {
                    val parsed = withContext(Dispatchers.Default) {
                        try {
                            Episodes.of(toc.map { it.title })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Log.w(TAG, "episode parse failed", t)
                            null
                        }
                    }
                    episodesOnce.complete(parsed)
                }
            }
        }
    }

    /**
     * Characters from (section, offset) to the end of the book (T1-7 "책 7시간 20분"; EPUB sections not laid out yet
     * count with their estimated length). O(1): see [PageCounts.charsFrom]. Main thread.
     */
    fun charsLeftInBook(section: Int, offset: Int): Long = counts.charsFrom(section, offset)

    /**
     * Characters from (section, offset) to where the next TOC entry starts, or to the end of the book after the last
     * one (T1-7 "이 화 3분"). The chapter around the position is looked up once ([ChapterIndex] scans the TOC) and
     * reused while later queries stay inside it, so a page turn within a chapter costs O(1); anchors resolved by a
     * layout or the counter invalidate it. Main thread.
     */
    fun charsLeftInChapter(section: Int, offset: Int): Long {
        val p = packPosition(section, offset)
        if (p < spanFrom || p >= spanTo) {
            val cur = chapters.indexAt(section, offset)
            val next = chapters.nextAfter(section, offset)
            spanFrom = if (cur >= 0) packPosition(chapters.section(cur), chapters.offset(cur)) else Long.MIN_VALUE
            spanTo = if (next >= 0) packPosition(chapters.section(next), chapters.offset(next)) else Long.MAX_VALUE
        }
        val to = spanTo
        if (to == Long.MAX_VALUE) return counts.charsFrom(section, offset)
        return counts.charsBetween(section, offset, (to ushr 32).toInt(), (to and 0xFFFFFFFFL).toInt())
    }

    /** Drops decoded images (memory pressure). */
    fun trimMemory() {
        try {
            images.clear()
        } catch (t: Throwable) {
            Log.w(TAG, "image cache clear failed", t)
        }
    }

    /**
     * Saves the page counts so far, cancels all work and closes the document once the worker threads are idle (the
     * close runs on the layout thread after any in-flight layout and after the counting thread finished its current
     * section). Callers still waiting for [episodes] get null.
     */
    fun close() {
        if (closed) return
        // What this generation counted so far (partial while counting ran), so the next open resumes from there.
        layoutKey?.let { saveCounts(it) }
        closed = true
        liveGenId = -1
        invalidateJobs()
        scope.cancel()
        cache.clear()
        lru.clear()
        episodesOnce.complete(null)
        val doc = document
        val imgs = images
        val counter = countExec
        try {
            layoutExec.execute {
                counter.shutdown()
                try {
                    counter.awaitTermination(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
                try {
                    imgs.clear()
                } catch (_: Throwable) {
                }
                try {
                    doc.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "document close failed", t)
                }
            }
        } catch (t: Throwable) {
            try {
                doc.close()
            } catch (_: Throwable) {
            }
        }
        layoutExec.shutdown()
    }

    companion object {
        private const val TAG = "BookSession"
        const val MAX_CACHED = 4
        /** How often a foreground layout is retried while the generation keeps changing underneath. */
        private const val MAX_ATTEMPTS = 8
        /** Partial page counts are saved after this many sections counted in the background (A2). */
        const val SAVE_EVERY = 25
        /** Age a layout generation needs before its partial page counts are saved (see [saveCounts]). */
        const val SAVE_SETTLE_MS = 30_000L

        /** (section, offset) as one comparable Long, like [ChapterIndex] orders positions. */
        private fun packPosition(section: Int, offset: Int): Long =
            (section.toLong() shl 32) or (offset.toLong() and 0xFFFFFFFFL)

        private fun setPriority(p: Int) {
            try {
                Process.setThreadPriority(p)
            } catch (_: Throwable) {
            }
        }
    }
}

/** When and what [BookSession] writes to the page-count cache (A2). Pure. */
internal object CountSaves {
    /**
     * True when counts with [known] counted sections should be written: they hold more than the cache has
     * ([savedKnown]), and they are [complete] or their layout generation is [ageMs] ≥ [BookSession.SAVE_SETTLE_MS] old.
     */
    fun due(known: Int, savedKnown: Int, complete: Boolean, ageMs: Long): Boolean =
        known > savedKnown && (complete || ageMs >= BookSession.SAVE_SETTLE_MS)

    /**
     * Turns the entries of [failed] sections (counted as their error page) in [arr] into -1, so they are counted again
     * next time instead of being cached. Returns the counts left in [arr].
     */
    fun maskFailed(arr: IntArray, failed: Collection<Int>): Int {
        for (s in failed) if (s in arr.indices) arr[s] = -1
        var n = 0
        for (v in arr) if (v >= 1) n++
        return n
    }
}

/**
 * A value computed once, on demand ([BookSession.episodes]): the first [get] starts the computation, callers asking
 * while it runs are queued, and every one of them gets the single result from [complete] — as does every later
 * caller, at once. Main thread only (the computation reports back through [complete] on the main thread); callbacks
 * must not throw.
 */
internal class Once<T : Any> {
    private var state = IDLE
    private var value: T? = null
    private var waiters: ArrayList<(T?) -> Unit>? = null

    val isDone: Boolean get() = state == DONE

    /** Delivers the value to [onReady]: now when known, else on [complete]. Only the first call runs [start]. */
    fun get(onReady: (T?) -> Unit, start: () -> Unit) {
        when (state) {
            DONE -> onReady(value)
            RUNNING -> waiters?.add(onReady)
            else -> {
                state = RUNNING
                waiters = arrayListOf(onReady)
                start()
            }
        }
    }

    /** Sets the result (null = none) and hands it to the waiting callers. Only the first call counts. */
    fun complete(result: T?) {
        if (state == DONE) return
        state = DONE
        value = result
        val w = waiters ?: return
        waiters = null
        for (cb in w) cb(result)
    }

    private companion object {
        const val IDLE = 0
        const val RUNNING = 1
        const val DONE = 2
    }
}
