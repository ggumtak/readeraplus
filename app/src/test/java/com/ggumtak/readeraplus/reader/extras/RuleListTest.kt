package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.format.txt.ReplaceRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/** T1-10: the rule text ⇄ rule list codec, "이 문구 지우기" literals, the rule editor and the cleanup packs. */
class RuleListTest {

    /** [line] through [rules] exactly as the TXT parser applies them. */
    private fun apply(rules: String, line: String): String = ReplaceRules.parse(rules)?.applier()?.apply(line) ?: line

    // ------------------------------------------------------------------ RuleList

    @Test
    fun parsesNamesDisabledRulesAndDropsFreeComments() {
        val text = """
            # 내 규칙 (자유 주석)
            ## 광고 줄
            ^.*광고.*$ =>
            #- \.{3,} => …
            ## 이름만 있고 규칙 없음

            (\S)~ => $1
            화살표 없는 줄
             => 빈 패턴
        """.trimIndent()
        val items = RuleList.parse(text)
        assertEquals(3, items.size)
        assertEquals(RuleItem("광고 줄", "^.*광고.*$", "", true), items[0])
        assertEquals(RuleItem("", "\\.{3,}", "…", false), items[1])
        // A name line not directly above a rule is a free comment.
        assertEquals(RuleItem("", "(\\S)~", "$1", true), items[2])
    }

    @Test
    fun serializeRoundTripsAndKeepsTheParserReadingTheSame() {
        val items = listOf(
            RuleItem("광고 줄", "^.*광고.*$", "", true),
            RuleItem("", "\\.{3,}", "…", false),
            RuleItem("물결", "(\\S)~", "$1", true),
        )
        val text = RuleList.serialize(items)
        assertEquals("## 광고 줄\n^.*광고.*$ =>\n#- \\.{3,} => …\n## 물결\n(\\S)~ => $1", text)
        assertEquals(items, RuleList.parse(text))
        // Names and disabled rules are comments to the parser: only the two enabled rules apply.
        assertEquals(2, RuleList.enabledCount(text))
        assertEquals("", apply(text, "여기 광고 있음"))
        assertEquals("그래...", apply(text, "그래..."))
        assertEquals("아", apply(text, "아~"))
    }

    @Test
    fun serializeKeepsEveryRuleOnOneLine() {
        val text = RuleList.serialize(listOf(RuleItem("두\n줄", "a", "b", true)))
        assertEquals("## 두 줄\na => b", text)
        assertEquals(RuleItem("두 줄", "a", "b", true), RuleList.parse(text).single())
    }

    @Test
    fun emptyTextHasNoRules() {
        assertTrue(RuleList.parse("").isEmpty())
        assertTrue(RuleList.parse("  \n# 주석\n").isEmpty())
        assertEquals("", RuleList.serialize(emptyList()))
        assertEquals(0, RuleList.enabledCount(""))
    }

    @Test
    fun validityFollowsTheParser() {
        assertTrue(RuleList.isValid(RuleItem("", "a+", "b", true)))
        assertFalse(RuleList.isValid(RuleItem("", "잘못된[", "x", true)))
        // $2 with one group: the parser skips the rule.
        assertFalse(RuleList.isValid(RuleItem("", "(a)", "$2", true)))
        // A pattern starting with '#' is a comment line.
        assertFalse(RuleList.isValid(RuleItem("", "#+", "", true)))
    }

    // ------------------------------------------------------------------ RuleLiteral

    @Test
    fun literalRulesDeleteThePhraseOrItsLine() {
        val phrase = "무단 전재 (금지)"
        val whole = RuleLiteral.build(phrase, wholeLine = true)!!
        val only = RuleLiteral.build(phrase, wholeLine = false)!!
        assertTrue(whole.startsWith("^.*") && whole.endsWith(".*$ =>"))
        assertEquals("", apply(whole, "앞 글 무단 전재 (금지) 뒤 글"))
        assertEquals("앞 글  뒤 글", apply(only, "앞 글 무단 전재 (금지) 뒤 글"))
        // Regex metacharacters are literal.
        assertEquals("무단 전재 금지", apply(only, "무단 전재 금지"))
    }

