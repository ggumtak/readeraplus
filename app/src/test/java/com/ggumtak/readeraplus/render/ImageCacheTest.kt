package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.format.BookDocument
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.DocMeta
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.SectionInfo
import com.ggumtak.readeraplus.format.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ImageCacheTest {

    /**
     * Document whose images are all missing (loadImage → null); counts the zip reads. With a [gate], each read
     * signals [reading] and then holds until the gate opens (a slow zip read / decode in progress).
     */
    private class MissingImages(private val gate: CountDownLatch? = null) : BookDocument {
        val loads = AtomicInteger()
        @Volatile var loadThread: Thread? = null
        val reading = CountDownLatch(1)
        override val file = File("/nonexistent/book.epub")
        override val format = BookFormat.EPUB
        override val meta = DocMeta("t")
        override val sections = emptyList<SectionInfo>()
        override val toc = emptyList<TocEntry>()
        override fun loadSection(index: Int) = SectionContent.EMPTY
        override fun loadImage(src: String): ByteArray? {
            loads.incrementAndGet()
            loadThread = Thread.currentThread()
            reading.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            return null
        }
        override fun coverImage(): ByteArray? = null
        override fun resolveLink(fromSection: Int, href: String): DocPosition? = null
        override fun resolveToc(entry: TocEntry) = DocPosition.START
        override fun close() {}
    }

    /**
     * Regression: an image that can't be loaded/decoded was looked up in the book file again on every draw of
     * its page (on the UI thread). Failures are now remembered per target size (until [ImageCache.clear]).
     */
    @Test
    fun failedImageIsNotReloadedOnEveryDraw() {
        val doc = MissingImages()
        val cache = ImageCache(doc)
        assertFalse(cache.isKnownFailure("img/a.jpg", 300, 400))
        repeat(10) { assertNull(cache.get("img/a.jpg", 300, 400)) }
        assertEquals(1, doc.loads.get())
        assertTrue(cache.isKnownFailure("img/a.jpg", 300, 400))
        assertFalse(cache.isKnownFailure("img/a.jpg", 301, 400))
        assertFalse(cache.isKnownFailure("img/b.jpg", 300, 400))
        // (clear() resets this too; android.util.LruCache.evictAll needs libcore's LinkedHashMap.eldest(),
        // which the desktop JVM lacks, so it is not exercised here.)
    }

    @Test
    fun sizeOfMissingImageIsCachedAsUnknown() {
        val doc = MissingImages()
        val cache = ImageCache(doc)
        repeat(5) { assertNull(cache.size("img/x.png")) }
        assertEquals(1, doc.loads.get())
    }

    @Test
    fun nonPositiveTargetsNeverTouchTheDocument() {
        val doc = MissingImages()
        val cache = ImageCache(doc)
        assertNull(cache.get("img/a.jpg", 0, 100))
        assertNull(cache.get("img/a.jpg", 100, -1))
        assertNull(cache.peek("img/a.jpg", 100, 100))
        assertNull(cache.getForDraw("img/a.jpg", 0, 100, null, 0))
        assertEquals(0, doc.loads.get())
        assertEquals(0, cache.drawMisses)
    }

    private fun thread(body: () -> Unit): Thread = Thread { body() }.apply {
        isDaemon = true
        start()
    }

    /** A neighbour prefetch and the preload of the page turned to ask for the same picture: one read, one decode. */
    @Test
    fun concurrentRequestsShareOneDecode() {
        val gate = CountDownLatch(1)
        val doc = MissingImages(gate)
        val cache = ImageCache(doc)
        val prefetch = thread { cache.get("img/a.jpg", 300, 400) }
        assertTrue(doc.reading.await(5, TimeUnit.SECONDS))
        val preload = thread { cache.get("img/a.jpg", 300, 400) }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (preload.state != Thread.State.WAITING && System.nanoTime() < end) Thread.sleep(1)
        assertEquals(Thread.State.WAITING, preload.state)
        gate.countDown()
        prefetch.join(5000)
        preload.join(5000)
        assertEquals(1, doc.loads.get())
        assertTrue(cache.isKnownFailure("img/a.jpg", 300, 400))
    }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond() && System.nanoTime() < end) Thread.sleep(1)
        assertTrue(what, cond())
    }

    /**
     * The draw on the UI thread neither waits for a decode running elsewhere nor starts a second one: it gets null at
     * once (the renderer draws its empty box) and the running decode is the one that serves it.
     */
    @Test
    fun drawNeverWaitsForOrRepeatsAnotherThreadsDecode() {
        val gate = CountDownLatch(1)
        val doc = MissingImages(gate)
        val cache = ImageCache(doc)
        val prefetch = thread { cache.get("img/a.jpg", 300, 400, visible = false) }
        assertTrue(doc.reading.await(5, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        assertNull(cache.getForDraw("img/a.jpg", 300, 400, null, 0))
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(1))
        assertEquals(1, doc.loads.get())
        assertEquals(1, cache.drawMisses)
        gate.countDown()
        prefetch.join(5000)
        assertEquals(1, doc.loads.get())
    }

    /**
     * A draw reads nothing and decodes nothing: it only asks. The picture nobody preloaded is read and decoded on the
     * decoder's worker, once, however many draws find it missing meanwhile.
     */
    @Test
    fun drawOnlyAsksTheWorkerForAMissingPicture() {
        val gate = CountDownLatch(1)
        val doc = MissingImages(gate)
        val cache = ImageCache(doc)
        val me = Thread.currentThread()
        assertNull(cache.getForDraw("img/a.jpg", 300, 400, null, 0))
        assertTrue(doc.reading.await(5, TimeUnit.SECONDS))
        assertTrue(doc.loadThread !== me)
        repeat(5) { assertNull(cache.getForDraw("img/a.jpg", 300, 400, null, 0)) }
        assertEquals(1, doc.loads.get())
        assertEquals(6, cache.drawMisses)
        gate.countDown()
        waitUntil("known failure") { cache.isKnownFailure("img/a.jpg", 300, 400) }
        // Known failure from now on: later draws touch neither the file nor the counter, and ask for nothing.
        repeat(3) { assertNull(cache.getForDraw("img/a.jpg", 300, 400, null, 0)) }
        assertEquals(1, doc.loads.get())
        assertEquals(6, cache.drawMisses)
    }

    /** One decoder: pictures a draw and a prefetch ask for are read one after the other, never side by side. */
    @Test
    fun picturesAreDecodedOneAtATime() {
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val reads = AtomicInteger()
        val doc = object : BookDocument by MissingImages() {
            override fun loadImage(src: String): ByteArray? {
                most.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                Thread.sleep(20)
                inside.decrementAndGet()
                reads.incrementAndGet()
                return null
            }
        }
        val cache = ImageCache(doc)
        val askers = (0 until 4).map { i -> thread { cache.get("img/$i.jpg", 300, 400, visible = i % 2 == 0) } }
        for (i in 4 until 8) cache.getForDraw("img/$i.jpg", 300, 400, null, 0)
        askers.forEach { it.join(5000) }
        waitUntil("all read") { reads.get() == 8 }
        assertEquals(1, most.get())
    }

    /** The book closed: what was queued is dropped (its waiters get null), nothing new is accepted or read. */
    @Test
    fun disposeDropsTheQueueAndRefusesNewRequests() {
        val gate = CountDownLatch(1)
        val doc = MissingImages(gate)
        val cache = ImageCache(doc)
        val first = thread { cache.get("img/a.jpg", 300, 400) }
        assertTrue(doc.reading.await(5, TimeUnit.SECONDS))
        var waiterResult: Any? = "unset"
        val waiter = thread { waiterResult = cache.get("img/b.jpg", 300, 400, visible = false) }
        cache.getForDraw("img/c.jpg", 300, 400, null, 0)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (waiter.state != Thread.State.WAITING && System.nanoTime() < end) Thread.sleep(1)
        cache.dispose()
        waiter.join(5000)
        assertNull(waiterResult)
        assertNull(cache.get("img/d.jpg", 300, 400))
        assertNull(cache.getForDraw("img/e.jpg", 300, 400, null, 0))
        gate.countDown()
        first.join(5000)
        assertTrue(cache.awaitIdle(5000))
        assertEquals(1, doc.loads.get())
    }

    /** The decoded bitmaps, the queued decodes and the failures share one key per picture and size. */
    @Test
    fun keyIsPerPictureAndSize() {
        val a = ImageKey("img/a.jpg", 300, 400)
        assertEquals(a, ImageKey("img/a.jpg", 300, 400))
        assertEquals(a.hashCode(), ImageKey("img/a.jpg", 300, 400).hashCode())
        assertFalse(a == ImageKey("img/a.jpg", 400, 300))
        assertFalse(a == ImageKey("img/a.jpg", 300, 401))
        assertFalse(a == ImageKey("img/b.jpg", 300, 400))
    }

    @Test
    fun pageNeedsDecodeUntilEachPictureIsSettled() {
        val doc = MissingImages()
        val cache = ImageCache(doc)
        fun image(src: String, top: Float) =
            LineInfo(0, 1, 0f, top, top + 100f, top + 100f, 0f, LineInfo.EXPAND_NONE, ImageBlock(0, src), 200.4f, 99.6f)
        val text = LineInfo(1, 3, 0f, 200f, 216f, 220f, 0f, LineInfo.EXPAND_NONE)
        val l = SectionLayout(
            SectionContent("￼글자", emptyList()),
            LayoutConfig(width = 600, height = 1000),
            listOf(PageInfo(0, 1, listOf(image("a.png", 0f), text, image("b.png", 100f))), PageInfo(1, 3, listOf(text))),
            FloatArray(3),
        )
        assertTrue(cache.needsDecode(l, 0))
        assertFalse(cache.needsDecode(l, 1))
        assertNull(cache.get("a.png", 200, 100))
        assertTrue(cache.needsDecode(l, 0))
        assertNull(cache.get("b.png", 200, 100))
        // Both undecodable at the drawn size: nothing left to wait for, the page shows at once (with the empty boxes).
        assertFalse(cache.needsDecode(l, 0))
        assertEquals(2, doc.loads.get())
    }
}
