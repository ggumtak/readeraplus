package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.reader.extras.TextActions
import com.ggumtak.readeraplus.render.QuoteStyles
import java.util.Calendar
import java.util.TimeZone

/**
 * The notes export (N §5.7, hub.md §10): Markdown (메모 앱·옵시디언) or plain text, streamed to a [java.io.Writer]
 * one book at a time. Pure (no Android, no database): [Notes.export] feeds it rows book by book.
 *
 * Order: books in the hub's book order; inside a book review, quotes, bookmarks, words, each in reading order. Empty
 * sections are left out. The header counts are the rows written. When the exported quotes use two or more colours,
 * every quote's meta line starts with its colour tag ("[초록]"); a single-colour export is byte-identical to the
 * untagged format.
 */
object NotesExport {
    enum class Format(val ext: String,val mime: String) { MARKDOWN("md","text/markdown"), TXT("txt","text/plain") }

    /** Writes [rows] (any order; grouped and sorted here) as [format]. Returns the number of notes written. */
    fun write(rows: Sequence<NoteRow>, books: List<NoteBook>, q: NotesQuery, format: Format, out: java.io.Writer, now: Long): Int {
        val list = rows.toList()
        val chunks = chunks(list, books)
        val summary = Summary.of(chunks)
        return render(chunks.asSequence(), summary, scope(q, books, null, format), format, WriterSink(out), now, TimeZone.getDefault())
    }

    // ---- internal API (Notes.export / Notes.share and the tests) ----

    /** What the header says and whether quotes carry colour tags; known before the first row is written. */
    internal class Summary(val books: Int, val counts: NotesCounts, val tagged: Boolean) {
        /** Every note written (quotes, bookmarks, reviews, words; memos are quotes or bookmarks). */
        val items: Int get() = counts.quotes + counts.bookmarks + counts.reviews + counts.words

        companion object {
            fun of(chunks: List<Pair<NoteBook, List<NoteRow>>>): Summary {
                var quotes = 0; var memos = 0; var marks = 0; var reviews = 0; var words = 0
                var styles = 0
                for ((_, rows) in chunks) for (r in rows) when (r.ref.kind) {
                    NoteKind.QUOTE -> { quotes++; if (r.note.isNotBlank()) memos++; styles = styles or (1 shl QuoteStyles.of(r.style)) }
                    NoteKind.BOOKMARK -> { marks++; if (r.note.isNotBlank()) memos++ }
                    NoteKind.REVIEW -> reviews++
                    NoteKind.LOOKUP -> words++
                }
                return Summary(chunks.size, NotesCounts(quotes, memos, marks, reviews, words), Integer.bitCount(styles) >= 2)
            }
        }
    }

    /** Receives the text. [head] is committed together with the next [item] (a cap never leaves a bare heading). */
    internal abstract class Sink {
        /** The file header: always kept. */
        open fun lead(text: CharSequence) = head(text)
        abstract fun head(text: CharSequence)
        /** Returns false when the sink is full: nothing more is written. */
        abstract fun item(text: CharSequence): Boolean
        open fun end() {}
    }

    internal class WriterSink(private val out: java.io.Writer) : Sink() {
        override fun head(text: CharSequence) { out.append(text) }
        override fun item(text: CharSequence): Boolean { out.append(text); return true }
        override fun end() { out.flush() }
    }

    /** Collects up to [max] chars, cutting at an item boundary ([Notes.share]). */
    internal class CapSink(private val max: Int, private val reserve: Int) : Sink() {
        val text = StringBuilder()
        private val pending = StringBuilder()
        var full = false; private set

        override fun lead(text: CharSequence) { this.text.append(text) }
        override fun head(text: CharSequence) { if (!full) pending.append(text) }
        override fun item(text: CharSequence): Boolean {
            if (full) return false
            if (this.text.length + pending.length + text.length > max - reserve) {
                full = true
                return false
            }
            this.text.append(pending).append(text)
            pending.setLength(0)
            return true
        }
        override fun end() { if (!full) { text.append(pending); pending.setLength(0) } }
    }

    /** Result of [share]: the TXT text, the notes in it and whether the rest was cut. */
    internal class Shared(val text: String, val written: Int, val cut: Boolean)

