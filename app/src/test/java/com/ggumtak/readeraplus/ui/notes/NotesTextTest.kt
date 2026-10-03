package com.ggumtak.readeraplus.ui.notes

import com.ggumtak.readeraplus.data.NotesOrder
import com.ggumtak.readeraplus.data.NotesTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** N §9.2 labels, §9.6 empty states, §5.7/§9.10 file names and the share cap. */
class NotesTextTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 9, 30, 15, 42)

    @Test
    fun timeLabels() {
        assertEquals("21:04", NotesText.time(at(2026, 9, 30, 21, 4), now, zone))
        assertEquals("09:05", NotesText.time(at(2026, 9, 30, 9, 5), now, zone))
        assertEquals("어제 21:04", NotesText.time(at(2026, 9, 29, 21, 4), now, zone))
        assertEquals("9월 28일", NotesText.time(at(2026, 9, 28, 21, 4), now, zone))
        assertEquals("1월 1일", NotesText.time(at(2026, 1, 1, 0, 0), now, zone))
        assertEquals("2025.12.03", NotesText.time(at(2025, 12, 3), now, zone))
    }

    @Test
    fun dayHeaders() {
        assertEquals("오늘 · 9월 30일 (수)", NotesText.dayHeader(at(2026, 9, 30, 8), now, zone))
        assertEquals("어제 · 9월 29일 (화)", NotesText.dayHeader(at(2026, 9, 29, 23, 59), now, zone))
        assertEquals("9월 28일 (월)", NotesText.dayHeader(at(2026, 9, 28), now, zone))
        assertEquals("2025년 12월 3일 (수)", NotesText.dayHeader(at(2025, 12, 3), now, zone))
        assertEquals(NotesText.dayKey(at(2026, 9, 30, 0, 1), zone), NotesText.dayKey(at(2026, 9, 30, 23, 59), zone))
        assertTrue(NotesText.dayKey(at(2026, 9, 29, 23, 59), zone) < NotesText.dayKey(at(2026, 9, 30, 0, 0), zone))
    }

    @Test
    fun placeLabels() {
        assertEquals("12화 과거로 · 37%", NotesText.place("12화 과거로", 0.379f))
        assertEquals("37%", NotesText.place("", 0.37f))
        assertEquals("0%", NotesText.place("", 0f))
        assertEquals("100%", NotesText.place("", 1f))
        assertEquals("12화", NotesText.place("12화", -1f))
        assertEquals("", NotesText.place("", -1f))
        val long = "가".repeat(40)
        val p = NotesText.place(long, 0.5f)
        assertEquals("가".repeat(23) + "… · 50%", p)
        assertNull(NotesText.percent(-1f))
    }

    @Test
    fun badgesHeadersAndMeta() {
        assertEquals("", NotesText.countBadge(1))
        assertEquals("3회", NotesText.countBadge(3))
        assertEquals("《샘플》 · 12", NotesText.bookHeader("샘플", 12))
        assertEquals("《샘플》", NotesText.bookPart("샘플", false, false))
        assertEquals("《샘플》(휴지통)", NotesText.bookPart("샘플", true, false))
        assertEquals("《샘플》(파일 없음)", NotesText.bookPart("샘플", true, true))
        assertEquals("(삭제된 책)", NotesText.bookPart(null, false, false))
        assertEquals("인용문 · 《샘플》 · 1화 · 3% · 21:04", NotesText.meta("인용문", "《샘플》", "1화 · 3%", null, "21:04"))
        assertEquals("리뷰 · 57% · 9월 12일", NotesText.meta("리뷰", "57%", "", "9월 12일"))
    }

    @Test
    fun chips() {
        assertEquals("모든 책 ▾", NotesText.scopeLabel(null))
        assertEquals("《샘플》 ✕", NotesText.scopeLabel("샘플"))
        assertEquals("최신순 ▾", NotesText.orderChip(NotesOrder.NEWEST))
        assertEquals("모든 색 ▾", NotesText.styleChip(null))
        assertEquals("초록 ▾", NotesText.styleChip(1))
        assertEquals(" · 128개", NotesText.countSuffix(128))
        assertEquals("12개 선택", NotesText.selectionTitle(12))
    }

    @Test
    fun wordsAndBold() {
        assertEquals("비명", NotesText.wordTitle(" 비명 "))
        assertEquals("첫 줄…", NotesText.wordTitle("첫 줄\n둘째 줄"))
        val long = "가나다라마바사아자차".repeat(5)
        assertEquals(long.take(NotesText.WORD_CHARS) + "…", NotesText.wordTitle(long))
        assertEquals(5 until 7, NotesText.boldRange("…타인의 비명이 퍼졌다", "비명"))
        assertEquals(4 until 9, NotesText.boldRange("The Apple apple", "apple"))
        assertNull(NotesText.boldRange("없는 문장", "비명"))
        assertNull(NotesText.boldRange("문장", " "))
    }

    @Test
    fun emptyStates() {
        assertEquals("‘비명’와 일치하는 노트가 없습니다", NotesText.emptyText(NotesTab.ALL, " 비명 ", true, true, true))
        assertEquals("이 책에는 노트가 없습니다", NotesText.emptyText(NotesTab.QUOTES, "", true, true, true))
        assertEquals("단어 기록이 꺼져 있습니다", NotesText.emptyText(NotesTab.WORDS, "", false, false, true))
        assertEquals(NotesText.Empty.WORDS_OFF, NotesText.emptyCase(NotesTab.WORDS, "", false, false))
        assertEquals(NotesText.Empty.TAB, NotesText.emptyCase(NotesTab.QUOTES, "", false, false))
        assertTrue(NotesText.emptyText(NotesTab.ALL, "", false, true, true).startsWith("아직 모은 노트가 없습니다\n\n"))
        assertTrue(NotesText.emptyText(NotesTab.BOOKMARKS, "", false, true, true).endsWith("\n화면 오른쪽 위 모서리를 눌러도 됩니다"))
        assertEquals("북마크가 없습니다\n\n읽는 중에 메뉴의 북마크 버튼을 누르세요", NotesText.emptyText(NotesTab.BOOKMARKS, "", false, true, false))
        assertEquals("인용문이 없습니다\n\n본문을 길게 눌러 문장을 선택한 뒤 '인용'을 누르세요", NotesText.emptyText(NotesTab.QUOTES, "", false, true, true))
        for (t in NotesTab.entries) assertTrue(NotesText.emptyText(t, "", false, true, true).isNotBlank())
    }

    @Test
    fun dialogsAndToasts() {
        assertEquals("선택한 3개를 삭제할까요?", NotesText.deleteSelectedMessage(3, false))
        assertEquals("선택한 3개를 삭제할까요? 리뷰는 책에서 지워집니다.", NotesText.deleteSelectedMessage(3, true))
        assertEquals("삭제했습니다", NotesText.deletedToast(1))
        assertEquals("4개를 삭제했습니다", NotesText.deletedToast(4))
        assertEquals("인용문 5개의 색을 바꿨습니다", NotesText.recolouredToast(5))
        assertEquals("노트 12개를 내보냈습니다", NotesText.exportedToast(12))
    }

    @Test
    fun shareTexts() {
        assertEquals("“본문”\n메모: 메모\n— 제목, 작가", NotesText.shareQuote(" 본문 ", "메모", "제목", "작가"))
        assertEquals("“본문”\n— 제목", NotesText.shareQuote("본문", "", "제목", ""))
        assertEquals("제목 · 12화 · 37%\n“snippet”\n메모: 다시", NotesText.shareBookmark("제목", "12화 · 37%", "snippet", "다시"))
        assertEquals("비명 — “sentence” (제목)", NotesText.shareWord("비명", "sentence", "제목"))
    }

    @Test
    fun shareCapCutsAtAnItemBoundary() {
        val item = "“인용문 본문”\n  — 1화 · 2026-09-12 21:04\n\n"
        val text = "독서 노트\n범위: 모든 책 · 전체\n\n" + item.repeat(100)
        val (same, cut0) = NotesText.shareCap(text, 100, text.length)
        assertFalse(cut0)
        assertEquals(text, same)
        val (capped, cut) = NotesText.shareCap(text, 100, 400)
        assertTrue(cut)
        val body = capped.substringBefore("\n…(")
        assertTrue(body.length <= 400)
        assertTrue(body.endsWith("21:04"))
        val shown = NotesText.itemsIn(body)
        assertTrue(shown in 1..99)
        assertTrue(capped.endsWith("\n…(나머지 ${100 - shown}개는 '내보내기'로 저장하세요)"))
        assertEquals(3, NotesText.itemsIn("[리뷰]\n좋다\n\n“a”\n\n• b\n  “c”"))
    }

    @Test
    fun fileNames() {
        val t = at(2026, 9, 30)
        assertEquals("독서노트-20260930.md", NotesText.fileName(null, "md", t, zone))
        assertEquals("독서노트-샘플-20260930.txt", NotesText.fileName("샘플", "txt", t, zone))
        assertEquals("독서노트-ab-20260930.md", NotesText.fileName("a/b\\:*?\"<>|", "md", t, zone))
        assertEquals("독서노트-20260930.md", NotesText.fileName("...", "md", t, zone))
        assertEquals("독서노트-20260930.md", NotesText.fileName(" \u0001\u007F ", "md", t, zone))
        assertEquals("hidden", NotesText.cleanTitle("..hidden. . "))
        assertEquals("가".repeat(40), NotesText.cleanTitle("가".repeat(45)))
        // 39 BMP chars + an emoji (a surrogate pair) at code point 40 stays whole; the 41st is cut.
        val emoji = "😀"
        assertEquals("a".repeat(39) + emoji, NotesText.cleanTitle("a".repeat(39) + emoji + emoji))
        assertEquals("a".repeat(40), NotesText.cleanTitle("a".repeat(40) + emoji))
    }
}
