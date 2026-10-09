package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.AllocCounter
import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.SectionLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn path's "decode before showing" check ([PageImages.needsDecode], used by [ImageCache.needsDecode] and the
 * reader's turn / flush): text pages and decoded pictures show at once, anything else is decoded off the UI thread.
 */
class PageImagesTest {

    private fun text(top: Float): LineInfo = LineInfo(0, 4, 0f, top, top + 16f, top + 20f, 0f, LineInfo.EXPAND_NONE)

    private fun image(src: String, top: Float, iw: Float, ih: Float): LineInfo =
        LineInfo(4, 5, 0f, top, top + ih, top + ih, 0f, LineInfo.EXPAND_NONE, ImageBlock(4, src), iw, ih)

    private fun layout(vararg pages: List<LineInfo>): SectionLayout = SectionLayout(
        SectionContent("글자들￼", emptyList()),
        LayoutConfig(width = 600, height = 1000),
        pages.map { PageInfo(0, 5, it) },
        FloatArray(5),
    )

    /** A cache as the check sees it: "src|w|h" of the decoded pictures and of the known failures. */
    private class Ready(vararg keys: String) {
        val keys = HashSet(keys.toList())
        val asked = ArrayList<String>()
        fun missing(src: String, w: Int, h: Int): Boolean {
            asked.add("$src|$w|$h")
            return !keys.contains("$src|$w|$h")
        }
    }

    private fun needs(l: SectionLayout, page: Int, ready: Ready): Boolean =
        PageImages.needsDecode(l, page) { src, w, h -> ready.missing(src, w, h) }

    @Test
    fun textPagesNeverAskTheCache() {
        val l = layout(listOf(text(0f), text(20f), text(40f)), emptyList())
        val ready = Ready()
        assertFalse(needs(l, 0, ready))
        assertFalse(needs(l, 1, ready))
        assertTrue(ready.asked.isEmpty())
    }

    @Test
    fun cachedPictureShowsAtOnceAndAnUncachedOneIsDecodedFirst() {
        val l = layout(listOf(text(0f), image("a.jpg", 20f, 300f, 400f)))
        assertFalse(needs(l, 0, Ready("a.jpg|300|400")))
        assertTrue(needs(l, 0, Ready()))
        assertTrue(needs(l, 0, Ready("b.jpg|300|400")))
    }

    @Test
    fun oneMissingPictureOfSeveralIsEnough() {
        val l = layout(listOf(image("a.jpg", 0f, 300f, 200f), text(200f), image("b.png", 220f, 300f, 200f)))
        assertFalse(needs(l, 0, Ready("a.jpg|300|200", "b.png|300|200")))
        assertTrue(needs(l, 0, Ready("a.jpg|300|200")))
        assertTrue(needs(l, 0, Ready("b.png|300|200")))
    }

    /** A font or margin change lays the picture out at another size: the bitmap cached at the old one does not count. */
    @Test
    fun pictureCachedAtAnotherSizeIsDecodedAgain() {
        val before = layout(listOf(image("a.jpg", 0f, 300f, 400f)))
        val after = layout(listOf(image("a.jpg", 0f, 270f, 360f)))
        val ready = Ready("a.jpg|300|400")
        assertFalse(needs(before, 0, ready))
        assertTrue(needs(after, 0, ready))
        assertEquals("a.jpg|270|360", ready.asked.last())
    }

    /** The size asked about is the renderer's draw size: the line's box rounded to px, at least 1. */
    @Test
    fun asksAtTheDrawnSize() {
        val ln = image("a.jpg", 0f, 299.6f, 400.4f)
        assertEquals(300, PageImages.width(ln))
        assertEquals(400, PageImages.height(ln))
        val l = layout(listOf(ln, image("dot.png", 400f, 0.2f, Float.NaN)))
        val ready = Ready("a.jpg|300|400")
        assertTrue(needs(l, 0, ready))
        assertEquals(listOf("a.jpg|300|400", "dot.png|1|1"), ready.asked)
    }

    @Test
    fun pagesOutOfRangeNeedNothing() {
        val l = layout(listOf(image("a.jpg", 0f, 300f, 400f)))
        val ready = Ready()
        assertFalse(needs(l, -1, ready))
        assertFalse(needs(l, 1, ready))
        assertFalse(needs(layout(), 0, ready))
        assertTrue(ready.asked.isEmpty())
    }

    /** It runs on every turn: a text page and a page of decoded pictures allocate nothing. */
    @Test
    fun checkAllocatesNothing() {
        if (!AllocCounter.supported) return
        val textPage = layout(List(30) { text(it * 20f) })
        val picturePage = layout(listOf(text(0f), image("a.jpg", 20f, 300f, 400f), text(420f)))
        val decoded = "a.jpg"
        var hits = 0
        val loop = {
            for (i in 0 until 10_000) {
                if (PageImages.needsDecode(textPage, 0) { _, _, _ -> true }) hits++
                if (PageImages.needsDecode(picturePage, 0) { src, w, h -> src != decoded || w != 300 || h != 400 }) hits++
            }
        }
        repeat(3) { loop() }
        // The least of three runs (as StatusModelTest): a JIT recompile or deopt landing inside one run is not the code
        // allocating (seen once here with other test runs on the machine).
        assertEquals(0L, (1..3).minOf { AllocCounter.measure(loop)!! })
        assertEquals(0, hits)
    }

    /** The repaint of a late picture finds its line by picture and drawn size, on the page that asked. */
    @Test
    fun findsThePictureLineOfAPage() {
        val a = image("a.jpg", 20f, 300f, 400f)
        val b = image("b.jpg", 440f, 300f, 200f)
        val l = layout(listOf(text(0f), a, b), listOf(image("a.jpg", 0f, 150f, 200f)))
        assertEquals(a, PageImages.find(l, 0, "a.jpg", 300, 400))
        assertEquals(b, PageImages.find(l, 0, "b.jpg", 300, 200))
        assertNull(PageImages.find(l, 0, "a.jpg", 150, 200))
        assertNull(PageImages.find(l, 0, "c.jpg", 300, 400))
        assertEquals(150f, PageImages.find(l, 1, "a.jpg", 150, 200)!!.imageWidth, 0f)
        assertNull(PageImages.find(l, 2, "a.jpg", 300, 400))
        assertNull(PageImages.find(l, -1, "a.jpg", 300, 400))
    }

    /** Only the picture's box is repainted: whole px outward plus the outline's px, never the page. */
    @Test
    fun repaintBoxIsThePicturesBoxInViewPx() {
        val ln = LineInfo(4, 5, 12.5f, 20.25f, 420.25f, 420.25f, 0f, LineInfo.EXPAND_NONE, ImageBlock(4, "a.jpg"), 299.5f, 400f)
        val out = IntArray(4)
        PageImages.bounds(ln, 40f, 100f, out)
        assertEquals(listOf(51, 119, 353, 522), out.toList())
    }
}