    @Test
    fun literalWithArrowSurvivesTheRuleParser() {
        val phrase = "A => B"
        val rule = RuleLiteral.build(phrase, wholeLine = false)!!
        // The arrow the parser splits at is the rule's own, at the end.
        assertEquals(rule.length - 2, rule.indexOf("=>"))
        assertEquals("x  y", apply(rule, "x A => B y"))
        assertEquals("", apply(RuleLiteral.build("=>", wholeLine = true)!!, "a=>b"))
        assertEquals("ab", apply(RuleLiteral.build("=>", wholeLine = false)!!, "a=>b"))
    }

    @Test
    fun literalWithBackslashE() {
        val phrase = "a\\Eb\\Q.*"
        val rule = RuleLiteral.build(phrase, wholeLine = false)!!
        assertEquals("[][]", apply(rule, "[a\\Eb\\Q.*][]"))
        assertEquals("abc", apply(rule, "abc"))
    }

    @Test
    fun blankOrMultiLinePhrasesMakeNoRule() {
        assertNull(RuleLiteral.build("", wholeLine = true))
        assertNull(RuleLiteral.build("   ", wholeLine = false))
        assertNull(RuleLiteral.build("첫 줄\n둘째 줄", wholeLine = true))
        assertNull(RuleLiteral.build("a\rb", wholeLine = false))
    }

    @Test
    fun unquoteReadsBackEveryQuotedPhrase() {
        for (p in listOf("광고", "a.b*c", "A => B", "=>", "x=>", "a\\Eb", "\\E", "\\Q", "a\\b", "(괄호)", "끝\\")) {
            assertEquals(p, RuleLiteral.unquote(RuleLiteral.quote(p)))
            assertEquals(p, RuleLiteral.unquote(Pattern.quote(p).takeIf { !p.contains("=>") } ?: RuleLiteral.quote(p)))
            assertEquals(p, RuleLiteral.wholeLineOf("^.*" + RuleLiteral.quote(p) + ".*$"))
        }
        // Real regexes are not literal.
        assertNull(RuleLiteral.unquote("a+"))
        assertNull(RuleLiteral.unquote("^광고"))
        assertNull(RuleLiteral.unquote("\\Qopen"))
        assertNull(RuleLiteral.wholeLineOf("^.*광고+.*$"))
        // Plain letters and digits are their own literal.
        assertEquals("abc123", RuleLiteral.unquote("abc123"))
    }

    // ------------------------------------------------------------------ RuleEdit

    @Test
    fun literalFieldsQuoteThePatternAndTheReplacement() {
        val built = RuleEdit.build(RuleEdit.Fields(" 광고 ", "a.b", "\$1 원", regex = false))
        val r = built.item!!
        assertNull(built.error)
        assertEquals("광고", r.name)
        assertEquals("x\$1 원y", apply(RuleList.line(r.pattern, r.replacement), "xa.by"))
        assertEquals("xacby", apply(RuleList.line(r.pattern, r.replacement), "xacby"))
        val f = RuleEdit.fieldsOf(r)
        assertFalse(f.regex)
        assertEquals("a.b", f.find)
        assertEquals("\$1 원", f.replace)
    }

    @Test
    fun regexFieldsAreKeptAsWritten() {
        val r = RuleEdit.build(RuleEdit.Fields("", "(\\S)\\.{3}", "$1…", regex = true)).item!!
        assertEquals("(\\S)\\.{3}", r.pattern)
        assertEquals("$1…", r.replacement)
        assertTrue(RuleEdit.fieldsOf(r).regex)
        assertEquals("아…", RuleEdit.preview(r, "아..."))
        // A leading '#' would make the line a comment: escaped, same regex.
        val hash = RuleEdit.build(RuleEdit.Fields("", "#+", "", regex = true)).item!!
        assertEquals("\\#+", hash.pattern)
        assertTrue(RuleList.isValid(hash))
        assertEquals("", RuleEdit.preview(hash, "###"))
    }

