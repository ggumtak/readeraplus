package com.ggumtak.readeraplus.format.txt

import java.util.regex.Pattern

/*
 * Korean web-novel chapter detection over processed lines.
 *
 * 1. Candidates: non-blank lines of at most 60 chars that pass a cheap structural prefilter (first significant
 *    char is a digit / 제 / a keyword initial / a separator, or the line ends with 화).
 * 2. Every built-in rule (K1..K6, see ARCHITECTURE.md) is tried on the candidate; the user regex first.
 * 3. Scoring: per rule, count matches spaced more than 1000 chars apart; the best rule wins (K4 "1. title" only
 *    under stricter conditions); specials (K3: 프롤로그, 외전, 후기, …) are always included, except author notes
 *    that recur after the episodes (작가의 말 / 작가 후기 / 후기 / 완결 후기, see [authorNotes]).
 * 4. Cleanup: an immediately repeated heading is a duplicated title (second one dropped); runs of 3+ headings
 *    with no body between them are a table-of-contents listing and are pruned.
 */
internal object TxtChapters {
    const val MAX_HEADING_CHARS = 60

    const val R_USER = 1
    const val R_K1 = 2
    const val R_K2 = 4
    const val R_K3 = 8
    const val R_K4 = 16
    const val R_K5 = 32
    const val R_K6 = 64

    private const val OPEN = "[<〈《\\[【「『(（]"
    private const val CLOSE = "[>〉》\\]】」』)）]"
    private const val SEP = "[.:：\\-–—~|·]"

    private val K1: Pattern = Pattern.compile(
        "$OPEN?\\s*(?:[제第]\\s*)?\\d{1,5}\\s*(?:화|장|회|편|부|권|막|절|話|章|回|節|卷)\\s*$CLOSE?(?:\\s*$SEP\\s*|\\s+|$).{0,50}"
    )
    private val K2: Pattern = Pattern.compile(
        "[<〈《\\[【]?\\s*(?:(?i:episode|chapter|ep|ch)|#)\\s*\\.?\\s*\\d{1,5}(?!\\d).{0,50}"
    )
    private val K3: Pattern = Pattern.compile(
        "[<〈《\\[【]?\\s*(?:프롤로그|에필로그|서장|종장|서문|특별\\s*외전|외전편|외전|번외편|번외|후일담|막간|" +
            "작가\\s*후기|작가의\\s*말|완결\\s*후기|후기|(?i:prologue|epilogue)|序章|終章)" +
            "(?=$|[\\s\\d.:：\\-–—~|·>〉》\\]】」』)）]).{0,40}"
    )
    private val K4: Pattern = Pattern.compile("\\d{1,4}\\s*[.)]\\s+\\S.{0,40}")
    private val K5: Pattern = Pattern.compile("[=\\-*~#]{3,}\\s*(\\S.{0,40}?)\\s*[=\\-*~#]{3,}")
    private val K6: Pattern = Pattern.compile("\\S.{0,30}?\\s+\\d{1,5}\\s*화")

    /** Result of detection: heading line indices (ascending) and their TOC titles. */
    class Result(val lines: IntArray, val titles: Array<String>)

