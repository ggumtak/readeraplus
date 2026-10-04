package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.data.Shelf
import com.ggumtak.readeraplus.format.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R2 reader additions: e-ink cadence (T1-3), footer items (T1-5, T1-7), end panel (T1-2), A5 / A8 wording. */
class ReaderR2FeaturesTest {

    @Test
    fun nightCadenceFollowsTheInvertedPage() {
        assertEquals(5, EinkCadence.everyFor(5, -1, inverted = true)) // 낮과 같게
        assertEquals(3, EinkCadence.everyFor(5, 3, inverted = true))
        assertEquals(5, EinkCadence.everyFor(5, 3, inverted = false))
        assertEquals(0, EinkCadence.everyFor(0, -1, inverted = true))
        assertEquals(10, EinkCadence.everyFor(0, 10, inverted = true))
    }

    @Test
    fun picturePagesAndTheirNeighboursAreDue() {
        val c = EinkCadence()
        assertFalse(c.imageDue(0f)) // text page
        assertTrue(c.imageDue(0.4f)) // a picture page
        assertTrue(c.imageDue(0.4f)) // the next picture page too
        assertTrue(c.imageDue(0f)) // the text page after it (the picture's ghost)
        assertFalse(c.imageDue(0f))
        assertFalse(c.imageDue(0.05f)) // a small inline picture
        assertTrue(c.imageDue(0.125f))
    }

    @Test
    fun anImageDueTurnRefreshesOnceAndRestartsTheCount() {
        val c = EinkCadence(every = 3)
        assertFalse(c.onTurn(false))
        assertTrue(c.onTurn(false, imageDue = true))
        assertFalse(c.onTurn(false))
        assertFalse(c.onTurn(false))
        assertTrue(c.onTurn(false))
        // Off by default: nothing is ever due without a cadence, a chapter refresh or picture pages switched on.
        val off = EinkCadence()
        repeat(30) { assertFalse(off.onTurn(it % 3 == 0)) }
    }

    @Test
    fun aClosedPanelCountsOneTurn() {
        val c = EinkCadence(every = 3)
        assertFalse(c.onTurn(false))
        assertFalse(c.onPanelClosed())
        assertTrue(c.onTurn(false))
        assertFalse(c.onPanelClosed())
        assertFalse(c.onPanelClosed())
        assertTrue(c.onPanelClosed())
        val off = EinkCadence(every = 0, onChapter = true)
        repeat(10) { assertFalse(off.onPanelClosed()) }
    }

    @Test
    fun episodeLabels() {
        assertEquals("123/540화", ReaderFormat.episodeLabel(true, 123, 540, 130, 560))
        // Numbers not parsed: the entry's place in the TOC.
        assertEquals("87/612", ReaderFormat.episodeLabel(false, -1, -1, 86, 612))
        assertEquals("87/612", ReaderFormat.episodeLabel(true, -1, 540, 86, 612))
        // Never "600/540화".
        assertEquals("600/600화", ReaderFormat.episodeLabel(true, 600, 540, 610, 620))
    }

    @Test
    fun timeLeft() {
        assertEquals("챕터 3분", ReaderFormat.timeLeft(false, 3))
        assertEquals("챕터 1분 미만", ReaderFormat.timeLeft(false, 0))
        assertEquals("책 7시간 20분", ReaderFormat.timeLeft(true, 440))
        assertEquals("책 104시간", ReaderFormat.timeLeft(true, 104 * 60 + 5))
        assertEquals(0, ReaderFormat.minutesFor(599, 600))
        assertEquals(1, ReaderFormat.minutesFor(600, 600))
        assertEquals(5200, ReaderFormat.minutesFor(3_120_000, 600))
        assertEquals(0, ReaderFormat.minutesFor(-5, 600))
        assertEquals(1000, ReaderFormat.minutesFor(1000, 0)) // a bad speed never divides by zero
    }

    @Test
    fun endPanelTexts() {
        assertEquals("읽은 시간 4시간 12분", ReaderFormat.readTime(4 * 3600 + 12 * 60 + 30))
        assertEquals("읽은 시간 1분 미만", ReaderFormat.readTime(20))
        assertEquals("다 읽은 책으로 표시", EndPanel.FINISHED_LABEL)
    }

    @Test
    fun indexLoadingTextOnlyForBigTxt() {
        val big = 14_800_000L
        assertEquals("목차를 만드는 중…", ReaderFormat.loadingText(true, big))
        assertEquals("목차를 만드는 중…", ReaderFormat.loadingText(true, ReaderFormat.BIG_TXT_BYTES))
        assertEquals("불러오는 중…", ReaderFormat.loadingText(true, ReaderFormat.BIG_TXT_BYTES - 1))
        assertEquals("불러오는 중…", ReaderFormat.loadingText(false, big))
    }

    @Test
    fun chapterIndexMapsToTheTocIndex() {
        val toc = listOf(
            TocEntry("표지", 1, 0),
            TocEntry("범위 밖", 1, 50),
            TocEntry("1화", 1, 1),
            TocEntry("음수", 1, -1),
            TocEntry("2화", 1, 2),
        )
        val idx = ChapterIndex(toc, 3)
        assertEquals(3, idx.size)
        assertEquals(0, idx.tocIndex(0))
        assertEquals(2, idx.tocIndex(1))
        assertEquals(4, idx.tocIndex(2))
        assertEquals("2화", idx.title(2))
    }

    @Test
    fun shelfMessagesUseTheShelfLabels() {
        assertEquals("읽을 책에 추가했습니다", shelfMessage(Shelf.TO_READ, true))
        assertEquals("다 읽은 책에서 뺐습니다", shelfMessage(Shelf.HAVE_READ, false))
    }
}
