package com.ggumtak.readeraplus.format.epub

import com.ggumtak.readeraplus.engine.Block
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.StyleRun
import com.ggumtak.readeraplus.format.txt.TxtChapters
import com.ggumtak.readeraplus.format.txt.TxtChars
import com.ggumtak.readeraplus.format.txt.TxtParagraphs

/**
 * Chapter headings of an EPUB with a poor TOC, found the way a TXT book's are (TXT→EPUB converters write
 * "1화", "2화" … as ordinary paragraphs of one huge item and a TOC with a title entry at most).
 *
 * - Opening: [EpubSplit.scan] hands over the item's blocks ([EpubSplit.Lines]); [detect] runs the TXT rules
 *   ([TxtChapters.detectLines]: K1–K6, spacing scores, author-note and listing pruning, the user rule) over the
 *   lines of all scanned items at once, positions being the scan's char counts (the spacing scores need them).
 *   Each heading becomes a [Head] with the synthetic anchor id [id].
 * - Converting: [apply] finds each heading's block in the converted item, after the item was cut, and gives it the
 *   anchor and the heading look of a TXT chapter (page break before, centred bold when emphasised). The headings
 *   take no part in the split: a chapter may start in the middle of a section, at the top of a page.
 * The matching is by text and order, not by offset: the k-th detected heading is the block holding the same text
 * that has [Head.occurrence] equal blocks before it; the estimate and the converted offsets need not agree.
 */
internal object EpubHeadings {
    /** Prefix of the anchor ids given to detected headings ("__ra_h0", "__ra_h1" … per item). */
    const val ID_PREFIX = "__ra_h"

    fun id(k: Int): String = ID_PREFIX + k

    /**
     * A detected heading: its TOC [title], the [key] of its block's text and how many blocks of that text precede it.
     * [part] / [offset]: the section of its item and the offset in it where its block starts, once known (the cut of
     * the item decides; -1: not yet; offset 0 in an item of one section, whose offsets come with the anchors).
     */
    class Head(val title: String, val key: String, val occurrence: Int) {
        @JvmField var part = -1
        @JvmField var offset = 0
    }

    /** Detection result per scanned item: the [heads] of each (null: none) and how many were found in all. */
    class Found(val heads: Array<Array<Head>?>, val total: Int)

    /** Any text longer than a heading: [TxtChapters.detectLines] reads only its length. */
    private val BODY = "x".repeat(TxtChapters.MAX_HEADING_CHARS + 1)

    /**
     * The TOC is poor, so the detected headings replace it: fewer than 2 resolved entries, or every entry sits at the
     * start of its spine item (one entry per file, or just a title) and there are more than twice as many detected
     * chapters as entries. A TOC with an anchor inside an item is the book's own and is never replaced.
     */
    fun replacesToc(entries: Int, allItemStart: Boolean, detected: Int): Boolean =
        detected >= 2 && (entries < 2 || (allItemStart && entries * 2 < detected))

    /** Matching text of a block: whitespace (NBSP, U+3000 too) collapsed and trimmed. */
    fun keyOf(s: String): String {
        for (c in s) if (c.code >= 0x80 && TxtChars.isWs(c)) return TxtChapters.collapseWs(s)
        return s
    }

    /**
     * Detects chapter headings over the [lines] of the scanned items (null: not collected), as if they were one text:
     * item k starts after the [chars] of the items before it. At least 2 headings overall, else nothing is found.
     */
    fun detect(lines: Array<EpubSplit.Lines?>, chars: IntArray, userRegex: String): Found {
        val m = lines.size
        val none = Found(arrayOfNulls(m), 0)
        var total = 0
        for (l in lines) if (l != null) total += l.n
        if (total < 2) return none
        val texts = ArrayList<String>(total)
        val pos = IntArray(total)
        val owner = IntArray(total)
        val local = IntArray(total)
        var at = 0
        var base = 0L
        for (k in 0 until m) {
            val l = lines[k] ?: continue
            for (j in 0 until l.n) {
                texts.add(l.text[j] ?: BODY)
                pos[at] = (base + l.pos[j]).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                owner[at] = k
                local[at] = j
                at++
            }
            base += chars[k]
        }
        val res = TxtChapters.detectLines(texts, pos, userRegex)
        if (res.lines.size < 2) return none

        val chosen = arrayOfNulls<IntArray>(m) // per item: indices (ascending) of its heading lines
        val titles = arrayOfNulls<ArrayList<String>>(m)
        val counts = IntArray(m)
        for (r in res.lines.indices) counts[owner[res.lines[r]]]++
        for (r in res.lines.indices) {
            val k = owner[res.lines[r]]
            val c = chosen[k] ?: IntArray(counts[k]).also { chosen[k] = it }
            val t = titles[k] ?: ArrayList<String>(counts[k]).also { titles[k] = it }
            c[t.size] = local[res.lines[r]]
            t.add(res.titles[r])
        }
        val heads = arrayOfNulls<Array<Head>>(m)
        for (k in 0 until m) {
            val idx = chosen[k] ?: continue
            val l = lines[k]!!
            val keys = HashSet<String>(idx.size * 2)
            for (j in idx) keys.add(keyOf(l.text[j]!!))
            val seen = HashMap<String, Int>(keys.size * 2)
            var next = 0
            val out = ArrayList<Head>(idx.size)
            for (j in 0 until l.n) {
                val s = l.text[j] ?: continue
                val key = keyOf(s)
                if (key !in keys) continue
                val occ = seen[key] ?: 0
                seen[key] = occ + 1
                if (next < idx.size && idx[next] == j) {
                    out.add(Head(titles[k]!![next], key, occ))
                    next++
                }
            }
            heads[k] = out.toTypedArray()
        }
        return Found(heads, res.lines.size)
    }