    /** "\n…(나머지 N개는 ‘내보내기’로 저장하세요)": appended when the share text was cut. */
    internal fun shareSuffix(rest: Int): String = "\n…(나머지 ${rest}개는 ‘내보내기’로 저장하세요)"

    /**
     * The 공유 text: TXT, at most [max] chars ([TextActions.SHARE_MAX_CHARS]: the binder buffer, not 200k), cut at an
     * item boundary with [shareSuffix].
     */
    internal fun share(
        chunks: Sequence<Pair<NoteBook, List<NoteRow>>>, summary: Summary, scope: String, now: Long,
        zone: TimeZone = TimeZone.getDefault(), max: Int = TextActions.SHARE_MAX_CHARS,
    ): Shared {
        val suffixMax = shareSuffix(summary.items).length
        val sink = CapSink(max, suffixMax)
        val written = render(chunks, summary, scope, Format.TXT, sink, now, zone)
        if (!sink.full) return Shared(sink.text.toString(), written, false)
        sink.text.append(shareSuffix(summary.items - written))
        return Shared(sink.text.toString(), written, true)
    }

    /** Groups [rows] by book: [books] order first, then unknown books (as "(삭제된 책)") in first-seen order. */
    internal fun chunks(rows: List<NoteRow>, books: List<NoteBook>): List<Pair<NoteBook, List<NoteRow>>> {
        val byBook = LinkedHashMap<Long, MutableList<NoteRow>>()
        for (r in rows) byBook.getOrPut(r.bookId) { ArrayList() } += r
        val out = ArrayList<Pair<NoteBook, List<NoteRow>>>(byBook.size)
        for (bk in books) byBook.remove(bk.id)?.let { out += bk to readingOrder(it) }
        for ((id, list) in byBook) out += deletedBook(id, list.size) to readingOrder(list)
        return out
    }

    internal fun deletedBook(id: Long, count: Int): NoteBook = NoteBook(id, DELETED_TITLE, "", "", false, true, 0L, count)

    /** One book's rows: review, quotes, bookmarks, words; each by section, offset, id. */
    internal fun readingOrder(rows: List<NoteRow>): List<NoteRow> = rows.sortedWith(ROW_ORDER)

    private val ROW_ORDER = Comparator<NoteRow> { a, b ->
        var c = kindRank(a.ref.kind).compareTo(kindRank(b.ref.kind))
        if (c == 0) c = a.section.compareTo(b.section)
        if (c == 0) c = a.start.compareTo(b.start)
        if (c == 0) c = a.ref.id.compareTo(b.ref.id)
        c
    }

    internal fun kindRank(k: NoteKind): Int = when (k) {
        NoteKind.REVIEW -> 0; NoteKind.QUOTE -> 1; NoteKind.BOOKMARK -> 2; NoteKind.LOOKUP -> 3
    }

    /**
     * "범위": "모든 책 · 모든 노트", "《제목》 · 인용문", "선택한 노트 12개", plus " · 검색: 단어". [selected] = the
     * selection size, null for the query's list.
     */
    internal fun scope(q: NotesQuery, books: List<NoteBook>, selected: Int?, format: Format): String {
        val sb = StringBuilder(48)
        if (selected != null) {
            sb.append("선택한 노트 ").append(selected).append('개')
        } else {
            val id = q.bookId
            if (id == null) sb.append("모든 책") else {
                val title = books.firstOrNull { it.id == id }?.title ?: DELETED_TITLE
                sb.append('《').append(inline(title, format)).append('》')
            }
            sb.append(" · ").append(if (q.tab == NotesTab.ALL) "모든 노트" else q.tab.label)
        }
        val text = q.text.trim()
        if (text.isNotEmpty()) sb.append(" · 검색: ").append(inline(text, format))
        return sb.toString()
    }

