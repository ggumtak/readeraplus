package com.ggumtak.readeraplus.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.LruCache
import com.ggumtak.readeraplus.engine.IntSize
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.format.BookDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decoded image cache for one open document (LRU by bytes). Thread-safe.
 *
 * Drawing never decodes: [getForDraw] only looks in the cache and, on a miss, asks the book's one decoder ([loader])
 * for the picture and returns null, so the renderer draws the empty box of the page's layout (the layout never
 * depends on the picture: its size is fixed before it is decoded). When the picture arrives, [listener] hears of it
 * once, and only for a request a draw made; the reader repaints that picture's box if its page is still the one shown.
 * A turn normally decodes the page it turns to first ([get] on a background thread: it queues at the front and waits),
 * so the draw finds it.
 *
 * [size] (called by the typesetter on its background thread) also keeps the compressed bytes in a small LRU,
 * so a later decode usually never touches the book file.
 * Bitmaps are never recycled on eviction: a page being drawn may still hold one (GC frees them).
 * Opaque pictures are kept as RGB_565 (half the memory); transparent ones keep their alpha, so the page's own colour
 * shows through them (white, the 마루뷰어 grey, black under the night filter) and one bitmap suits every palette.
 * Every decode runs on one worker of [executor] ([ImageLoader]): the page on screen before the neighbours' prefetch,
 * one picture at one size at a time however many ask. [dispose] (the book closed) drops the queue and the cache.
 */
