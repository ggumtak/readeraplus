package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.format.TocEntry
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.container
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.text
import com.ggumtak.readeraplus.format.epub.EpubTestUtil.writeEpub
import com.ggumtak.readeraplus.format.txt.TxtParagraphs
import com.ggumtak.readeraplus.reader.ChapterIndex
import com.ggumtak.readeraplus.reader.extras.EpisodeNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Chapter headings detected in an EPUB with a poor TOC (TXT→EPUB converter output): the TOC, the heading look, the
 * part boundaries, the plan cache and the cases that leave the book alone.
 */
class EpubHeadingsTest {
    private val sentences = EpubTestUtil.SENTENCES

    private fun paragraphs(c: Int, n: Int): String =
        (0 until n).joinToString("\n") { "<p>${sentences[(c + it) % sentences.size]} ${sentences[(c * 3 + it) % sentences.size]}</p>" }

    /** A whole-book body: [chapters] chapters, each a heading paragraph made by [heading] and [paras] long paragraphs. */
    private fun body(chapters: Int, paras: Int = 40, heading: (Int) -> String = { "<p>${it}화</p>" }): String {
        val sb = StringBuilder("<p>어느 작은 항구 마을의 이야기</p>\n")
        for (c in 1..chapters) sb.append(heading(c)).append('\n').append(paragraphs(c, paras)).append('\n')
        return sb.toString()
    }

    private fun ncx(points: List<Pair<String, String>>): String {
        val sb = StringBuilder("<ncx><navMap>")
        for ((i, p) in points.withIndex()) {
            sb.append("<navPoint id=\"n$i\" playOrder=\"$i\"><navLabel><text>${p.first}</text></navLabel><content src=\"${p.second}\"/></navPoint>")
        }
        return sb.append("</navMap></ncx>").toString()
    }

    /** One spine item "Text/book.xhtml" holding [bodyHtml], with the given NCX points (none: no TOC document). */
    private fun epub(bodyHtml: String, points: List<Pair<String, String>>, name: String = "book.epub"): File {
        val opf = """<package version="2.0"><metadata><dc:title>통짜 소설</dc:title></metadata>
<manifest>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="book" href="Text/book.xhtml" media-type="application/xhtml+xml"/>
</manifest><spine toc="ncx"><itemref idref="book"/></spine></package>"""
        return writeEpub(
            listOf(
                container("OEBPS/content.opf"),
                text("OEBPS/content.opf", opf),
                text("OEBPS/toc.ncx", ncx(points)),
                text("OEBPS/Text/book.xhtml", EpubTestUtil.html(bodyHtml)),
            ),
            name,
        )
    }

    private val titleOnly = listOf("통짜 소설" to "Text/book.xhtml")

    private fun titles(d: EpubBook): List<String> = d.toc.map { it.title }

    private fun chapterTitles(n: Int): List<String> = (1..n).map { "${it}화" }

    /** The block holding [offset] of [c] and its text. */
    private fun blockAt(c: SectionContent, offset: Int): Pair<ParagraphBlock, String> {
        val b = c.blocks.filterIsInstance<ParagraphBlock>().first { offset >= it.start && offset <= it.end }
        return b to c.text.substring(b.start, b.end)
    }

    private fun heading(d: EpubBook, e: TocEntry): Pair<ParagraphBlock, String> {
        val pos = d.resolveToc(e)
        return blockAt(d.loadSection(pos.section), pos.offset)
    }

    // ================================================================ the main case

    /** Section count, and the text of every section, of [f] opened with [o]. */
    private fun partition(f: File, o: ParseOptions): Pair<List<Int>, List<String>> =
        EpubBook.open(f, o).use { d -> d.partCounts to d.sections.indices.map { d.loadSection(it).text } }

