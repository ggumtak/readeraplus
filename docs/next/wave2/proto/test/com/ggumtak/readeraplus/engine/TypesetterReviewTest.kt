package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for issues found in the engine review. */
class TypesetterReviewTest {

    private val m = FakeMeasurer()

    private fun cfg(
        width: Int = 200,
        height: Int = 600,
        lineBreak: LineBreakMode = LineBreakMode.CHAR,
        widowOrphanControl: Boolean = false,
    ) = LayoutConfig(width, height, 1.5f, 0f, 0f, Align.LEFT, lineBreak, true, 1f, widowOrphanControl)

    private fun layout(content: SectionContent, c: LayoutConfig, measurer: TextMeasurer = m): SectionLayout {
        val l = Typesetter(measurer, c).layout(content)
        assertEquals("countPages == layout.pageCount", l.pageCount, Typesetter(measurer, c).countPages(content))
        LayoutChecks.checkAll(l, measurer)
        return l
    }

    private fun lineTexts(l: SectionLayout): List<String> = LayoutChecks.allLines(l)
        .filter { it.imageBlock == null && !it.isRule && it.end > it.start }
        .map { l.content.text.substring(it.start, it.end) }

    // --- break avoidance must not leave pages nearly empty ------------------------------------------------

    @Test
    fun longKeepWithNextChainDoesNotEmptyThePage() {
        // One body line, then eight 3-line keep-with-next blocks (24 lines) on a 20-line page. Moving the whole
        // chain used to leave page 1 with a single line and 19 blank ones.
        val b = SectionBuilder()
        b.para("가".repeat(10))
        repeat(8) { b.para("나".repeat(30), BlockStyle(keepWithNext = true)) }
        b.para("다".repeat(30))
        val l = layout(b.build(), cfg())
        assertEquals(20, l.pages[0].lines.size)
        // With widow/orphan control one line moves (the block's last line would be a widow), nothing more.
        val wo = layout(b.build(), cfg(widowOrphanControl = true))
        assertEquals(19, wo.pages[0].lines.size)
    }

    @Test
    fun headingListAfterAParagraphStaysOnThePage() {
        // A contents page styled with heading tags: "contents" paragraph + 20 one-line headings.
        val b = SectionBuilder()
        b.para("목차")
        repeat(20) { b.heading("제${it + 1}장") }
        b.para("본문")
        val l = layout(b.build(), cfg(height = 600))
        assertTrue("page 1 keeps the headings: ${l.pages[0].lines.size}", l.pages[0].lines.size > 5)
    }

    @Test
    fun overlongHeadingIsSplitInsteadOfLeavingAGap() {
        // Body 5 lines (0..150), heading (1 em before) of 16 lines x 36 px from 170: 11 lines fit (to 566).
        // Moving 396 px (> half the page) would leave page 1 two-thirds empty, so the heading splits.
        val b = SectionBuilder()
        b.para("가".repeat(50))
        val h = b.heading("나".repeat(8 * 16))
        b.para("다".repeat(10))
        val l = layout(b.build(), cfg())
        assertEquals(5 + 11, l.pages[0].lines.size)
        assertTrue(l.pages[1].start > h)
        // A short heading (3 lines on the page) is still moved whole.
        val b2 = SectionBuilder()
        b2.para("가".repeat(150))
        val h2 = b2.heading("나".repeat(8 * 5))
        b2.para("다".repeat(10))
        val l2 = layout(b2.build(), cfg())
        assertEquals(15, l2.pages[0].lines.size)
        assertEquals(h2, l2.pages[1].start)
    }

    // --- images: declared CSS size -----------------------------------------------------------------------

    private fun imageSection(block: ImageBlock) = SectionContent(OBJECT_CHAR.toString(), listOf(block))