class ImageCache(
    private val document: BookDocument,
    maxBytes: Int = 24 * 1024 * 1024,
    executor: Executor = Dispatchers.IO.asExecutor(),
) {

    /** A picture a draw asked for is decoded and cached (worker thread): [layout] and [page] are what the draw had. */
    fun interface Listener {
        fun onImageReady(layout: Any, page: Int, src: String, w: Int, h: Int)
    }

    private val budget = maxBytes.coerceAtLeast(1024 * 1024)

    /** By picture and size: one picture at two sizes (a font size tried and back) keeps both, not one slot. */
    private val bitmaps = ImageLru<Bitmap>(budget) { it.allocationByteCount }

    private val raw = object : LruCache<String, ByteArray>(RAW_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    /** src → intrinsic size; a null value means "tried, not decodable". */
    private val sizes = HashMap<String, IntSize?>()

    /** Pictures at a size whose decode failed (not OOM): drawing them again must not decode on every frame. */
    private val failed = ImageKeyMap<Boolean>()

    /** Queue, dedupe and the single worker; a waiter on a decode running in the background raises its priority. */
    private val loader = ImageLoader<Bitmap>(
        executor,
        work = ::load,
        onStart = { r ->
            if (r.ownerId == 0) r.ownerId = DecodeBoost.myTid()
            DecodeBoost.background(!r.visible)
        },
        onIdle = { DecodeBoost.background(false) },
        onTransient = {
            // Out of memory: keep the most recently used quarter (the page on screen), drop the rest and the bytes.
            bitmaps.trimTo(budget / 4)
            raw.evictAll()
        },
        onDone = { r, _ -> notifyReady(r) },
    )

    @Volatile
    private var disposed = false

    private val drawMissCount = AtomicInteger()

    /** Bumped by [clear]: a renderer then prefetches the neighbours of the page it draws again, even the same page. */
    @Volatile
    var clears = 0
        private set

    /** Draws that found their picture missing and showed its empty box (0 expected on turns: they preload). */
    val drawMisses: Int get() = drawMissCount.get()

    /** Who hears of a picture a draw asked for once it is cached (null = nobody). Called on the worker thread. */
    @Volatile
    var listener: Listener? = null

    /**
     * Log tag for one line per draw that found a picture missing (null = off; the reader sets RAPerf's when that tag
     * is on DEBUG). Read only on that slow path.
     */
    @Volatile
    var drawTraceTag: String? = null

    /**
     * Bitmap scaled to fit within maxW x maxH (decoded with inSampleSize), or null. For background threads: queues
     * the decode ([visible]: the page about to be shown, before any neighbour prefetch), shares it with whoever
     * asked for this picture at this size already, and waits for it. The UI thread gets the cache's content only.
     */
    fun get(src: String, maxW: Int, maxH: Int, visible: Boolean = true): Bitmap? {
        if (maxW <= 0 || maxH <= 0) return null
        peek(src, maxW, maxH)?.let { return it }
        if (isKnownFailure(src, maxW, maxH)) return null
        if (onMainThread()) return null
        val r = loader.submit(src, maxW, maxH, visible, explicit = true) ?: return null
        if (visible && r.running) DecodeBoost.raise(r.ownerId)
        return r.await()
    }

    /**
     * [get] for [PageRenderer.draw] on the UI thread: reads nothing but the cache, so it neither waits, reads the book
     * file nor decodes. A miss returns null (the renderer draws the box it draws for a failed picture) and asks the
     * worker for the picture ([layout] and [page]: the page that wants it, see [Listener]), unless it failed for good
     * or the worker is on it already. Allocates nothing on a hit, a known failure or a picture already queued.
     */
    fun getForDraw(src: String, maxW: Int, maxH: Int, layout: Any?, page: Int): Bitmap? {
        if (maxW <= 0 || maxH <= 0) return null
        peek(src, maxW, maxH)?.let { return it }
        if (isKnownFailure(src, maxW, maxH)) return null
        val n = drawMissCount.incrementAndGet()
        drawTraceTag?.let { Log.d(it, "draw miss $n: $src ${maxW}x$maxH") }
        loader.submit(src, maxW, maxH, visible = true, explicit = false, ctx = layout, page = page)
        return null
    }

    /**
     * True when page [pageIndex] of [layout] has a picture not decoded at its drawn size yet and not known to be
     * undecodable. Allocates nothing while the page's pictures are cached and never reads the book file.
     */
    fun needsDecode(layout: SectionLayout, pageIndex: Int): Boolean =
        PageImages.needsDecode(layout, pageIndex) { src, w, h -> peek(src, w, h) == null && !isKnownFailure(src, w, h) }

    /** The decode itself, on the worker ([loader]). An OutOfMemoryError is the loader's to handle (transient). */
    private fun load(key: ImageKey): Bitmap? {
        if (disposed) return null
        // A decode of this picture that ended between the ask and the claim left its bitmap or failure behind.
        peek(key.src, key.w, key.h)?.let { return it }
        if (isKnownFailure(key.src, key.w, key.h)) return null
        val bytes = bytes(key.src)
        if (bytes == null) {
            markFailed(key)
            return null
        }
        val bmp = try {
            decode(bytes, key.w, key.h)
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed: ${key.src}", t)
            null
        }
        if (bmp == null) {
            markFailed(key)
            return null
        }
        // Refused (null result) when the book closed meanwhile: a closed cache is not filled again.
        return if (bitmaps.put(key, bmp)) bmp else null
    }

    private fun notifyReady(r: ImageLoader.Request<Bitmap>) {
        val layout = r.ctx ?: return
        val l = listener ?: return
        if (disposed) return
        l.onImageReady(layout, r.page, r.key.src, r.key.w, r.key.h)
    }

    /** True when decoding [src] at this size already failed (the renderer draws a placeholder box instead). */
    fun isKnownFailure(src: String, maxW: Int, maxH: Int): Boolean {
        synchronized(failed) {
            return !failed.isEmpty() && failed.contains(src, maxW, maxH)
        }
    }

    private fun markFailed(key: ImageKey) {
        synchronized(failed) { failed.put(key, true) }
    }

    /** Cached bitmap for exactly this target size, without decoding (null if not cached). Allocates nothing. */
    fun peek(src: String, maxW: Int, maxH: Int): Bitmap? {
        val b = bitmaps.get(src, maxW, maxH) ?: return null
        return if (!b.isRecycled) b else null
    }

    /** Intrinsic size without decoding pixels. */
    fun size(src: String): IntSize? {
        synchronized(sizes) {
            if (sizes.containsKey(src)) return sizes[src]
        }
        val bytes = try {
            bytes(src)
        } catch (oom: OutOfMemoryError) {
            return null // not remembered: the next layout asks again
        }
        val result = if (bytes == null) null else bounds(bytes)
        synchronized(sizes) { sizes[src] = result }
        return result
    }

    /**
     * Drops all decoded bitmaps, cached bytes and sizes, and the decodes still waiting as a neighbour prefetch (a
     * trim of memory: the visible page's requests and the one running stay).
     */
    fun clear() {
        clears++
        bitmaps.evictAll()
        raw.evictAll()
        synchronized(sizes) { sizes.clear() }
        synchronized(failed) { failed.clear() }
        loader.cancelPrefetch()
        loader.clearBackoff()
    }

    /**
     * The book closed: drops the queue (waiters get null) and every bitmap, and from now on nothing is decoded,
     * cached or announced. A decode already running ends unseen.
     */
    fun dispose() {
        disposed = true
        listener = null
        loader.dispose()
        bitmaps.close()
        try {
            raw.evictAll()
        } catch (t: Throwable) {
            // the close path never throws (and the desktop JVM's LruCache lacks LinkedHashMap.eldest, see the tests)
        }
        synchronized(sizes) { sizes.clear() }
        synchronized(failed) { failed.clear() }
    }

    /** Waits up to [timeoutMs] for the decode running now, if any (before the document closes); true when idle. */
    fun awaitIdle(timeoutMs: Long): Boolean = loader.awaitIdle(timeoutMs)

    private fun bytes(src: String): ByteArray? {
        raw.get(src)?.let { return it }
        val b = try {
            document.loadImage(src)
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (t: Throwable) {
            Log.w(TAG, "loadImage failed: $src", t)
            null
        } ?: return null
        if (b.isEmpty()) return null
        if (b.size <= RAW_BYTES / 4) raw.put(src, b)
        return b
    }

    private fun bounds(bytes: ByteArray): IntSize? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) IntSize(o.outWidth, o.outHeight) else null
    }

    private fun decode(bytes: ByteArray, maxW: Int, maxH: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val iw = o.outWidth
        val ih = o.outHeight
        if (iw <= 0 || ih <= 0) return null
        val fit = ImageMath.fitNoUpscale(iw, ih, maxW, maxH)
        val tw = ImageMath.packedW(fit)
        val th = ImageMath.packedH(fit)
        val d = BitmapFactory.Options()
        d.inSampleSize = ImageMath.sampleSize(iw, ih, tw, th)
        // Honoured only for opaque images (JPEG, opaque PNG/WebP/GIF): the decoder itself falls back to
        // ARGB_8888 when the image has an alpha channel, which is kept (no matte: the renderer draws it over the page).
        d.inPreferredConfig = Bitmap.Config.RGB_565
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d) ?: return null
        if (decoded.width == tw && decoded.height == th) return decoded
        // Scale to the target in one pass (an empty ARGB_8888 bitmap is transparent).
        val out = Bitmap.createBitmap(tw, th, if (decoded.hasAlpha()) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawBitmap(decoded, null, Rect(0, 0, tw, th), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        decoded.recycle()
        return out
    }

    internal companion object {
        private const val TAG = "ImageCache"

        /** Compressed bytes kept between layout (size) and decoding. */
        private const val RAW_BYTES = 8 * 1024 * 1024

        /** True on the UI thread, where nothing may wait for a decode. False where there is no Looper (JVM tests). */
        private fun onMainThread(): Boolean = try {
            Looper.getMainLooper()?.thread === Thread.currentThread()
        } catch (t: Throwable) {
            false
        }
    }
}

/**
 * Priority of the decode worker (Android). A neighbour prefetch decodes at THREAD_PRIORITY_BACKGROUND, which on
 * Android also moves the thread into the background scheduling group (low CPU share, low IO priority): a turn that
 * waits for that decode would wait at that pace. [raise] lifts such a thread to the default priority, never lowers one
 * (the UI thread runs above the default), and the worker sets its priority again before each request ([background]).
 * The worker is a pool thread shared with other IO: [background] (false) puts it back when the queue runs dry.
 */
internal object DecodeBoost {
    fun myTid(): Int = try {
        Process.myTid()
    } catch (t: Throwable) {
        0 // JVM tests
    }

    /** The calling thread to the background group ([on]) or back to the default priority. */
    fun background(on: Boolean) {
        try {
            Process.setThreadPriority(if (on) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_DEFAULT)
        } catch (t: Throwable) {
            // not allowed, or no Process in JVM tests: the decode only runs at the pool's priority
        }
    }

    fun raise(tid: Int) {
        if (tid == 0) return
        try {
            if (Process.getThreadPriority(tid) > Process.THREAD_PRIORITY_DEFAULT) {
                Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT)
            }
        } catch (t: Throwable) {
            // the thread is gone, or not allowed: the wait only takes longer
        }
    }
}
