package com.ggumtak.readeraplus.render

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The one decoder of a book's pictures (unit-tested, no Android classes): requests wait in two queues, the pictures
 * of the page on screen before the neighbours' prefetch, and a single worker (one task at a time on [executor], the
 * app's IO pool) takes them in that order. A picture at a size is queued or running once, whoever asks and however
 * often: later askers share its [Request], and a visible ask moves a queued prefetch to the front queue. The
 * loader never touches a bitmap or a file itself; [work] does, and a draw that finds nothing only calls [submit].
 *
 * A transient failure ([OutOfMemoryError] in [work]) is not retried by the draws that follow it: for [backoffNs] only
 * an explicit ask ([submit] with `explicit`, a turn's preload) tries the picture again. [dispose] drops every queued
 * request and refuses new ones; the one running ends without a notification.
 */
internal class ImageLoader<V : Any>(
    private val executor: Executor,
    /** Decodes one picture on the worker thread; null = nothing to show (failed or cancelled). */
    private val work: (ImageKey) -> V?,
    /** The worker is about to run [Request]: its thread priority follows [Request.visible]. */
    private val onStart: (Request<V>) -> Unit = {},
    /** The worker ran out of requests: put its thread back. */
    private val onIdle: () -> Unit = {},
    /** [work] ran out of memory: free what can be freed. */
    private val onTransient: () -> Unit = {},
    /** A request ended with a value and the loader is not disposed (a draw's request repaints there). */
    private val onDone: (Request<V>, V) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::nanoTime,
    private val backoffNs: Long = BACKOFF_NS,
) {
    /**
     * One picture at one size, queued or running. [ctx] and [page] say which page of which layout asked last for a
     * draw (null / -1 for a blocking [ImageCache.get]): they decide whether the finished picture repaints anything.
     */
    class Request<V : Any> internal constructor(val key: ImageKey, visible: Boolean, ctx: Any?, page: Int) {
        @Volatile var visible: Boolean = visible
            internal set
        @Volatile var ctx: Any? = ctx
            internal set
        @Volatile var page: Int = page
            internal set
        /** The worker thread's id once it runs this (0 = not yet / unknown): a waiter raises its priority. */
        @Volatile var ownerId: Int = 0
        @Volatile var running: Boolean = false
            internal set
        @Volatile var result: V? = null
            private set
        private val done = CountDownLatch(1)

        internal fun finish(v: V?) {
            result = v
            done.countDown()
        }

        /** Waits until the request ended; null when it failed, was cancelled or the wait was interrupted. */
        fun await(): V? {
            try {
                done.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            return result
        }

        val isDone: Boolean get() = done.count == 0L
    }

    private val lock = Object()
    private val byKey = ImageKeyMap<Request<V>>()
    private val visibleQueue = ArrayDeque<Request<V>>()
    private val prefetchQueue = ArrayDeque<Request<V>>()
    private val backoff = ImageKeyMap<Long>()
    private var draining = false
    @Volatile private var disposed = false
    private val drainTask = Runnable { drain() }

    val isDisposed: Boolean get() = disposed

    /** Requests waiting (not the running one) in the visible and the prefetch queue (tests). */
    val queuedVisible: Int get() = synchronized(lock) { visibleQueue.size }
    val queuedPrefetch: Int get() = synchronized(lock) { prefetchQueue.size }

    /** True while the picture is queued or running. */
    fun isPending(src: String, w: Int, h: Int): Boolean = synchronized(lock) { byKey.contains(src, w, h) }

    /**
     * Queues the picture unless it is queued or running already (then its request is shared, moved to the visible
     * queue when [visible], and aimed at [ctx] / [page] when it is a visible ask with a [ctx]). Returns the request,
     * or null when the loader is disposed or the picture is in backoff and the ask is not [explicit]. Allocates
     * only for a picture not queued yet.
     */
    fun submit(src: String, w: Int, h: Int, visible: Boolean, explicit: Boolean, ctx: Any? = null, page: Int = -1): Request<V>? {
        var start = false
        val r: Request<V>
        synchronized(lock) {
            if (disposed) return null
            val found = byKey.get(src, w, h)
            if (found != null) {
                if (visible) {
                    if (!found.visible) {
                        found.visible = true
                        if (!found.running && prefetchQueue.remove(found)) visibleQueue.addLast(found)
                    }
                    if (ctx != null) {
                        found.ctx = ctx
                        found.page = page
                    }
                }
                return found
            }
            if (!explicit && !backoff.isEmpty()) {
                val until = backoff.get(src, w, h)
                if (until != null && clock() - until < 0L) return null
            }
            r = Request(ImageKey(src, w, h), visible, ctx, page)
            byKey.put(r.key, r)
            (if (visible) visibleQueue else prefetchQueue).addLast(r)
            if (!draining) {
                draining = true
                start = true
            }
        }
        if (start) startDrain()
        return r
    }

    /** Drops the queued prefetch requests (a memory trim): their waiters get null. Visible ones and the running one stay. */
    fun cancelPrefetch() {
        val dropped = ArrayList<Request<V>>()
        synchronized(lock) {
            while (true) {
                val r = prefetchQueue.pollFirst() ?: break
                byKey.remove(r.key)
                dropped.add(r)
            }
        }
        for (r in dropped) r.finish(null)
    }

    /** Forgets the pictures that ran out of memory: the next draw may ask for them again. */
    fun clearBackoff() {
        synchronized(lock) { backoff.clear() }
    }

    /** Drops every queued request (their waiters get null) and refuses new ones for good. */
    fun dispose() {
        val dropped = ArrayList<Request<V>>()
        synchronized(lock) {
            disposed = true
            while (true) {
                val r = visibleQueue.pollFirst() ?: prefetchQueue.pollFirst() ?: break
                byKey.remove(r.key)
                dropped.add(r)
            }
            backoff.clear()
        }
        for (r in dropped) r.finish(null)
    }

    /** Waits up to [timeoutMs] for the worker to be idle (a disposed loader's last decode); true when it is. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(lock) {
            while (draining) {
                val left = end - System.nanoTime()
                if (left <= 0L) return false
                try {
                    lock.wait(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(left)))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return true
    }

    private fun startDrain() {
        try {
            executor.execute(drainTask)
        } catch (t: Throwable) {
            // No worker (a shut-down pool): nothing will ever run, so nobody may wait for it.
            val dropped = ArrayList<Request<V>>()
            synchronized(lock) {
                draining = false
                while (true) {
                    val r = visibleQueue.pollFirst() ?: prefetchQueue.pollFirst() ?: break
                    byKey.remove(r.key)
                    dropped.add(r)
                }
                lock.notifyAll()
            }
            for (r in dropped) r.finish(null)
        }
    }

    private fun drain() {
        try {
            while (true) {
                val r = synchronized(lock) {
                    val next = visibleQueue.pollFirst() ?: prefetchQueue.pollFirst()
                    if (next == null) {
                        draining = false
                        lock.notifyAll()
                    } else {
                        next.running = true
                    }
                    next
                } ?: return
                runOne(r)
            }
        } finally {
            try {
                onIdle()
            } catch (t: Throwable) {
                // only a thread priority
            }
        }
    }

    private fun runOne(r: Request<V>) {
        var value: V? = null
        var transient = false
        if (!disposed) {
            try {
                onStart(r)
            } catch (t: Throwable) {
                // only a thread priority
            }
            try {
                value = work(r.key)
            } catch (oom: OutOfMemoryError) {
                transient = true
            } catch (t: Throwable) {
                value = null
            }
        }
        if (disposed) value = null
        synchronized(lock) {
            byKey.remove(r.key)
            r.running = false
            if (transient) backoff.put(r.key, clock() + backoffNs) else if (value != null) backoff.remove(r.key)
        }
        r.finish(value)
        if (transient) {
            try {
                onTransient()
            } catch (t: Throwable) {
                // best effort
            }
        }
        if (value != null && !disposed) {
            try {
                onDone(r, value)
            } catch (t: Throwable) {
                // a listener's failure is not the decode's
            }
        }
    }

    companion object {
        /** How long a picture that ran out of memory is left alone by the draws that find it missing. */
        const val BACKOFF_NS = 30_000_000_000L
    }
}

/**
 * Whether a picture that just finished decoding repaints the screen (unit-tested): only when the book is still open
 * and the page that asked for it (same layout object, which a new layout generation never reuses, and same page
 * index) is the paged page on screen now. Anything else (another page, a relaid-out section, a scroll viewport,
 * a closed book) keeps the bitmap in the cache for the next time and paints nothing.
 */
internal object ImageRepaint {
    fun shouldRepaint(
        open: Boolean,
        paged: Boolean,
        requestedLayout: Any?,
        shownLayout: Any?,
        requestedPage: Int,
        shownPage: Int,
    ): Boolean = open && paged && requestedLayout != null && requestedLayout === shownLayout && requestedPage == shownPage
}