    @Test
    fun aTitleOnlyTocGetsTheDetectedChapters() {
        val f = epub(body(60), titleOnly)
        EpubBook.open(f, ParseOptions()).use { d ->
            assertEquals(listOf("통짜 소설") + chapterTitles(60), titles(d))
            val parts = d.partCounts[0]
            assertTrue("split into parts: $parts", parts >= 3)
            assertEquals(parts, d.sections.size)
            var prev = -1L
            for (k in 1..60) {
                val e = d.toc[k]
                assertNotNull(e.anchor)
                assertTrue(e.anchor!!.startsWith(EpubHeadings.ID_PREFIX))
                val pos = d.resolveToc(e)
                // the entry is already where its heading is: section and offset
                assertEquals("section of ${e.title}", pos.section, e.section)
                assertEquals("offset of ${e.title}", pos.offset, e.offset)
                val at = (pos.section.toLong() shl 32) or pos.offset.toLong()
                assertTrue("in order", at > prev)
                prev = at
                val (b, t) = heading(d, e)
                assertEquals("${k}화", t)
                assertEquals("top of its block", b.start, pos.offset)
                // like a TXT chapter: bold centred, always at the top of a page
                assertTrue(b.style.pageBreakBefore)
                assertEquals(TxtParagraphs.HEADING_STYLE, b.style)
            }
            assertTrue((0 until parts).any { d.loadSection(it).styleRuns.isNotEmpty() })
            // every heading exists once, in order
            val all = (0 until parts).flatMap { s -> d.loadSection(s).text.split('\n') }.filter { Regex("\\d+화").matches(it) }
            assertEquals(chapterTitles(60), all)
            // the chapter list works with chapters that start in the middle of a section
            val chapters = ChapterIndex(d.toc, d.sections.size)
            assertEquals(61, chapters.size)
            for (k in 1..60) {
                val p = chapters.position(k)
                assertEquals(k, chapters.indexAt(p.section, p.offset))
                assertEquals(k - 1, chapters.lastBefore(p.section, p.offset))
                if (k < 60) assertEquals(k + 1, chapters.nextAfter(p.section, p.offset))
                assertEquals(k, chapters.indexAt(p.section, p.offset + 5)) // inside its text
            }
            assertTrue((1..60).any { chapters.offset(it) > 0 })
        }
    }

    @Test
    fun detectionNeverMovesTheCuts() {
        // saved positions, bookmarks and notes are (section, offset): the partition is the one of the book without
        // detection, to the char
        for ((name, html) in listOf("plain" to body(60), "listing" to "<p>목차</p>\n" + (1..8).joinToString("\n") { "<p>${it}화</p>" } + body(50), "h3" to body(50) { "<h3>${it}화</h3>" })) {
            val f = epub(html, titleOnly, "cuts-$name.epub")
            val off = partition(f, ParseOptions(txtDetectChapters = false))
            val on = partition(f, ParseOptions())
            assertTrue("$name: split", off.first[0] >= 3)
            assertEquals("$name: part count", off.first, on.first)
            assertEquals("$name: every part's text", off.second, on.second)
            assertEquals("$name: emphasis off", off.second, partition(f, ParseOptions(txtEmphasizeHeadings = false)).second)
        }
    }

    @Test
    fun aHeadingAtACutStillResolves() {
        // one long paragraph per chapter: a cut often falls right before a heading
        fun short(n: Int): String = body(n, 1) { "<p>${it}화</p>" }
        var found = false
        for (n in 800..1100 step 3) {
            val f = epub(short(n), titleOnly, "edge$n.epub")
            val off = partition(f, ParseOptions(txtDetectChapters = false))
            EpubBook.open(f, ParseOptions()).use { d ->
                val parts = d.partCounts[0]
                assertEquals(off.first, d.partCounts)
                for (s in 1 until parts) {
                    val c = d.loadSection(s)
                    val first = c.text.substringBefore('\n')
                    if (!Regex("\\d+화").matches(first)) continue
                    found = true
                    // the heading is the first block of its part (the part boundary is the legacy one)
                    val k = first.removeSuffix("화").toInt()
                    val e = d.toc[k]
                    assertEquals(first, e.title)
                    assertEquals(s, e.section)
                    assertEquals(0, e.offset)
                    val pos = d.resolveToc(e)
                    assertEquals(s, pos.section)
                    assertEquals(0, pos.offset)
                    assertEquals(first, heading(d, e).second)
                    assertTrue(c.blocks[0].let { it is ParagraphBlock && it.style.pageBreakBefore })
                    assertTrue(c.anchors.containsKey(e.anchor))
                    // the part before it ends with the previous chapter's text, not with the heading
                    assertFalse(d.loadSection(s - 1).text.endsWith(first))
                }
                if (found) return
            }
        }
        assertTrue("no cut fell on a heading", found)
    }

