package com.ggumtak.readeraplus.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.widget.ImageView
import com.ggumtak.readeraplus.data.Book
import com.ggumtak.readeraplus.render.Covers
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asynchronous library thumbnails. Process-wide so covers survive a trip to the reader and back:
 * - memory: one `LruCache` (~12 MB, by bitmap bytes), keyed by book id + file identity + encoding + size;
 * - work: two low-priority threads taking the **newest** request first (the rows currently on screen);
 *   requests whose ImageView was rebound to another book meanwhile are skipped before decoding;
 * - binding by tag: a finished bitmap is only set when the view still wants that key (no stale covers).
 * Disk caching is done by [Covers.thumbnail] itself.
 */
internal object CoverLoader {
    private const val MAX_BYTES = 12 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    /** Keys whose generation failed (don't retry while scrolling). Main thread only. */
    private val failed = HashSet<String>()

    /** key → views waiting for it. Guarded by [lock]. */
    private val pending = HashMap<String, ArrayList<WeakReference<ImageView>>>()
    private val lock = Any()

    private val main = Handler(Looper.getMainLooper())

    private val executor: ThreadPoolExecutor by lazy {
        val counter = AtomicInteger()
        val factory = ThreadFactory { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "cover-" + counter.incrementAndGet()).apply { isDaemon = true }
        }
        ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, LifoQueue(), factory).apply { allowCoreThreadTimeOut(true) }
    }

    /** Cache key: changes whenever the file, its forced encoding or the requested size changes. */
    fun keyFor(book: Book, w: Int, h: Int): String =
        "${book.id}_${book.modifiedAt}_${book.sizeBytes}_${book.encoding}_${w}x$h"

    /**
     * Shows the thumbnail of [book] in [view] (sized [w]×[h] px). Cached → immediately; otherwise the view is
     * cleared (its background is the placeholder box) and filled when ready. Main thread only.
     */
    fun bind(context: Context, view: ImageView, book: Book, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val key = keyFor(book, w, h)
        if (view.tag == key && view.drawable != null) return
        view.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        if (key in failed) return
        val app = context.applicationContext
        val submit: Boolean
        synchronized(lock) {
            val waiters = pending[key]
            if (waiters != null) {
                waiters += WeakReference(view)
                submit = false
            } else {
                pending[key] = arrayListOf(WeakReference(view))
                submit = true
            }
        }
        if (submit) executor.execute { load(app, book, key, w, h) }
    }

    private fun load(app: Context, book: Book, key: String, w: Int, h: Int) {
        // Skip work nobody is waiting for any more (the row scrolled away and was rebound).
        val wanted = synchronized(lock) {
            val waiters = pending[key]
            val any = waiters?.any { it.get()?.tag == key } == true
            if (!any) pending.remove(key)
            any
        }
        if (!wanted) return
        val bmp: Bitmap? = try {
            Covers.thumbnail(app, book, w, h)
        } catch (t: Throwable) {
            null
        }
        main.post {
            val waiters = synchronized(lock) { pending.remove(key) }
            if (bmp != null) cache.put(key, bmp) else failed += key
            waiters?.forEach { ref ->
                val v = ref.get()
                if (v != null && v.tag == key && bmp != null) v.setImageBitmap(bmp)
            }
        }
    }

    /** Drops memory entries of [bookId] (after an encoding/meta change); the caller invalidates the disk cache. */
    fun forget(bookId: Long) {
        val prefix = "${bookId}_"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        failed.removeAll { it.startsWith(prefix) }
    }

    /** Frees memory (called from onTrimMemory). */
    fun trim(all: Boolean) {
        if (all) cache.evictAll() else cache.trimToSize(MAX_BYTES / 2)
        failed.clear()
    }

    /** LIFO work queue: ThreadPoolExecutor enqueues with offer() and takes from the head. */
    private class LifoQueue : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }
}
