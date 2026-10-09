package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.Block
import com.ggumtak.readeraplus.engine.BlockStyle
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.RunStyle
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.StyleRun

/*
 * Paragraph layer: lines -> paragraphs (walker), paragraphs of one section -> kept paragraphs (filter),
 * kept paragraphs -> SectionContent (builder).
 *
 * The walker is local: run over any line range that starts at a paragraph start it produces exactly the
 * paragraphs the whole-file walk produces for that range (apart from trailing empty paragraphs, which the
 * section filter drops anyway). Only resolved global decisions (TxtDecisions) come from outside. This is what
 * lets a section be rebuilt from its byte range alone.
 */

/** Paragraph kinds. */
internal object ParaKind {
    const val TEXT = 0
    const val EMPTY = 1
    const val SCENE = 2
    const val HEADING = 3
    /** A continuation segment of an overlong line (soft line break, no indent). */
    const val CONT = 4
}

/** Global decisions resolved once over the whole file and stored in the index. */
internal class TxtDecisions(
    /** Resolved blank-line handling: [KEEP], [COLLAPSE] or [REMOVE_SINGLES]. */
    val blankMode: Int,
    /** REMOVE_SINGLES: a blank run at least this long is a scene break (2 normally; 3 in double-spaced files). */
    val sceneRun: Int,
    /** Hard-wrap joining: a line with at least this many columns (and no terminal punctuation) is joined; 0 = off. */
    val joinMinWidth: Int,
    /** Don't join into a line that had leading indentation (indent marks paragraph starts in this file). */
    val joinStopAtIndent: Boolean,
    /** Paragraph ends are marked explicitly (blank lines / indents): join full lines even after punctuation. */
    val joinIgnoreTerminal: Boolean,
    /**
     * A one-space indent ([LineFlags.INDENT1]) is what the hard wrap left at the start of continuation lines (most
     * such lines follow a full line): it neither marks a paragraph start nor stops joining.
     */
    val wrapSpaces: Boolean = false,
) {
    companion object {
        const val REMOVE_SINGLES = 1
        const val COLLAPSE = 2
        const val KEEP = 3
    }
}

/** Growable parallel arrays of paragraphs. */
internal class ParaList(capacity: Int = 64) {
    var n = 0
    var kind = IntArray(capacity)
    /** First and last line (inclusive) in the line table. */
    var first = IntArray(capacity)
    var last = IntArray(capacity)
    /** Exact text length in chars. */
    var len = IntArray(capacity)

    fun add(k: Int, f: Int, l: Int, length: Int) {
        if (n == kind.size) {
            val cap = n + (n shr 1) + 16
            kind = kind.copyOf(cap)
            first = first.copyOf(cap)
            last = last.copyOf(cap)
            len = len.copyOf(cap)
        }
        kind[n] = k
        first[n] = f
        last[n] = l
        len[n] = length
        n++
    }
}

internal object TxtParagraphs {
    /** Hard-wrap joining stops after a line ending in punctuation once a paragraph is this long. */
    const val JOIN_SOFT_CHARS = 4096
    /** Hard-wrap joining never grows a paragraph past this (then the next line starts a new paragraph). */
    const val JOIN_MAX_CHARS = MAX_SEGMENT_CHARS

    /** Separator length between two joined lines: none between two CJK ideographs/kana, else one space. */
    private fun joinSep(t: LineTable, a: Int, b: Int): Int =
        if (t.flags[a] and LineFlags.CJK_LAST != 0 && t.flags[b] and LineFlags.CJK_FIRST != 0) 0 else 1

