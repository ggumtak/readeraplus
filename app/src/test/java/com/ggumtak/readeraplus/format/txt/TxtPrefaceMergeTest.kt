package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.ParseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** A short text before the first chapter is merged into the first chapter's section (no nearly empty first page). */
class TxtPrefaceMergeTest {
    private val dir = TxtTestUtil.tempDir()
    private val cacheDir = File(dir, "cache")
    private var fileNo = 0

    private fun book(preface: String, seed: Int = 1, headings: List<String> = listOf("프롤로그", "제1화 시작", "제2화 끝")): String {
        val r = Random(seed)
        val sb = StringBuilder(preface)
        for (h in headings) {
            sb.append(h).append("\n\n")
            sb.append(TxtTestUtil.body(r, 1500, "\n\n")).append("\n\n")
        }
        return sb.toString()
    }

    private fun chapterTitles(p: TxtParser.Parsed): List<String?> =
        (0 until p.sectionCount).filter { p.flags[it] and TxtIndex.CHAPTER != 0 }.map { p.titles[it] }

    @Test
    fun shortPrefaceStartsTheFirstChapterSection() {
        val p = TxtTestUtil.parse(book("리더플러스 샘플 소설\n\n"))
        assertEquals(3, p.sectionCount)
        assertEquals(listOf("프롤로그", "제1화 시작", "제2화 끝"), chapterTitles(p))
        assertEquals("프롤로그", p.titles[0])
        assertTrue(p.flags[0] and TxtIndex.CHAPTER != 0)
        assertEquals(2, p.headLine[0]) // title, blank, heading
        val c = p.buildSection(0, true)
        TxtTestUtil.assertInvariants(c, "merged")
        assertEquals(p.chars[0], c.text.length)
        // the first page shows the book title followed by the chapter heading
        val paras = c.blocks.map { c.text.substring(it.start, it.end) }
        assertEquals("리더플러스 샘플 소설", paras[0])
        val hb = c.blocks.indexOfFirst { (it as ParagraphBlock).style.headingLevel == 2 }
        assertTrue(hb > 0)
        assertEquals("프롤로그", paras[hb])
        assertEquals(c.blocks[hb].start, p.headChar[0])
        assertEquals(1, c.styleRuns.size)
        assertEquals(p.headChar[0], c.styleRuns[0].start)
        // later chapters are unchanged: heading first, offset 0
        for (s in 1 until p.sectionCount) {
            assertEquals(0, p.headLine[s])
            assertEquals(0, p.headChar[s])
            val cs = p.buildSection(s, true)
            assertEquals(p.titles[s], cs.text.substring(cs.blocks[0].start, cs.blocks[0].end))
        }
    }

    @Test
    fun longPrefaceStaysItsOwnSection() {
        val long = TxtTestUtil.body(Random(9), TxtParser.PREFACE_MERGE_CHARS + 50, "\n\n")
        val p = TxtTestUtil.parse(book("$long\n\n"))
        assertEquals(4, p.sectionCount)
        assertNull(p.titles[0])
        assertEquals(0, p.flags[0] and TxtIndex.CHAPTER)
        assertEquals(long.replace("\n\n", "\n"), p.buildSection(0, true).text)
        for (s in 0 until p.sectionCount) {
            assertEquals(0, p.headLine[s])
            assertEquals(0, p.headChar[s])
        }
    }

    @Test
    fun blankOnlyPrefaceIsDropped() {
        val p = TxtTestUtil.parse(book("\n\n  \n\n"))
        assertEquals(3, p.sectionCount)
        assertEquals("프롤로그", p.titles[0])
        assertEquals(0, p.headLine[0])
        assertEquals(0, p.headChar[0])
        assertTrue(p.buildSection(0, true).text.startsWith("프롤로그\n"))
    }

    @Test
    fun blankHeavyPrefaceIsMeasuredWithItsEmptyParagraphs() {
        val t = book("책 제목\n" + "\n".repeat(600))
        // collapsed blank run: a short preface, merged
        assertEquals("프롤로그", TxtTestUtil.parse(t).titles[0])
        // kept blank lines take > PREFACE_MERGE_CHARS in the section: own section
        val keep = ParseOptions(txtBlankLines = ParseOptions.BLANK_KEEP)
        val p = TxtTestUtil.parse(t, keep)
        assertNull(p.titles[0])
        assertEquals("프롤로그", p.titles[1])
        TxtTestUtil.assertIndexEquivalent(dir, cacheDir, "blanks.txt", t.toByteArray(), keep, "keep blanks")
        TxtTestUtil.assertIndexEquivalent(dir, cacheDir, "blanks2.txt", t.toByteArray(), ParseOptions(), "collapse blanks")
    }

