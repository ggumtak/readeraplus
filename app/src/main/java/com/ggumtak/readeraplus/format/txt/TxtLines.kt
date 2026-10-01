package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import java.util.regex.Matcher
import java.util.regex.Pattern

/*
 * Line layer of the TXT parser: splits decoded text into lines (cutting overlong lines into segments),
 * runs the per-line pipeline in place and records per-line flags in primitive arrays.
 *
 * Pipeline per line: strip the CR of CRLF -> replace rules -> tabs / stray line separators to space, drop other
 * control chars -> trailing whitespace trim -> leading whitespace strip (txtStripIndent). A non-blank line that
 * the replace rules turn blank is DELETED (it vanishes instead of becoming a blank line).
 */

/** Lines longer than this (chars) are cut into segments so a file without line breaks still pages fast. */
internal const val MAX_SEGMENT_CHARS = 8192

internal object LineFlags {
    const val BLANK = 1
    const val DELETED = 2
    const val SCENE = 4
    const val TERMINAL = 8
    const val INDENT = 16
    const val HEADING = 32
    /** Part of an overlong source line that was cut into segments: never hard-wrap joined. */
    const val SEG = 64
    /** A segment other than the first one of its source line: rendered as a soft line break. */
    const val CONT = 128
    /** Content lives in [LineTable.ov] (rewritten by a replace rule). */
    const val OV = 256
    const val CJK_FIRST = 512
    const val CJK_LAST = 1024
}

/** Char classification helpers (no allocation, hot loops). */
internal object TxtChars {
    /** [CLASS] bits. */
    const val C_WIDE = 1
    const val C_WS = 2
    /** Dropped from the text: C0 / C1 controls (except tab / LF / NEL), DEL, BOM, U+FFFC. */
    const val C_DROP = 4
    /** Replaced by a space (tab, stray LF from replace rules, NEL, U+2028/2029); also whitespace. */
    const val C_SPACE = 8

    /** Class of every UTF-16 code unit: one array load per char in the line pipeline. */
    @JvmField
    val CLASS: ByteArray = ByteArray(65536).also { t ->
        for (x in 0 until 65536) {
            val c = x.toChar()
            var v = 0
            if (isWide(c)) v = v or C_WIDE
            if (isWs(c)) v = v or C_WS
            if ((x < 0x20 && x != 0x09 && x != 0x0A) || x == 0x7F || (x in 0x80..0x9F) || x == 0xFEFF || c == OBJECT_CHAR) {
                v = C_DROP
            }
            // Stray line/paragraph separators inside a line (a lone CR in an LF file, NEL, U+2028/2029) separate
            // words; dropping them would glue two sentences together.
            if (x == 0x09 || x == 0x0A || x == 0x0D || x == 0x85 || x == 0x2028 || x == 0x2029) v = C_SPACE or C_WS
            t[x] = v.toByte()
        }
    }

    fun isWs(c: Char): Boolean =
        c == ' ' || (c >= ' ' && (c == ' ' || c == '　' || (c in ' '..'​') ||
            c == ' ' || c == ' ' || c == ' '))

    /** East Asian wide (2 columns): Hangul, CJK, fullwidth forms. */
    fun isWide(c: Char): Boolean {
        val x = c.code
        if (x < 0x1100) return false
        return x <= 0x115F || (x in 0x2E80..0xA4CF && x != 0x303F) || (x in 0xAC00..0xD7A3) ||
            (x in 0xF900..0xFAFF) || (x in 0xFE30..0xFE4F) || (x in 0xFF00..0xFF60) || (x in 0xFFE0..0xFFE6)
    }

    /** CJK ideograph or kana: joined hard-wrapped lines get no space between two of these. */
    fun isCjkIdeoKana(c: Char): Boolean {
        val x = c.code
        return (x in 0x3040..0x30FF) || (x in 0x31F0..0x31FF) || (x in 0x3400..0x4DBF) ||
            (x in 0x4E00..0x9FFF) || (x in 0xF900..0xFAFF) || (x in 0xFF66..0xFF9F)
    }

    /** Line-final chars that end a sentence / quote: such a line is never joined with the next one. */
    fun isTerminal(c: Char): Boolean = when (c) {
        '.', '?', '!', '…', '"', '”', '’', '\'', '」', '』', ')', '~', '。', '？', '！', '～', '）',
        '>', '〉', '》', ']', '］', '】', '〕', '♡', '♥', '‥', ':' -> true
        else -> false
    }

