package com.ggumtak.readeraplus.format.txt

/*
 * Literal prefilter for the replace rules (T1-10). Most lines match none of the rules, and a rule such as
 * `^.*(무단\s*(전재|배포)|불펌).*$` costs a backtracking scan of the whole line to find that out. A pattern usually
 * cannot match unless the line contains one of a few literal strings ("무단" or "불펌" here): checking that first
 * skips the regex, and when no rule can match, [LineGate] even skips making a String of the line.
 *
 * Correctness rule: a literal set is reported only when EVERY match of the pattern contains one of its strings.
 * Anything the analysis does not fully understand (inline flags, \u / \x escapes, nested classes, back references,
 * quantified \Q…\E, surrogates) makes it report nothing for that pattern, and the rule then always runs its regex.
 */

/** Required literals of a regex (see the file comment). Pure; unit-tested. */
internal object RegexLiterals {
    /** Longer lists are cut: the extra sets would rarely reject a line the others let through. */
    private const val MAX_SETS = 4
    /** A repeated literal char (`\.{3,}` → "...") is spelled out at most this many times. */
    private const val MAX_REPEAT = 8

    /**
     * Sets of literals such that every match of [pattern] contains at least one string of EACH set; the set to test
     * first (the most selective) comes first. Empty when nothing is known (the pattern is always tried).
     */
    fun required(pattern: String): List<Array<String>> = try {
        val p = Parser(pattern)
        val sets = p.alternation()
        if (p.pos != pattern.length) emptyList() else order(sets)
    } catch (_: Unsupported) {
        emptyList()
    }

    /** Best set first: the longest shortest literal (up to 3 chars), then the earliest in the pattern. */
    private fun order(sets: List<Set<String>>): List<Array<String>> {
        val distinct = LinkedHashSet(sets.filter { it.isNotEmpty() })
        val sorted = distinct.withIndex().sortedWith(compareBy({ -minOf(3, minLen(it.value)) }, { it.index }))
        return sorted.take(MAX_SETS).map { it.value.toTypedArray() }
    }

    private fun minLen(s: Set<String>): Int = s.minOf { it.length }

    /** The best single set of [sets] (as [order] ranks them), or null. */
    private fun best(sets: List<Set<String>>): Set<String>? {
        var best: Set<String>? = null
        var bestLen = -1
        for (s in sets) {
            if (s.isEmpty()) continue
            val l = minOf(3, minLen(s))
            if (l > bestLen) {
                best = s
                bestLen = l
            }
        }
        return best
    }

    private class Unsupported : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Recursive descent over the subset of the regex syntax the analysis understands. */
    private class Parser(private val p: String) {
        var pos = 0

        /** `a|b|c` up to ')' or the end: the sets every match of the whole alternation must satisfy. */
        fun alternation(): List<Set<String>> {
            val alts = ArrayList<List<Set<String>>>()
            alts.add(sequence())
            while (pos < p.length && p[pos] == '|') {
                pos++
                alts.add(sequence())
            }
            if (alts.size == 1) return alts[0]
            // One of the branches matches: the union of one required set per branch is required.
            val union = LinkedHashSet<String>()
            for (a in alts) union.addAll(best(a) ?: return emptyList())
            return listOf(union)
        }

        private fun sequence(): List<Set<String>> {
            val out = ArrayList<Set<String>>()
            val run = StringBuilder()
            fun flush(extra: String = "") {
                val s = run.toString() + extra
                if (s.isNotEmpty()) out.add(setOf(s))
                run.setLength(0)
            }
            while (pos < p.length) {
                val c = p[pos]
                if (c == '|' || c == ')') break
                if (Character.isSurrogate(c)) throw Unsupported()
                when (c) {
                    '(' -> {
                        val sets = group()
                        flush()
                        val q = quantifier()
                        if (q == null || q.first >= 1) out.addAll(sets)
                    }
                    '\\' -> {
                        if (p.startsWith("\\Q", pos)) {
                            val end = p.indexOf("\\E", pos + 2)
                            val lit = if (end < 0) p.substring(pos + 2) else p.substring(pos + 2, end)
                            if (lit.any { Character.isSurrogate(it) }) throw Unsupported()
                            pos = if (end < 0) p.length else end + 2
                            if (isQuantifier()) throw Unsupported()
                            run.append(lit)
                        } else {
                            val lit = escape()
                            if (lit == null) {
                                flush()
                                quantifier()
                            } else {
                                literal(lit, run, ::flush)
                            }
                        }
                    }
                    '[' -> {
                        val lit = charClass()
                        if (lit == null) {
                            flush()
                            quantifier()
                        } else {
                            literal(lit, run, ::flush)
                        }
                    }
                    '.', '^', '$' -> {
                        pos++
                        flush()
                        quantifier()
                    }
                    '*', '+', '?', '{' -> throw Unsupported() // a quantifier without an atom (invalid anyway)
                    else -> {
                        pos++
                        literal(c, run, ::flush)
                    }
                }
            }
            flush()
            return out
        }

