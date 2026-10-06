package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.format.ParseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TxtChaptersTest {
    private fun titles(p: TxtParser.Parsed): List<String> =
        (0 until p.sectionCount).filter { p.flags[it] and TxtIndex.CHAPTER != 0 }.map { p.titles[it]!! }

    /** A file whose chapters start with [headings], each followed by ~1500 chars of body. */
    private fun book(headings: List<String>, seed: Int = 1, preface: String = "", blank: Boolean = true): String {
        val r = Random(seed)
        val sep = if (blank) "\n\n" else "\n"
        val sb = StringBuilder()
        if (preface.isNotEmpty()) sb.append(preface).append(sep)
        for (h in headings) {
            sb.append(h).append(sep)
            sb.append(TxtTestUtil.body(r, 1500, sep)).append(sep)
        }
        return sb.toString()
    }

    @Test
    fun builtinRulesPositives() {
        val positives = listOf(
            "제1화", "제 12 화 새로운 시작", "001화", "12화. 제목", "12화 - 제목", "< 1화 >", "<1화>", "〈1화〉",
            "[1화]", "【1화】", "『1화』", "제1장 귀환", "제1부", "2권", "#1", "#12. 제목", "EP.3", "Ep 3",
            "Episode 1", "Chapter 7", "CHAPTER 12 The End", "프롤로그", "에필로그", "서장", "종장", "외전 1화",
            "특별 외전", "번외", "후일담", "작가의 말", "작가 후기", "완결 후기", "후기", "Prologue",
            "=== 3화 ===", "--- 외전 ---", "어떤 소설 12화", "제１화",
        )
        for (s in positives) {
            val norm = s.replace('１', '1')
            assertTrue("should match: $s", TxtChapters.builtinMask(norm) and (TxtChapters.R_K4.inv()) != 0)
        }
    }

    @Test
    fun builtinRulesNegatives() {
        val negatives = listOf(
            "1화는 재미있었다고 그가 말했다.", "후기가 좋다고 하더라.", "외전이 나온다니 기대된다", "“1화 봤어?”",
            "“정말 그렇게 생각해?”", "3장의 카드를 뽑았다", "프롤로그가 끝났다", "서로를 바라보았다.", "에이, 설마",
            "1. 사과", "2) 배",
        )
        for (s in negatives) {
            val m = TxtChapters.builtinMask(s)
            val rejected = TxtChapters.rejectEnding(s)
            assertTrue("should not be a heading: $s (mask $m)", rejected || m and TxtChapters.R_K4.inv() == 0)
        }
        // lists match only the list-prone K4 rule
        assertEquals(TxtChapters.R_K4, TxtChapters.builtinMask("1. 사과"))
    }

    @Test
    fun detectsEachHeadingStyleInABook() {
        val styles = listOf(
            listOf("제1화", "제2화", "제3화"),
            listOf("제 1 화 새로운 시작", "제 2 화 두 번째 날", "제 3 화 마지막 밤"),
            listOf("001화", "002화", "003화"),
            listOf("1화. 출발", "2화. 도착", "3화. 귀환"),
            listOf("< 1화 >", "< 2화 >", "< 3화 >"),
            listOf("【1화】", "【2화】", "【3화】"),
            listOf("#1", "#2", "#3"),
            listOf("EP.1", "EP.2", "EP.3"),
            listOf("Chapter 1", "Chapter 2", "Chapter 3"),
            listOf("=== 1화 ===", "=== 2화 ===", "=== 3화 ==="),
            listOf("어떤 소설 1화", "어떤 소설 2화", "어떤 소설 3화"),
            listOf("　　제1장 시작", "  제2장 중간", "\t제3장 끝"),
        )
        for (hs in styles) {
            val p = TxtTestUtil.parse(book(hs))
            val expected = hs.map { h -> h.trim().trim('　').let { if (it.startsWith("===")) it.removePrefix("===").removeSuffix("===").trim() else it } }
            assertEquals("style ${hs[0]}", expected, titles(p))
        }
    }

    @Test
    fun specialsAlwaysJoinTheBestRule() {
        val p = TxtTestUtil.parse(book(listOf("프롤로그", "제1화 시작", "제2화 전개", "제3화 결말", "외전 1화", "에필로그", "작가의 말")))
        assertEquals(listOf("프롤로그", "제1화 시작", "제2화 전개", "제3화 결말", "외전 1화", "에필로그", "작가의 말"), titles(p))
        // a short text before the first chapter starts the first chapter's section (no nearly empty first page)
        val q = TxtTestUtil.parse(book(listOf("1화", "2화"), preface = "책 제목\n지은이"))
        assertEquals("1화", q.titles[0])
        assertEquals(2, q.sectionCount)
        assertTrue(q.buildSection(0, true).text.startsWith("책 제목\n지은이\n1화\n"))
        assertEquals(listOf("1화", "2화"), titles(q))
        // a longer one is its own untitled section
        val long = TxtTestUtil.body(Random(4), TxtParser.PREFACE_MERGE_CHARS, "\n")
        val q2 = TxtTestUtil.parse(book(listOf("1화", "2화"), preface = long))
        assertNull(q2.titles[0])
        assertEquals(0, q2.flags[0] and TxtIndex.CHAPTER)
        assertEquals(long, q2.buildSection(0, true).text)
        assertEquals(listOf("1화", "2화"), titles(q2))
    }

    @Test
    fun negativesInsideBodyAreNotHeadings() {
        val r = Random(5)
        val sb = StringBuilder()
        for (k in 1..4) {
            sb.append("제${k}화 이야기\n\n")
            sb.append(TxtTestUtil.body(r, 800, "\n\n")).append("\n\n")
            sb.append("1화는 재미있었다고 그가 말했다.\n\n“1화 봤어?”\n\n1. 사과\n2. 배\n3. 포도\n\n후기가 좋다고 하더라.\n\n")
            sb.append(TxtTestUtil.body(r, 800, "\n\n")).append("\n\n")
        }
        val p = TxtTestUtil.parse(sb.toString())
        assertEquals(listOf("제1화 이야기", "제2화 이야기", "제3화 이야기", "제4화 이야기"), titles(p))
    }

    @Test
    fun numberedListAloneIsNotChapters() {
        val r = Random(6)
        val text = TxtTestUtil.body(r, 3000, "\n\n") + "\n\n1. 사과\n2. 배\n\n" + TxtTestUtil.body(r, 3000, "\n\n") +
            "\n\n1. 연필\n2. 지우개\n3. 공책\n\n" + TxtTestUtil.body(r, 3000, "\n\n")
        val p = TxtTestUtil.parse(text)
        assertTrue(titles(p).isEmpty())
    }

    @Test
    fun spacedNumberedTitlesCanWinWithK4() {
        val p = TxtTestUtil.parse(book(listOf("1. 떠나는 날", "2. 낯선 도시", "3. 돌아오는 길", "4. 다시 봄")))
        assertEquals(listOf("1. 떠나는 날", "2. 낯선 도시", "3. 돌아오는 길", "4. 다시 봄"), titles(p))
    }

    @Test
    fun tocListingAtTopIsPruned() {
        val chapters = (1..8).map { "${it}화 제목$it" }
        val listing = "목차\n\n" + chapters.joinToString("\n")
        val p = TxtTestUtil.parse(book(chapters, preface = listing))
        assertEquals(chapters, titles(p))
        // the listing stays readable in the untitled first section
        val first = p.buildSection(0, true).text
        assertTrue(first.startsWith("목차"))
        assertTrue(first.contains("8화 제목8"))
        // listing immediately followed by the first real chapter (restart detection)
        val text2 = "목차\n" + chapters.joinToString("\n") + "\n\n" + book(chapters, seed = 2)
        assertEquals(chapters, titles(TxtTestUtil.parse(text2)))
        // listing with page numbers followed by body text (no restart): all listing entries dropped
        val text3 = chapters.joinToString("\n") { "$it ..... ${it.length}" } + "\n\n" +
            TxtTestUtil.body(Random(3), 800, "\n\n") + "\n\n" + book(chapters, seed = 3)
        assertEquals(chapters, titles(TxtTestUtil.parse(text3)))
    }

    @Test
    fun duplicateTitleRemoved() {
        val r = Random(8)
        val text = listOf("1화 시작", "2화 만남", "3화 이별").joinToString("\n\n") { h ->
            "$h\n\n$h\n\n" + TxtTestUtil.body(r, 1500, "\n\n")
        }
        val o = ParseOptions()
        val p = TxtTestUtil.parse(text, o)
        assertEquals(listOf("1화 시작", "2화 만남", "3화 이별"), titles(p))
        for (s in 0 until p.sectionCount) {
            val c = p.buildSection(s, true)
            val paras = c.blocks.map { c.text.substring(it.start, it.end) }
            assertEquals(p.titles[s], paras[0])
            assertFalse("duplicate removed in section $s", paras[1] == paras[0])
            assertEquals(p.chars[s], c.text.length)
        }
        // whitespace differences still count as duplicates
        val t2 = "제1화  시작\n제 1화 시작\n" + TxtTestUtil.body(r, 1500) + "\n제2화 끝\n" + TxtTestUtil.body(r, 1500)
        val p2 = TxtTestUtil.parse(t2)
        val c2 = p2.buildSection(0, true)
        assertEquals(2, titles(p2).size)
        assertFalse(c2.text.split('\n')[1].replace(" ", "") == "제1화시작")
    }

    @Test
    fun headingStyleAndPlainOption() {
        val text = book(listOf("제1화 시작", "제2화 끝"))
        val p = TxtTestUtil.parse(text)
        val c = p.buildSection(0, true)
        val b = c.blocks[0] as ParagraphBlock
        assertEquals(2, b.style.headingLevel)
        assertEquals(Align.CENTER, b.style.align)
        assertFalse(b.style.indent)
        assertEquals(1.5f, b.style.marginTopEm)
        assertEquals(1f, b.style.marginBottomEm)
        assertTrue(b.style.keepWithNext)
        assertEquals(1, c.styleRuns.size)
        assertTrue(c.styleRuns[0].style.bold)
        assertEquals(1.2f, c.styleRuns[0].style.sizeScale)
        assertEquals(0, c.styleRuns[0].start)
        assertEquals("제1화 시작".length, c.styleRuns[0].end)
        val plain = p.buildSection(0, false)
        assertEquals(0, (plain.blocks[0] as ParagraphBlock).style.headingLevel)
        assertTrue(plain.styleRuns.isEmpty())
        // detection off: no chapters at all
        val off = TxtTestUtil.parse(text, ParseOptions(txtDetectChapters = false))
        assertTrue(titles(off).isEmpty())
    }

    @Test
    fun userRegexTriedFirst() {
        val hs = listOf("**시작**", "**중간**", "**끝**")
        val o = ParseOptions(txtChapterRegex = "^\\*\\*.+\\*\\*$")
        assertEquals(hs, titles(TxtTestUtil.parse(book(hs), o)))
        // an invalid user regex is ignored (built-in rules still work)
        val o2 = ParseOptions(txtChapterRegex = "([bad")
        assertEquals(listOf("1화", "2화"), titles(TxtTestUtil.parse(book(listOf("1화", "2화")), o2)))
    }

    /**
     * A5 sample: [episodes] times "N화 / 본문 / [note] / 짧은 글", then each of [tail] with a little body text.
     * [head] headings come first (each with body text).
     */
    private fun notesBook(episodes: Int, tail: List<String> = emptyList(), note: String = "작가의 말", head: List<String> = emptyList()): String {
        val r = Random(7)
        val sb = StringBuilder()
        for (h in head) sb.append(h).append("\n\n").append(TxtTestUtil.body(r, 800, "\n\n")).append("\n\n")
        for (n in 1..episodes) {
            sb.append(n).append("화\n\n").append(TxtTestUtil.body(r, 1500, "\n\n")).append("\n\n")
            sb.append(note).append("\n\n").append("오늘도 읽어 주셔서 감사합니다!").append("\n\n")
        }
        for (h in tail) sb.append(h).append("\n\n").append(TxtTestUtil.body(r, 800, "\n\n")).append("\n\n")
        return sb.toString()
    }

    private fun episodes(n: Int) = (1..n).map { "${it}화" }

    @Test
    fun recurringAuthorNotesStayInsideTheirEpisode() {
        val p = TxtTestUtil.parse(notesBook(5))
        assertEquals(episodes(5), titles(p))
        // the note is still there: at the end of its episode's section, as an ordinary paragraph
        val last = p.buildSection(p.sectionCount - 1, true)
        assertTrue(last.text.endsWith("작가의 말\n오늘도 읽어 주셔서 감사합니다!"))
        val note = last.blocks.first { last.text.substring(it.start, it.end) == "작가의 말" } as ParagraphBlock
        assertEquals(0, note.style.headingLevel)

        // 에필로그 always stays; a closing note of another kind after the last episode keeps its entry
        assertEquals(episodes(5) + listOf("에필로그", "완결 후기"), titles(TxtTestUtil.parse(notesBook(5, listOf("에필로그", "완결 후기")))))
        assertEquals(episodes(5) + "후기", titles(TxtTestUtil.parse(notesBook(5, listOf("후기")))))
        // ... but not when it is the same kind as the recurring notes (the last episode's own note)
        assertEquals(episodes(4), titles(TxtTestUtil.parse(notesBook(4, listOf("후기"), note = "후기"))))
        // brackets and spacing don't hide a note; 프롤로그 and 외전 are never notes
        assertEquals(
            listOf("프롤로그") + episodes(4) + "외전 1화",
            titles(TxtTestUtil.parse(notesBook(4, listOf("외전 1화"), note = "[ 작가의  말 ]", head = listOf("프롤로그")))),
        )
    }

    @Test
    fun fewAuthorNotesAndUserRulesAreKept() {
        // fewer than 3 notes: unchanged (a single afterword, or a couple of notes)
        assertEquals(listOf("1화", "작가의 말", "2화", "작가의 말"), titles(TxtTestUtil.parse(notesBook(2))))
        // notes the user's own chapter regex matches are chapters
        val o = ParseOptions(txtChapterRegex = "^(\\d+화|작가의 말)$")
        assertEquals(episodes(3).flatMap { listOf(it, "작가의 말") }, titles(TxtTestUtil.parse(notesBook(3), o)))
        // only specials in the book (no numbered episodes): every special stays a chapter
        val specials = listOf("프롤로그", "작가의 말", "후기", "작가 후기", "에필로그")
        assertEquals(specials, titles(TxtTestUtil.parse(book(specials))))
    }

    @Test
    fun noteKinds() {
        assertEquals(1, TxtChapters.noteKind("작가의 말"))
        assertEquals(1, TxtChapters.noteKind("< 작가의말 >"))
        assertEquals(2, TxtChapters.noteKind("작가 후기 - 감사합니다"))
        assertEquals(3, TxtChapters.noteKind("【완결 후기】"))
        assertEquals(4, TxtChapters.noteKind("후기"))
        for (t in listOf("프롤로그", "에필로그", "서장", "종장", "서문", "외전", "번외", "후일담", "막간", "1화", null)) {
            assertEquals("$t", 0, TxtChapters.noteKind(t))
        }
    }

    @Test
    fun singleHeadingIsNotAToc() {
        val p = TxtTestUtil.parse(book(listOf("제1화 유일한 장")))
        assertTrue(titles(p).isEmpty())
    }

    @Test
    fun numberedHeadingsMayEndWithAPeriod() {
        val hs = listOf("7. 점소이가 행패를 부림.", "8. 객잔의 밤.", "9. 검은 옷의 사내.", "10. 새벽의 약속.")
        assertEquals(hs, titles(TxtTestUtil.parse(book(hs))))
        // the exemption is for the numbered shape only: a plain sentence is still no heading
        assertTrue(TxtChapters.rejectEnding("서로를 바라보았다."))
        assertFalse(TxtChapters.rejectEnding("7. 점소이가 행패를 부림."))
        assertFalse(TxtChapters.rejectEnding("12) 점소이가 행패를 부림."))
    }

    @Test
    fun scatteredNumberedSentencesAreNoChapters() {
        // numbered sentences that may end with '.' now, but whose numbers do not go up: no K4 chapters
        val hs = listOf("3. 그는 갔다.", "1. 그리고 끝났다.", "2. 다시 왔다.", "1. 또 갔다.", "3. 결국 졌다.")
        assertEquals(emptyList<String>(), titles(TxtTestUtil.parse(book(hs))))
    }

    @Test
    fun userRuleAddsToBuiltinRules() {
        val hs = (1..5).map { "${it}화" } + listOf("< 6 >", "< 7 >")
        val text = book(hs)
        assertEquals((1..5).map { "${it}화" }, titles(TxtTestUtil.parse(text)))
        val o = ParseOptions(txtChapterRegex = HeadingRule.simple("< N >"))
        assertEquals(hs, titles(TxtTestUtil.parse(text, o)))
        // a regex rule adds the same way
        val o2 = ParseOptions(txtChapterRegex = "^<\\s*\\d+\\s*>$")
        assertEquals(hs, titles(TxtTestUtil.parse(text, o2)))
    }

    @Test
    fun userRuleMatchedSentenceIsNotRejected() {
        val hs = listOf("첫 번째 이야기가 끝남.", "두 번째 이야기가 끝남.", "세 번째 이야기가 끝남.")
        val o = ParseOptions(txtChapterRegex = "끝남\\.$")
        assertEquals(hs, titles(TxtTestUtil.parse(book(hs), o)))
        assertTrue(titles(TxtTestUtil.parse(book(hs))).isEmpty())
    }

    @Test
    fun userRuleAloneWhenNoBuiltinRuleQualifies() {
        val hs = listOf("<시작>", "<중간>", "<끝>")
        val o = ParseOptions(txtChapterRegex = HeadingRule.simple("<*>"))
        assertEquals(hs, titles(TxtTestUtil.parse(book(hs), o)))
        // one match alone is not a rule
        val one = ParseOptions(txtChapterRegex = "^<시작>$")
        assertTrue(titles(TxtTestUtil.parse(book(hs), one)).isEmpty())
    }
}
