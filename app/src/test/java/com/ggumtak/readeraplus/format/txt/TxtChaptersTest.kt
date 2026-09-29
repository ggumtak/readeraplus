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
        // the text before the first chapter is its own untitled section
        val q = TxtTestUtil.parse(book(listOf("1화", "2화"), preface = "책 제목\n지은이"))
        assertNull(q.titles[0])
        assertEquals(0, q.flags[0] and TxtIndex.CHAPTER)
        assertEquals("책 제목\n지은이", q.buildSection(0, true).text)
        assertEquals(listOf("1화", "2화"), titles(q))
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

    @Test
    fun singleHeadingIsNotAToc() {
        val p = TxtTestUtil.parse(book(listOf("제1화 유일한 장")))
        assertTrue(titles(p).isEmpty())
    }
}
