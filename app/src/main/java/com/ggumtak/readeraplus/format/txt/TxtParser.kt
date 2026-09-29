package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.format.ParseOptions

/**
 * TXT parsing passes.
 *
 * - [parse]: the whole-file (first-open) pass: detect + decode, line pipeline, chapter detection, global
 *   decisions, paragraph walk, sectioning. Produces a [Parsed] from which the [TxtIndex] is taken.
 * - [loadSection]: the per-section pass used for every section load: decode only that section's byte range and
 *   rebuild it with the stored decisions. Equals [Parsed.buildSection] for the same section.
 */
internal object TxtParser {
    const val TARGET_CHARS = 30_000
    /** Chapters longer than this are split into ~[TARGET_CHARS] sections. */
    const val CHAPTER_MAX_CHARS = 60_000
    /** Text without chapters is split once longer than this. */
    const val PLAIN_MAX_CHARS = 45_000

    /** Result of the whole-file pass. Holds the decoded text: drop it after taking the index. */
    class Parsed internal constructor(
        val decoder: TxtDecoder,
        val newline: Int,
        val decisions: TxtDecisions,
        val lines: LineTable,
        val paras: ParaList,
        val secParaFrom: IntArray,
        val secParaTo: IntArray,
        val byteStart: IntArray,
        val byteEnd: IntArray,
        val flags: IntArray,
        val chars: IntArray,
        val titles: Array<String?>,
    ) {
        val sectionCount: Int get() = byteStart.size

        fun toIndex(key: String): TxtIndex =
            TxtIndex(key, decoder.name, newline, decisions, byteStart, byteEnd, flags, chars, titles)

        /** Builds section [i] from the whole-file structures (reference for the byte-range path). */
        fun buildSection(i: Int, emphasizeHeadings: Boolean): SectionContent {
            val from = secParaFrom[i]
            val to = secParaTo[i]
            val idx = IntArray(maxOf(0, to - from))
            val n = TxtParagraphs.select(lines, paras, from, to, titles[i], idx)
            return TxtParagraphs.build(lines, paras, idx, n, emphasizeHeadings)
        }
    }

    // ---------------------------------------------------------------- whole-file pass

    fun parse(bytes: ByteArray, len: Int, o: ParseOptions): Parsed {
        // 1. encoding (+ decode; UTF-8 detection and decoding are one fused pass)
        var det: DetectedEncoding? = if (o.txtEncoding.isNotBlank()) TxtCharsets.forced(o.txtEncoding, bytes, 0, len) else null
        if (det == null) {
            det = TxtCharsets.bom(bytes, 0, len) ?: TxtCharsets.utf16WithoutBom(bytes, 0, len)?.let { DetectedEncoding(it, 0) }
        }
        val chars: CharArray
        val n: Int
        val dec: TxtDecoder
        val dataStart: Int
        val nl: Int
        if (det == null) {
            // no BOM, not UTF-16: try UTF-8 (strict enough to reject CP949 within a few characters), else CP949
            dataStart = 0
            nl = detectNewline(Utf8Decoder, bytes, 0, len)
            val out = CharArray(len)
            val r = Utf8Decoder.decodeDetect(bytes, 0, len, out, 0, truncatedOk = true)
            if (r != Utf8Decoder.NOT_UTF8) {
                dec = Utf8Decoder
                chars = out
                n = r
            } else {
                dec = TxtCharsets.cp949
                chars = if (dec.maxChars(len) <= out.size) out else CharArray(dec.maxChars(len))
                n = dec.decode(bytes, 0, len, chars, 0)
            }
        } else {
            dec = det.decoder
            dataStart = minOf(det.bomLength, len)
            nl = detectNewline(dec, bytes, dataStart, len)
            val r = decodeRange(dec, bytes, dataStart, len, nl)
            chars = r.chars
            n = r.length
        }

        // 2. lines
        val cfg = LineConfig(o.txtStripIndent, ReplaceRules.parse(o.txtReplaceRules), segment = dec.canAdvance)
        val lines = LineTable.build(chars, n, nl.toChar(), cfg, ByteMap(bytes, dataStart, len, dec))

        // 3. chapters
        val chapters = if (o.txtDetectChapters) TxtChapters.detect(lines, o.txtChapterRegex) else null
        val titleByLine = HashMap<Int, String>()
        if (chapters != null) {
            for (k in chapters.lines.indices) {
                lines.markHeading(chapters.lines[k])
                titleByLine[chapters.lines[k]] = chapters.titles[k]
            }
        }

        // 4. global decisions, 5. paragraphs
        val d = decide(lines, o)
        val paras = ParaList(lines.count / 2 + 16)
        TxtParagraphs.walk(lines, 0, lines.count, d, paras)

        // 6. sections
        val sb = SectionAccumulator(lines, paras, dataStart, len)
        var firstHeading = -1
        for (p in 0 until paras.n) if (paras.kind[p] == ParaKind.HEADING) { firstHeading = p; break }
        if (firstHeading < 0) {
            sb.addChunks(0, paras.n, 0, null, chapter = false, maxUnsplit = PLAIN_MAX_CHARS)
        } else {
            if (firstHeading > 0 && hasContent(paras, 0, firstHeading)) {
                sb.addChunks(0, firstHeading, 0, null, chapter = false, maxUnsplit = CHAPTER_MAX_CHARS)
            }
            var h = firstHeading
            while (h < paras.n) {
                var next = h + 1
                while (next < paras.n && paras.kind[next] != ParaKind.HEADING) next++
                val line = paras.first[h]
                sb.addChunks(h, next, line, titleByLine[line] ?: lines.text(line), chapter = true, maxUnsplit = CHAPTER_MAX_CHARS)
                h = next
            }
        }
        if (sb.count == 0) sb.addEmpty()
        return sb.finish(dec, nl, d)
    }

