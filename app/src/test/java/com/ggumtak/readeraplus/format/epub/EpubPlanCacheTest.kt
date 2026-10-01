package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.container
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.text
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.writeEpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The EPUB section-plan cache (A12-1) and the golden values that guard [EpubPlanCache.VERSION]. */
class EpubPlanCacheTest {
    private val cacheDir: File = Files.createTempDirectory("epubplan").toFile().also { it.deleteOnExit() }

    private fun withCache(body: () -> Unit) {
        val old = Documents.cacheDir
        try {
            Documents.cacheDir = Files.createTempDirectory("epubplan-old").toFile().also { it.deleteOnExit() }
            EpubPlanCache.writePending() // leftovers of other tests go elsewhere
            Documents.cacheDir = cacheDir
            body()
        } finally {
            Documents.cacheDir = old
        }
    }

    private fun planFiles(): List<File> = File(cacheDir, "epubplan").listFiles()?.filter { it.name.endsWith(".bin") } ?: emptyList()

    // ================================================================ golden

    private val goldenSentences = listOf(
        "새벽 기차가 떠난 뒤에야 역 앞 골목에 불이 하나씩 켜졌다.",
        "그녀는 편지를 두 번 접어 외투 안주머니에 넣었다.",
        "“내일은 비가 온대.” 소년이 창밖을 보며 중얼거렸다.",
        "A small boat drifted past the pier &amp; the gulls followed it.",
        "책상 위에는   식은 차와 펼쳐 둔 지도가 그대로 남아 있었다.",
    )

    /**
     * A fixed converter-style whole-book XHTML of [chapters] chapters (no randomness): head, style and script to skip,
     * entities, whitespace runs, anchors as `id` on headings and paragraphs and as `<a name>`.
     */
    private fun goldenXhtml(chapters: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\">\n")
        sb.append("<head><title>골든</title><style>p { text-indent: 1em; }</style><script>var x = '<p>';</script></head>\n<body>\n")
        for (c in 1..chapters) {
            if (c % 5 == 0) sb.append("<p><a name=\"ch$c\"></a></p>\n<h2>제${c}화</h2>\n") else sb.append("<h2 id=\"ch$c\">제${c}화 여름</h2>\n")
            for (k in 0 until 12 + c % 7) {
                sb.append("<p")
                if (k == 3 && c % 4 == 0) sb.append(" id=\"note$c\"")
                sb.append(">")
                for (j in 0..(c + k) % 3) sb.append(goldenSentences[(c * 7 + k * 3 + j) % goldenSentences.size]).append(' ')
                sb.append("</p>\n")
            }
        }
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    /** The TOC anchors asked for: every chapter, two notes, and one id the text doesn't have. */
    private fun goldenWanted(chapters: Int): Set<String> = (1..chapters).map { "ch$it" }.toSet() + setOf("note8", "note40", "note160", "absent")

    /** The plan values [EpubPlanCache] stores, as one canonical string. */
    private fun planString(xhtml: String, wanted: Set<String>): String {
        val scan = EpubSplit.scan(xhtml, wanted)
        val n = EpubSplit.partsFor(scan.chars)
        val sb = StringBuilder()
        sb.append("chars=").append(scan.chars).append(" parts=").append(n)
        for ((id, before) in scan.anchors.toSortedMap()) sb.append(' ').append(id).append('@').append(before)
        if (n > 1) for ((id, part) in EpubSplit.assign(scan, n).toSortedMap()) sb.append(' ').append(id).append('>').append(part)
        return sb.toString()
    }