    @Test
    fun episodeNumbersReadTheSyntheticTitles() {
        EpubBook.open(epub(body(20), titleOnly), ParseOptions()).use { d ->
            val numbers = d.toc.drop(1).map { EpisodeNumbers.parse(it.title) }
            assertEquals((1..20).toList(), numbers)
        }
    }

    @Test
    fun plainHeadingsWhenEmphasisIsOff() {
        EpubBook.open(epub(body(30), titleOnly), ParseOptions(txtEmphasizeHeadings = false)).use { d ->
            assertEquals(31, d.toc.size)
            val (b, t) = heading(d, d.toc[5])
            assertEquals("5화", t)
            assertEquals(TxtParagraphs.PLAIN_HEADING_STYLE, b.style)
            for (s in 0 until d.sections.size) assertTrue(d.loadSection(s).styleRuns.isEmpty())
        }
    }

    @Test
    fun aHeadingThatIsAlreadyAHeadingKeepsItsLookAndGetsThePageBreak() {
        val f = epub(body(30) { "<h3>${it}화</h3>" }, titleOnly)
        EpubBook.open(f, ParseOptions()).use { d ->
            assertEquals(31, d.toc.size)
            val (b, t) = heading(d, d.toc[3])
            assertEquals("3화", t)
            assertEquals(3, b.style.headingLevel)
            assertTrue(b.style.pageBreakBefore)
        }
    }

    @Test
    fun headingsAfterSymbols() {
        val f = epub(
            body(30, 30) { "<p>◈ ${"%03d".format(it)}. [STAGE ${it / 10}] 튜토리얼 시작합니다</p>\n<p>◆ 그는 말했다.</p>" },
            titleOnly,
        )
        EpubBook.open(f, ParseOptions()).use { d ->
            assertEquals(31, d.toc.size)
            assertEquals("◈ 001. [STAGE 0] 튜토리얼 시작합니다", d.toc[1].title)
            assertEquals("◈ 030. [STAGE 3] 튜토리얼 시작합니다", d.toc[30].title)
            assertTrue(d.toc.none { it.title.contains("그는") })
            val (b, t) = heading(d, d.toc[2])
            assertEquals("◈ 002. [STAGE 0] 튜토리얼 시작합니다", t)
            assertTrue(b.style.pageBreakBefore)
        }
    }

    @Test
    fun aListingOfChaptersIsPrunedAndTheRealHeadingsAreMatched() {
        // a TOC page written as paragraphs: "1화" … "8화" right before the first chapter, whose heading is the
        // second block with that text
        val listing = (1..8).joinToString("\n") { "<p>${it}화</p>" }
        val f = epub("<p>목차</p>\n$listing\n" + body(40), titleOnly)
        EpubBook.open(f, ParseOptions()).use { d ->
            assertEquals(listOf("통짜 소설") + chapterTitles(40), titles(d))
            for (k in intArrayOf(1, 2, 8, 9, 40)) {
                val (b, t) = heading(d, d.toc[k])
                assertEquals("${k}화", t)
                assertTrue("a real heading, not the listing line: $k", b.style.pageBreakBefore)
                // the body follows it
                val c = d.loadSection(d.resolveToc(d.toc[k]).section)
                val next = c.blocks.filterIsInstance<ParagraphBlock>().first { it.start > b.start }
                assertTrue(next.end - next.start > 60)
            }
        }
    }

    @Test
    fun aSmallBookWithOneTocEntryIsDetectedToo() {
        val f = epub(body(8, 6), titleOnly, "small.epub")
        assertTrue(f.length() < EpubSplit.SCAN_MIN_BYTES)
        val before = EpubPlanCache.lookups
        EpubBook.open(f, ParseOptions()).use { d ->
            assertEquals(1, d.sections.size)
            assertEquals(listOf("통짜 소설") + chapterTitles(8), titles(d))
            val (b, t) = heading(d, d.toc[4])
            assertEquals("4화", t)
            assertTrue(b.style.pageBreakBefore)
            assertEquals(0, d.scannedItems)
        }
        assertEquals("small: no cache lookup", before, EpubPlanCache.lookups)
    }

    // ================================================================ left alone

