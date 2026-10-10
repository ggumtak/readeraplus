package com.ggumtak.readeraplus.format

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadHintsTest {
    /** Runs [body] on a fresh thread and rethrows what it threw. */
    private fun <T> onThread(body: () -> T): T {
        var result: Result<T>? = null
        val t = Thread { result = runCatching(body) }
        t.start()
        t.join()
        return result!!.getOrThrow()
    }

    @Test
    fun defaultIsForeground() {
        assertFalse(onThread { LoadHints.isBackground })
    }

    @Test
    fun flagIsPerThread() {
        val seen = onThread {
            val before = LoadHints.isBackground
            LoadHints.markBackground()
            val after = LoadHints.isBackground
            // another thread started from here does not inherit the mark
            val child = onThread { LoadHints.isBackground }
            Triple(before, after, child)
        }
        assertFalse(seen.first)
        assertTrue(seen.second)
        assertFalse(seen.third)
        assertFalse(onThread { LoadHints.isBackground })
    }
}
