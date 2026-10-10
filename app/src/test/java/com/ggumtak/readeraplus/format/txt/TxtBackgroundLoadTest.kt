package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.format.LoadHints
import com.ggumtak.readeraplus.format.ParseOptions
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Page counting (a background thread) must not evict the sections the reader is using. */
class TxtBackgroundLoadTest {
    private val dir = TxtTestUtil.tempDir()

    private fun book(): File {
        val r = Random(7)
        val sb = StringBuilder()
        for (k in 1..10) {
            sb.append("제${k}화 테스트 장\n\n")
            sb.append(TxtTestUtil.body(r, 4000, "\n\n")).append("\n\n")
        }
        return TxtTestUtil.writeTemp(dir, "bg.txt", sb.toString().toByteArray())
    }

    private fun onBackgroundThread(body: () -> Unit) {
        var result: Result<Unit>? = null
        val t = Thread {
            LoadHints.markBackground()
            result = runCatching(body)
        }
        t.start()
        t.join()
        result!!.getOrThrow()
    }

    @Test
    fun countingThreadDoesNotEvictTheReadersSections() {
        TxtTestUtil.withCacheDir(File(dir, "cache")) {
            TxtDocuments.open(book(), ParseOptions()).use { doc ->
                val n = doc.sections.size
                assertTrue("sections $n", n > TxtBook.CACHE_SIZE + 2)
                val s0 = doc.loadSection(0)
                val s1 = doc.loadSection(1)
                onBackgroundThread { for (s in 2 until n) doc.loadSection(s) }
                assertSame(s0, doc.loadSection(0))
                assertSame(s1, doc.loadSection(1))
                // the foreground still evicts as before
                for (s in 2 until n) doc.loadSection(s)
                assertNotSame(s0, doc.loadSection(0))
            }
        }
    }
}