    @Test
    fun aRealTocWithAnchorsInsideTheItemIsKept() {
        val html = body(40) { "<h2 id=\"ch$it\">${it}화</h2>" }
        val points = (1..40).map { "${it}화" to "Text/book.xhtml#ch$it" }
        EpubBook.open(epub(html, points), ParseOptions()).use { d ->
            assertEquals(chapterTitles(40), titles(d))
            assertTrue(d.toc.all { it.anchor!!.startsWith("ch") })
            // nothing was added to the converted text either
            for (s in 0 until d.sections.size) assertTrue(d.loadSection(s).anchors.keys.none { it.startsWith(EpubHeadings.ID_PREFIX) })
        }
    }

    @Test
    fun noDetectionWhenChapterDetectionIsOff() {
        EpubBook.open(epub(body(40), titleOnly), ParseOptions(txtDetectChapters = false)).use { d ->
            assertEquals(listOf("통짜 소설"), titles(d))
            for (s in 0 until d.sections.size) {
                val c = d.loadSection(s)
                assertTrue(c.anchors.keys.none { it.startsWith(EpubHeadings.ID_PREFIX) })
                assertTrue(c.blocks.filterIsInstance<ParagraphBlock>().none { it.style.pageBreakBefore })
            }
        }
    }

    @Test
    fun noChaptersNoTocEntries() {
        EpubBook.open(epub(paragraphs(1, 40) + paragraphs(2, 40), titleOnly), ParseOptions()).use { d ->
            assertEquals(listOf("통짜 소설"), titles(d))
        }
    }

    @Test
    fun theUserRuleIsUsed() {
        val f = epub(body(30) { "<p>〈 제${it}막 〉</p>" }, titleOnly)
        EpubBook.open(f, ParseOptions()).use { assertEquals(31, it.toc.size) } // K1 reads 막 too
        val g = epub(body(30) { "<p>-${it}-</p>" }, titleOnly, "rule.epub")
        EpubBook.open(g, ParseOptions()).use { assertEquals(listOf("통짜 소설"), titles(it)) }
        EpubBook.open(g, ParseOptions(txtChapterRegex = "^-\\d+-$")).use {
            assertEquals(31, it.toc.size)
            assertEquals("-7-", it.toc[7].title)
        }
    }

    @Test
    fun replacesTocOnlyWhenItIsPoor() {
        assertTrue(EpubHeadings.replacesToc(0, true, 2))
        assertTrue(EpubHeadings.replacesToc(1, true, 40))
        assertTrue(EpubHeadings.replacesToc(1, false, 40)) // fewer than 2 entries: poor whatever they point at
        assertTrue(EpubHeadings.replacesToc(3, true, 7))
        assertFalse(EpubHeadings.replacesToc(3, true, 6)) // an entry per file, a chapter per file
        assertFalse(EpubHeadings.replacesToc(30, false, 400)) // anchors inside items: the book's own TOC
        assertFalse(EpubHeadings.replacesToc(1, true, 1)) // detection needs two
    }

    // ================================================================ cache

    private val cacheDir: File = Files.createTempDirectory("epubheads").toFile().also { it.deleteOnExit() }

    private fun withCache(block: () -> Unit) {
        val old = Documents.cacheDir
        try {
            Documents.cacheDir = Files.createTempDirectory("epubheads-old").toFile().also { it.deleteOnExit() }
            EpubPlanCache.writePending()
            Documents.cacheDir = cacheDir
            block()
        } finally {
            Documents.cacheDir = old
        }
    }

    private class Snap(val toc: List<Triple<String, Int, String?>>, val parts: List<Int>, val texts: List<String>, val fromCache: Boolean)

    private fun snap(f: File, o: ParseOptions = ParseOptions()): Snap = EpubBook.open(f, o).use { d ->
        Snap(
            d.toc.map { Triple(it.title, it.section, it.anchor) }, d.partCounts, d.sections.indices.map { d.loadSection(it).text },
            d.planFromCache,
        )
    }

