package com.ggumtak.readeraplus.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlin.random.Random

/**
 * Deterministic fake measurer (spec): Hangul/CJK = 1.0 em, Latin letters/digits = 0.55 em, space = 0.3 em,
 * punctuation = 0.35 em, em = 20 px, ascent 16 / descent 4 (scaled by RunStyle.sizeScale). Like
 * Paint.getTextWidths, the trailing half of a surrogate pair and combining marks get 0.
 */
class FakeMeasurer(
    override val emPx: Float = 20f,
    private val images: Map<String, IntSize> = emptyMap(),
) : TextMeasurer {
    var measureCalls = 0

    override fun measure(text: String, start: Int, end: Int, style: RunStyle, out: FloatArray, outOffset: Int) {
        measureCalls++
        val k = emPx * style.sizeScale
        for (i in start until end) out[outOffset + i - start] = widthEm(text[i]) * k
    }

    override fun metrics(style: RunStyle): FontMetricsPx =
        FontMetricsPx(0.8f * emPx * style.sizeScale, 0.2f * emPx * style.sizeScale)

    override fun imageSize(src: String): IntSize? = images[src]

    companion object {
        fun widthEm(c: Char): Float {
            val code = c.code
            return when {
                c == '\n' || c == OBJECT_CHAR -> 0f
                Character.isLowSurrogate(c) -> 0f
                c == '\u200B' || c == '\u200D' || c == '\u2060' || c == '\uFEFF' -> 0f
                code in 0xFE00..0xFE0F -> 0f
                code in 0x1160..0x11FF -> 0f
                isCombining(c) -> 0f
                code in 0xAC00..0xD7A3 || code in 0x1100..0x115F || code in 0x3131..0x318E -> 1f
                code in 0x4E00..0x9FFF || code in 0x3040..0x30FF || code in 0xFF01..0xFF5E -> 1f
                Character.isHighSurrogate(c) -> 1f
                c == ' ' || c == '\u00A0' -> 0.3f
                c == '\u3000' -> 1f
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> 0.55f
                Character.isLetterOrDigit(c) -> 0.55f
                else -> 0.35f
            }
        }

        private fun isCombining(c: Char): Boolean {
            val t = Character.getType(c)
            return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() ||
                t == Character.COMBINING_SPACING_MARK.toInt()
        }
    }
}

/** Builds well-formed sections: paragraphs separated by one '\n', images as one OBJECT_CHAR, rules empty. */
class SectionBuilder {
    private val sb = StringBuilder()
    private val blocks = ArrayList<Block>()
    private val runs = ArrayList<StyleRun>()

    private fun sep() {
        if (blocks.isNotEmpty()) sb.append('\n')
    }

    /** Adds a paragraph; returns its start offset. [run] styles the whole paragraph. */
    fun para(text: String, style: BlockStyle = BlockStyle.BODY, run: RunStyle? = null): Int {
        sep()
        val s = sb.length
        sb.append(text)
        blocks.add(ParagraphBlock(s, sb.length, style))
        if (run != null && text.isNotEmpty()) runs.add(StyleRun(s, sb.length, run))
        return s
    }

    /** Adds a paragraph with an inline styled range [from, to) relative to the paragraph start. */
    fun paraWithRun(text: String, from: Int, to: Int, run: RunStyle, style: BlockStyle = BlockStyle.BODY): Int {
        val s = para(text, style)
        if (to > from) runs.add(StyleRun(s + from, s + to, run))
        return s
    }

    fun heading(text: String, level: Int = 2, keep: Boolean = true, pageBreak: Boolean = false): Int = para(
        text,
        BlockStyle(
            align = Align.CENTER, indent = false, headingLevel = level, keepWithNext = keep,
            pageBreakBefore = pageBreak,
        ),
        RunStyle(bold = true, sizeScale = 1.2f),
    )

    fun image(src: String, style: BlockStyle = BlockStyle(align = Align.CENTER, indent = false)): Int {
        sep()
        val s = sb.length
        sb.append(OBJECT_CHAR)
        blocks.add(ImageBlock(s, src, style = style))
        return s
    }

