package com.ggumtak.readeraplus.engine

import com.ggumtak.readeraplus.engine.BreakClass.CJK
import com.ggumtak.readeraplus.engine.BreakClass.CLOSE
import com.ggumtak.readeraplus.engine.BreakClass.DASH
import com.ggumtak.readeraplus.engine.BreakClass.EMOJI
import com.ggumtak.readeraplus.engine.BreakClass.GLUE
import com.ggumtak.readeraplus.engine.BreakClass.HANGUL
import com.ggumtak.readeraplus.engine.BreakClass.JOIN
import com.ggumtak.readeraplus.engine.BreakClass.OPEN
import com.ggumtak.readeraplus.engine.BreakClass.OTHER
import com.ggumtak.readeraplus.engine.BreakClass.QUOTE
import com.ggumtak.readeraplus.engine.BreakClass.SPACE
import com.ggumtak.readeraplus.engine.BreakClass.ZWSP

/**
 * Reusable primitive buffers for [TypesetPass] (paragraph lines, the current page, the counting scratch
 * advances). Owned by one [Typesetter]; grown on demand and never shrunk, so steady-state layout allocates
 * nothing but the retained results.
 */
/** Justification limits (in em): word gaps may grow a lot, letter gaps only slightly. */
internal const val MAX_SPACE_EXTRA_EM = 3f
internal const val MAX_CHAR_EXTRA_EM = 0.15f

internal class TypesetBuffers {
    // Advances scratch for countPages (holds one block at a time).
    @JvmField var scratch = FloatArray(4096)

    // Lines of the current paragraph.
    @JvmField var lnStart = IntArray(64)
    @JvmField var lnEnd = IntArray(64)
    @JvmField var lnWidth = FloatArray(64)

    // Items of the current page (+ one staged item at index n).
    @JvmField var cap = 0
    @JvmField var pStart = IntArray(0)
    @JvmField var pEnd = IntArray(0)
    @JvmField var pKind = IntArray(0)
    @JvmField var pBlock = IntArray(0)
    @JvmField var pLine = IntArray(0)
    @JvmField var pCount = IntArray(0)
    @JvmField var pFlags = IntArray(0)
    @JvmField var pExpand = IntArray(0)
    @JvmField var pX = FloatArray(0)
    @JvmField var pH = FloatArray(0)
    @JvmField var pBase = FloatArray(0)
    @JvmField var pSb = FloatArray(0)
    @JvmField var pJust = FloatArray(0)
    @JvmField var pImgW = FloatArray(0)
    @JvmField var pTop = FloatArray(0)

    init {
        growPage(64)
    }

    fun ensureLines(n: Int) {
        if (n <= lnStart.size) return
        val c = maxOf(n, lnStart.size * 2)
        lnStart = lnStart.copyOf(c)
        lnEnd = lnEnd.copyOf(c)
        lnWidth = lnWidth.copyOf(c)
    }

    fun ensurePage(n: Int) {
        if (n > cap) growPage(maxOf(n, cap * 2))
    }

    private fun growPage(c: Int) {
        cap = c
        pStart = pStart.copyOf(c)
        pEnd = pEnd.copyOf(c)
        pKind = pKind.copyOf(c)
        pBlock = pBlock.copyOf(c)
        pLine = pLine.copyOf(c)
        pCount = pCount.copyOf(c)
        pFlags = pFlags.copyOf(c)
        pExpand = pExpand.copyOf(c)
        pX = pX.copyOf(c)
        pH = pH.copyOf(c)
        pBase = pBase.copyOf(c)
        pSb = pSb.copyOf(c)
        pJust = pJust.copyOf(c)
        pImgW = pImgW.copyOf(c)
        pTop = pTop.copyOf(c)
    }

    fun ensureScratch(n: Int): FloatArray {
        if (n > scratch.size) scratch = FloatArray(maxOf(n, scratch.size * 2))
        return scratch
    }

    /** Drops buffers that a degenerate input (e.g. a 1M-char paragraph) grew, so they aren't pinned. */
    fun trim() {
        if (scratch.size > 1 shl 16) scratch = FloatArray(4096)
        if (lnStart.size > 4096) {
            lnStart = IntArray(64)
            lnEnd = IntArray(64)
            lnWidth = FloatArray(64)
        }
        if (cap > 1024) {
            cap = 0
            pStart = IntArray(0); pEnd = IntArray(0); pKind = IntArray(0); pBlock = IntArray(0)
            pLine = IntArray(0); pCount = IntArray(0); pFlags = IntArray(0); pExpand = IntArray(0)
            pX = FloatArray(0); pH = FloatArray(0); pBase = FloatArray(0); pSb = FloatArray(0)
            pJust = FloatArray(0); pImgW = FloatArray(0); pTop = FloatArray(0)
            growPage(64)
        }
    }
}

