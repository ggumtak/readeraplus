package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.AllocCounter
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.txt.TxtBook
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.format.txt.TxtIndexStore
import com.ggumtak.readeraplus.format.txt.TxtTestUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** The RAPerf DEBUG measuring lines ([PerfLines]), the FrameMetrics matching ([FrameTrace]) and [OnceGate]. */
class PerfTraceTest {
    private fun sb() = StringBuilder(256)

    @Test
    fun contactTimeIsLiftMinusDown() {
        assertEquals(96L, PerfLines.contactMs(1000L, 1096L))
        assertEquals(0L, PerfLines.contactMs(5000L, 5000L)) // a key's first KEY_DOWN
        assertEquals(-1L, PerfLines.contactMs(0L, 1096L))   // no down: wheel, accessibility
        assertEquals(-1L, PerfLines.contactMs(2000L, 1096L)) // a down from a later gesture
    }

    @Test
    fun millisecondsWithOneDecimalRoundHalfUp() {
        fun ms(n: Long) = PerfLines.appendMs(sb(), n).toString()
        assertEquals("0.0", ms(0L))
        assertEquals("0.0", ms(49_999L))
        assertEquals("0.1", ms(50_000L))
        assertEquals("4.2", ms(4_249_999L))
        assertEquals("4.3", ms(4_250_000L))
        assertEquals("1000.0", ms(999_950_000L))
        assertEquals("1234.6", ms(1_234_567_890L))
        assertEquals("-", ms(-1L))
    }

    @Test
    fun tapLineHasContactWaitAndBothEnds() {
        val line = PerfLines.turnLine(sb(), 12, PerfLines.INPUT_TAP, 1096L, 1000L, 2L, 1114L, 4_249_999L)
        assertEquals("turn #12 tap: contact 96 ms, wait 2 ms, up+18 ms, down+114 ms, onDraw 4.2 ms", line.toString())
    }

    @Test
    fun swipeLineWithoutWaitOrDraw() {
        val line = PerfLines.turnLine(sb(), 3, PerfLines.INPUT_SWIPE, 300L, 100L, -1L, 320L, -1L)
        assertEquals("turn #3 swipe: contact 200 ms, up+20 ms, down+220 ms", line.toString())
    }

    @Test
    fun keyLineShowsTheSystemHoldAsWait() {
        // A volume key: KEY_DOWN's event time is its down time, and Android held it ~150 ms before the reader saw it.
        val line = PerfLines.turnLine(sb(), 13, PerfLines.INPUT_KEY, 5000L, 5000L, 152L, 5160L, 3_950_000L)
        assertEquals("turn #13 key: held 0 ms, wait 152 ms, key+160 ms, down+160 ms, onDraw 4.0 ms", line.toString())
        // A held key's repeat turns: how long it has been down.
        val repeat = PerfLines.turnLine(sb(), 14, PerfLines.INPUT_KEY, 5400L, 5000L, 1L, 5410L, 3_000_000L)
        assertEquals("turn #14 key: held 400 ms, wait 1 ms, key+10 ms, down+410 ms, onDraw 3.0 ms", repeat.toString())
    }

    @Test
    fun wheelAndOtherInputsHaveNoDown() {
        assertEquals("turn #14 wheel: wait 1 ms, input+12 ms",
            PerfLines.turnLine(sb(), 14, PerfLines.INPUT_WHEEL, 2000L, 0L, 1L, 2012L, -1L).toString())
        assertEquals("turn #15 other: input+9 ms, onDraw 2.5 ms",
            PerfLines.turnLine(sb(), 15, PerfLines.INPUT_NONE, 2000L, 0L, -1L, 2009L, 2_500_000L).toString())
    }

    private val parts = longArrayOf(400_000, 100_000, 0, 200_000, 4_100_000, 600_000, 3_200_000, 1_100_000, 2_500_000)

    @Test
    fun frameLineListsEveryReportedPart() {
        val line = PerfLines.frameLine(sb(), 12, PerfLines.INPUT_TAP, parts, 21_300_000L, 1121L, 1096L, 1000L)
        assertEquals("frame #12: total 21.3 ms (delay 0.4, input 0.1, anim 0.0, layout 0.2, draw 4.1, sync 0.6, " +
            "cmd 3.2, swap 1.1, gpu 2.5), done up+25 ms, down+121 ms", line.toString())
    }