    private fun hasContent(p: ParaList, from: Int, to: Int): Boolean {
        for (q in from until to) if (p.kind[q] != ParaKind.EMPTY) return true
        return false
    }

    /** Collects sections: paragraph ranges, byte ranges, flags, exact lengths. */
    private class SectionAccumulator(
        val lines: LineTable,
        val paras: ParaList,
        val dataStart: Int,
        val dataEnd: Int,
    ) {
        var count = 0
        var pFrom = IntArray(64)
        var pTo = IntArray(64)
        var firstLine = IntArray(64)
        var flags = IntArray(64)
        var titles = arrayOfNulls<String>(64)
        private var scratch = IntArray(64)

        private fun add(from: Int, to: Int, line: Int, title: String?, f: Int) {
            if (count == pFrom.size) {
                val cap = count * 2
                pFrom = pFrom.copyOf(cap)
                pTo = pTo.copyOf(cap)
                firstLine = firstLine.copyOf(cap)
                flags = flags.copyOf(cap)
                titles = titles.copyOf(cap)
            }
            pFrom[count] = from
            pTo[count] = to
            firstLine[count] = line
            flags[count] = f
            titles[count] = title
            count++
        }

        fun addEmpty() = add(0, 0, -1, null, 0)

        /** Adds paragraphs [from, to) as one section, or several ~TARGET_CHARS chunks when longer than [maxUnsplit]. */
        fun addChunks(from: Int, to: Int, line: Int, title: String?, chapter: Boolean, maxUnsplit: Int) {
            val chapFlag = if (chapter) TxtIndex.CHAPTER else 0
            var total = 0L
            for (q in from until to) total += paras.len[q] + 1
            if (total <= maxUnsplit) {
                add(from, to, line, title, chapFlag)
                return
            }
            var start = from
            var startLine = line
            var first = true
            var remaining = total
            while (remaining > TARGET_CHARS * 3 / 2) {
                val cut = chooseCut(start, to)
                if (cut < 0) break
                add(start, cut, startLine, if (first) title else null, if (first) chapFlag else 0)
                var consumed = 0L
                for (q in start until cut) consumed += paras.len[q] + 1
                remaining -= consumed
                start = cut
                startLine = paras.first[cut]
                first = false
            }
            add(start, to, startLine, if (first) title else null, if (first) chapFlag else 0)
        }

        /**
         * Paragraph index to start the next chunk at: within [0.7, 1.3] x target of [start], preferring the
         * paragraph right after a scene break / empty paragraph, else the boundary closest to the target;
         * failing that the first possible boundary after the window. -1 if none.
         */
        private fun chooseCut(start: Int, to: Int): Int {
            val lo = TARGET_CHARS * 7 / 10
            val hi = TARGET_CHARS * 13 / 10
            var pos = 0L
            var best = -1
            var bestScore = Long.MAX_VALUE
            var q = start
            while (q < to) {
                if (q > start && pos >= lo) {
                    if (pos > hi) {
                        if (best >= 0) return best
                        if (canStart(q)) return q
                    } else if (canStart(q)) {
                        val prevKind = paras.kind[q - 1]
                        val brk = prevKind == ParaKind.EMPTY || prevKind == ParaKind.SCENE
                        val score = (if (brk) 0L else 1_000_000L) + kotlin.math.abs(pos - TARGET_CHARS)
                        if (score < bestScore) {
                            bestScore = score
                            best = q
                        }
                    }
                }
                pos += paras.len[q] + 1
                q++
            }
            return best
        }

        private fun canStart(q: Int): Boolean {
            val k = paras.kind[q]
            return k == ParaKind.TEXT || k == ParaKind.SCENE || k == ParaKind.CONT
        }

        fun finish(dec: TxtDecoder, nl: Int, d: TxtDecisions): Parsed {
            val n = count
            val bs = IntArray(n)
            val be = IntArray(n)
            val ch = IntArray(n)
            val fl = flags.copyOf(n)
            val lineBytes = lines.byteStart!!
            for (s in 0 until n) {
                val line = firstLine[s]
                bs[s] = if (line <= 0) dataStart else lineBytes[line]
                if (line >= 0 && lines.flags[line] and LineFlags.CONT != 0) fl[s] = fl[s] or TxtIndex.STARTS_CONT
            }
            for (s in 0 until n) {
                be[s] = if (s + 1 < n) bs[s + 1] else dataEnd
                if (s + 1 < n && fl[s + 1] and TxtIndex.STARTS_CONT != 0) fl[s] = fl[s] or TxtIndex.ENDS_SEG
                val size = pTo[s] - pFrom[s]
                if (scratch.size < size) scratch = IntArray(size)
                val k = TxtParagraphs.select(lines, paras, pFrom[s], pTo[s], titles[s], scratch)
                ch[s] = TxtParagraphs.textLength(paras, scratch, k)
            }
            return Parsed(dec, nl, d, lines, paras, pFrom.copyOf(n), pTo.copyOf(n), bs, be, fl, ch, titles.copyOf(n))
        }
    }

