package com.ggumtak.readeraplus.engine

import com.ggumtak.readeraplus.reader.LayoutKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Guards [LayoutKeys.ALGO_VERSION] (A2): cached page counts survive app updates, so any change to what the typesetter
 * produces for the same input must bump the version. This lays out a fixed original corpus (Korean and Latin prose,
 * dialogue, headings, images, a scene break, insets, poetry, CHAR and WORD breaking, justified and ragged text) under
 * several configurations, hashes every page boundary and every [LineInfo] field, and compares the hash with
 * [LayoutKeys.GOLDEN_HASH].
 *
 * Everything the hash depends on is frozen in this file: the corpus, the content builder and the measurer (the fake
 * measurer's widths, copied here so an edit to the shared test fixtures can't move the hash). What it doesn't cover:
 * the Android measurer (fonts, the synthetic stroke) — see [LayoutKeys.ALGO_VERSION].
 */
class LayoutGoldenTest {

    @Test
    fun layoutOutputMatchesTheGoldenHash() {
        val hash = goldenHash()
        assertEquals(
            "layout output changed: bump ALGO_VERSION and update GOLDEN_HASH (LayoutKeys) to \"$hash\"",
            LayoutKeys.GOLDEN_HASH, hash,
        )
    }

    @Test
    fun theHashIsDeterministic() {
        assertEquals(goldenHash(), goldenHash())
    }

    @Test
    fun theHashSeesALineFieldChange() {
        // The hash must react to a change in a single line: the corpus above feeds every field, so shifting one line's
        // x by the smallest float step gives another digest.
        val (content, cfg) = corpus().first() to CONFIGS.first()
        val l = Typesetter(GoldenMeasurer(), cfg).layout(content)
        val base = digestOf(listOf(l))
        val ln = l.pages[0].lines[0]
        val moved = LineInfo(
            ln.start, ln.end, Math.nextUp(ln.x), ln.top, ln.baseline, ln.bottom, ln.justifyExtra, ln.expandMode,
            ln.imageBlock, ln.imageWidth, ln.imageHeight, ln.isRule,
        )
        val pages = l.pages.toMutableList()
        pages[0] = PageInfo(pages[0].start, pages[0].end, listOf(moved) + pages[0].lines.drop(1))
        val changed = SectionLayout(l.content, l.config, pages, l.advances)
        assertTrue(base != digestOf(listOf(changed)))
    }

    @Test
    fun theCorpusExercisesWhatItClaims() {
        val layouts = layoutAll()
        val lines = layouts.flatMap { l -> l.pages.flatMap { it.lines } }
        assertTrue("several pages", layouts.sumOf { it.pageCount } > 40)
        assertTrue("images", lines.any { it.imageBlock != null })
        assertTrue("rules", lines.any { it.isRule })
        assertTrue("justified by spaces", lines.any { it.expandMode == LineInfo.EXPAND_SPACES && it.justifyExtra > 0f })
        assertTrue("justified by chars", lines.any { it.expandMode == LineInfo.EXPAND_CHARS && it.justifyExtra > 0f })
        assertTrue("ragged lines", lines.any { it.expandMode == LineInfo.EXPAND_NONE && it.end > it.start })
        assertTrue("CHAR and WORD", CONFIGS.map { it.lineBreak }.toSet().size == 2)
    }

    // ------------------------------------------------------------------ hashing

    @Test
    fun paragraphModeMatchesItsGoldenHash() {
        val hash = digestOf(layoutAll(PARAGRAPH_CONFIGS))
        println("GOLDEN_HASH_PARAGRAPH = $hash")
        org.junit.Assume.assumeTrue(LayoutKeys.GOLDEN_HASH_PARAGRAPH != "TBD")
        assertEquals(
            "PARAGRAPH layout output changed: bump ALGO_VERSION and update GOLDEN_HASH_PARAGRAPH (LayoutKeys) to \"$hash\"",
            LayoutKeys.GOLDEN_HASH_PARAGRAPH, hash,
        )
    }