    /** Renders [chunks] into [sink]; returns the number of notes written (fewer than the summary when the sink fills). */
    internal fun render(
        chunks: Sequence<Pair<NoteBook, List<NoteRow>>>, summary: Summary, scope: String, format: Format,
        sink: Sink, now: Long, zone: TimeZone,
    ): Int {
        val md = format == Format.MARKDOWN
        val cal = Calendar.getInstance(zone)
        val counts = counts(summary)
        if (md) {
            sink.lead("# 독서 노트\n\n- 내보낸 날짜: ${time(now, cal)}\n- 범위: $scope\n- $counts\n")
        } else {
            sink.lead("독서 노트\n내보낸 날짜: ${time(now, cal)}\n범위: $scope\n$counts\n")
        }
        var written = 0
        val sb = StringBuilder(1024)
        outer@ for ((book, rows) in chunks) {
            if (rows.isEmpty()) continue
            sink.head(bookHeading(book, md))
            var i = 0
            while (i < rows.size) {
                val kind = rows[i].ref.kind
                var j = i
                while (j < rows.size && rows[j].ref.kind == kind) j++
                sink.head(sectionHeading(kind, j - i, md))
                for (x in i until j) {
                    sb.setLength(0)
                    item(rows[x], x == i, summary.tagged, md, cal, sb)
                    if (!sink.item(sb)) break@outer
                    written++
                }
                i = j
            }
        }
        sink.end()
        return written
    }

    /** "책 3권 · 인용문 3개 · 메모 2개 · …": the kinds with none are left out ("책 1권 · 인용문 1개"). */
    internal fun counts(summary: Summary): String {
        val c = summary.counts
        val sb = StringBuilder(64).append("책 ").append(summary.books).append('권')
        for ((label, n) in arrayOf("인용문" to c.quotes, "메모" to c.memos, "북마크" to c.bookmarks, "리뷰" to c.reviews, "단어" to c.words)) {
            if (n > 0) sb.append(" · ").append(label).append(' ').append(n).append('개')
        }
        return sb.toString()
    }

    private fun bookHeading(book: NoteBook, md: Boolean): String {
        val mark = when {
            book.missing -> " (파일 없음)"
            book.trashed -> " (휴지통)"
            else -> ""
        }
        // The file name shows the format already ("… · `절대회귀 1-896 (완).txt`").
        val name = book.path.substringAfterLast('/')
        val info = StringBuilder(64)
        info.append(if (book.author.isBlank()) "작가 미상" else inline(book.author, md))
        if (name.isNotEmpty()) {
            info.append(" · ")
            val n = oneLine(norm(name))
            if (md) info.append('`').append(n.replace("`", "")).append('`') else info.append(n)
        }
        return if (md) {
            "\n## ${inline(book.title, md)}$mark\n\n$info\n"
        } else {
            "\n$RULE\n《${inline(book.title, md)}》$mark\n$info\n$RULE\n"
        }
    }

    private fun sectionHeading(kind: NoteKind, n: Int, md: Boolean): String {
        val list = kind == NoteKind.BOOKMARK || kind == NoteKind.LOOKUP
        return if (md) {
            val h = if (kind == NoteKind.REVIEW) "\n### 리뷰\n" else "\n### ${kind.label} ($n)\n"
            if (list) h + "\n" else h
        } else {
            if (kind == NoteKind.REVIEW) "\n[리뷰]\n" else "\n[${kind.label} $n]\n"
        }
    }