    // ---------------------------------------------------------------- decisions

    /**
     * Resolves the global decisions from line statistics:
     * - hard-wrap joining: L = 90th percentile of text line widths (columns, Hangul = 2); the file is hard-wrapped
     *   when >= 60% of text lines are 90–105% of L without terminal punctuation (or >= 35% and almost no line
     *   is longer than 1.1 L), L >= 20. Lines >= 80% of L are then joined (>= 70% when paragraph ends are
     *   explicit: blank lines after short lines, or indents). "Always" joins lines >= 75% of L.
     * - indent signal: 5–60% of non-blank lines indented -> an indented line starts a new paragraph.
     * - AUTO blank lines: blank runs are 60–140% of the (joined) paragraphs -> blank lines are separators and are
     *   removed; a run longer than the usual separator run is a scene break. Otherwise blank runs are kept
     *   (collapsed to one empty paragraph each).
     */
    fun decide(t: LineTable, o: ParseOptions): TxtDecisions {
        var nonBlank = 0
        var indented = 0
        var textLines = 0
        val hist = IntArray(4097)
        val flags = t.flags
        val count = t.count
        val textSkip = LineFlags.HEADING or LineFlags.SCENE or LineFlags.SEG
        for (i in 0 until count) {
            val f = flags[i]
            if (f and (LineFlags.DELETED or LineFlags.BLANK) != 0) continue
            nonBlank++
            if (f and LineFlags.INDENT != 0) indented++
            if (f and textSkip == 0) {
                textLines++
                hist[minOf(t.width[i], 4096)]++
            }
        }
        val stopIndent = nonBlank > 0 && indented * 20 >= nonBlank && indented * 10 <= nonBlank * 6

        // ---- hard-wrap joining
        var joinMin = 0
        var l = 0
        if (o.txtJoinWrappedLines != 0 && textLines > 0) {
            val target = (textLines * 9L + 9) / 10
            var acc = 0L
            while (l < 4096) {
                acc += hist[l]
                if (acc >= target) break
                l++
            }
            if (o.txtJoinWrappedLines == 2) {
                joinMin = maxOf(1, (l * 3 + 3) / 4)
            } else if (textLines >= 8 && l >= 20) {
                val lo = (l * 9 + 9) / 10
                val hiNear = l + l / 20
                val over = l + l / 10
                var near = 0
                var tail = 0
                for (i in 0 until count) {
                    val f = flags[i]
                    if (f and (LineFlags.DELETED or LineFlags.BLANK or textSkip) != 0) continue
                    val w = t.width[i]
                    if (w in lo..hiNear && f and LineFlags.TERMINAL == 0) near++
                    if (w > over) tail++
                }
                if (near * 10 >= textLines * 6 || (near * 100 >= textLines * 35 && tail * 100 <= textLines)) {
                    // word wrapping is ragged (Korean words are 4-16 columns): join lines >= 80% of L
                    joinMin = maxOf(1, (l * 4 + 4) / 5)
                }
            }
        }
        // Paragraph ends explicit? Clearly short lines (< 60% of L: paragraph ends) are followed by a blank line,
        // or indents mark paragraph starts. Then punctuation at a line end doesn't stop joining.
        var ignoreTerminal = false
        if (joinMin > 0) {
            val shortW = maxOf(1, l * 3 / 5)
            var shortFollowed = 0
            var shortThenBlank = 0
            for (i in 0 until count) {
                val f = flags[i]
                if (f and (LineFlags.DELETED or LineFlags.BLANK or textSkip) != 0 || t.width[i] >= shortW) continue
                var k = i + 1
                while (k < count && flags[k] and LineFlags.DELETED != 0) k++
                if (k >= count) continue
                shortFollowed++
                if (flags[k] and LineFlags.BLANK != 0) shortThenBlank++
            }
            ignoreTerminal = stopIndent || (shortFollowed >= 3 && shortThenBlank * 10 >= shortFollowed * 7)
            if (ignoreTerminal && o.txtJoinWrappedLines == 1) joinMin = maxOf(1, (l * 7 + 9) / 10)
        }

        // ---- blank lines: runs vs logical paragraphs (after joining)
        val sceneRunDefault = 2
        val blankMode: Int
        var sceneRun = sceneRunDefault
        when (o.txtBlankLines) {
            ParseOptions.BLANK_REMOVE_ALL -> blankMode = TxtDecisions.REMOVE_SINGLES
            ParseOptions.BLANK_COLLAPSE -> blankMode = TxtDecisions.COLLAPSE
            ParseOptions.BLANK_KEEP -> blankMode = TxtDecisions.KEEP
            else -> {
                val runHist = IntArray(9)
                var runs = 0
                var paragraphs = 0
                var run = 0
                var prevJoinable = false
                val noJoinNext = LineFlags.BLANK or LineFlags.HEADING or LineFlags.SCENE or LineFlags.SEG or LineFlags.CONT
                val stopFlags = if (ignoreTerminal) LineFlags.SEG else LineFlags.SEG or LineFlags.TERMINAL
                for (i in 0 until count) {
                    val f = flags[i]
                    if (f and LineFlags.DELETED != 0) {
                        if (run > 0) { runHist[minOf(run, 8)]++; runs++; run = 0 }
                        continue
                    }
                    if (f and LineFlags.BLANK != 0) {
                        run++
                        prevJoinable = false
                        continue
                    }
                    if (run > 0) { runHist[minOf(run, 8)]++; runs++; run = 0 }
                    val joined = prevJoinable && f and noJoinNext == 0 && !(stopIndent && f and LineFlags.INDENT != 0)
                    if (!joined) paragraphs++
                    prevJoinable = joinMin > 0 && f and (LineFlags.HEADING or LineFlags.SCENE or stopFlags) == 0 &&
                        t.width[i] >= joinMin
                }
                if (paragraphs > 0 && runs * 10 >= paragraphs * 6 && runs * 10 <= paragraphs * 14) {
                    blankMode = TxtDecisions.REMOVE_SINGLES
                    var mode = 1
                    for (r in 2..8) if (runHist[r] > runHist[mode]) mode = r
                    sceneRun = minOf(mode + 1, 9)
                } else {
                    blankMode = TxtDecisions.COLLAPSE
                }
            }
        }
        return TxtDecisions(blankMode, sceneRun, joinMin, stopIndent, ignoreTerminal)
    }

