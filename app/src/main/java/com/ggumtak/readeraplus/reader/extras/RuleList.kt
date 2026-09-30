package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.format.txt.ReplaceRules
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * One replacement rule as the rule manager shows it (T1-10). [pattern] is always the regex stored in the rule text
 * (the editor's "정규식" switch off means it was made with `Pattern.quote`); an empty [replacement] deletes.
 */
data class RuleItem(
    /** From a `## 이름` line directly above the rule; "" when unnamed. */
    val name: String,
    val pattern: String,
    val replacement: String,
    /** False for a rule stored as a `#- ` comment line (kept, not applied). */
    val enabled: Boolean,
)

/**
 * The rule text (`ReaderSettings.txtReplaceRules` / `TxtOverride.replaceRules`) ⇄ [RuleItem]s. The format does not
 * change: lines of `pattern => replacement`; the manager adds `## 이름` name lines and `#- ` for disabled rules,
 * both plain comments to the parser (`ReplaceRules.parse`), so old texts and hand-written ones stay valid.
 * Pure and unit-tested; owner: EXTRAS_TOOLS. Users: RulesDialog, the popup's TXT rows, SelectionController, and
 * SETTINGS' TXT 기본 정리 설정 page (for the row summary).
 */
object RuleList {
    private const val NAME_PREFIX = "##"
    private const val DISABLED_PREFIX = "#-"
    private const val ARROW = "=>"

    /** Items of [text] in order. Free comments / blank lines are not items ([serialize] may drop them). */
    fun parse(text: String): List<RuleItem> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<RuleItem>()
        var pendingName: String? = null
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            val t = line.trim()
            val name = pendingName
            pendingName = null
            when {
                t.startsWith(NAME_PREFIX) -> pendingName = t.substring(NAME_PREFIX.length).trim()
                t.startsWith(DISABLED_PREFIX) -> ruleOf(t.substring(DISABLED_PREFIX.length), name ?: "", enabled = false)?.let { out += it }
                // Exactly the parser's reading: a comment, or the first "=>" splits a non-empty pattern from the rest.
                t.isNotEmpty() && !t.startsWith("#") -> ruleOf(line, name ?: "", enabled = true)?.let { out += it }
            }
        }
        return out
    }

    /** Text for [rules]: `## 이름` (when named), then `pattern => replacement` or `#- pattern => replacement`. */
    fun serialize(rules: List<RuleItem>): String {
        val sb = StringBuilder()
        for (r in rules) {
            val name = oneLine(r.name).trim()
            if (name.isNotEmpty()) sb.append(NAME_PREFIX).append(' ').append(name).append('\n')
            if (!r.enabled) sb.append(DISABLED_PREFIX).append(' ')
            sb.append(line(r.pattern, r.replacement)).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** Rules the parser applies: non-blank, non-comment lines with "=>" (the row summary "치환 규칙 (n개 켜짐)"). */
    fun enabledCount(text: String): Int = text.lineSequence().count { line ->
        val t = line.trim()
        t.isNotEmpty() && !t.startsWith("#") && t.indexOf("=>") > 0
    }

    /** The rule line of one rule, as the parser reads it (`pattern =>` when the replacement is empty). */
    internal fun line(pattern: String, replacement: String): String {
        val p = oneLine(pattern).trim()
        val r = oneLine(replacement).trim()
        return if (r.isEmpty()) "$p $ARROW" else "$p $ARROW $r"
    }

    /** True when the parser would apply [r] (when enabled): the pattern compiles and the replacement is valid. */
    internal fun isValid(r: RuleItem): Boolean = runCatching { ReplaceRules.parse(line(r.pattern, r.replacement)) != null }.getOrDefault(false)

    private fun ruleOf(body: String, name: String, enabled: Boolean): RuleItem? {
        val arrow = body.indexOf(ARROW)
        if (arrow < 0) return null
        val pattern = body.substring(0, arrow).trim()
        if (pattern.isEmpty()) return null
        return RuleItem(name, pattern, body.substring(arrow + ARROW.length).trim(), enabled)
    }

    private fun oneLine(s: String): String = if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0) s else s.replace('\r', ' ').replace('\n', ' ')
}

/** A rule that deletes a phrase as it appears in the text (the selection's "이 문구 지우기", T1-10). Pure. */
object RuleLiteral {
    private const val WHOLE_START = "^.*"
    private const val WHOLE_END = ".*$"
    /** "=>" inside a pattern: the rule parser splits at the first "=>", so the arrow is written as a class. */
    private const val ARROW_LITERAL = "=[>]"

