package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.engine.Block
import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.PageInfo
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.SectionLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCoverageTest {

    private val w = 100
    private val h = 200

    private fun text(top: Float): LineInfo = LineInfo(0, 4, 0f, top, top + 16f, top + 20f, 0f, LineInfo.EXPAND_NONE)

    private fun image(x: Float, top: Float, iw: Float, ih: Float): LineInfo =
        LineInfo(4, 5, x, top, top + ih, top + ih, 0f, LineInfo.EXPAND_NONE, ImageBlock(4, "img.png"), iw, ih)

    private fun layout(vararg pages: List<LineInfo>, width: Int = w, height: Int = h): SectionLayout {
        val blocks: List<Block> = listOf(ParagraphBlock(0, 4), ImageBlock(4, "img.png"))
        return SectionLayout(
            SectionContent("글자들￼", blocks),
            LayoutConfig(width = width, height = height),
            pages.map { PageInfo(0, 5, it) },
            FloatArray(5),
        )
    }

    @Test
    fun textPagesHaveNoCoverage() {
        val l = layout(listOf(text(0f), text(20f)), emptyList())
        assertEquals(0f, ImageCoverage.of(l, 0), 0f)
        assertEquals(0f, ImageCoverage.of(l, 1), 0f)
    }

    @Test
    fun imageAreaOverContentArea() {
        // 100 × 100 of a 100 × 200 content box: half the page.
        val l = layout(listOf(text(0f), image(0f, 20f, 100f, 100f)))
        assertEquals(0.5f, ImageCoverage.of(l, 0), 1e-6f)
    }

    @Test
    fun severalImagesAddUp() {
        val l = layout(listOf(image(0f, 0f, 50f, 40f), text(40f), image(25f, 60f, 50f, 40f)))
        assertEquals((2000f + 2000f) / 20000f, ImageCoverage.of(l, 0), 1e-6f)
    }

    @Test
    fun onlyThePartInsideTheContentBoxCounts() {
        // 100 tall, starting 150 px down a 200 px box: 50 px of it is on the page; 20 px sticks out on the left.
        val l = layout(listOf(image(-20f, 150f, 120f, 100f)))
        assertEquals(100f * 50f / 20000f, ImageCoverage.of(l, 0), 1e-6f)
    }

    @Test
    fun neverAboveOne() {
        val l = layout(listOf(image(0f, 0f, 100f, 200f), image(0f, 0f, 100f, 200f)))
        assertEquals(1f, ImageCoverage.of(l, 0), 0f)
    }

    @Test
    fun smallPictureStaysBelowTheRefreshThreshold() {
        // 20 × 70 = 1,400 of 20,000 px² = 0.07 < 0.075: a small ornament is not a picture page.
        val small = ImageCoverage.of(layout(listOf(text(0f), image(40f, 20f, 20f, 70f))), 0)
        assertEquals(0.07f, small, 1e-6f)
        assert(small < 0.075f)
        val big = ImageCoverage.of(layout(listOf(image(0f, 0f, 100f, 16f))), 0)
        assert(big >= 0.075f)
    }

    @Test
    fun outOfRangePagesAndEmptyBoxesAreZero() {
        val l = layout(listOf(image(0f, 0f, 100f, 100f)))
        assertEquals(0f, ImageCoverage.of(l, -1), 0f)
        assertEquals(0f, ImageCoverage.of(l, 1), 0f)
        assertEquals(0f, ImageCoverage.of(layout(listOf(image(0f, 0f, 10f, 10f)), width = 0), 0), 0f)
        assertEquals(0f, ImageCoverage.of(layout(listOf(image(0f, 0f, 10f, 10f)), height = 0), 0), 0f)
    }

    @Test
    fun degenerateImageSizesCountAsNothing() {
        val l = layout(listOf(image(0f, 0f, 0f, 50f), image(0f, 0f, Float.NaN, 50f), image(0f, 0f, 50f, -5f)))
        assertEquals(0f, ImageCoverage.of(l, 0), 0f)
    }
}
