package com.ggumtak.readeraplus.ui.notes

import com.ggumtak.readeraplus.data.NotesOrder
import com.ggumtak.readeraplus.data.NotesTab
import com.ggumtak.readeraplus.render.QuoteStyles
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure labels of the 독서 노트 hub (N §9.2, §9.6, §9.10). No Android types: every function is JVM-tested
 * (NotesTextTest). Times are formatted in [zone] (the device zone by default) at bind time, so "오늘/어제" needs no timer.
 */
object NotesText {
    private val DAYS = arrayOf("월", "화", "수", "목", "금", "토", "일")

    /** Chapter titles in the place label are cut to this many chars ("…" included). */
    const val CHAPTER_CHARS = 24
    /** A lookup's word shown as a row title: its first line, cut to this many chars ("문장 앞부분…"). */
    const val WORD_CHARS = 30
    /** Book titles in export file names: at most this many code points. */
    const val FILE_TITLE_CODEPOINTS = 40

    private fun zoned(t: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(t).atZone(zone)

    private fun two(n: Int): String = if (n < 10) "0$n" else n.toString()

    /**
     * Meta time where no day header says the day (the book orders): today "21:04", yesterday "어제 21:04", this year
     * "9월 28일", older "2025년 12월 3일".
     */
    fun time(t: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = zoned(t, zone)
        val today = zoned(now, zone).toLocalDate()
        val day = d.toLocalDate()
        return when {
            day == today -> hm(t, zone)
            day == today.minusDays(1) -> "어제 ${hm(t, zone)}"
            day.year == today.year -> "${day.monthValue}월 ${day.dayOfMonth}일"
            else -> "${day.year}년 ${day.monthValue}월 ${day.dayOfMonth}일"
        }
    }

    /** "21:04": the meta time under a day header (최신순, 오래된순), which already names the day. */
    fun hm(t: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = zoned(t, zone)
        return two(d.hour) + ":" + two(d.minute)
    }

    /** The local day of [t] (epoch day): rows under one day header share it. */
    fun dayKey(t: Long, zone: ZoneId = ZoneId.systemDefault()): Long = zoned(t, zone).toLocalDate().toEpochDay()

    /** "오늘 · 9월 30일 (화)", "어제 · 9월 29일 (월)", "9월 28일 (일)", "2025년 12월 3일 (수)". */
    fun dayHeader(t: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = zoned(t, zone).toLocalDate()
        val today = zoned(now, zone).toLocalDate()
        val dow = DAYS[day.dayOfWeek.value - 1]
        val md = "${day.monthValue}월 ${day.dayOfMonth}일 ($dow)"
        return when {
            day == today -> "오늘 · $md"
            day == today.minusDays(1) -> "어제 · $md"
            day.year == today.year -> md
            else -> "${day.year}년 $md"
        }
    }

    /** Book-order group header: "《제목》 · 12개". */
    fun bookHeader(title: String, count: Int): String = "《${title.ifBlank { "제목 없음" }}》 · ${count}개"

    /** Percent of a reading fraction: floor(frac × 100), "0%" allowed; null when unknown (frac < 0). */
    fun percent(frac: Float): Int? = if (frac < 0f || frac.isNaN()) null else Math.floor(frac.coerceAtMost(1f) * 100.0).toInt()

    /**
     * Place label: "12화 과거로 · 37%" (chapter cut at [CHAPTER_CHARS]); "37%" without a chapter; the chapter alone
     * when frac < 0; "" when both are unknown. No page numbers (they depend on the layout; the hub opens no book).
     */
    fun place(chapter: String, frac: Float): String {
        val c = chapter.trim().replace('\n', ' ').let { if (it.length > CHAPTER_CHARS) it.take(CHAPTER_CHARS - 1) + "…" else it }
        val p = percent(frac)?.let { "$it%" }
        return when {
            c.isEmpty() -> p ?: ""
            p == null -> c
            else -> "$c · $p"
        }
    }

    /** "3회" when a word was looked up more than once, else "". */
    fun countBadge(n: Int): String = if (n > 1) "${n}회" else ""

    /** The book part of a meta line: "《제목》", "《제목》(휴지통)", "《제목》(파일 없음)", or "(삭제된 책)" when unknown. */
    fun bookPart(title: String?, trashed: Boolean, missing: Boolean): String = when {
        title == null -> "(삭제된 책)"
        missing -> "《$title》(파일 없음)"
        trashed -> "《$title》(휴지통)"
        else -> "《$title》"
    }

    /** Joins the non-blank meta parts with " · " (book, place, app, time). */
    fun meta(vararg parts: String?): String {
        val sb = StringBuilder()
        for (p in parts) {
            if (p.isNullOrBlank()) continue
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(p)
        }
        return sb.toString()
    }

    /** A lookup's row title: the selection's first line; a long selection reads "문장 앞부분…". */
    fun wordTitle(word: String): String {
        val line = word.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        val cut = line.length > WORD_CHARS || word.trim().contains('\n')
        return if (!cut) line else line.take(WORD_CHARS).trimEnd() + "…"
    }

    /** The first case-insensitive hit of [word] in [text] as start..end (exclusive), or null (no bold). */
    fun boldRange(text: String, word: String): IntRange? {
        val w = word.trim()
        if (w.isEmpty()) return null
        val i = text.indexOf(w, ignoreCase = true)
        return if (i < 0) null else i until i + w.length
    }

    /** Chip of the book filter: "모든 책 ▾", or "《제목》 ✕" when filtered to one book. */
    fun scopeLabel(title: String?): String = if (title == null) "모든 책 ▾" else "《$title》 ✕"

    /** Order chip: the order's short name, "책별 · 최근 ▾" (the chooser lists the full ones). */
    fun orderChip(order: NotesOrder): String = order.short + " ▾"

    /** Colour chip: "모든 색 ▾" or "노랑 ▾". */
    fun styleChip(style: Int?): String = (if (style == null) "모든 색" else QuoteStyles.label(style)) + " ▾"

    /** Page-bar suffix: " · 128개". */
    fun countSuffix(count: Int): String = " · ${count}개"

    fun selectionTitle(n: Int): String = "${n}개 선택"

    // ------------------------------------------------------------------ empty states (N §9.6)

    /** Why the list is empty, in the order the cases are checked. */
    enum class Empty { SEARCH, ONE_BOOK, WORDS_OFF, TAB }

    fun emptyCase(tab: NotesTab, search: String, oneBook: Boolean, recordLookups: Boolean): Empty = when {
        search.isNotBlank() -> Empty.SEARCH
        oneBook -> Empty.ONE_BOOK
        tab == NotesTab.WORDS && !recordLookups -> Empty.WORDS_OFF
        else -> Empty.TAB
    }

    fun emptyText(tab: NotesTab, search: String, oneBook: Boolean, recordLookups: Boolean, bookmarkByTouch: Boolean): String =
        when (emptyCase(tab, search, oneBook, recordLookups)) {
            Empty.SEARCH -> "‘${search.trim()}’ 검색 결과가 없습니다"
            Empty.ONE_BOOK -> "이 책에는 노트가 없습니다"
            Empty.WORDS_OFF -> "찾아본 단어 기록이 꺼져 있습니다"
            Empty.TAB -> when (tab) {
                NotesTab.ALL -> "아직 노트가 없습니다\n\n읽다가 글자를 길게 눌러 ‘인용’·‘메모’를 고르거나\n" +
                    "북마크를 추가하면 여기에 모입니다"
                NotesTab.QUOTES -> "인용문이 없습니다\n\n본문을 길게 눌러 문장을 선택한 뒤 ‘인용’을 누르세요"
                NotesTab.MEMOS -> "메모가 없습니다\n\n문장을 길게 눌러 ‘메모’를 누르세요"
                NotesTab.BOOKMARKS -> "북마크가 없습니다\n\n읽는 중에 메뉴의 북마크 버튼을 누르세요" +
                    (if (bookmarkByTouch) "\n화면 오른쪽 위 모서리를 눌러도 됩니다" else "")
                NotesTab.REVIEWS -> "리뷰가 없습니다\n\n읽는 화면 ⋮ 메뉴의 ‘내 리뷰’나\n다 읽은 뒤 ‘리뷰 쓰기’로 남기세요"
                NotesTab.WORDS -> "찾아본 단어가 없습니다\n\n글자를 길게 눌러 ‘사전·번역’이나 ‘웹 검색’을 누르면\n" +
                    "찾아본 단어와 그 문장이 여기에 기록됩니다"
            }
        }

    // ------------------------------------------------------------------ dialogs and toasts (N §19)

    fun deleteSelectedMessage(n: Int, reviews: Boolean): String =
        "선택한 ${n}개를 삭제할까요?" + if (reviews) " 리뷰는 책에서 지워집니다." else ""

    fun exportedToast(n: Int): String = "노트 ${n}개를 내보냈습니다"

    // ------------------------------------------------------------------ share texts (N §9.3)

    /** Quote: the ContentsDialog.quoteShareText format ("“…”\n메모: …\n— 제목, 작가"). */
    fun shareQuote(body: String, note: String, title: String?, author: String?): String {
        val sb = StringBuilder()
        sb.append('“').append(body.trim()).append('”')
        if (note.isNotBlank()) sb.append("\n메모: ").append(note.trim())
        if (!title.isNullOrBlank()) {
            sb.append("\n— ").append(title)
            if (!author.isNullOrBlank()) sb.append(", ").append(author)
        }
        return sb.toString()
    }

    /** Bookmark: "제목 · 12화 · 37%\n“snippet”\n메모: …". */
    fun shareBookmark(title: String?, place: String, snippet: String, note: String): String {
        val sb = StringBuilder(meta(title, place))
        if (snippet.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append('“').append(snippet.trim()).append('”')
        }
        if (note.isNotBlank()) sb.append("\n메모: ").append(note.trim())
        return sb.toString()
    }

    /** Word: "비명 — “sentence” (제목)". */
    fun shareWord(word: String, sentence: String, title: String?): String {
        val sb = StringBuilder(word.trim())
        if (sentence.isNotBlank()) sb.append(" — “").append(sentence.trim()).append('”')
        if (!title.isNullOrBlank()) sb.append(" (").append(title).append(')')
        return sb.toString()
    }

    /** Review: the review and the book ("— 제목, 작가"). */
    fun shareReview(review: String, title: String?, author: String?): String {
        val sb = StringBuilder(review.trim())
        if (!title.isNullOrBlank()) {
            sb.append("\n— ").append(title)
            if (!author.isNullOrBlank()) sb.append(", ").append(author)
        }
        return sb.toString()
    }

    /**
     * Caps a TXT export for ACTION_SEND at [cap] chars, cut at an item boundary (a blank line, or a "• " list item),
     * with "\n…(나머지 N개는 ‘내보내기’로 저장하세요)". [total] = the notes written. Returns (text, cut).
     */
    fun shareCap(text: String, total: Int, cap: Int): Pair<String, Boolean> {
        if (text.length <= cap) return text to false
        var end = cap
        val blank = text.lastIndexOf("\n\n", cap)
        val item = text.lastIndexOf("\n• ", cap)
        end = maxOf(blank, item)
        if (end <= 0) end = text.lastIndexOf('\n', cap).takeIf { it > 0 } ?: cap
        val kept = text.substring(0, end).trimEnd()
        val shown = itemsIn(kept)
        val rest = (total - shown).coerceAtLeast(1)
        return "$kept\n…(나머지 ${rest}개는 ‘내보내기’로 저장하세요)" to true
    }

    /** Items started in a TXT export text: quotes (“ at a line start), list items ("• ") and reviews ("[리뷰]"). */
    internal fun itemsIn(text: String): Int {
        var n = 0
        var lineStart = true
        var prevBlank = true
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (lineStart) {
                if (c == '•' && text.startsWith("• ", i)) n++
                else if (c == '“' && prevBlank) n++
                else if (c == '[' && text.startsWith("[리뷰]", i)) n++
            }
            if (c == '\n') {
                prevBlank = lineStart
                lineStart = true
            } else {
                if (lineStart) prevBlank = false
                lineStart = false
            }
            i++
        }
        return n
    }

