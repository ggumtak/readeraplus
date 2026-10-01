package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.DocumentException
import com.ggumtak.readeraplus.format.ParseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The byte-range index path must reproduce the whole-file parse exactly (text, blocks, runs). */
class TxtIndexCacheTest {
    private val dir = TxtTestUtil.tempDir()
    private val cacheDir = File(dir, "cache")
    private var fileNo = 0

    /** Opens [bytes] fresh (full parse, index saved) and again from the index; compares with the whole-file parse. */
    private fun checkEquivalence(bytes: ByteArray, o: ParseOptions, where: String) {
        val f = TxtTestUtil.writeTemp(dir, "book${fileNo++}.txt", bytes)
        val expected = TxtParser.parse(bytes, bytes.size, o)
        TxtTestUtil.withCacheDir(cacheDir) {
            val key = TxtIndexStore.key(f, o)
            TxtIndexStore.fileFor(key)!!.delete()
            for (pass in 0..1) {
                if (pass == 1) assertNotNull("$where index saved", TxtIndexStore.load(key, f.length()))
                val book = TxtDocuments.open(f, o)
                try {
                    assertEquals("$where section count", expected.sectionCount, book.sections.size)
                    for (i in 0 until expected.sectionCount) {
                        val ref = expected.buildSection(i, o.txtEmphasizeHeadings)
                        val got = book.loadSection(i)
                        TxtTestUtil.assertSameContent(ref, got, "$where pass $pass section $i")
                        TxtTestUtil.assertInvariants(got, "$where pass $pass section $i")
                        assertEquals("$where approxChars $i", got.length, book.sections[i].approxChars)
                        assertEquals("$where title $i", if (expected.flags[i] and TxtIndex.CHAPTER != 0) expected.titles[i] else null, book.sections[i].title)
                    }
                    assertEquals(expected.decoder.name, book.meta.encoding)
                } finally {
                    book.close()
                }
            }
        }
    }

    private fun text(r: Random): String {
        val sb = StringBuilder()
        for (k in 1..6) {
            sb.append("제${k}화 테스트 장\n\n")
            sb.append(TxtTestUtil.body(r, 3000 + r.nextInt(8000), "\n\n")).append("\n\n")
            if (k == 3) sb.append("* * *\n\n").append(TxtTestUtil.body(r, 2000, "\n\n")).append("\n\n\n\n")
        }
        return sb.toString()
    }

    @Test
    fun equivalenceAcrossEncodings() {
        val t = text(Random(31)) + "똠 햏 쐈 뷁\n"
        val bom8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bom16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val o = ParseOptions()
        checkEquivalence(t.toByteArray(Charsets.UTF_8), o, "utf8")
        checkEquivalence(bom8 + t.toByteArray(Charsets.UTF_8), o, "utf8bom")
        checkEquivalence(t.toByteArray(TxtTestUtil.CP949), o, "cp949")
        checkEquivalence(bom16le + t.toByteArray(Charsets.UTF_16LE), o, "utf16le-bom")
        checkEquivalence(t.toByteArray(Charsets.UTF_16BE), o, "utf16be")
        checkEquivalence(t.replace("\n", "\r\n").toByteArray(TxtTestUtil.CP949), o, "crlf")
        checkEquivalence(t.replace("\n", "\r").toByteArray(Charsets.UTF_8), o, "cr")
        checkEquivalence(t.toByteArray(Charset.forName("EUC-KR")), o.copy(txtEncoding = "EUC-KR"), "forced-euckr")
    }

    @Test
    fun equivalenceAcrossOptions() {
        val r = Random(32)
        val base = text(r)
        val wrapped = TxtTestUtil.body(r, 20000, "\n\n").chunked(37).joinToString("\n")
        val inputs = listOf(base, wrapped, "  " + base.replace("\n\n", "\n  "))
        val opts = listOf(
            ParseOptions(),
            ParseOptions(txtBlankLines = ParseOptions.BLANK_KEEP, txtStripIndent = false),
            ParseOptions(txtBlankLines = ParseOptions.BLANK_COLLAPSE, txtJoinWrappedLines = 2),
            ParseOptions(txtBlankLines = ParseOptions.BLANK_REMOVE_ALL, txtEmphasizeHeadings = false),
            ParseOptions(txtDetectChapters = false, txtJoinWrappedLines = 0),
            ParseOptions(txtReplaceRules = "기사는 => 騎士는\n^제(\\d)화.*$ => 제$1화 바뀐 제목\n그러나.* =>"),
        )
        for ((ii, t) in inputs.withIndex()) for ((oi, o) in opts.withIndex()) {
            checkEquivalence(t.toByteArray(Charsets.UTF_8), o, "input $ii options $oi")
        }
    }