    /**
     * Walks lines [from, to) and appends paragraphs to [out]. Heading lines must already be marked.
     * Blank runs: KEEP -> one empty paragraph per blank line; COLLAPSE -> one per run; REMOVE_SINGLES -> one per
     * run of >= sceneRun (scene break). DELETED lines split runs (the longest contiguous blank stretch counts). Outside
     * KEEP, blank runs next to a scene-marker line are absorbed by it.
     */
    fun walk(t: LineTable, from: Int, to: Int, d: TxtDecisions, out: ParaList) {
        val flags = t.flags
        var i = from
        var prevScene = false
        val joinMin = d.joinMinWidth
        val stopFlags = if (d.joinIgnoreTerminal) LineFlags.SEG else LineFlags.SEG or LineFlags.TERMINAL
        val noJoinNext = LineFlags.BLANK or LineFlags.HEADING or LineFlags.SCENE or LineFlags.SEG or LineFlags.CONT
        while (i < to) {
            // gap of blank / deleted lines
            if (flags[i] and (LineFlags.BLANK or LineFlags.DELETED) != 0) {
                val gapStart = i
                var blanks = 0
                var run = 0
                var maxRun = 0
                while (i < to && flags[i] and (LineFlags.BLANK or LineFlags.DELETED) != 0) {
                    if (flags[i] and LineFlags.BLANK != 0) {
                        blanks++
                        run++
                        if (run > maxRun) maxRun = run
                    } else {
                        run = 0
                    }
                    i++
                }
                if (blanks > 0) {
                    val nextScene = i < to && flags[i] and LineFlags.SCENE != 0 && flags[i] and LineFlags.HEADING == 0
                    if (d.blankMode == TxtDecisions.KEEP) {
                        repeat(blanks) { out.add(ParaKind.EMPTY, gapStart, gapStart, 0) }
                    } else if (!prevScene && !nextScene) {
                        val need = if (d.blankMode == TxtDecisions.COLLAPSE) 1 else d.sceneRun
                        if (maxRun >= need) out.add(ParaKind.EMPTY, gapStart, gapStart, 0)
                    }
                }
                continue
            }
            val f = flags[i]
            if (f and LineFlags.HEADING != 0) {
                out.add(ParaKind.HEADING, i, i, t.length(i))
                prevScene = false
                i++
                continue
            }
            if (f and LineFlags.SCENE != 0) {
                out.add(ParaKind.SCENE, i, i, t.length(i))
                prevScene = true
                i++
                continue
            }
            val kind = if (f and LineFlags.CONT != 0) ParaKind.CONT else ParaKind.TEXT
            var j = i
            var length = t.length(i)
            if (joinMin > 0) {
                while (flags[j] and stopFlags == 0 && t.width[j] >= joinMin) {
                    // A hard-wrapped text without paragraph ends must not become one giant paragraph (it could
                    // not be split into sections): past JOIN_SOFT_CHARS stop at a line-final punctuation,
                    // past JOIN_MAX_CHARS anywhere. Depends only on this paragraph, so section-local walks agree.
                    if (length >= JOIN_SOFT_CHARS && (length >= JOIN_MAX_CHARS || flags[j] and LineFlags.TERMINAL != 0)) break
                    var k = j + 1
                    while (k < to && flags[k] and LineFlags.DELETED != 0) k++
                    if (k >= to) break
                    val fk = flags[k]
                    if (fk and noJoinNext != 0) break
                    if (d.joinStopAtIndent && fk and LineFlags.INDENT != 0 && !(d.wrapSpaces && fk and LineFlags.INDENT1 != 0)) break
                    trimLeading(t, k)
                    length += joinSep(t, j, k) + t.length(k)
                    j = k
                }
            }
            out.add(kind, i, j, length)
            prevScene = false
            i = j + 1
        }
    }

    /** A joined continuation line never keeps leading whitespace (matters only when indents aren't stripped). */
    private fun trimLeading(t: LineTable, k: Int) {
        val a = t.arr(k)
        var s = t.start[k]
        val e = t.end[k]
        while (s < e && TxtChars.isWs(a[s])) s++
        t.start[k] = s
    }