        /** A literal char atom and its quantifier: extends [run], or ends it where the char may be absent. */
        private fun literal(c: Char, run: StringBuilder, flush: (String) -> Unit) {
            val q = quantifier()
            when {
                q == null -> run.append(c)
                q.first == 0 -> flush("")
                else -> {
                    val rep = c.toString().repeat(minOf(q.first, MAX_REPEAT))
                    if (q.first == q.second && q.first <= MAX_REPEAT) {
                        run.append(rep)
                    } else {
                        // "ab{2,}c": "abb" and "bbc" both occur (the last two b's are followed by c)
                        flush(rep)
                        run.append(rep)
                    }
                }
            }
        }

        /** `( … )` at [pos]: the sets of its content (none for lookarounds). */
        private fun group(): List<Set<String>> {
            pos++ // '('
            var capture = true
            if (p.startsWith("?", pos)) {
                when {
                    p.startsWith("?:", pos) || p.startsWith("?>", pos) -> pos += 2
                    p.startsWith("?=", pos) || p.startsWith("?!", pos) -> { pos += 2; capture = false }
                    p.startsWith("?<=", pos) || p.startsWith("?<!", pos) -> { pos += 3; capture = false }
                    p.startsWith("?<", pos) -> {
                        val close = p.indexOf('>', pos + 2)
                        if (close < 0 || close == pos + 2) throw Unsupported()
                        for (k in pos + 2 until close) if (!p[k].isLetterOrDigit()) throw Unsupported()
                        pos = close + 1
                    }
                    else -> throw Unsupported() // inline flags (?i) (?x) …, comments: literals may not be literal
                }
            }
            val sets = alternation()
            if (pos >= p.length || p[pos] != ')') throw Unsupported()
            pos++
            return if (capture) sets else emptyList()
        }

        /**
         * `\x` at [pos]: the literal char for an escaped non-alphanumeric char, null for a class / anchor escape
         * that takes no further chars; anything else (\u, \x, \0, \c, \N, \k, back references…) is unsupported.
         */
        private fun escape(): Char? {
            if (pos + 1 >= p.length) throw Unsupported()
            val c = p[pos + 1]
            if (!c.isLetterOrDigit()) {
                if (Character.isSurrogate(c)) throw Unsupported()
                pos += 2
                return c
            }
            when (c) {
                's', 'S', 'd', 'D', 'w', 'W', 'b', 'B', 'h', 'H', 'v', 'V', 't', 'n', 'r', 'f', 'A', 'z', 'Z', 'G', 'R', 'X' -> {
                    pos += 2
                    return null
                }
                'p', 'P' -> {
                    if (pos + 2 < p.length && p[pos + 2] == '{') {
                        val close = p.indexOf('}', pos + 3)
                        if (close < 0) throw Unsupported()
                        pos = close + 1
                    } else {
                        if (pos + 2 >= p.length || !p[pos + 2].isLetter()) throw Unsupported()
                        pos += 3
                    }
                    return null
                }
                else -> throw Unsupported()
            }
        }

        /**
         * `[ … ]` at [pos]: the member of a one-member class such as `[>]` (literal), else null (any char of a set).
         * Nested classes, a leading ']' and \Q inside are unsupported (engines differ there).
         */
        private fun charClass(): Char? {
            val start = pos
            pos++ // '['
            if (pos < p.length && p[pos] == '^') pos++
            if (pos < p.length && p[pos] == ']') throw Unsupported()
            while (true) {
                if (pos >= p.length) throw Unsupported()
                val c = p[pos]
                when (c) {
                    ']' -> break
                    '[' -> throw Unsupported()
                    '\\' -> {
                        if (pos + 1 >= p.length || p[pos + 1] == 'Q') throw Unsupported()
                        pos += 2
                    }
                    else -> pos++
                }
            }
            val body = p.substring(start + 1, pos)
            pos++ // ']'
            if (body.length == 1 && body[0] != '^' && body[0] != '-' && body[0] != '&' && !Character.isSurrogate(body[0])) return body[0]
            if (body.length == 2 && body[0] == '\\' && !body[1].isLetterOrDigit() && !Character.isSurrogate(body[1])) return body[1]
            return null
        }

        private fun isQuantifier(): Boolean =
            pos < p.length && (p[pos] == '*' || p[pos] == '+' || p[pos] == '?' || p[pos] == '{')