    /**
     * Detects chapter headings in [t] (does not modify it). [userRegex] (find semantics) is tried first;
     * an invalid one is ignored. Returns an empty result when fewer than 2 chapters are found.
     */
    fun detect(t: LineTable, userRegex: String): Result {
        val user: Pattern? = if (userRegex.isBlank()) null else try {
            Pattern.compile(userRegex)
        } catch (_: Exception) {
            null
        }
        var cIdx = IntArray(256)
        var cMask = IntArray(256)
        var cTitle = arrayOfNulls<String>(256)
        var nc = 0
        val skip = LineFlags.BLANK or LineFlags.DELETED or LineFlags.SCENE or LineFlags.CONT
        for (i in 0 until t.count) {
            val f = t.flags[i]
            if (f and skip != 0) continue
            val a = t.arr(i)
            var s = t.start[i]
            val e = t.end[i]
            while (s < e && TxtChars.isWs(a[s])) s++
            if (e - s > MAX_HEADING_CHARS || e <= s) continue
            val pre = prefilter(a, s, e)
            if (user == null && !pre) continue
            val cand = candidateString(a, s, e)
            if (rejectEnding(cand)) continue
            var mask = 0
            var title: String? = null
            if (user != null && userMatches(user, cand)) mask = mask or R_USER
            if (pre) mask = mask or builtinMask(cand)
            if (mask and R_K5 != 0) {
                val m = K5.matcher(cand)
                if (m.matches()) {
                    val inner = m.group(1) ?: ""
                    val innerMask = builtinMask(inner) and R_K5.inv()
                    if (innerMask == 0 && !inner.any { it in '0'..'9' }) {
                        mask = mask and R_K5.inv()
                    } else {
                        mask = mask or innerMask
                        title = collapseWs(inner)
                    }
                }
            }
            if (mask == 0) continue
            if (nc == cIdx.size) {
                cIdx = cIdx.copyOf(nc * 2)
                cMask = cMask.copyOf(nc * 2)
                cTitle = cTitle.copyOf(nc * 2)
            }
            cIdx[nc] = i
            cMask[nc] = mask
            cTitle[nc] = title ?: collapseWs(cand)
            nc++
        }
        if (nc < 2) return EMPTY

        val chosen = chooseRule(t, cIdx, cMask, nc, user != null)
        if (chosen == 0) return EMPTY
        val selMask = chosen or R_K3
        val notes = if (chosen != R_K3) authorNotes(cMask, cTitle, nc, chosen) else null
        var sel = IntArray(nc)
        var selTitle = arrayOfNulls<String>(nc)
        var ns = 0
        for (k in 0 until nc) {
            if (cMask[k] and selMask != 0 && (notes == null || !notes[k])) {
                sel[ns] = cIdx[k]
                selTitle[ns] = cTitle[k]
                ns++
            }
        }
        sel = sel.copyOf(ns)
        selTitle = selTitle.copyOf(ns)
        val keep = prune(t, sel, selTitle)
        var n = 0
        for (k in 0 until ns) if (keep[k]) n++
        if (n < 2) return EMPTY
        val lines = IntArray(n)
        val titles = Array(n) { "" }
        var q = 0
        for (k in 0 until ns) {
            if (!keep[k]) continue
            lines[q] = sel[k]
            titles[q] = selTitle[k] ?: ""
            q++
        }
        return Result(lines, titles)
    }

    private val EMPTY = Result(IntArray(0), emptyArray())

    /** User regex (find semantics); a line the regex engine fails on simply doesn't match. */
    private fun userMatches(user: Pattern, cand: String): Boolean = try {
        user.matcher(cand).find()
    } catch (_: Exception) {
        false
    } catch (_: StackOverflowError) {
        false
    }

    /** Bitmask of the built-in rules matching [s] (already trimmed and normalised). */
    internal fun builtinMask(s: String): Int {
        if (s.isEmpty()) return 0
        var mask = 0
        var k = 0
        while (k < s.length && (isOpener(s[k]) || s[k] == ' ')) k++
        val c0 = if (k < s.length) s[k] else ' '
        val first = s[0]
        if (c0 in '0'..'9' || c0 == '제' || c0 == '第') {
            if (K1.matcher(s).matches()) mask = mask or R_K1
        }
        if (c0 == '#' || c0 == 'E' || c0 == 'e' || c0 == 'C' || c0 == 'c') {
            if (K2.matcher(s).matches()) mask = mask or R_K2
        }
        if (isSpecialInitial(c0)) {
            if (K3.matcher(s).matches()) mask = mask or R_K3
        }
        if (first in '0'..'9') {
            if (K4.matcher(s).matches()) mask = mask or R_K4
        }
        if (first == '=' || first == '-' || first == '*' || first == '~' || first == '#') {
            if (K5.matcher(s).matches()) mask = mask or R_K5
        }
        if (s[s.length - 1] == '화' && mask and R_K1 == 0) {
            if (K6.matcher(s).matches()) mask = mask or R_K6
        }
        return mask
    }

    private fun isOpener(c: Char): Boolean = when (c) {
        '<', '〈', '《', '[', '【', '「', '『', '(', '（' -> true
        else -> false
    }

    private fun isSpecialInitial(c: Char): Boolean = when (c) {
        '프', '에', '서', '종', '외', '번', '특', '후', '막', '작', '완', 'P', 'p', 'E', 'e', '序', '終' -> true
        else -> false
    }

    /** Cheap structural check on the raw trimmed line before any regex runs. */
    private fun prefilter(a: CharArray, s: Int, e: Int): Boolean {
        var k = s
        while (k < e && (isOpener(a[k]) || TxtChars.isWs(a[k]))) k++
        if (k >= e) return false
        val c0 = a[k]
        if (c0 in '0'..'9' || c0 in '０'..'９' || c0 == '제' || c0 == '第' || c0 == '#' || c0 == 'C' || c0 == 'c') return true
        if (isSpecialInitial(c0)) return true
        val f = a[s]
        if (f == '=' || f == '-' || f == '*' || f == '~') return true
        return a[e - 1] == '화'
    }

