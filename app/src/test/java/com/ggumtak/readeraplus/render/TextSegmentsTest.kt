package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.BlockStyle
import com.ggumtak.readeraplus.engine.Block
import com.ggumtak.readeraplus.engine.FontMetricsPx
import com.ggumtak.readeraplus.engine.IntSize
import com.ggumtak.readeraplus.engine.LayoutConfig
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.engine.LineGeometry
import com.ggumtak.readeraplus.engine.LineInfo
import com.ggumtak.readeraplus.engine.OBJECT_CHAR
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.RunStyle
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.engine.SectionLayout
import com.ggumtak.readeraplus.engine.StyleRun
import com.ggumtak.readeraplus.engine.TextMeasurer
import com.ggumtak.readeraplus.engine.Typesetter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.util.Random

class TextSegmentsTest {

    /** Spec fake: Hangul/CJK 1 em, Latin/digits 0.55, space 0.3, other 0.35, marks & low surrogates 0; em = 20. */
    private class Fake : TextMeasurer {
        override val emPx = 20f
        override fun measure(text: String, start: Int, end: Int, style: RunStyle, out: FloatArray, outOffset: Int) {
            for (i in start until end) out[outOffset + i - start] = width(text, i) * style.sizeScale
        }
        override fun metrics(style: RunStyle) = FontMetricsPx(16f * style.sizeScale, 4f * style.sizeScale)
        override fun imageSize(src: String): IntSize? = null

        fun width(t: String, i: Int): Float {
            val c = t[i]
            return when {
                c == '\n' -> 0f
                Character.isLowSurrogate(c) -> 0f
                Character.isHighSurrogate(c) -> 20f
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> 0f
                c == '‍' -> 0f
                c == ' ' || c == ' ' || c == '　' -> 6f
                c in '가'..'힣' || c in '一'..'鿿' -> 20f
                c.isLetterOrDigit() -> 11f
                else -> 7f
            }
        }
    }

    private fun isExpandSpace(c: Char) = c == ' ' || c == ' ' || c == '　'

    /** Reference geometry from the architecture spec (independent of the engine implementation). */
    private fun specPositions(text: String, adv: FloatArray, ln: LineInfo, out: FloatArray): Float {
        var x = ln.x
        val s = ln.start
        val e = ln.end
        when (ln.expandMode) {
            LineInfo.EXPAND_SPACES -> {
                var i = s
                while (i < e && isExpandSpace(text[i])) { out[i - s] = x; x += adv[i]; i++ }
                while (i < e) {
                    out[i - s] = x
                    x += adv[i]
                    if (isExpandSpace(text[i])) x += ln.justifyExtra
                    i++
                }
            }
            LineInfo.EXPAND_CHARS -> {
                var last = e - 1
                while (last >= s && !(adv[last] > 0f)) last--
                for (i in s until e) {
                    out[i - s] = x
                    x += adv[i]
                    if (i < last && adv[i] > 0f) x += ln.justifyExtra
                }
            }
            else -> for (i in s until e) { out[i - s] = x; x += adv[i] }
        }
        return x
    }

    private fun advances(text: String): FloatArray {
        val f = Fake()
        return FloatArray(text.length) { f.width(text, it) }
    }

    /** Asserts that drawing each segment at xs[p] with natural advances puts every visible glyph at xs[i]. */
    private fun checkLine(text: String, adv: FloatArray, xs: FloatArray, ln: LineInfo): Int {
        var p = ln.start
        var segments = 0
        while (p < ln.end) {
            val q = TextSegments.segmentEnd(text, adv, xs, ln.start, p, ln.end)
            assertTrue("progress", q > p)
            assertFalse("never split a surrogate pair", q < ln.end && Character.isLowSurrogate(text[q]))
            var nat = xs[p - ln.start]
            for (i in p until q) {
                if (adv[i] > 0f) assertEquals("glyph $i of '${text.substring(ln.start, ln.end)}'", xs[i - ln.start], nat, 0.01f)
                nat += adv[i]
            }
            segments++
            p = q
        }
        return segments
    }

    private fun line(s: Int, e: Int, x: Float, extra: Float, mode: Int) =
        LineInfo(s, e, x, 0f, 16f, 20f, extra, mode)

    @Test
    fun unjustifiedLineIsOneSegment() {
        val t = "가나 다라 hello"
        val adv = advances(t)
        val ln = line(0, t.length, 20f, 0f, LineInfo.EXPAND_NONE)
        val xs = FloatArray(t.length)
        specPositions(t, adv, ln, xs)
        assertEquals(1, checkLine(t, adv, xs, ln))
    }

    @Test
    fun spaceJustificationSplitsAfterEachSpace() {
        val t = "가나 다라 마바 사"
        val adv = advances(t)
        val ln = line(0, t.length, 0f, 3.5f, LineInfo.EXPAND_SPACES)
        val xs = FloatArray(t.length)
        specPositions(t, adv, ln, xs)
        assertEquals(4, checkLine(t, adv, xs, ln))
    }

    @Test
    fun charJustificationKeepsClustersTogether() {
        // é as e + combining acute, an astral ideograph (surrogate pair), ZWJ sequence
        val t = "가é나𠀋다‍라"
        val adv = advances(t)
        val ln = line(0, t.length, 0f, 2f, LineInfo.EXPAND_CHARS)
        val xs = FloatArray(t.length)
        specPositions(t, adv, ln, xs)
        val segs = checkLine(t, adv, xs, ln)
        // visible clusters: 가, é, 나, 𠀋, 다, 라  → one segment each
        assertEquals(6, segs)
    }