    /**
     * [content] with the headings [heads] marked: the block of each gets the anchor `id(index)`, a page break before
     * it and the TXT heading look (centred, bold, larger when [emphasize]; a block that already is a heading keeps
     * its own look). A heading whose block is not found is simply left out (its TOC entry then lands at the start of
     * its section). Returns [content] itself when nothing matched.
     */
    fun apply(content: SectionContent, heads: Array<Head>, emphasize: Boolean): SectionContent {
        if (heads.isEmpty()) return content
        val byKey = HashMap<String, ArrayList<Int>>(heads.size * 2)
        for ((j, h) in heads.withIndex()) byKey.getOrPut(h.key) { ArrayList(2) }.add(j)
        val seen = HashMap<String, Int>(byKey.size * 2)
        val text = content.text
        var blocks: ArrayList<Block>? = null
        var anchors: HashMap<String, Int>? = null
        var marked: ArrayList<IntArray>? = null
        for ((i, b) in content.blocks.withIndex()) {
            if (b !is ParagraphBlock) continue
            val len = b.end - b.start
            // a list item or table cell may carry a few extra chars (marker, separator): room for them
            if (len <= 0 || len > TxtChapters.MAX_HEADING_CHARS + 16) continue
            val key = keyOf(text.substring(b.start, b.end))
            val cand = byKey[key] ?: continue
            val occ = seen[key] ?: 0
            seen[key] = occ + 1
            var j = -1
            for (c in cand) if (heads[c].occurrence == occ) j = c
            if (j < 0) continue
            val bl = blocks ?: ArrayList(content.blocks).also { blocks = it }
            val an = anchors ?: HashMap(content.anchors).also { anchors = it }
            an[id(j)] = b.start
            val own = b.style.headingLevel > 0
            val style = when {
                own -> b.style.copy(pageBreakBefore = true)
                emphasize -> TxtParagraphs.HEADING_STYLE
                else -> TxtParagraphs.PLAIN_HEADING_STYLE
            }
            bl[i] = ParagraphBlock(b.start, b.end, style)
            if (emphasize && !own) (marked ?: ArrayList<IntArray>().also { marked = it }).add(intArrayOf(b.start, b.end))
        }
        val bl = blocks ?: return content
        val runs = marked?.let { withHeadingRuns(content.styleRuns, it) } ?: content.styleRuns
        return SectionContent(text, bl, runs, anchors ?: content.anchors)
    }

    /** [runs] with the heading run laid over each of [ranges] (ascending, disjoint): runs under it are cut or dropped. */
    private fun withHeadingRuns(runs: List<StyleRun>, ranges: List<IntArray>): List<StyleRun> {
        val out = ArrayList<StyleRun>(runs.size + ranges.size)
        var ri = 0
        var carry: StyleRun? = null
        for (range in ranges) {
            while (true) {
                val run = carry ?: (if (ri < runs.size) runs[ri++] else null) ?: break
                carry = null
                if (run.end <= range[0]) {
                    out.add(run)
                    continue
                }
                if (run.start >= range[1]) {
                    carry = run
                    break
                }
                if (run.start < range[0]) out.add(StyleRun(run.start, range[0], run.style))
                if (run.end > range[1]) {
                    carry = StyleRun(range[1], run.end, run.style)
                    break
                }
            }
            out.add(StyleRun(range[0], range[1], TxtParagraphs.HEADING_RUN))
        }
        carry?.let { out.add(it) }
        while (ri < runs.size) out.add(runs[ri++])
        return out
    }
}
