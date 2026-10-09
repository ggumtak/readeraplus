package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.engine.Block
import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.RuleBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.StyleRun
import com.ggumtak.readeraplus.format.txt.TxtChapters

/**
 * Splits oversized spine items (whole-book XHTML files written by TXT→EPUB converters) into sub-sections, so
 * the reader lays out at most about [PART_TARGET_CHARS] chars for the first page and for each settings change,
 * as with TXT sections.
 *
 * The number of parts is fixed while opening, from a text scan of the raw item ([scan], [partsFor]). The same
 * scan assigns the TOC anchors of the item to parts by their position ([assign]), so TOC entries get their
 * section before anything is converted. Cut positions are chosen after conversion ([cut]), deterministically
 * from the converted content, and they keep every assigned TOC anchor in its part. Where a part has TOC anchors,
 * it starts at the block of its first anchor, so chapters start at the top of a section. Otherwise the cut goes
 * at a paragraph boundary near an even share of the text, preferring headings and scene breaks.
 * Pure: no zip access (unit-tested directly).
 */
internal object EpubSplit {
    /** Items up to this uncompressed size are never scanned or split (at 3 bytes per Hangul char, ~64K chars). */
    const val SCAN_MIN_BYTES = 192 * 1024L
    /** An item whose scanned text is at most this many chars stays one section. */
    const val SPLIT_MIN_CHARS = 80_000
    /** Aimed-at size of each part of a split item. */
    const val PART_TARGET_CHARS = 40_000
    const val MAX_PARTS = 4096

    /** Maximum distance searched for a space when a cut has to go inside a paragraph. */
    private const val SPACE_SEARCH = 2000

    /**
     * Result of [scan]: approximate converted length and, per requested anchor, the text chars before it; [lines]
     * (when asked for) the item's blocks.
     */
    class Scan(val chars: Int, val anchors: Map<String, Int>, val lines: Lines? = null)

    /**
     * The blocks of one item in document order, for chapter detection ([EpubHeadings]): [pos] is the text chars
     * before the block (the coordinate of [Scan.anchors]), [text] its whitespace-collapsed text, or null for a block
     * longer than [TxtChapters.MAX_HEADING_CHARS] (body: only that it exists matters). Blocks without text are not
     * lines; an image is a null line.
     */
    class Lines {
        @JvmField var n = 0
        @JvmField var pos = IntArray(256)
        @JvmField var text = arrayOfNulls<String>(256)

        fun add(at: Int, s: String?) {
            if (n >= MAX_LINES) return
            if (n == pos.size) {
                pos = pos.copyOf(n * 2)
                text = text.copyOf(n * 2)
            }
            pos[n] = at
            text[n] = s
            n++
        }
    }

    /** Lines kept per item (a longer item is detected on its first part only). */
    private const val MAX_LINES = 1_000_000

    /**
     * Collects the block texts of [scan]: the converter's paragraph model (a paragraph closes at every block tag, at
     * `<br>`, `<hr>` and an image), its whitespace collapsing and the characters it drops, so the text of a line is
     * the text of the converted block. Stops reading a block once it is longer than a heading.
     */
    private class LineBuilder(private val out: Lines) {
        private val sb = StringBuilder(64)
        private var started = false
        private var long = false
        private var space = false
        private var at = 0

        fun text(s: String, from: Int, end: Int, raw: Boolean, before: Int) {
            if (long) return
            var i = from
            while (i < end) {
                val c = s[i]
                if (c == '&' && !raw) {
                    val e = Entities.parseAt(s, i, end)
                    if (e >= 0) {
                        add((e ushr 32).toInt(), before)
                        i = (e and 0xFFFFFFFFL).toInt()
                        if (long) return
                        continue
                    }
                }
                add(c.code, before)
                i++
                if (long) return
            }
        }

