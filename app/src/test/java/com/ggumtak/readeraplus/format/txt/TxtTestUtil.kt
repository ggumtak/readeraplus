package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.engine.ImageBlock
import com.ggumtak.readeraplus.engine.ParagraphBlock
import com.ggumtak.readeraplus.engine.RuleBlock
import com.ggumtak.readeraplus.engine.SectionContent
import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.format.ParseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.random.Random

/** Shared helpers for the TXT tests. All sample text is generated here (original, no real novels). */
internal object TxtTestUtil {
    val CP949: Charset = Charset.forName("x-windows-949")

    private val subjects = listOf(
        "나는", "그는", "그녀는", "우리는", "소년은", "기사는", "마법사는", "상인은", "노인은", "아이들은",
        "바람이", "하늘이", "강물이", "도시가", "마을 사람들은", "검은 고양이가", "작은 새가", "선생님은",
    )
    private val objects = listOf(
        "오래된 지도를", "푸른 등불을", "낡은 책을", "따뜻한 빵을", "은빛 열쇠를", "편지 한 장을",
        "창밖의 풍경을", "먼 산을", "작은 상자를", "부서진 시계를", "새로운 길을", "하얀 꽃을",
    )
    private val verbs = listOf(
        "바라보았다", "천천히 펼쳤다", "조용히 들었다", "손에 쥐었다", "한참 동안 살폈다", "내려놓았다",
        "기억해 냈다", "찾아 헤맸다", "건네주었다", "끝내 포기하지 않았다", "다시 한번 확인했다",
    )
    private val adverbs = listOf(
        "그날 밤", "아침이 되자", "잠시 후", "비가 그치고", "해가 질 무렵", "모두가 잠든 사이",
        "갑자기", "마침내", "어쩐지", "문득", "그러나", "한편",
    )
    private val lines = listOf(
        "“정말 그렇게 생각해?”", "“괜찮아, 걱정하지 마.”", "“어디로 가는 거야?”", "“잠깐만 기다려.”",
        "“그건 내 잘못이 아니야.”", "“이제 시작이야.”",
    )

    /** A random original Korean sentence. */
    fun sentence(r: Random): String {
        if (r.nextInt(6) == 0) return lines[r.nextInt(lines.size)]
        val sb = StringBuilder()
        if (r.nextBoolean()) sb.append(adverbs[r.nextInt(adverbs.size)]).append(' ')
        sb.append(subjects[r.nextInt(subjects.size)]).append(' ')
        sb.append(objects[r.nextInt(objects.size)]).append(' ')
        sb.append(verbs[r.nextInt(verbs.size)]).append('.')
        return sb.toString()
    }