    /** Random line soup covering every pipeline feature. */
    private fun fuzzText(r: Random): String {
        val sb = StringBuilder()
        val n = 50 + r.nextInt(400)
        var chapter = 1
        repeat(n) {
            val line = when (r.nextInt(22)) {
                0, 1, 2 -> ""
                3 -> " \t　"
                4 -> "제${chapter++}화 ${TxtTestUtil.sentence(r).take(10)}"
                5 -> listOf("***", "* * *", "◇◇◇", "---", "§")[r.nextInt(5)]
                6 -> "  " + TxtTestUtil.paragraph(r, 30)
                7 -> "[광고] 방문하세요 ${r.nextInt(100)}"
                8 -> TxtTestUtil.paragraph(r, 200).chunked(33 + r.nextInt(10)).joinToString("\n")
                9 -> if (r.nextInt(8) == 0) TxtTestUtil.body(r, 9000 + r.nextInt(20000), " ") else TxtTestUtil.sentence(r)
                10 -> "\n\n\n"
                11 -> "외전 ${r.nextInt(5)}화"
                12 -> "제${chapter - 1}화"
                13 -> "탭\t과\u0001제어￼문자 "
                14 -> "#${r.nextInt(50)}"
                15 -> "1. 목록\n2. 목록\n3. 목록"
                else -> TxtTestUtil.paragraph(r, 20 + r.nextInt(300))
            }
            sb.append(line).append(if (r.nextInt(10) == 0) "\r\n" else "\n")
        }
        return sb.toString()
    }

    @Test
    fun equivalenceFuzz() {
        val r = Random(33)
        repeat(40) { k ->
            val t = fuzzText(r)
            val o = ParseOptions(
                txtBlankLines = r.nextInt(4),
                txtStripIndent = r.nextBoolean(),
                txtJoinWrappedLines = r.nextInt(3),
                txtDetectChapters = r.nextInt(5) != 0,
                txtEmphasizeHeadings = r.nextBoolean(),
                txtReplaceRules = if (r.nextBoolean()) "^\\[광고\\].*$ =>" else "",
            )
            val cs = when (r.nextInt(4)) {
                0 -> TxtTestUtil.CP949
                1 -> Charsets.UTF_16LE
                else -> Charsets.UTF_8
            }
            val bytes = if (cs == TxtTestUtil.CP949) t.replace("￼", "?").toByteArray(cs) else t.toByteArray(cs)
            checkEquivalence(bytes, o, "fuzz $k")
        }
    }

    @Test
    fun equivalenceWithOverlongLinesAcrossSectionBoundaries() {
        val r = Random(39)
        val giant = TxtTestUtil.body(r, 150_000, " ")
        val noNewline = TxtTestUtil.body(r, 120_000, " ")
        val mixed = "제1화 시작\n" + TxtTestUtil.body(r, 2000) + "\n" + giant + "\n제2화 다음\n" + TxtTestUtil.body(r, 70_000) +
            "\n\t" + TxtTestUtil.body(r, 30_000, "\t ") + "\n제3화 끝\n짧다."
        for ((name, t) in listOf("mixed" to mixed, "no-newline" to noNewline, "crlf" to mixed.replace("\n", "\r\n"))) {
            for (cs in listOf(Charsets.UTF_8, TxtTestUtil.CP949, Charsets.UTF_16BE)) {
                for (o in listOf(ParseOptions(), ParseOptions(txtStripIndent = false, txtJoinWrappedLines = 2, txtBlankLines = 3))) {
                    val p = TxtParser.parse(t.toByteArray(cs), t.toByteArray(cs).size, o)
                    assertTrue(name, (0 until p.sectionCount).any { p.flags[it] and TxtIndex.STARTS_CONT != 0 })
                    checkEquivalence(t.toByteArray(cs), o, "$name ${cs.name()} strip=${o.txtStripIndent}")
                }
            }
        }
    }

