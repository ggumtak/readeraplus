package com.ggumtak.readeraplus.format.txt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern
import kotlin.random.Random

/** The replace rules' literal prefilter (T1-10): what it extracts, that it never rejects a match, same output. */
class RulePrefilterTest {
    private fun req(p: String): List<Set<String>> = RegexLiterals.required(p).map { it.toSet() }

    /** The five cleanup packs of spec T1-10, verbatim. */
    private val packs = listOf(
        """^.*(무단\s*(전재|복제|배포|도용)|재배포\s*(금지|불가)|불펌\s*금지).*$""",
        """^\s*[<\[(〈《【]?\s*(다음\s*화|이전\s*화|다음\s*편|목록(으로)?)\s*(보기|가기)?\s*[>\])〉》】]?\s*$""",
        """^.*(https?://|www\.)\S+.*$""",
        """^.*(추천|선호작|후원|구독|알림\s*설정|좋아요|댓글)[^.!?]{0,15}(부탁|눌러|해\s*주(세요|시면)).*$""",
        """\.{3,}""",
    )

    @Test
    fun cleanupPackLiterals() {
        assertEquals(listOf(setOf("무단", "재배포", "불펌")), req(packs[0]))
        assertEquals(listOf(setOf("다음", "이전", "목록")), req(packs[1]))
        assertEquals(listOf(setOf("http", "www.")), req(packs[2]))
        assertEquals(
            listOf(setOf("추천", "선호작", "후원", "구독", "알림", "좋아요", "댓글"), setOf("부탁", "눌러", "세요", "시면")),
            req(packs[3]),
        )
        assertEquals(listOf(setOf("...")), req(packs[4]))
    }

    @Test
    fun literalRulesFromTheSelection() {
        // RuleLiteral.build's shapes: `^.*<lit>.*$` and `<lit>`, "=>" written as "=[>]", Pattern.quote parts
        assertEquals(listOf(setOf("광고 문구")), req("^.*" + Pattern.quote("광고 문구") + ".*$"))
        assertEquals(listOf(setOf("a=>b")), req(Pattern.quote("a") + "=[>]" + Pattern.quote("b")))
        assertEquals(listOf(setOf("x\\Ey")), req(Pattern.quote("x\\Ey")))
        assertEquals(listOf(setOf("(1+1)?")), req(Pattern.quote("(1+1)?")))
        assertEquals(listOf(setOf("#공지")), req("\\#공지"))
    }

    @Test
    fun sequencesAndQuantifiers() {
        assertEquals(listOf(setOf("abc")), req("abc"))
        assertEquals(listOf(setOf("a"), setOf("c")), req("ab?c"))
        assertEquals(listOf(setOf("ab"), setOf("bc")), req("ab+c"))
        assertEquals(listOf(setOf("abbc")), req("ab{2}c"))
        assertEquals(listOf(setOf("abb"), setOf("bbc")), req("ab{2,5}c"))
        assertEquals(listOf(setOf("b")), req("a*b"))
        assertEquals(listOf(setOf("가나"), setOf("다")), req("가나.{3}다"))
        assertEquals(listOf(setOf("foo", "bar"), setOf("baz")), req("(foo|bar)+baz"))
        assertEquals(listOf(setOf("baz")), req("(foo|bar)?baz"))
        assertEquals(listOf(setOf("baz")), req("(?:foo|bar)*?baz"))
        assertEquals(listOf(setOf("foo", "ba")), req("foo|ba"))
        assertEquals(listOf(setOf("d")), req("(?=abc)d"))
        assertEquals(listOf(setOf("x")), req("(?<!y)x(?!z)"))
        assertEquals(listOf(setOf("title")), req("(?<name>title)\\s+\\d+"))
        assertEquals(listOf(setOf("]x")), req("[\\]]x"))
        assertEquals(listOf(setOf("b")), req("[^a]b"))
        assertEquals(listOf(setOf("...")), req("\\.{3}"))
        assertEquals(listOf(setOf("........")), req("\\.{20,}")) // spelled out 8 times at most
        // the most selective set first: longest shortest literal, then the earliest
        assertEquals(listOf(setOf("abc"), setOf("x")), req("x.abc"))
    }