        /** Quantifier at [pos] as (min, max; max = Int.MAX_VALUE for no limit), or null when there is none. */
        private fun quantifier(): Pair<Int, Int>? {
            if (!isQuantifier()) return null
            val q = when (p[pos]) {
                '*' -> { pos++; 0 to Int.MAX_VALUE }
                '+' -> { pos++; 1 to Int.MAX_VALUE }
                '?' -> { pos++; 0 to 1 }
                else -> counted()
            }
            if (pos < p.length && (p[pos] == '?' || p[pos] == '+')) pos++ // lazy / possessive
            if (isQuantifier()) throw Unsupported() // stacked quantifiers: engines differ
            return q
        }

        /** `{n}`, `{n,}` or `{n,m}`; anything else is unsupported (a literal '{' in some engines, an error in others). */
        private fun counted(): Pair<Int, Int> {
            val close = p.indexOf('}', pos)
            if (close < 0) throw Unsupported()
            val body = p.substring(pos + 1, close)
            val comma = body.indexOf(',')
            val lo = (if (comma < 0) body else body.substring(0, comma)).toIntOrNull() ?: throw Unsupported()
            val hi = when {
                comma < 0 -> lo
                comma == body.length - 1 -> Int.MAX_VALUE
                else -> body.substring(comma + 1).toIntOrNull() ?: throw Unsupported()
            }
            if (lo < 0 || hi < lo) throw Unsupported()
            pos = close + 1
            return lo to hi
        }
    }
}

/**
 * Whole-line gate over the rules' first literal sets: false when the line holds no string of any rule's first set,
 * so no rule can match and the line needs no String at all. A bit test per char pair (the first pair of each
 * literal), and per char for one-char literals; [scanLine] folds it into the newline search the line table does
 * anyway, branch-free, so a line costs one pass over its chars.
 */
internal class LineGate private constructor(
    /** Bit per hashed char pair that starts one of the literals. */
    private val pairs: LongArray,
    /** Bit per char that is a whole one-char literal; null when there is none. */
    private val singles: LongArray?,
) {
    /** True when line `a[s, e)` may hold one of the literals. */
    fun mayMatch(a: CharArray, s: Int, e: Int): Boolean {
        val bits = pairs
        val one = singles
        var prev = 0
        for (k in s until e) {
            val c = a[k].code
            val h = pairHash(prev, c)
            if (bits[h ushr 6] and (1L shl h) != 0L) return true
            if (one != null && one[c ushr 6] and (1L shl c) != 0L) return true
            prev = c
        }
        return false
    }

    /**
     * Finds the end of the line starting at [from] (the index of the next [nl] before [n], else [n]) and tests the
     * line on the way: returns that end in the low 32 bits, bit 32 set when the line may hold a literal.
     */
    fun scanLine(a: CharArray, from: Int, n: Int, nl: Char): Long {
        val bits = pairs
        val one = singles
        val stop = nl.code
        var acc = 0L
        var prev = 0
        var k = from
        if (one == null) { // the usual case
            while (k < n) {
                val c = a[k].code
                if (c == stop) break
                val h = pairHash(prev, c)
                acc = acc or (bits[h ushr 6] ushr h)
                prev = c
                k++
            }
        } else {
            while (k < n) {
                val c = a[k].code
                if (c == stop) break
                val h = pairHash(prev, c)
                acc = acc or (bits[h ushr 6] ushr h) or (one[c ushr 6] ushr c)
                prev = c
                k++
            }
        }
        return k.toLong() or ((acc and 1L) shl 32)
    }

    companion object {
        /** Hash of the pair (a, b). A line's first char is paired with 0 (at worst a false "may match"). */
        private fun pairHash(a: Int, b: Int): Int = (a * 0x9E3B + b) and 0xFFFF

        /** The gate for rules whose first sets are [firstSets]; null when some rule has no set (it always runs). */
        fun of(firstSets: List<Array<String>?>): LineGate? {
            if (firstSets.isEmpty()) return null
            val pairs = LongArray(1024)
            var singles: LongArray? = null
            for (set in firstSets) {
                if (set == null || set.isEmpty()) return null
                for (lit in set) {
                    if (lit.isEmpty()) return null
                    if (lit.length == 1) {
                        val c = lit[0].code
                        val one = singles ?: LongArray(1024).also { singles = it }
                        one[c ushr 6] = one[c ushr 6] or (1L shl c)
                    } else {
                        val h = pairHash(lit[0].code, lit[1].code)
                        pairs[h ushr 6] = pairs[h ushr 6] or (1L shl h)
                    }
                }
            }
            return LineGate(pairs, singles)
        }
    }
}