/**
 * One layout (or counting) run over a section. Implements docs/ARCHITECTURE.md "engine/":
 * per-block measuring, greedy line breaking (WORD/CHAR + kinsoku), justification, em-based line boxes,
 * paragraph spacing and pagination with keep-with-next and widow/orphan control.
 *
 * [retain] = true builds [LineInfo]/[PageInfo] objects and a full advances array; false only counts pages
 * (advances go into a per-block scratch buffer). Both modes run the exact same code path so the page counts
 * are identical.
 */
internal class TypesetPass(
    private val measurer: TextMeasurer,
    private val cfg: LayoutConfig,
    private val content: SectionContent,
    private val retain: Boolean,
    private val buf: TypesetBuffers,
    anchorBreak: Int = -1,
) {
    private val text: String = content.text
    private val len: Int = text.length
    private val em: Float = measurer.emPx.let { if (it.isFinite() && it > 0f) it else 16f }
    private val pageW: Float = maxOf(0, cfg.width).toFloat()
    private val pageH: Float = maxOf(0, cfg.height).toFloat()
    private val charMode: Boolean = cfg.lineBreak == LineBreakMode.CHAR
    private val defaultAlign: Align = if (cfg.align == Align.DEFAULT) Align.JUSTIFY else cfg.align
    private val lineHeightEm: Float = nonNeg(cfg.lineHeightEm)
    private val paraSpacePx: Float = nonNeg(cfg.paragraphSpacingEm) * em
    private val indentBasePx: Float = nonNeg(cfg.indentEm) * em
    private val widowOrphan: Boolean = cfg.widowOrphanControl
    private val imageFraction: Float =
        cfg.maxImageHeightFraction.let { if (it.isFinite() && it > 0f) minOf(it, 1f) else 1f }
    /** R3 stub (owner: E1). U4 PARAGRAPH mode: a block that fits on one page is never split. */
    private val keepParas: Boolean = cfg.pageBreak == PageBreakMode.PARAGRAPH
    /** PARAGRAPH mode: height of the block being staged (its lines, no space-before) and whether it fits a page. */
    private var blockH = 0f
    private var blockFits = false

    /** U6: pending forced page break: the first item with end > it (or start >= it) opens a page; -1 once placed. */
    private var anchorLeft: Int = if (anchorBreak in 1 until len) anchorBreak else -1

    /** U6: index of the page the anchor's item opened, -1 = none. */
    var anchorPage = -1
        private set

    /**
     * U6: the anchor forced a page break the un-anchored pass would not make at that point (conservative: false
     * guarantees pages identical to the un-anchored layout; true means they may differ from the anchor on).
     */
    var anchorShifted = false
        private set

    /** Advances: full array (retain) or the scratch holding the current block (count). adv[i - advBase]. */
    private var adv: FloatArray = if (retain) FloatArray(len) else buf.scratch
    private var advBase = 0

    // Sanitised style runs.
    private var nRuns = 0
    private var runS = EMPTY_INTS
    private var runE = EMPTY_INTS
    private var runStyle: Array<RunStyle?> = EMPTY_STYLES
    private var runScale = EMPTY_FLOATS
    private var runAsc = EMPTY_FLOATS
    private var runDesc = EMPTY_FLOATS
    private var plainAsc = 0f
    private var plainDesc = 0f
    private var measureCursor = 0
    private var metricCursor = 0

    // Per-line results (fields instead of allocations).
    private var mLineH = 0f
    private var mBaseOff = 0f
    private var jMode = LineInfo.EXPAND_NONE
    private var jExtra = 0f

    // Pagination state.
    private var n = 0 // committed items on the current page; the staged item lives at index n
    private var y = 0f
    private var pageStart = 0

    /** Number of pages produced. */
    var pageCount = 0
        private set

    /** Pages (retain mode only). */
    val pages: ArrayList<PageInfo>? = if (retain) ArrayList() else null

    /** Measured advances (retain mode only; index = offset). */
    val advances: FloatArray get() = adv

    fun run() {
        prepareRuns()
        val blocks = content.blocks
        var prevEnd = 0
        var prevAfter = 0f
        for (bi in blocks.indices) {
            val b = blocks[bi]
            val s = b.start
            when (b) {
                is ParagraphBlock -> {
                    if (s < prevEnd || s > len) continue
                    val e = minOf(b.end, len)
                    if (e < s) continue
                    prevEnd = e
                    prevAfter = paragraph(bi, b.style, s, e, prevAfter)
                }
                is ImageBlock -> {
                    if (s < prevEnd || s >= len) continue
                    prevEnd = s + 1
                    prevAfter = image(bi, b, prevAfter)
                }
                is RuleBlock -> {
                    if (s < prevEnd || s > len) continue
                    prevEnd = s
                    prevAfter = rule(bi, s, prevAfter)
                }
            }
        }
        finish()
    }

    // ------------------------------------------------------------------------------------------------
    // Style runs & measuring

    private fun prepareRuns() {
        val runs = content.styleRuns
        val cnt = runs.size
        val cache = HashMap<RunStyle, FontMetricsPx>()
        val plain = measurer.metrics(RunStyle.PLAIN)
        cache[RunStyle.PLAIN] = plain
        plainAsc = nonNeg(plain.ascent)
        plainDesc = nonNeg(plain.descent)
        if (cnt == 0) return
        runS = IntArray(cnt)
        runE = IntArray(cnt)
        runStyle = arrayOfNulls(cnt)
        runScale = FloatArray(cnt)
        runAsc = FloatArray(cnt)
        runDesc = FloatArray(cnt)
        var prevE = 0
        var k = 0
        for (r in runs) {
            val s = maxOf(r.start, prevE, 0)
            val e = minOf(r.end, len)
            if (s >= e) continue
            val st = r.style
            val m = cache.getOrPut(st) { measurer.metrics(st) }
            runS[k] = s
            runE[k] = e
            runStyle[k] = st
            runScale[k] = st.sizeScale.let { if (it.isFinite() && it > 0f) it else 1f }
            runAsc[k] = nonNeg(m.ascent)
            runDesc[k] = nonNeg(m.descent)
            prevE = e
            k++
        }
        nRuns = k
    }

    /** Measures text[bs, be) into [adv] (style by style, in chunks) and sanitises the result. */
    private fun measureBlock(bs: Int, be: Int) {
        if (!retain) {
            adv = buf.ensureScratch(be - bs)
            advBase = bs
        }
        var r = measureCursor
        while (r < nRuns && runE[r] <= bs) r++
        measureCursor = r
        var pos = bs
        while (pos < be) {
            val style: RunStyle
            val segEnd: Int
            if (r < nRuns && runS[r] <= pos) {
                style = runStyle[r]!!
                segEnd = minOf(runE[r], be)
            } else {
                style = RunStyle.PLAIN
                segEnd = if (r < nRuns) minOf(runS[r], be) else be
            }
            measureRange(pos, segEnd, style)
            pos = segEnd
            while (r < nRuns && runE[r] <= pos) r++
        }
        val a = adv
        val base = advBase
        val t = text
        for (i in bs until be) {
            val v = a[i - base]
            val c = t[i]
            if (c == '\n' || c == OBJECT_CHAR || !(v > 0f) || v == Float.POSITIVE_INFINITY) a[i - base] = 0f
        }
    }

    private fun measureRange(s: Int, e: Int, style: RunStyle) {
        var p = s
        while (p < e) {
            var q = e
            if (q - p > MEASURE_CHUNK) q = chunkSplit(p, p + MEASURE_CHUNK)
            measurer.measure(text, p, q, style, adv, p - advBase)
            p = q
        }
    }

    /** A split point in (p, limit] that doesn't cut a cluster; prefers the position after a space. */
    private fun chunkSplit(p: Int, limit: Int): Int {
        val t = text
        val tbl = BreakClass.TABLE
        var k = limit
        val lo = maxOf(p + 1, limit - 256)
        while (k > lo) {
            if (tbl[t[k - 1].code].toInt() == SPACE && tbl[t[k].code].toInt() != SPACE) return k
            k--
        }
        k = limit
        while (k > p + 1) {
            val c = tbl[t[k].code].toInt()
            if (c != GLUE && c != JOIN && tbl[t[k - 1].code].toInt() != JOIN) return k
            k--
        }
        return limit
    }

    // ------------------------------------------------------------------------------------------------
    // Blocks

    private fun spaceBefore(st: BlockStyle, prevAfter: Float): Float {
        if (st.softBreak) return 0f
        var own = paraSpacePx + nonNeg(st.marginTopEm) * em
        if (st.headingLevel > 0 && own < em) own = em
        return prevAfter + own
    }

    private fun spaceAfter(st: BlockStyle): Float {
        var a = nonNeg(st.marginBottomEm) * em
        if (st.headingLevel > 0 && a < 0.6f * em) a = 0.6f * em
        return a
    }

    private fun blockFlags(st: BlockStyle): Int {
        var f = 0
        if (st.keepWithNext) f = f or F_KEEP
        if (st.pageBreakBefore) f = f or F_PBB
        f = f or if (st.headingLevel > 0) F_HEADING else F_WO
        return f
    }

    // Insets of the current block (set by computeBox).
    private var boxLeft = 0f
    private var boxWidth = 0f

    private fun computeBox(st: BlockStyle) {
        var il = nonNeg(st.insetLeftEm) * em
        var ir = nonNeg(st.insetRightEm) * em
        val maxIns = pageW * 0.7f
        if (il + ir > maxIns) {
            val f = if (il + ir > 0f) maxIns / (il + ir) else 0f
            il *= f
            ir *= f
        }
        boxLeft = il
        boxWidth = maxOf(1f, pageW - il - ir)
    }

    private fun paragraph(bi: Int, st: BlockStyle, bs: Int, be: Int, prevAfter: Float): Float {
        val sb = spaceBefore(st, prevAfter)
        val after = spaceAfter(st)
        val flags = blockFlags(st)
        val tbl = BreakClass.TABLE
        val t = text
        var visible = false
        for (i in bs until be) {
            if (tbl[t[i].code].toInt() != SPACE) {
                visible = true
                break
            }
        }
        computeBox(st)
        if (!visible) {
            plainLineBox()
            stage(K_EMPTY, bs, bs, boxLeft, mLineH, mBaseOff, sb, bi, 0, 1, flags)
            commit()
            return after
        }
        measureBlock(bs, be)
        val align = if (st.align != Align.DEFAULT && (cfg.publisherStyles || st.headingLevel > 0)) {
            st.align
        } else {
            defaultAlign
        }
        val w = boxWidth
        val indent = if (st.indent && !st.softBreak && align != Align.CENTER && align != Align.RIGHT) {
            minOf(indentBasePx, w * 0.5f)
        } else {
            0f
        }
        val pre = st.preformatted
        val nl = breakLines(bs, be, w - indent, w, pre)
        val ls = buf.lnStart
        val le = buf.lnEnd
        val lw = buf.lnWidth
        for (j in 0 until nl) {
            val s = ls[j]
            val e = le[j]
            val width = lw[j]
            lineBox(s, e)
            val ind = if (j == 0) indent else 0f
            var x = boxLeft + ind
            jMode = LineInfo.EXPAND_NONE
            jExtra = 0f
            when (align) {
                Align.CENTER -> x = boxLeft + maxOf(0f, (w - width) * 0.5f)
                Align.RIGHT -> x = boxLeft + maxOf(0f, w - width)
                Align.JUSTIFY -> if (!pre && j < nl - 1) justify(s, e, w - ind - width)
                else -> {}
            }
            var f = flags
            if (j > 0) f = f and F_PBB.inv()
            stage(K_TEXT, s, e, x, mLineH, mBaseOff, if (j == 0) sb else 0f, bi, j, nl, f)
            buf.pJust[n] = jExtra
            buf.pExpand[n] = jMode
            commit()
        }
        return after
    }

    private fun image(bi: Int, b: ImageBlock, prevAfter: Float): Float {
        val size = measurer.imageSize(b.src) ?: return prevAfter
        if (size.width <= 0 || size.height <= 0) return prevAfter
        val st = b.style
        computeBox(st)
        val w = boxWidth
        // Intrinsic size: the source's declared CSS px size when known (Content.kt), else the decoded bitmap
        // size; a single declared dimension keeps the bitmap's aspect ratio.
        var iw = size.width.toFloat()
        var ih = size.height.toFloat()
        val declW = b.intrinsicWidth
        val declH = b.intrinsicHeight
        if (declW > 0 && declH > 0) {
            iw = declW.toFloat()
            ih = declH.toFloat()
        } else if (declW > 0) {
            ih = ih * declW / iw
            iw = declW.toFloat()
        } else if (declH > 0) {
            iw = iw * declH / ih
            ih = declH.toFloat()
        }
        val scale = if (iw >= 0.4f * w) w / iw else minOf(2f, w / iw)
        var dw = iw * scale
        var dh = ih * scale
        val maxH = maxOf(1f, minOf(pageH * imageFraction, pageH))
        if (dh > maxH) {
            dw *= maxH / dh
            dh = maxH
        }
        if (dw < 1f) dw = 1f
        if (dh < 1f) dh = 1f
        val sb = spaceBefore(st, prevAfter)
        val x = boxLeft + maxOf(0f, (w - dw) * 0.5f)
        stage(K_IMAGE, b.start, b.start + 1, x, dh, dh, sb, bi, 0, 1, blockFlags(st) and F_WO.inv())
        buf.pImgW[n] = dw
        commit()
        return spaceAfter(st)
    }

    private fun rule(bi: Int, s: Int, prevAfter: Float): Float {
        plainLineBox()
        stage(K_RULE, s, s, 0f, mLineH, mBaseOff, prevAfter + paraSpacePx, bi, 0, 1, 0)
        commit()
        return 0f
    }

    // ------------------------------------------------------------------------------------------------
    // Line breaking

    /**
     * Greedy line breaking of text[bs, be) into buf.ln*; returns the number of lines. Line ends exclude
     * trailing (hanging) spaces; continuation lines skip leading spaces unless [pre].
     */
    private fun breakLines(bs: Int, be: Int, availFirst: Float, availRest: Float, pre: Boolean): Int {
        val t = text
        val tbl = BreakClass.TABLE
        val a = adv
        val base = advBase
        var count = 0
        var ls = bs
        var first = true
        while (true) {
            if (!first && !pre) {
                while (ls < be && tbl[t[ls].code].toInt() == SPACE) ls++
            }
            if (ls >= be) break
            val avail = if (first) availFirst else availRest
            var w = 0f
            var wNoTrail = 0f
            var trail = -1
            var firstContent = -1
            var lb = -1
            var lbEnd = 0
            var lbWidth = 0f
            var i = ls
            var overflow = false
            while (i < be) {
                val cls = tbl[t[i].code].toInt()
                val ad = a[i - base]
                if (firstContent >= 0 && canBreak(i, bs, cls, ad, charMode)) {
                    lb = i
                    lbEnd = if (trail >= 0) trail else i
                    lbWidth = wNoTrail
                }
                if (cls == SPACE) {
                    if (trail < 0) trail = i
                    w += ad
                } else {
                    if (firstContent >= 0 && w + ad > avail + EPS) {
                        overflow = true
                        break
                    }
                    w += ad
                    wNoTrail = w
                    trail = -1
                    if (firstContent < 0) firstContent = i
                }
                i++
            }
            if (!overflow) {
                if (firstContent >= 0) {
                    count = emitLine(count, ls, if (trail >= 0) trail else be, wNoTrail)
                }
                break
            }
            val end: Int
            val next: Int
            val width: Float
            if (lb > ls) {
                end = lbEnd
                next = lb
                width = lbWidth
            } else {
                var f = -1
                if (!charMode) f = charFallback(bs, firstContent, i)
                if (f < 0) f = forcedBreak(bs, be, firstContent, i)
                next = f
                var e = f
                while (e > ls && tbl[t[e - 1].code].toInt() == SPACE) e--
                end = e
                var sum = 0f
                for (k in ls until e) sum += a[k - base]
                width = sum
            }
            count = emitLine(count, ls, end, width)
            ls = next
            first = false
        }
        return count
    }

    private fun emitLine(count: Int, s: Int, e: Int, w: Float): Int {
        buf.ensureLines(count + 1)
        buf.lnStart[count] = s
        buf.lnEnd[count] = e
        buf.lnWidth[count] = w
        return count + 1
    }

    /**
     * Break opportunity between text[i - 1] and text[i] (bs < i). [cls] = class of text[i], [ad] its advance.
     */
    private fun canBreak(i: Int, bs: Int, cls: Int, ad: Float, charMode: Boolean): Boolean {
        if (cls == SPACE || cls == GLUE || cls == JOIN) return false
        if (!(ad > 0f)) return false
        val t = text
        val tbl = BreakClass.TABLE
        val cb = if (cls == EMOJI) (if (charMode) CJK else OTHER) else cls
        var ca = tbl[t[i - 1].code].toInt()
        if (cb == CLOSE) {
            // Kinsoku. Exception: a stand-alone ellipsis after a space may start a line (UAX #14 class IN).
            return ca == SPACE && (t[i] == '\u2026' || t[i] == '\u2025')
        }
        if (ca == SPACE) return true
        if (cb == QUOTE) return false // closing use (not preceded by a space)
        if (ca == OPEN || ca == JOIN) return false
        if (ca == ZWSP) return true
        if (ca == GLUE) {
            var j = i - 2
            while (j >= bs && tbl[t[j].code].toInt() == GLUE) j--
            ca = if (j >= bs) tbl[t[j].code].toInt() else OTHER
            if (ca == OPEN || ca == JOIN || ca == SPACE) return false
        }
        if (ca == QUOTE) {
            // Opening quote (at paragraph start or after a space/opening bracket): no break after it.
            val j = i - 2
            if (j < bs) return false
            val pc = tbl[t[j].code].toInt()
            if (pc == SPACE || pc == OPEN || pc == JOIN) return false
            ca = CLOSE
        }
        if (ca == EMOJI) ca = if (charMode) CJK else OTHER
        if (ca == DASH) return Character.isLetter(t[i]) || cb == CJK
        if (ca == CJK || cb == CJK) return true
        if (charMode) {
            if (ca == HANGUL && (cb == HANGUL || cb == OPEN)) return true
            if (ca == CLOSE && (cb == HANGUL || cb == OPEN)) return true
        }
        return false
    }

    /** WORD mode: a word wider than the line falls back to CHAR opportunities inside it. */
    private fun charFallback(bs: Int, firstContent: Int, i: Int): Int {
        val t = text
        val tbl = BreakClass.TABLE
        val a = adv
        val base = advBase
        var k = i
        while (k > firstContent) {
            if (canBreak(k, bs, tbl[t[k].code].toInt(), a[k - base], true)) return k
            k--
        }
        return -1
    }

    /**
     * Forced break before text[i] (the first char that doesn't fit). Prefers a nearby position that respects
     * joiners and kinsoku; otherwise the last fitting grapheme boundary; at least one grapheme per line.
     */
    private fun forcedBreak(bs: Int, be: Int, firstContent: Int, i: Int): Int {
        var k = i
        var tries = 0
        while (k > firstContent && tries < 4) {
            if (joinOk(k) && graphemeOk(k) && kinsokuOk(k)) return k
            k--
            tries++
        }
        k = i
        while (k > firstContent && !graphemeOk(k)) k--
        if (k > firstContent) return k
        k = firstContent + 1
        while (k < be && !graphemeOk(k)) k++
        return k
    }

    /** Not inside a grapheme: never before a combining mark / low surrogate / zero-advance char, never at a ZWJ. */
    private fun graphemeOk(k: Int): Boolean {
        val t = text
        val c = t[k]
        val cls = BreakClass.TABLE[c.code].toInt()
        if (cls == SPACE) return true
        if (cls == GLUE || c == '\u200D' || t[k - 1] == '\u200D') return false
        return adv[k - advBase] > 0f
    }

    /** Not next to a no-break joiner (NBSP, word joiner, ...). */
    private fun joinOk(k: Int): Boolean {
        val tbl = BreakClass.TABLE
        return tbl[text[k].code].toInt() != JOIN && tbl[text[k - 1].code].toInt() != JOIN
    }

    private fun kinsokuOk(k: Int): Boolean {
        val tbl = BreakClass.TABLE
        val c = tbl[text[k].code].toInt()
        if (c == CLOSE) return false
        val p = tbl[text[k - 1].code].toInt()
        if (c == QUOTE && p != SPACE) return false
        return p != OPEN
    }

    // ------------------------------------------------------------------------------------------------
    // Line boxes & justification

    private fun plainLineBox() {
        setBox(1f, plainAsc, plainDesc)
    }

    /** Vertical metrics of text[s, e): max scale/ascent/descent over the styles on the line. */
    private fun lineBox(s: Int, e: Int) {
        var r = metricCursor
        while (r < nRuns && runE[r] <= s) r++
        metricCursor = r
        var scale = 0f
        var asc = 0f
        var desc = 0f
        var pos = s
        var gap = false
        while (r < nRuns && runS[r] < e) {
            if (runS[r] > pos) gap = true
            if (runScale[r] > scale) scale = runScale[r]
            if (runAsc[r] > asc) asc = runAsc[r]
            if (runDesc[r] > desc) desc = runDesc[r]
            if (runE[r] > pos) pos = runE[r]
            r++
        }
        if (pos < e) gap = true
        if (gap) {
            if (scale < 1f) scale = 1f
            if (plainAsc > asc) asc = plainAsc
            if (plainDesc > desc) desc = plainDesc
        }
        setBox(scale, asc, desc)
    }

    private fun setBox(scale: Float, asc: Float, desc: Float) {
        val natural = asc + desc
        var h = lineHeightEm * em * scale
        if (h < natural) h = natural
        if (!(h >= 1f)) h = 1f
        mLineH = h
        mBaseOff = (h - natural) * 0.5f + asc
    }

    /** Sets jMode/jExtra for a justified (non-final) line with [slack] px to distribute. */
    private fun justify(s: Int, e: Int, slack: Float) {
        if (!(slack > 0.01f)) return
        val t = text
        val a = adv
        val base = advBase
        var fns = s
        while (fns < e && BreakClass.isExpandSpace(t[fns])) fns++
        var spaces = 0
        for (k in fns until e) if (BreakClass.isExpandSpace(t[k])) spaces++
        // Korean typesetting widens word gaps; spreading slack between letters ("R e a d e r") reads badly, so
        // letters only absorb slack when it is barely visible. Absurd gaps leave the line ragged instead.
        if (spaces > 0) {
            val ex = slack / spaces
            if (ex <= MAX_SPACE_EXTRA_EM * em || e - s < 2) {
                jMode = LineInfo.EXPAND_SPACES
                jExtra = ex
                return
            }
        }
        var vis = 0
        for (k in s until e) if (a[k - base] > 0f) vis++
        if (vis >= 2) {
            val ex = slack / (vis - 1)
            if (ex <= MAX_CHAR_EXTRA_EM * em) {
                jMode = LineInfo.EXPAND_CHARS
                jExtra = ex
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Pagination

    private fun stage(
        kind: Int, s: Int, e: Int, x: Float, h: Float, baseOff: Float, sb: Float,
        block: Int, line: Int, count: Int, flags: Int,
    ) {
        val b = buf
        b.ensurePage(n + 1)
        val k = n
        b.pKind[k] = kind
        b.pStart[k] = s
        b.pEnd[k] = e
        b.pX[k] = x
        b.pH[k] = h
        b.pBase[k] = baseOff
        b.pSb[k] = sb
        b.pBlock[k] = block
        b.pLine[k] = line
        b.pCount[k] = count
        b.pFlags[k] = flags
        b.pJust[k] = 0f
        b.pExpand[k] = LineInfo.EXPAND_NONE
        b.pImgW[k] = 0f
    }

    /** Places the staged item (index n) on the current page, breaking pages as needed. */
    private fun commit() {
        val b = buf
        if (b.pKind[n] == K_EMPTY && n == 0) return // an empty paragraph at the top of a page is dropped
        if ((b.pFlags[n] and F_PBB) != 0 && n > 0) {
            emitPage(n, b.pStart[n], true)
            if (b.pKind[0] == K_EMPTY) return
        }
        var s = n
        var sb = if (s > 0) b.pSb[s] else 0f
        if (s > 0 && y + sb + b.pH[s] > pageH + EPS) {
            val cut = decideCut(s)
            emitPage(cut, b.pStart[cut], true)
            s = n
            if (s == 0 && b.pKind[0] == K_EMPTY) return
            sb = if (s > 0) b.pSb[s] else 0f
            if (s > 0 && y + sb + b.pH[s] > pageH + EPS) {
                emitPage(s, b.pStart[s], true)
                s = 0
                if (b.pKind[0] == K_EMPTY) return
                sb = 0f
            }
        }
        val top = y + sb
        b.pTop[s] = top
        y = top + b.pH[s]
        n = s + 1
    }

    /** How many of the page's k committed items stay when the staged item k doesn't fit (>= 1). */
    private fun decideCut(k: Int): Int {
        val b = buf
        var cut = k
        val kind = b.pKind[k]
        if (kind == K_TEXT && (b.pFlags[k] and F_HEADING) != 0 && b.pLine[k] > 0) {
            // Don't split a heading: move all of its lines if it started on this page after other content.
            val p = k - b.pLine[k]
            if (p > 0 && b.pBlock[p] == b.pBlock[k] && b.pLine[p] == 0 && movable(p)) cut = p
        } else if (widowOrphan && kind == K_TEXT && (b.pFlags[k] and F_WO) != 0 && b.pCount[k] >= 3 &&
            b.pBlock[k - 1] == b.pBlock[k]
        ) {
            val ln = b.pLine[k]
            if (ln == 1) {
                // Orphan: the paragraph's first line would sit alone at the page bottom.
                if (k - 1 >= 2) cut = k - 1
            } else if (ln == b.pCount[k] - 1) {
                // Widow: its last line would start the next page alone -> move one more line.
                var nc = k - 1
                if (nc >= 1 && b.pBlock[nc - 1] == b.pBlock[k] && b.pLine[nc - 1] == 0) nc--
                if (nc >= 2) cut = nc
            }
        }
        // Keep-with-next chain (headings) ending the page moves with the following content.
        var j = cut - 1
        while (j >= 0 && b.pKind[j] == K_EMPTY) j--
        var chain = -1
        while (j >= 0 && (b.pFlags[j] and F_KEEP) != 0 && b.pLine[j] == b.pCount[j] - 1) {
            var f = j
            while (f > 0 && b.pBlock[f - 1] == b.pBlock[j]) f--
            chain = f
            j = f - 1
        }
        if (chain > 0 && movable(chain)) cut = chain
        return if (cut < 1) 1 else cut
    }

    /**
     * Break avoidance (unsplit headings, keep-with-next chains) may carry items [from, n) to the next page
     * only while they take at most [KEEP_MAX_MOVE] of the page height. Long "headings" (EPUBs that style body
     * text with h-tags) or long chains of them would otherwise leave pages nearly empty.
     */
    private fun movable(from: Int): Boolean = y - buf.pTop[from] <= pageH * KEEP_MAX_MOVE

    /**
     * Finishes the current page with items [0, cut) and range end [endOffset]; items [cut, n] (including
     * the staged one when [staged]) move to the top of the next page.
     */
    private fun emitPage(cut: Int, endOffset: Int, staged: Boolean) {
        val b = buf
        val pages = pages
        if (pages != null) {
            val lines = ArrayList<LineInfo>(cut)
            for (j in 0 until cut) lines.add(makeLine(j))
            pages.add(PageInfo(pageStart, endOffset, lines))
        }
        pageCount++
        pageStart = endOffset
        val total = if (staged) n + 1 else n
        shiftItems(cut, total - cut)
        n -= cut
        // Carried empty paragraphs would now open the page: drop them like any page-top blank line.
        var drop = 0
        while (drop < n && b.pKind[drop] == K_EMPTY) drop++
        if (drop > 0) {
            shiftItems(drop, (if (staged) n + 1 else n) - drop)
            n -= drop
        }
        var yy = 0f
        for (j in 0 until n) {
            val top = if (j == 0) yy else yy + b.pSb[j]
            b.pTop[j] = top
            yy = top + b.pH[j]
        }
        y = yy
    }

    /** Moves page items [from, from + count) to [0, count). */
    private fun shiftItems(from: Int, count: Int) {
        if (from <= 0 || count <= 0) return
        val b = buf
        System.arraycopy(b.pStart, from, b.pStart, 0, count)
        System.arraycopy(b.pEnd, from, b.pEnd, 0, count)
        System.arraycopy(b.pKind, from, b.pKind, 0, count)
        System.arraycopy(b.pBlock, from, b.pBlock, 0, count)
        System.arraycopy(b.pLine, from, b.pLine, 0, count)
        System.arraycopy(b.pCount, from, b.pCount, 0, count)
        System.arraycopy(b.pFlags, from, b.pFlags, 0, count)
        System.arraycopy(b.pExpand, from, b.pExpand, 0, count)
        System.arraycopy(b.pX, from, b.pX, 0, count)
        System.arraycopy(b.pH, from, b.pH, 0, count)
        System.arraycopy(b.pBase, from, b.pBase, 0, count)
        System.arraycopy(b.pSb, from, b.pSb, 0, count)
        System.arraycopy(b.pJust, from, b.pJust, 0, count)
        System.arraycopy(b.pImgW, from, b.pImgW, 0, count)
    }

    private fun makeLine(j: Int): LineInfo {
        val b = buf
        val top = b.pTop[j]
        val h = b.pH[j]
        return when (b.pKind[j]) {
            K_IMAGE -> LineInfo(
                b.pStart[j], b.pEnd[j], b.pX[j], top, top + h, top + h, 0f, LineInfo.EXPAND_NONE,
                imageBlock = content.blocks[b.pBlock[j]] as ImageBlock, imageWidth = b.pImgW[j], imageHeight = h,
            )
            K_RULE -> LineInfo(
                b.pStart[j], b.pEnd[j], b.pX[j], top, top + b.pBase[j], top + h, 0f, LineInfo.EXPAND_NONE,
                isRule = true,
            )
            else -> LineInfo(
                b.pStart[j], b.pEnd[j], b.pX[j], top, top + b.pBase[j], top + h, b.pJust[j], b.pExpand[j],
            )
        }
    }

    private fun finish() {
        if (n > 0) {
            emitPage(n, len, false)
            return
        }
        val pages = pages
        if (pageCount == 0) {
            pages?.add(PageInfo(0, len, emptyList()))
            pageCount = 1
        } else if (pages != null) {
            val last = pages[pages.size - 1]
            if (last.end != len) pages[pages.size - 1] = PageInfo(last.start, len, last.lines)
        }
    }

    private companion object {
        const val EPS = 0.01f
        const val MEASURE_CHUNK = 4096
        const val KEEP_MAX_MOVE = 0.5f

        const val K_TEXT = 0
        const val K_EMPTY = 1
        const val K_IMAGE = 2
        const val K_RULE = 3

        const val F_KEEP = 1
        const val F_PBB = 2
        const val F_HEADING = 4
        const val F_WO = 8

        val EMPTY_INTS = IntArray(0)
        val EMPTY_FLOATS = FloatArray(0)
        val EMPTY_STYLES = arrayOfNulls<RunStyle>(0)

        fun nonNeg(v: Float): Float = if (v > 0f && v.isFinite()) v else 0f
    }
}