    @Test
    fun unknownSyntaxGivesNothing() {
        val none = listOf(
            "(?i)abc", "(?x)a b", "a(?i:b)", "\\u00e9x", "\\x41bc", "\\0101", "\\cMx", "(a)\\1b", "(?<n>a)\\k<n>",
            "\\N{LATIN SMALL LETTER A}", "[[a]b]x", "[]a]x", "\\Qab\\E+", "a{2}{3}", "😀?x", "a|", "(a|)", "\\s+$", ".*", "",
            "\\p{L}+",
        )
        for (p in none) {
            Pattern.compile(p) // valid patterns: only the analysis gives up
            assertTrue("$p → ${req(p)}", req(p).isEmpty())
        }
        // a pattern with some literal outside the unknown part still reports that one... only if the rest parses
        assertEquals(listOf(setOf("b")), req("(a|)b"))
        assertEquals(listOf(setOf("가")), req("\\p{L}+가"))
    }

    /**
     * Soundness on random patterns: whenever the regex finds a match in a line, the line holds a string of every
     * required set. Small alphabet and short lines, so matches are frequent.
     */
    @Test
    fun neverRejectsAMatch() {
        val r = Random(11)
        val alphabet = "ab가.-"
        var checked = 0
        var matched = 0
        repeat(4000) {
            val p = randomPattern(r, 0)
            val compiled = try {
                Pattern.compile(p)
            } catch (_: Exception) {
                return@repeat
            }
            val sets = RegexLiterals.required(p)
            repeat(40) {
                val line = String(CharArray(r.nextInt(14)) { alphabet[r.nextInt(alphabet.length)] })
                checked++
                if (compiled.matcher(line).find()) {
                    matched++
                    for (set in sets) assertTrue("pattern $p, line '$line', set ${set.toList()}", set.any { line.contains(it) })
                }
            }
        }
        assertTrue("matches $matched of $checked", matched > checked / 20)
    }

    private fun randomPattern(r: Random, depth: Int): String {
        val sb = StringBuilder()
        repeat(1 + r.nextInt(4)) {
            when (r.nextInt(if (depth < 2) 10 else 7)) {
                0, 1, 2 -> sb.append("ab가"[r.nextInt(3)])
                3 -> sb.append(listOf("\\.", "\\-", "[ab]", "[^a]", "[.]", ".", "^", "$", "\\s", "[가]")[r.nextInt(10)])
                4 -> sb.append("\\Q").append("a.b"[r.nextInt(3)]).append("a-"[r.nextInt(2)]).append("\\E")
                5, 6 -> sb.append("ab"[r.nextInt(2)]).append(listOf("?", "*", "+", "{2}", "{1,3}", "{2,}", "??", "+?")[r.nextInt(8)])
                else -> {
                    val alts = List(1 + r.nextInt(3)) { randomPattern(r, depth + 1) }
                    sb.append(listOf("(", "(?:", "(?=", "(?!")[r.nextInt(4)]).append(alts.joinToString("|")).append(')')
                    if (r.nextBoolean()) sb.append(listOf("?", "*", "+", "{2}")[r.nextInt(4)])
                }
            }
        }
        return sb.toString()
    }

    /** The rules as they applied before the prefilter: every regex on every line, in order. */
    private fun reference(rules: List<Pair<Pattern, String>>, line: String): String {
        var s = line
        for ((p, rep) in rules) {
            val m = p.matcher(s)
            if (m.find()) s = m.replaceAll(rep)
        }
        return s
    }

    @Test
    fun sameOutputAsWithoutThePrefilter() {
        val text = packs.joinToString("\n") { "$it =>" }.replace("\\.{3,} =>", "\\.{3,} => …") +
            "\nfoo => 무단\n^.*무단.*$ => [지움]\n"
        sameOutput(text) // every rule has literals: the line gate is on
        sameOutput(text + "(?i)ABC => x\n") // a rule without literals: every line is a String again
    }

    private fun sameOutput(text: String) {
        val rules = ReplaceRules.parse(text)!!
        val plain = text.lines().mapNotNull { l ->
            val a = l.indexOf("=>")
            if (a <= 0 || l.startsWith("#")) null else Pattern.compile(l.substring(0, a).trim()) to l.substring(a + 2).trim()
        }
        val lines = listOf(
            "", "평범한 문장입니다.", "※ 무단 전재 및 재배포 금지", "무단전재", "< 다음 화 보기 >", "다음 화", "그는 다음 날 떠났다.",
            "출처: https://example.com/a", "www.site.kr/1 참고", "추천과 선호작 부탁드려요", "좋아요 눌러 주세요!",
            "그래서...... 말이야...", "..", "foo bar", "abc ABC aBc", "“추천해 주세요.”", "목록으로 가기", "  [이전 화]  ",
        )
        val applier = rules.applier()
        val r = Random(5)
        val all = lines + List(300) { lines[r.nextInt(lines.size)] + " " + lines[r.nextInt(lines.size)] }
        for (line in all) {
            val expected = reference(plain, line)
            assertEquals("'$line'", expected, applier.apply(line))
            val a = ("xx" + line + "yy").toCharArray()
            val got = applier.apply(a, 2, 2 + line.length)
            if (expected == line) assertNull("'$line' → $got", got) else assertEquals("'$line'", expected, got)
        }
    }

