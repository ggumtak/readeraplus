package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.format.ParseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TxtFormatTest {
    private val noCh = ParseOptions(txtDetectChapters = false, txtJoinWrappedLines = 0)

    private fun paras(text: String, o: ParseOptions = noCh) = TxtTestUtil.paragraphs(text, o)

    // ------------------------------------------------------------ blank lines

    @Test
    fun autoRemovesSingleBlanksInAlternatingFile() {
        val text = "첫째 문단이다.\n\n둘째 문단이다.\n\n셋째 문단이다.\n\n\n\n장면이 바뀐 뒤의 문단.\n\n마지막 문단."
        assertEquals(listOf("첫째 문단이다.", "둘째 문단이다.", "셋째 문단이다.", "", "장면이 바뀐 뒤의 문단.", "마지막 문단."), paras(text))
    }

    @Test
    fun autoKeepsRareBlankLines() {
        val text = "하나.\n둘.\n셋.\n넷.\n\n다섯.\n여섯.\n\n\n일곱.\n여덟."
        // blank lines are rare: a single blank is kept as one empty paragraph, a run of 2 becomes one as well
        assertEquals(listOf("하나.", "둘.", "셋.", "넷.", "", "다섯.", "여섯.", "", "일곱.", "여덟."), paras(text))
    }

    @Test
    fun explicitBlankModes() {
        val text = "가.\n\n나.\n다.\n\n\n\n라."
        assertEquals(listOf("가.", "나.", "다.", "", "라."), paras(text, noCh.copy(txtBlankLines = ParseOptions.BLANK_REMOVE_ALL)))
        assertEquals(listOf("가.", "", "나.", "다.", "", "라."), paras(text, noCh.copy(txtBlankLines = ParseOptions.BLANK_COLLAPSE)))
        assertEquals(listOf("가.", "", "나.", "다.", "", "", "", "라."), paras(text, noCh.copy(txtBlankLines = ParseOptions.BLANK_KEEP)))
    }

    @Test
    fun leadingAndTrailingBlankLinesDropped() {
        val text = "\n\n\n   \n본문 시작.\n\n\n\n"
        for (mode in 0..3) {
            assertEquals("mode $mode", listOf("본문 시작."), paras(text, noCh.copy(txtBlankLines = mode)))
        }
    }

    @Test
    fun whitespaceOnlyLinesAreBlank() {
        val text = "가.\n \t 　\n나.\n \n다.\n \n라."
        assertEquals(listOf("가.", "나.", "다.", "라."), paras(text))
    }

    // ------------------------------------------------------------ scene breaks

    @Test
    fun sceneMarkersBecomeCenteredParagraphs() {
        val markers = listOf("***", "* * *", "＊＊＊", "---", "===", "◇◇◇", "◆◆◆", "ㅡㅡㅡ", "~~~", "ooo", "○○○", "§", "-◇-")
        for (m in markers) {
            val text = "앞 문단.\n\n$m\n\n뒤 문단.\n\n또 다른 문단.\n\n끝."
            val p = TxtTestUtil.parse(text, noCh)
            val s = TxtTestUtil.sections(p, noCh)[0]
            val texts = s.blocks.map { s.text.substring(it.start, it.end) }
            assertEquals(m, listOf("앞 문단.", m, "뒤 문단.", "또 다른 문단.", "끝."), texts)
            val style = (s.blocks[1] as ParagraphBlock).style
            assertEquals(Align.CENTER, style.align)
            assertFalse(style.indent)
        }
        // not markers
        for (notMarker in listOf("……", "...", "ㅡㅡ;", "--그래서", "oh")) {
            val s = TxtTestUtil.sections(TxtTestUtil.parse("가.\n$notMarker\n나.", noCh), noCh)[0]
            assertTrue(s.blocks.all { (it as ParagraphBlock).style.align == Align.DEFAULT })
        }
    }

    @Test
    fun sceneMarkerAbsorbsAdjacentBlankRunsExceptKeep() {
        val text = "가.\n나.\n\n\n\n***\n\n\n다.\n라."
        assertEquals(listOf("가.", "나.", "***", "다.", "라."), paras(text))
        assertEquals(
            listOf("가.", "나.", "", "", "", "***", "", "", "다.", "라."),
            paras(text, noCh.copy(txtBlankLines = ParseOptions.BLANK_KEEP)),
        )
    }

    // ------------------------------------------------------------ indentation / cleanup

    @Test
    fun stripIndentAndWhitespaceCleanup() {
        val text = "  　첫 줄은 들여쓰기.\t\t\n\t둘째 줄에는\t탭이 있다.   \n  셋째 줄.\r\n넷째\u0007 줄￼.﻿"
        assertEquals(listOf("첫 줄은 들여쓰기.", "둘째 줄에는 탭이 있다.", "셋째 줄.", "넷째 줄."), paras(text))
        val kept = paras(text, noCh.copy(txtStripIndent = false))
        assertEquals(listOf("  　첫 줄은 들여쓰기.", " 둘째 줄에는 탭이 있다.", "  셋째 줄.", "넷째 줄."), kept)
    }

    @Test
    fun crlfAndClassicMacLineEndings() {
        assertEquals(listOf("가.", "나.", "", "다."), paras("가.\r\n나.\r\n\r\n\r\n다.\r\n"))
        assertEquals(listOf("가.", "나.", "", "다."), paras("가.\r나.\r\r\r다.\r"))
    }

    // ------------------------------------------------------------ replace rules

    @Test
    fun replaceRules() {
        val rules = """
            # 광고 줄 제거
            ^\[광고\].*$ =>
            (\d+)원 => ${'$'}1 원
            [invalid( => 무시됨
            잘못된 그룹 => ${'$'}9
            즐거운 => 기쁜
        """.trimIndent()
        val o = noCh.copy(txtReplaceRules = rules)
        val text = "가격은 300원이다.\n\n[광고] 지금 접속하세요\n\n즐거운 하루였다.\n\n잘못된 그룹 문장."
        // the ad line is deleted without leaving a scene break behind; the invalid rules are ignored
        assertEquals(listOf("가격은 300 원이다.", "기쁜 하루였다.", "잘못된 그룹 문장."), paras(text, o))
    }

    @Test
    fun replaceRulesParse() {
        assertEquals(null, ReplaceRules.parse(""))
        assertEquals(null, ReplaceRules.parse("# only a comment\nno arrow here"))
        assertEquals(2, ReplaceRules.parse("a => b\nc=>d\n(=> x")!!.size)
    }

    // ------------------------------------------------------------ hard-wrap joining

    /** Word-wraps [text] at [cols] display columns (Hangul = 2). */
    private fun wrap(text: String, cols: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var w = 0
        for (word in text.split(' ')) {
            val ww = word.sumOf { if (TxtChars.isWide(it)) 2 else 1 as Int }
            if (w > 0 && w + 1 + ww > cols) {
                out.add(sb.toString())
                sb.setLength(0)
                w = 0
            }
            if (w > 0) { sb.append(' '); w++ }
            sb.append(word)
            w += ww
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun wrappedFile(r: Random, paragraphs: Int, cols: Int, sep: String): Pair<String, List<String>> {
        val originals = List(paragraphs) { TxtTestUtil.paragraph(r, 150 + r.nextInt(250)) }
        val text = originals.joinToString(sep) { wrap(it, cols).joinToString("\n") }
        return text to originals
    }

    @Test
    fun autoJoinsHardWrappedKoreanText() {
        // blank lines between paragraphs (the common hard-wrapped layout): exact recovery, blanks removed
        val (text, originals) = wrappedFile(Random(11), 40, 70, "\n\n")
        val o = ParseOptions(txtDetectChapters = false, txtJoinWrappedLines = 1)
        assertEquals(originals, paras(text, o))
        // off: every line stays a paragraph
        assertTrue(paras(text, o.copy(txtJoinWrappedLines = 0)).size > originals.size * 2)
    }

    @Test
    fun autoJoinWithoutParagraphMarkersNeverMergesParagraphs() {
        // no blank lines / indents: a wrapped line ending at a sentence end is ambiguous and stays a break,
        // but two different source paragraphs are never merged
        val (text, originals) = wrappedFile(Random(15), 40, 70, "\n")
        val out = paras(text, ParseOptions(txtDetectChapters = false, txtJoinWrappedLines = 1))
        val lines = text.split('\n').size
        assertTrue("joined: ${out.size} < $lines / 2", out.size < lines / 2)
        var o = 0
        for (p in out) {
            while (o < originals.size && !originals[o].contains(p)) o++
            assertTrue("paragraph comes from one source paragraph: $p", o < originals.size)
        }
    }

    @Test
    fun joinStopsAtBlankLinesScenesAndIndents() {
        val r = Random(13)
        val (a, origA) = wrappedFile(r, 12, 60, "\n\n")
        val (b, origB) = wrappedFile(r, 12, 60, "\n\n")
        val text = "$a\n\n* * *\n\n$b"
        val o = ParseOptions(txtDetectChapters = false, txtJoinWrappedLines = 1)
        assertEquals(origA + listOf("* * *") + origB, paras(text, o))
        // indented paragraph starts (no blank lines) also mark paragraphs
        val indented = origA.joinToString("\n") { p -> wrap(p, 60).mapIndexed { k, l -> if (k == 0) "  $l" else l }.joinToString("\n") }
        assertEquals(origA, paras(indented, o))
    }

    @Test
    fun doubleSpacedFileUsesLongerRunsForSceneBreaks() {
        val r = Random(16)
        val ps = List(30) { TxtTestUtil.paragraph(r, 80) }
        val text = ps.subList(0, 15).joinToString("\n\n\n") + "\n\n\n\n\n" + ps.subList(15, 30).joinToString("\n\n\n")
        assertEquals(ps.subList(0, 15) + listOf("") + ps.subList(15, 30), paras(text))
    }

    @Test
    fun alwaysJoinAndCjkJoinWithoutSpace() {
        val cjk = "これは長い日本語の文章です今日はとても良い天気で空が青い\n続きの行もここにありますそして最後に句点で終わる。"
        val o = ParseOptions(txtDetectChapters = false, txtJoinWrappedLines = 2)
        assertEquals(listOf("これは長い日本語の文章です今日はとても良い天気で空が青い続きの行もここにありますそして最後に句点で終わる。"), paras(cjk, o))
        val latin = "This is a hard wrapped line of text that goes on\nand continues here until it ends."
        assertEquals(listOf("This is a hard wrapped line of text that goes on and continues here until it ends."), paras(latin, o))
    }

    // ------------------------------------------------------------ overlong lines

    @Test
    fun overlongLineIsSegmented() {
        val r = Random(14)
        val giant = TxtTestUtil.body(r, 40_000, sep = " ")
        val text = "짧은 첫 줄.\n$giant\n짧은 끝 줄."
        val p = TxtTestUtil.parse(text, noCh)
        val all = TxtTestUtil.sections(p, noCh)
        val blocks = all.flatMap { s -> s.blocks.map { s.text.substring(it.start, it.end) to (it as ParagraphBlock).style } }
        assertEquals("짧은 첫 줄.", blocks.first().first)
        assertEquals("짧은 끝 줄.", blocks.last().first)
        val segs = blocks.subList(1, blocks.size - 1)
        assertTrue(segs.size >= 5)
        assertTrue(segs.all { it.first.length <= MAX_SEGMENT_CHARS })
        assertFalse(segs[0].second.softBreak)
        assertTrue(segs.drop(1).all { it.second.softBreak && !it.second.indent })
        // segments rejoin to the original line (split after spaces, which are trimmed)
        assertEquals(giant.replace(" ", ""), segs.joinToString("") { it.first }.replace(" ", ""))
    }
}
