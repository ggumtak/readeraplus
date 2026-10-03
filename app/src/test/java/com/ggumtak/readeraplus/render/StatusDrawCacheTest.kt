package com.ggumtak.readeraplus.render

import org.junit.Assert.*
import org.junit.Test

class StatusDrawCacheTest {
    @Test fun unchangedScrollFramesReuseTheSlotGeometry() {
        val cache = StatusDrawCache()
        val decor = StatusDecor()
        assertTrue(cache.changed(decor, 600f, 0f, 22f))
        repeat(10_000) { assertFalse(cache.changed(decor, 600f, 0f, 22f)) }
    }
    @Test fun everyRelevantChangeInvalidatesTheGeometry() {
        val cache = StatusDrawCache()
        val decor = StatusDecor()
        cache.changed(decor, 600f, 0f, 22f)
        decor.version++
        assertTrue(cache.changed(decor, 600f, 0f, 22f))
        assertTrue(cache.changed(decor, 500f, 0f, 22f))
        assertTrue(cache.changed(decor, 500f, 10f, 22f))
        assertTrue(cache.changed(decor, 500f, 10f, 18f))
        assertFalse(cache.changed(decor, 500f, 10f, 18f))
        assertTrue(cache.changed(StatusDecor().apply { version = decor.version }, 500f, 10f, 18f))
    }
}
