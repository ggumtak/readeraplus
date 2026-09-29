package com.ggumtak.readeraplus.reader

import android.content.Context
import android.os.Process
import android.util.Log
import android.util.TypedValue
import com.ggumtak.readeraplus.BuildConfig
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.engine.Typesetter
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.render.AndroidTextMeasurer
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
    private var viewW = 0
    private var viewH = 0

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
        val relayout = LayoutKeys.layoutChanged(settings, new)
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
        generation = Generation(genCounter, settings, g, LayoutKeys.config(settings, g, txt = document.format == BookFormat.TXT), dm.density)
        invalidateJobs()
        cache.clear()
        lru.clear()
        counts.reset()
        counts.charsPerPageHint = LayoutKeys.charsPerPageHint(generation!!.config, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp, dm))
        layoutKey = null
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
        cache[section] = layout
        lru.remove(section)
        lru.add(section)
        while (lru.size > MAX_CACHED) {
            val victim = lru.firstOrNull { it != protectedSection } ?: break
            lru.remove(victim)
            cache.remove(victim)
        }
        counts.set(section, layout.pageCount, layout.content.length)
        chapters.resolveAnchors(section, layout.content.anchors)
    }

    // ------------------------------------------------------------------ worker-thread code

    private fun layoutOnThread(gen: Generation, section: Int): SectionLayout {
        if (layoutGenId != gen.id || layoutMeasurer == null) {
            layoutMeasurer = AndroidTextMeasurer(context, gen.settings) { images.size(it) }
            layoutGenId = gen.id
        }
        val m = layoutMeasurer!!
        val content = loadContent(section).content
        return try {
            Typesetter(m, gen.config).layout(content)
        } catch (t: Throwable) {
            Log.w(TAG, "typesetting failed for section $section", t)
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
        val m = countMeasurer!!
        val loaded = loadContent(section)
        val c = loaded.content
        return try {
            val pages = Typesetter(m, gen.config).countPages(c)
            CountResult(pages, if (loaded.failed) -1 else c.length, c.anchors, loaded.failed)
        } catch (t: Throwable) {
            Log.w(TAG, "counting failed for section $section", t)
            val pages = try {
                Typesetter(m, gen.config).countPages(errorContent(t))
            } catch (_: Throwable) {
                1
            }
            CountResult(pages, -1, emptyMap(), true)
        }
    }

    private class Loaded(val content: SectionContent, val failed: Boolean)

    /** Section content; a readable error paragraph instead of a crash when the section can't be parsed. */
    private fun loadContent(section: Int): Loaded =
        try {
            Loaded(document.loadSection(section), false)
        } catch (t: Throwable) {
            Log.w(TAG, "loadSection($section) failed", t)
            Loaded(errorContent(t), true)
        }

    private fun errorContent(t: Throwable): SectionContent {
        val msg = when (t) {
            is OutOfMemoryError -> "메모리가 부족해 이 부분을 표시하지 못했습니다."
            else -> "이 부분을 불러오지 못했습니다." + (t.message?.let { " ($it)" } ?: "")
        }
        return SectionContent(msg, listOf(ParagraphBlock(0, msg.length)))
    }

    // ------------------------------------------------------------------ page counting

    /**
     * Loads cached page counts for the current generation or counts every section in the background
     * (starting after [countDelayMs] so the first pages and prefetches win the CPU), then saves them.
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

    private suspend fun countAll(gen: Generation, countDelayMs: Long) {
        val key = withContext(Dispatchers.IO) { computeKey(gen) }
        if (gen !== generation) return
        layoutKey = key
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
        if (saved != null && counts.setAll(saved)) {
            notifyCounts(true)
            return
        }
        if (countDelayMs > 0) delay(countDelayMs)
        var anyFailed = false
        for (i in 0 until sectionCount) {
            if (gen !== generation || closed) return
            if (counts.isKnown(i)) continue
            val r = withContext(countDispatcher) { countOnThread(gen, i) }
            if (gen !== generation) return
            if (r.failed) anyFailed = true
            if (!counts.isKnown(i)) counts.set(i, r.pages, if (r.chars >= 0) r.chars else counts.charLength(i))
            chapters.resolveAnchors(i, r.anchors)
            notifyCounts(counts.isComplete)
        }
        // Counts that include error pages (a section that could not be read this time) are not cached.
        if (!anyFailed) saveCounts(key)
    }

    private fun notifyCounts(complete: Boolean) {
        try {
            listener?.onCountsChanged(complete)
        } catch (t: Throwable) {
            Log.w(TAG, "counts listener failed", t)
        }
    }

    private suspend fun saveCounts(key: String) {
        val arr = counts.toArray() ?: return
        val id = book.id
        withContext(Dispatchers.IO) {
            try {
                Library.savePageCounts(id, key, arr)
            } catch (t: Throwable) {
                Log.w(TAG, "savePageCounts failed", t)
            }
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
        return LayoutKeys.key(
            gen.settings, gen.settings.parseOptions(book.encoding), gen.geometry, gen.density,
            "$fontIdentity|file=$bookFile|fs=$fontScale", BuildConfig.VERSION_CODE,
        )
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
     * Cancels all work and closes the document once the worker threads are idle (the close runs on the layout
     * thread after any in-flight layout and after the counting thread finished its current section).
     */
    fun close() {
        if (closed) return
        closed = true
        invalidateJobs()
        scope.cancel()
        cache.clear()
        lru.clear()
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

        private fun setPriority(p: Int) {
            try {
                Process.setThreadPriority(p)
            } catch (_: Throwable) {
            }
        }
    }
}