    fun isSentenceEnd(c: Char): Boolean = when (c) {
        '.', '?', '!', '…', '。', '？', '！', '"', '”', '’', '」', '』' -> true
        else -> false
    }

    private fun isStrongMarker(c: Char): Boolean = when (c) {
        '*', '＊', '◇', '◆', '○', '●', '§', '※', '☆', '★', '◈', '◎', '□', '■', '△', '▲', '▽', '▼',
        '♡', '♥', '♣', '♠', '◯', '❖', '✽', '✱', '✳', '✻', '⁂', '♧', '♤', '◐', '◑', '⊙' -> true
        else -> false
    }

    private fun isWeakMarker(c: Char): Boolean = when (c) {
        '-', '=', '~', '～', 'ㅡ', '_', '─', '━', '—', '–', '·', '•', '+', '＝', '－', 'o', 'O', '#', '＃', '°' -> true
        else -> false
    }

    /**
     * Scene-break marker line (`***`, `* * *`, `---`, `===`, `◇◇◇`, `ㅡㅡㅡ`, `~~~`, `ooo`, `○○○`, `§`, …):
     * only marker symbols and spaces; at least 3 symbols, or 1+ when a "strong" symbol (*, ◇, §, …) is used.
     */
    fun isSceneMarker(a: CharArray, s: Int, e: Int): Boolean {
        if (e - s > 40 || e <= s) return false
        var n = 0
        var strong = false
        for (k in s until e) {
            val c = a[k]
            if (isWs(c)) continue
            if (isStrongMarker(c)) strong = true else if (!isWeakMarker(c)) return false
            n++
        }
        return n >= 3 || (n >= 1 && strong)
    }
}

/**
 * Compiled replace rules (`pattern => replacement`, `#` comment lines). Patterns are immutable and shared;
 * [applier] creates the per-thread matchers. Each rule carries the literals one of which a line must contain for
 * the rule to match ([RegexLiterals]): the regex runs only on lines that have them, and when every rule has such
 * literals a [LineGate] passes most lines through without making a String of them (T1-10: the cleanup packs).
 */
