package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Randomised invariant tests (fixed seeds, so failures are reproducible). */
class TypesetterFuzzTest {

    private fun randomText(r: Random): String = when (r.nextInt(4)) {
        0 -> SampleText.mixedParagraph(r, r.nextInt(1, 400))
        1 -> SampleText.hangulWord(r, 1, 80)
        else -> SampleText.koreanParagraph(r, r.nextInt(1, 700))
    }

    private fun randomAlign(r: Random) = Align.values()[r.nextInt(Align.values().size)]

    private fun randomSection(r: Random): Pair<SectionContent, FakeMeasurer> {
        val images = HashMap<String, IntSize>()
        for (i in 0 until 5) {
            if (r.nextInt(4) != 0) images["img$i"] = IntSize(r.nextInt(1, 2000), r.nextInt(1, 3000))
        }
        val b = SectionBuilder()
        repeat(r.nextInt(0, 40)) {
            when (r.nextInt(20)) {
                0 -> b.para("")
                1 -> b.image("img${r.nextInt(6)}")
                2 -> b.rule()
                3 -> b.heading(
                    SampleText.hangulWord(r, 1, 30) + " " + r.nextInt(1000),
                    level = r.nextInt(1, 7), keep = r.nextBoolean(), pageBreak = r.nextInt(4) == 0,
                )
                4 -> b.para(randomText(r), BlockStyle(softBreak = true, indent = false))
                5 -> b.para(
                    randomText(r),
                    BlockStyle(
                        align = randomAlign(r), indent = r.nextBoolean(),
                        marginTopEm = r.nextFloat() * 3f, marginBottomEm = r.nextFloat() * 2f,
                        insetLeftEm = r.nextFloat() * 4f, insetRightEm = r.nextFloat() * 2f,
                        keepWithNext = r.nextInt(5) == 0, pageBreakBefore = r.nextInt(8) == 0,
                    ),
                )
                6 -> b.para(randomText(r), BlockStyle(preformatted = true, indent = false))
                7, 8 -> {
                    val t = randomText(r)
                    val from = r.nextInt(0, t.length + 1)
                    val to = r.nextInt(from, t.length + 1)
                    b.paraWithRun(t, from, to, RunStyle(bold = r.nextBoolean(), sizeScale = 0.6f + r.nextFloat() * 1.6f))
                }
                else -> b.para(randomText(r))
            }
        }
        return b.build() to FakeMeasurer(images = images)
    }

    private fun randomConfig(r: Random) = LayoutConfig(
        width = r.nextInt(30, 800),
        height = r.nextInt(20, 1500),
        lineHeightEm = 0.8f + r.nextFloat() * 1.8f,
        paragraphSpacingEm = r.nextFloat() * 2f,
        indentEm = r.nextFloat() * 3f,
        align = if (r.nextInt(6) == 0) randomAlign(r) else if (r.nextBoolean()) Align.JUSTIFY else Align.LEFT,
        lineBreak = if (r.nextBoolean()) LineBreakMode.WORD else LineBreakMode.CHAR,
        publisherStyles = r.nextBoolean(),
        maxImageHeightFraction = 0.2f + r.nextFloat(),
        widowOrphanControl = r.nextBoolean(),
    )