    @Test
    fun randomLinesAgainstSpecGeometry() {
        val rnd = Random(42)
        val alphabet = "가나다라마바사아자차카타파하 abcXYZ019.,!?“” 　"
        repeat(2000) {
            val n = 1 + rnd.nextInt(60)
            val sb = StringBuilder()
            while (sb.length < n) {
                when (rnd.nextInt(20)) {
                    0 -> sb.append("😀")
                    1 -> sb.append("á")
                    else -> sb.append(alphabet[rnd.nextInt(alphabet.length)])
                }
            }
            val t = sb.toString()
            val adv = advances(t)
            val mode = rnd.nextInt(3)
            val ln = line(0, t.length, rnd.nextFloat() * 30f, rnd.nextFloat() * 8f, mode)
            val xs = FloatArray(t.length)
            specPositions(t, adv, ln, xs)
            checkLine(t, adv, xs, ln)
        }
    }

    @Test
    fun segmentsWithinLineOffset() {
        val t = "xxxxx가나 다라"
        val adv = advances(t)
        val ln = line(5, t.length, 10f, 4f, LineInfo.EXPAND_SPACES)
        val xs = FloatArray(t.length - 5)
        specPositions(t, adv, ln, xs)
        assertEquals(2, checkLine(t, adv, xs, ln))
    }

    /**
     * Regression: a stray U+FFFC inside a paragraph is measured as 0 but fonts draw it as a box with a real
     * advance, which pushed the rest of its segment off the laid-out positions. It must be its own (blank) segment.
     */
    @Test
    fun strayObjectCharIsIsolatedAndBlank() {
        val t = "가나${OBJECT_CHAR}다라"
        val adv = advances(t)
        adv[2] = 0f // the measurer zeroes OBJECT_CHAR
        val ln = line(0, t.length, 0f, 0f, LineInfo.EXPAND_NONE)
        val xs = FloatArray(t.length)
        specPositions(t, adv, ln, xs)
        assertEquals(2, TextSegments.segmentEnd(t, adv, xs, 0, 0, t.length))
        assertEquals(3, TextSegments.segmentEnd(t, adv, xs, 0, 2, t.length))
        assertTrue(TextSegments.isBlank(t, 2, 3))
        assertEquals(5, TextSegments.segmentEnd(t, adv, xs, 0, 3, t.length))
        assertEquals(3, checkLine(t, adv, xs, ln))
        assertEquals(1, TextSegments.firstInk("${OBJECT_CHAR}a", 0, 2))
        assertEquals(-1, TextSegments.firstInk("$OBJECT_CHAR", 0, 1))
    }

    @Test
    fun blankDetection() {
        assertTrue(TextSegments.isBlank("   　​", 0, 5))
        assertFalse(TextSegments.isBlank(" a ", 0, 3))
        assertTrue(TextSegments.isBlank(" a ", 2, 3))
        assertEquals(1, TextSegments.firstInk(" a b ", 0, 5))
        assertEquals(3, TextSegments.lastInk(" a b ", 0, 5))
        assertEquals(-1, TextSegments.firstInk("   ", 0, 3))
        assertEquals(-1, TextSegments.lastInk("   ", 0, 3))
    }

    @Test
    fun firstRunAfter() {
        val bold = RunStyle(bold = true)
        val runs = listOf(StyleRun(2, 4, bold), StyleRun(6, 9, bold), StyleRun(9, 10, bold))
        assertEquals(0, TextSegments.firstRunAfter(runs, 0))
        assertEquals(0, TextSegments.firstRunAfter(runs, 3))
        assertEquals(1, TextSegments.firstRunAfter(runs, 4))
        assertEquals(1, TextSegments.firstRunAfter(runs, 8))
        assertEquals(2, TextSegments.firstRunAfter(runs, 9))
        assertEquals(3, TextSegments.firstRunAfter(runs, 10))
        assertEquals(0, TextSegments.firstRunAfter(emptyList(), 5))
    }

    /** Same property against the live engine (skipped when only the engine contract stub is compiled). */
    @Test
    fun realEngineGeometry() {
        val rnd = Random(7)
        val words = arrayOf("가나다", "라마", "바사아자", "차카타파하", "hello", "world,", "“인용”", "1234", "…", "😀")
        val sb = StringBuilder()
        val blocks = ArrayList<Block>()
        repeat(40) { p ->
            val start = sb.length
            repeat(5 + rnd.nextInt(40)) {
                if (sb.length > start) sb.append(' ')
                sb.append(words[rnd.nextInt(words.size)])
            }
            blocks.add(ParagraphBlock(start, sb.length, if (p % 7 == 0) BlockStyle(indent = false) else BlockStyle.BODY))
            sb.append('\n')
        }
        sb.setLength(sb.length - 1)
        val content = SectionContent(sb.toString(), blocks, listOf(StyleRun(3, 9, RunStyle(bold = true))))
        val layout: SectionLayout = try {
            Typesetter(Fake(), LayoutConfig(300, 400, 1.6f, 0.5f, 1f, Align.JUSTIFY, LineBreakMode.CHAR)).layout(content)
        } catch (e: NotImplementedError) {
            Assume.assumeNoException(e)
            return
        }
        var lines = 0
        for (page in layout.pages) for (ln in page.lines) {
            if (ln.imageBlock != null || ln.isRule || ln.end <= ln.start) continue
            val xs = FloatArray(ln.end - ln.start)
            LineGeometry.charPositions(layout, ln, xs)
            checkLine(layout.content.text, layout.advances, xs, ln)
            lines++
        }
        assertTrue(lines > 20)
    }
}