    /** A paragraph of [minChars]+ chars. */
    fun paragraph(r: Random, minChars: Int = 60): String {
        val sb = StringBuilder()
        while (sb.length < minChars) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(sentence(r))
        }
        return sb.toString()
    }

    /** Body text of about [chars] chars: paragraphs separated by [sep]. */
    fun body(r: Random, chars: Int, sep: String = "\n"): String {
        val sb = StringBuilder()
        while (sb.length < chars) {
            if (sb.isNotEmpty()) sb.append(sep)
            sb.append(paragraph(r, 40 + r.nextInt(160)))
        }
        return sb.toString()
    }

    fun tempDir(): File = Files.createTempDirectory("txttest").toFile().also { it.deleteOnExit() }

    fun writeTemp(dir: File, name: String, bytes: ByteArray): File {
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f
    }

    /** Whole-file parse of [text] encoded with [cs] (or raw [bytes]). */
    fun parse(text: String, o: ParseOptions = ParseOptions(), cs: Charset = Charsets.UTF_8): TxtParser.Parsed {
        val b = text.toByteArray(cs)
        return TxtParser.parse(b, b.size, o)
    }

    fun parseBytes(b: ByteArray, o: ParseOptions = ParseOptions()): TxtParser.Parsed = TxtParser.parse(b, b.size, o)

    fun sections(p: TxtParser.Parsed, o: ParseOptions = ParseOptions()): List<SectionContent> =
        List(p.sectionCount) { p.buildSection(it, o.txtEmphasizeHeadings) }

    /** All paragraphs of all sections, in order. */
    fun paragraphs(text: String, o: ParseOptions = ParseOptions()): List<String> {
        val p = parse(text, o)
        return sections(p, o).flatMap { s -> s.blocks.map { s.text.substring(it.start, it.end) } }
    }

    /**
     * Writes [bytes] to [dir]/[name], opens it through TxtDocuments twice (full parse + saved index, then the
     * index only) and checks every section against the whole-file parse.
     */
    fun assertIndexEquivalent(dir: File, cacheDir: File, name: String, bytes: ByteArray, o: ParseOptions, where: String) {
        val f = writeTemp(dir, name, bytes)
        val expected = TxtParser.parse(bytes, bytes.size, o)
        withCacheDir(cacheDir) {
            TxtIndexStore.fileFor(TxtIndexStore.key(f, o))!!.delete()
            for (pass in 0..1) {
                if (pass == 1) org.junit.Assert.assertNotNull("$where index saved", TxtIndexStore.load(TxtIndexStore.key(f, o), f.length()))
                TxtDocuments.open(f, o).use { book ->
                    assertEquals("$where section count", expected.sectionCount, book.sections.size)
                    for (i in 0 until expected.sectionCount) {
                        val got = book.loadSection(i)
                        assertSameContent(expected.buildSection(i, o.txtEmphasizeHeadings), got, "$where pass $pass section $i")
                        assertInvariants(got, "$where pass $pass section $i")
                        assertEquals("$where approxChars $i", got.length, book.sections[i].approxChars)
                    }
                }
            }
        }
    }

    fun <T> withCacheDir(dir: File, body: () -> T): T {
        val old = Documents.cacheDir
        Documents.cacheDir = dir
        try {
            return body()
        } finally {
            Documents.cacheDir = old
        }
    }

    /** Structural invariants every SectionContent must satisfy. */
    fun assertInvariants(s: SectionContent, where: String = "") {
        val t = s.text
        if (t.isEmpty()) {
            assertTrue("$where empty text has at most one empty block", s.blocks.size <= 1)
            return
        }
        assertTrue("$where has blocks", s.blocks.isNotEmpty())
        assertEquals("$where first block at 0", 0, s.blocks[0].start)
        assertEquals("$where last block ends at length", t.length, s.blocks.last().end)
        for (k in s.blocks.indices) {
            val b = s.blocks[k]
            assertTrue("$where block $k is a paragraph", b is ParagraphBlock)
            assertTrue("$where block $k range", b.start <= b.end)
            for (c in b.start until b.end) {
                assertTrue("$where no newline inside block $k", t[c] != '\n')
            }
            if (k > 0) {
                val prev = s.blocks[k - 1]
                assertEquals("$where separator after block ${k - 1}", prev.end + 1, b.start)
                assertEquals("$where separator char", '\n', t[prev.end])
            }
        }
        var last = 0
        for (r in s.styleRuns) {
            assertTrue("$where run order", r.start >= last && r.end > r.start && r.end <= t.length)
            last = r.end
        }
        assertTrue("$where no object char", t.indexOf('￼') < 0)
    }

    fun assertSameContent(expected: SectionContent, actual: SectionContent, where: String) {
        assertEquals("$where text", expected.text, actual.text)
        assertEquals("$where block count", expected.blocks.size, actual.blocks.size)
        for (k in expected.blocks.indices) {
            val a = expected.blocks[k]
            val b = actual.blocks[k]
            assertEquals("$where block $k class", a.javaClass, b.javaClass)
            assertEquals("$where block $k start", a.start, b.start)
            assertEquals("$where block $k end", a.end, b.end)
            when (a) {
                is ParagraphBlock -> assertEquals("$where block $k style", a.style, (b as ParagraphBlock).style)
                is ImageBlock -> assertEquals("$where block $k src", a.src, (b as ImageBlock).src)
                is RuleBlock -> Unit
            }
        }
        assertEquals("$where run count", expected.styleRuns.size, actual.styleRuns.size)
        for (k in expected.styleRuns.indices) {
            val a = expected.styleRuns[k]
            val b = actual.styleRuns[k]
            assertEquals("$where run $k start", a.start, b.start)
            assertEquals("$where run $k end", a.end, b.end)
            assertEquals("$where run $k style", a.style, b.style)
        }
    }
}