    // ---------------------------------------------------------------- per-section pass

    /**
     * Rebuilds section [i] of [index] from its raw bytes [bytes][0, len) (exactly the section's byte range).
     */
    fun loadSection(
        bytes: ByteArray, len: Int, index: TxtIndex, i: Int, dec: TxtDecoder, o: ParseOptions, rules: ReplaceRules?,
    ): SectionContent {
        val r = decodeRange(dec, bytes, 0, len, index.newline)
        val cfg = LineConfig(o.txtStripIndent, rules, segment = dec.canAdvance)
        val lines = LineTable.build(r.chars, r.length, index.newline.toChar(), cfg, null)
        val f = index.flags[i]
        if (lines.count > 0) {
            if (f and TxtIndex.STARTS_CONT != 0) lines.flags[0] = lines.flags[0] or LineFlags.SEG or LineFlags.CONT
            if (f and TxtIndex.ENDS_SEG != 0) {
                val last = lines.count - 1
                lines.flags[last] = lines.flags[last] or LineFlags.SEG
            }
            if (f and TxtIndex.CHAPTER != 0) {
                var h = 0
                while (h < lines.count && lines.flags[h] and (LineFlags.BLANK or LineFlags.DELETED) != 0) h++
                if (h < lines.count) lines.markHeading(h)
            }
        }
        val paras = ParaList(lines.count / 2 + 8)
        TxtParagraphs.walk(lines, 0, lines.count, index.decisions, paras)
        val idx = IntArray(paras.n)
        val n = TxtParagraphs.select(lines, paras, 0, paras.n, index.titles[i], idx)
        return TxtParagraphs.build(lines, paras, idx, n, o.txtEmphasizeHeadings)
    }

