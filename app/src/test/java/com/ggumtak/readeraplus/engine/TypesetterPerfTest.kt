package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.random.Random

/**
 * Performance smoke tests (spec: 1,000,000 chars of Korean-like text laid out in < 1 s, counted in < 0.6 s
 * on the JVM with the fake measurer). Timings are printed so they can be reported.
 */
class TypesetterPerfTest {

    private fun koreanSection(chars: Int, seed: Int = 1): SectionContent {
        val r = Random(seed)
        val b = SectionBuilder()
        var total = 0
        while (total < chars) {
            val p = SampleText.koreanParagraph(r, r.nextInt(80, 600))
            b.para(p)
            total += p.length + 1
            if (r.nextInt(40) == 0) {
                b.para("")
                total++
            }
        }
        return b.build()
    }

    private val comet = LayoutConfig(
        width = 640, height = 1300, lineHeightEm = 1.7f, paragraphSpacingEm = 0.5f, indentEm = 1f,
        align = Align.JUSTIFY, lineBreak = LineBreakMode.WORD, publisherStyles = true,
        maxImageHeightFraction = 1f, widowOrphanControl = true,
    )

    private inline fun bestOf(runs: Int, block: () -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(runs) {
            val t0 = System.nanoTime()
            block()
            best = minOf(best, System.nanoTime() - t0)
        }
        return best / 1_000_000
    }

    @Test
    fun millionCharsLayoutAndCount() {
        val content = koreanSection(1_000_000)
        assertTrue(content.length >= 1_000_000)
        val m = FakeMeasurer()
        for (mode in LineBreakMode.values()) {
            val cfg = comet.copy(lineBreak = mode)
            val ts = Typesetter(m, cfg)
            var pages = 0
            var counted = 0
            // Warm-up (JIT).
            repeat(3) {
                pages = ts.layout(content).pageCount
                counted = ts.countPages(content)
            }
            assertEquals(pages, counted)
            val layoutMs = bestOf(3) { ts.layout(content) }
            val countMs = bestOf(3) { ts.countPages(content) }
            println("PERF engine $mode: ${content.length} chars, $pages pages: layout ${layoutMs} ms, count ${countMs} ms")
            assertTrue("layout took $layoutMs ms", layoutMs < 1000)
            assertTrue("count took $countMs ms", countMs < 600)
        }
    }

    @Test
    fun hugeSingleParagraphIsLinear() {
        val r = Random(3)
        val sb = StringBuilder()
        while (sb.length < 1_000_000) sb.append(SampleText.hangulWord(r)).append(' ')
        val b = SectionBuilder()
        b.para(sb.toString())
        val content = b.build()
        val ts = Typesetter(FakeMeasurer(), comet)
        repeat(2) { ts.countPages(content) }
        val ms = bestOf(2) { ts.layout(content) }
        println("PERF engine single 1M-char paragraph: layout $ms ms")
        assertTrue("single paragraph layout took $ms ms", ms < 2000)
        // Unbreakable text (no spaces, WORD mode -> CHAR fallback on every line) must stay linear too.
        val b2 = SectionBuilder()
        b2.para("가".repeat(300_000))
        val ms2 = bestOf(2) { Typesetter(FakeMeasurer(), comet).layout(b2.build()) }
        println("PERF engine 300k-char unbreakable word: layout $ms2 ms")
        assertTrue(ms2 < 2000)
    }

    @Test
    fun countingAllocatesAlmostNothingPerChar() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        val content = koreanSection(1_000_000, seed = 5)
        val ts = Typesetter(FakeMeasurer(), comet)
        repeat(3) { ts.countPages(content) }
        val tid = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(tid)
        ts.countPages(content)
        val countBytes = bean.getThreadAllocatedBytes(tid) - before
        val before2 = bean.getThreadAllocatedBytes(tid)
        val layout = ts.layout(content)
        val layoutBytes = bean.getThreadAllocatedBytes(tid) - before2
        val lines = layout.pages.sumOf { it.lines.size }
        println(
            "ALLOC engine: countPages ${countBytes / 1024} KB, layout ${layoutBytes / 1024} KB " +
                "(${content.length} chars, $lines lines, ${layout.pageCount} pages)",
        )
        // Counting: no per-char or per-line garbage (the runs table/metrics cache only).
        assertTrue("countPages allocated $countBytes bytes", countBytes < 256 * 1024)
        // Layout: the advances array (4 bytes/char) + LineInfo/PageInfo objects; nothing per char beyond that.
        assertTrue("layout allocated $layoutBytes bytes", layoutBytes < content.length * 4L + lines * 120L + 1_000_000L)
    }
}
