package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.format.TocEntry
import com.ggumtak.readeraplus.reader.ChapterIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1-11 / T1-6: TTS time counted with the reader in the background, and the "이 화 / 2화 끝까지" sleep boundaries. */
class TtsHelpersTest {

    // ------------------------------------------------------------------ SpeakClock

    @Test
    fun countsOnlyTheTimeSpentSpeaking() {
        val c = SpeakClock()
        assertNull(c.take(0L))
        c.start(1_000L)
        c.start(5_000L) // already running: the first start counts
        assertTrue(c.running)
        c.stop(11_500L) // 10.5 s
        assertFalse(c.running)
        c.stop(20_000L) // not running: nothing
        c.start(30_000L)
        c.addChars(120)
        c.addPage()
        val t = c.take(32_000L)!! // + 2 s so far, still speaking
        assertEquals(12L, t.first)
        assertEquals(1, t.second)
        assertEquals(120L, t.third)
        assertTrue(c.running)
        // The half second left over is kept for the next report.
        c.stop(32_600L)
        val u = c.take(40_000L)!!
        assertEquals(1L, u.first)
        assertEquals(0, u.second)
        assertEquals(0L, u.third)
        assertNull(c.take(50_000L))
    }

    @Test
    fun pagesAloneAreReportedAndNegativeCharsIgnored() {
        val c = SpeakClock()
        c.addChars(-5)
        c.addChars(0)
        assertNull(c.take(0L))
        c.addPage()
        assertEquals(Triple(0L, 1, 0L), c.take(0L))
    }

    // ------------------------------------------------------------------ sleep by episodes

    /** Episodes at (0,0) (0,500) (1,0) (1,800) (3,0). */
    private val chapters = ChapterIndex(
        listOf(
            TocEntry("1화", 0, 0, 0),
            TocEntry("2화", 0, 0, 500),
            TocEntry("3화", 0, 1, 0),
            TocEntry("4화", 0, 1, 800),
            TocEntry("5화", 0, 3, 0),
            TocEntry("밖", 0, 9, 0), // outside the book (4 sections): ignored
        ),
        sectionCount = 4,
    )

    @Test
    fun boundaryIsTheStartOfTheNthNextEpisode() {
        assertEquals(DocPosition(0, 500), TtsChapters.boundary(chapters, 0, 10, 1))
        assertEquals(DocPosition(1, 0), TtsChapters.boundary(chapters, 0, 10, 2))
        // At an episode's very start, "this episode" is that one.
        assertEquals(DocPosition(1, 800), TtsChapters.boundary(chapters, 1, 0, 1))
        assertEquals(DocPosition(3, 0), TtsChapters.boundary(chapters, 1, 900, 1))
        // The book ends first: no stop.
        assertNull(TtsChapters.boundary(chapters, 1, 900, 2))
        assertNull(TtsChapters.boundary(chapters, 3, 5, 1))
        assertNull(TtsChapters.boundary(chapters, 0, 0, 0))
        assertNull(TtsChapters.boundary(ChapterIndex(emptyList(), 4), 0, 0, 1))
    }

    @Test
    fun episodesLeftBeforeTheStop() {
        val stop = TtsChapters.boundary(chapters, 0, 10, 2)
        assertEquals(2, TtsChapters.left(chapters, 0, 10, stop))
        assertEquals(1, TtsChapters.left(chapters, 0, 600, stop))
        assertEquals(0, TtsChapters.left(chapters, 1, 0, stop))
        assertEquals(0, TtsChapters.left(chapters, 0, 10, null))
    }

    @Test
    fun packedPositionsCompareLikePositions() {
        assertTrue(TtsChapters.pack(0, Int.MAX_VALUE) < TtsChapters.pack(1, 0))
        assertTrue(TtsChapters.pack(2, 5) < TtsChapters.pack(2, 6))
        assertEquals(TtsChapters.pack(3, 7), TtsChapters.pack(3, 7))
    }
}
