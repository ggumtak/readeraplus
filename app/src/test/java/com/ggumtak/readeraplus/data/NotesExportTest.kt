package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.reader.extras.TextActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.util.TimeZone

/** The notes export (N §5.7, hub.md §10): golden Markdown and TXT, escaping, colour tags and the share cap. */
class NotesExportTest {

    private val utc = TimeZone.getTimeZone("UTC")
    /** 2026-09-30 15:42 UTC. */
    private val now = 1790782920000L
    /** 2026-09-12 21:04 UTC. */
    private val t0 = 1789247040000L

    private fun quote(id: Long, book: Long, sec: Int, start: Int, body: String, note: String = "", style: Int = 0,
                      chapter: String = "12화 과거로", frac: Float = 0.37f, time: Long = t0) =
        NoteRow(NoteRef(NoteKind.QUOTE, id), book, sec, start, start + 5, body, false, note, false, "", 0, style, 0, "",
            chapter, frac, "", time)

    private fun mark(id: Long, book: Long, sec: Int, off: Int, snippet: String, note: String = "", chapter: String = "12화 과거로", frac: Float = 0.37f) =
        NoteRow(NoteRef(NoteKind.BOOKMARK, id), book, sec, off, off, snippet, false, note, false, "", 0, 0, 0, "", chapter, frac, "", t0)

    private fun review(book: Long, text: String) =
        NoteRow(NoteRef(NoteKind.REVIEW, book), book, -1, -1, -1, text, false, "", false, "", 0, 0, 0, "", "", 0.5f, "", t0)

    private fun word(id: Long, book: Long, sec: Int, start: Int, w: String, context: String, note: String = "") =
        NoteRow(NoteRef(NoteKind.LOOKUP, id), book, sec, start, start + w.length, context, false, note, false, w, 1, 0,
            Lookups.VIA_APP, "파파고", "12화", 0.37f, "", t0)

    private val book1 = NoteBook(1, "절대회귀 1-896 (완)", "", "/storage/emulated/0/Books/절대회귀 1-896 (완).txt", false, false, 5, 0)
    private val book2 = NoteBook(2, "여름의 끝", "김작가", "/x/여름의 끝.epub", true, false, 3, 0)
    private val book3 = NoteBook(3, "사라진 책", "박작가", "/x/사라진 책.txt", true, true, 1, 0)

    private val rows = listOf(
        quote(11, 1, 13, 0, "두 번째 인용문", chapter = "13화", frac = 0.38f, time = t0 + 26 * 60_000L),
        word(41, 1, 12, 40, "비명", "…타인의 비명이 퍼졌다…", "외마디 소리"),
        mark(21, 1, 12, 10, "새벽 공기는 생각보다 차가웠고…", "여기서부터 다시"),
        quote(10, 1, 12, 5, "인용문 본문 첫 줄\n둘째 줄", "이 장면 다시 읽기"),
        review(1, "끝까지 읽었다. 중반부가 가장 좋았다.\n둘째 줄"),
        mark(22, 2, 0, 0, "", chapter = "", frac = -1f),
        quote(12, 3, 0, 0, "사라진 책의 인용"),
    )

    private fun render(rows: List<NoteRow>, books: List<NoteBook>, format: NotesExport.Format, q: NotesQuery = NotesQuery(),
                       selected: Int? = null): Pair<String, Int> {
        val chunks = NotesExport.chunks(rows, books)
        val out = StringWriter()
        val n = NotesExport.render(chunks.asSequence(), NotesExport.Summary.of(chunks), NotesExport.scope(q, books, selected, format),
            format, NotesExport.WriterSink(out), now, utc)
        return out.toString() to n
    }

