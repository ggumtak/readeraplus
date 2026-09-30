package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.settings.EINK_MODE_FAST
import com.ggumtak.readeraplus.settings.EINK_MODE_HD
import com.ggumtak.readeraplus.settings.EINK_MODE_NORMAL
import com.ggumtak.readeraplus.settings.EINK_MODE_REGAL
import com.ggumtak.readeraplus.settings.EINK_MODE_SYSTEM
import com.ggumtak.readeraplus.ui.settings.SettingsFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Device feedback on build 8: go-to by percent, fast tapping, e-ink mode labels. */
class ReaderBuild9FixesTest {

    // ------------------------------------------------------------------ go to N% (by pages)

    @Test
    fun percentJumpLandsOnThePageShowingThatPercent() {
        for (total in 1..3000) {
            // What the footer shows on each page (monotonic in the page).
            val shown = IntArray(total + 1) { g -> if (g == 0) -1 else PageProgress.percentOf(g, total) }
            val first = IntArray(101) { -1 }
            for (g in 1..total) if (first[shown[g]] < 0) first[shown[g]] = g
            val present = (0..100).filter { first[it] > 0 }
            for (p in 0..100) {
                val g = PageProgress.pageFor(p / 100f, total)
                assertTrue("total=$total p=$p g=$g", g in 1..total)
                if (first[p] > 0) {
                    // the first page whose footer reads p%
                    assertEquals("total=$total p=$p", first[p], g)
                } else {
                    // a short book skips p: the nearest percent it does show
                    val best = present.minOf { Math.abs(it - p) }
                    assertEquals("total=$total p=$p g=$g", best, Math.abs(shown[g] - p))
                }
            }
        }
    }

    @Test
    fun percentJumpEnds() {
        assertEquals(1, PageProgress.pageFor(0f, 3259))
        assertEquals(3259, PageProgress.pageFor(1f, 3259))
        assertEquals(1, PageProgress.pageFor(0.5f, 1))
        assertEquals(1, PageProgress.pageFor(Float.NaN, 100))
        // the reported case: 52% must read 52%, not 48%
        val total = 1234
        assertEquals(52, PageProgress.percentOf(PageProgress.pageFor(0.52f, total), total))
        // a decimal percent goes to the first page at or past it
        assertEquals(525, PageProgress.pageFor(0.525f, 1000))
    }

    @Test
    fun progressOfPages() {
        assertEquals(1f, PageProgress.of(10, 10), 0f)
        assertEquals(1f, PageProgress.of(12, 10), 0f)
        assertEquals(0.5f, PageProgress.of(5, 10), 1e-6f)
        assertEquals(0f, PageProgress.of(1, 0), 0f)
        assertEquals(100, PageProgress.percentOf(3259, 3259))
        assertEquals(0, PageProgress.percentOf(1, 3259))
    }

    // ------------------------------------------------------------------ go to N% (by characters, before counting)

    @Test
    fun charPercentJumpReadsTheTypedPercent() {
        val rnd = Random(9)
        repeat(200) { round ->
            val n = 1 + rnd.nextInt(40)
            val chars = IntArray(n) { if (rnd.nextInt(8) == 0) 0 else 1 + rnd.nextInt(if (round % 3 == 0) 400_000 else 20_000) }
            if (chars.sum() < 100) chars[0] += 100
            val c = PageCounts(chars)
            val sum = chars.fold(0L) { a, b -> a + b }
            for (p in 0..100) {
                val pos = c.locateProgress(p / 100f)
                val read = ReaderFormat.percent(c.charProgress(pos.section, pos.offset))
                assertEquals("round=$round p=$p pos=$pos", p, read)
                // never past the first char that reaches p%
                var g = 0L
                for (i in 0 until pos.section) g += chars[i]
                g += pos.offset
                val firstChar = (p * sum + 99) / 100
                assertTrue("round=$round p=$p g=$g first=$firstChar", g <= firstChar)
            }
        }
    }

    @Test
    fun charPercentJumpAtSectionEndGoesToNextSectionStart() {
        val c = PageCounts(intArrayOf(500, 500))
        assertEquals(1, c.locateProgress(0.5f).section)
        assertEquals(0, c.locateProgress(0.5f).offset)
        assertEquals(0, c.locateProgress(0f).section)
        assertEquals(500, c.locateProgress(1f).offset)
        assertEquals(1, c.locateProgress(1f).section)
    }

    // ------------------------------------------------------------------ fast taps: pending-turn accounting

    @Test
    fun backlogCountsNetTurnsAndCaps() {
        val b = TurnBacklog()
        assertTrue(b.isEmpty)
        repeat(3) { b.add(true) }
        b.add(false)
        assertEquals(2, b.net)
        assertEquals(2, b.take())
        assertTrue(b.isEmpty)
        assertEquals(0, b.take())
        repeat(100) { b.add(true) }
        assertEquals(TurnBacklog.MAX, b.net)
        b.clear()
        repeat(100) { b.add(false) }
        assertEquals(-TurnBacklog.MAX, b.take())
        b.restore(-4)
        b.add(false)
        assertEquals(-5, b.net)
        b.restore(8)
        assertEquals(3, b.net)
    }