    fun rule(): Int {
        sep()
        val s = sb.length
        blocks.add(RuleBlock(s))
        return s
    }

    fun build(): SectionContent = SectionContent(sb.toString(), blocks.toList(), runs.toList())
}

/** Original sample-text generators (no copyrighted material). */
object SampleText {
    private val SYLLABLES = "가나다라마바사아자차카타파하고노도로모보소오조초코토포호구누두루무부수우주추쿠투푸후" +
        "그느드르므브스으즈츠크트프흐기니디리미비시이지치키티피히강산물빛길꽃별달눈비바람하늘마음"
    private val LATIN = listOf("the", "reader", "page", "light", "stone", "river", "book", "night", "code", "e-ink")
    private val PUNCT = listOf(".", ",", "!", "?", "\u2026", "\u2026\u2026", "?!", "~", ".\u201D", "\u2019")

    fun hangulWord(r: Random, min: Int = 1, max: Int = 6): String {
        val n = r.nextInt(min, max + 1)
        val sb = StringBuilder(n)
        repeat(n) { sb.append(SYLLABLES[r.nextInt(SYLLABLES.length)]) }
        return sb.toString()
    }

    /** Korean-like prose paragraph of about [chars] chars: words, spaces, punctuation, quotes. */
    fun koreanParagraph(r: Random, chars: Int): String {
        val sb = StringBuilder(chars + 16)
        var quoteOpen = false
        while (sb.length < chars) {
            if (sb.isNotEmpty()) sb.append(' ')
            if (!quoteOpen && r.nextInt(12) == 0) {
                sb.append('\u201C')
                quoteOpen = true
            }
            sb.append(hangulWord(r))
            when (r.nextInt(10)) {
                0 -> sb.append('.')
                1 -> sb.append(',')
                2 -> if (quoteOpen) {
                    sb.append(".\u201D")
                    quoteOpen = false
                }
            }
        }
        if (quoteOpen) sb.append('\u201D')
        return sb.toString()
    }

    /** Adversarial mixed text: every char class the breaker cares about. */
    fun mixedParagraph(r: Random, chars: Int): String {
        val sb = StringBuilder()
        while (sb.length < chars) {
            when (r.nextInt(22)) {
                0, 1, 2, 3, 4 -> sb.append(hangulWord(r))
                5 -> sb.append(LATIN[r.nextInt(LATIN.size)])
                6 -> sb.append(r.nextInt(100000))
                7 -> sb.append(PUNCT[r.nextInt(PUNCT.size)])
                8, 9, 10 -> sb.append(' ')
                11 -> sb.append("   ")
                12 -> sb.append("\u201C").append(hangulWord(r)).append("\u201D")
                13 -> sb.append("\"").append(hangulWord(r)).append("\"")
                14 -> sb.append("\uD83D\uDE00") // emoji surrogate pair
                15 -> sb.append("e\u0301") // combining acute
                16 -> sb.append('\u00A0')
                17 -> sb.append('\u200B')
                18 -> sb.append("\u6F22\u5B57") // CJK ideographs
                19 -> sb.append("\u2014\u2014")
                20 -> sb.append("(").append(hangulWord(r)).append(")")
                21 -> sb.append(hangulWord(r, 15, 40)) // over-long word
            }
        }
        return sb.toString()
    }
}

/** Layout invariants shared by the fuzz and unit tests. */
object LayoutChecks {
    private const val EPS = 0.05f

    /** Content-box width available to the block that contains [offset]. */
    fun blockWidth(content: SectionContent, cfg: LayoutConfig, em: Float, block: Block): Pair<Float, Float> {
        val st = when (block) {
            is ParagraphBlock -> block.style
            is ImageBlock -> block.style
            else -> BlockStyle.BODY
        }
        var il = maxOf(0f, st.insetLeftEm) * em
        var ir = maxOf(0f, st.insetRightEm) * em
        val maxIns = cfg.width * 0.7f
        if (il + ir > maxIns && il + ir > 0f) {
            val f = maxIns / (il + ir)
            il *= f
            ir *= f
        }
        return il to maxOf(1f, cfg.width - il - ir)
    }