    @Test
    fun goldenMarkdown() {
        val (md, n) = render(rows, listOf(book1, book2, book3), NotesExport.Format.MARKDOWN)
        assertEquals(7, n)
        val expect = """
            |# 독서 노트
            |
            |- 내보낸 날짜: 2026년 9월 30일 15:42
            |- 범위: 모든 책 · 모든 노트
            |- 책 3권 · 인용문 3개 · 메모 2개 · 북마크 2개 · 리뷰 1개 · 단어 1개
            |
            |## 절대회귀 1-896 (완)
            |
            |작가 미상 · `절대회귀 1-896 (완).txt`
            |
            |### 리뷰
            |
            |> 끝까지 읽었다. 중반부가 가장 좋았다.
            |> 둘째 줄
            |
            |*2026년 9월 12일 21:04*
            |
            |### 인용문 (2)
            |
            |> 인용문 본문 첫 줄
            |> 둘째 줄
            |
            |— 12화 과거로 · 37% · 2026년 9월 12일 21:04  
            |**메모:** 이 장면 다시 읽기
            |
            |> 두 번째 인용문
            |
            |— 13화 · 38% · 2026년 9월 12일 21:30
            |
            |### 북마크 (1)
            |
            |- 12화 과거로 · 37% · 2026년 9월 12일 21:04 — “새벽 공기는 생각보다 차가웠고…”  
            |  **메모:** 여기서부터 다시
            |
            |### 단어 (1)
            |
            |- **비명** — “…타인의 **비명**이 퍼졌다…” — 12화 · 37% · 파파고 · 2026년 9월 12일 21:04  
            |  **뜻:** 외마디 소리
            |
            |## 여름의 끝 (휴지통)
            |
            |김작가 · `여름의 끝.epub`
            |
            |### 북마크 (1)
            |
            |- 2026년 9월 12일 21:04
            |
            |## 사라진 책 (파일 없음)
            |
            |박작가 · `사라진 책.txt`
            |
            |### 인용문 (1)
            |
            |> 사라진 책의 인용
            |
            |— 12화 과거로 · 37% · 2026년 9월 12일 21:04
            |""".trimMargin()
        assertEquals(expect, md)
    }

    @Test
    fun goldenText() {
        val (txt, n) = render(rows.filter { it.bookId != 3L }, listOf(book1, book2), NotesExport.Format.TXT)
        assertEquals(6, n)
        val expect = """
            |독서 노트
            |내보낸 날짜: 2026년 9월 30일 15:42
            |범위: 모든 책 · 모든 노트
            |책 2권 · 인용문 2개 · 메모 2개 · 북마크 2개 · 리뷰 1개 · 단어 1개
            |
            |========================================
            |《절대회귀 1-896 (완)》
            |작가 미상 · 절대회귀 1-896 (완).txt
            |========================================
            |
            |[리뷰]
            |끝까지 읽었다. 중반부가 가장 좋았다.
            |둘째 줄
            |(2026년 9월 12일 21:04)
            |
            |[인용문 2]
            |“인용문 본문 첫 줄
            |둘째 줄”
            |  — 12화 과거로 · 37% · 2026년 9월 12일 21:04
            |  메모: 이 장면 다시 읽기
            |
            |“두 번째 인용문”
            |  — 13화 · 38% · 2026년 9월 12일 21:30
            |
            |[북마크 1]
            |• 12화 과거로 · 37% · 2026년 9월 12일 21:04
            |  “새벽 공기는 생각보다 차가웠고…”
            |  메모: 여기서부터 다시
            |
            |[단어 1]
            |• 비명 — “…타인의 비명이 퍼졌다…”
            |  12화 · 37% · 파파고 · 2026년 9월 12일 21:04
            |  뜻: 외마디 소리
            |
            |========================================
            |《여름의 끝》 (휴지통)
            |김작가 · 여름의 끝.epub
            |========================================
            |
            |[북마크 1]
            |• 2026년 9월 12일 21:04
            |""".trimMargin()
        assertEquals(expect, txt)
    }

    @Test
    fun scopes() {
        val books = listOf(book1)
        assertEquals("모든 책 · 모든 노트", NotesExport.scope(NotesQuery(), books, null, NotesExport.Format.TXT))
        assertEquals("《절대회귀 1-896 (완)》 · 인용문", NotesExport.scope(NotesQuery(NotesTab.QUOTES, bookId = 1), books, null, NotesExport.Format.TXT))
        assertEquals("선택한 노트 12개", NotesExport.scope(NotesQuery(), books, 12, NotesExport.Format.TXT))
        assertEquals("모든 책 · 단어장 · 검색: 비명", NotesExport.scope(NotesQuery(NotesTab.WORDS, text = " 비명 "), books, null, NotesExport.Format.TXT))
        assertEquals("선택한 노트 2개 · 검색: a\\_b", NotesExport.scope(NotesQuery(text = "a_b"), books, 2, NotesExport.Format.MARKDOWN))
        // One-book and selection scopes in the header.
        val (one, _) = render(rows.filter { it.bookId == 1L }, books, NotesExport.Format.TXT, NotesQuery(NotesTab.ALL, bookId = 1))
        assertTrue(one.contains("범위: 《절대회귀 1-896 (완)》 · 모든 노트\n책 1권 · 인용문 2개"))
        val (sel, n) = render(rows.take(2), listOf(book1), NotesExport.Format.MARKDOWN, selected = 2)
        assertEquals(2, n)
        // Kinds with none are left out of the counts.
        assertTrue(sel.contains("- 범위: 선택한 노트 2개\n- 책 1권 · 인용문 1개 · 단어 1개\n"))
    }