    private fun item(r: NoteRow, first: Boolean, tagged: Boolean, md: Boolean, cal: Calendar, sb: StringBuilder) {
        when (r.ref.kind) {
            NoteKind.REVIEW -> {
                if (md) {
                    sb.append('\n'); blockQuote(r.body, sb)
                    sb.append("\n*").append(time(r.time, cal)).append("*\n")
                } else {
                    if (!first) sb.append('\n')
                    sb.append(norm(r.body).trimEnd('\n')).append("\n(").append(time(r.time, cal)).append(")\n")
                }
            }
            NoteKind.QUOTE -> {
                val meta = StringBuilder(64)
                if (tagged) meta.append(QuoteStyles.tag(r.style)).append(' ')
                meta.append(meta(r, md, cal, null))
                if (md) {
                    sb.append('\n'); blockQuote(r.body, sb)
                    sb.append("\n— ").append(meta)
                    memo("메모", r.note, md, "", sb)
                    sb.append('\n')
                } else {
                    if (!first) sb.append('\n')
                    sb.append('“').append(norm(r.body).trim('\n')).append("”\n  — ").append(meta)
                    memo("메모", r.note, md, "  ", sb)
                    sb.append('\n')
                }
            }
            NoteKind.BOOKMARK -> {
                val snippet = oneLine(norm(r.body)).trim()
                if (md) {
                    sb.append("- ").append(meta(r, md, cal, null))
                    if (snippet.isNotEmpty()) sb.append(" — “").append(inline(snippet, md)).append('”')
                    memo("메모", r.note, md, "  ", sb)
                } else {
                    sb.append("• ").append(meta(r, md, cal, null))
                    if (snippet.isNotEmpty()) sb.append("\n  “").append(snippet).append('”')
                    memo("메모", r.note, md, "  ", sb)
                }
                sb.append('\n')
            }
            NoteKind.LOOKUP -> {
                val word = oneLine(norm(r.word)).trim()
                val context = oneLine(norm(r.body)).trim()
                if (md) {
                    val w = inline(word, md)
                    sb.append("- **").append(w).append("**")
                    if (context.isNotEmpty()) sb.append(" — “").append(bold(inline(context, md), w)).append('”')
                    sb.append(" — ").append(meta(r, md, cal, r.app))
                    memo("뜻", r.note, md, "  ", sb)
                } else {
                    sb.append("• ").append(word)
                    if (context.isNotEmpty()) sb.append(" — “").append(context).append('”')
                    sb.append("\n  ").append(meta(r, md, cal, r.app))
                    memo("뜻", r.note, md, "  ", sb)
                }
                sb.append('\n')
            }
        }
    }

    /** "12화 과거로 · 37% · [파파고 · ]2026년 9월 12일 21:04" (chapter and percent only when known). */
    private fun meta(r: NoteRow, md: Boolean, cal: Calendar, app: String?): String {
        val sb = StringBuilder(48)
        val chapter = oneLine(norm(r.chapter)).trim()
        if (chapter.isNotEmpty()) sb.append(inline(chapter, md)).append(" · ")
        if (r.frac >= 0f && !r.frac.isNaN()) sb.append(percent(r.frac)).append("% · ")
        if (!app.isNullOrBlank()) sb.append(inline(oneLine(norm(app)).trim(), md)).append(" · ")
        sb.append(time(r.time, cal))
        return sb.toString()
    }

    /**
     * Appends a memo line ("메모: …", "뜻: …") after the current line. Markdown: the current line ends with two spaces
     * (a line break) and the label is bold; continuation lines are line breaks too. Nothing for a blank memo.
     */
    private fun memo(label: String, note: String, md: Boolean, indent: String, sb: StringBuilder) {
        val n = norm(note).trim('\n', ' ')
        if (n.isBlank()) return
        val lines = n.split('\n')
        if (md) {
            sb.append("  \n").append(indent).append("**").append(label).append(":** ")
            for (i in lines.indices) {
                if (i > 0) sb.append("  \n").append(indent)
                sb.append(mdLine(lines[i]))
            }
        } else {
            sb.append('\n').append(indent).append(label).append(": ")
            for (i in lines.indices) {
                if (i > 0) sb.append('\n').append(indent)
                sb.append(lines[i])
            }
        }
    }

    /** Every body line prefixed with "> "; empty lines inside the body become ">". */
    private fun blockQuote(body: String, sb: StringBuilder) {
        val n = norm(body).trim('\n')
        for (line in n.split('\n')) {
            val e = mdLine(line)
            if (e.isEmpty()) sb.append(">\n") else sb.append("> ").append(e).append('\n')
        }
    }

    /** Wraps the first case-insensitive hit of [word] in [text] in `**…**` (both already escaped). */
    private fun bold(text: String, word: String): String {
        if (word.isEmpty()) return text
        var i = 0
        while (i + word.length <= text.length) {
            if (text.regionMatches(i, word, 0, word.length, ignoreCase = true)) {
                return text.substring(0, i) + "**" + text.substring(i, i + word.length) + "**" + text.substring(i + word.length)
            }
            i++
        }
        return text
    }

    // ---- text rules ----