    fun checkPages(layout: SectionLayout) {
        val pages = layout.pages
        val len = layout.content.length
        assertTrue("at least one page", pages.isNotEmpty())
        assertEquals("first page starts at 0", 0, pages[0].start)
        assertEquals("last page ends at length", len, pages[pages.size - 1].end)
        for (i in pages.indices) {
            val p = pages[i]
            assertTrue("page $i range", p.start <= p.end)
            if (i > 0) assertEquals("contiguous at $i", pages[i - 1].end, p.start)
            if (p.lines.isEmpty()) assertEquals("only a content-less section may have an empty page", 1, pages.size)
        }
    }

    /** Full structural check; returns the number of text lines seen. */
    fun checkAll(layout: SectionLayout, measurer: TextMeasurer, forcedOk: Boolean = true): Int {
        checkPages(layout)
        val content = layout.content
        val text = content.text
        val cfg = layout.config
        val em = measurer.emPx
        val adv = layout.advances
        assertEquals(text.length, adv.size)
        var prevEnd = 0
        var textLines = 0
        val covered = BooleanArray(text.length)
        val pos = FloatArray(text.length + 1)
        for ((pi, p) in layout.pages.withIndex()) {
            var prevBottom = -1f
            for ((li, ln) in p.lines.withIndex()) {
                assertTrue("line order page $pi line $li", ln.start >= prevEnd)
                assertTrue("line inside page range", ln.start >= p.start && ln.end <= maxOf(p.end, ln.start))
                assertTrue("vertical order", ln.top >= prevBottom - EPS)
                assertTrue("line box", ln.bottom >= ln.top && ln.baseline >= ln.top - EPS && ln.baseline <= ln.bottom + EPS)
                if (li > 0) assertTrue("page $pi line $li bottom ${ln.bottom} > ${cfg.height}", ln.bottom <= cfg.height + EPS)
                prevBottom = ln.bottom
                prevEnd = maxOf(prevEnd, ln.end)
                if (ln.imageBlock != null || ln.isRule || ln.end == ln.start) continue
                textLines++
                assertTrue("trailing space hangs", BreakClass.of(text[ln.end - 1]) != BreakClass.SPACE)
                assertTrue(
                    "no break inside a surrogate pair",
                    !(Character.isLowSurrogate(text[ln.start]) && ln.start > 0 && Character.isHighSurrogate(text[ln.start - 1])),
                )
                for (i in ln.start until ln.end) {
                    assertTrue("char $i covered twice", !covered[i])
                    covered[i] = true
                }
                val right = LineGeometry.charPositions(layout, ln, pos)
                val block = content.blocks[content.blockIndexAt(ln.start)]
                val (il, w) = blockWidth(content, cfg, em, block)
                var visible = 0
                for (i in ln.start until ln.end) if (adv[i] > 0f && BreakClass.of(text[i]) != BreakClass.SPACE) visible++
                if (right > il + w + EPS) {
                    val singleCluster = visible <= 1
                    if (!(forcedOk && singleCluster)) {
                        fail("line [${ln.start},${ln.end}) '${text.substring(ln.start, ln.end)}' right=$right > ${il + w}")
                    }
                }
                if (ln.expandMode != LineInfo.EXPAND_NONE) {
                    assertEquals("justified line fills W: '${text.substring(ln.start, ln.end)}'", il + w, right, 0.02f)
                }
            }
        }
        // Every visible char of every paragraph block is on exactly one line.
        for (b in content.blocks) {
            if (b !is ParagraphBlock) continue
            for (i in b.start until minOf(b.end, text.length)) {
                if (BreakClass.of(text[i]) == BreakClass.SPACE) continue
                if (!covered[i]) fail("char $i '${text[i]}' of block [${b.start},${b.end}) not on any line")
            }
        }
        return textLines
    }

    fun allLines(layout: SectionLayout): List<LineInfo> = layout.pages.flatMap { it.lines }
}