        private fun add(cp: Int, before: Int) {
            when {
                cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0D || cp == 0x0C || cp == 0x2028 || cp == 0x2029 ->
                    if (started) space = true
                cp < 0x20 || cp in 0x7F..0x9F || cp == 0xAD || cp == 0xFEFF || cp == 0xFFFC -> {}
                cp == 0x3000 && !started -> {}
                else -> {
                    if (!started) {
                        started = true
                        at = before
                    }
                    if (space) {
                        sb.append(' ')
                        space = false
                    }
                    sb.appendCodePoint(cp)
                    if (sb.length > TxtChapters.MAX_HEADING_CHARS) long = true
                }
            }
        }

        /** Ends the block: a line when it had text. */
        fun flush() {
            if (started) out.add(at, if (long) null else sb.toString())
            sb.setLength(0)
            started = false
            long = false
            space = false
        }

        /** An image: ends the block and is a body line of its own. */
        fun image(before: Int) {
            flush()
            out.add(before, null)
        }
    }

    /**
     * Estimates the converted text length of [xhtml] (whitespace runs count as one char, entities as one, head,
     * script and style content skipped) and finds where the ids in [wanted] occur (first occurrence, as the
     * converter records them: `id` of any element, `name` of `<a>`). With [lines] the item's blocks are collected
     * in the same pass over the markup (a second, short look at each text token; the char counting is unchanged).
     */
    fun scan(xhtml: String, wanted: Set<String>, lines: Lines? = null): Scan {
        val r = MarkupReader(xhtml)
        val lb = if (lines != null) LineBuilder(lines) else null
        val found = HashMap<String, Int>()
        var chars = 0
        var space = true // leading whitespace never counts
        var inHead = false
        var skipRaw = false
        while (true) {
            when (r.next()) {
                MarkupReader.EOF -> break
                MarkupReader.START -> {
                    val n = r.name
                    skipRaw = false
                    when (n) {
                        "head" -> if (!r.selfClosing) inHead = true
                        "body" -> inHead = false
                        "style", "script" -> if (!r.selfClosing) skipRaw = true
                    }
                    if (wanted.isNotEmpty() && found.size < wanted.size) {
                        r.attr("id")?.let { if (it in wanted) found.putIfAbsent(it, chars) }
                        if (n == "a") r.attr("name")?.let { if (it in wanted) found.putIfAbsent(it, chars) }
                    }
                    if (lb != null && !inHead) {
                        if (n == "img" || n == "image") lb.image(chars)
                        else if (n == "br" || n == "hr" || XhtmlConverter.isBlockTag(n)) lb.flush()
                    }
                }
                MarkupReader.END -> {
                    skipRaw = false
                    if (r.name == "head") inHead = false
                    if (lb != null && !inHead && XhtmlConverter.isBlockTag(r.name)) lb.flush()
                }
                MarkupReader.TEXT -> {
                    val raw = r.textRaw
                    if (raw && skipRaw) {
                        skipRaw = false
                        continue
                    }
                    skipRaw = false
                    if (inHead) continue
                    val s = r.source
                    var i = r.textStart
                    val end = r.textEnd
                    lb?.text(s, i, end, raw, chars)
                    while (i < end) {
                        val c = s[i]
                        if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\u000C') {
                            if (!space) {
                                chars++
                                space = true
                            }
                            i++
                            continue
                        }
                        space = false
                        chars++
                        i++
                        if (c == '&' && !raw) { // an entity counts as one char
                            var j = i
                            val lim = minOf(end, i + 12)
                            while (j < lim && s[j] != ';' && s[j] != '&' && s[j] != '<') j++
                            if (j < lim && s[j] == ';' && j > i) i = j + 1
                        }
                    }
                }
            }
        }
        lb?.flush()
        return Scan(chars, found, lines)
    }

    /** Number of parts for an item whose scan found [chars] text chars. */
    fun partsFor(chars: Int): Int {
        if (chars <= SPLIT_MIN_CHARS) return 1
        val n = (chars.toLong() + PART_TARGET_CHARS - 1) / PART_TARGET_CHARS
        return n.coerceIn(2L, MAX_PARTS.toLong()).toInt()
    }

    /** Part of each anchor found by [scan], proportional to the text before it (monotone in document order). */
    fun assign(scan: Scan, parts: Int): HashMap<String, Int> {
        val out = HashMap<String, Int>(scan.anchors.size * 2)
        val total = maxOf(1, scan.chars).toLong()
        for ((id, before) in scan.anchors) {
            out[id] = (before.toLong() * parts / total).toInt().coerceIn(0, parts - 1)
        }
        return out
    }

    /**
     * Part ranges of a split item: part k covers `text[starts[k], ends[k])` of the whole converted item. Between
     * two parts at most one char is dropped (the '\n' separating two blocks, or the space a paragraph was cut at).
     */
    class Cuts(@JvmField val starts: IntArray, @JvmField val ends: IntArray) {
        val size: Int get() = starts.size

        /** Part holding the whole-item [offset] (an offset on a dropped char belongs to the part before it). */
        fun locate(offset: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            var k = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] <= offset) {
                    k = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            // An empty part (only at the end of an item that yielded fewer blocks than expected): prefer the
            // text before it.
            while (k > 0 && starts[k] == ends[k] && offset <= ends[k - 1]) k--
            return k
        }

        /** [offset] relative to part [part], clamped to it. */
        fun local(part: Int, offset: Int): Int = (offset - starts[part]).coerceIn(0, ends[part] - starts[part])
    }

    /** Everything in part 0 (fallback when cutting fails). */
    fun trivial(length: Int, parts: Int): Cuts {
        val n = maxOf(1, parts)
        val starts = IntArray(n) { if (it == 0) 0 else length }
        val ends = IntArray(n) { length }
        return Cuts(starts, ends)
    }

    /**
     * Cuts converted item [c] into [parts] parts. Anchor j (whole-item offset [anchorOffsets] j) must end up in
     * part [anchorParts] j; when constraints conflict (anchors of different parts in one spot) they are ignored
     * for that cut. Always returns exactly [parts] parts; trailing parts are empty only when the content has too
     * few places to cut.
     */
    fun cut(c: SectionContent, parts: Int, anchorOffsets: IntArray, anchorParts: IntArray): Cuts {
        val n = maxOf(1, parts)
        val len = c.length
        val starts = IntArray(n)
        val ends = IntArray(n)
        ends[n - 1] = len
        if (n == 1) return Cuts(starts, ends)
        val minA = IntArray(n) { Int.MAX_VALUE }
        val maxA = IntArray(n) { -1 }
        for (j in anchorOffsets.indices) {
            val p = anchorParts[j]
            if (p < 0 || p >= n) continue
            val a = anchorOffsets[j].coerceIn(0, len)
            if (a < minA[p]) minA[p] = a
            if (a > maxA[p]) maxA[p] = a
        }
        val minFrom = IntArray(n + 1) { Int.MAX_VALUE }
        for (k in n - 1 downTo 0) minFrom[k] = minOf(minA[k], minFrom[k + 1])
        val cutter = Cutter(c)
        var maxBefore = -1
        var prev = 0
        for (k in 1 until n) {
            maxBefore = maxOf(maxBefore, maxA[k - 1])
            val share = (len - prev) / (n - k + 1)
            val target = prev + share
            val lb = maxOf(prev + 1, maxBefore + 1)
            val ub = minOf(len, minFrom[k])
            var r = NONE
            if (lb <= ub) {
                val first = minA[k]
                if (first != Int.MAX_VALUE && first in lb..ub) r = cutter.atAnchor(first, lb)
                if (r == NONE) r = cutter.near(target, lb, ub, share)
            }
            if (r == NONE && prev < len) r = cutter.near(target, prev + 1, len, share) // constraints ignored
            val endPrev: Int
            val start: Int
            if (r == NONE) {
                endPrev = len
                start = len
            } else {
                endPrev = (r ushr 32).toInt()
                start = r.toInt()
            }
            ends[k - 1] = endPrev
            starts[k] = start
            prev = start
        }
        return Cuts(starts, ends)
    }

    private const val NONE = -1L

    private fun pack(endPrev: Int, start: Int): Long = (endPrev.toLong() shl 32) or (start.toLong() and 0xFFFFFFFFL)

    /** Cut position search over one converted item. */
    private class Cutter(c: SectionContent) {
        private val text = c.text
        private val blocks: List<Block> = c.blocks

        /** A cut before block [i] (i >= 1): the separating '\n' is dropped. */
        private fun before(i: Int): Long {
            val s = blocks[i].start
            return pack(s - 1, s)
        }

        /** Index of the last block starting at or before [offset], -1 if none. */
        fun lastStartingAtOrBefore(offset: Int): Int {
            var lo = 0
            var hi = blocks.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (blocks[mid].start <= offset) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return ans
        }

        /** A part whose first TOC anchor sits at [a]: cut before the block holding it, else at the anchor itself. */
        fun atAnchor(a: Int, lb: Int): Long {
            var i = lastStartingAtOrBefore(a)
            if (i < 0) return NONE
            val b = blocks[i]
            if (i >= 1 && b.start >= lb) {
                // `<h1>Part</h1><h2 id=…>Chapter</h2>`: keep the headings above the chapter together
                while (i >= 2 && isHeading(blocks[i - 1]) && isHeading(blocks[i]) && blocks[i - 1].start >= lb) i--
                return before(i)
            }
            if (b is ParagraphBlock && a > b.start && a < b.end) return pack(a, a) // shares a paragraph: split it
            if (i + 1 < blocks.size && blocks[i + 1].start >= lb) return before(i + 1) // anchor at a block's end
            return NONE
        }

        /**
         * Best cut in [lb, ub] around [target]: a heading or scene start within 30% of [share], else the nearest
         * ordinary paragraph start there, else the nearest block start in range, else inside the paragraph that
         * spans the whole range (after a space when there is one).
         */
        fun near(target: Int, lb: Int, ub: Int, share: Int): Long {
            if (lb > ub || blocks.isEmpty()) return NONE
            val t = target.coerceIn(lb, ub)
            val w = maxOf(1, share * 3 / 10)
            val lo = maxOf(lb, t - w)
            val hi = minOf(ub, t + w)
            var best = -1
            var bestTier = Int.MAX_VALUE
            var bestDist = Int.MAX_VALUE
            var i = maxOf(1, lastStartingAtOrBefore(lo - 1) + 1)
            while (i < blocks.size) {
                val b = blocks[i]
                if (b.start > hi) break
                if (b.start >= lo) {
                    val tier = tier(i)
                    val d = kotlin.math.abs(b.start - t)
                    if (tier < bestTier || (tier == bestTier && d < bestDist)) {
                        best = i
                        bestTier = tier
                        bestDist = d
                    }
                }
                i++
            }
            if (best >= 1) return before(best)
            // Nothing in the window: nearest block start anywhere in [lb, ub].
            val at = lastStartingAtOrBefore(t)
            val below = if (at >= 1 && blocks[at].start >= lb) at else -1
            val above = if (at + 1 in 1 until blocks.size && blocks[at + 1].start <= ub) at + 1 else -1
            if (below >= 1 || above >= 1) {
                val pick = when {
                    below < 1 -> above
                    above < 1 -> below
                    t - blocks[below].start <= blocks[above].start - t -> below
                    else -> above
                }
                return before(pick)
            }
            // [lb, ub] lies inside one block: only a paragraph can be cut there.
            if (at < 0) return NONE
            val b = blocks[at]
            if (b !is ParagraphBlock) return NONE
            // after a space q: the parts are [.., q) and [q + 1, ..), both non-empty
            val qLo = maxOf(lb - 1, b.start + 1)
            val qHi = minOf(ub - 1, b.end - 2)
            if (qLo <= qHi) {
                val q0 = (t - 1).coerceIn(qLo, qHi)
                var q = q0
                val downTo = maxOf(qLo, q0 - SPACE_SEARCH)
                while (q >= downTo) {
                    if (text[q] == ' ') return pack(q, q + 1)
                    q--
                }
                q = q0 + 1
                val upTo = minOf(qHi, q0 + SPACE_SEARCH)
                while (q <= upTo) {
                    if (text[q] == ' ') return pack(q, q + 1)
                    q++
                }
            }
            // no space (CJK without spaces): any char boundary strictly inside the paragraph
            val pLo = maxOf(lb, b.start + 1)
            val pHi = minOf(ub, b.end - 1)
            if (pLo > pHi) return NONE
            var p = t.coerceIn(pLo, pHi)
            if (Character.isLowSurrogate(text[p]) && Character.isHighSurrogate(text[p - 1])) {
                if (p + 1 <= pHi) p++ else if (p - 1 >= pLo) p-- else return NONE
            }
            return pack(p, p)
        }

        /** 0: a heading starting a run of headings, or the block after a scene break; 1: a paragraph or image; 2: other. */
        private fun isHeading(b: Block): Boolean = b is ParagraphBlock && b.style.headingLevel > 0

        private fun tier(i: Int): Int {
            val b = blocks[i]
            val prev = blocks[i - 1]
            if (isHeading(b) && !(b as ParagraphBlock).style.softBreak && !isHeading(prev)) return 0
            if (prev is RuleBlock && b !is RuleBlock) return 0
            if (b is ParagraphBlock && !b.style.softBreak && b.end > b.start) return 1
            if (b is ImageBlock) return 1
            return 2
        }
    }

    /**
     * Content of part [part]: text, blocks, style runs and anchors of its range, shifted to start at 0. A
     * paragraph cut in two continues as a soft break at the top of the second part. [extraAnchors] (whole-item
     * offsets) are TOC anchors assigned to this part: those that ended up elsewhere are clamped to its bounds,
     * so the TOC entry still resolves next to its chapter.
     */
    fun slice(c: SectionContent, cuts: Cuts, part: Int, extraAnchors: Map<String, Int>?): SectionContent {
        val from = cuts.starts[part]
        val to = cuts.ends[part]
        if (from == 0 && to == c.length) return c
        val len = to - from
        val anchors = HashMap<String, Int>()
        for ((id, v) in c.anchors) if (v in from..to) anchors[id] = v - from
        if (extraAnchors != null) {
            for ((id, v) in extraAnchors) if (!anchors.containsKey(id)) anchors[id] = (v - from).coerceIn(0, maxOf(0, len))
        }
        if (len <= 0) return SectionContent("", emptyList(), emptyList(), anchors)
        val blocks = ArrayList<Block>()
        val src = c.blocks
        var lo = 0
        var hi = src.size - 1
        var i = 0
        while (lo <= hi) { // last block starting at or before `from`
            val mid = (lo + hi) ushr 1
            if (src[mid].start <= from) {
                i = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        while (i < src.size) {
            val b = src[i++]
            if (b.start > to) break
            if (b.start >= from) {
                when (b) {
                    is ParagraphBlock -> blocks.add(
                        if (b.start == from && b.end <= to) shift(b, from) else ParagraphBlock(b.start - from, minOf(b.end, to) - from, b.style),
                    )
                    is ImageBlock -> if (b.end <= to) {
                        blocks.add(ImageBlock(b.start - from, b.src, b.intrinsicWidth, b.intrinsicHeight, b.alt, b.style))
                    }
                    is RuleBlock -> blocks.add(RuleBlock(b.start - from))
                }
            } else if (b.end > from && b is ParagraphBlock) { // the tail of a paragraph cut in two
                val st = b.style.copy(softBreak = true, marginTopEm = 0f, pageBreakBefore = false)
                blocks.add(ParagraphBlock(0, minOf(b.end, to) - from, st))
            }
        }
        val runs = ArrayList<StyleRun>()
        val sr = c.styleRuns
        lo = 0
        hi = sr.size - 1
        var r = sr.size
        while (lo <= hi) { // first run ending after `from`
            val mid = (lo + hi) ushr 1
            if (sr[mid].end > from) {
                r = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        while (r < sr.size) {
            val run = sr[r++]
            if (run.start >= to) break
            val s = maxOf(run.start, from)
            val e = minOf(run.end, to)
            if (e > s) runs.add(StyleRun(s - from, e - from, run.style))
        }
        return SectionContent(c.text.substring(from, to), blocks, runs, anchors)
    }

    private fun shift(b: ParagraphBlock, from: Int): ParagraphBlock =
        if (from == 0) b else ParagraphBlock(b.start - from, b.end - from, b.style)
}