internal class ReplaceRules private constructor(
    private val patterns: Array<Pattern>,
    private val replacements: Array<String>,
    /** Per rule: sets of literals, each of which must have a string in the line for the rule to match. */
    private val needs: Array<List<Array<String>>>,
) {
    val size: Int get() = patterns.size

    /** Null when some rule has no required literal (every line then goes through the regexes). */
    internal val gate: LineGate? = LineGate.of(needs.map { it.firstOrNull() })

    /** Stateful applier (reuses one Matcher per rule). Not thread-safe; create one per parse. */
    inner class Applier {
        private val matchers: Array<Matcher> = Array(patterns.size) { patterns[it].matcher("") }

        /**
         * The rules applied to line `a[s, e)`: null when they leave it unchanged (most lines: those without any
         * rule's literals are rejected by the gate without allocating), else the rewritten line. Same outcome as
         * [apply] on that text.
         */
        fun apply(a: CharArray, s: Int, e: Int): String? {
            val g = gate
            if (g != null && !g.mayMatch(a, s, e)) return null
            return applyPassed(a, s, e)
        }

        /** [apply] for a line the [gate] already let through ([LineGate.scanLine]). */
        fun applyPassed(a: CharArray, s: Int, e: Int): String? {
            if (e <= s) return null
            val str = String(a, s, e - s)
            val r = apply(str)
            return if (r === str || r == str) null else r
        }

        /**
         * Applies all rules in order; returns [line] itself when nothing matched. Never throws: a rule the regex
         * engine fails on (e.g. stack overflow on a pathological line) leaves that line unchanged. The outcome
         * depends only on the line, so the whole-file and per-section passes agree.
         */
        fun apply(line: String): String {
            var s = line
            for (r in matchers.indices) {
                if (!hasNeeds(s, needs[r])) continue
                val m = matchers[r]
                s = try {
                    m.reset(s)
                    if (!m.find()) continue
                    m.reset()
                    m.replaceAll(replacements[r])
                } catch (_: Exception) {
                    s
                } catch (_: StackOverflowError) {
                    s
                }
            }
            return s
        }
    }

    fun applier(): Applier = Applier()

    /** True when [line] holds a string of every set in [sets] (the rule may match). */
    private fun hasNeeds(line: String, sets: List<Array<String>>): Boolean {
        for (set in sets) {
            var found = false
            for (lit in set) {
                if (line.contains(lit)) {
                    found = true
                    break
                }
            }
            if (!found) return false
        }
        return true
    }

    companion object {
        /** Parses the rule text; returns null when there is no valid rule. */
        fun parse(text: String): ReplaceRules? {
            if (text.isBlank()) return null
            val ps = ArrayList<Pattern>()
            val rs = ArrayList<String>()
            val ns = ArrayList<List<Array<String>>>()
            for (raw in text.split('\n')) {
                val line = raw.trimEnd('\r')
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val arrow = line.indexOf("=>")
                if (arrow < 0) continue
                val pat = line.substring(0, arrow).trim()
                val rep = line.substring(arrow + 2).trim()
                if (pat.isEmpty()) continue
                try {
                    val p = Pattern.compile(pat)
                    // An invalid replacement would throw on every matching line (slow): skip the rule instead.
                    if (!validReplacement(rep, p.matcher("").groupCount(), pat)) continue
                    ps.add(p)
                    rs.add(rep)
                    ns.add(RegexLiterals.required(pat))
                } catch (_: Exception) {
                    // invalid pattern: skipped
                }
            }
            if (ps.isEmpty()) return null
            return ReplaceRules(ps.toTypedArray(), rs.toTypedArray(), ns.toTypedArray())
        }

        /**
         * Whether [rep] is a valid `Matcher.replaceAll` replacement for a pattern ([source]) with [groups]
         * capturing groups: `\x` escapes, `$n` with 0 <= n <= groups, `${name}` naming a group of [source].
         * Stricter than Android's lenient parser, so a rule behaves the same everywhere.
         */
        internal fun validReplacement(rep: String, groups: Int, source: String): Boolean {
            var i = 0
            val n = rep.length
            while (i < n) {
                when (rep[i]) {
                    '\\' -> {
                        if (i + 1 >= n) return false
                        i += 2
                    }
                    '$' -> {
                        if (i + 1 >= n) return false
                        val d = rep[i + 1]
                        if (d == '{') {
                            val close = rep.indexOf('}', i + 2)
                            if (close <= i + 2) return false
                            if (!source.contains("(?<" + rep.substring(i + 2, close) + ">")) return false
                            i = close + 1
                        } else {
                            if (d !in '0'..'9' || d - '0' > groups) return false
                            i += 2
                        }
                    }
                    else -> i++
                }
            }
            return true
        }
    }
}

/** Parameters of the line pipeline. */
internal class LineConfig(
    val stripIndent: Boolean,
    val rules: ReplaceRules?,
    /** Cut lines longer than [MAX_SEGMENT_CHARS] (requires a decoder that supports byte advancing). */
    val segment: Boolean,
)

/** Byte-offset tracking for the global pass (each line's first byte in the file). */
internal class ByteMap(val bytes: ByteArray, val dataStart: Int, val dataEnd: Int, val decoder: TxtDecoder)

/**
 * Lines of a decoded text range with their normalised content ranges and flags. Content of line i is
 * `arr(i)[start[i] until end[i]]`.
 */
