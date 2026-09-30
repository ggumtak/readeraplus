package com.ggumtak.readeraplus.reader.extras

import android.view.KeyEvent
import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TocTextTest {

    @Test
    fun summary() {
        assertEquals("540화 · 지금 123화", TocText.summary(560, 130, 540, 123))
        assertEquals("540화", TocText.summary(560, -1, 540, -1))
        assertEquals("목차 612개 · 지금 87번째", TocText.summary(612, 86, -1, -1))
        assertEquals("목차 612개", TocText.summary(612, -1, -1, -1))
    }

    @Test
    fun timeLeft() {
        assertEquals("남은 시간  이 화 3분 · 책 7시간 20분", TocText.timeLeft(3, 440))
        assertEquals("남은 시간  책 12시간", TocText.timeLeft(null, 12 * 60 + 5))
        assertEquals("남은 시간  이 화 1분 미만", TocText.timeLeft(0, null))
        assertNull(TocText.timeLeft(null, null))
    }

    @Test
    fun gapsLine() {
        assertEquals("빠진 화 3개 · 중복 1개 ›", TocText.gapsLine(3, 1))
        assertEquals("빠진 화 2개 ›", TocText.gapsLine(2, 0))
        assertEquals("중복 4개 ›", TocText.gapsLine(0, 4))
        assertNull(TocText.gapsLine(0, 0))
    }

    @Test
    fun filterTexts() {
        assertEquals("‘외전’ 12개", TocText.filterSummary("외전", 12))
        assertEquals("‘외전’이 들어간 제목이 없습니다", TocText.noMatch("외전"))
        assertEquals("‘그녀’가 들어간 제목이 없습니다", TocText.noMatch("그녀"))
        assertEquals("‘13’이 들어간 제목이 없습니다", TocText.noMatch("13"))
        assertEquals("‘12’가 들어간 제목이 없습니다", TocText.noMatch("12"))
        assertEquals("‘ABC’이(가) 들어간 제목이 없습니다", TocText.noMatch("ABC"))
    }

    @Test
    fun normalizeIgnoresCaseAndSpaces() {
        assertEquals("외전1화", TocText.normalize(" 외전 1 화 "))
        assertEquals("chapterone", TocText.normalize("Chapter\tONE"))
        assertEquals("가나", TocText.normalize("가　나 "))
        // The filter: normalized title contains the normalized query.
        assertEquals(true, TocText.normalize("제 12 화 외전 이야기").contains(TocText.normalize("외전이야기")))
    }

    @Test
    fun runsCollapseThreeOrMore() {
        assertEquals(listOf(57..57, 120..120, 121..121), TocText.runs(listOf(57, 120, 121)))
        assertEquals(listOf(1..1, 5..9, 11..11), TocText.runs(listOf(1, 5, 6, 7, 8, 9, 11)))
        assertEquals(emptyList<IntRange>(), TocText.runs(emptyList()))
        assertEquals("57", TocText.runLabel(57..57))
        assertEquals("101–399", TocText.runLabel(101..399))
    }

    @Test
    fun jumpTexts() {
        assertEquals("57화가 없어 58화로 이동했습니다", TocText.jumped(57, 58))
        assertEquals("541화가 없습니다", TocText.missing(541))
        assertEquals("1–540화 · 지금 123화", TocText.episodeHint(1, 540, 123))
        assertEquals("0–12화", TocText.episodeHint(0, 12, -1))
    }

    @Test
    fun bookmarkEmptyTextMentionsTheCornerOnlyWhenOn() {
        assertEquals(true, TocText.noBookmarks(true).contains("모서리"))
        assertEquals(false, TocText.noBookmarks(false).contains("모서리"))
        assertEquals(true, TocText.noBookmarks(false).startsWith("북마크가 없습니다"))
    }

    @Test
    fun currentIndexIsTheLastEntryAtOrBefore() {
        val secs = intArrayOf(0, 0, 1, 3)
        val offs = intArrayOf(0, 500, 0, -1)
        assertEquals(-1, ContentsDialog.currentIndex(intArrayOf(1), intArrayOf(10), DocPosition(0, 99)))
        assertEquals(0, ContentsDialog.currentIndex(secs, offs, DocPosition(0, 499)))
        assertEquals(1, ContentsDialog.currentIndex(secs, offs, DocPosition(0, 500)))
        assertEquals(2, ContentsDialog.currentIndex(secs, offs, DocPosition(2, 0)))
        // An unresolved anchor (-1) counts from its section's start.
        assertEquals(3, ContentsDialog.currentIndex(secs, offs, DocPosition(3, 0)))
    }

    // ------------------------------------------------------------------ ListKeys

    @Test
    fun listKeysVolumeAndPageKeys() {
        val a = AppSettings()
        assertEquals(1, ListKeys.direction(KeyEvent.KEYCODE_VOLUME_DOWN, a))
        assertEquals(-1, ListKeys.direction(KeyEvent.KEYCODE_VOLUME_UP, a))
        assertEquals(1, ListKeys.direction(KeyEvent.KEYCODE_PAGE_DOWN, a))
        assertEquals(-1, ListKeys.direction(KeyEvent.KEYCODE_PAGE_UP, a))
        assertEquals(0, ListKeys.direction(KeyEvent.KEYCODE_A, a))
        val inverted = a.copy(invertVolumeKeys = true)
        assertEquals(-1, ListKeys.direction(KeyEvent.KEYCODE_VOLUME_DOWN, inverted))
        val noVolume = a.copy(volumeKeysTurn = false)
        assertEquals(0, ListKeys.direction(KeyEvent.KEYCODE_VOLUME_DOWN, noVolume))
    }

    @Test
    fun listKeysLearnedAndBoundKeys() {
        val learned = AppSettings(nextPageKeys = setOf(KeyEvent.KEYCODE_F1), prevPageKeys = setOf(KeyEvent.KEYCODE_F2))
        assertEquals(1, ListKeys.direction(KeyEvent.KEYCODE_F1, learned))
        assertEquals(-1, ListKeys.direction(KeyEvent.KEYCODE_F2, learned))
        val bound = AppSettings(
            volumeKeysTurn = false,
            keyBindings = mapOf(
                KeyEvent.KEYCODE_VOLUME_DOWN to TapAction.NEXT_CHAPTER,
                KeyEvent.KEYCODE_VOLUME_UP to TapAction.PREV,
                KeyEvent.KEYCODE_PAGE_DOWN to TapAction.NONE,
                KeyEvent.KEYCODE_F3 to TapAction.TOC,
            ),
        )
        assertEquals(1, ListKeys.direction(KeyEvent.KEYCODE_VOLUME_DOWN, bound))
        assertEquals(-1, ListKeys.direction(KeyEvent.KEYCODE_VOLUME_UP, bound))
        // "없음(시스템에 맡김)": the system's.
        assertEquals(0, ListKeys.direction(KeyEvent.KEYCODE_PAGE_DOWN, bound))
        // Another action: the list's usual meaning of the key (none for F3).
        assertEquals(0, ListKeys.direction(KeyEvent.KEYCODE_F3, bound))
        // BACK is never a page key.
        val back = AppSettings(keyBindings = mapOf(KeyEvent.KEYCODE_BACK to TapAction.NEXT))
        assertEquals(0, ListKeys.direction(KeyEvent.KEYCODE_BACK, back))
    }

    // ------------------------------------------------------------------ 책 정보

    @Test
    fun infoVolume() {
        assertEquals("약 312만 자 · 예상 약 86시간", InfoText.volume(3_120_000, 600))
        assertEquals("약 312만 자", InfoText.volume(3_120_000, null))
        assertEquals("약 1.6만 자 · 예상 약 25분", InfoText.volume(15_500, 600))
        assertEquals("약 1만 자", InfoText.volume(10_000, 0))
        assertEquals("약 9,600자 · 예상 약 15분", InfoText.volume(9_550, 600))
        assertEquals("약 300자 · 예상 1분 미만", InfoText.volume(300, 600))
        assertNull(InfoText.volume(0, 600))
    }

    @Test
    fun infoTimeLeft() {
        assertEquals("약 7시간 20분 (이 화 3분)", InfoText.timeLeft(440, 3))
        assertEquals("약 104시간", InfoText.timeLeft(104 * 60 + 12, null))
        assertEquals("1분 미만 (이 화 1분 미만)", InfoText.timeLeft(0, 0))
        assertNull(InfoText.timeLeft(null, 3))
    }
}