    @Test
    fun emptySectionsAreOmittedAndCountsMatchRowsWritten() {
        val (md, n) = render(listOf(quote(1, 1, 0, 0, "하나")), listOf(book1, book2), NotesExport.Format.MARKDOWN)
        assertEquals(1, n)
        assertFalse(md.contains("### 리뷰") || md.contains("### 북마크") || md.contains("### 단어") || md.contains("여름의 끝"))
        assertTrue(md.contains("- 책 1권 · 인용문 1개\n"))
    }

    @Test
    fun colourTagOnlyWithTwoOrMoreStyles() {
        val one = listOf(quote(1, 1, 0, 0, "가", style = 1), quote(2, 1, 0, 9, "나", style = 1))
        val (plain, _) = render(one, listOf(book1), NotesExport.Format.MARKDOWN)
        assertFalse(plain.contains("[초록]"))
        val (same, _) = render(one.map { it.copy(style = 0) }, listOf(book1), NotesExport.Format.MARKDOWN)
        assertEquals(same, plain) // single-colour exports are byte-identical
        val two = listOf(quote(1, 1, 0, 0, "가", style = 1), quote(2, 1, 0, 9, "나", style = 2))
        val (md, _) = render(two, listOf(book1), NotesExport.Format.MARKDOWN)
        assertTrue(md.contains("\n— [초록] 12화 과거로 · 37% · 2026년 9월 12일 21:04\n"))
        assertTrue(md.contains("\n— [파랑] 12화 과거로"))
        val (txt, _) = render(two, listOf(book1), NotesExport.Format.TXT)
        assertTrue(txt.contains("\n  — [초록] 12화 과거로 · 37% · 2026년 9월 12일 21:04\n"))
    }

    @Test
    fun markdownEscaping() {
        val cases = listOf(
            "a\\b" to "a\\\\b", "`code`" to "\\`code\\`", "*별*" to "\\*별\\*", "_x_" to "\\_x\\_",
            "[링크](u)" to "\\[링크\\](u)", "<b>" to "\\<b\\>", "# 제목" to "\\# 제목", "a|b" to "a\\|b",
            "~~취소~~" to "\\~\\~취소\\~\\~", "==강조==" to "\\=\\=강조\\=\\=", "${'$'}x${'$'}" to "\\${'$'}x\\${'$'}",
            "%%숨김%%" to "\\%\\%숨김\\%\\%", "&nbsp;" to "\\&nbsp;",
            "- 목록" to "\\- 목록", "+ 목록" to "\\+ 목록", "1. 하나" to "1\\. 하나", "12) 둘" to "12\\) 둘",
            "중간 - 1. 그대로" to "중간 - 1. 그대로", "=====" to "\\=\\=\\=\\=\\=",
            "    들여쓴 문단" to "들여쓴 문단", "\t\t탭" to "탭", "  - 들여쓴 목록" to "\\- 들여쓴 목록",
            "a\uFFFCb" to "ab", "a\u0001b\u007Fc" to "abc", "a\tb" to "a b",
            "a\r\nb\rc\u2028d\u2029e" to "a\nb\nc\nd\ne", "1.5배" to "1.5배",
        )
        for ((input, expect) in cases) assertEquals(input, expect, NotesExport.md(input))
    }