    @Test
    fun randomSectionsKeepAllInvariants() {
        val r = Random(20260929)
        var totalLines = 0
        repeat(200) { iter ->
            val (content, measurer) = randomSection(r)
            val cfg = randomConfig(r)
            val l = Typesetter(measurer, cfg).layout(content)
            try {
                totalLines += LayoutChecks.checkAll(l, measurer)
                assertEquals("countPages", l.pageCount, Typesetter(measurer, cfg).countPages(content))
                // Deterministic.
                val again = Typesetter(measurer, cfg).layout(content)
                assertEquals(l.pages.map { it.start }, again.pages.map { it.start })
                // pageForOffset agrees with the ranges.
                for ((i, p) in l.pages.withIndex()) {
                    if (p.start < p.end) {
                        assertEquals(i, l.pageForOffset(p.start))
                        assertEquals(i, l.pageForOffset(p.end - 1))
                    }
                }
                // hitTest round-trips with charPositions on every visible char.
                val pos = FloatArray(content.length + 1)
                for (p in l.pages) {
                    for (ln in p.lines) {
                        if (ln.imageBlock != null || ln.end <= ln.start) continue
                        LineGeometry.charPositions(l, ln, pos)
                        val y = (ln.top + ln.bottom) / 2f
                        for (i in ln.start until ln.end) {
                            val a = l.advances[i]
                            if (a <= 0f) continue
                            assertEquals(i, LineGeometry.hitTest(l, p, pos[i - ln.start] + a / 2f, y))
                        }
                    }
                }
            } catch (e: AssertionError) {
                throw AssertionError("iteration $iter, config $cfg: ${e.message}", e)
            }
        }
        assertTrue("fuzz produced text lines", totalLines > 1000)
    }

    /** Every observable field of a layout, for exact comparisons. */
    private fun signature(l: SectionLayout): String {
        val sb = StringBuilder()
        for (p in l.pages) {
            sb.append('P').append(p.start).append('-').append(p.end).append(':')
            for (ln in p.lines) {
                sb.append(ln.start).append(',').append(ln.end).append(',').append(ln.x).append(',')
                    .append(ln.top).append(',').append(ln.baseline).append(',').append(ln.bottom).append(',')
                    .append(ln.justifyExtra).append(',').append(ln.expandMode).append(',').append(ln.imageWidth)
                    .append(',').append(ln.imageHeight).append(',').append(ln.isRule).append(';')
            }
        }
        return sb.toString()
    }

    @Test
    fun reusedTypesetterMatchesAFreshOne() {
        // The per-typesetter scratch buffers must never leak state from one pass into the next.
        val r = Random(31337)
        repeat(150) { iter ->
            val (content, measurer) = randomSection(r)
            val cfg = randomConfig(r)
            val fresh = Typesetter(measurer, cfg).layout(content)
            val reused = Typesetter(measurer, cfg)
            reused.countPages(randomSection(r).first)
            reused.layout(randomSection(r).first)
            assertEquals("iteration $iter", signature(fresh), signature(reused.layout(content)))
            assertEquals("iteration $iter", fresh.pageCount, reused.countPages(content))
        }
    }

    @Test
    fun malformedSectionsNeverCrash() {
        val r = Random(7)
        repeat(300) { iter ->
            val len = r.nextInt(0, 300)
            val sb = StringBuilder()
            repeat(len) {
                sb.append(
                    when (r.nextInt(10)) {
                        0 -> '\n'
                        1 -> ' '
                        2 -> OBJECT_CHAR
                        3 -> '\uDC00' // lone low surrogate
                        4 -> '\uD83D' // lone high surrogate
                        else -> ('가'.code + r.nextInt(11172)).toChar()
                    },
                )
            }
            val text = sb.toString()
            val blocks = ArrayList<Block>()
            repeat(r.nextInt(0, 30)) {
                val s = r.nextInt(-5, len + 6)
                val e = s + r.nextInt(-5, 60)
                blocks.add(
                    when (r.nextInt(4)) {
                        0 -> ImageBlock(s, "img")
                        1 -> RuleBlock(s)
                        else -> ParagraphBlock(
                            s, e,
                            BlockStyle(
                                headingLevel = r.nextInt(0, 3), keepWithNext = r.nextBoolean(),
                                pageBreakBefore = r.nextBoolean(), marginTopEm = r.nextFloat() * 10f - 5f,
                                insetLeftEm = r.nextFloat() * 100f - 10f, insetRightEm = r.nextFloat() * 100f,
                            ),
                        )
                    },
                )
            }
            if (r.nextBoolean()) blocks.sortBy { it.start }
            val runs = ArrayList<StyleRun>()
            repeat(r.nextInt(0, 10)) {
                val s = r.nextInt(-5, len + 5)
                runs.add(StyleRun(s, s + r.nextInt(-3, 50), RunStyle(sizeScale = r.nextFloat() * 4f - 1f)))
            }
            val content = SectionContent(text, blocks, runs)
            val m = FakeMeasurer(images = mapOf("img" to IntSize(r.nextInt(-5, 500), r.nextInt(-5, 500))))
            val cfg = randomConfig(r).copy(
                width = r.nextInt(-10, 300),
                height = r.nextInt(-10, 300),
                lineHeightEm = if (r.nextInt(5) == 0) Float.NaN else r.nextFloat() * 3f,
            )
            try {
                val l = Typesetter(m, cfg).layout(content)
                LayoutChecks.checkPages(l)
                assertEquals(l.pageCount, Typesetter(m, cfg).countPages(content))
            } catch (e: Throwable) {
                throw AssertionError("iteration $iter: $e", e)
            }
        }
    }