    @Test
    fun frameLineSkipsPartsThisApiLevelDoesNotReport() {
        val old = parts.copyOf().also { it[PerfLines.FRAME_PARTS - 1] = -1L } // no GPU_DURATION before API 31
        val line = PerfLines.frameLine(sb(), 4, PerfLines.INPUT_KEY, old, 9_000_000L, 5170L, 5000L, 5000L)
        assertEquals("frame #4: total 9.0 ms (delay 0.4, input 0.1, anim 0.0, layout 0.2, draw 4.1, sync 0.6, " +
            "cmd 3.2, swap 1.1), done key+170 ms, down+170 ms", line.toString())
    }

    @Test
    fun firstPageFrameCountsFromTheOpen() {
        val line = PerfLines.frameLine(sb(), 0, PerfLines.INPUT_NONE, parts, 21_300_000L, 1230L, 1000L, 0L)
        assertEquals("frame open: total 21.3 ms (delay 0.4, input 0.1, anim 0.0, layout 0.2, draw 4.1, sync 0.6, " +
            "cmd 3.2, swap 1.1, gpu 2.5), done open+230 ms", line.toString())
    }

    @Test
    fun openLines() {
        assertEquals("open doc TXT index 4.1 ms, 15204352 bytes, 7480012 chars, 312 sections",
            PerfLines.docLine(sb(), "TXT", "index", 4_100_000L, 15_204_352L, 7_480_012L, true, 312).toString())
        // EPUB section sizes are estimates until a section is loaded.
        assertEquals("open doc EPUB scan 120.0 ms, 3260000 bytes, ~1086666 chars, 45 sections",
            PerfLines.docLine(sb(), "EPUB", "scan", 120_000_000L, 3_260_000L, 1_086_666L, false, 45).toString())
        assertEquals("open layout s:12 g:1 load 3.0 ms 24011 chars, typeset 19.2 ms 11 pages",
            PerfLines.layoutLine(sb(), true, 12, 1, 3_000_000L, 24_011, 19_249_999L, 11, false).toString())
        assertEquals("layout s:13 g:1 load 0.8 ms 23110 chars, typeset 17.5 ms 10 pages, prefetch",
            PerfLines.layoutLine(sb(), false, 13, 1, 800_000L, 23_110, 17_500_000L, 10, true).toString())
        assertEquals("open 7: onDraw 5.1 ms", PerfLines.openDrawLine(sb(), 7L, 5_060_000L).toString())
    }

    @Test
    fun epubOpenSaysWhetherItUsedThePlanScannedOrHadNothingToScan() {
        assertEquals("plan", PerfLines.epubHow(planFromCache = true, scannedItems = 0))
        assertEquals("scan", PerfLines.epubHow(planFromCache = false, scannedItems = 2))
        // No item above the scan threshold: neither scanned nor cached, on every open.
        assertEquals("small", PerfLines.epubHow(planFromCache = false, scannedItems = 0))
        assertEquals("open doc EPUB small 8.0 ms, 412000 bytes, ~137333 chars, 12 sections",
            PerfLines.docLine(sb(), "EPUB", PerfLines.epubHow(false, 0), 8_000_000L, 412_000L, 137_333L, false, 12)
                .toString())
    }

    /** tools/ci/perf_log.py reads only `show` lines with this shape (SHOW_RE): no new line may be taken for one. */
    @Test
    fun noNewLineLooksLikeAShowLine() {
        val show = Regex("""\bshow (\w+) s:(-?\d+) o:(-?\d+) a:(-?\d+) g:(-?\d+) (-?\d+)ms""")
        val lines = listOf(
            PerfLines.turnLine(sb(), 12, PerfLines.INPUT_TAP, 1096L, 1000L, 2L, 1114L, 4_249_999L),
            PerfLines.frameLine(sb(), 12, PerfLines.INPUT_TAP, parts, 21_300_000L, 1121L, 1096L, 1000L),
            PerfLines.frameLine(sb(), 0, PerfLines.INPUT_NONE, parts, 21_300_000L, 1230L, 1000L, 0L),
            PerfLines.docLine(sb(), "TXT", "parse", 380_000_000L, 15_204_352L, 7_480_012L, true, 312),
            PerfLines.layoutLine(sb(), true, 12, 1, 3_000_000L, 24_011, 19_249_999L, 11, false),
            PerfLines.openDrawLine(sb(), 7L, 5_060_000L),
        ).map { it.toString() }
        for (l in lines) {
            assertNull(l, show.find(l))
            // The existing lines keep their own shapes: "turn N ms" and "open <id>: first page N ms".
            assertFalse(l, Regex("""^turn \d+ ms$""").matches(l))
            assertFalse(l, l.contains("first page"))
        }
    }

