package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.format.ParseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Performance smoke tests on the JVM (targets from ARCHITECTURE.md: first parse of 15 MB CP949 < 1.5 s,
 * indexed open < 50 ms, section load < 20 ms). Timings are printed for the report.
 */
class TxtPerfTest {
    private val dir = TxtTestUtil.tempDir()
    private val cacheDir = File(dir, "cache")

    /**
     * ~[bytes] of CP949 text: web-novel layout (blank line between paragraphs), a chapter every ~5000 chars. With
     * [noise], each chapter also carries a few of the lines that 텍본 sites add (notices, links, "다음 화", "……").
     */
    private fun synthetic(bytes: Int, seed: Int, noise: Boolean = false): ByteArray {
        val r = Random(seed)
        val sb = StringBuilder(bytes / 2 + 1024)
        var ch = 1
        var approxBytes = 0
        while (approxBytes < bytes) {
            val start = sb.length
            sb.append("제").append(ch++).append("화 ").append(TxtTestUtil.sentence(r).take(12)).append("\r\n\r\n")
            if (noise) sb.append(NOISE[r.nextInt(NOISE.size)]).append("\r\n\r\n")
            var n = 0
            while (n < 5000) {
                val p = TxtTestUtil.paragraph(r, 30 + r.nextInt(200))
                sb.append(p).append("\r\n\r\n")
                n += p.length + 4
                if (noise && r.nextInt(12) == 0) sb.append(NOISE[r.nextInt(NOISE.size)]).append("\r\n\r\n")
            }
            approxBytes += (sb.length - start) * 17 / 10
        }
        return sb.toString().toByteArray(TxtTestUtil.CP949)
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000.0

    internal companion object {
        /**
         * The five cleanup packs of spec T1-10, verbatim (the rule manager's `CleanupPacks.ALL` holds the same
         * patterns; they are copied so this FORMAT test does not depend on the reader UI, and
         * `CleanupPacksPerfTest.perfGateUsesThePacksUsersAdd` checks that the copy matches).
         */
        val CLEANUP_PACKS = listOf(
            """^.*(무단\s*(전재|복제|배포|도용)|재배포\s*(금지|불가)|불펌\s*금지).*$ =>""",
            """^\s*[<\[(〈《【]?\s*(다음\s*화|이전\s*화|다음\s*편|목록(으로)?)\s*(보기|가기)?\s*[>\])〉》】]?\s*$ =>""",
            """^.*(https?://|www\.)\S+.*$ =>""",
            """^.*(추천|선호작|후원|구독|알림\s*설정|좋아요|댓글)[^.!?]{0,15}(부탁|눌러|해\s*주(세요|시면)).*$ =>""",
            """\.{3,} => …""",
        ).joinToString("\n")

        val NOISE = listOf(
            "※ 이 글의 무단 전재 및 재배포를 금지합니다.",
            "< 다음 화 보기 >",
            "출처: https://example.com/novel/12345",
            "추천과 선호작 꼭 부탁드립니다!",
            "그는 잠시 말이 없었다... 그리고 고개를 끄덕였다...",
        )
    }

    @Test
    fun largeCp949File() {
        val o = ParseOptions()
        // warm-up (JIT + CP949 table) on a separate small file
        val warm = TxtTestUtil.writeTemp(dir, "warm.txt", synthetic(2_000_000, 1))
        TxtTestUtil.withCacheDir(cacheDir) { repeat(2) { TxtIndexStore.fileFor(TxtIndexStore.key(warm, o))!!.delete(); TxtDocuments.open(warm, o).close() } }

        val bytes = synthetic(15_000_000, 2)
        val f = TxtTestUtil.writeTemp(dir, "big15.txt", bytes)
        TxtTestUtil.withCacheDir(cacheDir) {
            System.gc()
            val t0 = System.nanoTime()
            val book = TxtDocuments.open(f, o)
            val first = ms(t0)
            val sections = book.sections.size
            book.close()

            val t1 = System.nanoTime()
            val again = TxtDocuments.open(f, o)
            val indexed = ms(t1)
            assertEquals(sections, again.sections.size)

            var maxLoad = 0.0
            val t2 = System.nanoTime()
            for (i in 0 until again.sections.size) {
                val t3 = System.nanoTime()
                val c = again.loadSection(i)
                val d = ms(t3)
                if (d > maxLoad) maxLoad = d
                assertEquals(again.sections[i].approxChars, c.length)
            }
            val avgLoad = ms(t2) / again.sections.size
            again.close()

            println(
                "TXT perf: %.1f MB CP949, %d sections, %d toc | first open %.0f ms | indexed open %.1f ms | section load avg %.2f ms, max %.1f ms"
                    .format(bytes.size / 1e6, sections, again.toc.size, first, indexed, avgLoad, maxLoad)
            )
            assertTrue("first open $first ms", first < 1500)
            assertTrue("indexed open $indexed ms", indexed < 50)
            assertTrue("avg section load $avgLoad ms", avgLoad < 20)
        }
    }

    /**
     * T1-10 acceptance gate: the line stage (decoded chars → [LineTable], where the replace rules run) of the 15 MB
     * perf file with all five cleanup packs on costs at most +30% over the same stage without rules. A noisy variant
     * (a notice, link, "다음 화" or "..." line per chapter and in about every 12th paragraph: lines the packs delete
     * or rewrite) is measured too, with a looser bound: those lines pay for the regex work they need. Min of several
     * runs, so a GC or JIT pause in one run does not decide the gate.
     */
    @Test
    fun cleanupPacksLineStage() {
        val packs = ReplaceRules.parse(CLEANUP_PACKS)!!
        assertEquals(5, packs.size)
        val clean = lineStage(synthetic(15_000_000, 2), packs)
        val noisy = lineStage(synthetic(15_000_000, 2, noise = true), packs)

        // The whole first open of the noisy file with the packs, for the report.
        val f = TxtTestUtil.writeTemp(dir, "big15packs.txt", noisy.bytes)
        val o = ParseOptions(txtReplaceRules = CLEANUP_PACKS)
        val first = TxtTestUtil.withCacheDir(cacheDir) {
            TxtIndexStore.fileFor(TxtIndexStore.key(f, o))?.delete()
            System.gc()
            val t0 = System.nanoTime()
            TxtDocuments.open(f, o).close()
            ms(t0)
        }
        for ((name, st) in listOf("perf file" to clean, "noisy" to noisy)) {
            println(
                "TXT perf: cleanup packs, %s %.1f MB CP949, %d lines (%d deleted, %d rewritten) | line stage %.0f ms without rules, %.0f ms with 5 packs (%+.0f%%)"
                    .format(name, st.bytes.size / 1e6, st.lines, st.deleted, st.rewritten, st.plain, st.withPacks, (st.withPacks / st.plain - 1) * 100)
            )
        }
        println("TXT perf: cleanup packs, noisy first open %.0f ms".format(first))
        assertEquals(0, clean.deleted)
        assertTrue("noise lines deleted: ${noisy.deleted}", noisy.deleted > 1000 && noisy.rewritten > 500)
        assertTrue("line stage with packs ${clean.withPacks} ms vs ${clean.plain} ms", clean.withPacks <= clean.plain * 1.3)
        assertTrue("noisy line stage with packs ${noisy.withPacks} ms vs ${noisy.plain} ms", noisy.withPacks <= noisy.plain * 2)
    }

    private class Stage(val bytes: ByteArray, val plain: Double, val withPacks: Double, val lines: Int, val deleted: Int, val rewritten: Int)

    private fun lineStage(bytes: ByteArray, packs: ReplaceRules): Stage {
        val dec = TxtCharsets.cp949
        val nl = TxtParser.detectNewline(dec, bytes, 0, bytes.size)
        val decoded = CharArray(dec.maxChars(bytes.size))
        val n = dec.decode(bytes, 0, bytes.size, decoded, 0)
        fun run(rules: ReplaceRules?): Pair<Double, LineTable> {
            val buf = decoded.copyOf() // the pipeline normalises in place
            System.gc()
            val t0 = System.nanoTime()
            val t = LineTable.build(buf, n, nl.toChar(), LineConfig(true, rules, dec.canAdvance), ByteMap(bytes, 0, bytes.size, dec))
            return ms(t0) to t
        }
        repeat(2) { run(null); run(packs) } // warm-up
        var plain = Double.MAX_VALUE
        var withPacks = Double.MAX_VALUE
        var table: LineTable? = null
        repeat(7) {
            plain = minOf(plain, run(null).first)
            val (ms, t) = run(packs)
            withPacks = minOf(withPacks, ms)
            table = t
        }
        val t = table!!
        var deleted = 0
        var rewritten = 0
        for (i in 0 until t.count) {
            if (t.flags[i] and LineFlags.DELETED != 0) deleted++
            if (t.flags[i] and LineFlags.OV != 0) rewritten++
        }
        return Stage(bytes, plain, withPacks, t.count, deleted, rewritten)
    }

    @Test
    fun fileWithoutLineBreaksStaysFast() {
        val r = Random(3)
        val text = TxtTestUtil.body(r, 3_000_000, " ")
        val f = TxtTestUtil.writeTemp(dir, "oneline.txt", text.toByteArray(Charsets.UTF_8))
        TxtTestUtil.withCacheDir(cacheDir) {
            val t0 = System.nanoTime()
            val book = TxtDocuments.open(f, ParseOptions())
            val open = ms(t0)
            var max = 0
            for (s in book.sections) max = maxOf(max, s.approxChars)
            val t1 = System.nanoTime()
            book.loadSection(book.sections.size / 2)
            val load = ms(t1)
            println("TXT perf: 3M chars without line breaks: open %.0f ms, %d sections (max %d chars), mid section load %.1f ms".format(open, book.sections.size, max, load))
            assertTrue(max <= TxtParser.TARGET_CHARS * 2)
            assertTrue(load < 50)
            book.close()
        }
    }
}