    /** The line table built with the gate (every rule has literals) equals the one built without it. */
    @Test
    fun lineTableSameWithAndWithoutTheGate() {
        val rules = packs.joinToString("\n") { "$it =>" }.replace("\\.{3,} =>", "\\.{3,} => …")
        val gated = ReplaceRules.parse(rules)!!
        assertNotNull(gated.gate)
        val ungated = ReplaceRules.parse("$rules\n(?i)zz\\x{FFFF} =>")!! // a rule without literals: no gate
        assertNull(ungated.gate)
        val r = Random(9)
        val samples = listOf(
            "평범한 문장입니다.", "※ 무단 전재 및 재배포 금지", "< 다음 화 보기 >", "출처: https://example.com/a", "   ",
            "추천과 선호작 부탁드려요", "그래서...... 말이야...", "", "\t들여쓴 줄", "“추천해 주세요.”", "무\u0000단", "x" + "가".repeat(9000),
        )
        val text = List(600) { samples[r.nextInt(samples.size)] }.joinToString("\n")
        fun table(rules: ReplaceRules) = LineTable.build(text.toCharArray(), text.length, '\n', LineConfig(true, rules, true), null)
        val a = table(gated)
        val b = table(ungated)
        assertEquals(b.count, a.count)
        var deleted = 0
        for (i in 0 until a.count) {
            assertEquals("line $i", b.flags[i], a.flags[i])
            assertEquals("line $i", b.text(i), a.text(i))
            if (a.flags[i] and LineFlags.DELETED != 0) deleted++
        }
        assertTrue(deleted > 50)
    }

    @Test
    fun lineGate() {
        val gate = LineGate.of(listOf(arrayOf("무단", "불펌"), arrayOf("...")))!!
        fun may(s: String) = gate.mayMatch(s.toCharArray(), 0, s.length)
        assertFalse(may("평범한 문장입니다."))
        assertFalse(may(""))
        assertFalse(may("무"))
        assertTrue(may("무단 전재"))
        assertTrue(may("끝에 불펌"))
        assertTrue(may("그래서..."))
        // a one-char literal is tested per char
        val one = LineGate.of(listOf(arrayOf("♡")))!!
        assertTrue(one.mayMatch("사랑해♡".toCharArray(), 0, 4))
        assertTrue(one.mayMatch("♡".toCharArray(), 0, 1))
        assertFalse(one.mayMatch("사랑해".toCharArray(), 0, 3))
        // a rule without literals disables the gate
        assertNull(LineGate.of(listOf(arrayOf("a"), null)))
        assertNotNull(LineGate.of(listOf(arrayOf("ab"))))
        // control chars inside a line don't hide a literal after them
        assertTrue(may("x\u0000y\r무단"))
    }

    @Test
    fun scanLineFindsTheLineEndAndTestsTheLine() {
        val gate = LineGate.of(listOf(arrayOf("무단", "불펌"), arrayOf("♡")))!!
        val text = "평범한 줄\n무단 전재 금지\n\n사랑해♡\n마지막 줄".toCharArray()
        val got = ArrayList<Pair<String, Boolean>>()
        var cs = 0
        while (cs <= text.size) {
            val r = gate.scanLine(text, cs, text.size, '\n')
            val ce = r.toInt()
            got.add(String(text, cs, ce - cs) to (r ushr 32 != 0L))
            if (ce >= text.size) break
            cs = ce + 1
        }
        assertEquals(
            listOf("평범한 줄" to false, "무단 전재 금지" to true, "" to false, "사랑해♡" to true, "마지막 줄" to false),
            got,
        )
        // the same answers as mayMatch, line by line, on random text
        val r = Random(3)
        val alphabet = "무단불펌♡가나 \n"
        val a = CharArray(5000) { alphabet[r.nextInt(alphabet.length)] }
        var start = 0
        while (start < a.size) {
            val res = gate.scanLine(a, start, a.size, '\n')
            val end = res.toInt()
            assertEquals(gate.mayMatch(a, start, end), res ushr 32 != 0L)
            assertEquals(end == a.size || a[end] == '\n', true)
            start = end + 1
        }
    }
}
