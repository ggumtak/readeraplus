package com.ggumtak.readeraplus.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.util.LruCache
import com.ggumtak.readeraplus.engine.IntSize
import com.ggumtak.readeraplus.format.BookDocument

/**
 * Decoded image cache for one open document (LRU by bytes). Thread-safe.
 *
 * [size] (called by the typesetter on its background thread) also keeps the compressed bytes in a small LRU,
 * so a later [get] from the drawing thread usually only decodes and never touches the book file.
 * Bitmaps are never recycled on eviction: a page being drawn may still hold one (GC frees them).
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

    /** Bitmap scaled to fit within maxW x maxH (decoded with inSampleSize), or null. */
    fun get(src: String, maxW: Int, maxH: Int): Bitmap? {
        if (maxW <= 0 || maxH <= 0) return null
        peek(src, maxW, maxH)?.let { return it }
        if (isKnownFailure(src, maxW, maxH)) return null
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
        // ARGB_8888 when the image has an alpha channel, which is then composited onto white below.
        d.inPreferredConfig = Bitmap.Config.RGB_565
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d) ?: return null
        val alpha = decoded.hasAlpha()
        if (!alpha && decoded.width == tw && decoded.height == th) return decoded
        // Scale to the target and/or composite transparency onto white in one pass (RGB_565: half the memory).
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        if (alpha) c.drawColor(Color.WHITE)
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