    private fun pages(vararg p: Int): (Int) -> Int = { p[it] }

    @Test
    fun walkInsideTheSection() {
        assertEquals(TurnWalk(0, 5, 0, false), TurnMath.walk(0, 2, 3, 1, pages(10)))
        assertEquals(TurnWalk(0, 0, 0, false), TurnMath.walk(0, 2, -2, 1, pages(10)))
        assertEquals(TurnWalk(0, 2, 0, false), TurnMath.walk(0, 2, 0, 1, pages(10)))
    }

    @Test
    fun walkAcrossKnownSections() {
        // sections of 5, 3 and 4 pages: global pages 1-5, 6-8, 9-12
        val p = pages(5, 3, 4)
        assertEquals(TurnWalk(1, 2, 0, false), TurnMath.walk(0, 3, 4, 3, p)) // p.4 + 4 = p.8
        assertEquals(TurnWalk(2, 0, 0, false), TurnMath.walk(0, 4, 4, 3, p)) // p.5 + 4 = p.9
        assertEquals(TurnWalk(0, 4, 0, false), TurnMath.walk(2, 1, -5, 3, p)) // p.10 - 5 = p.5
        assertEquals(TurnWalk(1, 0, 0, false), TurnMath.walk(2, 0, -3, 3, p)) // p.9 - 3 = p.6
        // every walk agrees with plain one-page steps
        val sizes = intArrayOf(5, 3, 4)
        val starts = intArrayOf(0, 5, 8)
        for (s in 0..2) for (i in 0 until sizes[s]) for (d in -15..15) {
            val g = (starts[s] + i + d).coerceIn(0, 11)
            val w = TurnMath.walk(s, i, d, 3, p)
            assertEquals("s=$s i=$i d=$d", g, starts[w.section] + w.pageIndex)
            assertEquals(0, w.remaining)
            assertEquals("s=$s i=$i d=$d", starts[s] + i + d !in 0..11, w.hitEdge)
        }
    }

    @Test
    fun walkStopsAtAnUnknownSection() {
        val p = pages(5, -1, 4)
        // p.4 + 4: one turn to the end of section 0, one into section 1 (unknown) → its first page, 2 left
        assertEquals(TurnWalk(1, 0, 2, false), TurnMath.walk(0, 3, 4, 3, p))
        // backwards into the unknown section: its last page, the rest kept (negative)
        assertEquals(TurnWalk(1, TurnMath.LAST_PAGE, -1, false), TurnMath.walk(2, 1, -3, 3, p))
        // exactly one turn into the unknown section: nothing left
        assertEquals(TurnWalk(1, 0, 0, false), TurnMath.walk(0, 4, 1, 3, p))
    }

    @Test
    fun walkStopsAtTheBookEdges() {
        val p = pages(5, 3, 4)
        assertEquals(TurnWalk(2, 3, 0, true), TurnMath.walk(2, 2, 10, 3, p))
        assertEquals(TurnWalk(0, 0, 0, true), TurnMath.walk(1, 1, -30, 3, p))
        assertEquals(TurnWalk(2, 3, 0, true), TurnMath.walk(2, 3, 1, 3, p))
    }

    @Test
    fun tapDedupDropsOnlyDuplicateReports() {
        val d = TapDedup()
        val slop = 20f
        assertTrue(d.accept(100f, 100f, 1000, slop))
        // the same touch reported again 15 ms later at the same spot
        assertFalse(d.accept(102f, 101f, 1015, slop))
        // a second finger elsewhere at nearly the same time is a real tap
        assertTrue(d.accept(400f, 100f, 1030, slop))
        // fast drumming on one spot (~12 taps/s) is never dropped
        var t = 1100L
        repeat(20) {
            assertTrue(d.accept(100f, 100f, t, slop))
            t += 80
        }
    }

    @Test
    fun cadenceRefreshWaitsWhileFlipping() {
        assertEquals(0L, EinkCadence.refreshDelay(10_000, Long.MIN_VALUE / 2))
        assertEquals(0L, EinkCadence.refreshDelay(10_000, 9_000))
        assertEquals(EinkCadence.RAPID_TURN_MS, EinkCadence.refreshDelay(10_000, 9_800))
        assertEquals(EinkCadence.RAPID_TURN_MS, EinkCadence.refreshDelay(10_000, 10_000))
    }

    // ------------------------------------------------------------------ e-ink mode labels

    @Test
    fun einkModeChoices() {
        val values = SettingsFormat.EINK_MODES.map { it.second }
        assertEquals(listOf(EINK_MODE_SYSTEM, EINK_MODE_HD, EINK_MODE_REGAL, EINK_MODE_FAST, EINK_MODE_NORMAL), values)
        assertEquals("기기 설정 따름 (권장·기본)", SettingsFormat.einkMode(EINK_MODE_SYSTEM))
        assertEquals("잔상 적게 (REGAL)", SettingsFormat.einkMode(EINK_MODE_REGAL))
        assertEquals(SettingsFormat.einkMode(EINK_MODE_SYSTEM), SettingsFormat.einkMode(12345))
    }
}
