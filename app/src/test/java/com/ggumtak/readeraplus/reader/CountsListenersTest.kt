package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountsListenersTest {
    @Test
    fun aListenerAddedTwiceRunsOnce() {
        val l = CountsListeners()
        var n = 0
        val f: () -> Unit = { n++ }
        l.add(f)
        l.add(f)
        assertEquals(1, l.size)
        l.fire()
        assertEquals(1, n)
    }

    @Test
    fun aRemovedListenerIsNotCalled() {
        val l = CountsListeners()
        var a = 0
        var b = 0
        val fa: () -> Unit = { a++ }
        val fb: () -> Unit = { b++ }
        l.add(fa)
        l.add(fb)
        l.remove(fa)
        l.fire()
        assertEquals(0, a)
        assertEquals(1, b)
        l.remove(fb)
        assertTrue(l.isEmpty)
        l.fire()
        assertEquals(1, b)
    }

    @Test
    fun aListenerMayRemoveItselfWhileFiring() {
        val l = CountsListeners()
        var a = 0
        var b = 0
        lateinit var fa: () -> Unit
        fa = { a++; l.remove(fa) }
        l.add(fa)
        l.add { b++ }
        l.fire()
        l.fire()
        assertEquals(1, a)
        assertEquals(2, b)
    }

    @Test
    fun aThrowingListenerDoesNotStopTheOthers() {
        val l = CountsListeners()
        var after = 0
        l.add { throw IllegalStateException("panel gone") }
        l.add { after++ }
        l.fire()
        assertEquals(1, after)
    }
}