    @Test
    fun everyDerivedFieldIsEscaped() {
        val b = book1.copy(title = "*별* #1", author = "a_b", path = "/x/a`b`.txt")
        val r = listOf(
            quote(1, 1, 0, 0, "본문", note = "메모 *강조*\n2) 둘째", chapter = "_1장_ #"),
            word(2, 1, 0, 9, "a*b", "문장 a*b 끝", "뜻 ~x~"),
        )
        val (md, _) = render(r, listOf(b), NotesExport.Format.MARKDOWN)
        assertTrue(md.contains("\n## \\*별\\* \\#1\n"))
        assertTrue(md.contains("\na\\_b · `ab.txt`\n"))
        assertTrue(md.contains("\n— \\_1장\\_ \\# · 37% · 2026년 9월 12일 21:04  \n**메모:** 메모 \\*강조\\*  \n2\\) 둘째\n"))
        assertTrue(md.contains("- **a\\*b** — “문장 **a\\*b** 끝” — 12화 · 37% · 파파고 · 2026년 9월 12일 21:04  \n  **뜻:** 뜻 \\~x\\~\n"))
    }

    @Test
    fun indentedBodyNeverBecomesACodeBlock() {
        val (md, _) = render(listOf(quote(1, 1, 0, 0, "    첫 문단\n\n    둘째 문단")), listOf(book1), NotesExport.Format.MARKDOWN)
        assertTrue(md.contains("\n> 첫 문단\n>\n> 둘째 문단\n"))
    }

    @Test
    fun frozenWriteGroupsAndSortsLikeRender() {
        val prev = TimeZone.getDefault()
        TimeZone.setDefault(utc)
        try {
            val out = StringWriter()
            val n = NotesExport.write(rows.asSequence(), listOf(book1, book2, book3), NotesQuery(), NotesExport.Format.MARKDOWN, out, now)
            assertEquals(7, n)
            assertEquals(render(rows, listOf(book1, book2, book3), NotesExport.Format.MARKDOWN).first, out.toString())
            // A row whose book is unknown is still written, under "(삭제된 책)".
            val o2 = StringWriter()
            NotesExport.write(sequenceOf(quote(1, 99, 0, 0, "고아")), emptyList(), NotesQuery(), NotesExport.Format.TXT, o2, now)
            assertTrue(o2.toString().contains("《(삭제된 책)》 (파일 없음)\n작가 미상\n"))
        } finally {
            TimeZone.setDefault(prev)
        }
    }

    @Test
    fun shareCapCutsAtAnItemBoundary() {
        assertEquals(50_000, TextActions.SHARE_MAX_CHARS)
        val many = (1..2000).map { quote(it.toLong(), 1, 0, it, "인용문 $it " + "가".repeat(40)) }
        val chunks = NotesExport.chunks(many, listOf(book1))
        val summary = NotesExport.Summary.of(chunks)
        val s = NotesExport.share(chunks.asSequence(), summary, "모든 책 · 모든 노트", now, utc)
        assertTrue(s.cut)
        assertTrue(s.text.length <= TextActions.SHARE_MAX_CHARS)
        assertTrue(s.text.endsWith("”\n  — 12화 과거로 · 37% · 2026년 9월 12일 21:04\n" + NotesExport.shareSuffix(2000 - s.written)))
        assertTrue(s.text.endsWith("\n…(나머지 ${2000 - s.written}개는 ‘내보내기’로 저장하세요)"))
        assertTrue(s.written in 100 until 2000)
        // Small lists are shared whole.
        val few = NotesExport.chunks(many.take(3), listOf(book1))
        val all = NotesExport.share(few.asSequence(), NotesExport.Summary.of(few), "모든 책 · 모든 노트", now, utc)
        assertFalse(all.cut)
        assertEquals(3, all.written)
        assertEquals(render(many.take(3), listOf(book1), NotesExport.Format.TXT).first, all.text)
        // A tiny cap keeps the header only, with every note counted as left out.
        val tiny = NotesExport.share(few.asSequence(), NotesExport.Summary.of(few), "모든 책 · 모든 노트", now, utc, max = 200)
        assertTrue(tiny.cut)
        assertEquals(0, tiny.written)
        assertTrue(tiny.text.endsWith("책 1권 · 인용문 3개\n\n…(나머지 3개는 ‘내보내기’로 저장하세요)"))
    }

    @Test
    fun textNormalisationAndPercent() {
        assertEquals("a\nb", NotesExport.norm("a\r\nb"))
        assertEquals("ab", NotesExport.norm("a\u0000b"))
        assertEquals(37, NotesExport.percent(0.37f))
        assertEquals(0, NotesExport.percent(0f))
        assertEquals(100, NotesExport.percent(1.2f))
        assertEquals(99, NotesExport.percent(0.999f))
    }
}