    @Test
    fun linesAllocateNothingIntoABuilderWithRoom() {
        if (!AllocCounter.supported) return
        val b = StringBuilder(512)
        var length = 0
        val loop = {
            for (i in 0 until 2_000) {
                b.setLength(0)
                PerfLines.turnLine(b, i, PerfLines.INPUT_TAP, 1096L, 1000L, 2L, 1114L, 4_249_999L)
                PerfLines.frameLine(b, i, PerfLines.INPUT_KEY, parts, 21_300_000L, 1121L, 1096L, 1000L)
                PerfLines.docLine(b, "TXT", "index", 4_100_000L, 15_204_352L, 7_480_012L, true, 312)
                PerfLines.layoutLine(b, false, 13, 1, 800_000L, 23_110, 17_500_000L, 10, true)
                PerfLines.openDrawLine(b, 7L, 5_060_000L)
                length += b.length
            }
        }
        repeat(3) { loop() }
        val bytes = AllocCounter.measure(loop)!!
        assertTrue(length > 0)
        assertEquals("2 000 rounds of every line allocated $bytes bytes", 0L, bytes)
    }

    @Test
    fun frameTraceMatchesTheFrameByVsyncWithinOneMs() {
        val t = FrameTrace()
        val out = LongArray(FrameTrace.FIELDS)
        t.expect(1_000L, 7, PerfLines.INPUT_TAP, 990L, 900L)
        assertFalse(t.take(998L, out))    // another frame
        assertFalse(t.take(1_002L, out))
        assertTrue(t.take(1_001L, out))   // the same vsync, rounded the other way
        assertArrayEquals(longArrayOf(7L, PerfLines.INPUT_TAP.toLong(), 990L, 900L), out)
        assertFalse("taken once", t.take(1_000L, out))
    }

    @Test
    fun frameTraceHandsOutEveryTraceOfOneFrameAndDropsStaleOnes() {
        val t = FrameTrace()
        val out = LongArray(FrameTrace.FIELDS)
        // An open and a turn drawn by the same frame: both get its metrics.
        t.expect(500L, 0, PerfLines.INPUT_NONE, 100L, 0L)
        t.expect(500L, 1, PerfLines.INPUT_KEY, 480L, 480L)
        val ids = ArrayList<Long>()
        while (t.take(500L, out)) ids += out[0]
        assertEquals(listOf(0L, 1L), ids.sorted())
        // Frames whose metrics never came are overwritten after SLOTS newer ones.
        for (k in 0 until FrameTrace.SLOTS + 1) t.expect(1_000L + 20L * k, k + 10, PerfLines.INPUT_TAP, 0L, 0L)
        assertFalse(t.take(1_000L, out))
        assertTrue(t.take(1_000L + 20L * FrameTrace.SLOTS, out))
        assertEquals((FrameTrace.SLOTS + 10).toLong(), out[0])
    }

    @Test
    fun frameTraceAllocatesNothing() {
        if (!AllocCounter.supported) return
        val t = FrameTrace()
        val out = LongArray(FrameTrace.FIELDS)
        var found = 0
        val loop = {
            for (i in 0 until 10_000) {
                t.expect(i * 16L, i, PerfLines.INPUT_TAP, i * 16L - 20L, i * 16L - 100L)
                if (t.take(i * 16L, out)) found++
                if (t.take(i * 16L + 8L, out)) found-- // a frame without a trace
            }
        }
        repeat(3) { loop() }
        found = 0
        val bytes = AllocCounter.measure(loop)!!
        assertEquals(10_000, found)
        assertEquals("10 000 frames allocated $bytes bytes", 0L, bytes)
    }

    @Test
    fun onceGateOpensOnce() {
        val g = OnceGate()
        assertTrue(g.take())
        assertFalse(g.take())
        assertFalse(g.take())
        assertTrue("each reader has its own", OnceGate().take())
    }

    @Test
    fun txtOpenSaysWhetherItParsedTheWholeFile() {
        val dir = TxtTestUtil.tempDir()
        val text = "제1화 시작\n\n" + TxtTestUtil.body(Random(7), 4000, "\n\n") + "\n\n제2화 다음\n\n" +
            TxtTestUtil.body(Random(8), 4000, "\n\n")
        val f = TxtTestUtil.writeTemp(dir, "parsed.txt", text.toByteArray())
        val o = ParseOptions()
        TxtTestUtil.withCacheDir(File(dir, "cache")) {
            TxtIndexStore.fileFor(TxtIndexStore.key(f, o))?.delete()
            TxtDocuments.open(f, o).use { assertTrue("first open parses", (it as TxtBook).parsed) }
            TxtDocuments.open(f, o).use { assertFalse("reopen reads the index", (it as TxtBook).parsed) }
        }
    }
}