    private fun fnv(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    /**
     * Golden values of [EpubSplit.scan] / [EpubSplit.partsFor] / [EpubSplit.assign] (A12-1): a cached plan is reused
     * only while these give the same result, so a change here must bump [EpubPlanCache.VERSION].
     */
    @Test
    fun goldenPlan() {
        val msg = "EpubSplit plan output changed: bump EpubPlanCache.VERSION and update the golden values"
        val counts = listOf(0, 1, 80_000, 80_001, 120_000, 120_001, 1_000_000, 500_000_000).map { EpubSplit.partsFor(it) }
        assertEquals(msg, listOf(1, 1, 1, 3, 3, 4, 25, 4096), counts)
        // one part: the scan's char count and anchor positions
        val small = planString(goldenXhtml(48), goldenWanted(48))
        // several parts: anchors assigned across them
        val big = planString(goldenXhtml(192), goldenWanted(192))
        assertTrue(msg + "\n" + small, small.startsWith("chars=$GOLDEN_SMALL_CHARS parts=1 "))
        assertEquals(msg + "\n" + small, GOLDEN_SMALL_HASH, fnv(small))
        assertTrue(msg + "\n" + big, big.startsWith("chars=$GOLDEN_BIG_CHARS parts=$GOLDEN_BIG_PARTS "))
        assertEquals(msg + "\n" + big, GOLDEN_BIG_HASH, fnv(big))
    }

    // ================================================================ cache

    private fun ncxPoint(i: Int, label: String, src: String) =
        "<navPoint id=\"n$i\" playOrder=\"$i\"><navLabel><text>$label</text></navLabel><content src=\"$src\"/></navPoint>"

    /** Front matter, one whole-book XHTML well above the scan threshold with [chapters] anchored chapters, an end. */
    private fun wholeBookEpub(chapters: Int, name: String = "book.epub"): File {
        val body = StringBuilder()
        for (c in 1..chapters) {
            body.append("<h2 id=\"ch$c\">제${c}화</h2>\n")
            for (k in 0 until 40) body.append("<p>").append(EpubTestUtil.SENTENCES[(c + k) % EpubTestUtil.SENTENCES.size]).append("</p>\n")
        }
        val points = StringBuilder(ncxPoint(0, "표지", "Text/front.xhtml"))
        for (c in 1..chapters) points.append(ncxPoint(c, "제${c}화", "Text/book.xhtml#ch$c"))
        points.append(ncxPoint(chapters + 1, "후기", "Text/end.xhtml"))
        val opf = """<package version="2.0"><metadata><dc:title>통짜</dc:title></metadata>
<manifest>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="front" href="Text/front.xhtml" media-type="application/xhtml+xml"/>
<item id="book" href="Text/book.xhtml" media-type="application/xhtml+xml"/>
<item id="end" href="Text/end.xhtml" media-type="application/xhtml+xml"/>
</manifest><spine toc="ncx"><itemref idref="front"/><itemref idref="book"/><itemref idref="end"/></spine></package>"""
        val book = EpubTestUtil.html(body.toString())
        assertTrue(book.toByteArray().size > EpubSplit.SCAN_MIN_BYTES)
        return writeEpub(
            listOf(
                container("OEBPS/content.opf"),
                text("OEBPS/content.opf", opf),
                text("OEBPS/toc.ncx", "<ncx><navMap>$points</navMap></ncx>"),
                text("OEBPS/Text/front.xhtml", EpubTestUtil.html("<h1>통짜</h1>")),
                text("OEBPS/Text/book.xhtml", book),
                text("OEBPS/Text/end.xhtml", EpubTestUtil.html("<h1>후기</h1><p>끝.</p>")),
            ),
            name,
        )
    }

    private class Snapshot(val parts: List<Int>, val approx: List<Int>, val toc: List<Pair<Int, String?>>, val texts: List<String>)

    private fun snapshot(f: File): Pair<Snapshot, Boolean> = EpubBook.open(f, ParseOptions()).use { d ->
        Snapshot(d.partCounts, d.sections.map { it.approxChars }, d.toc.map { it.section to it.anchor }, d.sections.indices.map { d.loadSection(it).text }) to
            d.planFromCache
    }

    private fun assertSame(a: Snapshot, b: Snapshot) {
        assertEquals(a.parts, b.parts)
        assertEquals(a.approx, b.approx)
        assertEquals(a.toc, b.toc)
        assertEquals(a.texts, b.texts)
    }

    @Test
    fun reopenUsesTheCachedPlanWrittenAfterTheFirstPage() = withCache {
        val f = wholeBookEpub(60)
        val (first, cached1) = snapshot(f)
        assertFalse(cached1)
        assertTrue("split", first.parts[1] > 1)
        // staged, not written while opening
        assertEquals(1, EpubPlanCache.pendingCount)
        assertTrue(planFiles().isEmpty())
        Documents.writeDeferredCaches()
        assertEquals(0, EpubPlanCache.pendingCount)
        assertEquals(1, planFiles().size)

        val (second, cached2) = snapshot(f)
        assertTrue("plan from the cache", cached2)
        assertSame(first, second)
        Documents.writeDeferredCaches() // the recently-used mark of the hit
        assertEquals(0, EpubPlanCache.pendingCount)

        // the file changes: another key, scanned again
        f.setLastModified(f.lastModified() + 10_000)
        val (third, cached3) = snapshot(f)
        assertFalse(cached3)
        assertSame(first, third)
        Documents.writeDeferredCaches()
        assertEquals(2, planFiles().size)
    }

    @Test
    fun secondOpenOfABigBookIsFaster() = withCache {
        val f = wholeBookEpub(600, "big.epub") // one ~2 MB spine item
        fun openMs(): Double {
            val t0 = System.nanoTime()
            EpubBook.open(f, ParseOptions()).close()
            return (System.nanoTime() - t0) / 1e6
        }
        val key = EpubPlanCache.key(f)
        var scanned = Double.MAX_VALUE
        repeat(4) {
            EpubPlanCache.fileFor(key)!!.delete()
            scanned = minOf(scanned, openMs())
        }
        Documents.writeDeferredCaches()
        var cached = Double.MAX_VALUE
        repeat(4) { cached = minOf(cached, openMs()) }
        Documents.writeDeferredCaches()
        println("EPUB perf: one ~2 MB spine item (%d KB zipped) | open with scan %.1f ms, with cached plan %.1f ms".format(f.length() / 1024, scanned, cached))
        assertTrue("cached $cached ms vs scanned $scanned ms", cached < scanned)
    }

    @Test
    fun damagedOrStalePlansAreIgnored() = withCache {
        val f = wholeBookEpub(60, "damaged.epub")
        val (first, _) = snapshot(f)
        Documents.writeDeferredCaches()
        val key = EpubPlanCache.key(f)
        val file = EpubPlanCache.fileFor(key)!!
        val good = file.readBytes()
        val plan = EpubPlanCache.decode(good, key)!!

        // truncated → scanned again, same result
        file.writeBytes(good.copyOf(good.size / 2))
        assertNull(EpubPlanCache.load(key))
        val (a, cachedA) = snapshot(f)
        assertFalse(cachedA)
        assertSame(first, a)

        // the TOC anchors differ (another app version parsed the TOC differently) → not used
        val other = EpubPlanCache.Plan(plan.spineSize, plan.anchors + 1, plan.items, plan.parts, plan.chars, plan.frags)
        file.writeBytes(EpubPlanCache.encode(key, other))
        val (b, cachedB) = snapshot(f)
        assertFalse(cachedB)
        assertSame(first, b)

        // another spine size / other scanned items → not used
        val spine = EpubPlanCache.Plan(plan.spineSize + 1, plan.anchors, plan.items, plan.parts, plan.chars, plan.frags)
        file.writeBytes(EpubPlanCache.encode(key, spine))
        assertFalse(snapshot(f).second)
        val items = EpubPlanCache.Plan(plan.spineSize, plan.anchors, intArrayOf(0), plan.parts, plan.chars, plan.frags)
        file.writeBytes(EpubPlanCache.encode(key, items))
        assertFalse(snapshot(f).second)

        // the good plan again → used
        file.writeBytes(good)
        assertTrue(snapshot(f).second)
        Documents.writeDeferredCaches()
    }

    @Test
    fun smallBooksNeverLookUpTheCache() = withCache {
        val opf = """<package><metadata/><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="a"/></spine></package>"""
        val f = writeEpub(listOf(container("content.opf"), text("content.opf", opf), text("a.xhtml", EpubTestUtil.html("<p>짧은 책</p>"))), "small.epub")
        val before = EpubPlanCache.lookups
        EpubBook.open(f, ParseOptions()).use { assertFalse(it.planFromCache) }
        assertEquals(before, EpubPlanCache.lookups)
        assertEquals(0, EpubPlanCache.pendingCount)
        assertFalse(File(cacheDir, "epubplan").exists())
    }

    @Test
    fun noCacheDirNoStaging() {
        val old = Documents.cacheDir
        Documents.cacheDir = null
        try {
            val f = wholeBookEpub(60, "nocache.epub")
            val before = EpubPlanCache.lookups
            val pending = EpubPlanCache.pendingCount
            EpubBook.open(f, ParseOptions()).use { assertTrue(it.partCounts[1] > 1) }
            assertEquals(before, EpubPlanCache.lookups)
            assertEquals(pending, EpubPlanCache.pendingCount)
            Documents.writeDeferredCaches() // nothing to write, no directory: never throws
        } finally {
            Documents.cacheDir = old
        }
    }

    @Test
    fun codecRoundTripAndValidation() {
        val key = "v${EpubPlanCache.VERSION}|/b.epub|123|456"
        val plan = EpubPlanCache.Plan(
            5, 77L, intArrayOf(1, 3), intArrayOf(4, 1), intArrayOf(160_000, 70_000), arrayOf(mapOf("a" to 0, "하나" to 3), null),
        )
        val data = EpubPlanCache.encode(key, plan)
        val back = EpubPlanCache.decode(data, key)
        assertNotNull(back)
        back!!
        assertEquals(5, back.spineSize)
        assertEquals(77L, back.anchors)
        assertEquals(listOf(1, 3), back.items.toList())
        assertEquals(listOf(4, 1), back.parts.toList())
        assertEquals(listOf(160_000, 70_000), back.chars.toList())
        assertEquals(mapOf("a" to 0, "하나" to 3), back.frags[0])
        assertNull(back.frags[1])

        assertNull(EpubPlanCache.decode(data, key + "x")) // another file
        assertNull(EpubPlanCache.decode(data.copyOf(data.size - 1), key))
        assertNull(EpubPlanCache.decode(ByteArray(0), key))
        fun bad(p: EpubPlanCache.Plan) = assertNull(EpubPlanCache.decode(EpubPlanCache.encode(key, p), key))
        bad(EpubPlanCache.Plan(5, 0, intArrayOf(3, 1), intArrayOf(1, 1), intArrayOf(0, 0), arrayOf(null, null))) // not ascending
        bad(EpubPlanCache.Plan(2, 0, intArrayOf(2), intArrayOf(1), intArrayOf(0), arrayOf(null))) // outside the spine
        bad(EpubPlanCache.Plan(5, 0, intArrayOf(1), intArrayOf(2), intArrayOf(0), arrayOf(mapOf("a" to 2)))) // part out of range
        bad(EpubPlanCache.Plan(5, 0, intArrayOf(1), intArrayOf(2), intArrayOf(0), arrayOf(null))) // split without anchors map
        bad(EpubPlanCache.Plan(5, 0, intArrayOf(1), intArrayOf(0), intArrayOf(0), arrayOf(null))) // no parts
    }

    @Test
    fun stagedWritesAreBounded() = withCache {
        val plan = EpubPlanCache.Plan(1, 0, intArrayOf(0), intArrayOf(1), intArrayOf(0), arrayOf(null))
        for (k in 0 until 10) EpubPlanCache.stage("k$k", plan)
        EpubPlanCache.stage("k9", plan) // the same key once
        assertEquals(4, EpubPlanCache.pendingCount)
        Documents.writeDeferredCaches()
        assertEquals(4, planFiles().size)
        assertNotNull(EpubPlanCache.load("k9"))
        assertNull(EpubPlanCache.load("k0"))
        Documents.writeDeferredCaches()
    }

    private companion object {
        // EpubPlanCache.VERSION 1
        const val GOLDEN_SMALL_CHARS = 54783
        const val GOLDEN_SMALL_HASH = 6379262348441614560L
        const val GOLDEN_BIG_CHARS = 217973
        const val GOLDEN_BIG_PARTS = 6
        const val GOLDEN_BIG_HASH = -7704661065254693615L
    }
}