    // ---------------------------------------------------------------- helpers

    class Decoded(val chars: CharArray, val length: Int)

    /**
     * Decodes [from, to). The built-in decoders keep newline units 1:1; for java.nio charsets the newline count
     * is verified and, if a malformed sequence swallowed a newline, each line is decoded on its own.
     */
    fun decodeRange(dec: TxtDecoder, bytes: ByteArray, from: Int, to: Int, nl: Int): Decoded {
        val out = CharArray(dec.maxChars(to - from))
        val n = dec.decode(bytes, from, to, out, 0)
        if (dec !is JavaCharsetDecoder) return Decoded(out, n)
        var byteLines = 0
        var p = from
        while (true) {
            val q = dec.nextNewline(bytes, p, to, nl)
            if (q < 0) break
            byteLines++
            p = q + dec.unitSize
        }
        var charLines = 0
        val c = nl.toChar()
        for (k in 0 until n) if (out[k] == c) charLines++
        if (charLines == byteLines) return Decoded(out, n)
        val sb = StringBuilder(n)
        val tmp = CharArray(dec.maxChars(to - from))
        p = from
        while (p <= to) {
            val q = dec.nextNewline(bytes, p, to, nl)
            val e = if (q < 0) to else q
            val m = dec.decode(bytes, p, e, tmp, 0)
            for (k in 0 until m) sb.append(if (tmp[k] == c) '�' else tmp[k])
            if (q < 0) break
            sb.append(c)
            p = q + dec.unitSize
        }
        val res = CharArray(sb.length)
        sb.getChars(0, sb.length, res, 0)
        return Decoded(res, res.size)
    }

    /**
     * LF unless, in the first 64 KB, lone CRs (not followed by LF: classic Mac line ends) clearly outnumber LFs.
     * A few stray LFs in a CR file must not turn the whole book into a handful of giant lines.
     */
    fun detectNewline(dec: TxtDecoder, bytes: ByteArray, from: Int, to: Int): Int {
        val end = minOf(to, from + 65536)
        val u = dec.unitSize
        var lf = 0
        var p = from
        while (true) {
            val q = dec.nextNewline(bytes, p, end, 0x0A)
            if (q < 0) break
            lf++
            p = q + u
        }
        var loneCr = 0
        p = from
        while (true) {
            val q = dec.nextNewline(bytes, p, end, 0x0D)
            if (q < 0) break
            if (q + u >= end || dec.nextNewline(bytes, q + u, q + 2 * u, 0x0A) != q + u) loneCr++
            p = q + u
        }
        return if (loneCr > 0 && loneCr > lf * 4) 0x0D else 0x0A
    }

    /** End (exclusive, after the newline unit) of the last complete line in [from, to), or -1. */
    fun lastLineEnd(dec: TxtDecoder, bytes: ByteArray, from: Int, to: Int, nl: Int): Int {
        val u = dec.unitSize
        var p = from + ((to - from) / u) * u - u
        val lo = nl.toByte()
        while (p >= from) {
            if (u == 1) {
                if (bytes[p] == lo) return p + 1
            } else if (dec.nextNewline(bytes, p, p + 2, nl) == p) {
                return p + 2
            }
            p -= u
        }
        return -1
    }
}