    private const val RULE = "========================================"
    internal const val DELETED_TITLE = "(삭제된 책)"
    private const val MD_SPECIAL = "\\`*_[]<>#|~=\$%&"

    /**
     * Normalised before anything else: U+FFFC and C0 controls other than `\n` removed, `\t` → space, `\r\n` / `\r` /
     * U+2028 / U+2029 → `\n`.
     */
    internal fun norm(s: String): String {
        var clean = true
        for (ch in s) if (ch == '\uFFFC' || ch == '\u2028' || ch == '\u2029' || ch == '\u007F' || (ch < ' ' && ch != '\n')) { clean = false; break }
        if (clean) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '\r' -> { sb.append('\n'); if (i + 1 < s.length && s[i + 1] == '\n') i++ }
                ch == '\u2028' || ch == '\u2029' || ch == '\n' -> sb.append('\n')
                ch == '\t' -> sb.append(' ')
                ch == '\uFFFC' || ch == '\u007F' || ch < ' ' -> {}
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    private fun oneLine(s: String): String = if (s.indexOf('\n') < 0) s else s.replace('\n', ' ')

    /**
     * Markdown escaping of book- and user-derived text (N §5.7, hub.md §10.1): normalised; every line's leading spaces
     * and tabs stripped (no indented code block); a backslash before `` \ ` * _ [ ] < > # | ~ = $ % & ``; at a line
     * start also before `-`, `+` and the `.` / `)` of "N. " / "N)" (followed by a space or the line end).
     */
    internal fun md(text: String): String {
        val n = norm(text)
        if (n.indexOf('\n') < 0) return mdLine(n)
        val lines = n.split('\n')
        val sb = StringBuilder(n.length + 16)
        for (i in lines.indices) {
            if (i > 0) sb.append('\n')
            sb.append(mdLine(lines[i]))
        }
        return sb.toString()
    }

    /** One line (already normalised) of [md]. */
    private fun mdLine(line: String): String {
        var start = 0
        while (start < line.length && (line[start] == ' ' || line[start] == '\t')) start++
        val sb = StringBuilder(line.length - start + 8)
        var i = start
        if (i < line.length && (line[i] == '-' || line[i] == '+')) {
            sb.append('\\').append(line[i]); i++
        } else {
            var d = i
            while (d < line.length && line[d] in '0'..'9') d++
            // "N." / "N)" open an ordered list only when a space or the line end follows ("1.5배" stays).
            if (d > i && d < line.length && (line[d] == '.' || line[d] == ')') && (d + 1 == line.length || line[d + 1] == ' ')) {
                sb.append(line, i, d).append('\\').append(line[d]); i = d + 1
            }
        }
        while (i < line.length) {
            val ch = line[i]
            if (MD_SPECIAL.indexOf(ch) >= 0) sb.append('\\')
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /** One-line field (title, author, chapter, word, app, search words): escaped for Markdown, as is for TXT. */
    private fun inline(s: String, md: Boolean): String = if (md) mdLine(oneLine(norm(s)).trim()) else oneLine(norm(s)).trim()

    private fun inline(s: String, format: Format): String = inline(s, format == Format.MARKDOWN)

    /** Whole percent, floored, 0..100. */
    internal fun percent(frac: Float): Int = (frac.coerceIn(0f, 1f) * 100f + 1e-4f).toInt().coerceIn(0, 100)

    /** "2026년 9월 12일 21:04" in the calendar's zone: always with the year (the file is kept). */
    internal fun time(ms: Long, cal: Calendar): String {
        cal.timeInMillis = ms
        val sb = StringBuilder(24)
        sb.append(cal.get(Calendar.YEAR)).append("년 ")
        sb.append(cal.get(Calendar.MONTH) + 1).append("월 ")
        sb.append(cal.get(Calendar.DAY_OF_MONTH)).append("일 ")
        two(sb, cal.get(Calendar.HOUR_OF_DAY)); sb.append(':')
        two(sb, cal.get(Calendar.MINUTE))
        return sb.toString()
    }

    private fun two(sb: StringBuilder, v: Int) {
        if (v < 10) sb.append('0')
        sb.append(v)
    }
}
