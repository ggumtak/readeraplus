package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.format.LoadHints
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.container
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.text
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.writeEpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Page counting (a background thread) must not evict the split item or the sections the reader is using. */
class EpubBackgroundLoadTest {
    private val names = listOf("a", "b", "c", "d")

    /** Four whole-book items, each big enough to be split. */
    private fun fourSplitItems(): File {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        val points = StringBuilder()
        val entries = ArrayList<EpubTestUtil.Entry>()
        for ((i, n) in names.withIndex()) {
            manifest.append("<item id=\"$n\" href=\"Text/$n.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            spine.append("<itemref idref=\"$n\"/>")
            points.append(
                "<navPoint id=\"p$i\" playOrder=\"$i\"><navLabel><text>$n</text></navLabel>" +
                    "<content src=\"Text/$n.xhtml\"/></navPoint>",
            )
            entries.add(text("OEBPS/Text/$n.xhtml", EpubTestUtil.converterItem(400 * 1024)))
        }
        val opf = """<package version="2.0"><metadata><dc:title>네 권</dc:title><dc:language>ko</dc:language></metadata>
<manifest>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
$manifest</manifest><spine toc="ncx">$spine</spine></package>"""
        entries.add(0, container("OEBPS/content.opf"))
        entries.add(1, text("OEBPS/content.opf", opf))
        entries.add(2, text("OEBPS/toc.ncx", "<ncx><navMap>$points</navMap></ncx>"))
        return writeEpub(entries, name = "four.epub")
    }

    private fun <T> onBackgroundThread(body: () -> T): T {
        var result: Result<T>? = null
        val t = Thread {
            LoadHints.markBackground()
            result = runCatching(body)
        }
        t.start()
        t.join()
        return result!!.getOrThrow()
    }

    @Test
    fun countingThreadDoesNotEvictTheReadersItem() {
        EpubBook.open(fourSplitItems(), ParseOptions(txtDetectChapters = false)).use { doc ->
            val parts = doc.partCounts
            assertEquals(names.size, parts.size)
            for (p in parts) assertTrue("parts $parts", p >= 2)
            val first = IntArray(parts.size + 1)
            for (i in parts.indices) first[i + 1] = first[i] + parts[i]
            assertEquals(0, doc.conversions)

            // the reader opens item A
            val a0 = doc.loadSection(first[0])
            assertEquals(1, doc.conversions)

            // page counting walks items B, C and D (more split items than the item cache holds)
            onBackgroundThread {
                for (s in first[1] until first[4]) doc.loadSection(s)
            }
            assertEquals(4, doc.conversions)

            // the reader's section is still cached; its next page (same item) is cut without converting A again
            assertSame(a0, doc.loadSection(first[0]))
            doc.loadSection(first[0] + 1)
            assertEquals(4, doc.conversions)

            // the item counted last is taken over by the reader without a conversion, and stays for the counter
            doc.loadSection(first[3] + 1)
            assertEquals(4, doc.conversions)
            onBackgroundThread { doc.loadSection(first[3]) }
            assertEquals(4, doc.conversions)
        }
    }
}
