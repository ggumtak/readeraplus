package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.AllocCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ImageKeyMap] and [ImageLru]: lookups by (src, w, h) without a key String, and the cache's byte accounting. */
class ImageKeysTest {

    private class Blob(val bytes: Int)

    private fun lru(max: Int) = ImageLru<Blob>(max) { it.bytes }

    private fun put(c: ImageLru<Blob>, src: String, w: Int, h: Int, bytes: Int): Blob =
        Blob(bytes).also { c.put(ImageKey(src, w, h), it) }

    @Test
    fun mapFindsByPictureAndSize() {
        val m = ImageKeyMap<String>()
        m.put(ImageKey("a.jpg", 10, 20), "x")
        assertEquals("x", m.get("a.jpg", 10, 20))
        assertNull(m.get("a.jpg", 20, 10))
        assertNull(m.get("b.jpg", 10, 20))
        assertTrue(m.contains("a.jpg", 10, 20))
        m.remove(ImageKey("a.jpg", 10, 20))
        assertTrue(m.isEmpty())
    }

    @Test
    fun onePictureAtTwoSizesKeepsBoth() {
        val c = lru(1000)
        val small = put(c, "a.jpg", 100, 100, 10)
        val big = put(c, "a.jpg", 200, 200, 40)
        assertSame(small, c.get("a.jpg", 100, 100))
        assertSame(big, c.get("a.jpg", 200, 200))
        assertEquals(50L, c.byteCount)
    }

    @Test
    fun evictsLeastRecentlyUsedWhenOverBudget() {
        val c = lru(100)
        put(c, "a", 1, 1, 40)
        put(c, "b", 1, 1, 40)
        c.get("a", 1, 1) // a is now newer than b
        put(c, "c", 1, 1, 40)
        assertNull(c.get("b", 1, 1))
        assertEquals(40, c.get("a", 1, 1)!!.bytes)
        assertEquals(40, c.get("c", 1, 1)!!.bytes)
        assertEquals(80L, c.byteCount)
        assertEquals(2, c.count)
    }

    @Test
    fun replacingAnEntryCountsItsBytesOnce() {
        val c = lru(100)
        put(c, "a", 1, 1, 30)
        val newer = put(c, "a", 1, 1, 50)
        assertEquals(50L, c.byteCount)
        assertEquals(1, c.count)
        assertSame(newer, c.get("a", 1, 1))
    }

    @Test
    fun aValueLargerThanTheBudgetDoesNotStay() {
        val c = lru(100)
        put(c, "a", 1, 1, 60)
        put(c, "big", 1, 1, 500)
        assertNull(c.get("big", 1, 1))
        assertEquals(0L, c.byteCount)
    }

    @Test
    fun trimKeepsTheMostRecentlyUsed() {
        val c = lru(1000)
        put(c, "a", 1, 1, 100)
        put(c, "b", 1, 1, 100)
        put(c, "c", 1, 1, 100)
        c.get("a", 1, 1)
        c.trimTo(150)
        assertEquals(100L, c.byteCount)
        assertEquals(100, c.get("a", 1, 1)!!.bytes)
        assertNull(c.get("b", 1, 1))
        c.evictAll()
        assertEquals(0L, c.byteCount)
        assertEquals(0, c.count)
    }

    /** A decode that ends after its book closed does not fill the cache again. */
    @Test
    fun closedCacheRefusesPuts() {
        val c = lru(100)
        put(c, "a", 1, 1, 10)
        c.close()
        assertNull(c.get("a", 1, 1))
        assertFalse(c.put(ImageKey("b", 1, 1), Blob(10)))
        assertNull(c.get("b", 1, 1))
        assertEquals(0L, c.byteCount)
    }

    /** What a draw does for every picture of every frame: a hit, a miss and a failure lookup allocate nothing. */
    @Test
    fun lookupsAllocateNothing() {
        if (!AllocCounter.supported) return
        val c = lru(1000)
        put(c, "OEBPS/images/cover.jpg", 600, 800, 10)
        val failed = ImageKeyMap<Boolean>()
        failed.put(ImageKey("OEBPS/images/bad.png", 600, 800), true)
        var hits = 0
        val loop = {
            for (i in 0 until 10_000) {
                if (c.get("OEBPS/images/cover.jpg", 600, 800) != null) hits++
                if (c.get("OEBPS/images/other.jpg", 600, 800) == null) hits++
                synchronized(failed) { if (failed.contains("OEBPS/images/bad.png", 600, 800)) hits++ }
            }
        }
        repeat(3) { loop() }
        hits = 0
        assertEquals(0L, AllocCounter.measure(loop))
        assertEquals(30_000, hits)
    }
}
