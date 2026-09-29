package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.format.ParseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Regression tests for defects found in review (each test names the defect it pins down). */
class TxtReviewRegressionTest {
    private val dir = TxtTestUtil.tempDir()
    private val cacheDir = File(dir, "cache")
    private var fileNo = 0

    private fun equivalent(bytes: ByteArray, o: ParseOptions, where: String) =
        TxtTestUtil.assertIndexEquivalent(dir, cacheDir, "r${fileNo++}.txt", bytes, o, where)

    private val noCh = ParseOptions(txtDetectChapters = false)

    // ------------------------------------------------------------ CRLF + overlong line

    /** A line of exactly MAX_SEGMENT_CHARS chars + CRLF used to leave a CR-only segment: a stray empty paragraph. */
    @Test
    fun crlfOverlongLineLeavesNoStrayEmptyParagraph() {
        val giant = "가".repeat(MAX_SEGMENT_CHARS)
        val text = "첫 줄.\r\n$giant\r\n끝 줄.\r\n"
        for (mode in 0..3) {
            val o = noCh.copy(txtBlankLines = mode)
            assertEquals("mode $mode", listOf("첫 줄.", giant, "끝 줄."), TxtTestUtil.paragraphs(text, o))
        }
        // one char longer: a real split, still no empty paragraph
        val longer = "가".repeat(MAX_SEGMENT_CHARS + 1)
        val keep = noCh.copy(txtBlankLines = ParseOptions.BLANK_KEEP)
        val paras = TxtTestUtil.paragraphs("첫 줄.\r\n$longer\r\n끝 줄.", keep)
        assertEquals(4, paras.size)
        assertTrue(paras.none { it.isEmpty() })
        equivalent(text.toByteArray(TxtTestUtil.CP949), keep, "crlf exact")
        equivalent(text.toByteArray(Charsets.UTF_16LE), keep, "crlf exact utf16")
    }

    // ------------------------------------------------------------ runaway hard-wrap joining

    /** Hard-wrapped text without any paragraph end became ONE paragraph, hence one unsplittable huge section. */
    @Test
    fun hardWrappedTextWithoutParagraphEndsIsStillSectioned() {
        val r = Random(41)
        val words = TxtTestUtil.body(r, 400_000, " ").filter { it in '가'..'힣' }
        val text = words.chunked(35).joinToString("\n")
        val p = TxtTestUtil.parse(text, noCh)
        assertTrue("joining active", p.decisions.joinMinWidth > 0)
        assertTrue("sections ${p.sectionCount}", p.sectionCount >= 8)
        for (i in 0 until p.sectionCount) {
            assertTrue("section $i = ${p.chars[i]}", p.chars[i] <= TxtParser.TARGET_CHARS * 3 / 2 + TxtParagraphs.JOIN_MAX_CHARS)
            val c = p.buildSection(i, true)
            for (b in c.blocks) assertTrue(b.end - b.start <= TxtParagraphs.JOIN_MAX_CHARS + 40)
        }
        // nothing lost: all characters survive (joined without separators? no: Hangul joins with one space)
        val all = TxtTestUtil.sections(p, noCh).joinToString("") { it.text }.filter { it in '가'..'힣' }
        assertEquals(words, all)
        equivalent(words.take(120_000).chunked(35).joinToString("\n").toByteArray(), noCh, "wrap-no-ends")
    }

    /** With explicit paragraph ends (blank lines) a long joined paragraph prefers to stop after a sentence end. */
    @Test
    fun longJoinedParagraphStopsAtSentenceEndFirst() {
        val r = Random(42)
        val sources = List(8) { List(400) { TxtTestUtil.sentence(r).replace("“", "").replace("”", "") }.joinToString(" ") }
        // hard-wrapped at 70 columns, a blank line after each paragraph
        val text = sources.joinToString("\n\n") { wrapCols(it, 70).joinToString("\n") }
        val p = TxtTestUtil.parse(text, noCh)
        assertTrue("paragraph ends are explicit", p.decisions.joinIgnoreTerminal)
        val out = TxtTestUtil.paragraphs(text, noCh)
        assertTrue("split ${out.size}", out.size > sources.size)
        for (q in out) assertTrue(q.length <= TxtParagraphs.JOIN_MAX_CHARS + 80)
        assertTrue(out.all { TxtChars.isTerminal(it.last()) })
        val hangul = { x: String -> x.filter { it in '가'..'힣' } }
        assertEquals(hangul(sources.joinToString("")), hangul(out.joinToString("")))
    }