    @Test
    fun declaredImageSizeIsTheIntrinsicSize() {
        val mm = FakeMeasurer(images = mapOf("ornament" to IntSize(1000, 500)))
        val c = cfg(width = 400, height = 600)
        // Declared 50x25 CSS px (< 40% of W): shown at 2x = 100x50, not stretched to the full width.
        val both = layout(imageSection(ImageBlock(0, "ornament", 50, 25)), c, mm).pages[0].lines.single()
        assertEquals(100f, both.imageWidth, 0.01f)
        assertEquals(50f, both.imageHeight, 0.01f)
        assertEquals(150f, both.x, 0.01f)
        // Only the width declared: the height follows the bitmap's aspect ratio.
        val w = layout(imageSection(ImageBlock(0, "ornament", 60, 0)), c, mm).pages[0].lines.single()
        assertEquals(120f, w.imageWidth, 0.01f)
        assertEquals(60f, w.imageHeight, 0.01f)
        // Only the height declared.
        val h = layout(imageSection(ImageBlock(0, "ornament", 0, 40)), c, mm).pages[0].lines.single()
        assertEquals(160f, h.imageWidth, 0.01f)
        assertEquals(80f, h.imageHeight, 0.01f)
        // Nothing declared: the bitmap size (1000 >= 40% of W -> full width).
        val none = layout(imageSection(ImageBlock(0, "ornament")), c, mm).pages[0].lines.single()
        assertEquals(400f, none.imageWidth, 0.01f)
        assertEquals(200f, none.imageHeight, 0.01f)
        // Declared size but undecodable: still skipped.
        val missing = layout(imageSection(ImageBlock(0, "missing", 50, 25)), c, mm)
        assertTrue(missing.pages[0].lines.isEmpty())
    }

    // --- line breaking -----------------------------------------------------------------------------------

    @Test
    fun emojiStaysWithItsWordInWordMode() {
        val b = SectionBuilder()
        b.para("가 나다라마😊")
        // "가 나다라마" = 106 px, the emoji would end at 126 > 110: the whole word moves, the emoji is not split off.
        assertEquals(
            listOf("가", "나다라마😊"),
            lineTexts(layout(b.build(), cfg(width = 110, lineBreak = LineBreakMode.WORD))),
        )
        // CHAR mode: an emoji is a break opportunity like an ideograph.
        val b2 = SectionBuilder()
        b2.para("가나다라마😊바")
        assertEquals(
            listOf("가나다라마", "😊바"),
            lineTexts(layout(b2.build(), cfg(width = 100, lineBreak = LineBreakMode.CHAR))),
        )
        // WORD mode, an emoji-only run wider than the line still breaks (CHAR fallback), never inside a pair.
        val b3 = SectionBuilder()
        b3.para("😀".repeat(12))
        val lines = lineTexts(layout(b3.build(), cfg(width = 100, lineBreak = LineBreakMode.WORD)))
        assertEquals(listOf(10, 10, 4), lines.map { it.length })
    }

    @Test
    fun koreanInterpunctNeverStartsALine() {
        val b = SectionBuilder()
        b.para("가나다라ㆍ마바")
        assertEquals(
            listOf("가나다", "라ㆍ마바"),
            lineTexts(layout(b.build(), cfg(width = 90, lineBreak = LineBreakMode.CHAR))),
        )
        assertEquals(BreakClass.CLOSE, BreakClass.of('ㆍ'))
    }

    @Test
    fun charModeBreaksBetweenClosingAndOpeningPunctuation() {
        val b = SectionBuilder()
        b.para("가나다.“라마")
        assertEquals(
            listOf("가나다.", "“라마"),
            lineTexts(layout(b.build(), cfg(width = 90, lineBreak = LineBreakMode.CHAR))),
        )
        // WORD mode keeps the unspaced run together (falls back to CHAR only inside an over-long word).
        val b2 = SectionBuilder()
        b2.para("가 나다.“라")
        assertEquals(
            listOf("가", "나다.“라"),
            lineTexts(layout(b2.build(), cfg(width = 90, lineBreak = LineBreakMode.WORD))),
        )
    }
}