    /** Each block's lines in document order: block index -> list of lines. */
    private fun linesByBlock(l: SectionLayout): Map<Int, List<LineInfo>> =
        LayoutChecks.allLines(l).groupBy { l.content.blockIndexAt(it.start) }

    @Test
    fun noWidowsOrOrphansWhenAvoidable() {
        val r = Random(99)
        repeat(60) {
            val b = SectionBuilder()
            repeat(r.nextInt(5, 40)) { b.para("가".repeat(10 * r.nextInt(1, 9) - r.nextInt(0, 5))) }
            val content = b.build()
            val cfg = LayoutConfig(200, 30 * r.nextInt(6, 16), 1.5f, 0f, 0f, Align.LEFT, LineBreakMode.CHAR, true, 1f, true)
            val l = Typesetter(FakeMeasurer(), cfg).layout(content)
            LayoutChecks.checkAll(l, FakeMeasurer())
            val byBlock = linesByBlock(l)
            for (i in 0 until l.pageCount - 1) {
                val last = l.pages[i].lines.last()
                val first = l.pages[i + 1].lines.first()
                val bi = content.blockIndexAt(last.start)
                if (bi != content.blockIndexAt(first.start)) continue
                val lines = byBlock.getValue(bi)
                if (lines.size < 3) continue
                assertTrue("orphan on page $i", lines.indexOf(last) != 0)
                assertTrue("widow on page ${i + 1}", lines.indexOf(first) != lines.size - 1)
            }
        }
    }

    @Test
    fun headingsNeverEndAPageUnlessAlone() {
        val r = Random(4242)
        repeat(80) {
            val b = SectionBuilder()
            repeat(r.nextInt(5, 40)) {
                if (r.nextInt(3) == 0) b.heading(SampleText.hangulWord(r, 1, 20))
                else b.para(SampleText.koreanParagraph(r, r.nextInt(5, 300)))
            }
            val content = b.build()
            val cfg = LayoutConfig(
                300, r.nextInt(250, 700), 1.6f, 0.5f, 1f, Align.JUSTIFY,
                if (r.nextBoolean()) LineBreakMode.WORD else LineBreakMode.CHAR, true, 1f, r.nextBoolean(),
            )
            val m = FakeMeasurer()
            val l = Typesetter(m, cfg).layout(content)
            LayoutChecks.checkAll(l, m)
            for (i in 0 until l.pageCount - 1) {
                val lines = l.pages[i].lines
                fun keep(ln: LineInfo) =
                    (content.blocks[content.blockIndexAt(ln.start)] as ParagraphBlock).style.keepWithNext
                if (!keep(lines.last())) continue
                var c = lines.size - 1
                while (c > 0 && keep(lines[c - 1])) c--
                // Allowed only when the keep chain opens the page, or when moving it would carry more than half a
                // page (break avoidance is capped), which requires the chain to start in the upper half.
                assertTrue(
                    "page $i ends with a heading but has other content",
                    c == 0 || lines[c].top < cfg.height * 0.5f,
                )
            }
        }
    }
}