    @Test
    fun tocOffsetPointsAtTheHeading() {
        val bytes = book("리더플러스 샘플 소설\n지은이 미상\n\n").toByteArray()
        val f = TxtTestUtil.writeTemp(dir, "toc.txt", bytes)
        val o = ParseOptions()
        TxtTestUtil.withCacheDir(cacheDir) {
            TxtIndexStore.fileFor(TxtIndexStore.key(f, o))!!.delete()
            for (pass in 0..1) { // full parse, then from the saved index
                TxtDocuments.open(f, o).use { b ->
                    assertEquals(listOf("프롤로그", "제1화 시작", "제2화 끝"), b.toc.map { it.title })
                    assertEquals(listOf(0, 1, 2), b.toc.map { it.section })
                    assertEquals("프롤로그", b.sections[0].title)
                    val first = b.toc[0]
                    val text = b.loadSection(0).text
                    assertTrue(text.startsWith("리더플러스 샘플 소설\n지은이 미상\n"))
                    assertTrue(first.offset > 0)
                    assertEquals("프롤로그", text.substring(first.offset, first.offset + "프롤로그".length))
                    assertEquals(DocPosition(0, first.offset), b.resolveToc(first))
                    for (e in b.toc.drop(1)) assertEquals(0, e.offset)
                }
            }
        }
    }

    @Test
    fun byteRangeLoadMatchesFullParse() {
        val r = Random(41)
        val prefaces = listOf(
            "리더플러스 샘플 소설\n\n",
            "리더플러스 샘플 소설\n",
            "\n\n  리더플러스 샘플 소설\n\n\n지은이\n\n\n",
            "목차\n\n프롤로그\n제1화 시작\n제2화 끝\n\n",
            "* * *\n\n작은 제목\n\n",
            "[광고] 방문하세요\n책 제목\n\n",
        )
        val opts = listOf(
            ParseOptions(),
            ParseOptions(txtBlankLines = ParseOptions.BLANK_KEEP, txtStripIndent = false),
            ParseOptions(txtBlankLines = ParseOptions.BLANK_COLLAPSE, txtJoinWrappedLines = 2),
            ParseOptions(txtBlankLines = ParseOptions.BLANK_REMOVE_ALL, txtEmphasizeHeadings = false),
            ParseOptions(txtReplaceRules = "^\\[광고\\].* =>"),
        )
        val bom16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        for ((pi, pre) in prefaces.withIndex()) {
            val t = book(pre, seed = pi)
            for ((oi, o) in opts.withIndex()) {
                val where = "preface $pi options $oi"
                TxtTestUtil.assertIndexEquivalent(dir, cacheDir, "eq${fileNo++}.txt", t.toByteArray(), o, where)
                val p = TxtTestUtil.parse(t, o)
                assertEquals("$where merged", "프롤로그", p.titles[0])
            }
            TxtTestUtil.assertIndexEquivalent(dir, cacheDir, "eq${fileNo++}.txt", t.toByteArray(TxtTestUtil.CP949), ParseOptions(), "cp949 $pi")
            TxtTestUtil.assertIndexEquivalent(dir, cacheDir, "eq${fileNo++}.txt", bom16le + t.replace("\n", "\r\n").toByteArray(Charsets.UTF_16LE), ParseOptions(), "utf16 crlf $pi")
        }
        // preface merged into a first chapter that is long enough to be split into chunks
        val longFirst = "리더플러스 샘플 소설\n\n프롤로그\n\n" + TxtTestUtil.body(r, 90_000, "\n\n") + "\n\n제1화 시작\n\n" +
            TxtTestUtil.body(r, 2000, "\n\n") + "\n\n제2화 끝\n\n" + TxtTestUtil.body(r, 2000, "\n\n")
        val p = TxtTestUtil.parse(longFirst)
        assertEquals("프롤로그", p.titles[0])
        assertNull(p.titles[1])
        assertEquals(0, p.flags[1] and TxtIndex.CHAPTER)
        assertTrue(p.headChar[0] > 0)
        TxtTestUtil.assertIndexEquivalent(dir, cacheDir, "long.txt", longFirst.toByteArray(), ParseOptions(), "long first chapter")
    }

    @Test
    fun indexRoundTripKeepsHeadingPosition() {
        val p = TxtTestUtil.parse(book("리더플러스 샘플 소설\n\n"))
        val idx = p.toIndex("k")
        val back = TxtIndexStore.decode(TxtIndexStore.encode(idx), "k", Long.MAX_VALUE)!!
        assertEquals(idx.headLine.toList(), back.headLine.toList())
        assertEquals(idx.headChar.toList(), back.headChar.toList())
        assertTrue(back.headChar[0] > 0)
        // a heading offset past the section text is rejected
        val bad = TxtIndex(
            "k", idx.encoding, idx.newline, idx.decisions, idx.byteStart, idx.byteEnd, idx.flags, idx.chars, idx.titles,
            idx.headLine, IntArray(idx.size) { if (it == 0) idx.chars[0] + 1 else 0 },
        )
        assertNull(TxtIndexStore.decode(TxtIndexStore.encode(bad), "k", Long.MAX_VALUE))
    }
}
