package com.ggumtak.readeraplus.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.util.LruCache
import com.ggumtak.readeraplus.engine.IntSize
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.format.BookDocument
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decoded image cache for one open document (LRU by bytes). Thread-safe.
 *
 * [size] (called by the typesetter on its background thread) also keeps the compressed bytes in a small LRU,
 * so a later [get] from the drawing thread usually only decodes and never touches the book file.
 * Bitmaps are never recycled on eviction: a page being drawn may still hold one (GC frees them).
 * Opaque pictures are kept as RGB_565 (half the memory); transparent ones keep their alpha, so the page's own colour
 * shows through them (white, the 마루뷰어 grey, black under the night filter) and one bitmap suits every palette.
 * One picture at one size is decoded by one thread at a time: a neighbour prefetch and the reader's preload of the page
 * it turns to share that decode ([InFlight]), and a draw never waits for it ([getForDraw]).
 */
class ImageCache(private val document: BookDocument, maxBytes: Int = 24 * 1024 * 1024) {

    private class Entry(val maxW: Int, val maxH: Int, val bitmap: Bitmap)

    private val bitmaps = object : LruCache<String, Entry>(maxBytes.coerceAtLeast(1024 * 1024)) {
        override fun sizeOf(key: String, value: Entry): Int = value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    private val raw = object : LruCache<String, ByteArray>(RAW_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    /** src → intrinsic size; a null value means "tried, not decodable". */
    private val sizes = HashMap<String, IntSize?>()

    /** "src|w|h" of decodes that failed (not OOM): drawing them again must not re-decode on every frame. */
    private val failed = HashSet<String>()

    /** "src|w|h" of the decodes running now. */
    private val decoding = InFlight<String, Bitmap>()

    private val drawDecodeCount = AtomicInteger()

    /** Bumped by [clear]: a renderer then prefetches the neighbours of the page it draws again, even the same page. */
    @Volatile
    var clears = 0
        private set

    /** Pictures [getForDraw] decoded on the drawing thread so far (its last resort: stays 0 while turns preload). */
    val drawDecodes: Int get() = drawDecodeCount.get()

    /**
     * Log tag for one line per picture [getForDraw] decodes, or leaves to another thread's decode (null = off; the
     * reader sets RAPerf's when that tag is on DEBUG). Read only on those slow paths.
     */
    @Volatile
    var drawTraceTag: String? = null

    /**
     * Bitmap scaled to fit within maxW x maxH (decoded with inSampleSize), or null. For background threads: while
     * another thread decodes this picture at this size, waits for that decode and returns its bitmap.
     */
    fun get(src: String, maxW: Int, maxH: Int): Bitmap? {
        if (maxW <= 0 || maxH <= 0) return null
        peek(src, maxW, maxH)?.let { return it }
        if (isKnownFailure(src, maxW, maxH)) return null
        return decoding.run(failKey(src, maxW, maxH), wait = true) { decodeOnce(src, maxW, maxH, inDraw = false) }
    }

    /**
     * [get] for [PageRenderer.draw] on the UI thread, which never waits: a picture another thread is decoding gives
     * null (the renderer draws the box it draws for a failed picture, and that decode landing redraws nothing, so a
     * turn stays one e-ink update) instead of a second decode. A picture nobody is decoding is decoded right here, as
     * before: the reader preloads every page it turns to, so this is the last resort, counted in [drawDecodes].
     */
    fun getForDraw(src: String, maxW: Int, maxH: Int): Bitmap? {
        if (maxW <= 0 || maxH <= 0) return null
        peek(src, maxW, maxH)?.let { return it }
        if (isKnownFailure(src, maxW, maxH)) return null
        val key = failKey(src, maxW, maxH)
        if (decoding.isRunning(key)) {
            drawTraceTag?.let { Log.d(it, "draw skip $src ${maxW}x$maxH: decoding on another thread") }
            return null
        }
        return decoding.run(key, wait = false) { decodeOnce(src, maxW, maxH, inDraw = true) }
    }

    /**
     * True when page [pageIndex] of [layout] has a picture not decoded at its drawn size yet and not known to be
     * undecodable. Allocates nothing while the page's pictures are cached and never reads the book file.
     */
    fun needsDecode(layout: SectionLayout, pageIndex: Int): Boolean =
        PageImages.needsDecode(layout, pageIndex) { src, w, h -> peek(src, w, h) == null && !isKnownFailure(src, w, h) }

    /** The decode itself, by one thread per picture and size at a time ([decoding]). */
    private fun decodeOnce(src: String, maxW: Int, maxH: Int, inDraw: Boolean): Bitmap? {
        // A decode of this key that ended between the caller's peek and its claim left its bitmap or failure behind.
        peek(src, maxW, maxH)?.let { return it }
        if (isKnownFailure(src, maxW, maxH)) return null
        val t0 = if (inDraw) System.nanoTime() else 0L
        val bmp = decodeNow(src, maxW, maxH)
        if (inDraw) {
            val n = drawDecodeCount.incrementAndGet()
            drawTraceTag?.let { Log.d(it, "draw decode $n: $src ${maxW}x$maxH ${(System.nanoTime() - t0) / 1_000_000} ms") }
        }
        return bmp
    }

    private fun decodeNow(src: String, maxW: Int, maxH: Int): Bitmap? {
        val bytes = bytes(src)
        if (bytes == null) {
            markFailed(src, maxW, maxH)
            return null
        }
        val bmp = try {
            decode(bytes, maxW, maxH)
        } catch (oom: OutOfMemoryError) {
            // Transient: free what we can and let a later draw try again.
            bitmaps.evictAll()
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed: $src", t)
            null
        }
        if (bmp == null) {
            markFailed(src, maxW, maxH)
            return null
        }
        bitmaps.put(src, Entry(maxW, maxH, bmp))
        return bmp
    }

    /** True when decoding [src] at this size already failed (the renderer draws a placeholder box instead). */
    fun isKnownFailure(src: String, maxW: Int, maxH: Int): Boolean {
        synchronized(failed) {
            return failed.isNotEmpty() && failed.contains(failKey(src, maxW, maxH))
        }
    }

    private fun markFailed(src: String, maxW: Int, maxH: Int) {
        synchronized(failed) { failed.add(failKey(src, maxW, maxH)) }
    }

    /** "src|w|h": the key of a failed decode and of a running one. */
    private fun failKey(src: String, maxW: Int, maxH: Int): String = "$src|$maxW|$maxH"

    /** Cached bitmap for exactly this target size, without decoding (null if not cached). */
    fun peek(src: String, maxW: Int, maxH: Int): Bitmap? {
        val e = bitmaps.get(src) ?: return null
        return if (e.maxW == maxW && e.maxH == maxH && !e.bitmap.isRecycled) e.bitmap else null
    }

    /** Intrinsic size without decoding pixels. */
    fun size(src: String): IntSize? {
        synchronized(sizes) {
            if (sizes.containsKey(src)) return sizes[src]
        }
        val bytes = bytes(src)
        val result = if (bytes == null) null else bounds(bytes)
        synchronized(sizes) { sizes[src] = result }
        return result
    }

    /** Drops all decoded bitmaps, cached bytes and sizes. */
    fun clear() {
        clears++
        bitmaps.evictAll()
        raw.evictAll()
        synchronized(sizes) { sizes.clear() }
        synchronized(failed) { failed.clear() }
    }

    private fun bytes(src: String): ByteArray? {
        raw.get(src)?.let { return it }
        val b = try {
            document.loadImage(src)
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

    private companion object {
        const val TAG = "ImageCache"
        /** Compressed bytes kept between layout (size) and drawing (get). */
        const val RAW_BYTES = 8 * 1024 * 1024
    }
}

/**
 * Work running now, by key (unit-tested): a caller asking for a key another thread is working on gets that work's
 * result instead of doing it a second time. No lock is held while working or waiting, and the key is free again as
 * soon as its work returns or throws, so a failure can be tried again. A work must not ask for its own key.
 */
internal class InFlight<K : Any, V : Any> {
    private class Call<V : Any> {
        val done = CountDownLatch(1)
        @Volatile var result: V? = null
    }

    private val calls = HashMap<K, Call<V>>()

    /** True while some thread runs the work of [key]. */
    fun isRunning(key: K): Boolean = synchronized(calls) { calls.containsKey(key) }

    /**
     * Runs [work] for [key], unless another thread is running it already: then waits for it and returns its result
     * (null when it failed or threw), or with [wait] = false returns null at once without working.
     */
    fun run(key: K, wait: Boolean, work: () -> V?): V? {
        val mine = Call<V>()
        val running = synchronized(calls) {
            val other = calls[key]
            if (other == null) calls[key] = mine
            other
        }
        if (running != null) {
            if (!wait) return null
            try {
                running.done.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            return running.result
        }
        try {
            val v = work()
            mine.result = v
            return v
        } finally {
            synchronized(calls) { calls.remove(key) }
            mine.done.countDown()
        }
    }
}