    @Test
    fun editorRejectsWhatTheParserWouldSkip() {
        assertNotNull(RuleEdit.build(RuleEdit.Fields("", "  ", "x", regex = false)).error)
        assertNotNull(RuleEdit.build(RuleEdit.Fields("", "a\nb", "x", regex = false)).error)
        assertNotNull(RuleEdit.build(RuleEdit.Fields("", "a", "x\ny", regex = false)).error)
        assertNotNull(RuleEdit.build(RuleEdit.Fields("", "잘못된[", "x", regex = true)).error)
        assertNotNull(RuleEdit.build(RuleEdit.Fields("", "a=>b", "x", regex = true)).error)
        assertNotNull(RuleEdit.build(RuleEdit.Fields("", "(a)", "$2", regex = true)).error)
        // The same text is fine as plain text.
        assertNotNull(RuleEdit.build(RuleEdit.Fields("", "a=>b", "x", regex = false)).item)
    }

    @Test
    fun literalReplacementOnlyForQuotedText() {
        assertEquals("a\$b\\c", RuleEdit.literalReplacement("a\\\$b\\\\c"))
        assertNull(RuleEdit.literalReplacement("$1"))
        assertNull(RuleEdit.literalReplacement("a\\"))
        // "\n" in a replacement is a plain n: not what quoteReplacement writes, so it stays a regex replacement.
        assertNull(RuleEdit.literalReplacement("\\n"))
        assertEquals("", RuleEdit.literalReplacement(""))
    }

    @Test
    fun rowSummaryIsPlainForLiteralRules() {
        val line = RuleItem("", "^.*" + RuleLiteral.quote("광고") + ".*$", "", true)
        assertEquals("‘광고’가 든 줄 → 지움", RuleText.summary(line))
        val word = RuleItem("", RuleLiteral.quote("회차"), "", true)
        assertEquals("‘회차’가 든 줄 → 지움", RuleText.summary(word.copy(pattern = "^.*" + word.pattern + ".*$")))
        assertEquals("회차 → 지움", RuleText.summary(word))
        assertEquals("a.b → \$1", RuleText.summary(RuleEdit.build(RuleEdit.Fields("", "a.b", "\$1", regex = false)).item!!))
        assertEquals("\\.{3,} → …", RuleText.summary(RuleItem("", "\\.{3,}", "…", true)))
        assertEquals("‘책’이 든 줄 → 지움", RuleText.summary(RuleItem("", "^.*" + RuleLiteral.quote("책") + ".*$", "", true)))
    }

    // ------------------------------------------------------------------ cleanup packs

    @Test
    fun everyPackIsAValidRuleAndOffUntilAdded() {
        assertEquals(5, CleanupPacks.ALL.size)
        for (p in CleanupPacks.ALL) {
            assertTrue(p.name, RuleList.isValid(p.item))
            assertFalse(p.name, p.pattern.contains("=>"))
            assertFalse(CleanupPacks.isAdded(emptyList(), p))
            assertTrue(CleanupPacks.isAdded(RuleList.parse(RuleList.serialize(listOf(p.item.copy(enabled = false)))), p))
        }
        assertNotNull(CleanupPacks.ALL[3].warning)
    }

    @Test
    fun packsMatchTypicalNoiseAndLeaveStoryText() {
        val all = RuleList.serialize(CleanupPacks.ALL.map { it.item })
        for (noise in listOf(
            "※ 본 작품은 무단 전재 및 재배포를 금지합니다.",
            "불펌 금지",
            "< 다음 화 보기 >",
            "[목록으로]",
            "이전 화",
            "출처: https://example.com/novel/123",
            "www.example.co.kr 에서 연재 중",
            "재미있게 보셨다면 추천 부탁드립니다!",
            "선호작 등록과 댓글 부탁드려요",
        )) {
            assertEquals(noise, "", apply(all, noise))
        }
        for (story in listOf(
            "그는 다음 화살을 시위에 걸었다.",
            "목록을 훑어보던 그녀가 고개를 들었다.",
            "“추천서는 내가 써 주지.”",
            "무단으로 들어온 사람은 없었다.",
        )) {
            assertEquals(story, story, apply(all, story))
        }
        assertEquals("그래…", apply(all, "그래......"))
    }
}