    /**
     * Section filter: indices of the paragraphs in [pFrom, pTo) that make up the section text. Drops leading
     * and trailing empty paragraphs, empty paragraphs right after a heading, and the first body line after a
     * heading when it repeats the heading ([title] or the heading line itself, whitespace-insensitive).
     * Returns the count; indices are written to [outIdx] (size >= pTo - pFrom).
     */
    fun select(t: LineTable, p: ParaList, pFrom: Int, pTo: Int, title: String?, outIdx: IntArray): Int {
        var n = 0
        var pendingFrom = -1
        var heading = -1
        var dupChecked = false
        for (q in pFrom until pTo) {
            val k = p.kind[q]
            if (k == ParaKind.EMPTY) {
                if (n == 0 || heading >= 0) continue
                if (pendingFrom < 0) pendingFrom = q
                continue
            }
            if (heading >= 0 && !dupChecked && k == ParaKind.TEXT) {
                dupChecked = true
                if (p.first[q] == p.last[q] && isDuplicateTitle(t, p.first[q], p.first[heading], title)) continue
            }
            if (pendingFrom >= 0) {
                // everything since pendingFrom is an empty paragraph (a skipped duplicate title only happens
                // in the after-heading state, where empties are not pending)
                for (r in pendingFrom until q) outIdx[n++] = r
                pendingFrom = -1
            }
            outIdx[n++] = q
            if (k == ParaKind.HEADING) {
                heading = q
                dupChecked = false
            } else {
                heading = -1
            }
        }
        return n
    }

    private fun isDuplicateTitle(t: LineTable, line: Int, headingLine: Int, title: String?): Boolean {
        if (title != null && title.isNotEmpty() && t.equalsIgnoringWs(line, title)) return true
        return t.equalsIgnoringWs(line, t.text(headingLine))
    }

    /** Exact text length of the selected paragraphs (paragraphs joined by '\n'). */
    fun textLength(p: ParaList, idx: IntArray, n: Int): Int {
        if (n == 0) return 0
        var total = n - 1
        for (r in 0 until n) total += p.len[idx[r]]
        return total
    }

    /**
     * A chapter heading (화) always opens a page (user, 2026-10-06: "화는 항상 이렇게 맨 위에 올라오도록"), also where short
     * chapters share one section; the first page of a section never gets an extra blank one.
     */
    internal val HEADING_STYLE = BlockStyle(
        headingLevel = 2, align = Align.CENTER, indent = false,
        marginTopEm = 1.5f, marginBottomEm = 1f, keepWithNext = true, pageBreakBefore = true,
    )
    /** A heading with 제목 강조 off: body text, but still at the top of its page. */
    internal val PLAIN_HEADING_STYLE = BlockStyle(pageBreakBefore = true)
    internal val HEADING_RUN = RunStyle(bold = true, sizeScale = 1.2f)
    private val SCENE_STYLE = BlockStyle(align = Align.CENTER, indent = false)
    private val CONT_STYLE = BlockStyle(indent = false, softBreak = true)

    /** Builds the section text / blocks / runs from the selected paragraphs. */
    fun build(t: LineTable, p: ParaList, idx: IntArray, n: Int, emphasizeHeadings: Boolean): SectionContent {
        if (n == 0) return SectionContent("", emptyList())
        val sb = StringBuilder(textLength(p, idx, n))
        val blocks = ArrayList<Block>(n)
        var runs: ArrayList<StyleRun>? = null
        for (r in 0 until n) {
            val q = idx[r]
            if (r > 0) sb.append('\n')
            val st = sb.length
            val k = p.kind[q]
            if (k != ParaKind.EMPTY) {
                var prev = -1
                for (line in p.first[q]..p.last[q]) {
                    if (t.flags[line] and LineFlags.DELETED != 0) continue
                    if (prev >= 0 && joinSep(t, prev, line) == 1) sb.append(' ')
                    sb.append(t.arr(line), t.start[line], t.end[line] - t.start[line])
                    prev = line
                }
            }
            val en = sb.length
            val style = when (k) {
                ParaKind.HEADING -> if (emphasizeHeadings) HEADING_STYLE else PLAIN_HEADING_STYLE
                ParaKind.SCENE -> SCENE_STYLE
                ParaKind.CONT -> CONT_STYLE
                else -> BlockStyle.BODY
            }
            blocks.add(ParagraphBlock(st, en, style))
            if (k == ParaKind.HEADING && emphasizeHeadings && en > st) {
                if (runs == null) runs = ArrayList(2)
                runs.add(StyleRun(st, en, HEADING_RUN))
            }
        }
        return SectionContent(sb.toString(), blocks, runs ?: emptyList())
    }
}
