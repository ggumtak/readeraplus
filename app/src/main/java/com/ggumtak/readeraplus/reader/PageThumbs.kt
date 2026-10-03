package com.ggumtak.readeraplus.reader

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.reader.extras.ThumbBatch
import com.ggumtak.readeraplus.reader.extras.ThumbCell
import com.ggumtak.readeraplus.render.AndroidTextMeasurer
import com.ggumtak.readeraplus.render.Highlight
import com.ggumtak.readeraplus.render.PageDecor
import com.ggumtak.readeraplus.render.PageRenderer
import com.ggumtak.readeraplus.render.QuoteLook
import com.ggumtak.readeraplus.settings.ReaderSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The 페이지 썸네일 pipeline behind ReaderActivity's `PageThumbsHost` (NOTES_SPEC §12, library.md §3.3–3.4).
 * Uses only [BookSession]'s public API. Created on the first thumbnail request, never at open: +0 on open and turn.
 *
 * - **Mapping** (main): global pages → (section, pageIndex) with [ThumbMap]; sections not laid out go through
 *   [BookSession.layout] (the reader's layout thread and 4-entry LRU, so a tap into them is instant), then the mapping
 *   is resolved again, at most 3 rounds. Labels are `counts.globalPage`, the footer's own number. A warm grid page
 *   (every touched section counted, every cell in the LRU) needs neither `peek` nor a layout ([ThumbMap.cached]).
 * - **Rendering** (one shared "reader-thumbs" daemon thread, BACKGROUND priority, created on the first request): a
 *   [PageRenderer] per generation and paint version, built from `session.settings` at request time (day/night, colours
 *   and the weight stroke are repaints that keep the generation). Per cell `drawChrome` with
 *   `PageDecor(highlights, bookmarked = false, status = null)`, then `drawBody` (peeks at decoded images, never
 *   decodes), both on a uniform `canvas.scale` into an RGB_565 bitmap. Below [THUMB_GREY_SCALE] the renderer's
 *   [PageRenderer.thumbnail] flag draws the thumbnail greys without quote strokes.
 * - **One e-ink update per grid page**: without `progressive` the batch is reported once complete, or partially
 *   after [PARTIAL_MS] and then complete; with `progressive` (phones) at once with placeholders, then as cells finish,
 *   ≥ [PROGRESS_MS] apart. A complete batch is followed by a prefetch of the next grid page in the direction of travel
 *   (no callback; cancelled by any request).
 * - **Memory**: an LRU of [ThumbBudget] bytes keyed by [ThumbKey]; cleared on a generation or paint change, by
 *   [clear] (`onTrimMemory ≥ RUNNING_LOW`) and [close].
 *
 * Main thread only (except the private render step). Transient highlight owners (TTS, selection, jump, search) are
 * the [Source]'s business: it returns only the persistent ones.
 */
class PageThumbs(
    context: Context,
    private val session: BookSession,
    private val source: Source,
    /** E-ink device: the 8 MB LRU cap (phones 16 MB). */
    private val eink: Boolean,
) {
    /** ReaderActivity's side (main thread). */
    interface Source {
        /**
         * Persistent decor of one page: quote highlights (no transient owners) and the [ThumbCell] `MARK_*` flags
         * (bookmark, quote, note). Called on the main thread right before the page is rendered.
         */
        fun decorFor(section: Int, layout: SectionLayout, pageIndex: Int): Decor

        /** Bumped on every `Change.REPAINT` and reader colour change. */
        fun paintVersion(): Int

        /** Bumped only when [section]'s quotes or bookmarks change. */
        fun decorVersion(section: Int): Int

        /** Global 1-based page on screen (the footer's number). */
        fun currentPage(): Int

        /** Section on screen (its neighbours are prefetched again by [cancel]); -1 when none. */
        fun currentSection(): Int
    }

    /** [highlights] drawn into the bitmap; [marks] drawn by the grid on top of it. */
    class Decor(val highlights: List<Highlight>, val marks: Int) {
        companion object {
            @JvmField val NONE = Decor(emptyList(), 0)
        }
    }

    private class Entry(val bitmap: Bitmap, val marks: Int)

    /** Everything one render needs, captured on the main thread (immutable). */
    private class Task(
        val key: ThumbKey,
        val gen: BookSession.Generation,
        val settings: ReaderSettings,
        val layout: SectionLayout,
        val highlights: List<Highlight>,
        val marks: Int,
    )

    private val app: Context = context.applicationContext ?: context
    private val scope = MainScope()
    private var job: Job? = null
    /** Created on the first request; read by the render thread. */
    @Volatile private var lru: LruCache<ThumbKey, Entry>? = null
    @Volatile private var lruGen = -1
    @Volatile private var lruPaint = Int.MIN_VALUE
    @Volatile private var closed = false
    private var lastFirst = 0
    private var direction = 1
    /** A thumbnail layout ran since the last [cancel]: the reader's neighbours may have been evicted. */
    private var layoutsRan = false

    // Render-thread state.
    private var renderer: PageRenderer? = null
    private var rendererGen = -1
    private var rendererPaint = Int.MIN_VALUE
    private var rendererSettings: ReaderSettings? = null
    private var canvas: ThumbCanvas? = null

    /**
     * Renders global pages [first, first + count) at [widthPx] × [heightPx] and reports to [onBatch] on the main
     * thread (see the class comment). Supersedes the previous request: its callback is not called again.
     */
    fun request(first: Int, count: Int, widthPx: Int, heightPx: Int, progressive: Boolean, onBatch: (ThumbBatch) -> Unit) {
        stop()
        if (closed || session.isClosed || count <= 0 || widthPx <= 0 || heightPx <= 0) return
        val gen = session.generation ?: return
        val cache = lru ?: newCache().also { lru = it }
        val paint = source.paintVersion()
        if (gen.id != lruGen || paint != lruPaint) {
            cache.evictAll()
            lruGen = gen.id
            lruPaint = paint
        }
        if (lastFirst > 0 && first != lastFirst) direction = if (first > lastFirst) 1 else -1
        lastFirst = first
        job = scope.launch {
            val done = fill(gen, first, count, widthPx, heightPx, progressive, onBatch)
            if (!done) return@launch
            val next = if (direction > 0) first + count else first - count
            val total = session.counts.total()
            if (next + count - 1 >= 1 && next <= total) {
                val from = next.coerceAtLeast(1)
                fill(gen, from, (next + count - from).coerceAtMost(total - from + 1), widthPx, heightPx, false, null)
            }
        }
    }

    /**
     * Cancels the running request and its prefetch ([PageThumbsHost.cancelThumbs]: the tab is left or the dialog
     * dismissed). When a thumbnail layout ran since the last call, the reader's `curSection ± 1` are prefetched again
     * in the background (thumbnail layouts may have evicted them). Returns whether that happened.
     */
    fun cancel(): Boolean {
        stop()
        if (!layoutsRan) return false
        layoutsRan = false
        if (closed || session.isClosed) return false
        val cur = source.currentSection()
        if (cur < 0) return false
        session.prefetch(cur + 1)
        session.prefetch(cur - 1)
        return true
    }

    /** Drops every cached thumbnail (`onTrimMemory ≥ RUNNING_LOW`). A running request carries on. */
    fun clear() {
        lru?.evictAll()
    }

    /** Session close / activity destroy: cancels everything and frees the bitmaps and the renderer. */
    fun close() {
        if (closed) return
        closed = true
        stop()
        scope.cancel()
        lru?.evictAll()
        lru = null
        // The thread exists only after a request; never create it just to clean up.
        executor?.execute {
            renderer = null
            rendererSettings = null
            canvas = null
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
    }

    /** Sized in bytes ([Bitmap.getAllocationByteCount]); evicted bitmaps are never recycled (the grid may show them). */
    private fun newCache(): LruCache<ThumbKey, Entry> = object : LruCache<ThumbKey, Entry>(budget()) {
        override fun sizeOf(key: ThumbKey, value: Entry): Int = value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    private fun budget(): Int {
        val mc = try {
            (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: 0
        } catch (_: RuntimeException) {
            0
        }
        return ThumbBudget.budget(if (mc > 0) mc else 64, eink)
    }

    /** One grid page (or its prefetch when [onBatch] is null). True when every cell finished for [gen]. */
    private suspend fun fill(
        gen: BookSession.Generation, first: Int, count: Int, w: Int, h: Int, progressive: Boolean,
        onBatch: ((ThumbBatch) -> Unit)?,
    ): Boolean = coroutineScope {
        try {
            fillIn(this, gen, first, count, w, h, progressive, onBatch)
        } finally {
            coroutineContext.cancelChildren() // the partial timer and a queued progress update
        }
    }

    private suspend fun fillIn(
        scope: CoroutineScope, gen: BookSession.Generation, first: Int, count: Int, w: Int, h: Int, progressive: Boolean,
        onBatch: ((ThumbBatch) -> Unit)?,
    ): Boolean {
        val t0 = SystemClock.uptimeMillis()
        val counts = session.counts
        val map = ThumbMap()
        map.resolve(counts, first, count)
        var bitmaps = arrayOfNulls<Bitmap>(map.size)
        var marks = IntArray(map.size)
        var labels: IntArray? = null
        var finished = false
        var lastEmit = 0L
        var emitQueued = false

        fun valid() = !closed && !session.isClosed && session.generation === gen

        fun emit(complete: Boolean) {
            val cb = onBatch ?: return
            if (!valid()) return
            val n = map.size
            val cells = ArrayList<ThumbCell>(n)
            for (i in 0 until n) {
                val page = labels?.get(i) ?: (map.first + i)
                cells.add(ThumbCell(page, map.sections[i], map.indices[i], bitmaps.getOrNull(i), marks.getOrElse(i) { 0 }))
            }
            lastEmit = SystemClock.uptimeMillis()
            cb(ThumbBatch(map.first, cells, counts.total(), source.currentPage(), complete))
        }

        fun progress() {
            if (!progressive || onBatch == null || emitQueued) return
            val wait = PROGRESS_MS - (SystemClock.uptimeMillis() - lastEmit)
            if (wait <= 0) {
                emit(false)
                return
            }
            emitQueued = true
            scope.launch {
                delay(wait)
                emitQueued = false
                if (!finished) emit(false)
            }
        }

        // Phones show placeholders only once a cell actually has to be rendered (a warm grid page is one update).
        var placeholders = !progressive || onBatch == null
        val timer = when {
            onBatch == null || progressive -> null
            else -> scope.launch {
                delay(PARTIAL_MS)
                if (!finished) emit(false)
            }
        }

        // 0: a warm grid page (every section counted, every cell in the LRU) maps without its layouts: thumbnail
        // layouts and the reader's prefetch evict each other from the session's MAX_CACHED layouts. No peek, no layout.
        val warm = lru
        if (warm != null && valid()) {
            val paint = source.paintVersion()
            val look = QuoteLook.generation
            val hits = arrayOfNulls<Entry>(map.size)
            val all = map.cached(counts, hits) { s, idx ->
                warm.get(ThumbKey(gen.id, s, idx, w, h, source.decorVersion(s), paint, look))
            }
            if (all) {
                val n = map.size
                for (i in 0 until n) {
                    val e = hits[i]!!
                    bitmaps[i] = e.bitmap
                    marks[i] = e.marks
                }
                labels = IntArray(n) { counts.globalPage(map.sections[it], map.indices[it]) }
                finished = true
                timer?.cancel()
                if (onBatch != null) emit(true)
                if (perf) Log.d(TAG, "${if (onBatch == null) "prefetch" else "grid"} p$first+$n: ${SystemClock.uptimeMillis() - t0} ms" +
                    " (rounds 0, hits $n, rendered 0)")
                return true
            }
        }

        // 1–3: map, laying out the touched sections (null value = the layout failed: not retried).
        val layouts = HashMap<Int, SectionLayout?>()
        val ready: (Int) -> Boolean = { s ->
            layouts.containsKey(s) || session.peek(s)?.also { layouts[s] = it } != null
        }
        val rounds = ThumbMap.converge(map, counts, first, count, ready) { s ->
            layoutsRan = true
            layouts[s] = session.layout(s)
        }
        scope.ensureActive()
        if (!valid()) return false
        for (s in map.missing(ready)) { // still touched after 3 rounds: lay out without re-mapping
            layoutsRan = true
            layouts[s] = session.layout(s)
        }
        scope.ensureActive()
        if (!valid()) return false
        map.clampIndices { s -> layouts[s]?.pageCount ?: 0 }
        val n = map.size
        if (bitmaps.size != n) {
            bitmaps = arrayOfNulls(n)
            marks = IntArray(n)
        }
        labels = IntArray(n) { counts.globalPage(map.sections[it], map.indices[it]) }

        // 4–5: cached cells, then the missing ones one at a time on the thumbs thread.
        val cache = lru ?: return false
        val paint = source.paintVersion()
        val look = QuoteLook.generation
        val settings = session.settings
        var hits = 0
        var rendered = 0
        for (i in 0 until n) {
            val s = map.sections[i]
            val idx = map.indices[i]
            val layout = layouts[s] ?: continue // failed section: an empty cell
            val key = ThumbKey(gen.id, s, idx, w, h, source.decorVersion(s), paint, look)
            val hit = cache.get(key)
            if (hit != null) {
                bitmaps[i] = hit.bitmap
                marks[i] = hit.marks
                hits++
                continue
            }
            val decor = try {
                source.decorFor(s, layout, idx)
            } catch (t: Throwable) {
                Log.w(TAG, "decor failed for $s/$idx", t)
                Decor.NONE
            }
            marks[i] = decor.marks
            if (!placeholders) {
                placeholders = true
                emit(false)
            }
            val task = Task(key, gen, settings, layout, decor.highlights, decor.marks)
            val e = withContext(dispatcher()) { renderOnThread(task) }
            scope.ensureActive()
            if (!valid()) return false
            bitmaps[i] = e?.bitmap
            rendered++
            progress()
        }
        finished = true
        timer?.cancel()
        if (onBatch != null) {
            if (progressive && lastEmit > 0L) {
                val wait = PROGRESS_MS - (SystemClock.uptimeMillis() - lastEmit)
                if (wait > 0) delay(wait)
                if (!valid()) return false
            }
            emit(true)
        }
        if (perf) Log.d(TAG, "${if (onBatch == null) "prefetch" else "grid"} p$first+$n: ${SystemClock.uptimeMillis() - t0} ms" +
            " (rounds $rounds, hits $hits, rendered $rendered)")
        return true
    }

    /** Render thread. Null when the page could not be drawn (the cell stays a numbered frame). */
    private fun renderOnThread(t: Task): Entry? {
        if (closed || lruGen != t.key.genId) return null
        return try {
            val g = t.gen.geometry
            val viewW = g.viewWidth
            val viewH = g.viewHeight
            if (viewW <= 0 || viewH <= 0) return null
            val r = rendererFor(t)
            val w = t.key.wPx
            val h = t.key.hPx
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val c = canvas ?: ThumbCanvas().also { canvas = it }
            c.setBitmap(bmp)
            c.logicalWidth = viewW
            val s = min(w.toFloat() / viewW, h.toFloat() / viewH)
            r.thumbnail = s < THUMB_GREY_SCALE
            val save = c.save()
            c.translate(((w - viewW * s) / 2f).roundToInt().toFloat(), ((h - viewH * s) / 2f).roundToInt().toFloat())
            c.scale(s, s)
            val decor = PageDecor(t.highlights, bookmarked = false, status = null)
            val cl = g.contentLeft.toFloat()
            val ct = g.contentTop.toFloat()
            r.drawChrome(c, decor, cl, ct, g.contentWidth.toFloat(), g.contentHeight.toFloat(), viewW, viewH)
            r.drawBody(c, t.layout, t.key.pageIndex, cl, ct, 0f, viewH.toFloat(), t.highlights)
            c.restoreToCount(save)
            c.setBitmap(null)
            val e = Entry(bmp, t.marks)
            if (!closed && lruGen == t.key.genId && lruPaint == t.key.paintVersion) lru?.put(t.key, e)
            e
        } catch (e: Throwable) {
            Log.w(TAG, "thumbnail failed for ${t.key.section}/${t.key.pageIndex}", e)
            canvas = null // a failed draw may leave the canvas mid-save
            null
        }
    }

    /** Render thread: one renderer per generation, paint version and settings. */
    private fun rendererFor(t: Task): PageRenderer {
        val r = renderer
        if (r != null && rendererGen == t.key.genId && rendererPaint == t.key.paintVersion && rendererSettings === t.settings) return r
        val images = session.images
        val m = AndroidTextMeasurer(app, t.settings) { images.size(it) }
        return PageRenderer(app, m, images).also {
            renderer = it
            rendererGen = t.key.genId
            rendererPaint = t.key.paintVersion
            rendererSettings = t.settings
        }
    }

    /**
     * `drawBody` clips to `canvas.width` in its own (scaled) coordinates: a bitmap-sized width would cut a scaled
     * page to its left quarter. This canvas reports the page view's width instead.
     */
    private class ThumbCanvas : Canvas() {
        var logicalWidth = 0
        override fun getWidth(): Int = if (logicalWidth > 0) logicalWidth else super.getWidth()
    }

    companion object {
        const val TAG = "RAThumbs"
        /** E-ink: the longest wait for a complete batch before a partial one is shown. */
        const val PARTIAL_MS = 700L
        /** Phones: the shortest gap between progressive updates. */
        const val PROGRESS_MS = 100L
        /** Below this scale the renderer draws thumbnail greys without quote strokes (QuoteStyles.thumbGrey). */
        const val THUMB_GREY_SCALE = 0.3f

        private val perf: Boolean by lazy {
            try {
                Log.isLoggable(TAG, Log.DEBUG)
            } catch (_: Throwable) {
                false
            }
        }

        @Volatile private var executor: ExecutorService? = null
        private var thumbDispatcher: CoroutineDispatcher? = null

        /** The shared "reader-thumbs" thread, created on the first request. Main thread. */
        private fun dispatcher(): CoroutineDispatcher {
            thumbDispatcher?.let { return it }
            val ex = Executors.newSingleThreadExecutor { r ->
                Thread({
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    } catch (_: Throwable) {
                    }
                    r.run()
                }, "reader-thumbs").apply { isDaemon = true }
            }
            executor = ex
            return ex.asCoroutineDispatcher().also { thumbDispatcher = it }
        }
    }
}