    @Test
    fun malformedBytesNeverCrash() {
        val r = Random(34)
        repeat(30) { k ->
            val b = ByteArray(r.nextInt(30000))
            r.nextBytes(b)
            // sprinkle newlines so there are lines
            for (q in b.indices) if (r.nextInt(40) == 0) b[q] = 0x0A
            val o = ParseOptions(txtEncoding = listOf("", "UTF-8", "MS949", "UTF-16LE", "UTF-16BE")[k % 5])
            checkEquivalence(b, o, "garbage $k")
        }
    }

    @Test
    fun equivalenceWithAuthorNotes() {
        // A5: recurring notes stay inside their episodes on the byte-range path as in the whole-file parse
        val r = Random(40)
        val sb = StringBuilder()
        for (k in 1..6) {
            sb.append("${k}화\n\n").append(TxtTestUtil.body(r, 2000, "\n\n")).append("\n\n")
            sb.append(if (k % 2 == 0) "[작가의 말]\n\n" else "작가의 말\n\n").append("감사합니다.\n\n")
        }
        sb.append("완결 후기\n\n").append(TxtTestUtil.body(r, 500, "\n\n")).append("\n")
        val bytes = sb.toString().toByteArray()
        checkEquivalence(bytes, ParseOptions(), "notes")
        val p = TxtParser.parse(bytes, bytes.size, ParseOptions())
        val toc = (0 until p.sectionCount).filter { p.flags[it] and TxtIndex.CHAPTER != 0 }.map { p.titles[it] }
        assertEquals((1..6).map { "${it}화" } + "완결 후기", toc)
    }

    @Test
    fun buildingIndexOnlyWhileParsingInFull() {
        val bytes = text(Random(41)).repeat(40).toByteArray()
        val f = TxtTestUtil.writeTemp(dir, "building.txt", bytes)
        val o = ParseOptions()
        TxtTestUtil.withCacheDir(cacheDir) {
            TxtIndexStore.fileFor(TxtIndexStore.key(f, o))!!.delete()
            assertFalse(TxtDocuments.isBuildingIndex(f.path))
            val pool = Executors.newSingleThreadExecutor()
            try {
                val open = pool.submit<Int> { TxtDocuments.open(f, o).use { it.sections.size } }
                var seen = false
                while (!open.isDone) {
                    if (!TxtDocuments.isBuildingIndex(f.path)) continue
                    seen = true
                    // the same file under another spelling of its path (unless the parse ended in between)
                    val other = TxtDocuments.isBuildingIndex(f.parent + "//" + f.name)
                    assertTrue("other spelling", other || !TxtDocuments.isBuildingIndex(f.path))
                    break
                }
                assertTrue(open.get(30, TimeUnit.SECONDS) > 1)
                assertTrue("seen while parsing", seen)
            } finally {
                pool.shutdown()
            }
            assertFalse(TxtDocuments.isBuildingIndex(f.path))
            assertFalse(TxtDocuments.isBuildingIndex(File(dir, "other.txt").path))
            // an indexed open never builds
            TxtDocuments.open(f, o).close()
            assertFalse(TxtDocuments.isBuildingIndex(f.path))
        }
        // a missing file is never marked
        val missing = File(dir, "missing.txt")
        try {
            TxtDocuments.open(missing, o)
        } catch (_: DocumentException) {
        }
        assertFalse(TxtDocuments.isBuildingIndex(missing.path))
    }

    @Test
    fun indexInvalidatedWhenFileChanges() {
        val f = TxtTestUtil.writeTemp(dir, "change.txt", "1화\n첫 번째 내용.\n".repeat(3).toByteArray())
        TxtTestUtil.withCacheDir(cacheDir) {
            val o = ParseOptions()
            TxtDocuments.open(f, o).use { assertTrue(it.loadSection(0).text.contains("첫 번째")) }
            f.writeText("완전히 다른 내용이다.\n")
            f.setLastModified(f.lastModified() + 5000)
            TxtDocuments.open(f, o).use { assertEquals("완전히 다른 내용이다.", it.loadSection(0).text) }
        }
    }

