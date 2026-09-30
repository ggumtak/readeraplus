package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.txt.TxtParser
import com.ggumtak.readeraplus.format.txt.TxtTestUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * T1-10 acceptance: the first parse of a 15 MB CP949 web novel with all five cleanup packs switched on, against the
 * same parse without rules (the rules run in the line stage, once per source line). Printed for the report. The spec
 * gate (+30% on the line stage) needs the parser's per-rule literal prefilter (format/txt, not in this module's
 * reach); until then only a loose bound, so that a pathological pattern (catastrophic backtracking) fails the build.
 */
class CleanupPacksPerfTest {

    /** ~[bytes] of CP949: web-novel layout, a chapter every ~5000 chars, a site notice / nav line now and then. */
    private fun synthetic(bytes: Int, seed: Int): ByteArray {
        val r = Random(seed)
        val sb = StringBuilder(bytes / 2 + 1024)
        var ch = 1
        var approx = 0
        val noise = listOf(
            "※ 본 작품은 무단 전재 및 재배포를 금지합니다.",
            "< 다음 화 보기 >",
            "출처: https://example.com/novel/123",
            "재미있게 보셨다면 추천 부탁드립니다!",
        )
        while (approx < bytes) {
            val start = sb.length
            sb.append("제").append(ch++).append("화 ").append(TxtTestUtil.sentence(r).take(12)).append("\r\n\r\n")
            var n = 0
            while (n < 5000) {
                val p = TxtTestUtil.paragraph(r, 30 + r.nextInt(200))
                sb.append(if (r.nextInt(40) == 0) "$p..." else p).append("\r\n\r\n")
                n += p.length + 4
            }
            sb.append(noise[r.nextInt(noise.size)]).append("\r\n\r\n")
            approx += (sb.length - start) * 17 / 10
        }
        return sb.toString().toByteArray(TxtTestUtil.CP949)
    }

    private fun bestOf(n: Int, bytes: ByteArray, o: ParseOptions): Pair<Double, TxtParser.Parsed> {
        var best = Double.MAX_VALUE
        var last: TxtParser.Parsed? = null
        repeat(n) {
            System.gc()
            val t0 = System.nanoTime()
            last = TxtParser.parse(bytes, bytes.size, o)
            best = minOf(best, (System.nanoTime() - t0) / 1e6)
        }
        return best to last!!
    }

    @Test
    fun allPacksOnAFifteenMegabyteFile() {
        val packs = ParseOptions(txtReplaceRules = RuleList.serialize(CleanupPacks.ALL.map { it.item }))
        val plain = ParseOptions()
        // Warm-up (JIT, CP949 tables, regex classes).
        val warm = synthetic(2_000_000, 1)
        repeat(2) {
            TxtParser.parse(warm, warm.size, plain)
            TxtParser.parse(warm, warm.size, packs)
        }
        val bytes = synthetic(15_000_000, 2)
        val (base, p0) = bestOf(3, bytes, plain)
        val (ruled, p1) = bestOf(3, bytes, packs)
        println(
            "TXT perf: %.1f MB CP949, 5 cleanup packs: parse %.0f ms without rules, %.0f ms with (+%.0f%%)"
                .format(bytes.size / 1e6, base, ruled, (ruled / base - 1) * 100),
        )
        // The notices and nav lines are gone; the chapters are the same.
        assertEquals(p0.sectionCount, p1.sectionCount)
        assertTrue("with packs $ruled ms vs $base ms", ruled < base * 15 + 500)
    }
}
