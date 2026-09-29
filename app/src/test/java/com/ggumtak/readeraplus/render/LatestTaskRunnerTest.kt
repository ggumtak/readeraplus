package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LatestTaskRunnerTest {

    @Test
    fun keepsOnlyTheLatestPendingTask() {
        val runner = LatestTaskRunner("test-latest")
        val ran = Collections.synchronizedList(ArrayList<Int>())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        runner.submit {
            ran.add(0)
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // While the worker is busy, rapid page flips replace each other: only the last one may run.
        for (i in 1..5) runner.submit { ran.add(i) }
        runner.submit {
            ran.add(6)
            done.countDown()
        }
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(0, 6), ran.toList())
    }

    @Test
    fun runsEveryTaskWhenIdleAndSurvivesFailures() {
        val runner = LatestTaskRunner("test-idle")
        repeat(20) { i ->
            val latch = CountDownLatch(1)
            runner.submit {
                try {
                    if (i % 3 == 0) throw IllegalStateException("boom $i")
                } finally {
                    latch.countDown()
                }
            }
            assertTrue("task $i ran", latch.await(5, TimeUnit.SECONDS))
        }
        val last = CountDownLatch(1)
        runner.submit { last.countDown() }
        assertTrue(last.await(5, TimeUnit.SECONDS))
    }
}