    @Test
    fun corruptIndexIsIgnored() {
        val bytes = text(Random(35)).toByteArray()
        val f = TxtTestUtil.writeTemp(dir, "corrupt.txt", bytes)
        TxtTestUtil.withCacheDir(cacheDir) {
            val o = ParseOptions()
            val first = TxtDocuments.open(f, o).use { b -> (0 until b.sections.size).map { b.loadSection(it).text } }
            val idx = TxtIndexStore.fileFor(TxtIndexStore.key(f, o))!!
            assertTrue(idx.isFile)
            idx.writeBytes(idx.readBytes().copyOf(idx.length().toInt() / 2))
            assertNull(TxtIndexStore.load(TxtIndexStore.key(f, o), f.length()))
            val again = TxtDocuments.open(f, o).use { b -> (0 until b.sections.size).map { b.loadSection(it).text } }
            assertEquals(first, again)
            idx.writeBytes(ByteArray(0))
            TxtDocuments.open(f, o).use { assertEquals(first.size, it.sections.size) }
        }
    }

    @Test
    fun noCacheDirStillWorks() {
        val bytes = text(Random(36)).toByteArray()
        val f = TxtTestUtil.writeTemp(dir, "nocache.txt", bytes)
        TxtTestUtil.withCacheDir(File("/proc/definitely/not/writable")) {
            TxtDocuments.open(f, ParseOptions()).use { assertTrue(it.sections.isNotEmpty()) }
        }
    }

    @Test
    fun bookApiAndConcurrentLoads() {
        val bytes = text(Random(37)).toByteArray(TxtTestUtil.CP949)
        val f = TxtTestUtil.writeTemp(dir, "api.txt", bytes)
        TxtTestUtil.withCacheDir(cacheDir) {
            val book = TxtDocuments.open(f, ParseOptions())
            assertEquals("api", book.meta.title)
            assertEquals("MS949", book.meta.encoding)
            assertEquals(6, book.toc.size)
            assertEquals((1..6).map { "제${it}화 테스트 장" }, book.toc.map { it.title })
            for (e in book.toc) {
                assertEquals(1, e.level)
                assertEquals(DocPosition(e.section, 0), book.resolveToc(e))
                assertEquals(e.title, book.sections[e.section].title)
            }
            assertNull(book.coverImage())
            assertNull(book.loadImage("x"))
            assertNull(book.resolveLink(0, "#a"))
            assertEquals(0, book.loadSection(-1).length)
            assertEquals(0, book.loadSection(999).length)
            val expected = (0 until book.sections.size).map { book.loadSection(it).text }
            val pool = Executors.newFixedThreadPool(6)
            val futures = (0 until 200).map { k ->
                pool.submit<Boolean> {
                    val i = (k * 7) % book.sections.size
                    book.loadSection(i).text == expected[i]
                }
            }
            assertTrue(futures.all { it.get() })
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
            book.close()
            // loads after close still work (temporary file handle)
            assertEquals(expected[1], book.loadSection(1).text)
        }
    }

    @Test
    fun missingFileThrowsDocumentException() {
        var thrown = false
        try {
            TxtDocuments.open(File(dir, "nope.txt"), ParseOptions())
        } catch (_: DocumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun readMetaAndPreview() {
        val t = "  제1화 시작\n\n첫 문단입니다.\n\n둘째 문단입니다.\n\n\n\n셋째 문단입니다.\n"
        val f = TxtTestUtil.writeTemp(dir, "제목 있는 책.txt", t.toByteArray(TxtTestUtil.CP949))
        val meta = TxtDocuments.readMeta(f)
        assertEquals("제목 있는 책", meta.title)
        assertEquals("MS949", meta.encoding)
        assertEquals("제1화 시작\n첫 문단입니다.\n둘째 문단입니다.\n\n셋째 문단입니다.", TxtDocuments.preview(f, 1500))
        assertEquals("제1화 시작\n첫", TxtDocuments.preview(f, 8))
        assertEquals("", TxtDocuments.preview(f, 0))
        assertEquals("", TxtDocuments.preview(File(dir, "missing.txt")))
        assertEquals("missing", TxtDocuments.readMeta(File(dir, "missing.txt")).title)
        // big file: only the head is read, the cut is at a line boundary
        val big = TxtTestUtil.body(Random(38), 200_000, "\n\n")
        val fb = TxtTestUtil.writeTemp(dir, "big.txt", big.toByteArray(Charsets.UTF_16LE).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it })
        val pv = TxtDocuments.preview(fb, 1200)
        assertTrue(pv.length <= 1200)
        assertTrue(big.replace("\n\n", "\n").startsWith(pv))
        assertEquals("UTF-16LE", TxtDocuments.readMeta(fb).encoding)
        // forced encoding
        assertTrue(TxtDocuments.preview(f, 100, "UTF-8").isNotEmpty())
    }
}