    private fun wrapCols(text: String, cols: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var w = 0
        for (word in text.split(' ')) {
            val ww = word.sumOf { if (TxtChars.isWide(it)) 2 else 1 as Int }
            if (w > 0 && w + 1 + ww > cols) {
                out.add(sb.toString()); sb.setLength(0); w = 0
            }
            if (w > 0) { sb.append(' '); w++ }
            sb.append(word)
            w += ww
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    // ------------------------------------------------------------ chapter titles

    /** Quoted chapter titles ending in ?” / !” and "제N화." were rejected as sentences. */
    @Test
    fun quotedAndPeriodTitlesAreHeadings() {
        val hs = listOf("제1화 “이건 말도 안 돼!”", "제2화 “누구세요?”", "제3화 \"끝났다.\"", "제4화.", "5화 ‘괜찮아?’", "제6화 평범한 제목")
        val r = Random(43)
        val text = hs.joinToString("\n\n") { it + "\n\n" + TxtTestUtil.body(r, 2000, "\n\n") }
        val p = TxtTestUtil.parse(text)
        val titles = (0 until p.sectionCount).filter { p.flags[it] and TxtIndex.CHAPTER != 0 }.map { p.titles[it] }
        assertEquals(hs, titles)
        // dialogue tails and sentences are still rejected
        for (s in listOf("1화 봤어?”", "끝이야.\"", "3화 보고 잤어요.", "외전 나왔대?’")) {
            assertTrue(s, TxtChapters.rejectEnding(s))
        }
        assertTrue(TxtChapters.rejectEnding("3화 보고 잤어요."))
        assertFalse(TxtChapters.rejectEnding("제4화."))
        assertFalse(TxtChapters.rejectEnding("제2화 “누구세요?”"))
    }

    // ------------------------------------------------------------ replace rules

    /** An invalid replacement threw (and was caught) on every matching line: slow on ART. Now skipped up front. */
    @Test
    fun invalidReplacementsAreRejectedAtParse() {
        val v = ReplaceRules.Companion
        assertTrue(v.validReplacement("", 0, "a"))
        assertTrue(v.validReplacement("x\\\$y", 0, "a"))
        assertTrue(v.validReplacement("\$0 \$1", 1, "(a)"))
        assertTrue(v.validReplacement("\${name}!", 1, "(?<name>a)"))
        assertFalse(v.validReplacement("\$", 0, "a"))
        assertFalse(v.validReplacement("\$1", 0, "a"))
        assertFalse(v.validReplacement("\$x", 3, "(a)(b)(c)"))
        assertFalse(v.validReplacement("tail\\", 0, "a"))
        assertFalse(v.validReplacement("\${nope}", 1, "(?<name>a)"))
        assertFalse(v.validReplacement("\${}", 1, "(?<name>a)"))
        val rules = ReplaceRules.parse("다 => \$\n가 => \\\n(b) => \$2\n(c) => [\$1]\n(?<k>d) => <\${k}>\n좋은 => 멋진")!!
        assertEquals(3, rules.size)
        assertEquals("[c] <d> 멋진 다 가 b", rules.applier().apply("c d 좋은 다 가 b"))
    }

    /** The regex engine failing on a line (stack overflow) must leave the line unchanged, not abort the parse. */
    @Test
    fun replaceRuleEngineFailureLeavesLineUnchanged() {
        val rules = ReplaceRules.parse("(a|b)*c => X\n좋은 => 멋진")!!
        val line = "a".repeat(200_000) + " 좋은"
        var result: String? = null
        var error: Throwable? = null
        val t = Thread(null, {
            try {
                result = rules.applier().apply(line)
            } catch (e: Throwable) {
                error = e
            }
        }, "small-stack", 128 * 1024)
        t.isDaemon = true
        t.start()
        t.join(20_000)
        assertFalse("finished", t.isAlive)
        assertNull(error)
        assertEquals("a".repeat(200_000) + " 멋진", result)
    }

    // ------------------------------------------------------------ encoding detection

    /** A UTF-8 book starting with a short CP949 header was decoded entirely as CP949. */
    @Test
    fun utf8WithShortCp949HeaderStaysUtf8() {
        val r = Random(44)
        val header = "텍스트 파일 변환기로 만든 문서입니다 원본 사이트 안내문\n".toByteArray(TxtTestUtil.CP949)
        val body = TxtTestUtil.body(r, 20_000).toByteArray(Charsets.UTF_8)
        val b = header + body
        val p = TxtTestUtil.parseBytes(b, noCh)
        assertEquals("UTF-8", p.decoder.name)
        assertEquals("UTF-8", TxtCharsets.sniffName(b, 0, b.size))
        // plain CP949 (and mostly-ASCII CP949) is still CP949
        val cp = TxtTestUtil.body(r, 5_000).toByteArray(TxtTestUtil.CP949)
        assertEquals("MS949", TxtTestUtil.parseBytes(cp, noCh).decoder.name)
        val ascii = ("Chapter one. " + "plain words ".repeat(3000) + "끝 한마디.\n").toByteArray(TxtTestUtil.CP949)
        assertEquals("MS949", TxtTestUtil.parseBytes(ascii, noCh).decoder.name)
    }

    // ------------------------------------------------------------ newlines and control characters

    /** One stray LF in a CR-only file made the whole book LF-delimited: a few giant lines, no paragraphs. */
    @Test
    fun crFileWithStrayLfKeepsCrLines() {
        val r = Random(45)
        val ps = List(60) { TxtTestUtil.paragraph(r, 60) }
        val text = ps.subList(0, 30).joinToString("\r") + "\n" + ps.subList(30, 60).joinToString("\r")
        val out = TxtTestUtil.paragraphs(text, noCh.copy(txtJoinWrappedLines = 0))
        assertEquals(ps.subList(0, 29) + (ps[29] + " " + ps[30]).let { listOf(it) } + ps.subList(31, 60), out)
        equivalent(text.toByteArray(TxtTestUtil.CP949), noCh, "cr stray lf")
        equivalent(text.toByteArray(Charsets.UTF_16BE), noCh, "cr stray lf utf16")
        // CRLF files are unaffected
        val crlf = ps.joinToString("\r\n")
        assertEquals(ps, TxtTestUtil.paragraphs(crlf, noCh.copy(txtJoinWrappedLines = 0)))
    }

    /** Line separators inside a line glued words together (lone CR) or reached the renderer (NEL, U+2028, C1). */
    @Test
    fun strayLineSeparatorsBecomeSpacesAndC1ControlsAreDropped() {
        val text = "하나\r둘\n셋 넷 다섯\u0085여섯\u0090일곱\n"
        assertEquals(listOf("하나 둘", "셋 넷 다섯 여섯일곱"), TxtTestUtil.paragraphs(text, noCh.copy(txtJoinWrappedLines = 0)))
    }

    // ------------------------------------------------------------ index

    @Test
    fun indexWithoutSectionsIsRejected() {
        val empty = TxtIndex("k", "UTF-8", 0x0A, TxtDecisions(1, 2, 0, false, false), IntArray(0), IntArray(0), IntArray(0), IntArray(0), arrayOf())
        assertNull(TxtIndexStore.decode(TxtIndexStore.encode(empty), "k", 100))
        val one = TxtIndex("k", "UTF-8", 0x0A, TxtDecisions(1, 2, 0, false, false), intArrayOf(0), intArrayOf(10), intArrayOf(0), intArrayOf(9), arrayOf(null))
        assertEquals(1, TxtIndexStore.decode(TxtIndexStore.encode(one), "k", 100)!!.size)
    }

    // ------------------------------------------------------------ equivalence fuzz over the new code paths

    @Test
    fun equivalenceFuzzReviewCases() {
        val r = Random(46)
        repeat(24) { k ->
            val sb = StringBuilder()
            val nl = listOf("\n", "\r\n", "\r")[k % 3]
            repeat(150 + r.nextInt(250)) {
                val line = when (r.nextInt(14)) {
                    0, 1 -> ""
                    2 -> "제${it}화 “제목${it}?”"
                    3 -> TxtTestUtil.body(r, 9000 + r.nextInt(12000), " ")
                    4 -> "* * *"
                    5 -> "  " + TxtTestUtil.paragraph(r, 40)
                    6 -> "😀가".repeat(r.nextInt(6000))
                    7 -> "가".repeat(MAX_SEGMENT_CHARS - 1 + r.nextInt(3))
                    8 -> TxtTestUtil.body(r, 5000, " ").filter { c -> c in '가'..'힣' }.chunked(30).joinToString(nl)
                    9 -> "줄 안\u0085의\u0090구분"
                    else -> TxtTestUtil.paragraph(r, 20 + r.nextInt(200))
                }
                sb.append(line).append(nl)
                if (k % 3 == 2 && r.nextInt(40) == 0) sb.append('\n') // stray LF in a CR file
            }
            val cs = listOf(Charsets.UTF_8, Charsets.UTF_16LE, TxtTestUtil.CP949, Charsets.UTF_16BE)[k % 4]
            val t = if (cs == TxtTestUtil.CP949) sb.toString().replace("😀", "?").replace(" ", " ").replace("\u0085", " ").replace("\u0090", " ") else sb.toString()
            val o = ParseOptions(
                txtBlankLines = r.nextInt(4),
                txtStripIndent = r.nextBoolean(),
                txtJoinWrappedLines = r.nextInt(3),
                txtDetectChapters = r.nextInt(4) != 0,
                txtReplaceRules = if (r.nextBoolean()) "제(\\d+)화 => 第$1話\n가가 => \$" else "",
            )
            equivalent(t.toByteArray(cs), o, "review fuzz $k ${cs.name()}")
        }
    }
}
