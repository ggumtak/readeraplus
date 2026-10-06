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
        val reading = CountDownLatch(1)
        override val file = File("/nonexistent/book.epub")
        override val format = BookFormat.EPUB
        override val meta = DocMeta("t")
        override val sections = emptyList<SectionInfo>()
        override val toc = emptyList<TocEntry>()
        override fun loadSection(index: Int) = SectionContent.EMPTY
        override fun loadImage(src: String): ByteArray? {
            loads.incrementAndGet()
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
        assertNull(cache.getForDraw("img/a.jpg", 0, 100))
        assertEquals(0, doc.loads.get())
        assertEquals(0, cache.drawDecodes)
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

    /**
     * The draw on the UI thread neither waits for a decode running elsewhere nor starts a second one: it gets null at
     * once (the renderer draws its empty box) and counts no draw decode.
     */
    @Test
    fun drawNeverWaitsForOrRepeatsAnotherThreadsDecode() {
        val gate = CountDownLatch(1)
        val doc = MissingImages(gate)
        val cache = ImageCache(doc)
        val prefetch = thread { cache.get("img/a.jpg", 300, 400) }
        assertTrue(doc.reading.await(5, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        assertNull(cache.getForDraw("img/a.jpg", 300, 400))
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(1))
        assertEquals(1, doc.loads.get())
        assertEquals(0, cache.drawDecodes)
        assertEquals(1, cache.drawSkips)
        gate.countDown()
        prefetch.join(5000)
        assertEquals(1, doc.loads.get())
        assertEquals(1, cache.drawSkips)
    }

    /** The last resort: a picture nobody preloaded is decoded inside the draw, and counted (RAPerf "draw decode"). */
    @Test
    fun drawDecodesAnUnclaimedPictureItselfAndCountsIt() {
        val doc = MissingImages()
        val cache = ImageCache(doc)
        assertNull(cache.getForDraw("img/a.jpg", 300, 400))
        assertEquals(1, doc.loads.get())
        assertEquals(1, cache.drawDecodes)
        assertEquals(0, cache.drawSkips)
        // Known failure from now on: later draws touch neither the file nor the counter.
        repeat(3) { assertNull(cache.getForDraw("img/a.jpg", 300, 400)) }
        assertEquals(1, doc.loads.get())
        assertEquals(1, cache.drawDecodes)
        // Background decodes are not draw decodes.
        assertNull(cache.get("img/b.jpg", 300, 400))
        assertEquals(1, cache.drawDecodes)
    }

    /** The decoded bitmaps, the running decodes and the failures share one key per picture and size. */
    @Test
    fun keyIsPerPictureAndSize() {
        assertEquals("img/a.jpg|300|400", ImageCache.key("img/a.jpg", 300, 400))
        assertFalse(ImageCache.key("img/a.jpg", 300, 400) == ImageCache.key("img/a.jpg", 400, 300))
        assertFalse(ImageCache.key("img/a.jpg", 300, 400) == ImageCache.key("img/a.jpg", 300, 401))
        assertFalse(ImageCache.key("img/a.jpg", 300, 400) == ImageCache.key("img/b.jpg", 300, 400))
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