    @Test
    fun theCachedPlanCarriesTheDetectedChapters() = withCache {
        val f = epub(body(60), titleOnly, "cached.epub")
        val first = snap(f)
        assertFalse(first.fromCache)
        assertEquals(61, first.toc.size)
        assertEquals(1, EpubPlanCache.pendingCount)
        Documents.writeDeferredCaches()
        val second = snap(f)
        assertTrue("plan from the cache", second.fromCache)
        assertEquals(first.toc, second.toc)
        assertEquals(first.parts, second.parts)
        assertEquals(first.texts, second.texts)
        // the headings are marked in a book opened from the cached plan
        EpubBook.open(f, ParseOptions()).use { d ->
            assertTrue(d.planFromCache)
            assertEquals("the cached plan has the headings' places: nothing converted to open", 0, d.conversions)
            val (b, t) = heading(d, d.toc[9])
            assertEquals("9화", t)
            assertTrue(b.style.pageBreakBefore)
        }
        Documents.writeDeferredCaches()

        // the options that decide the result are part of the key: another rule is another plan, found again later
        val other = snap(f, ParseOptions(txtChapterRegex = "^-\\d+-$"))
        assertFalse(other.fromCache)
        Documents.writeDeferredCaches()
        assertTrue(snap(f, ParseOptions(txtChapterRegex = "^-\\d+-$")).fromCache)
        // detection off: no headings, its own key
        val off = snap(f, ParseOptions(txtDetectChapters = false))
        assertEquals(1, off.toc.size)
        assertFalse(off.fromCache)
        Documents.writeDeferredCaches()
        val offAgain = snap(f, ParseOptions(txtDetectChapters = false))
        assertEquals(1, offAgain.toc.size)
        assertTrue(snap(f).fromCache)
        Documents.writeDeferredCaches()
    }

    @Test
    fun planCodecKeepsTheHeadings() {
        val key = EpubPlanCache.key(File("/b.epub"), "0:")
        assertTrue(key.endsWith("|d0:"))
        val heads = arrayOf(
            EpubHeadings.Head("1화", "1화", 0).also { it.part = 0; it.offset = 14 },
            EpubHeadings.Head("제2화 하나", "제2화 하나", 1).also { it.part = 2; it.offset = 3000 },
        )
        val plan = EpubPlanCache.Plan(
            3, 9L, intArrayOf(0, 2), intArrayOf(3, 1), intArrayOf(120_000, 5_000),
            arrayOf(mapOf("ch1" to 0, "ch9" to 2), null), arrayOf(heads, null),
        )
        val back = EpubPlanCache.decode(EpubPlanCache.encode(key, plan), key)!!
        val h = back.heads[0]!!
        assertEquals(listOf("1화", "제2화 하나"), h.map { it.title })
        assertEquals(listOf("1화", "제2화 하나"), h.map { it.key })
        assertEquals(listOf(0, 1), h.map { it.occurrence })
        assertEquals(listOf(0, 2), h.map { it.part })
        assertEquals(listOf(14, 3000), h.map { it.offset })
        assertNull(back.heads[1])
        assertEquals(mapOf("ch1" to 0, "ch9" to 2), back.frags[0])
        // a plan without headings still reads
        val plain = EpubPlanCache.Plan(1, 0, intArrayOf(0), intArrayOf(1), intArrayOf(0), arrayOf(null))
        assertNull(EpubPlanCache.decode(EpubPlanCache.encode(key, plain), key)!!.heads[0])
        assertNull(EpubPlanCache.decode(EpubPlanCache.encode(key, plain), key + "x"))
    }

    /** What detection adds to the first open of a huge converter book (the item is scanned for its parts anyway). */
    @Test
    fun detectionOnAHugeItemCostsLittle() {
        val f = epub(body(1500, 60), titleOnly, "huge.epub")
        val mb = EpubTestUtil.html(body(1500, 60)).toByteArray().size / 1048576.0
        fun openMs(o: ParseOptions): Double {
            var best = Double.MAX_VALUE
            repeat(3) {
                val t0 = System.nanoTime()
                EpubBook.open(f, o).use { d -> assertTrue(d.toc.size > 1 || !o.txtDetectChapters) }
                best = minOf(best, (System.nanoTime() - t0) / 1e6)
            }
            return best
        }
        val off = openMs(ParseOptions(txtDetectChapters = false))
        val on = openMs(ParseOptions())
        println("EPUB headings perf: %.1f MB item | open without detection %.0f ms, with %.0f ms".format(mb, off, on))
        assertTrue("with $on ms vs without $off ms", on < off + 3000)
    }
}
