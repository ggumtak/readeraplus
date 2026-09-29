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

    /** ~[bytes] of CP949 text: web-novel layout (blank line between paragraphs), a chapter every ~5000 chars. */
    private fun synthetic(bytes: Int, seed: Int): ByteArray {
        val r = Random(seed)
        val sb = StringBuilder(bytes / 2 + 1024)
        var ch = 1
        var approxBytes = 0
        while (approxBytes < bytes) {
            val start = sb.length
            sb.append("제").append(ch++).append("화 ").append(TxtTestUtil.sentence(r).take(12)).append("\r\n\r\n")
            var n = 0
            while (n < 5000) {
                val p = TxtTestUtil.paragraph(r, 30 + r.nextInt(200))
                sb.append(p).append("\r\n\r\n")
                n += p.length + 4
            }
            approxBytes += (sb.length - start) * 17 / 10
        }
        return sb.toString().toByteArray(TxtTestUtil.CP949)
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000.0

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