    // ------------------------------------------------------------------ export file names (N §5.7, §9.10)

    /** yyyyMMdd of [now] in [zone]. */
    fun dateStamp(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d: LocalDate = zoned(now, zone).toLocalDate()
        return "${d.year}${two(d.monthValue)}${two(d.dayOfMonth)}"
    }

    /**
     * "독서노트-20260930.md", or "독서노트-<제목 40자>-20260930.txt" when filtered to one book. The title loses
     * `/\:*?"<>|`, C0 controls and DEL, leading dots, trailing dots and spaces, and is cut at 40 code points
     * (never inside a surrogate pair); an empty result leaves the title out.
     */
    fun fileName(title: String?, ext: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = title?.let { cleanTitle(it) }.orEmpty()
        val stamp = dateStamp(now, zone)
        return if (t.isEmpty()) "독서노트-$stamp.$ext" else "독서노트-$t-$stamp.$ext"
    }

    internal fun cleanTitle(title: String): String {
        val sb = StringBuilder(title.length)
        for (c in title) {
            if (c.code < 0x20 || c.code == 0x7F || c in "/\\:*?\"<>|") continue
            sb.append(c)
        }
        var s = sb.toString().trim()
        s = s.trimStart('.', ' ')
        // Cut at FILE_TITLE_CODEPOINTS code points (a lone surrogate at the cut is never kept).
        val cps = s.codePointCount(0, s.length)
        if (cps > FILE_TITLE_CODEPOINTS) s = s.substring(0, s.offsetByCodePoints(0, FILE_TITLE_CODEPOINTS))
        if (s.isNotEmpty() && Character.isHighSurrogate(s.last())) s = s.dropLast(1)
        return s.trimEnd('.', ' ')
    }
}
