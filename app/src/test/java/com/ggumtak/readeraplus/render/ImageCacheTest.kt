package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.engine.SectionContent
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
import java.util.concurrent.atomic.AtomicInteger

class ImageCacheTest {

    /** Document whose images are all missing (loadImage → null); counts the zip reads. */
    private class MissingImages : BookDocument {
        val loads = AtomicInteger()
        override val file = File("/nonexistent/book.epub")
        override val format = BookFormat.EPUB
        override val meta = DocMeta("t")
        override val sections = emptyList<SectionInfo>()
        override val toc = emptyList<TocEntry>()
        override fun loadSection(index: Int) = SectionContent.EMPTY
        override fun loadImage(src: String): ByteArray? {
            loads.incrementAndGet()
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
        assertEquals(0, doc.loads.get())
    }
}