    /** Trimmed line with U+3000 / NBSP as spaces and fullwidth digits as ASCII (for matching only). */
    private fun candidateString(a: CharArray, s: Int, e: Int): String {
        val out = CharArray(e - s)
        for (k in s until e) {
            var c = a[k]
            if (c == '　' || c == ' ') c = ' ' else if (c in '０'..'９') c = '0' + (c - '０')
            out[k - s] = c
        }
        return String(out)
    }

    /**
     * Sentence-like endings are never headings: Hangul + '.' (except a numbered unit such as "제1화."), or a
     * quote closed after ? ! . … whose opening quote is not on the line (the tail of a dialogue line). A quoted
     * chapter title such as `제12화 “누구세요?”` keeps its opening quote and is accepted.
     */
    internal fun rejectEnding(t: String): Boolean {
        val n = t.length
        if (n < 2) return false
        val last = t[n - 1]
        val prev = t[n - 2]
        if (last == '.' && prev in '가'..'힣') {
            return !(n >= 3 && isUnit(prev) && t[n - 3] in '0'..'9')
        }
        if ((last == '”' || last == '"' || last == '’') && (prev == '?' || prev == '!' || prev == '.' || prev == '…')) {
            val open = when (last) {
                '”' -> '“'
                '’' -> '‘'
                else -> '"'
            }
            return t.lastIndexOf(open, n - 2) < 0
        }
        return false
    }

    private fun isUnit(c: Char): Boolean = when (c) {
        '화', '장', '회', '편', '부', '권', '막', '절' -> true
        else -> false
    }