internal class LineTable(
    /** Decoded text; normalised in place. */
    val buf: CharArray,
    capacity: Int,
) {
    var count = 0
    var start = IntArray(capacity)
    var end = IntArray(capacity)
    var flags = IntArray(capacity)
    var width = IntArray(capacity)
    /** Raw char position of each line in [buf] (global pass only; used for chapter-spacing scores). */
    var rawPos = IntArray(capacity)
    /** First byte of each line in the file (global pass only). */
    var byteStart: IntArray? = null
    /** Storage for lines rewritten by replace rules. */
    var ov: CharArray = EMPTY
    var ovLen = 0

    fun arr(i: Int): CharArray = if (flags[i] and LineFlags.OV != 0) ov else buf

    fun length(i: Int): Int = end[i] - start[i]

    fun text(i: Int): String = String(arr(i), start[i], end[i] - start[i])

    fun has(i: Int, flag: Int): Boolean = flags[i] and flag != 0

    internal fun ensure(n: Int) {
        if (n <= start.size) return
        val cap = maxOf(n, start.size + (start.size shr 1) + 16)
        start = start.copyOf(cap)
        end = end.copyOf(cap)
        flags = flags.copyOf(cap)
        width = width.copyOf(cap)
        rawPos = rawPos.copyOf(cap)
        byteStart = byteStart?.copyOf(cap)
    }

    internal fun appendOv(s: String): Int {
        val need = ovLen + s.length
        if (need > ov.size) ov = ov.copyOf(maxOf(need, ov.size * 2, 256))
        s.toCharArray(ov, ovLen, 0, s.length)
        val at = ovLen
        ovLen = need
        return at
    }

    /**
     * Marks line [i] as a chapter heading and trims its leading whitespace (headings are always shown
     * trimmed, whatever txtStripIndent says).
     */
    fun markHeading(i: Int) {
        val a = arr(i)
        var s = start[i]
        val e = end[i]
        while (s < e && TxtChars.isWs(a[s])) s++
        start[i] = s
        flags[i] = flags[i] or LineFlags.HEADING
    }

    /** True if the content of line [i] equals [other] ignoring all whitespace. */
    fun equalsIgnoringWs(i: Int, other: String): Boolean {
        val a = arr(i)
        var p = start[i]
        val e = end[i]
        var q = 0
        val n = other.length
        while (true) {
            while (p < e && TxtChars.isWs(a[p])) p++
            while (q < n && TxtChars.isWs(other[q])) q++
            if (p >= e || q >= n) return p >= e && q >= n
            if (a[p] != other[q]) return false
            p++
            q++
        }
    }

    companion object {
        private val EMPTY = CharArray(0)

        /**
         * Splits buf[0, n) at [nl] into lines (a final newline does not start an extra empty line), cuts overlong
         * lines into segments, and runs the pipeline. [bm] enables byte-start tracking (global pass).
         */
        fun build(buf: CharArray, n: Int, nl: Char, cfg: LineConfig, bm: ByteMap?): LineTable {
            val cap = n / 48 + 16
            val t = LineTable(buf, cap)
            if (bm != null) t.byteStart = IntArray(cap)
            if (n == 0) return t
            val proc = LineProcessor(t, cfg)
            val segment = cfg.segment && (bm == null || bm.decoder.canAdvance)
            val gate = cfg.rules?.gate
            var cs = 0
            var bpos = bm?.dataStart ?: 0
            while (true) {
                var ce = cs
                // With replace rules, the literal gate runs in the same pass as the newline search.
                var may = true
                if (gate == null) {
                    while (ce < n && buf[ce] != nl) ce++
                } else {
                    val r = gate.scanLine(buf, cs, n, nl)
                    ce = r.toInt()
                    may = r ushr 32 != 0L
                }
                val lineByte = bpos
                if (bm != null) {
                    val nb = if (ce < n) bm.decoder.nextNewline(bm.bytes, bpos, bm.dataEnd, nl.code) else -1
                    bpos = if (nb < 0) bm.dataEnd else nb + bm.decoder.unitSize
                }
                // The CR of a CRLF ending is not content: measuring with it could cut off a CR-only (blank) last
                // segment, which KEEP mode would then show as a stray empty paragraph.
                val ceText = if (ce > cs && buf[ce - 1] == '\r') ce - 1 else ce
                if (segment && ceText - cs > MAX_SEGMENT_CHARS) {
                    var a = cs
                    var ab = lineByte
                    var segFlags = LineFlags.SEG
                    while (ceText - a > MAX_SEGMENT_CHARS) {
                        val b = chooseSplit(buf, a)
                        proc.add(a, b, ab, segFlags, may)
                        if (bm != null) ab = bm.decoder.advance(bm.bytes, ab, bm.dataEnd, b - a)
                        a = b
                        segFlags = LineFlags.SEG or LineFlags.CONT
                    }
                    proc.add(a, ce, ab, segFlags, may)
                } else {
                    proc.add(cs, ce, lineByte, 0, may)
                }
                if (ce >= n) break
                cs = ce + 1
                if (cs >= n) break
            }
            return t
        }

        /**
         * Split point of an overlong line starting at [a]: after a sentence end + space, else after a space,
         * within the last quarter of the maximum; else at the maximum (never inside a surrogate pair).
         * Depends only on buf[a + 3/4 max - 2, a + max], so it is identical whatever follows.
         */
        internal fun chooseSplit(buf: CharArray, a: Int): Int {
            val hi = a + MAX_SEGMENT_CHARS
            val lo = a + MAX_SEGMENT_CHARS * 3 / 4
            var p = hi
            while (p > lo) {
                if (buf[p - 1] == ' ' && TxtChars.isSentenceEnd(buf[p - 2])) return p
                p--
            }
            p = hi
            while (p > lo) {
                val c = buf[p - 1]
                if (c == ' ' || c == '　' || c == '\t') return p
                p--
            }
            p = hi
            if (Character.isLowSurrogate(buf[p]) && Character.isHighSurrogate(buf[p - 1])) p--
            return p
        }
    }
}

