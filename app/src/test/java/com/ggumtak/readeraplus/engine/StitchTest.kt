package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Restoring swallowed page-top space must reproduce the same continuous flow at every page height. */
class StitchTest {
    private val measurer = FakeMeasurer(images = mapOf("small" to IntSize(240, 100)))
    private val base = LayoutConfig(340, 300, lineHeightEm = 1.5f, paragraphSpacingEm = 0.5f,
        indentEm = 1f, align = Align.JUSTIFY, maxImageHeightFraction = 1f)

    private data class FlowLine(val start: Int, val end: Int, val x: Float, val extra: Float,
        val mode: Int, val rule: Boolean, val image: String?, val top: Float, val bottom: Float)

    private fun stitch(layout: SectionLayout): List<FlowLine> {
        val out = ArrayList<FlowLine>()
        var y = 0f
        for ((p, page) in layout.pages.withIndex()) {
            assertTrue("negative lead on page $p", page.lead >= 0f)
            if (p > 0) y += page.lead
            for (line in page.lines) {
                // Interior blanks still contribute height; a blank dropped at a page top is recovered by lead.
                if (line.end > line.start || line.isRule || line.imageBlock != null) {
                    out.add(FlowLine(line.start, line.end, line.x, line.justifyExtra, line.expandMode,
                        line.isRule, line.imageBlock?.src, y + line.top, y + line.bottom))
                }
            }
            y += page.lines.lastOrNull()?.bottom ?: 0f
        }
        return out
    }

    private fun section(seed: Int): SectionContent {
        val r = Random(seed)
        val b = SectionBuilder()
        repeat(45) { i ->
            when (r.nextInt(12)) {
                0 -> b.para("")
                1 -> b.para("   ", BlockStyle(marginTopEm = 0.4f))
                2 -> b.heading("다음 장면 $i", keep = true, pageBreak = r.nextBoolean())
                3 -> b.rule()
                4 -> b.image("small")
                5 -> b.para("바람이 불고 길이 이어진다", BlockStyle(softBreak = true))
                else -> b.para(if (r.nextBoolean()) SampleText.koreanParagraph(r, r.nextInt(10, 700))
                    else SampleText.mixedParagraph(r, r.nextInt(10, 700)),
                    BlockStyle(marginTopEm = r.nextInt(3) * 0.3f, marginBottomEm = r.nextInt(3) * 0.2f))
            }
        }
        return b.build()
    }

    private fun checked(c: SectionContent, cfg: LayoutConfig, anchor: Int): SectionLayout {
        val layout = Typesetter(measurer, cfg).layout(c, anchor)
        assertEquals(layout.pageCount, Typesetter(measurer, cfg).count(c, anchor).pages)
        LayoutChecks.checkPages(layout)
        return layout
    }

    @Test fun stitchedPagesMatchContinuousLayoutIncludingAnchorsAndParagraphMode() {
        for (seed in 0 until 200) {
            val c = section(seed)
            for (mode in PageBreakMode.values()) for (widows in listOf(false, true)) {
                for (anchor in intArrayOf(-1, c.length / 3)) {
                    val cfg = base.copy(pageBreak = mode, widowOrphanControl = widows)
                    val reference = stitch(checked(c, cfg.copy(height = 10_000_000), anchor))
                    for (height in intArrayOf(300, 517, 1000, 1400)) {
                        val layout = checked(c, cfg.copy(height = height), anchor)
                        val actual = stitch(layout)
                        val tag = "seed=$seed mode=$mode widows=$widows anchor=$anchor height=$height"
                        assertEquals(tag, reference.size, actual.size)
                        for (i in actual.indices) {
                            val a = actual[i]
                            val e = reference[i]
                            assertEquals("$tag line=$i", e.copy(top = 0f, bottom = 0f), a.copy(top = 0f, bottom = 0f))
                            assertEquals("$tag top line=$i", e.top, a.top, 0.5f)
                            assertEquals("$tag bottom line=$i", e.bottom, a.bottom, 0.5f)
                        }
                    }
                }
            }
        }
    }
}
