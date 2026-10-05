package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** [InFlight]: one decode per picture and size, shared by a prefetch and a demand; a draw never waits for it. */
class InFlightTest {

    /** A work that starts, then blocks until released. */
    private class Gate<V : Any>(private val value: V?) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runs = AtomicInteger()
        val work: () -> V? = {
            runs.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            value
        }
    }

    private fun thread(body: () -> Unit): Thread = Thread { body() }.apply {
        isDaemon = true
        start()
    }

    /**
     * Until [t] waits for another thread's work (an untimed wait: a [Gate] work of its own would be a timed one).
     */
    private fun awaitWaiting(t: Thread) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (t.state != Thread.State.WAITING && System.nanoTime() < end) Thread.sleep(1)
        assertEquals(Thread.State.WAITING, t.state)
    }

    @Test
    fun secondRequestSharesTheRunningWork() {
        val f = InFlight<String, Any>()
        val bitmap = Any()
        val gate = Gate(bitmap)
        val first = AtomicReference<Any?>()
        val second = AtomicReference<Any?>()
        val a = thread { first.set(f.run("a.jpg|300|400", wait = true, work = gate.work)) }
        assertTrue(gate.started.await(5, TimeUnit.SECONDS))
        assertTrue(f.isRunning("a.jpg|300|400"))
        val b = thread { second.set(f.run("a.jpg|300|400", wait = true, work = gate.work)) }
        // b waits for a's decode instead of starting a second one.
        awaitWaiting(b)
        assertEquals(1, gate.runs.get())
        gate.release.countDown()
        a.join(5000)
        b.join(5000)
        assertEquals(1, gate.runs.get())
        assertSame(bitmap, first.get())
        assertSame(bitmap, second.get())
        assertFalse(f.isRunning("a.jpg|300|400"))
    }

    /** The UI thread neither blocks nor works on a key another thread is running. */
    @Test
    fun noWaitCallerReturnsAtOnceWithoutWorking() {
        val f = InFlight<String, Any>()
        val gate = Gate(Any())
        val a = thread { f.run("k", wait = true, work = gate.work) }
        assertTrue(gate.started.await(5, TimeUnit.SECONDS))
        val drawRuns = AtomicInteger()
        val t0 = System.nanoTime()
        assertNull(f.run("k", wait = false) { drawRuns.incrementAndGet(); Any() })
        assertTrue("returned in ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(1))
        assertEquals(0, drawRuns.get())
        gate.release.countDown()
        a.join(5000)
        assertEquals(1, gate.runs.get())
    }

    @Test
    fun otherKeysRunSideBySide() {
        val f = InFlight<String, Any>()
        val gate = Gate(Any())
        val a = thread { f.run("a|300|400", wait = true, work = gate.work) }
        assertTrue(gate.started.await(5, TimeUnit.SECONDS))
        // The same picture at another size, and another picture, are not held up by it.
        val other = Any()
        assertSame(other, f.run("a|270|360", wait = false) { other })
        assertSame(other, f.run("b|300|400", wait = true) { other })
        gate.release.countDown()
        a.join(5000)
    }

    /** A failed decode (null) frees its key: the waiter gets null, and a later request decodes again. */
    @Test
    fun failureClearsTheEntry() {
        val f = InFlight<String, Any>()
        val gate = Gate<Any>(null)
        val first = AtomicReference<Any?>(Any())
        val second = AtomicReference<Any?>(Any())
        val a = thread { first.set(f.run("k", wait = true, work = gate.work)) }
        assertTrue(gate.started.await(5, TimeUnit.SECONDS))
        val b = thread { second.set(f.run("k", wait = true, work = gate.work)) }
        awaitWaiting(b)
        gate.release.countDown()
        a.join(5000)
        b.join(5000)
        assertNull(first.get())
        assertNull(second.get())
        assertEquals(1, gate.runs.get())
        assertFalse(f.isRunning("k"))
        val again = Any()
        assertSame(again, f.run("k", wait = true) { again })
    }

    /** A work that throws frees its key too, and its waiters get null instead of hanging. */
    @Test
    fun throwingWorkClearsTheEntryAndReleasesWaiters() {
        val f = InFlight<String, Any>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>()
        val waited = AtomicReference<Any?>(Any())
        val a = thread {
            try {
                f.run("k", wait = true) {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    throw OutOfMemoryError("test")
                }
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val b = thread { waited.set(f.run("k", wait = true) { Any() }) }
        awaitWaiting(b)
        release.countDown()
        a.join(5000)
        b.join(5000)
        assertFalse(b.isAlive)
        assertTrue(thrown.get() is OutOfMemoryError)
        assertNull(waited.get())
        assertFalse(f.isRunning("k"))
    }
}