    /**
     * The rule line for [phrase]: `^.*<lit>.*$ =>` ([wholeLine]: "줄 전체 지우기") or `<lit> =>` ("이 문구만"), where
     * `<lit>` is [phrase] split at "=>", each part `Pattern.quote`d and the parts joined with `=[>]` (a raw `\Q…\E`
     * holding "=>" would be cut at the arrow by the rule parser; `Pattern.quote` also handles an embedded `\E`).
     * Null when [phrase] is blank or contains a line break (rules apply per source line: the action is disabled).
     */
    fun build(phrase: String, wholeLine: Boolean): String? {
        if (phrase.isBlank() || phrase.indexOf('\n') >= 0 || phrase.indexOf('\r') >= 0) return null
        val lit = quote(phrase)
        return if (wholeLine) "$WHOLE_START$lit$WHOLE_END =>" else "$lit =>"
    }

    /** A regex that matches [text] literally and holds no "=>" (see [build]). */
    internal fun quote(text: String): String =
        text.split("=>").joinToString(ARROW_LITERAL) { if (it.isEmpty()) "" else Pattern.quote(it) }

    /**
     * The text a literal pattern ([quote]'s output, or any mix of `\Q…\E` blocks, `\\` escapes, `=[>]` and plain
     * letters / digits) matches, or null when [pattern] is a real regex.
     */
    internal fun unquote(pattern: String): String? {
        if (pattern.isEmpty()) return null
        val sb = StringBuilder(pattern.length)
        var i = 0
        val n = pattern.length
        while (i < n) {
            val c = pattern[i]
            when {
                pattern.startsWith("\\Q", i) -> {
                    val end = pattern.indexOf("\\E", i + 2)
                    if (end < 0) return null
                    sb.append(pattern, i + 2, end)
                    i = end + 2
                }
                pattern.startsWith("\\\\", i) -> {
                    sb.append('\\')
                    i += 2
                }
                pattern.startsWith(ARROW_LITERAL, i) -> {
                    sb.append("=>")
                    i += ARROW_LITERAL.length
                }
                c.isLetterOrDigit() -> {
                    sb.append(c)
                    i++
                }
                else -> return null
            }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** The phrase of a whole-line literal rule pattern (`^.*<lit>.*$`, "줄 전체 지우기"), or null. */
    internal fun wholeLineOf(pattern: String): String? {
        if (pattern.length <= WHOLE_START.length + WHOLE_END.length) return null
        if (!pattern.startsWith(WHOLE_START) || !pattern.endsWith(WHOLE_END)) return null
        return unquote(pattern.substring(WHOLE_START.length, pattern.length - WHOLE_END.length))
    }
}

/** The rule editor's fields ⇄ a [RuleItem] (T1-10; pure, unit-tested). */
internal object RuleEdit {
    /** What the editor shows: [regex] false = [find] / [replace] are plain text (quoted when saved). */
    class Fields(val name: String, val find: String, val replace: String, val regex: Boolean)

    /** A rule, or why the fields can't make one (a message for the user). */
    class Built(val item: RuleItem?, val error: String?)

    /** The fields of [r]: plain text when its pattern and replacement are literal, else the regex as stored. */
    fun fieldsOf(r: RuleItem): Fields {
        val lit = RuleLiteral.unquote(r.pattern)
        val rep = literalReplacement(r.replacement)
        return if (lit != null && rep != null) Fields(r.name, lit, rep, regex = false) else Fields(r.name, r.pattern, r.replacement, regex = true)
    }

    /** Builds the rule for [f] (keeping [enabled]); names are cut to one line. */
    fun build(f: Fields, enabled: Boolean = true): Built {
        val name = f.name.replace('\r', ' ').replace('\n', ' ').trim()
        if (f.find.isBlank()) return Built(null, "찾을 내용을 입력하세요")
        if (f.find.indexOf('\n') >= 0 || f.find.indexOf('\r') >= 0) return Built(null, "찾을 내용은 한 줄로 적으세요")
        if (f.replace.indexOf('\n') >= 0 || f.replace.indexOf('\r') >= 0) return Built(null, "바꿀 내용은 한 줄로 적으세요")
        val replace = f.replace.trim()
        if (!f.regex) {
            return Built(RuleItem(name, RuleLiteral.quote(f.find), Matcher.quoteReplacement(replace), enabled), null)
        }
        var pattern = f.find.trim()
        if (pattern.contains("=>")) return Built(null, "정규식에 '=>'를 쓰려면 '=[>]'로 적으세요")
        // A rule line starting with '#' is a comment to the parser: escape the character (same regex).
        if (pattern.startsWith("#")) pattern = "\\$pattern"
        val compiled = try {
            Pattern.compile(pattern)
        } catch (e: PatternSyntaxException) {
            return Built(null, ErrorText.regex(e))
        }
        if (!ReplaceRules.validReplacement(replace, compiled.matcher("").groupCount(), pattern)) {
            return Built(null, "바꿀 내용의 \$ 또는 \\ 표현이 올바르지 않습니다")
        }
        return Built(RuleItem(name, pattern, replace, enabled), null)
    }

    /** [sample] after [r] (as the parser applies it to one source line), or null when the rule is invalid. */
    fun preview(r: RuleItem, sample: String): String? =
        runCatching { ReplaceRules.parse(RuleList.line(r.pattern, r.replacement))?.applier()?.apply(sample) }.getOrNull()

    /** The plain text of a replacement made with `Matcher.quoteReplacement`, or null when it uses `$` groups. */
    fun literalReplacement(rep: String): String? {
        val sb = StringBuilder(rep.length)
        var i = 0
        while (i < rep.length) {
            val c = rep[i]
            when (c) {
                '\\' -> {
                    if (i + 1 >= rep.length) return null
                    sb.append(rep[i + 1])
                    i += 2
                }
                '$' -> return null
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        val s = sb.toString()
        return if (Matcher.quoteReplacement(s) == rep) s else null
    }
}

/** How the rule manager lists a rule (pure, unit-tested). */
internal object RuleText {
    /** "‘광고’가 든 줄 → 지움", "무단 전재 → 지움", "\.{3,} → …" (plain text for literal rules). */
    fun summary(r: RuleItem): String = "${find(r)} → ${replace(r)}"

    fun find(r: RuleItem): String {
        RuleLiteral.wholeLineOf(r.pattern)?.let { return "‘$it’${Josa.iGa(it)} 든 줄" }
        return RuleLiteral.unquote(r.pattern) ?: r.pattern
    }

    fun replace(r: RuleItem): String {
        if (r.replacement.isEmpty()) return "지움"
        val lit = if (RuleLiteral.unquote(r.pattern) != null || RuleLiteral.wholeLineOf(r.pattern) != null) RuleEdit.literalReplacement(r.replacement) else null
        return lit ?: r.replacement
    }
}

/**
 * The ready-made cleanup rules ("정리 규칙 팩", T1-10): original patterns for common 텍본 noise. None is in any rule
 * text until the user adds it; an added pack is an ordinary named rule (it can be switched off or edited).
 */
internal object CleanupPacks {
    class Pack(val name: String, val pattern: String, val replacement: String, val warning: String? = null) {
        val item: RuleItem get() = RuleItem(name, pattern, replacement, enabled = true)
    }

    val ALL: List<Pack> = listOf(
        Pack("무단 전재·배포 금지 문구", """^.*(무단\s*(전재|복제|배포|도용)|재배포\s*(금지|불가)|불펌\s*금지).*$""", ""),
        Pack(
            "다음 화·목록 안내",
            """^\s*[<\[(〈《【]?\s*(다음\s*화|이전\s*화|다음\s*편|목록(으로)?)\s*(보기|가기)?\s*[>\])〉》】]?\s*$""",
            "",
        ),
        Pack("사이트 주소 줄", """^.*(https?://|www\.)\S+.*$""", ""),
        Pack(
            "추천·선호작·후원 부탁 줄",
            """^.*(추천|선호작|후원|구독|알림\s*설정|좋아요|댓글)[^.!?]{0,15}(부탁|눌러|해\s*주(세요|시면)).*$""",
            "",
            warning = "대사에 쓰이면 함께 지워질 수 있음",
        ),
        Pack("말줄임표 정리", """\.{3,}""", "…"),
    )

    /** True when [rules] already hold [p] (by pattern, switched on or off). */
    fun isAdded(rules: List<RuleItem>, p: Pack): Boolean = rules.any { it.pattern == p.pattern }
}