    private fun layoutAll(configs: List<LayoutConfig> = CONFIGS): List<SectionLayout> {
        val out = ArrayList<SectionLayout>()
        val sections = corpus()
        for (cfg in configs) {
            for (content in sections) {
                val ts = Typesetter(GoldenMeasurer(), cfg)
                val l = ts.layout(content)
                // Background counting uses countPages: it must agree with the layout it stands for.
                assertEquals("countPages", l.pageCount, Typesetter(GoldenMeasurer(), cfg).countPages(content))
                out.add(l)
            }
        }
        return out
    }

    private fun goldenHash(): String = digestOf(layoutAll())

    private fun digestOf(layouts: List<SectionLayout>): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(4)
        fun int(v: Int) {
            buf[0] = (v ushr 24).toByte()
            buf[1] = (v ushr 16).toByte()
            buf[2] = (v ushr 8).toByte()
            buf[3] = v.toByte()
            md.update(buf)
        }
        fun float(v: Float) = int(java.lang.Float.floatToIntBits(v))
        int(layouts.size)
        for (l in layouts) {
            int(l.pageCount)
            for (p in l.pages) {
                int(p.start)
                int(p.end)
                int(p.lines.size)
                for (ln in p.lines) {
                    int(ln.start)
                    int(ln.end)
                    float(ln.x)
                    float(ln.top)
                    float(ln.baseline)
                    float(ln.bottom)
                    float(ln.justifyExtra)
                    int(ln.expandMode)
                    int(ln.imageBlock?.start ?: -1)
                    float(ln.imageWidth)
                    float(ln.imageHeight)
                    int(if (ln.isRule) 1 else 0)
                }
            }
        }
        val d = md.digest()
        val hex = "0123456789abcdef"
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            val v = d[i].toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0xF])
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ frozen inputs

    /**
     * The fake measurer's rules (EngineFixtures), frozen: Hangul / CJK 1 em, Latin letters and digits 0.55 em,
     * spaces 0.3 em, other punctuation 0.35 em, the trailing half of a surrogate pair and combining marks 0; em = 20 px,
     * ascent 0.8 em, descent 0.2 em (scaled by RunStyle.sizeScale). Fixed image sizes.
     */
    private class GoldenMeasurer : TextMeasurer {
        override val emPx: Float = 20f

        override fun measure(text: String, start: Int, end: Int, style: RunStyle, out: FloatArray, outOffset: Int) {
            val k = emPx * style.sizeScale
            for (i in start until end) out[outOffset + i - start] = widthEm(text[i]) * k
        }

        override fun metrics(style: RunStyle): FontMetricsPx =
            FontMetricsPx(0.8f * emPx * style.sizeScale, 0.2f * emPx * style.sizeScale)

        override fun imageSize(src: String): IntSize? = when (src) {
            "wide" -> IntSize(600, 400)
            "small" -> IntSize(48, 48)
            "tall" -> IntSize(300, 900)
            else -> null
        }

        private fun widthEm(c: Char): Float {
            val code = c.code
            return when {
                c == '\n' || c == OBJECT_CHAR -> 0f
                code in 0xDC00..0xDFFF -> 0f // trailing surrogate
                code == 0x0301 -> 0f // combining acute
                code in 0xAC00..0xD7A3 || code in 0x3131..0x318E -> 1f
                code in 0x4E00..0x9FFF || code in 0x3040..0x30FF -> 1f
                code in 0xD800..0xDBFF -> 1f // leading surrogate carries the pair
                c == ' ' || c == ' ' -> 0.3f
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> 0.55f
                else -> 0.35f
            }
        }
    }

    /** Paragraphs separated by one '\n', images as one OBJECT_CHAR, rules empty (the Content.kt contract). */
    private class Builder {
        private val sb = StringBuilder()
        private val blocks = ArrayList<Block>()
        private val runs = ArrayList<StyleRun>()

        private fun sep() {
            if (blocks.isNotEmpty()) sb.append('\n')
        }

        fun para(text: String, style: BlockStyle = BlockStyle.BODY, run: RunStyle? = null, from: Int = 0, to: Int = -1) {
            sep()
            val s = sb.length
            sb.append(text)
            blocks.add(ParagraphBlock(s, sb.length, style))
            val e = if (to < 0) text.length else to
            if (run != null && e > from) runs.add(StyleRun(s + from, s + e, run))
        }

        fun heading(text: String, level: Int, pageBreak: Boolean = false) = para(
            text,
            BlockStyle(
                align = Align.CENTER, indent = false, headingLevel = level, keepWithNext = true,
                pageBreakBefore = pageBreak, marginTopEm = 1f, marginBottomEm = 0.5f,
            ),
            RunStyle(bold = true, sizeScale = if (level == 1) 1.4f else 1.2f),
        )

        fun image(src: String, w: Int = 0, h: Int = 0) {
            sep()
            val s = sb.length
            sb.append(OBJECT_CHAR)
            blocks.add(ImageBlock(s, src, w, h, style = BlockStyle(align = Align.CENTER, indent = false, marginTopEm = 0.5f)))
        }

        fun rule() {
            sep()
            blocks.add(RuleBlock(sb.length))
        }

        fun build(): SectionContent = SectionContent(sb.toString(), blocks.toList(), runs.toList())
    }

    /** Deterministic sentence picker (a fixed LCG: no dependency on a library RNG's algorithm). */
    private class Picker(private var state: Long) {
        fun next(n: Int): Int {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            return ((state ushr 33) % n).toInt()
        }
    }

    private fun prose(p: Picker, sentences: Int, pool: List<String>): String {
        val sb = StringBuilder()
        repeat(sentences) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(pool[p.next(pool.size)])
        }
        return sb.toString()
    }

    /** Three sections: a mixed "EPUB chapter", a dialogue-heavy web-novel episode, and an empty section. */
    private fun corpus(): List<SectionContent> {
        val p = Picker(20260930L)
        val a = Builder()
        a.heading("제1장 새벽의 빵집", 1)
        repeat(6) { a.para(prose(p, 5, KOREAN)) }
        a.para("그날 밤 그는 처음으로 두 번째 편지를 썼다. 쓰다 만 문장이 책상 위에 남았다.", run = RunStyle(bold = true), from = 3, to = 13)
        a.para("각주가 달린 문장이다.1 위첨자가 한 줄에 있다.", run = RunStyle(baselineShift = 1, sizeScale = 0.7f), from = 12, to = 13)
        a.image("wide")
        a.para(prose(p, 4, KOREAN))
        a.para(prose(p, 3, LATIN) + " " + LONG_WORD + " " + prose(p, 2, LATIN))
        a.para(MIXED.joinToString(" "))
        a.rule()
        a.para(prose(p, 4, KOREAN), BlockStyle(insetLeftEm = 2f, insetRightEm = 1f, indent = false))
        a.para("바람이 분다", BlockStyle(preformatted = true, indent = false))
        a.para("   살아 봐야겠다   ", BlockStyle(preformatted = true, indent = false))
        a.para("오늘도 걷는다", BlockStyle(softBreak = true))
        a.para("어제의 길을", BlockStyle(softBreak = true))
        a.para("")
        a.image("small")
        a.para(prose(p, 3, KOREAN), BlockStyle(align = Align.RIGHT))
        a.para(prose(p, 2, KOREAN), BlockStyle(align = Align.CENTER))
        a.heading("제2장 터널 너머", 2, pageBreak = true)
        repeat(8) { a.para(prose(p, 4, if (it % 3 == 2) LATIN else KOREAN)) }
        a.image("tall")
        a.image("wide", 200, 0)
        repeat(4) { a.para(prose(p, 6, KOREAN)) }

        val b = Builder()
        b.heading("137화", 2)
        repeat(90) {
            when (p.next(4)) {
                0 -> b.para(DIALOGUE[p.next(DIALOGUE.size)])
                1 -> b.para(prose(p, 1, KOREAN))
                else -> b.para(prose(p, 2 + p.next(4), KOREAN))
            }
        }
        b.para("* * *", BlockStyle(align = Align.CENTER, indent = false))
        repeat(30) { b.para(prose(p, 3, KOREAN)) }

        return listOf(a.build(), b.build(), SectionContent.EMPTY)
    }

    private companion object {
        val CONFIGS = listOf(
            config(340, 560, 1.7f, 0.5f, 1f, Align.JUSTIFY, LineBreakMode.CHAR, publisher = true, imageMax = 1f, widows = true),
            config(340, 560, 1.7f, 0.5f, 1f, Align.JUSTIFY, LineBreakMode.WORD, publisher = true, imageMax = 1f, widows = true),
            config(300, 480, 1.4f, 0f, 0f, Align.LEFT, LineBreakMode.CHAR, publisher = false, imageMax = 0.5f, widows = false),
            config(200, 300, 2.0f, 1f, 2f, Align.JUSTIFY, LineBreakMode.WORD, publisher = true, imageMax = 1f, widows = true),
        )

        /** U4: the same corpus paginated by paragraph (LINE output is guarded by [CONFIGS] above, unchanged). */
        val PARAGRAPH_CONFIGS = listOf(
            CONFIGS[0].copy(pageBreak = PageBreakMode.PARAGRAPH),
            CONFIGS[3].copy(pageBreak = PageBreakMode.PARAGRAPH),
        )

        fun config(
            w: Int, h: Int, lineHeight: Float, paragraphSpacing: Float, indent: Float, align: Align, lineBreak: LineBreakMode,
            publisher: Boolean, imageMax: Float, widows: Boolean,
        ) = LayoutConfig(
            width = w, height = h, lineHeightEm = lineHeight, paragraphSpacingEm = paragraphSpacing, indentEm = indent,
            align = align, lineBreak = lineBreak, publisherStyles = publisher, maxImageHeightFraction = imageMax,
            widowOrphanControl = widows,
        )

        val KOREAN = listOf(
            "새벽 네 시, 골목 끝의 빵집에서 첫 반죽이 부풀기 시작했다.",
            "그녀는 낡은 우산을 접으며 “오늘은 비가 그칠 거야”라고 중얼거렸다.",
            "창밖의 가로등이 하나씩 꺼지자 거리는 푸른빛으로 물들었다.",
            "도서관 삼층 구석 자리는 언제나 비어 있었고, 오래된 종이 냄새가 났다.",
            "기차가 터널을 빠져나오는 순간, 바다가 한꺼번에 쏟아져 들어왔다!",
            "……그래서 결국 아무 말도 하지 못했다.",
            "편지는 세 줄뿐이었다. 잘 지내, 미안해, 그리고 고마워.",
            "고양이는 담장 위를 천천히 걸어가다가 문득 멈춰 서서 하늘을 올려다보았다.",
            "밤새 내린 눈 위로 작은 발자국들이 길게 이어져 있었다.",
            "그는 시계를 두 번 확인하고서야 문을 나섰다—약속까지는 아직 이십 분이 남아 있었다.",
            "라디오에서는 오래된 노래가 흘러나왔고, 누군가 그 가락을 따라 휘파람을 불었다.",
            "빗방울이 유리창을 두드렸다. 톡, 톡, 톡.",
            "시장 골목의 국숫집 할머니는 오늘도 가·나·다 순서로 이름표를 붙였다.",
            "아무도없는운동장한가운데에오래된축구공하나가덩그러니놓여있었다.",
        )

        val DIALOGUE = listOf(
            "“정말? 그게 전부야?”",
            "“응.”",
            "“잠깐만, 거기 누구 있어요?!”",
            "‘설마 벌써 끝난 건 아니겠지.’",
            "“내일 다시 오자. 오늘은 여기까지~”",
            "“……알았어.”",
        )

        val LATIN = listOf(
            "The e-ink panel refreshed once, and the page settled into quiet black and white.",
            "Version 2.4.1 (build 1587) fixed the margin bug on 720x1440 screens.",
            "She read the last line twice, then closed the book without a word.",
            "\"Wait,\" he said. \"It's not over yet.\"",
            "A well-known author once wrote: reading is a conversation with the absent.",
        )

        const val LONG_WORD = "https://example.org/library/volume-03/chapter_12-supercalifragilistic.html"

        val MIXED = listOf(
            "3월 14일 오후 2시 30분, KTX 107호를 탔다.",
            "漢字와 한글이 섞인 문장도 있다: 春夏秋冬.",
            "좋아😊 정말로.",
            "Café에서 만나자.",
            "가격은 12,000원~15,000원(부가세 별도)이다.",
            "숫자 100과 단위 kg은 떨어지지 않는다.",
        )
    }
}