    internal fun collapseWs(s: String): String {
        val sb = StringBuilder(s.length)
        var sp = false
        for (c in s) {
            if (TxtChars.isWs(c) || c == '\t') {
                sp = sb.isNotEmpty()
            } else {
                if (sp) sb.append(' ')
                sp = false
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Picks the rule that defines chapters: the user rule if it matches at least twice; else the built-in rule
     * with the most matches spaced > 1000 chars apart (ties: K1 > K2 > K6 > K5). K4 wins only with >= 3 spaced
     * matches, strictly more than every other rule, and when most of its matches are isolated (not lists).
     * Returns 0 when nothing qualifies (specials alone may still form chapters: returns R_K3 then).
     */
    private fun chooseRule(t: LineTable, idx: IntArray, mask: IntArray, n: Int, hasUser: Boolean): Int {
        if (hasUser) {
            var c = 0
            for (k in 0 until n) if (mask[k] and R_USER != 0) c++
            if (c >= 2) return R_USER
        }
        val order = intArrayOf(R_K1, R_K2, R_K6, R_K5)
        var best = 0
        var bestCount = 0
        for (r in order) {
            val c = spacedCount(t, idx, mask, n, r)
            if (c > bestCount) {
                best = r
                bestCount = c
            }
        }
        val k4 = spacedCount(t, idx, mask, n, R_K4)
        if (k4 >= 3 && k4 > bestCount) {
            var total = 0
            for (k in 0 until n) if (mask[k] and R_K4 != 0) total++
            if (k4 * 2 >= total) {
                best = R_K4
                bestCount = k4
            }
        }
        if (bestCount >= 2) return best
        var specials = 0
        for (k in 0 until n) if (mask[k] and R_K3 != 0) specials++
        return if (specials >= 2) R_K3 else 0
    }

    /**
     * A5: web-novel dumps put an author note ("작가의 말", "작가 후기", "후기", "완결 후기") after many episodes. Such a
     * note belongs to its episode: it must not become a TOC entry or start a new page. With 3 or more note
     * candidates, every note before the last heading of the [chosen] rule is dropped; a note after it is dropped
     * too when its kind already recurred (the last episode's own note), while a different kind there stays (a
     * closing "완결 후기" after per-episode "작가의 말"s). Notes the user regex matches stay; 프롤로그, 에필로그,
     * 서장, 종장, 서문, 외전, 번외, 후일담 and 막간 are not notes. Returns the candidates to drop, or null for none.
     */
    private fun authorNotes(mask: IntArray, titles: Array<String?>, n: Int, chosen: Int): BooleanArray? {
        val kind = IntArray(n)
        var notes = 0
        var lastChosen = -1
        for (k in 0 until n) {
            val m = mask[k]
            if (m and R_K3 != 0 && !(chosen == R_USER && m and R_USER != 0)) {
                kind[k] = noteKind(titles[k])
                if (kind[k] != 0) notes++
            }
            if (kind[k] == 0 && m and chosen != 0) lastChosen = k
        }
        if (notes < 3 || lastChosen < 0) return null
        val drop = BooleanArray(n)
        var recurring = 0 // bit per note kind seen before the last chosen heading
        for (k in 0 until n) {
            if (kind[k] == 0) continue
            val bit = 1 shl kind[k]
            if (k < lastChosen) {
                drop[k] = true
                recurring = recurring or bit
            } else if (recurring and bit != 0) {
                drop[k] = true
            }
        }
        return drop
    }

    /** 1..4 for the author-note kinds (by title prefix, ignoring spaces and brackets), else 0. */
    internal fun noteKind(title: String?): Int {
        val k = normKey(title)
        return when {
            k.startsWith("작가의말") -> 1
            k.startsWith("작가후기") -> 2
            k.startsWith("완결후기") -> 3
            k.startsWith("후기") -> 4
            else -> 0
        }
    }

    private fun spacedCount(t: LineTable, idx: IntArray, mask: IntArray, n: Int, rule: Int): Int {
        var count = 0
        var last = Int.MIN_VALUE
        for (k in 0 until n) {
            if (mask[k] and rule == 0) continue
            val pos = t.rawPos[idx[k]]
            if (last == Int.MIN_VALUE || pos - last > 1000) {
                count++
                last = pos
            }
        }
        return count
    }

    /**
     * Returns which of the selected headings survive:
     * - a heading repeated right after an equal heading (no body between) is a duplicated title: drop it;
     * - a run of >= 3 headings with no body between them is a TOC listing. If a later heading in the run
     *   restarts the listing (same chapter key as the run's first), everything before it is dropped and the
     *   rest is examined again; otherwise runs of >= 5 are dropped entirely and shorter runs keep only their
     *   last heading when body text follows it.
     */
    private fun prune(t: LineTable, lines: IntArray, titles: Array<String?>): BooleanArray {
        val n = lines.size
        val keep = BooleanArray(n) { true }
        if (n == 0) return keep
        // bodyBefore[k]: is there body text between heading k-1 and heading k?
        val bodyBefore = BooleanArray(n)
        bodyBefore[0] = hasBody(t, 0, lines[0])
        for (k in 1 until n) bodyBefore[k] = hasBody(t, lines[k - 1] + 1, lines[k])
        val bodyAfterLast = hasBody(t, lines[n - 1] + 1, t.count)

        // duplicated titles
        for (k in 1 until n) {
            if (!bodyBefore[k] && keep[k - 1] && normKey(titles[k]) == normKey(titles[k - 1])) {
                keep[k] = false
            }
        }
        // runs of kept headings with no body between them
        var k = 0
        while (k < n) {
            if (!keep[k]) { k++; continue }
            val run = ArrayList<Int>()
            run.add(k)
            var j = k + 1
            while (j < n) {
                if (bodyBefore[j]) break
                if (keep[j]) run.add(j)
                j++
            }
            pruneRun(run, titles, keep, followedByBody = j < n || bodyAfterLast)
            k = j
        }
        return keep
    }

    private fun pruneRun(run: List<Int>, titles: Array<String?>, keep: BooleanArray, followedByBody: Boolean) {
        var from = 0
        while (run.size - from >= 3) {
            val firstKey = chapterKey(titles[run[from]])
            var restart = -1
            for (q in run.size - 1 downTo from + 1) {
                if (chapterKey(titles[run[q]]) == firstKey) { restart = q; break }
            }
            if (restart > 0) {
                for (q in from until restart) keep[run[q]] = false
                from = restart
                continue
            }
            val size = run.size - from
            val lastIdx = if (size < 5 && followedByBody) run.size - 1 else run.size
            for (q in from until lastIdx) keep[run[q]] = false
            return
        }
    }

    private fun hasBody(t: LineTable, from: Int, to: Int): Boolean {
        val skip = LineFlags.BLANK or LineFlags.DELETED
        for (i in from until to) if (t.flags[i] and skip == 0) return true
        return false
    }

    private fun normKey(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length)
        for (c in s) if (!TxtChars.isWs(c) && !isOpener(c) && !isCloser(c)) sb.append(c)
        return sb.toString()
    }

    private fun isCloser(c: Char): Boolean = when (c) {
        '>', '〉', '》', ']', '】', '」', '』', ')', '）' -> true
        else -> false
    }

    /** Chapter identity for listing-restart detection: text up to the first number and its unit, or all. */
    private fun chapterKey(s: String?): String {
        val k = normKey(s)
        var i = 0
        while (i < k.length && k[i] !in '0'..'9') i++
        if (i >= k.length) return k
        while (i < k.length && k[i] in '0'..'9') i++
        if (i < k.length && k[i].code >= 0x80) i++
        return k.substring(0, i)
    }
}