/** Runs the per-line pipeline and appends lines to a [LineTable]. */
private class LineProcessor(private val t: LineTable, private val cfg: LineConfig) {
    private val applier = cfg.rules?.applier()

    /** Adds line `buf[s0, e0)`; [rulesMay] false when the rules' literal gate showed that no rule can match. */
    fun add(s0: Int, e0: Int, byteStart: Int, segFlags: Int, rulesMay: Boolean) {
        val i = t.count
        t.ensure(i + 1)
        t.count = i + 1
        t.rawPos[i] = s0
        t.byteStart?.let { it[i] = byteStart }
        var arr = t.buf
        var s = s0
        var e = e0
        var f = segFlags
        if (e > s && arr[e - 1] == '\r') e--
        if (applier != null && rulesMay && e > s) {
            val r = applier.applyPassed(arr, s, e)
            if (r != null) {
                if (isBlank(r) && !isBlank(arr, s, e)) {
                    t.start[i] = s
                    t.end[i] = s
                    t.flags[i] = f or LineFlags.DELETED
                    t.width[i] = 0
                    return
                }
                s = t.appendOv(r)
                e = s + r.length
                arr = t.ov
                f = f or LineFlags.OV
            }
        }
        // Normalise in place: tabs/newlines -> space, drop other controls, BOM, object replacement char;
        // track the trailing-trim point, the first non-space and the column width in the same loop.
        var w = s
        var wid = 0
        var lastEnd = s
        var widAtLast = 0
        var lead = -1
        var widBeforeLead = 0
        val cls = TxtChars.CLASS
        for (k in s until e) {
            var c = arr[k]
            val v = cls[c.code].toInt()
            if (v == 0) {
                // plain narrow char (most ASCII, punctuation, Latin)
                arr[w++] = c
                wid++
                lastEnd = w
                widAtLast = wid
                if (lead < 0) { lead = w - 1; widBeforeLead = wid - 1 }
                continue
            }
            if (v and TxtChars.C_DROP != 0) continue
            if (v and TxtChars.C_SPACE != 0) c = ' '
            arr[w++] = c
            wid += if (v and TxtChars.C_WIDE != 0) 2 else 1
            if (v and TxtChars.C_WS == 0) {
                lastEnd = w
                widAtLast = wid
                if (lead < 0) {
                    lead = w - 1
                    widBeforeLead = wid - (if (v and TxtChars.C_WIDE != 0) 2 else 1)
                }
            }
        }
        if (lead < 0) {
            t.start[i] = s
            t.end[i] = s
            t.flags[i] = f or LineFlags.BLANK
            t.width[i] = 0
            return
        }
        e = lastEnd
        var width = widAtLast
        if (lead > s) f = f or LineFlags.INDENT
        if (cfg.stripIndent) {
            s = lead
            width -= widBeforeLead
        }
        val last = arr[e - 1]
        if (TxtChars.isTerminal(last)) f = f or LineFlags.TERMINAL
        if (TxtChars.isCjkIdeoKana(last)) f = f or LineFlags.CJK_LAST
        if (TxtChars.isCjkIdeoKana(arr[lead])) f = f or LineFlags.CJK_FIRST
        if (e - lead <= 40 && TxtChars.isSceneMarker(arr, lead, e)) f = f or LineFlags.SCENE
        t.start[i] = s
        t.end[i] = e
        t.flags[i] = f
        t.width[i] = width
    }

    private fun isBlank(s: String): Boolean {
        for (c in s) if (!TxtChars.isWs(c) && c >= ' ') return false
        return true
    }

    private fun isBlank(a: CharArray, s: Int, e: Int): Boolean {
        for (k in s until e) if (!TxtChars.isWs(a[k]) && a[k] >= ' ') return false
        return true
    }
}
