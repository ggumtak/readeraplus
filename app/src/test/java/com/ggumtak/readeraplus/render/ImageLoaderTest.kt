package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ImageLoader] and [ImageRepaint]: the queue's two priorities, one request per picture and size, the one worker,
 * backoff after running out of memory, dispose, and which finished pictures repaint.
 */
class ImageLoaderTest {

    /** Collects the worker tasks instead of running them: the test decides when the worker runs. */
    private class Manual : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }
    }

    private class Run(
        executor: Executor,
        clock: () -> Long = System::nanoTime,
        backoffNs: Long = ImageLoader.BACKOFF_NS,
        val behave: (ImageKey) -> String? = { "bmp:$it" },
    ) {
        val ran = ArrayList<String>()
        val done = ArrayList<String>()
        var transients = 0
        val loader: ImageLoader<String> = ImageLoader(
            executor,
            work = { k ->
                synchronized(ran) { ran.add(k.toString()) }
                behave(k)
            },
            onTransient = { transients++ },
            onDone = { r, v -> synchronized(done) { done.add("$v@${r.page}") } },
            clock = clock,
            backoffNs = backoffNs,
        )

        fun visible(src: String, ctx: Any? = null, page: Int = -1, explicit: Boolean = false) =
            loader.submit(src, 10, 20, visible = true, explicit = explicit, ctx = ctx, page = page)

        fun prefetch(src: String) = loader.submit(src, 10, 20, visible = false, explicit = true)
    }

    @Test
    fun visiblePicturesRunBeforeEveryQueuedPrefetch() {
        val ex = Manual()
        val t = Run(ex)
        t.prefetch("p1")
        t.prefetch("p2")
        t.visible("v1")
        t.prefetch("p3")
        t.visible("v2")
        assertEquals(1, ex.tasks.size) // one worker task, however many requests
        assertEquals(2, t.loader.queuedVisible)
        assertEquals(3, t.loader.queuedPrefetch)
        ex.runAll()
        assertEquals(listOf("v1|10|20", "v2|10|20", "p1|10|20", "p2|10|20", "p3|10|20"), t.ran)
    }

    @Test
    fun aVisibleAskMovesAQueuedPrefetchToTheFront() {
        val ex = Manual()
        val t = Run(ex)
        val p1 = t.prefetch("p1")!!
        t.prefetch("p2")
        val again = t.visible("p2")
        assertEquals(1, t.loader.queuedVisible)
        assertEquals(1, t.loader.queuedPrefetch)
        assertSame(again, t.loader.submit("p2", 10, 20, visible = false, explicit = true))
        ex.runAll()
        assertEquals(listOf("p2|10|20", "p1|10|20"), t.ran)
        assertEquals("bmp:p1|10|20", p1.result)
    }

    @Test
    fun aPictureAtASizeIsQueuedOnceWhoeverAsks() {
        val ex = Manual()
        val t = Run(ex)
        val a = t.visible("a", ctx = "L", page = 3)
        val b = t.visible("a", ctx = "L", page = 3)
        val c = t.prefetch("a")
        assertSame(a, b)
        assertSame(a, c)
        assertTrue(t.loader.isPending("a", 10, 20))
        // Another size is another picture.
        val other = t.loader.submit("a", 10, 21, visible = true, explicit = false)
        assertTrue(other !== a)
        ex.runAll()
        assertEquals(listOf("a|10|20", "a|10|21"), t.ran)
        assertFalse(t.loader.isPending("a", 10, 20))
        // Asked again once it ended: decoded again (the cache answers before this is reached).
        t.visible("a")
        ex.runAll()
        assertEquals(3, t.ran.size)
    }

    @Test
    fun theLastVisibleAskerIsTheOneNotified() {
        val ex = Manual()
        val t = Run(ex)
        t.visible("a", ctx = "L1", page = 1)
        t.visible("a", ctx = "L2", page = 2)
        // A blocking ask (no page) shares the request and does not take the page away.
        t.loader.submit("a", 10, 20, visible = true, explicit = true)
        t.prefetch("a")
        ex.runAll()
        assertEquals(listOf("bmp:a|10|20@2"), t.done)
    }

    @Test
    fun waitersGetTheResultAndFailuresGetNull() {
        val ex = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        try {
            val t = Run(ex, behave = { k -> if (k.src == "bad") null else "ok:${k.src}" })
            val good = t.visible("good", explicit = true)!!
            val bad = t.visible("bad", explicit = true)!!
            assertEquals("ok:good", good.await())
            assertNull(bad.await())
            assertTrue(good.isDone)
        } finally {
            ex.shutdownNow()
        }
    }

    /** However many threads ask, only one picture is being decoded at any moment. */
    @Test
    fun oneDecodeAtATime() {
        val pool = Executors.newFixedThreadPool(4) { r -> Thread(r).apply { isDaemon = true } }
        try {
            val inside = AtomicInteger()
            val most = AtomicInteger()
            val t = Run(pool, behave = { k ->
                most.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                Thread.sleep(5)
                inside.decrementAndGet()
                "ok:${k.src}"
            })
            val askers = (0 until 8).map { i ->
                Thread { t.loader.submit("p$i", 10, 20, visible = i % 2 == 0, explicit = true)?.await() }.apply { isDaemon = true; start() }
            }
            askers.forEach { it.join(10_000) }
            assertEquals(8, t.ran.size)
            assertEquals(1, most.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun outOfMemoryIsNotRetriedByDrawsUntilTheBackoffEndsButATurnMay() {
        var now = 0L
        val ex = Manual()
        var oom = true
        val t = Run(ex, clock = { now }, backoffNs = 1000L, behave = { k -> if (oom) throw OutOfMemoryError("test") else "ok:${k.src}" })
        val r = t.visible("a", ctx = "L", page = 0)!!
        ex.runAll()
        assertNull(r.result)
        assertEquals(1, t.transients)
        assertTrue(t.done.isEmpty())
        // Every draw of the page finds it missing and asks again: nothing is queued while it backs off.
        repeat(5) { assertNull(t.visible("a", ctx = "L", page = 0)) }
        assertTrue(ex.tasks.isEmpty())
        assertEquals(1, t.ran.size)
        now = 999L
        assertNull(t.visible("a", ctx = "L", page = 0))
        // An explicit ask (the preload of a turn) tries it now.
        oom = false
        val turn = t.visible("a", explicit = true)!!
        ex.runAll()
        assertEquals("ok:a", turn.result)
        assertEquals(2, t.ran.size)
        // Success ends the backoff; a draw's ask is accepted again.
        assertNotNull(t.visible("a", ctx = "L", page = 0))
    }

    @Test
    fun aDrawMayTryAgainOnceTheBackoffEnded() {
        var now = 0L
        val ex = Manual()
        var oom = true
        val t = Run(ex, clock = { now }, backoffNs = 1000L, behave = { k -> if (oom) throw OutOfMemoryError() else "ok:${k.src}" })
        t.visible("a")
        ex.runAll()
        assertNull(t.visible("a"))
        now = 1000L
        oom = false
        val r = t.visible("a", ctx = "L", page = 4)!!
        ex.runAll()
        assertEquals("ok:a", r.result)
        assertEquals(listOf("ok:a@4"), t.done)
    }

    @Test
    fun clearBackoffLetsDrawsAskAgain() {
        val ex = Manual()
        val t = Run(ex, behave = { throw OutOfMemoryError() })
        t.visible("a")
        ex.runAll()
        assertNull(t.visible("a"))
        t.loader.clearBackoff()
        assertNotNull(t.visible("a"))
    }

    @Test
    fun cancelPrefetchDropsOnlyTheQueuedPrefetch() {
        val ex = Manual()
        val t = Run(ex)
        val p = t.prefetch("p")!!
        val v = t.visible("v")!!
        t.loader.cancelPrefetch()
        assertTrue(p.isDone)
        assertNull(p.result)
        assertFalse(t.loader.isPending("p", 10, 20))
        ex.runAll()
        assertEquals(listOf("v|10|20"), t.ran)
        assertEquals("bmp:v|10|20", v.result)
    }

    /** The book closed: the queue is dropped, waiters released, nothing runs or is announced, nothing new accepted. */
    @Test
    fun disposeDropsQueuedWorkAndRefusesNewWork() {
        val ex = Manual()
        val t = Run(ex)
        val a = t.visible("a", ctx = "L", page = 1)!!
        val b = t.prefetch("b")!!
        t.loader.dispose()
        assertTrue(a.isDone && b.isDone)
        assertNull(a.result)
        assertNull(b.result)
        assertNull(t.visible("c"))
        assertNull(t.prefetch("d"))
        ex.runAll()
        assertTrue(t.ran.isEmpty())
        assertTrue(t.done.isEmpty())
    }

    /** A decode running when the book closes ends without a result, a notification or a cache fill. */
    @Test
    fun aDecodeRunningAtDisposeEndsUnseen() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        try {
            val t = Run(pool, behave = { k ->
                started.countDown()
                gate.await(5, TimeUnit.SECONDS)
                "ok:${k.src}"
            })
            val r = t.visible("a", ctx = "L", page = 1)!!
            val queued = t.prefetch("b")!!
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(r.running)
            t.loader.dispose()
            assertNull(queued.await())
            assertFalse(t.loader.awaitIdle(50))
            gate.countDown()
            assertNull(r.await())
            assertTrue(t.loader.awaitIdle(5000))
            assertTrue(t.done.isEmpty())
            assertEquals(listOf("a|10|20"), t.ran)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun noWorkerMeansNobodyWaitsForever() {
        val t = Run(Executor { throw RejectedExecutionException("shut down") })
        val r = t.visible("a", explicit = true)!!
        assertTrue(r.isDone)
        assertNull(r.await())
        assertFalse(t.loader.isPending("a", 10, 20))
        assertTrue(t.loader.awaitIdle(10))
    }

    @Test
    fun failedWorkThatThrowsIsAFailureNotACrash() {
        val ex = Manual()
        val t = Run(ex, behave = { throw IllegalStateException("corrupt") })
        val a = t.visible("a", ctx = "L", page = 0)!!
        val b = t.visible("b")!!
        ex.runAll()
        assertNull(a.result)
        assertNull(b.result)
        assertEquals(0, t.transients)
        // Not a transient failure: no backoff here (the cache's own failure set keeps it from being asked again).
        assertNotNull(t.visible("a"))
    }

    @Test
    fun onlyTheShownPageOfTheSameOpenLayoutRepaints() {
        val layout = Any()
        assertTrue(ImageRepaint.shouldRepaint(true, true, layout, layout, 4, 4))
        // The book closed, or the viewport is a scroll.
        assertFalse(ImageRepaint.shouldRepaint(false, true, layout, layout, 4, 4))
        assertFalse(ImageRepaint.shouldRepaint(true, false, layout, layout, 4, 4))
        // Another page is shown now, or the section was laid out again (a new layout object).
        assertFalse(ImageRepaint.shouldRepaint(true, true, layout, layout, 4, 5))
        assertFalse(ImageRepaint.shouldRepaint(true, true, layout, Any(), 4, 4))
        assertFalse(ImageRepaint.shouldRepaint(true, true, layout, null, 4, 4))
        // A request that did not come from a draw has no layout: nothing to repaint.
        assertFalse(ImageRepaint.shouldRepaint(true, true, null, null, -1, -1))
    }
}
